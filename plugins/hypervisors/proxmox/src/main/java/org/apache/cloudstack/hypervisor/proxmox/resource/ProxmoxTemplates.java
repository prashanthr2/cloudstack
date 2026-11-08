// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.proxmox.resource;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.DestroyCommand;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.storage.Storage;
import com.cloud.storage.template.Processor.FormatInfo;
import com.cloud.storage.template.TemplateLocation;

/**
 * A CloudStack template on a primary storage pool is a Proxmox <b>template VM</b> (design-phase11-secondary-mounts.md, step 2; the base of linked
 * clones in design-phase12-linked-clones.md): made once per pool and template when core copies the template to the pool, cloned for every root volume,
 * and deleted when core evicts the template from the pool.
 * <p>
 * As on KVM, what core keeps in {@code template_spool_ref} ({@code install_path} and {@code local_path}) is the place of the template on the primary
 * storage: the path of the base disk of the template VM in the pool, "&lt;vmid&gt;/base-&lt;vmid&gt;-disk-0.qcow2", the form that the path of a volume
 * has. The deploy and the eviction find the template VM by its id and check that it is a template of ours whose disk is that path on that pool, so a VM
 * id that Proxmox has given to another VM since is never touched. A template VM that was removed outside CloudStack is not made again at a deploy (the
 * path says nothing of where the template came from, as on KVM a missing base file fails the deploy): the deploy fails and says so, and the template
 * is copied again once its row is removed. The copy finds a template VM that it made earlier by its name, which is derived from the pool and the file
 * on the secondary store.
 * <p>
 * Every value that CloudStack gives ends up in a root shell, so each is checked against a strict pattern first (see {@link ProxmoxSecondaryMounts}).
 */
class ProxmoxTemplates {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxTemplates.class);

    /** The name of a template VM is this, the UUID of the template in CloudStack and the first 8 characters of the UUID of the pool. */
    static final String NAME_PREFIX = "cs-tmpl-";
    private static final String MEMORY_MIB = "256";
    private static final int CREATE_ATTEMPTS = 10;
    private static final long CREATE_RETRY_WAIT_MS = 500;
    private static final long GIB = 1024L * 1024 * 1024;

    private static final Pattern TEMPLATE_UUID = Pattern.compile("^[A-Za-z0-9-]{1,36}$");
    private static final Pattern POOL_UUID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern DISK_PATH = Pattern.compile("^[0-9]+/vm-[0-9]+-[A-Za-z0-9-]+\\.qcow2$");
    /** The path of a template on primary: the base disk of a template VM, "&lt;vmid&gt;/base-&lt;vmid&gt;-disk-0.qcow2". */
    private static final Pattern TEMPLATE_PATH = Pattern.compile("^([0-9]+)/base-\\1-[A-Za-z0-9-]+\\.qcow2$");

    private final Supplier<ProxmoxApiClient> clients;
    private final String node;
    private final Supplier<ProxmoxSsh> logins;
    private final ProxmoxSecondaryMounts mounts;
    private final IntSupplier taskTimeoutSeconds;
    private final LongConsumer pauser;

    ProxmoxTemplates(Supplier<ProxmoxApiClient> clients, String node, Supplier<ProxmoxSsh> logins, ProxmoxSecondaryMounts mounts, IntSupplier taskTimeoutSeconds,
            LongConsumer pauser) {
        this.clients = clients;
        this.node = node;
        this.logins = logins;
        this.mounts = mounts;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
        this.pauser = pauser;
    }

    // ---- the path and the name ----

    /** Whether the path is that of a template VM's base disk in a pool, the form that this plugin answers a copy with. */
    static boolean isTemplatePath(String path) {
        return path != null && TEMPLATE_PATH.matcher(path).matches();
    }

    private static long vmIdOf(String path) {
        return Long.parseLong(StringUtils.substringBefore(path, "/"));
    }

    /** What names the file on the secondary store: the same store and file on the same pool make the same template VM. */
    static String sourceKey(NfsTO store, String relativePath) {
        String[] address = ProxmoxSecondaryMounts.address(store);
        String version = ProxmoxSecondaryMounts.checkVersion(store.getNfsVersion());
        return version + "|" + address[0] + "|" + address[1] + "|" + ProxmoxSecondaryMounts.relativePath(relativePath, "the template file");
    }

    /**
     * The name of the template VM of a template on a pool, which a repeated copy finds again: the UUID of the template in CloudStack and the start of the
     * pool's UUID, so that the VM can be traced to both. A template without a UUID gets a name from the pool and the file on the store instead.
     */
    static String vmName(String poolUuid, String templateUuid, String sourceKey) {
        String pool = checkedPool(poolUuid);
        if (templateUuid != null && TEMPLATE_UUID.matcher(templateUuid).matches()) {
            return NAME_PREFIX + templateUuid + "-" + StringUtils.left(pool, 8);
        }
        return NAME_PREFIX + UUID.nameUUIDFromBytes((pool + "|" + sourceKey).getBytes(StandardCharsets.UTF_8));
    }

    static String vmName(String poolUuid, String sourceKey) {
        return vmName(poolUuid, null, sourceKey);
    }

    private static String checkedPool(String poolUuid) {
        if (poolUuid == null || !POOL_UUID.matcher(poolUuid).matches()) {
            throw new IllegalArgumentException("The primary storage uuid " + poolUuid + " is not one this plugin uses");
        }
        return poolUuid;
    }

    // ---- copy to primary ----

    /** A template VM on the pool and its path. */
    private static final class OnPool {
        private final ProxmoxApiClient.VmLocation vm;
        private final String path;

        private OnPool(ProxmoxApiClient.VmLocation vm, String path) {
            this.vm = vm;
            this.path = path;
        }
    }

    /**
     * Makes the template VM for the template on this pool, unless there is one (core calls this under its lock on the template and pool, so there is one
     * caller at a time, and a repeated call after a failure finds what is there). The answer is the path that core keeps (the base disk of the template
     * VM in the pool) and the size of the image.
     */
    CopyCmdAnswer copyToPrimary(TemplateObjectTO source, TemplateObjectTO destination) {
        try {
            NfsTO store = (NfsTO) source.getDataStore();
            String relative = ProxmoxSecondaryMounts.relativePath(StringUtils.removeStart(StringUtils.defaultString(source.getPath()), "/"), "the template file");
            String poolUuid = checkedPool(((PrimaryDataStoreTO) destination.getDataStore()).getUuid());
            String name = vmName(poolUuid, source.getUuid(), sourceKey(store, relative));
            ProxmoxApiClient client = clients.get();
            try (ProxmoxSsh ssh = logins.get()) {
                ProxmoxSecondaryMounts.ImageInfo[] image = new ProxmoxSecondaryMounts.ImageInfo[1];
                OnPool template = mounts.withMount(ssh, store, true, mount -> {
                    String from = mount + "/" + relative;
                    requireFile(ssh, from);
                    image[0] = mounts.checkImage(ssh, from);
                    ProxmoxApiClient.VmLocation existing = client.findVmOrTemplateByName(name);
                    if (existing != null && ProxmoxApiClient.isTemplate(client.getVmConfig(existing.getNode(), existing.getVmid()))) {
                        OnPool found = new OnPool(existing, templatePath(client, poolUuid, existing));
                        LOGGER.info("The template {} is on the pool {} already as the template VM {} ({})", relative, poolUuid, existing.getVmid(), found.path);
                        return found;
                    }
                    return makeTemplateVm(client, ssh, poolUuid, name, from, image[0], existing);
                });
                TemplateObjectTO onPrimary = new TemplateObjectTO();
                onPrimary.setPath(template.path);
                onPrimary.setSize(image[0].getVirtualSize());
                onPrimary.setPhysicalSize(source.getPhysicalSize());
                onPrimary.setFormat(Storage.ImageFormat.QCOW2);
                return new CopyCmdAnswer(onPrimary);
            }
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("Proxmox node {} could not make the template {} on the pool: {}", node, source.getPath(), e.getMessage());
            return new CopyCmdAnswer(e.getMessage());
        }
    }

    /**
     * The template VM of a template path on a pool, or null when there is none: no VM of that id, or one that is not a template VM of ours whose base disk
     * is that path on that pool (a VM id that was given to another VM after the template VM was removed). Needs no SSH login and no secondary store.
     * <p>
     * The id and the node come from the cluster list, but whether it is a template, its name and its disk come from <b>its configuration</b>: the cluster
     * list shows the name and the template flag of a VM only after Proxmox's status daemon has reported it (some seconds after the VM was made or
     * converted), so a deploy right after the copy would otherwise find no template VM.
     */
    ProxmoxApiClient.VmLocation findTemplateVm(String poolUuid, String path) {
        if (!isTemplatePath(path)) {
            throw new IllegalArgumentException("The template path " + path + " is not one this plugin made");
        }
        String storage = ProxmoxStorageNames.storageId(checkedPool(poolUuid));
        ProxmoxApiClient client = clients.get();
        ProxmoxApiClient.VmLocation vm = client.findVmOrTemplateById(vmIdOf(path));
        if (vm == null) {
            return null;
        }
        Map<String, String> config = client.getVmConfig(vm.getNode(), vm.getVmid());
        String name = config.get("name");
        String disk = config.get(ProxmoxVmConfig.ROOT_DISK);
        if (!ProxmoxApiClient.isTemplate(config) || name == null || !name.startsWith(NAME_PREFIX) || disk == null
                || !(storage + ":" + path).equals(StringUtils.substringBefore(disk, ","))) {
            return null;
        }
        return new ProxmoxApiClient.VmLocation(vm.getVmid(), name, vm.getNode(), "stopped", true);
    }

    /** The template VM at a deploy: the one {@link #findTemplateVm} finds, or a failure that says what to do. */
    ProxmoxApiClient.VmLocation requireTemplateVm(String poolUuid, String path) {
        ProxmoxApiClient.VmLocation vm = findTemplateVm(poolUuid, path);
        if (vm == null) {
            throw new ProxmoxApiException("Proxmox has no template VM with the disk " + path + " on the pool " + ProxmoxStorageNames.storageId(poolUuid)
                    + ": it was removed outside CloudStack. Remove the template from the primary storage (its row in template_spool_ref) so that it is copied again");
        }
        return vm;
    }

    /** The path of a template VM's base disk, read from its configuration and checked to be on the pool. */
    private String templatePath(ProxmoxApiClient client, String poolUuid, ProxmoxApiClient.VmLocation vm) {
        String disk = client.getVmConfig(vm.getNode(), vm.getVmid()).get(ProxmoxVmConfig.ROOT_DISK);
        String path = ProxmoxVmConfig.diskPath(disk);
        String storage = ProxmoxStorageNames.storageId(poolUuid);
        if (disk == null || !isTemplatePath(path) || vmIdOf(path) != vm.getVmid() || !storage.equals(StringUtils.substringBefore(disk, ":"))) {
            throw new ProxmoxApiException("The template VM " + vm.getVmid() + " has no base disk on the pool " + storage + " (" + disk + ")");
        }
        return path;
    }

    /**
     * Makes the VM that caches the template: a blank disk the size of the image, replaced by the converted image (as the revert of a volume snapshot
     * replaces a file), then {@code template}. A VM of that name that is not a template is what an earlier, interrupted copy left: it is removed first.
     * Whatever fails removes the VM again.
     */
    private OnPool makeTemplateVm(ProxmoxApiClient client, ProxmoxSsh ssh, String poolUuid, String name, String from,
            ProxmoxSecondaryMounts.ImageInfo image, ProxmoxApiClient.VmLocation leftover) {
        if (leftover != null) {
            LOGGER.warn("Removing the unfinished template VM {} ({}) that an earlier copy left", name, leftover.getVmid());
            waitFor(client, client.destroyVm(leftover.getNode(), leftover.getVmid()));
        }
        String storage = ProxmoxStorageNames.storageId(poolUuid);
        long gib = Math.max(1, (image.getVirtualSize() + GIB - 1) / GIB);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("memory", MEMORY_MIB);
        params.put(ProxmoxVmConfig.ROOT_DISK, storage + ":" + gib + ",format=qcow2");
        long vmid = createVm(client, params);
        try {
            String disk = client.getVmConfig(node, vmid).get(ProxmoxVmConfig.ROOT_DISK);
            String diskPath = ProxmoxVmConfig.diskPath(disk);
            if (disk == null || !DISK_PATH.matcher(diskPath).matches()) {
                throw new ProxmoxApiException("Proxmox created the template VM " + vmid + " without a disk this plugin can fill (" + disk + ")");
            }
            replaceFile(ssh, from, ProxmoxStorageNames.mountPath(poolUuid) + "/images/" + diskPath);
            waitFor(client, client.convertToTemplate(node, vmid));
            ProxmoxApiClient.VmLocation made = new ProxmoxApiClient.VmLocation(vmid, name, node, "stopped", true);
            String path = templatePath(client, poolUuid, made);
            LOGGER.info("Made the template VM {} ({}) on {} from {}: {}", name, vmid, storage, from, path);
            return new OnPool(made, path);
        } catch (RuntimeException e) {
            destroyQuietly(client, vmid);
            throw e;
        }
    }

    /** Creates the VM with the next free id; an id that another caller was given a moment ago is tried again with a new one. */
    private long createVm(ProxmoxApiClient client, Map<String, String> params) {
        long vmid = 0;
        for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
            vmid = client.nextVmId();
            if (ProxmoxVmConfig.isReservedVmId(vmid)) {
                throw new ProxmoxApiException("Proxmox offered the VM id " + vmid + ", which is in the range 900000001 to 999999999 that CloudStack keeps for the "
                        + "owners of data volumes");
            }
            try {
                waitFor(client, client.createVm(node, vmid, params));
                return vmid;
            } catch (ProxmoxApiException e) {
                boolean taken = e.getMessage() != null && e.getMessage().contains("already exists");
                if (!taken) {
                    destroyQuietly(client, vmid);
                }
                if (!taken || attempt == CREATE_ATTEMPTS) {
                    throw e;
                }
                pauser.accept(CREATE_RETRY_WAIT_MS * attempt);
            }
        }
        throw new ProxmoxApiException("Proxmox did not create the template VM " + vmid);
    }

    /** Converts the image over the blank disk file: into a temporary file next to it first, so a failure leaves the blank disk as it was. */
    private void replaceFile(ProxmoxSsh ssh, String from, String file) {
        String temporary = file + ".tmp";
        try {
            ExecResult converted = ssh.sudo("qemu-img convert -O qcow2 " + ProxmoxSsh.quote(from) + " " + ProxmoxSsh.quote(temporary), null, timeoutMillis());
            if (!converted.isSuccess()) {
                throw new ProxmoxSshException("qemu-img could not copy " + from + ": " + converted.describeFailure());
            }
            runOrFail(ssh, "put the template into place", "mv -f " + ProxmoxSsh.quote(temporary) + " " + ProxmoxSsh.quote(file));
        } catch (RuntimeException e) {
            ssh.sudo("rm -f " + ProxmoxSsh.quote(temporary), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
            throw e;
        }
    }

    // ---- a template from a volume ----

    /**
     * Makes a template on the secondary store from a volume (core's "Create template" of a volume: a root volume of a stopped instance, or a data volume,
     * also one made from a snapshot; design-phase11-step4-template-from-volume.md). Over SSH, with the store mounted read-write for this one command:
     * {@code qemu-img convert} writes the flat qcow2 {@code <template uuid>.qcow2} into the template folder (under a temporary name, then moved), and
     * {@code template.properties} is written last, in the form CloudStack's own {@link TemplateLocation} writes. A failure removes what this call made.
     * Core refuses a volume of a running instance, so the file is not open anywhere.
     */
    CopyCmdAnswer createFromVolume(VolumeObjectTO volume, TemplateObjectTO destination, long waitSeconds) {
        try {
            if (volume.getDataStore() == null || !(volume.getDataStore() instanceof PrimaryDataStoreTO) || !(destination.getDataStore() instanceof NfsTO)) {
                throw new IllegalArgumentException("Proxmox makes a template from a volume on a primary storage onto an NFS secondary store only");
            }
            if (volume.requiresEncryption()) {
                throw new IllegalArgumentException("Templates of encrypted volumes are not supported for Proxmox");
            }
            String volumePath = volume.getPath();
            if (volumePath == null || !DISK_PATH.matcher(volumePath).matches()) {
                throw new IllegalArgumentException("The volume path " + volumePath + " is not a Proxmox qcow2 disk (expected <vmid>/vm-<vmid>-<name>.qcow2)");
            }
            NfsTO store = (NfsTO) destination.getDataStore();
            String folder = ProxmoxSecondaryMounts.relativePath(StringUtils.removeStart(StringUtils.defaultString(destination.getPath()), "/"),
                    "the template folder on the secondary store");
            String uuid = destination.getUuid() != null && TEMPLATE_UUID.matcher(destination.getUuid()).matches() ? destination.getUuid() : UUID.randomUUID().toString();
            String file = ProxmoxStorageNames.mountPath(checkedPool(((PrimaryDataStoreTO) volume.getDataStore()).getUuid())) + "/images/" + volumePath;
            int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(taskTimeoutSeconds.getAsInt(), waitSeconds) * 1000L);
            try (ProxmoxSsh ssh = logins.get()) {
                TemplateObjectTO made = mounts.withMount(ssh, store, false, mount -> writeTemplate(ssh, mount, folder, file, uuid, destination, timeout));
                LOGGER.info("Made the template {} on the secondary store from the volume {}: {}", uuid, volumePath, made.getPath());
                return new CopyCmdAnswer(made);
            }
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("Proxmox node {} could not make a template from the volume {}: {}", node, volume.getPath(), e.getMessage());
            return new CopyCmdAnswer(e.getMessage());
        }
    }

    private TemplateObjectTO writeTemplate(ProxmoxSsh ssh, String mount, String folder, String file, String uuid, TemplateObjectTO destination, int timeout) {
        requireFile(ssh, file);
        ProxmoxSecondaryMounts.ImageInfo image = mounts.checkImage(ssh, file, true);
        String directory = mount + "/" + folder;
        String fileName = uuid + ".qcow2";
        String target = directory + "/" + fileName;
        String temporary = target + ".tmp";
        String properties = directory + "/" + TemplateLocation.Filename;
        boolean existed = ssh.sudo("test -d " + ProxmoxSsh.quote(directory), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess();
        runOrFail(ssh, "make the template folder", "mkdir -p " + ProxmoxSsh.quote(directory));
        try {
            ExecResult converted = ssh.sudo("qemu-img convert -O qcow2 " + ProxmoxSsh.quote(file) + " " + ProxmoxSsh.quote(temporary), null, timeout);
            if (!converted.isSuccess()) {
                throw new ProxmoxSshException("qemu-img could not copy " + file + ": " + converted.describeFailure());
            }
            runOrFail(ssh, "put the template into place", "mv -f " + ProxmoxSsh.quote(temporary) + " " + ProxmoxSsh.quote(target));
            long physical = Long.parseLong(runOrFail(ssh, "read the size of the template", "stat -c %s " + ProxmoxSsh.quote(target)).trim());
            ssh.writeFile(properties, propertiesFile(destination, uuid, fileName, physical, image.getVirtualSize()), "644");
            TemplateObjectTO made = new TemplateObjectTO();
            made.setPath(folder + "/" + fileName);
            made.setSize(image.getVirtualSize());
            made.setPhysicalSize(physical);
            made.setFormat(Storage.ImageFormat.QCOW2);
            made.setName(uuid);
            return made;
        } catch (RuntimeException e) {
            ssh.sudo("rm -f " + ProxmoxSsh.quote(temporary) + " " + ProxmoxSsh.quote(target) + " " + ProxmoxSsh.quote(properties)
                    + (existed ? "" : " && rmdir " + ProxmoxSsh.quote(directory)), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
            throw e;
        }
    }

    /** The content of {@code template.properties}, written by CloudStack's {@link TemplateLocation} on a local temporary directory. */
    private static byte[] propertiesFile(TemplateObjectTO destination, String uuid, String fileName, long physicalSize, long virtualSize) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("cs-template-");
            TemplateLocation location = new TemplateLocation(new JavaStorageLayer(), directory.toString());
            location.create(destination.getId() > 0 ? destination.getId() : 1, true, StringUtils.defaultIfBlank(destination.getName(), uuid));
            FormatInfo info = new FormatInfo();
            info.format = Storage.ImageFormat.QCOW2;
            info.filename = fileName;
            info.size = physicalSize;
            info.virtualSize = virtualSize;
            if (!location.addFormat(info) || !location.save()) {
                throw new ProxmoxSshException("The properties of the template could not be made");
            }
            return Files.readAllBytes(directory.resolve(TemplateLocation.Filename));
        } catch (IOException e) {
            throw new ProxmoxSshException("The properties of the template could not be made: " + e.getMessage(), e);
        } finally {
            if (directory != null) {
                try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                    files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                } catch (IOException e) {
                    LOGGER.debug("Could not remove {}: {}", directory, e.getMessage());
                }
            }
        }
    }

    // ---- eviction ----

    /**
     * Core's eviction of an unused template from a pool ({@code DestroyCommand} with the stored path): the template VM is destroyed. Proxmox refuses
     * while clones depend on it; that is answered as a failure, so core keeps the row and tries again. A template VM that is gone is a success (nothing
     * is left to remove, and a VM id that another VM has now is left alone), and so is a template that was copied before template VMs (a legacy path),
     * which has nothing on the pool.
     */
    Answer destroy(DestroyCommand cmd) {
        String path = cmd.getVolume() == null ? null : cmd.getVolume().getPath();
        if (path != null && ProxmoxStorageNames.isTemplateStorageId(StringUtils.substringBefore(path, ":"))) {
            return new Answer(cmd, true, "The template " + path + " was made by an earlier version and has nothing on the pool to remove");
        }
        if (!isTemplatePath(path)) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation existing = findTemplateVm(cmd.getVolume().getPoolUuid(), path);
            if (existing == null) {
                return new Answer(cmd, true, "Proxmox has no template VM with the disk " + path + ", nothing to remove");
            }
            waitFor(client, client.destroyVm(existing.getNode(), existing.getVmid()));
            LOGGER.info("Removed the template VM {} ({}) from the pool", existing.getName(), existing.getVmid());
            return new Answer(cmd, true, "Removed the template VM " + existing.getName());
        } catch (ProxmoxApiException | IllegalArgumentException e) {
            LOGGER.warn("The template {} was not removed from the pool: {}", path, e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    // ---- small helpers ----

    private void waitFor(ProxmoxApiClient client, String upid) {
        client.waitForTask(upid, taskTimeoutSeconds.getAsInt());
    }

    private int timeoutMillis() {
        return (int) Math.min(Integer.MAX_VALUE, taskTimeoutSeconds.getAsInt() * 1000L);
    }

    private void destroyQuietly(ProxmoxApiClient client, long vmid) {
        if (vmid == 0) {
            return;
        }
        try {
            ProxmoxApiClient.VmLocation vm = client.findVmOrTemplateById(vmid);
            if (vm != null) {
                waitFor(client, client.destroyVm(vm.getNode(), vmid));
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Could not remove the partly made template VM {}: {}", vmid, e.getMessage());
        }
    }

    private static void requireFile(ProxmoxSsh ssh, String file) {
        if (!ssh.sudo("test -f " + ProxmoxSsh.quote(file), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess()) {
            throw new ProxmoxSshException("The template file " + file + " does not exist on the secondary store");
        }
    }

    private static String runOrFail(ProxmoxSsh ssh, String what, String command) {
        ExecResult result = ssh.sudo(command, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess()) {
            throw new ProxmoxSshException("Could not " + what + ": " + result.describeFailure());
        }
        return result.getStdout();
    }
}

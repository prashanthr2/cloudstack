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
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.DestroyCommand;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.Storage;

/**
 * A CloudStack template on a primary storage pool is a Proxmox <b>template VM</b> (design-phase11-secondary-mounts.md, step 2; the base of linked
 * clones in design-phase12-linked-clones.md): made once per pool and template when core copies the template to the pool, cloned for every root volume,
 * and deleted when core evicts the template from the pool.
 * <p>
 * What core keeps in {@code template_spool_ref} and hands back is only the {@code path} of the answer to the copy, so the path says where the template
 * comes from ({@link #PATH_PREFIX}, the store and the file), and the template VM is found by a name that is derived from the pool and the path, which
 * the copy, the deploy and the eviction all know. A template VM that is lost is made again from the store named in the path.
 * <p>
 * Every value that CloudStack gives ends up in a root shell, so each is checked against a strict pattern first (see {@link ProxmoxSecondaryMounts}).
 */
class ProxmoxTemplates {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxTemplates.class);

    /** The start of the path of a template on primary: "secondary:&lt;NFS version&gt;|&lt;server&gt;|&lt;export&gt;|&lt;file below the store&gt;". */
    static final String PATH_PREFIX = "secondary:";
    /** The name of a template VM is this and the UUID of the pool and the path. */
    static final String NAME_PREFIX = "cs-tmpl-";
    /** The size of the column that holds the path in the database (template_spool_ref.install_path). */
    static final int MAX_PATH_LENGTH = 255;
    private static final String MEMORY_MIB = "256";
    private static final int CREATE_ATTEMPTS = 10;
    private static final long CREATE_RETRY_WAIT_MS = 500;
    private static final long GIB = 1024L * 1024 * 1024;

    private static final Pattern POOL_UUID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern DISK_PATH = Pattern.compile("^[0-9]+/vm-[0-9]+-[A-Za-z0-9-]+\\.qcow2$");

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

    /** Where a template comes from: the parts of its path. */
    static final class Source {
        private final String version;
        private final String server;
        private final String export;
        private final String relative;

        private Source(String version, String server, String export, String relative) {
            this.version = version;
            this.server = server;
            this.export = export;
            this.relative = relative;
        }

        NfsTO store() {
            return ProxmoxSecondaryMounts.storeOf(version, server, export);
        }

        String getRelative() {
            return relative;
        }
    }

    // ---- the path and the name ----

    static boolean isTemplatePath(String path) {
        return path != null && path.startsWith(PATH_PREFIX);
    }

    /** The path of a template file on a store, as it is kept in the database. */
    static String path(NfsTO store, String relativePath) {
        String[] address = ProxmoxSecondaryMounts.address(store);
        String version = ProxmoxSecondaryMounts.checkVersion(store.getNfsVersion());
        String relative = ProxmoxSecondaryMounts.relativePath(relativePath, "the template file");
        String path = PATH_PREFIX + version + "|" + address[0] + "|" + address[1] + "|" + relative;
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("The template path " + path + " is " + path.length() + " characters long and CloudStack keeps at most "
                    + MAX_PATH_LENGTH + ": use a shorter export or template folder");
        }
        return path;
    }

    /** The parts of a template path, each checked again as the address of a store and a relative path are. */
    static Source parse(String path) {
        if (!isTemplatePath(path)) {
            throw new IllegalArgumentException("The template path " + path + " is not one this plugin made");
        }
        String[] parts = path.substring(PATH_PREFIX.length()).split("\\|", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("The template path " + path + " is not of the form secondary:<version>|<server>|<export>|<file>");
        }
        ProxmoxSecondaryMounts.storeOf(parts[0], parts[1], parts[2]);
        return new Source(ProxmoxSecondaryMounts.checkVersion(parts[0]), parts[1], parts[2], ProxmoxSecondaryMounts.relativePath(parts[3], "the template file"));
    }

    /** The name of the template VM of a template path on a pool: the same for the copy, the deploy and the eviction, which know only these two. */
    static String vmName(String poolUuid, String path) {
        return NAME_PREFIX + UUID.nameUUIDFromBytes((checkedPool(poolUuid) + "|" + path).getBytes(StandardCharsets.UTF_8));
    }

    private static String checkedPool(String poolUuid) {
        if (poolUuid == null || !POOL_UUID.matcher(poolUuid).matches()) {
            throw new IllegalArgumentException("The primary storage uuid " + poolUuid + " is not one this plugin uses");
        }
        return poolUuid;
    }

    // ---- copy to primary ----

    /**
     * Makes the template VM for the template on this pool, unless there is one (core calls this under its lock on the template and pool, so there is one
     * caller at a time, and a repeated call after a failure finds what is there). The answer is the path that core keeps and the size of the image.
     */
    CopyCmdAnswer copyToPrimary(TemplateObjectTO source, TemplateObjectTO destination) {
        try {
            NfsTO store = (NfsTO) source.getDataStore();
            String relative = ProxmoxSecondaryMounts.relativePath(StringUtils.removeStart(StringUtils.defaultString(source.getPath()), "/"), "the template file");
            String path = path(store, relative);
            String poolUuid = checkedPool(((PrimaryDataStoreTO) destination.getDataStore()).getUuid());
            String name = vmName(poolUuid, path);
            ProxmoxApiClient client = clients.get();
            try (ProxmoxSsh ssh = logins.get()) {
                ProxmoxSecondaryMounts.ImageInfo image = mounts.withMount(ssh, store, true, mount -> {
                    String from = mount + "/" + relative;
                    requireFile(ssh, from);
                    ProxmoxSecondaryMounts.ImageInfo info = mounts.checkImage(ssh, from);
                    ProxmoxApiClient.VmLocation existing = client.findVmOrTemplateByName(name);
                    if (existing != null && existing.isTemplate()) {
                        LOGGER.info("The template {} is on the pool {} already as the template VM {}", relative, poolUuid, existing.getVmid());
                    } else {
                        makeTemplateVm(client, ssh, poolUuid, name, from, info, existing);
                    }
                    return info;
                });
                TemplateObjectTO onPrimary = new TemplateObjectTO();
                onPrimary.setPath(path);
                onPrimary.setSize(image.getVirtualSize());
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
     * The template VM for a template path on a pool, made again from the store named in the path when it is gone (an administrator removed it). The
     * normal case needs no SSH login and no secondary store: the VM is found by its name.
     */
    ProxmoxApiClient.VmLocation ensureTemplateVm(String poolUuid, String path) {
        Source source = parse(path);
        String name = vmName(poolUuid, path);
        ProxmoxApiClient client = clients.get();
        ProxmoxApiClient.VmLocation existing = client.findVmOrTemplateByName(name);
        if (existing != null && existing.isTemplate()) {
            return existing;
        }
        LOGGER.warn("Proxmox has no template VM {} for {} on the pool {}: making it again from the secondary store", name, path, poolUuid);
        NfsTO store = source.store();
        try (ProxmoxSsh ssh = logins.get()) {
            return mounts.withMount(ssh, store, true, mount -> {
                String from = mount + "/" + source.getRelative();
                requireFile(ssh, from);
                return makeTemplateVm(client, ssh, poolUuid, name, from, mounts.checkImage(ssh, from), existing);
            });
        }
    }

    /**
     * Makes the VM that caches the template: a blank disk the size of the image, replaced by the converted image (as the revert of a volume snapshot
     * replaces a file), then {@code template}. A VM of that name that is not a template is what an earlier, interrupted copy left: it is removed first.
     * Whatever fails removes the VM again.
     */
    private ProxmoxApiClient.VmLocation makeTemplateVm(ProxmoxApiClient client, ProxmoxSsh ssh, String poolUuid, String name, String from,
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
            LOGGER.info("Made the template VM {} ({}) on {} from {}", name, vmid, storage, from);
            return new ProxmoxApiClient.VmLocation(vmid, name, node, "stopped", true);
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

    // ---- eviction ----

    /**
     * Core's eviction of an unused template from a pool ({@code DestroyCommand} with the stored path): the template VM is destroyed. Proxmox refuses
     * while clones depend on it; that is answered as a failure, so core keeps the row and tries again. A template VM that is gone is a success, and so is
     * a template that was copied before template VMs (a legacy path), which has nothing on the pool.
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
            String name = vmName(cmd.getVolume().getPoolUuid(), path);
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation existing = client.findVmOrTemplateByName(name);
            if (existing == null) {
                return new Answer(cmd, true, "Proxmox has no template VM " + name + ", nothing to remove");
            }
            waitFor(client, client.destroyVm(existing.getNode(), existing.getVmid()));
            LOGGER.info("Removed the template VM {} ({}) from the pool", name, existing.getVmid());
            return new Answer(cmd, true, "Removed the template VM " + name);
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

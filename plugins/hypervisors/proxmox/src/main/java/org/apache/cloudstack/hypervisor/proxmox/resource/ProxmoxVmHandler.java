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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachAnswer;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.GetVmWebsocketConsoleAnswer;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.GetVmWebsocketConsoleCommand;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.storage.ResizeVolumeAnswer;
import com.cloud.agent.api.storage.ResizeVolumeCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.vm.VirtualMachine;

/**
 * Deploying and running a VM on one Proxmox node: getting a template to the primary storage, creating the root volume (which in
 * Proxmox is a VM with its disk, see docs/design-phase3-vm-deployment.md), and start, stop, reboot and delete.
 * Every method answers with the answer type CloudStack expects for its command, and turns a failed Proxmox call into a failed
 * answer carrying Proxmox's reason.
 */
class ProxmoxVmHandler {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxVmHandler.class);
    /** A new storage entry is mounted by the node a moment after it is added, so its content is polled for a while. */
    static final int ENTRY_ACTIVATION_ATTEMPTS = 15;
    static final long ENTRY_ACTIVATION_WAIT_MS = 2000;
    /** How long a guest is given to shut down by itself before Proxmox stops it by force. */
    static final int SHUTDOWN_TIMEOUT_SECONDS = 60;
    /** The VM is created with this much memory and a small CPU count; the real size is set when it is started. */
    private static final String CREATE_MEMORY_MIB = "256";
    /** Several VMs created at once can be given the same free id (Proxmox does not reserve it), so a taken id is retried with a short pause. */
    private static final int CREATE_ATTEMPTS = 10;
    private static final long CREATE_RETRY_WAIT_MS = 500;
    private static final long GIB = 1024L * 1024 * 1024;
    /** How often the real state of a VM is looked at while waiting for a start, a stop or a move; an HA request is only queued by its task. */
    static final long VM_POLL_MS = 2000;
    /** The comment of the HA resources this plugin makes; one without it was made by an administrator and is never changed. */
    static final String HA_COMMENT = "CloudStack";
    /** How long after the end of a migration timeout a mark left by PrepareForMigrationCommand is still believed. */
    static final long MARK_MARGIN_MS = 300_000;

    private final Supplier<ProxmoxApiClient> clients;
    private final String node;
    /** The address of this node and the cluster's API port, for the websocket URL of a console on this node. */
    private final String nodeAddress;
    private final int apiPort;
    /** The value of the Authorization header that proves the API token, "PVEAPIToken=user@realm!token=secret". */
    private final Supplier<String> authorization;
    private final LongConsumer pauser;
    private final IntSupplier taskTimeoutSeconds;
    private final IntSupplier migrationTimeoutSeconds;
    private final BooleanSupplier manageHa;
    private LongSupplier clock = System::currentTimeMillis;
    /**
     * VMs this node was asked to prepare for a migration (name to the time the mark ends). A stop for such a VM that is running on another
     * node is the clean-up of a failed migration, see {@link #leftAloneAsMigrationCleanup}.
     */
    private final Map<String, Long> migrationMarks = new ConcurrentHashMap<>();
    private volatile ProxmoxSystemVms systemVms;
    private volatile Supplier<String> controlBridge = () -> "cloud0";

    ProxmoxVmHandler(Supplier<ProxmoxApiClient> clients, String node, String nodeAddress, int apiPort, Supplier<String> authorization,
            LongConsumer pauser, IntSupplier taskTimeoutSeconds) {
        this(clients, node, nodeAddress, apiPort, authorization, pauser, taskTimeoutSeconds, () -> 3600, () -> false);
    }

    ProxmoxVmHandler(Supplier<ProxmoxApiClient> clients, String node, String nodeAddress, int apiPort, Supplier<String> authorization,
            LongConsumer pauser, IntSupplier taskTimeoutSeconds, IntSupplier migrationTimeoutSeconds, BooleanSupplier manageHa) {
        this.clients = clients;
        this.nodeAddress = nodeAddress;
        this.apiPort = apiPort;
        this.authorization = authorization;
        this.node = node;
        this.pauser = pauser;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
        this.migrationTimeoutSeconds = migrationTimeoutSeconds;
        this.manageHa = manageHa;
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** What happens after a system VM has started (the guest agent, the boot arguments, waiting for the VM). Without it system VMs are started but not set up. */
    void setSystemVms(ProxmoxSystemVms systemVms) {
        this.systemVms = systemVms;
    }

    /** The name of the node's host-only bridge for the control NIC of system VMs (the setting {@code proxmox.control.bridge}). */
    void setControlBridge(Supplier<String> controlBridge) {
        this.controlBridge = controlBridge;
    }

    /** A system VM cannot start on a node that lacks the control bridge: the message says what to do about it. */
    private void checkControlBridge(ProxmoxApiClient client, String bridge) {
        for (ProxmoxApiClient.NodeBridge existing : client.listBridges(node)) {
            if (existing.getName().equals(bridge)) {
                return;
            }
        }
        throw new ProxmoxApiException(String.format("Proxmox node %s has no control bridge '%s', which the control NIC of a system VM or router needs. "
                + "Create it as described in the Proxmox plugin requirements (\"The link-local control bridge\"), on every node.", node, bridge));
    }

    private void waitFor(ProxmoxApiClient client, String upid) {
        client.waitForTask(upid, taskTimeoutSeconds.getAsInt());
    }

    /**
     * Waits until the VM is as wanted, looking at its real state: for a VM under Proxmox HA the start, stop and migrate calls end their task at
     * once and the work follows. The node the VM is expected on is asked first (that answer is exact; the cluster list can be seconds old),
     * then the cluster list, which also finds a VM that moved. A VM that is gone counts as stopped.
     */
    private void waitForVm(ProxmoxApiClient client, long vmid, String expectedNode, String what, int timeoutSeconds,
            Predicate<ProxmoxApiClient.VmLocation> done) {
        long attempts = Math.max(1, timeoutSeconds * 1000L / VM_POLL_MS);
        for (long attempt = 0; attempt < attempts; attempt++) {
            ProxmoxApiClient.VmLocation location = null;
            String status = expectedNode == null ? null : client.getVmStatus(expectedNode, vmid);
            if (status != null) {
                location = new ProxmoxApiClient.VmLocation(vmid, null, expectedNode, status);
            } else {
                location = client.findVm(vmid);
            }
            if (done.test(location)) {
                return;
            }
            pauser.accept(VM_POLL_MS);
        }
        throw new ProxmoxApiException(String.format("Proxmox VM %d did not %s within %d seconds", vmid, what, timeoutSeconds));
    }

    private static boolean isStopped(ProxmoxApiClient.VmLocation location) {
        return location == null || !location.isRunning();
    }

    // ---- copy: template to primary storage, and template to root volume ----

    Answer copy(CopyCommand cmd) {
        DataTO src = cmd.getSrcTO();
        DataTO dest = cmd.getDestTO();
        try {
            if (src instanceof TemplateObjectTO && src.getDataStore() instanceof NfsTO && dest instanceof TemplateObjectTO
                    && dest.getDataStore() instanceof PrimaryDataStoreTO) {
                return copyTemplateToPrimary((TemplateObjectTO) src);
            }
            if (src instanceof TemplateObjectTO && src.getDataStore() instanceof PrimaryDataStoreTO && dest instanceof VolumeObjectTO) {
                return createRootVolume((TemplateObjectTO) src, (VolumeObjectTO) dest);
            }
            if (src instanceof VolumeObjectTO && dest instanceof VolumeObjectTO && src.getDataStore() instanceof PrimaryDataStoreTO
                    && dest.getDataStore() instanceof PrimaryDataStoreTO) {
                return copyDataVolume((VolumeObjectTO) src, (VolumeObjectTO) dest);
            }
            return new CopyCmdAnswer("Copying " + (src == null ? null : src.getObjectType()) + " to " + (dest == null ? null : dest.getObjectType())
                    + " is not supported for Proxmox");
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not complete {}: {}", node, cmd.getClass().getSimpleName(), e.getMessage());
            return new CopyCmdAnswer(e.getMessage());
        }
    }

    /**
     * Copies a data volume that no running VM uses to another primary storage (CloudStack's "migrate volume" of a detached volume). The holder VM of
     * the account lists the volume, and Proxmox copies that entry to the other storage (move_disk of an "unusedN" entry, which needs no running VM).
     * The source is not removed here: it stays listed as another "unusedN" and CloudStack's DeleteCommand for the old volume takes it away, so a
     * failed copy loses nothing. The new volume is named by Proxmox ("&lt;holder&gt;/vm-&lt;holder&gt;-disk-&lt;n&gt;.qcow2") and the answer carries it.
     */
    private Answer copyDataVolume(VolumeObjectTO src, VolumeObjectTO dest) {
        if (!isHolderAccount(src.getAccountId())) {
            return new CopyCmdAnswer("The volume " + src.getUuid() + " has no account that Proxmox can hold data volumes for");
        }
        ProxmoxApiClient client = clients.get();
        String destStorage = ProxmoxStorageNames.storageId(((PrimaryDataStoreTO) dest.getDataStore()).getUuid());
        String srcId = resolveVolumeId(client, src);
        if (srcId.startsWith(destStorage + ":")) {
            return new CopyCmdAnswer("The volume " + src.getUuid() + " is on " + destStorage + " already");
        }
        if (client.getImageInfo(node, srcId) == null) {
            return new CopyCmdAnswer("Proxmox has no disk image " + srcId + " to copy");
        }
        long holderVmid = ProxmoxVmConfig.holderVmId(src.getAccountId());
        ProxmoxHolderVms holders = new ProxmoxHolderVms(client, taskTimeoutSeconds);
        String newId = holders.withLock(holderVmid, () -> {
            ProxmoxApiClient.VmLocation holder = holders.ensureHolder(src.getAccountId(), node);
            String entry = holders.adopt(holder, srcId);
            client.waitForTask(client.moveDisk(node, holderVmid, entry, destStorage, false), migrationTimeoutSeconds.getAsInt());
            return ProxmoxHolderVms.volumeIdOf(client.getVmConfig(node, holderVmid).get(entry));
        });
        if (newId == null || !newId.startsWith(destStorage + ":")) {
            return new CopyCmdAnswer("Proxmox copied " + srcId + " but the holder VM " + holderVmid + " does not list the copy on " + destStorage);
        }
        ProxmoxApiClient.ImageInfo copy = client.getImageInfo(node, newId);
        VolumeObjectTO moved = new VolumeObjectTO();
        moved.setPath(StringUtils.substringAfter(newId, ":"));
        moved.setSize(copy == null ? src.getSize() : Long.valueOf(copy.getSize()));
        moved.setFormat(Storage.ImageFormat.QCOW2);
        LOGGER.info("Copied data volume {} to {}", srcId, newId);
        return new CopyCmdAnswer(moved);
    }

    /**
     * Nothing is copied. The secondary store is added to the Proxmox cluster as one read-only NFS entry, which is the only mount, and the
     * account's template folder ("template/tmpl/&lt;account&gt;") as a directory entry on that mount, shared by the templates of the account.
     * The answer names the template file in it ("&lt;entry&gt;:&lt;template id&gt;/&lt;file&gt;"); the root volume is created from that file
     * (createRootVolume).
     */
    private Answer copyTemplateToPrimary(TemplateObjectTO template) {
        NfsTO secondary = (NfsTO) template.getDataStore();
        URI url = URI.create(StringUtils.removeEnd(secondary.getUrl(), "/"));
        String server = url.getHost();
        String export = url.getPath();
        // template/tmpl/<account>/<template id>/<file>
        String[] parts = StringUtils.removeStart(StringUtils.defaultString(template.getPath()), "/").split("/");
        int count = parts.length;
        if (StringUtils.isBlank(server) || StringUtils.isBlank(export) || count < 3 || !StringUtils.isNumeric(parts[count - 2])
                || parts[count - 1].isEmpty() || template.getPath().contains("..")) {
            return new CopyCmdAnswer(String.format("Cannot find the template file from the secondary store %s and the path %s: "
                    + "expected <folder>/<template id>/<file>", secondary.getUrl(), template.getPath()));
        }
        String folder = String.join("/", Arrays.copyOfRange(parts, 0, count - 2));

        ProxmoxApiClient client = clients.get();
        String mountPath = ensureSecondaryMount(client, secondary, server, export);
        String id = ProxmoxStorageNames.templateStorageId(server, export, folder);
        ensureStorage(client, id, () -> client.createTemplateDirStorage(id, mountPath, folder));
        // The folders of a template have a short number as their name, which Proxmox does not list, so the file itself cannot be looked
        // up; what can be checked is that the entry is usable on this node. A file that is not there fails the creation of the VM later.
        if (!waitForStorage(client, id)) {
            return new CopyCmdAnswer(String.format("Proxmox storage %s for the templates in %s is not active on node %s", id, folder, node));
        }
        TemplateObjectTO onPrimary = new TemplateObjectTO();
        onPrimary.setPath(id + ":" + parts[count - 2] + "/" + parts[count - 1]);
        onPrimary.setSize(template.getSize() != null && template.getSize() > 0 ? template.getSize() : template.getPhysicalSize());
        onPrimary.setFormat(Storage.ImageFormat.QCOW2);
        return new CopyCmdAnswer(onPrimary);
    }

    /**
     * Makes sure the cluster has the NFS entry that mounts the secondary store (the only mount; the template and ISO entries are directories on it),
     * and that this node has it mounted. Returns the mount path.
     */
    private String ensureSecondaryMount(ProxmoxApiClient client, NfsTO secondary, String server, String export) {
        String options = StringUtils.isBlank(secondary.getNfsVersion()) ? "ro" : "vers=" + secondary.getNfsVersion() + ",ro";
        String storeId = ProxmoxStorageNames.secondaryStorageId(server, export);
        String mountPath = ProxmoxStorageNames.secondaryMountPath(storeId);
        ensureStorage(client, storeId, () -> client.createSecondaryNfsStorage(storeId, server, export, mountPath, options));
        if (!waitForStorage(client, storeId)) {
            throw new ProxmoxApiException(String.format("Proxmox node %s did not mount %s:%s as storage %s: check that the NFS server exports the "
                    + "secondary store to the Proxmox nodes", node, server, export, storeId));
        }
        return mountPath;
    }

    /** Creates the entry unless it exists; one that another host's command created a moment ago counts as existing. */
    private void ensureStorage(ProxmoxApiClient client, String id, Runnable create) {
        if (client.findStorage(id) != null) {
            return;
        }
        try {
            create.run();
            LOGGER.info("Created Proxmox storage {}", id);
        } catch (ProxmoxApiException e) {
            if (client.findStorage(id) == null) {
                throw e;
            }
        }
    }

    private boolean waitForStorage(ProxmoxApiClient client, String storageId) {
        for (int attempt = 0; attempt < ENTRY_ACTIVATION_ATTEMPTS; attempt++) {
            try {
                if (client.getStorageStatus(node, storageId).isActive()) {
                    return true;
                }
            } catch (ProxmoxApiException e) {
                LOGGER.debug("Storage {} is not ready on node {} yet: {}", storageId, node, e.getMessage());
            }
            pauser.accept(ENTRY_ACTIVATION_WAIT_MS);
        }
        return false;
    }

    /**
     * The root volume of a VM is created as the VM itself, with the template file imported as its disk. The volume path is the disk's
     * place in the primary storage ("&lt;vmid&gt;/vm-&lt;vmid&gt;-disk-0.qcow2"), which carries the VM id the later commands need.
     */
    private Answer createRootVolume(TemplateObjectTO template, VolumeObjectTO volume) {
        String templateVolume = template.getPath();
        String templateEntry = StringUtils.substringBefore(StringUtils.defaultString(templateVolume), ":");
        if (!ProxmoxStorageNames.isTemplateStorageId(templateEntry) || !templateVolume.contains(":")) {
            return new CopyCmdAnswer("The template path " + templateVolume + " is not a template volume of a Proxmox storage entry");
        }
        ProxmoxApiClient client = clients.get();
        if (client.findStorage(templateEntry) == null) {
            return new CopyCmdAnswer(String.format("Proxmox has no storage entry %s for the template %s: it was removed; remove the template "
                    + "from the primary storage so that it is made available again", templateEntry, templateVolume));
        }
        String poolStorage = ProxmoxStorageNames.storageId(((PrimaryDataStoreTO) volume.getDataStore()).getUuid());
        return new CopyCmdAnswer(createVmWithRootDisk(client, volume, poolStorage + ":0,import-from=" + templateVolume + ",format=qcow2"));
    }

    /**
     * Creates a volume on the primary storage. A root volume is a blank VM (a VM deployed from an ISO): the same VM as for a template, with an empty disk
     * of the volume's size rounded up to whole GiB. A data volume is a disk image owned by the account's holder vmid (see createDataVolume).
     */
    Answer createObject(CreateObjectCommand cmd) {
        DataTO data = cmd.getData();
        if (!(data instanceof VolumeObjectTO) || !(data.getDataStore() instanceof PrimaryDataStoreTO)) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
        VolumeObjectTO volume = (VolumeObjectTO) data;
        if (volume.getVolumeType() != Volume.Type.ROOT && volume.getVolumeType() != Volume.Type.DATADISK) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
        if (volume.getSize() == null || volume.getSize() <= 0) {
            return new CreateObjectAnswer("The volume has no size");
        }
        try {
            String poolStorage = ProxmoxStorageNames.storageId(((PrimaryDataStoreTO) volume.getDataStore()).getUuid());
            long gib = (volume.getSize() + GIB - 1) / GIB;
            if (volume.getVolumeType() == Volume.Type.DATADISK) {
                return new CreateObjectAnswer(createDataVolume(clients.get(), volume, poolStorage, gib));
            }
            return new CreateObjectAnswer(createVmWithRootDisk(clients.get(), volume, poolStorage + ":" + gib + ",format=qcow2"));
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not create the volume: {}", node, e.getMessage());
            return new CreateObjectAnswer(e.getMessage());
        }
    }

    /**
     * A data volume is a disk image owned by the holder vmid of the account ("900000000 + account id"), named after the CloudStack volume, so it belongs
     * to no VM: it can be attached to any, survives the deletion of an instance, and leaves no "unused" entry when it is detached.
     * Idempotent: an image that exists already (a repeated command) is answered as it is.
     */
    private VolumeObjectTO createDataVolume(ProxmoxApiClient client, VolumeObjectTO volume, String poolStorage, long gib) {
        String path = ProxmoxVmConfig.dataVolumePath(volume.getAccountId(), volume.getUuid());
        String volumeId = poolStorage + ":" + path;
        ProxmoxApiClient.ImageInfo existing = client.getImageInfo(node, volumeId);
        long size;
        if (existing == null) {
            client.allocateImage(node, poolStorage, ProxmoxVmConfig.holderVmId(volume.getAccountId()), StringUtils.substringAfter(path, "/"), gib);
            size = gib * GIB;
            LOGGER.info("Created Proxmox data volume {}", volumeId);
        } else {
            size = existing.getSize();
        }
        VolumeObjectTO created = new VolumeObjectTO();
        created.setPath(path);
        created.setSize(size);
        created.setFormat(Storage.ImageFormat.QCOW2);
        return created;
    }

    /**
     * Creates the Proxmox VM that a root volume is, with {@code diskOption} as its root disk ("&lt;pool entry&gt;:0,import-from=..." for a template,
     * "&lt;pool entry&gt;:&lt;GiB&gt;" for a blank disk), grows the disk to the volume's size, and returns the volume as CloudStack stores it. The volume
     * path is the disk's place in the primary storage ("&lt;vmid&gt;/vm-&lt;vmid&gt;-disk-0.qcow2"), which carries the VM id the later commands need.
     */
    private VolumeObjectTO createVmWithRootDisk(ProxmoxApiClient client, VolumeObjectTO volume, String diskOption) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", StringUtils.defaultIfBlank(volume.getVmName(), "cs-" + volume.getUuid()));
        params.put("memory", CREATE_MEMORY_MIB);
        params.put(ProxmoxVmConfig.ROOT_DISK, diskOption);

        long vmid = 0;
        for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
            vmid = client.nextVmId();
            if (ProxmoxVmConfig.isReservedVmId(vmid)) {
                throw new ProxmoxApiException("Proxmox offered the VM id " + vmid + ", which is in the range 900000001 to 999999999 that CloudStack keeps for the owners of data volumes");
            }
            try {
                waitFor(client, client.createVm(node, vmid, params));
                break;
            } catch (ProxmoxApiException e) {
                // the id is not reserved: another caller may have been given it a moment ago
                boolean taken = e.getMessage() != null && e.getMessage().contains("already exists");
                destroyQuietly(client, vmid, taken);
                if (!taken || attempt == CREATE_ATTEMPTS) {
                    throw e;
                }
                pauser.accept(CREATE_RETRY_WAIT_MS * attempt);
            }
        }
        Map<String, String> config = client.getVmConfig(node, vmid);
        String disk = config.get(ProxmoxVmConfig.ROOT_DISK);
        if (disk == null) {
            destroyQuietly(client, vmid, false);
            throw new ProxmoxApiException("Proxmox created VM " + vmid + " without a root disk");
        }
        long size = ProxmoxVmConfig.diskSizeBytes(disk);
        if (volume.getSize() != null && volume.getSize() > size) {
            try {
                waitFor(client, client.resizeDisk(node, vmid, ProxmoxVmConfig.ROOT_DISK, Long.toString(volume.getSize())));
                size = volume.getSize();
            } catch (ProxmoxApiException e) {
                destroyQuietly(client, vmid, false);
                throw e;
            }
        }
        VolumeObjectTO created = new VolumeObjectTO();
        created.setPath(ProxmoxVmConfig.diskPath(disk));
        created.setSize(size);
        created.setFormat(Storage.ImageFormat.QCOW2);
        LOGGER.info("Created Proxmox VM {} ({}) with root disk {} on node {}", vmid, params.get("name"), created.getPath(), node);
        return created;
    }

    /** Removes what a failed creation may have left behind. Does nothing when the id belongs to someone else's VM ({@code idWasTaken}). */
    private void destroyQuietly(ProxmoxApiClient client, long vmid, boolean idWasTaken) {
        if (idWasTaken || vmid == 0) {
            return;
        }
        try {
            ProxmoxApiClient.VmLocation vm = client.findVm(vmid);
            if (vm != null) {
                waitFor(client, client.destroyVm(vm.getNode(), vmid));
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Could not remove the partly created Proxmox VM {}: {}", vmid, e.getMessage());
        }
    }

    // ---- start, stop, reboot ----

    Answer start(StartCommand cmd) {
        VirtualMachineTO vm = cmd.getVirtualMachine();
        try {
            Long vmid = rootVmId(vm);
            if (vmid == null) {
                return new StartAnswer(cmd, "The root volume of " + vm.getName() + " does not belong to a Proxmox VM");
            }
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVm(vmid);
            if (location == null) {
                return new StartAnswer(cmd, "Proxmox has no VM " + vmid + " for " + vm.getName());
            }
            if (location.isRunning()) {
                return new StartAnswer(cmd);
            }
            if (!node.equals(location.getNode())) {
                // the VM was created on whichever node got the volume command; a stopped VM on shared storage only moves its configuration
                LOGGER.info("Moving Proxmox VM {} from node {} to {}", vmid, location.getNode(), node);
                waitFor(client, client.migrateVm(location.getNode(), vmid, node));
                // for a VM under Proxmox HA the task only queued the move
                waitForVm(client, vmid, null, "move to node " + node, taskTimeoutSeconds.getAsInt(), l -> l != null && node.equals(l.getNode()));
            }
            Map<String, String> cdroms = cdromDrives(client, vm);
            String rootDrive = ProxmoxVmConfig.rootDiskDrive(vm);
            String control = null;
            if (ProxmoxVmConfig.isSystemVm(vm)) {
                control = controlBridge.get();
                checkControlBridge(client, control);
            }
            if (systemVms != null) {
                String problem = systemVms.beforeStart(client, vm, node);
                if (problem != null) {
                    LOGGER.error("Proxmox node {} cannot start {}: {}", node, vm.getName(), problem);
                    return new StartAnswer(cmd, problem);
                }
            }
            Map<String, String> options = ProxmoxVmConfig.startOptions(vm, cpuCapacityMhz(client), cdroms, rootDrive, control);
            Map<String, String> dataDrives = dataDrives(client, vm, rootDrive);
            giveBackStaleDataDisks(client, location, vm, rootDrive, dataDrives);
            Map<String, String> current = client.getVmConfig(node, vmid);
            moveRootDisk(options, current, rootVolumePath(vm), rootDrive, vm.getName());
            applyRootDriveOptions(options, current, vm, rootDrive);
            options.putAll(dataDrives);
            // a data volume that the VM has from an earlier start but that the command does not list was detached: it must not stay
            for (Map.Entry<String, String> existing : current.entrySet()) {
                if (existing.getKey().matches("(scsi|virtio|sata)\\d+") && ProxmoxVmConfig.isHolderVolume(existing.getValue())
                        && !dataDrives.containsKey(existing.getKey()) && !existing.getKey().equals(rootDrive)) {
                    addDelete(options, existing.getKey());
                }
            }
            // a drive the VM has from an earlier start but that the command does not list was detached: it must not keep its disc
            for (Map.Entry<String, String> existing : current.entrySet()) {
                if (Arrays.asList(ProxmoxVmConfig.CDROM_DRIVES).contains(existing.getKey()) && ProxmoxVmConfig.isCdrom(existing.getValue())
                        && !cdroms.containsKey(existing.getKey())) {
                    options.put(existing.getKey(), ProxmoxVmConfig.EMPTY_CDROM);
                }
            }
            client.updateVmConfig(node, vmid, options);
            waitFor(client, client.startVm(node, vmid));
            waitForVm(client, vmid, node, "start on node " + node, taskTimeoutSeconds.getAsInt(), l -> l != null && node.equals(l.getNode()) && l.isRunning());
            applyHaFlag(client, vm, vmid);
            if (ProxmoxVmConfig.isSystemVm(vm) && systemVms != null) {
                String problem = systemVms.afterStart(client, vm, node, vmid);
                if (problem != null) {
                    LOGGER.error("Proxmox node {} started {} but it is not usable: {}", node, vm.getName(), problem);
                    return new StartAnswer(cmd, problem);
                }
            }
            return new StartAnswer(cmd);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not start {}: {}", node, vm.getName(), e.getMessage());
            return new StartAnswer(cmd, e.getMessage());
        }
    }

    /**
     * The CD-ROM drives of a start command: for every ISO disk (CloudStack sends one per slot, an empty one where nothing is attached) the drive and
     * its value; the ISOs get their storage entry first.
     */
    private Map<String, String> cdromDrives(ProxmoxApiClient client, VirtualMachineTO vm) {
        Map<String, String> drives = new LinkedHashMap<>();
        if (vm.getDisks() == null) {
            return drives;
        }
        for (DiskTO disk : vm.getDisks()) {
            if (disk.getType() != Volume.Type.ISO) {
                continue;
            }
            TemplateObjectTO iso = disk.getData() instanceof TemplateObjectTO ? (TemplateObjectTO) disk.getData() : null;
            drives.put(ProxmoxVmConfig.cdromDrive(disk.getDiskSeq()),
                    iso == null || StringUtils.isBlank(iso.getPath()) ? ProxmoxVmConfig.EMPTY_CDROM : ProxmoxVmConfig.cdromValue(isoVolume(client, iso)));
        }
        return drives;
    }

    /** What the node can deliver, cores times MHz; 0 when unknown. */
    private long cpuCapacityMhz(ProxmoxApiClient client) {
        ProxmoxApiClient.NodeStatus status = client.getNodeStatus(node);
        return status == null ? 0 : status.getCpus() * status.getCpuMhz();
    }

    /**
     * The root disk is created on scsi0; a guest without a driver for that (the detail "rootDiskController") has it on another drive. Proxmox moves
     * a disk between drives in one call that deletes the old drive and sets the new one to the same volume (lab I9); the size is kept.
     */
    private void moveRootDisk(Map<String, String> options, Map<String, String> current, String volumePath, String wanted, String vmName) {
        String drive = ProxmoxVmConfig.rootDriveOf(current, volumePath);
        if (drive == null) {
            if (!ProxmoxVmConfig.ROOT_DISK.equals(wanted)) {
                throw new ProxmoxApiException("The root disk " + volumePath + " of " + vmName + " is not on a drive of the VM: cannot move it to " + wanted);
            }
            return;
        }
        if (!drive.equals(wanted)) {
            addDelete(options, drive);
            options.put(wanted, StringUtils.substringBefore(current.get(drive), ","));
            LOGGER.info("Moving the root disk of {} from {} to {}", vmName, drive, wanted);
        }
    }

    /** Adds a drive to the list of settings a configuration change deletes. */
    private static void addDelete(Map<String, String> options, String drive) {
        String deletes = options.get("delete");
        options.put("delete", deletes == null ? drive : deletes + "," + drive);
    }

    /**
     * The limits and the cache mode of the disk offering are options of the root drive as well. They are applied when the VM starts, and only when
     * the offering has some and the drive does not have them yet (the drive is set again with its volume, and Proxmox adds the size itself).
     */
    private void applyRootDriveOptions(Map<String, String> options, Map<String, String> current, VirtualMachineTO vm, String rootDrive) {
        VolumeObjectTO root = rootVolume(vm);
        if (root == null) {
            return;
        }
        String wanted = ProxmoxVmConfig.driveOptions(root);
        if (wanted.isEmpty()) {
            return;
        }
        String base = options.containsKey(rootDrive) ? options.get(rootDrive) : current.get(rootDrive);
        if (base == null || (!options.containsKey(rootDrive) && ProxmoxVmConfig.driveHasOptions(base, wanted))) {
            return;
        }
        options.put(rootDrive, StringUtils.substringBefore(base, ",") + wanted);
    }

    /** The drive and value for every data volume of a start command; fails on a slot that is used twice or that the controller does not have. */
    private Map<String, String> dataDrives(ProxmoxApiClient client, VirtualMachineTO vm, String rootDrive) {
        Map<String, String> drives = new LinkedHashMap<>();
        if (vm.getDisks() == null) {
            return drives;
        }
        for (DiskTO disk : vm.getDisks()) {
            if (disk.getType() != Volume.Type.DATADISK || !(disk.getData() instanceof VolumeObjectTO)) {
                continue;
            }
            VolumeObjectTO volume = (VolumeObjectTO) disk.getData();
            String drive = ProxmoxVmConfig.dataDrive(disk.getDiskSeq(), vm.getDetails());
            if (drive.equals(rootDrive) || drives.containsKey(drive)) {
                throw new ProxmoxApiException("The drive " + drive + " of " + vm.getName() + " is used by two disks");
            }
            drives.put(drive, resolveVolumeId(client, volume) + ProxmoxVmConfig.driveOptions(volume));
        }
        return drives;
    }

    /** The volume id in Proxmox: "&lt;pool entry&gt;:&lt;path&gt;". */
    private static String volumeId(VolumeObjectTO volume) {
        if (StringUtils.isBlank(volume.getPath()) || !(volume.getDataStore() instanceof PrimaryDataStoreTO)) {
            throw new ProxmoxApiException("The volume " + volume.getUuid() + " has no path on a primary storage");
        }
        return ProxmoxStorageNames.storageId(((PrimaryDataStoreTO) volume.getDataStore()).getUuid()) + ":" + volume.getPath();
    }

    private static final Pattern INSTANCE_NAME = Pattern.compile("^i-(\\d+)-\\d+-.*");

    /** The account of an instance from its name "i-&lt;account&gt;-&lt;id&gt;-VM" (a resize command carries no account), or null. */
    private static Long accountOfInstance(String instanceName) {
        Matcher matcher = INSTANCE_NAME.matcher(StringUtils.defaultString(instanceName));
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static boolean isHolderAccount(Long accountId) {
        return accountId != null && accountId >= 1 && accountId <= ProxmoxVmConfig.MAX_ACCOUNT_ID;
    }

    /**
     * The volume id that the path of a data volume stands for now. Core keeps the path it was given, and a volume that the plugin handed to the
     * holder VM when no answer could carry the new path has another one (the alias map, design-phase9-storage-migration.md section 10). The image
     * is looked for first, which is all a volume needs that was never renamed.
     */
    private String resolveVolumeId(ProxmoxApiClient client, String volumeId, Long accountId) {
        if (!isHolderAccount(accountId) || client.getImageInfo(node, volumeId) != null) {
            return volumeId;
        }
        return new ProxmoxHolderVms(client, taskTimeoutSeconds).resolve(accountId, volumeId);
    }

    private String resolveVolumeId(ProxmoxApiClient client, VolumeObjectTO volume) {
        return resolveVolumeId(client, volumeId(volume), volume.getAccountId());
    }

    private static VolumeObjectTO rootVolume(VirtualMachineTO vm) {
        if (vm.getDisks() != null) {
            for (DiskTO disk : vm.getDisks()) {
                if (disk.getType() == Volume.Type.ROOT && disk.getData() instanceof VolumeObjectTO) {
                    return (VolumeObjectTO) disk.getData();
                }
            }
        }
        return null;
    }

    private static String rootVolumePath(VirtualMachineTO vm) {
        if (vm.getDisks() != null) {
            for (DiskTO disk : vm.getDisks()) {
                if (disk.getType() == Volume.Type.ROOT && disk.getData() instanceof VolumeObjectTO) {
                    return ((VolumeObjectTO) disk.getData()).getPath();
                }
            }
        }
        return null;
    }

    private static Long rootVmId(VirtualMachineTO vm) {
        if (vm.getDisks() == null) {
            return null;
        }
        for (DiskTO disk : vm.getDisks()) {
            if (disk.getType() == Volume.Type.ROOT && disk.getData() instanceof VolumeObjectTO) {
                return ProxmoxVmConfig.vmIdOfVolumePath(((VolumeObjectTO) disk.getData()).getPath());
            }
        }
        return null;
    }

    /**
     * Adds a network interface to a VM, running or stopped (a VPC tier or a public network for a router, "add network" for an instance). The
     * interface is {@code net<device id>} on the bridge of the traffic label with the VLAN tag of the network, as at start; when that key is
     * taken the next free one is used. An interface with the MAC address that the VM already has is a success (core may repeat the command).
     * On a running VM Proxmox plugs it at once when the VM's {@code hotplug} option includes the network (the default; lab S6).
     */
    Answer plugNic(PlugNicCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new PlugNicAnswer(cmd, false, "Proxmox has no VM " + cmd.getVmName());
            }
            NicTO nic = cmd.getNic();
            Map<String, String> config = client.getVmConfig(location.getNode(), location.getVmid());
            List<ProxmoxRouterNics.Nic> nics = ProxmoxRouterNics.parse(config);
            if (ProxmoxRouterNics.numberOf(nics, nic.getMac()) != null) {
                return new PlugNicAnswer(cmd, true, "success");
            }
            String model = cmd.getVMType() != null && cmd.getVMType() != VirtualMachine.Type.User ? "virtio" : ProxmoxVmConfig.nicModel(cmd.getDetails());
            String key = config.containsKey("net" + nic.getDeviceId()) ? "net" + ProxmoxRouterNics.nextIndex(nics) : "net" + nic.getDeviceId();
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap(key, ProxmoxVmConfig.netOption(nic, model, nic.getName())));
            LOGGER.info("Plugged the network interface {} ({}) into {}", key, nic.getMac(), cmd.getVmName());
            return new PlugNicAnswer(cmd, true, "success");
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not plug a network interface into {}: {}", node, cmd.getVmName(), e.getMessage());
            return new PlugNicAnswer(cmd, false, "Plug Nic failed: " + e.getMessage() + " (the option hotplug of the VM must include network)");
        }
    }

    /** Removes the network interface with the MAC address of the command; a VM that does not have it is a success, as on KVM. */
    Answer unplugNic(UnPlugNicCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new UnPlugNicAnswer(cmd, true, "success");
            }
            List<ProxmoxRouterNics.Nic> nics = ProxmoxRouterNics.parse(client.getVmConfig(location.getNode(), location.getVmid()));
            Integer position = ProxmoxRouterNics.numberOf(nics, cmd.getNic().getMac());
            if (position != null) {
                client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap("delete", "net" + ProxmoxRouterNics.indexAt(nics, position)));
                LOGGER.info("Removed the network interface net{} ({}) of {}", ProxmoxRouterNics.indexAt(nics, position), cmd.getNic().getMac(), cmd.getVmName());
            }
            return new UnPlugNicAnswer(cmd, true, "success");
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not remove a network interface of {}: {}", node, cmd.getVmName(), e.getMessage());
            return new UnPlugNicAnswer(cmd, false, "Unplug Nic failed: " + e.getMessage());
        }
    }

    Answer stop(StopCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new StopAnswer(cmd, "Proxmox has no VM " + cmd.getVmName() + ", nothing to stop", true);
            }
            if (leftAloneAsMigrationCleanup(cmd.getVmName(), location)) {
                return new StopAnswer(cmd, cmd.getVmName() + " is running on node " + location.getNode() + ", the source of a failed migration; "
                        + "it was not touched", true);
            }
            if (!location.isRunning()) {
                return new StopAnswer(cmd, cmd.getVmName() + " is already stopped", true);
            }
            String task = cmd.isForceStop() ? client.stopVm(location.getNode(), location.getVmid())
                    : client.shutdownVm(location.getNode(), location.getVmid(), SHUTDOWN_TIMEOUT_SECONDS);
            waitFor(client, task);
            // for a VM under Proxmox HA the task only queued the stop
            waitForVm(client, location.getVmid(), location.getNode(), "stop", taskTimeoutSeconds.getAsInt(), ProxmoxVmHandler::isStopped);
            return new StopAnswer(cmd, "Stopped " + cmd.getVmName(), true);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not stop {}: {}", node, cmd.getVmName(), e.getMessage());
            return new StopAnswer(cmd, e.getMessage(), false);
        }
    }

    Answer reboot(RebootCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new RebootAnswer(cmd, "Proxmox has no VM " + cmd.getVmName(), false);
            }
            if (leftAloneAsMigrationCleanup(cmd.getVmName(), location)) {
                return new RebootAnswer(cmd, cmd.getVmName() + " is running on node " + location.getNode() + ", the source of a failed migration; "
                        + "it was not touched", true);
            }
            waitFor(client, client.rebootVm(location.getNode(), location.getVmid()));
            return new RebootAnswer(cmd, "Rebooted " + cmd.getVmName(), true);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not reboot {}: {}", node, cmd.getVmName(), e.getMessage());
            return new RebootAnswer(cmd, e.getMessage(), false);
        }
    }

    /**
     * A stop or reboot that arrives on the destination of a migration, for a VM that is running on another node, is what core sends after a
     * failed migration (it cannot be told from a user's stop by the command, see docs/design-phase5-migration-ha.md); the VM is still
     * running on the source and must be left alone. The mark made by PrepareForMigrationCommand is what tells the two apart, and is used up.
     */
    private boolean leftAloneAsMigrationCleanup(String vmName, ProxmoxApiClient.VmLocation location) {
        Long until = migrationMarks.get(vmName);
        if (until == null) {
            return false;
        }
        if (until < clock.getAsLong()) {
            migrationMarks.remove(vmName);
            return false;
        }
        if (node.equals(location.getNode())) {
            return false;
        }
        migrationMarks.remove(vmName);
        LOGGER.info("Leaving {} alone on node {}: node {} was prepared as the target of a migration that did not complete", vmName,
                location.getNode(), node);
        return true;
    }

    /**
     * Puts the VM under Proxmox HA when the offering has HA enabled, and takes a resource made by this plugin away when it does not. Only with
     * the setting proxmox.ha.manage; a resource that an administrator made (another comment) is never changed. A VM whose owner believes it
     * is protected must not run unprotected, so a failure to create the resource fails the start.
     */
    private void applyHaFlag(ProxmoxApiClient client, VirtualMachineTO vm, long vmid) {
        if (!manageHa.getAsBoolean()) {
            return;
        }
        try {
            ProxmoxApiClient.HaResource existing = client.findHaResource(vmid);
            if (vm.isEnableHA()) {
                if (existing == null) {
                    client.createHaResource(vmid, HA_COMMENT);
                    LOGGER.info("Put Proxmox VM {} ({}) under Proxmox HA", vmid, vm.getName());
                }
            } else if (existing != null && HA_COMMENT.equals(existing.getComment())) {
                client.deleteHaResource(vmid);
                LOGGER.info("Took Proxmox VM {} ({}) out of Proxmox HA: its offering no longer has HA", vmid, vm.getName());
            }
        } catch (ProxmoxApiException e) {
            if (!vm.isEnableHA()) {
                LOGGER.warn("Could not check the Proxmox HA resource of {}: {}", vm.getName(), e.getMessage());
                return;
            }
            String reason = e.getStatusCode() == 403 ? "the API token needs the privileges Sys.Audit and Sys.Console on / (see the Proxmox plugin "
                    + "requirements)" : e.getMessage();
            throw new ProxmoxApiException("Proxmox HA could not be enabled for " + vm.getName() + ": " + reason, e.getStatusCode(), e);
        }
    }

    // ---- ISOs ----

    /**
     * The Proxmox volume of an ISO on the secondary store ("&lt;entry&gt;:iso/&lt;file&gt;"), after making sure its entry exists and is active on this
     * node. The entry is a directory on the mount of the secondary store that exposes the ISO's own folder, because Proxmox allows no folder below
     * "iso/" and CloudStack keeps every ISO in a folder of its own ("template/tmpl/&lt;account&gt;/&lt;id&gt;/&lt;file&gt;"). Entries are kept after use.
     */
    private String isoVolume(ProxmoxApiClient client, TemplateObjectTO iso) {
        if (!(iso.getDataStore() instanceof NfsTO)) {
            throw new ProxmoxApiException("The ISO " + iso.getPath() + " is not on an NFS secondary store; only those are supported for Proxmox");
        }
        NfsTO secondary = (NfsTO) iso.getDataStore();
        URI url = URI.create(StringUtils.removeEnd(secondary.getUrl(), "/"));
        String server = url.getHost();
        String export = url.getPath();
        String path = StringUtils.removeStart(StringUtils.defaultString(iso.getPath()), "/");
        String file = StringUtils.substringAfterLast(path, "/");
        String folder = StringUtils.substringBeforeLast(path, "/");
        if (StringUtils.isAnyBlank(server, export, file, folder) || path.contains("..")) {
            throw new ProxmoxApiException(String.format("Cannot find the ISO file from the secondary store %s and the path %s: expected <folder>/<file>",
                    secondary.getUrl(), iso.getPath()));
        }
        String lower = file.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".iso") && !lower.endsWith(".img")) {
            throw new ProxmoxApiException("Proxmox lists only files ending in .iso or .img as ISOs, not " + file);
        }
        String mountPath = ensureSecondaryMount(client, secondary, server, export);
        String id = ProxmoxStorageNames.isoStorageId(server, export, folder);
        ensureStorage(client, id, () -> client.createIsoDirStorage(id, mountPath, folder));
        if (!waitForStorage(client, id)) {
            throw new ProxmoxApiException(String.format("Proxmox storage %s for the ISO in %s is not active on node %s", id, folder, node));
        }
        return id + ":iso/" + file;
    }

    /**
     * Puts the ISO in the CD-ROM drive of its slot. A drive cannot be added to a running VM (Proxmox keeps the change pending until a restart), so
     * for a running VM the drive must already be in the running configuration; the drives are made when the VM starts.
     */
    Answer attachIso(AttachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        if (disk == null || disk.getType() != Volume.Type.ISO || !(disk.getData() instanceof TemplateObjectTO)) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new Answer(cmd, false, "Proxmox has no VM " + cmd.getVmName());
            }
            String drive = ProxmoxVmConfig.cdromDrive(disk.getDiskSeq());
            if (location.isRunning() && !ProxmoxVmConfig.isCdrom(client.getRunningVmConfig(location.getNode(), location.getVmid()).get(drive))) {
                return new Answer(cmd, false, String.format("%s has no CD-ROM drive %s while it runs, and Proxmox cannot add one to a running VM: stop and "
                        + "start the instance (the drives are made when it starts)", cmd.getVmName(), drive));
            }
            String volume = isoVolume(client, (TemplateObjectTO) disk.getData());
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap(drive, ProxmoxVmConfig.cdromValue(volume)));
            LOGGER.info("Attached {} to {} as {}", volume, cmd.getVmName(), drive);
            return new Answer(cmd, true, "Attached " + volume + " as " + drive);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not attach an ISO to {}: {}", node, cmd.getVmName(), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    /** Ejects the disc and keeps the drive. A drive that is empty or holds another ISO is left as it is: there is nothing of this ISO to remove. */
    Answer detachIso(DettachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        if (disk == null || disk.getType() != Volume.Type.ISO || !(disk.getData() instanceof TemplateObjectTO)) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new Answer(cmd, true, "Proxmox has no VM " + cmd.getVmName() + ", nothing to detach");
            }
            String drive = ProxmoxVmConfig.cdromDrive(disk.getDiskSeq());
            String current = (location.isRunning() ? client.getRunningVmConfig(location.getNode(), location.getVmid())
                    : client.getVmConfig(location.getNode(), location.getVmid())).get(drive);
            String path = ((TemplateObjectTO) disk.getData()).getPath();
            String file = StringUtils.substringAfterLast(StringUtils.defaultString(path), "/");
            if (!ProxmoxVmConfig.isCdrom(current) || ProxmoxVmConfig.isEmptyCdrom(current) || (StringUtils.isNotBlank(file) && !current.contains(file))) {
                return new Answer(cmd, true, "Drive " + drive + " does not hold this ISO, nothing to detach");
            }
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap(drive, ProxmoxVmConfig.EMPTY_CDROM));
            LOGGER.info("Ejected the ISO from {} of {}", drive, cmd.getVmName());
            return new Answer(cmd, true, "Ejected the ISO from " + drive);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not detach an ISO from {}: {}", node, cmd.getVmName(), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    // ---- data volumes ----

    /** Attach: an ISO goes into a CD-ROM drive, a data volume becomes a disk drive; anything else is not supported. */
    Answer attach(AttachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        if (disk != null && disk.getType() == Volume.Type.DATADISK && disk.getData() instanceof VolumeObjectTO) {
            return attachDataVolume(cmd, disk, (VolumeObjectTO) disk.getData());
        }
        return attachIso(cmd);
    }

    /** Detach: the counterpart of {@link #attach}. */
    Answer detach(DettachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        if (disk != null && disk.getType() == Volume.Type.DATADISK && disk.getData() instanceof VolumeObjectTO) {
            return detachDataVolume(cmd, disk, (VolumeObjectTO) disk.getData());
        }
        return detachIso(cmd);
    }

    /**
     * Sets the drive of the volume's slot to the volume. For a running VM the drive must really appear in the running configuration: a controller that
     * cannot hot-plug (SATA) only keeps the change pending, which is taken back again so that the VM and CloudStack agree.
     */
    private Answer attachDataVolume(AttachCommand cmd, DiskTO disk, VolumeObjectTO volume) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new AttachAnswer("Proxmox has no VM " + cmd.getVmName());
            }
            String drive = ProxmoxVmConfig.dataDrive(disk.getDiskSeq(), cmd.getControllerInfo());
            String wanted = resolveVolumeId(client, volume);
            // a volume that another instance still has (a live move made it that instance's disk, and detaching it from a stopped instance sends
            // no command) goes back to the holder first, or destroying that instance would destroy the volume
            Long owner = ownerVm(wanted);
            if (owner != null && owner != location.getVmid() && isHolderAccount(volume.getAccountId())) {
                ProxmoxApiClient.VmLocation ownerLocation = client.findVm(owner);
                if (ownerLocation != null) {
                    wanted = giveBackToHolder(client, ownerLocation, wanted, volume.getAccountId(), true);
                }
            }
            String existing = client.getVmConfig(location.getNode(), location.getVmid()).get(drive);
            if (existing != null) {
                if (wanted.equals(StringUtils.substringBefore(existing, ","))) {
                    return new AttachAnswer(disk);
                }
                return new AttachAnswer("The drive " + drive + " of " + cmd.getVmName() + " already holds another disk");
            }
            String value = wanted + ProxmoxVmConfig.driveOptions(volume);
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap(drive, value));
            if (location.isRunning() && client.getRunningVmConfig(location.getNode(), location.getVmid()).get(drive) == null) {
                client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap("revert", drive));
                return new AttachAnswer(String.format("Proxmox cannot add the drive %s to the running VM %s (the %s controller is not hot-pluggable): stop the instance, "
                        + "attach the volume while it is stopped and start it again (the drive is made when it starts), or use the scsi or virtio controller",
                        drive, cmd.getVmName(), ProxmoxVmConfig.controllerOf(drive)));
            }
            LOGGER.info("Attached {} to {} as {}", volume.getPath(), cmd.getVmName(), drive);
            return new AttachAnswer(disk);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not attach {} to {}: {}", node, volume.getPath(), cmd.getVmName(), e.getMessage());
            return new AttachAnswer(e.getMessage());
        }
    }

    /** Removes the drive that holds the volume. The image stays where it is, and Proxmox leaves no "unused" entry for a disk the VM does not own. */
    private Answer detachDataVolume(DettachCommand cmd, DiskTO disk, VolumeObjectTO volume) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new DettachAnswer(disk);
            }
            String resolved = resolveVolumeId(client, volume);
            String drive = ProxmoxVmConfig.driveHoldingVolume(client.getVmConfig(location.getNode(), location.getVmid()), resolved);
            if (drive == null) {
                return new DettachAnswer(disk);
            }
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap("delete", drive));
            if (location.isRunning() && client.getRunningVmConfig(location.getNode(), location.getVmid()).get(drive) != null) {
                client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap("revert", drive));
                return new DettachAnswer(String.format("Proxmox cannot remove the drive %s from the running VM %s (the %s controller is not hot-pluggable): stop the instance first",
                        drive, cmd.getVmName(), ProxmoxVmConfig.controllerOf(drive)));
            }
            LOGGER.info("Detached {} from {} ({})", volume.getPath(), cmd.getVmName(), drive);
            DettachAnswer answer = new DettachAnswer(disk);
            Long owner = ownerVm(resolved);
            if (owner != null && owner == location.getVmid() && isHolderAccount(volume.getAccountId())) {
                // the instance owns the disk (a live move made it so): it goes back to the holder, and the answer tells CloudStack the new path
                String back = giveBackToHolder(client, location, resolved, volume.getAccountId(), false);
                answer.setContextParam("volumePath", StringUtils.substringAfter(back, ":"));
            }
            return answer;
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not detach {} from {}: {}", node, volume.getPath(), cmd.getVmName(), e.getMessage());
            return new DettachAnswer(e.getMessage());
        }
    }

    // ---- Phase 9: disks that an instance owns after a live move, and their way back to the holder ----

    /** The VM that owns the disk of a volume id, or null when a holder VM (a data volume as Phase 7 made it) or nobody does. */
    private static Long ownerVm(String volumeId) {
        Long vmid = ProxmoxVmConfig.vmIdOfVolumePath(volumeId);
        return vmid == null || ProxmoxVmConfig.isReservedVmId(vmid) ? null : vmid;
    }

    /**
     * Gives a disk that the instance owns to the account's holder VM: the drive that holds it (if it is still on one) is removed, which makes it an
     * "unusedN" entry of the instance (lab M5), and Proxmox hands that entry to the holder (lab M6). The holder is moved to the node of the instance first.
     * With {@code withAlias} the old id is recorded, because CloudStack is not told the new path. Returns the new volume id.
     */
    private String giveBackToHolder(ProxmoxApiClient client, ProxmoxApiClient.VmLocation owner, String volumeId, long accountId, boolean withAlias) {
        ProxmoxHolderVms holders = new ProxmoxHolderVms(client, taskTimeoutSeconds);
        return holders.withLock(ProxmoxVmConfig.holderVmId(accountId), () -> {
            ProxmoxApiClient.VmLocation holder = holders.ensureHolder(accountId, owner.getNode());
            Map<String, String> config = client.getVmConfig(owner.getNode(), owner.getVmid());
            String entry = ProxmoxHolderVms.entryOf(config, volumeId);
            if (entry == null) {
                String drive = ProxmoxVmConfig.driveHoldingVolume(config, volumeId);
                if (drive == null) {
                    throw new ProxmoxApiException("Proxmox VM " + owner.getVmid() + " does not list the disk " + volumeId);
                }
                client.updateVmConfig(owner.getNode(), owner.getVmid(), Collections.singletonMap("delete", drive));
                entry = ProxmoxHolderVms.entryOf(client.getVmConfig(owner.getNode(), owner.getVmid()), volumeId);
                if (entry == null) {
                    throw new ProxmoxApiException("Proxmox removed " + drive + " of VM " + owner.getVmid() + " but did not keep " + volumeId + " as an unused disk");
                }
            }
            return withAlias ? holders.handBackWithAlias(holder, owner.getNode(), owner.getVmid(), entry, volumeId)
                    : holders.handBack(owner.getNode(), owner.getVmid(), entry, holder.getVmid());
        });
    }

    /**
     * At start: a data disk that the instance owns and that the command does not list was detached while the instance was stopped (CloudStack sends
     * no command then): it goes back to the holder, with an alias, as it would otherwise stay in the instance and go with it.
     */
    private void giveBackStaleDataDisks(ProxmoxApiClient client, ProxmoxApiClient.VmLocation location, VirtualMachineTO vm, String rootDrive,
            Map<String, String> dataDrives) {
        Long account = accountOfInstance(vm.getName());
        if (!isHolderAccount(account)) {
            return;
        }
        VolumeObjectTO rootVolume = rootVolume(vm);
        String rootId = rootVolume == null || StringUtils.isBlank(rootVolume.getPath()) || !(rootVolume.getDataStore() instanceof PrimaryDataStoreTO) ? null : volumeId(rootVolume);
        String rootPath = rootVolume == null ? null : rootVolume.getPath();
        for (Map.Entry<String, String> existing : new LinkedHashMap<>(client.getVmConfig(node, location.getVmid())).entrySet()) {
            String volumeId = ProxmoxHolderVms.volumeIdOf(existing.getValue());
            // the root disk may still be on the drive it was moved from (moveRootDisk), so it is told by its volume id, not its drive
            if (existing.getKey().matches("(scsi|virtio|sata)\\d+") && !existing.getKey().equals(rootDrive) && volumeId != null
                    && !volumeId.equals(rootId) && !(rootId == null && rootPath != null && volumeId.endsWith(":" + rootPath))
                    && Long.valueOf(location.getVmid()).equals(ownerVm(volumeId)) && dataDrives.values().stream().noneMatch(value -> value.startsWith(volumeId))) {
                giveBackToHolder(client, location, volumeId, account, true);
            }
        }
    }

    /** Before an instance is destroyed: every disk on a drive that is not its root volume is a data volume that came to it by a live move. */
    private void giveBackDataDisks(ProxmoxApiClient client, ProxmoxApiClient.VmLocation location, VolumeObjectTO root) {
        if (!isHolderAccount(root.getAccountId())) {
            return;
        }
        for (Map.Entry<String, String> existing : new LinkedHashMap<>(client.getVmConfig(location.getNode(), location.getVmid())).entrySet()) {
            String volumeId = ProxmoxHolderVms.volumeIdOf(existing.getValue());
            if (existing.getKey().matches("(scsi|virtio|sata)\\d+") && volumeId != null && Long.valueOf(location.getVmid()).equals(ownerVm(volumeId))
                    && !volumeId.equals(volumeId(root))) {
                giveBackToHolder(client, location, volumeId, root.getAccountId(), true);
            }
        }
    }

    /**
     * Before a data volume is deleted: an instance that still lists its disk (the volume was detached from a stopped instance, which sends no command)
     * must not keep an entry for it. Removing the entry removes the disk; a volume that no instance owns is not touched.
     */
    private void removeFromOwner(ProxmoxApiClient client, String volumeId) {
        Long owner = ownerVm(volumeId);
        ProxmoxApiClient.VmLocation location = owner == null ? null : client.findVm(owner);
        if (location == null) {
            return;
        }
        Map<String, String> config = client.getVmConfig(location.getNode(), location.getVmid());
        String drive = ProxmoxVmConfig.driveHoldingVolume(config, volumeId);
        if (drive != null) {
            if (location.isRunning() && client.getRunningVmConfig(location.getNode(), location.getVmid()).get(drive) != null) {
                throw new ProxmoxApiException("The volume " + volumeId + " is still on " + drive + " of the running VM " + owner);
            }
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap("delete", drive));
            config = client.getVmConfig(location.getNode(), location.getVmid());
        }
        String entry = ProxmoxHolderVms.entryOf(config, volumeId);
        if (entry != null) {
            client.updateVmConfig(location.getNode(), location.getVmid(), Collections.singletonMap("delete", entry));
            LOGGER.info("Removed {} of VM {} with the volume {}", entry, owner, volumeId);
        }
    }

    /** The old disk is gone after a move: the holder VM's entry for it (adopted by a copy, step 2) and its aliases go too. Best effort. */
    private void forgetOldVolume(ProxmoxApiClient client, long accountId, String oldVolumeId) {
        try {
            ProxmoxApiClient.VmLocation holder = client.findVm(ProxmoxVmConfig.holderVmId(accountId));
            if (holder != null) {
                ProxmoxHolderVms holders = new ProxmoxHolderVms(client, taskTimeoutSeconds);
                holders.dropDanglingEntry(holder, oldVolumeId);
                holders.forgetAliases(holder, oldVolumeId);
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("The holder VM of account {} may still list {}: {}", accountId, oldVolumeId, e.getMessage());
        }
    }

    /**
     * The live move of an attached volume (CloudStack's "migrate volume" of a volume whose instance runs): Proxmox copies the disk while the VM runs and
     * switches the drive (move_disk, lab M1). The answer carries the new path, which is "&lt;vmid&gt;/vm-&lt;vmid&gt;-disk-&lt;n&gt;.qcow2" because the VM owns the copy;
     * a data volume goes back to the holder when it is detached ({@link #detachDataVolume}).
     */
    Answer migrateVolume(MigrateVolumeCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            if (StringUtils.isBlank(cmd.getAttachedVmName()) || cmd.getPool() == null) {
                return new MigrateVolumeAnswer(cmd, false, "Proxmox moves a volume with MigrateVolumeCommand only while an instance uses it; a detached volume is copied", null);
            }
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getAttachedVmName());
            if (location == null) {
                return new MigrateVolumeAnswer(cmd, false, "Proxmox has no VM " + cmd.getAttachedVmName(), null);
            }
            Map<String, String> config = client.getVmConfig(location.getNode(), location.getVmid());
            Long account = accountOfInstance(cmd.getAttachedVmName());
            List<String> drives = ProxmoxVmConfig.drivesHolding(config, cmd.getVolumePath());
            if (drives.isEmpty() && isHolderAccount(account)) {
                // a volume that was handed to the holder with an alias keeps the path CloudStack has
                String resolved = new ProxmoxHolderVms(client, taskTimeoutSeconds).resolveByPath(account, cmd.getVolumePath());
                drives = resolved == null ? drives : ProxmoxVmConfig.drivesHolding(config, StringUtils.substringAfter(resolved, ":"));
            }
            if (drives.size() > 1) {
                // the command names no source pool and Proxmox numbers disks per storage, so a root and a data volume can share a path: tell them by type
                boolean root = cmd.getVolumeType() == Volume.Type.ROOT;
                drives = drives.stream().filter(candidate -> root == Arrays.asList(ProxmoxVmConfig.ROOT_DRIVES).contains(candidate)).collect(Collectors.toList());
            }
            if (drives.size() != 1) {
                return new MigrateVolumeAnswer(cmd, false, drives.isEmpty() ? "The volume " + cmd.getVolumePath() + " is not on a drive of " + cmd.getAttachedVmName()
                        : "More than one drive of " + cmd.getAttachedVmName() + " holds " + cmd.getVolumePath() + " and the volume type does not tell them apart: " + drives, null);
            }
            String drive = drives.get(0);
            String destStorage = ProxmoxStorageNames.storageId(cmd.getPool().getUuid());
            String current = ProxmoxHolderVms.volumeIdOf(config.get(drive));
            if (current.startsWith(destStorage + ":")) {
                return new MigrateVolumeAnswer(cmd, false, "The volume " + cmd.getVolumePath() + " is on " + destStorage + " already", null);
            }
            client.waitForTask(client.moveDisk(location.getNode(), location.getVmid(), drive, destStorage), migrationTimeoutSeconds.getAsInt());
            String moved = ProxmoxHolderVms.volumeIdOf(client.getVmConfig(location.getNode(), location.getVmid()).get(drive));
            if (moved == null || !moved.startsWith(destStorage + ":")) {
                return new MigrateVolumeAnswer(cmd, false, "Proxmox moved " + current + " but " + drive + " of " + cmd.getAttachedVmName() + " is not on " + destStorage, null);
            }
            LOGGER.info("Moved {} of {} from {} to {}", drive, cmd.getAttachedVmName(), current, moved);
            if (isHolderAccount(account)) {
                forgetOldVolume(client, account, current);
            }
            return new MigrateVolumeAnswer(cmd, true, "Moved " + current + " to " + moved, StringUtils.substringAfter(moved, ":"));
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not move volume {}: {}", node, cmd.getVolumePath(), e.getMessage());
            return new MigrateVolumeAnswer(cmd, false, e.getMessage(), null);
        }
    }

    /**
     * Grows the disk that holds the volume. Proxmox resizes the disk of a VM (running or stopped) and has no resize for a disk that no VM uses, so a volume
     * that is not attached cannot be resized; a disk never shrinks, whatever CloudStack says.
     */
    Answer resize(ResizeVolumeCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = StringUtils.isBlank(cmd.getInstanceName()) ? null : client.findVmByName(cmd.getInstanceName());
            if (location == null) {
                return new ResizeVolumeAnswer(cmd, false, "Proxmox can only resize a disk that is attached to an instance: attach the volume first, then resize it");
            }
            Map<String, String> config = client.getVmConfig(location.getNode(), location.getVmid());
            String drive = cmd.getPool() == null ? ProxmoxVmConfig.driveHolding(config, cmd.getPath()) : ProxmoxVmConfig.driveHoldingVolume(config,
                    resolveVolumeId(client, ProxmoxStorageNames.storageId(cmd.getPool().getUuid()) + ":" + cmd.getPath(), accountOfInstance(cmd.getInstanceName())));
            if (drive == null) {
                return new ResizeVolumeAnswer(cmd, false, "The volume " + cmd.getPath() + " is not on a drive of " + cmd.getInstanceName());
            }
            long current = ProxmoxVmConfig.diskSizeBytes(config.get(drive));
            long wanted = (cmd.getNewSize() + GIB - 1) / GIB * GIB;
            if (wanted < current) {
                return new ResizeVolumeAnswer(cmd, false, "Proxmox cannot shrink a disk (" + current / GIB + " GiB now, " + wanted / GIB + " GiB asked)");
            }
            if (wanted > current) {
                waitFor(client, client.resizeDisk(location.getNode(), location.getVmid(), drive, (wanted / GIB) + "G"));
                LOGGER.info("Resized {} of {} from {} GiB to {} GiB", drive, cmd.getInstanceName(), current / GIB, wanted / GIB);
            }
            return new ResizeVolumeAnswer(cmd, true, "Resized " + drive + " to " + wanted / GIB + " GiB", wanted);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not resize {}: {}", node, cmd.getPath(), e.getMessage());
            return new ResizeVolumeAnswer(cmd, false, e.getMessage());
        }
    }

    /** The size and the used space of each volume (by path) in the pool, from the disk image; a volume that has no image is left out. */
    Answer volumeStats(GetVolumeStatsCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            String poolStorage = ProxmoxStorageNames.storageId(cmd.getPoolUuid());
            HashMap<String, VolumeStatsEntry> stats = new HashMap<>();
            for (String path : cmd.getVolumeUuids()) {
                ProxmoxApiClient.ImageInfo info = client.getImageInfo(node, poolStorage + ":" + path);
                if (info != null) {
                    stats.put(path, new VolumeStatsEntry(path, info.getUsed(), info.getSize()));
                }
            }
            return new GetVolumeStatsAnswer(cmd, "", stats);
        } catch (ProxmoxApiException e) {
            return new GetVolumeStatsAnswer(cmd, "Can't get volume stats: " + e.getMessage(), null);
        }
    }

    // ---- migration ----

    /**
     * On the destination, before core moves anything: checks that the VM can run here, so that a refusal changes nothing. Proxmox has no
     * per-VLAN object to create, so what must exist is the bridge of every NIC (VLAN-aware for a tagged one).
     */
    Answer prepareForMigration(PrepareForMigrationCommand cmd) {
        VirtualMachineTO vm = cmd.getVirtualMachine();
        if (cmd.isRollback()) {
            migrationMarks.remove(vm.getName());
            return new PrepareForMigrationAnswer(cmd);
        }
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.NodeInfo self = null;
            for (ProxmoxApiClient.NodeInfo info : client.getClusterNodes(null)) {
                if (node.equals(info.getName())) {
                    self = info;
                }
            }
            if (self == null || !self.isOnline()) {
                return new PrepareForMigrationAnswer(cmd, "Proxmox node " + node + " is not online");
            }
            ProxmoxApiClient.VmLocation location = client.findVmByName(vm.getName());
            if (location == null) {
                return new PrepareForMigrationAnswer(cmd, "Proxmox has no VM " + vm.getName());
            }
            if (node.equals(location.getNode())) {
                return new PrepareForMigrationAnswer(cmd, vm.getName() + " is already on Proxmox node " + node);
            }
            String missing = missingBridge(client, vm);
            if (missing != null) {
                return new PrepareForMigrationAnswer(cmd, missing);
            }
            List<String> problems = client.getMigrationProblems(location.getNode(), location.getVmid(), node);
            if (!problems.isEmpty()) {
                return new PrepareForMigrationAnswer(cmd, String.format("%s cannot move from node %s to %s: %s", vm.getName(), location.getNode(),
                        node, String.join("; ", problems)));
            }
            migrationMarks.put(vm.getName(), clock.getAsLong() + migrationTimeoutSeconds.getAsInt() * 1000L + MARK_MARGIN_MS);
            return new PrepareForMigrationAnswer(cmd);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not prepare for the migration of {}: {}", node, vm.getName(), e.getMessage());
            return new PrepareForMigrationAnswer(cmd, e.getMessage());
        }
    }

    /** The reason the VM's NICs cannot be attached on this node, or null. */
    private String missingBridge(ProxmoxApiClient client, VirtualMachineTO vm) {
        if (vm.getNics() == null) {
            return null;
        }
        List<ProxmoxApiClient.NodeBridge> bridges = client.listBridges(node);
        for (NicTO nic : vm.getNics()) {
            if (StringUtils.isBlank(nic.getName())) {
                continue;
            }
            ProxmoxApiClient.NodeBridge bridge = bridges.stream().filter(b -> b.getName().equals(nic.getName())).findFirst().orElse(null);
            if (bridge == null || !bridge.isActive()) {
                return String.format("Proxmox node %s has no active bridge %s for NIC %d of %s", node, nic.getName(), nic.getDeviceId(), vm.getName());
            }
            if (ProxmoxVmConfig.vlanTag(nic.getBroadcastUri()) != null && !bridge.isVlanAware()) {
                return String.format("Bridge %s on Proxmox node %s is not VLAN-aware, which NIC %d of %s needs", nic.getName(), node, nic.getDeviceId(),
                        vm.getName());
            }
        }
        return null;
    }

    /** On the source: moves the running VM to the node that has the destination address, and answers when it really is there and running. */
    Answer migrate(MigrateCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            String target = null;
            for (ProxmoxApiClient.NodeInfo info : client.getClusterNodes(null)) {
                if (cmd.getDestinationIp() != null && cmd.getDestinationIp().equals(info.getIp())) {
                    target = info.getName();
                }
            }
            if (target == null) {
                return new MigrateAnswer(cmd, false, cmd.getDestinationIp() + " is not a node of this Proxmox cluster", null);
            }
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new MigrateAnswer(cmd, false, "Proxmox has no VM " + cmd.getVmName(), null);
            }
            if (target.equals(location.getNode())) {
                return new MigrateAnswer(cmd, true, cmd.getVmName() + " is already on node " + target, null);
            }
            boolean wasRunning = location.isRunning();
            String finalTarget = target;
            int timeout = migrationTimeoutSeconds.getAsInt();
            client.waitForTask(client.migrateRunningVm(location.getNode(), location.getVmid(), target), timeout);
            // for a VM under Proxmox HA the task only queued the move
            waitForVm(client, location.getVmid(), target, "move to node " + target, timeout,
                    l -> l != null && finalTarget.equals(l.getNode()) && (!wasRunning || l.isRunning()));
            LOGGER.info("Moved Proxmox VM {} ({}) from node {} to {}", location.getVmid(), cmd.getVmName(), location.getNode(), target);
            return new MigrateAnswer(cmd, true, "Moved " + cmd.getVmName() + " to node " + target, null);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox could not migrate {}: {}", cmd.getVmName(), e.getMessage());
            return new MigrateAnswer(cmd, false, e.getMessage(), null);
        }
    }

    /** On the destination, after a migration: is the VM running here? The answer also ends the mark of PrepareForMigrationCommand. */
    Answer checkVirtualMachine(CheckVirtualMachineCommand cmd) {
        try {
            ProxmoxApiClient.VmLocation location = clients.get().findVmByName(cmd.getVmName());
            if (location != null && node.equals(location.getNode()) && location.isRunning()) {
                migrationMarks.remove(cmd.getVmName());
                return new CheckVirtualMachineAnswer(cmd, VirtualMachine.PowerState.PowerOn, null);
            }
            return new CheckVirtualMachineAnswer(cmd, VirtualMachine.PowerState.PowerOff, null);
        } catch (ProxmoxApiException e) {
            return new CheckVirtualMachineAnswer(cmd, e.getMessage());
        }
    }

    // ---- delete ----

    /**
     * A root volume is deleted by destroying its VM (which removes the disk it owns). Nothing is done for a template on the primary storage
     * (see deleteTemplate).
     */
    Answer delete(DeleteCommand cmd) {
        DataTO data = cmd.getData();
        try {
            if (data instanceof VolumeObjectTO) {
                return deleteVolume(cmd, (VolumeObjectTO) data);
            }
            if (data instanceof TemplateObjectTO) {
                return deleteTemplate(cmd, (TemplateObjectTO) data);
            }
            return Answer.createUnsupportedCommandAnswer(cmd);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not complete {}: {}", node, cmd.getClass().getSimpleName(), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    private Answer deleteVolume(DeleteCommand cmd, VolumeObjectTO volume) {
        Long vmid = ProxmoxVmConfig.vmIdOfVolumePath(volume.getPath());
        // by the volume's type and its path: the path of a data volume names a holder, which is not a VM, and must never be taken for one
        if (volume.getVolumeType() == Volume.Type.DATADISK || (vmid != null && ProxmoxVmConfig.isReservedVmId(vmid))) {
            return deleteDataVolume(cmd, volume);
        }
        if (vmid == null) {
            return new Answer(cmd, true, "Volume " + volume.getPath() + " does not belong to a Proxmox VM, nothing to delete");
        }
        ProxmoxApiClient client = clients.get();
        ProxmoxApiClient.VmLocation location = client.findVm(vmid);
        if (location == null) {
            return new Answer(cmd, true, "Proxmox VM " + vmid + " is already gone");
        }
        if (location.isRunning()) {
            waitFor(client, client.stopVm(location.getNode(), vmid));
            waitForVm(client, vmid, location.getNode(), "stop", taskTimeoutSeconds.getAsInt(), ProxmoxVmHandler::isStopped);
        }
        giveBackDataDisks(client, location, volume);
        waitFor(client, client.destroyVm(location.getNode(), vmid));
        LOGGER.info("Destroyed Proxmox VM {} with its disk", vmid);
        return new Answer(cmd, true, "Destroyed Proxmox VM " + vmid);
    }

    /**
     * Deletes the disk image of a data volume. CloudStack deletes only volumes that are detached; Proxmox itself would delete an image that a VM still
     * refers to, so nothing here relies on it. An image that is gone already is a success.
     */
    private Answer deleteDataVolume(DeleteCommand cmd, VolumeObjectTO volume) {
        String requested = volumeId(volume);
        String volumeId = requested;
        ProxmoxApiClient client = clients.get();
        ProxmoxHolderVms holders = new ProxmoxHolderVms(client, taskTimeoutSeconds);
        // a volume that the account's holder VM lists (it was moved or handed over, Phase 9) goes with its entry, which leaves no dangling one;
        // a volume that was renamed without core hearing of it is found through the alias map
        ProxmoxApiClient.VmLocation holder = isHolderAccount(volume.getAccountId()) ? client.findVm(ProxmoxVmConfig.holderVmId(volume.getAccountId())) : null;
        if (holder != null && client.getImageInfo(node, requested) == null) {
            volumeId = holders.resolve(holder, requested);
        }
        removeFromOwner(client, volumeId);
        if (holder != null) {
            if (client.getImageInfo(node, requested) == null) {
                volumeId = holders.resolve(holder, requested);
            }
            holders.deleteListed(holder, volumeId);
        }
        try {
            if (client.getImageInfo(node, volumeId) == null) {
                return new Answer(cmd, true, "Proxmox has no disk image " + volumeId + ", nothing to delete");
            }
            waitFor(client, client.deleteImage(node, volumeId));
            LOGGER.info("Deleted Proxmox data volume {}", volumeId);
            return new Answer(cmd, true, "Deleted " + volumeId);
        } finally {
            if (holder != null) {
                try {
                    holders.forgetAliases(holder, volumeId);
                    if (!requested.equals(volumeId)) {
                        holders.forgetAliases(holder, requested);
                    }
                } catch (ProxmoxApiException e) {
                    LOGGER.warn("The aliases of {} stay on the holder VM {}: {}", volumeId, holder.getVmid(), e.getMessage());
                }
            }
        }
    }

    /**
     * The storage entry of a template is shared by all templates of the same account folder, so it is not removed with one template;
     * it is a small read-only entry and an administrator can remove it when no template of that folder is used any more.
     */
    private Answer deleteTemplate(DeleteCommand cmd, TemplateObjectTO template) {
        return new Answer(cmd, true, "Nothing to remove for the template " + template.getPath() + ": its Proxmox storage entry is shared and is kept");
    }

    // ---- console ----

    /**
     * Opens a console on the node that holds the VM and answers with what the console proxy needs: the websocket URL, the header that proves
     * the API token (the ticket is bound to it, so the websocket must use the same token), and the VNC password for the viewer. Everything
     * is good for seconds and one connection, so nothing is kept.
     */
    Answer console(GetVmWebsocketConsoleCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new GetVmWebsocketConsoleAnswer(cmd, "Proxmox has no VM " + cmd.getVmName());
            }
            if (!location.isRunning()) {
                return new GetVmWebsocketConsoleAnswer(cmd, cmd.getVmName() + " is not running, so it has no console");
            }
            String address = addressOf(client, location.getNode());
            if (address == null) {
                return new GetVmWebsocketConsoleAnswer(cmd, "No address is known for Proxmox node " + location.getNode());
            }
            ProxmoxApiClient.ConsoleSession session = client.openConsole(location.getNode(), location.getVmid());
            if (StringUtils.isBlank(session.getPassword())) {
                return new GetVmWebsocketConsoleAnswer(cmd, "Proxmox returned no VNC password for " + cmd.getVmName());
            }
            String url = String.format("wss://%s:%d/api2/json/nodes/%s/qemu/%d/vncwebsocket?port=%d&vncticket=%s", address, apiPort,
                    location.getNode(), location.getVmid(), session.getPort(), URLEncoder.encode(session.getTicket(), StandardCharsets.UTF_8));
            return new GetVmWebsocketConsoleAnswer(cmd, url, authorization.get(), session.getPassword());
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not open a console for {}: {}", node, cmd.getVmName(), e.getMessage());
            return new GetVmWebsocketConsoleAnswer(cmd, e.getMessage());
        }
    }

    /** The address of a node: this one's, or another node's as the cluster reports it. */
    private String addressOf(ProxmoxApiClient client, String nodeName) {
        if (node.equals(nodeName) && StringUtils.isNotBlank(nodeAddress)) {
            return nodeAddress;
        }
        for (ProxmoxApiClient.NodeInfo info : client.getClusterNodes(null)) {
            if (nodeName.equals(info.getName()) && StringUtils.isNotBlank(info.getIp())) {
                return info.getIp();
            }
        }
        return null;
    }
}

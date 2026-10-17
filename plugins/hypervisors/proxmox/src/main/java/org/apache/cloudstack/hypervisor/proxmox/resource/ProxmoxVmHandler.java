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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmWebsocketConsoleAnswer;
import com.cloud.agent.api.GetVmWebsocketConsoleCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;

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
    private static final int CREATE_ATTEMPTS = 3;

    private final Supplier<ProxmoxApiClient> clients;
    private final String node;
    /** The address of this node and the cluster's API port, for the websocket URL of a console on this node. */
    private final String nodeAddress;
    private final int apiPort;
    /** The value of the Authorization header that proves the API token, "PVEAPIToken=user@realm!token=secret". */
    private final Supplier<String> authorization;
    private final LongConsumer pauser;
    private final IntSupplier taskTimeoutSeconds;

    ProxmoxVmHandler(Supplier<ProxmoxApiClient> clients, String node, String nodeAddress, int apiPort, Supplier<String> authorization,
            LongConsumer pauser, IntSupplier taskTimeoutSeconds) {
        this.clients = clients;
        this.nodeAddress = nodeAddress;
        this.apiPort = apiPort;
        this.authorization = authorization;
        this.node = node;
        this.pauser = pauser;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    private void waitFor(ProxmoxApiClient client, String upid) {
        client.waitForTask(upid, taskTimeoutSeconds.getAsInt());
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
            return new CopyCmdAnswer("Copying " + (src == null ? null : src.getObjectType()) + " to " + (dest == null ? null : dest.getObjectType())
                    + " is not supported for Proxmox");
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not complete {}: {}", node, cmd.getClass().getSimpleName(), e.getMessage());
            return new CopyCmdAnswer(e.getMessage());
        }
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
        String options = StringUtils.isBlank(secondary.getNfsVersion()) ? "ro" : "vers=" + secondary.getNfsVersion() + ",ro";

        ProxmoxApiClient client = clients.get();
        String storeId = ProxmoxStorageNames.secondaryStorageId(server, export);
        String mountPath = ProxmoxStorageNames.secondaryMountPath(storeId);
        ensureStorage(client, storeId, () -> client.createSecondaryNfsStorage(storeId, server, export, mountPath, options));
        if (!waitForStorage(client, storeId)) {
            return new CopyCmdAnswer(String.format("Proxmox node %s did not mount %s:%s as storage %s: check that the NFS server exports the "
                    + "secondary store to the Proxmox nodes", node, server, export, storeId));
        }
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
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", StringUtils.defaultIfBlank(volume.getVmName(), "cs-" + volume.getUuid()));
        params.put("memory", CREATE_MEMORY_MIB);
        params.put(ProxmoxVmConfig.ROOT_DISK, poolStorage + ":0,import-from=" + templateVolume + ",format=qcow2");

        long vmid = 0;
        for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
            vmid = client.nextVmId();
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
            }
        }
        Map<String, String> config = client.getVmConfig(node, vmid);
        String disk = config.get(ProxmoxVmConfig.ROOT_DISK);
        if (disk == null) {
            destroyQuietly(client, vmid, false);
            return new CopyCmdAnswer("Proxmox created VM " + vmid + " without a root disk");
        }
        long size = ProxmoxVmConfig.diskSizeBytes(disk);
        if (volume.getSize() != null && volume.getSize() > size) {
            try {
                client.resizeDisk(node, vmid, ProxmoxVmConfig.ROOT_DISK, Long.toString(volume.getSize()));
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
        return new CopyCmdAnswer(created);
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
            }
            client.updateVmConfig(node, vmid, ProxmoxVmConfig.startOptions(vm, cpuCapacityMhz(client)));
            waitFor(client, client.startVm(node, vmid));
            return new StartAnswer(cmd);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not start {}: {}", node, vm.getName(), e.getMessage());
            return new StartAnswer(cmd, e.getMessage());
        }
    }

    /** What the node can deliver, cores times MHz; 0 when unknown. */
    private long cpuCapacityMhz(ProxmoxApiClient client) {
        ProxmoxApiClient.NodeStatus status = client.getNodeStatus(node);
        return status == null ? 0 : status.getCpus() * status.getCpuMhz();
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

    Answer stop(StopCommand cmd) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation location = client.findVmByName(cmd.getVmName());
            if (location == null) {
                return new StopAnswer(cmd, "Proxmox has no VM " + cmd.getVmName() + ", nothing to stop", true);
            }
            if (!location.isRunning()) {
                return new StopAnswer(cmd, cmd.getVmName() + " is already stopped", true);
            }
            String task = cmd.isForceStop() ? client.stopVm(location.getNode(), location.getVmid())
                    : client.shutdownVm(location.getNode(), location.getVmid(), SHUTDOWN_TIMEOUT_SECONDS);
            waitFor(client, task);
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
            waitFor(client, client.rebootVm(location.getNode(), location.getVmid()));
            return new RebootAnswer(cmd, "Rebooted " + cmd.getVmName(), true);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Proxmox node {} could not reboot {}: {}", node, cmd.getVmName(), e.getMessage());
            return new RebootAnswer(cmd, e.getMessage(), false);
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
        }
        waitFor(client, client.destroyVm(location.getNode(), vmid));
        LOGGER.info("Destroyed Proxmox VM {} with its disk", vmid);
        return new Answer(cmd, true, "Destroyed Proxmox VM " + vmid);
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

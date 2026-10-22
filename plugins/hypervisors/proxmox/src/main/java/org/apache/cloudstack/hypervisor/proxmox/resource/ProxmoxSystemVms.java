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

import java.util.Collections;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.Duration;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.network.Networks.TrafficType;
import com.cloud.utils.ExecutionResult;

/**
 * What happens after a system VM or a router has been started, which KVM does over SSH from the host (LibvirtStartCommandWrapper) and Proxmox
 * does through the guest agent: wait for the agent, give the VM its boot arguments, wait until the VM has configured itself.
 */
public class ProxmoxSystemVms {
    /** Where the system VM template reads its boot arguments from (init.sh waits for the host to write it). */
    static final String CMDLINE = "/var/cache/cloud/cmdline";
    static final int AGENT_WAIT_SECONDS = 300;
    static final long AGENT_POLL_MILLIS = 2000;
    static final int WRITE_ATTEMPTS = 10;
    static final long WRITE_RETRY_MILLIS = 3000;
    static final int READY_WAIT_SECONDS = 300;
    static final long READY_POLL_MILLIS = 5000;
    /** sshd listens on the control address once the VM has set its network up: what KVM waits for by opening a connection to port 3922. */
    static final String READY_CHECK = "ss -ltn | grep -q ':3922 '";

    /** The directory the system VM reads its patch files from. */
    static final String CACHE_DIR = "/var/cache/cloud/";
    /** What a CD shows: whole sectors of 2048 bytes (lab P3), so the payload is padded to a multiple of it. */
    static final int SECTOR_BYTES = 2048;
    /** The script signature the VM keeps, and whether it has an agent: what cloud-early-config looks at to decide whether to patch. */
    static final String STATE_CHECK = "echo \"SIG=$(cat " + CACHE_DIR + "cloud-scripts-signature 2>/dev/null)\"; "
            + "if [ -n \"$(ls -A /usr/local/cloud/systemvm 2>/dev/null)\" ]; then echo AGENT=yes; else echo AGENT=no; fi";
    static final int STORAGE_ACTIVATION_ATTEMPTS = 15;
    static final long STORAGE_ACTIVATION_WAIT_MS = 2000;
    static final int COPY_WAIT_SECONDS = 300;

    private static final Logger LOGGER = LogManager.getLogger(ProxmoxSystemVms.class);

    private final ProxmoxRouterDeployer deployer;
    private final VirtualRoutingResource routing;
    private final Supplier<ProxmoxPatchFiles> patchFiles;
    private final Supplier<String> patchPath;
    private final Supplier<String> sshKeys;
    private final IntSupplier taskTimeoutSeconds;
    private final LongConsumer pauser;
    private final LongSupplier clock;

    public ProxmoxSystemVms(ProxmoxRouterDeployer deployer, VirtualRoutingResource routing, Supplier<ProxmoxPatchFiles> patchFiles,
            Supplier<String> patchPath, Supplier<String> sshKeys, IntSupplier taskTimeoutSeconds, LongConsumer pauser, LongSupplier clock) {
        this.deployer = deployer;
        this.routing = routing;
        this.patchFiles = patchFiles;
        this.patchPath = patchPath;
        this.sshKeys = sshKeys;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
        this.pauser = pauser;
        this.clock = clock;
    }

    /** The address of the VM's control NIC, the key of every call to the VM; null when it has none. */
    public static String controlIp(VirtualMachineTO vm) {
        if (vm.getNics() != null) {
            for (NicTO nic : vm.getNics()) {
                if (nic.getType() == TrafficType.Control) {
                    return nic.getIp();
                }
            }
        }
        return null;
    }

    /**
     * Before a secondary storage VM or a console proxy VM is started: the file with its agent must be on the node, because the VM needs it in
     * the first minutes of its boot. The file (agent.zip, padded to whole CD sectors) is uploaded once per node and version to the one patch
     * storage entry, which is created when it is missing; files of older versions are removed.
     *
     * @return null when the file is ready, otherwise the reason it is not (the start is then failed with it)
     */
    public String beforeStart(ProxmoxApiClient client, VirtualMachineTO vm, String node) {
        if (!ProxmoxVmConfig.needsAgentPatch(vm)) {
            return null;
        }
        try {
            ProxmoxPatchFiles files = patchFiles.get();
            String storage = ensurePatchStorage(client, node);
            String fileName = ProxmoxStorageNames.patchFileName(files.sha512(ProxmoxPatchFiles.AGENT_ZIP));
            long padded = roundUp(files.size(ProxmoxPatchFiles.AGENT_ZIP));
            ProxmoxApiClient.ImageInfo existing = client.getImageInfo(node, storage + ":iso/" + fileName);
            if (existing == null || existing.getSize() != padded) {
                LOGGER.info("Uploading {} ({} bytes) to the patch storage {} on Proxmox node {}", ProxmoxPatchFiles.AGENT_ZIP, padded, storage, node);
                client.waitForTask(client.uploadIso(node, storage, fileName, files.path(ProxmoxPatchFiles.AGENT_ZIP), SECTOR_BYTES),
                        taskTimeoutSeconds.getAsInt());
                removeOlderPayloads(client, node, storage, fileName);
            }
            return null;
        } catch (ProxmoxApiException e) {
            return String.format("Unable to prepare the system VM patch file on Proxmox node %s: %s. The token needs the privileges of "
                    + "PVEDatastoreAdmin on /storage, and the node room for 110 MiB under %s", node, e.getMessage(), patchPath.get());
        }
    }

    private static long roundUp(long size) {
        return (size + SECTOR_BYTES - 1) / SECTOR_BYTES * SECTOR_BYTES;
    }

    /** The entry is made once, by whoever needs it first; Proxmox makes the directory on a node when the entry is active there. */
    private String ensurePatchStorage(ProxmoxApiClient client, String node) {
        String path = patchPath.get();
        if (path == null || !path.matches("/[-/a-zA-Z0-9_.@]+")) {
            throw new ProxmoxApiException("The setting proxmox.patch.path is '" + path + "', which is not a usable directory path");
        }
        String id = ProxmoxStorageNames.patchStorageId(path);
        if (client.findStorage(id) == null) {
            try {
                client.createPatchStorage(id, path);
            } catch (ProxmoxApiException e) {
                // another start may have made it a moment ago
                if (client.findStorage(id) == null) {
                    throw e;
                }
            }
        }
        for (int attempt = 1; attempt <= STORAGE_ACTIVATION_ATTEMPTS; attempt++) {
            if (client.getStorageStatus(node, id).isActive()) {
                return id;
            }
            pauser.accept(STORAGE_ACTIVATION_WAIT_MS);
        }
        throw new ProxmoxApiException("the storage entry " + id + " did not become active on the node");
    }

    private void removeOlderPayloads(ProxmoxApiClient client, String node, String storage, String keep) {
        try {
            for (ProxmoxApiClient.ContentItem item : client.listContent(node, storage, "iso")) {
                String name = item.getVolid().substring(item.getVolid().indexOf(":iso/") + 5);
                if (name.startsWith(ProxmoxStorageNames.PATCH_FILE_PREFIX) && !name.equals(keep)) {
                    client.deleteImage(node, item.getVolid());
                }
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Could not remove old patch files from {} on node {}: {}", storage, node, e.getMessage());
        }
    }

    /**
     * What cloud-early-config of the VM needs, as KVM copies it over SSH: a secondary storage VM or console proxy VM that has no agent yet (or
     * a different script signature) gets agent.zip, which is taken from the payload CD; it and every router get cloud-scripts.tgz, written last
     * because that file is what the VM waits for, and patch-sysvms.sh. A restart of a patched SSVM or CPVM needs nothing.
     */
    private String deliverPatchFiles(ProxmoxApiClient client, VirtualMachineTO vm, String node, long vmid, String controlIp) {
        ProxmoxPatchFiles files = patchFiles.get();
        boolean agentVm = ProxmoxVmConfig.needsAgentPatch(vm);
        ExecutionResult state = deployer.run(controlIp, STATE_CHECK, null, Duration.standardSeconds(30));
        if (!state.isSuccess()) {
            return "Unable to look at the patch state of " + vm.getName() + ": " + state.getDetails();
        }
        String details = state.getDetails() == null ? "" : state.getDetails();
        boolean sameScripts = ("SIG=" + files.sha512(ProxmoxPatchFiles.SCRIPTS)).equals(details.lines().filter(l -> l.startsWith("SIG=")).findFirst().orElse(""));
        if (agentVm && sameScripts && details.contains("AGENT=yes")) {
            LOGGER.info("{} has the agent and the current scripts, nothing to patch", vm.getName());
            return null;
        }
        if (agentVm) {
            String problem = copyAgent(client, files, vm, node, vmid, controlIp);
            if (problem != null) {
                return problem;
            }
        }
        for (String name : new String[] {ProxmoxPatchFiles.SCRIPTS, ProxmoxPatchFiles.PATCH_SCRIPT}) {
            ExecutionResult written = deployer.writeBytes(controlIp, CACHE_DIR + name, files.bytes(name));
            if (!written.isSuccess()) {
                return String.format("Unable to give %s to %s: %s", name, vm.getName(), written.getDetails());
            }
        }
        LOGGER.info("Gave the patch files to {}", vm.getName());
        return null;
    }

    /** The CD drive that was reserved at start gets the payload as its medium for the copy, and is emptied again afterwards. */
    private String copyAgent(ProxmoxApiClient client, ProxmoxPatchFiles files, VirtualMachineTO vm, String node, long vmid, String controlIp) {
        String sha512 = files.sha512(ProxmoxPatchFiles.AGENT_ZIP);
        long size = files.size(ProxmoxPatchFiles.AGENT_ZIP);
        String volume = ProxmoxStorageNames.patchStorageId(patchPath.get()) + ":iso/" + ProxmoxStorageNames.patchFileName(sha512);
        String target = CACHE_DIR + ProxmoxPatchFiles.AGENT_ZIP;
        String copy = String.format("for i in $(seq 1 30); do [ \"$(cat /sys/block/sr0/size 2>/dev/null || echo 0)\" -ge %d ] && break; sleep 1; done; "
                + "head -c %d /dev/sr0 > %s.part && sha512sum %s.part | cut -d' ' -f1", roundUp(size) / 512, size, target, target);
        try {
            client.updateVmConfig(node, vmid, Collections.singletonMap(ProxmoxVmConfig.PATCH_CDROM, ProxmoxVmConfig.cdromValue(volume)));
            ExecutionResult copied = deployer.run(controlIp, copy, null, Duration.standardSeconds(COPY_WAIT_SECONDS));
            if (!copied.isSuccess()) {
                return String.format("Unable to copy %s from the patch CD in %s: %s", ProxmoxPatchFiles.AGENT_ZIP, vm.getName(), copied.getDetails());
            }
            if (!sha512.equals(copied.getDetails() == null ? "" : copied.getDetails().trim())) {
                return String.format("The copy of %s in %s is damaged: its checksum is not %s", ProxmoxPatchFiles.AGENT_ZIP, vm.getName(), sha512);
            }
            ExecutionResult moved = deployer.run(controlIp, "mv -f " + target + ".part " + target, null, Duration.standardSeconds(30));
            return moved.isSuccess() ? null : "Unable to put " + ProxmoxPatchFiles.AGENT_ZIP + " in place in " + vm.getName() + ": " + moved.getDetails();
        } finally {
            try {
                client.updateVmConfig(node, vmid, Collections.singletonMap(ProxmoxVmConfig.PATCH_CDROM, ProxmoxVmConfig.EMPTY_CDROM));
            } catch (ProxmoxApiException e) {
                LOGGER.warn("Could not eject the patch CD of {}: {}", vm.getName(), e.getMessage());
            }
        }
    }

    /**
     * Brings a system VM that Proxmox has started to the point where CloudStack can use it.
     *
     * @return null when it is ready, otherwise the reason it is not (the start is then failed with it)
     */
    public String afterStart(ProxmoxApiClient client, VirtualMachineTO vm, String node, long vmid) {
        String controlIp = controlIp(vm);
        if (controlIp == null) {
            return "The system VM " + vm.getName() + " has no control NIC";
        }
        if (StringUtils.isBlank(vm.getBootArgs())) {
            return "The system VM " + vm.getName() + " has no boot arguments";
        }
        deployer.register(controlIp, vm.getName());

        try {
            if (!waitForAgent(client, node, vmid)) {
                return String.format("The guest agent of %s did not answer within %d seconds: the system VM template needs qemu-guest-agent, "
                        + "and the VM needs the agent option (set by CloudStack)", vm.getName(), AGENT_WAIT_SECONDS);
            }
            String problem = writeBootArguments(client, node, vmid, vm);
            if (problem != null) {
                return problem;
            }
            problem = deliverPatchFiles(client, vm, node, vmid, controlIp);
            if (problem != null) {
                return problem;
            }
            if (!waitUntilConfigured(controlIp)) {
                return String.format("The system VM %s did not finish configuring itself within %d seconds after it received its boot "
                        + "arguments; see /var/log/cloud.log in the VM", vm.getName(), READY_WAIT_SECONDS);
            }
            installLoginKeys(vm, controlIp);
            if (!routing.isSystemVMSetup(vm.getName(), controlIp)) {
                return "The system VM " + vm.getName() + " did not complete its setup";
            }
            return null;
        } catch (ProxmoxApiException e) {
            return e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the system VM " + vm.getName();
        }
    }

    private boolean waitForAgent(ProxmoxApiClient client, String node, long vmid) {
        long deadline = clock.getAsLong() + AGENT_WAIT_SECONDS * 1000L;
        while (!client.agentPing(node, vmid)) {
            if (clock.getAsLong() >= deadline) {
                return false;
            }
            pauser.accept(AGENT_POLL_MILLIS);
        }
        return true;
    }

    /** The VM waits only a minute for the file once its agent runs, so the first write follows the first answer of the agent at once. */
    private String writeBootArguments(ProxmoxApiClient client, String node, long vmid, VirtualMachineTO vm) {
        ProxmoxApiException last = null;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                client.agentFileWrite(node, vmid, CMDLINE, vm.getBootArgs());
                LOGGER.info("Gave the boot arguments to {} through its guest agent", vm.getName());
                return null;
            } catch (ProxmoxApiException e) {
                last = e;
                pauser.accept(WRITE_RETRY_MILLIS);
            }
        }
        return String.format("Unable to give the boot arguments to %s through its guest agent: %s", vm.getName(), last == null ? "" : last.getMessage());
    }

    /** A public key line: its type, the base64 key and a comment; nothing that could end the quotes of a shell command. */
    static final java.util.regex.Pattern PUBLIC_KEY = java.util.regex.Pattern.compile("(ssh|ecdsa|sk)-[A-Za-z0-9@.\\-]+ [A-Za-z0-9+/=]+( [A-Za-z0-9@._:+=/ \\-]*)?");

    /** The keys of the setting: one per line or separated by a vertical bar, blanks ignored. */
    static java.util.List<String> splitKeys(String setting) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (setting != null) {
            for (String part : setting.split("[|\\r\\n]")) {
                if (!part.trim().isEmpty()) {
                    keys.add(part.trim());
                }
            }
        }
        return keys;
    }

    /**
     * Adds the administrator's public keys (the setting proxmox.systemvm.ssh.key) to the VM's authorized_keys, after cloud-early-config has written
     * that file from the boot arguments, so that a login from a Proxmox node works. A problem with a key or with the call is logged and does not
     * fail the start: it only affects the troubleshooting login. The VM rewrites the file at its next boot from its boot arguments, so the keys are
     * added again at every start.
     */
    private void installLoginKeys(VirtualMachineTO vm, String controlIp) {
        StringBuilder script = new StringBuilder("mkdir -p /root/.ssh && chmod 700 /root/.ssh && touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys");
        int added = 0;
        for (String key : splitKeys(sshKeys.get())) {
            if (!PUBLIC_KEY.matcher(key).matches()) {
                LOGGER.warn("The setting proxmox.systemvm.ssh.key has an entry that is not a usable public key and is ignored: {}",
                        org.apache.commons.lang3.StringUtils.abbreviate(key, 40));
                continue;
            }
            script.append(" && (grep -qxF -- '").append(key).append("' /root/.ssh/authorized_keys || echo '").append(key).append("' >> /root/.ssh/authorized_keys)");
            added++;
        }
        if (added == 0) {
            return;
        }
        ExecutionResult result = deployer.run(controlIp, script.toString(), null, Duration.standardSeconds(30));
        if (result.isSuccess()) {
            LOGGER.info("Added {} login key(s) to {}", added, vm.getName());
        } else {
            LOGGER.warn("Could not add the login keys to {}: {}", vm.getName(), result.getDetails());
        }
    }

    private boolean waitUntilConfigured(String controlIp) {
        long deadline = clock.getAsLong() + READY_WAIT_SECONDS * 1000L;
        while (true) {
            ExecutionResult result = deployer.run(controlIp, READY_CHECK, null, Duration.standardSeconds(30));
            if (result.isSuccess()) {
                return true;
            }
            if (clock.getAsLong() >= deadline) {
                return false;
            }
            pauser.accept(READY_POLL_MILLIS);
        }
    }
}

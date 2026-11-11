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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxVmShell;
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
 * does through the guest agent: wait for the agent, give the VM its boot arguments, patch it over SSH through the node, wait until the VM has
 * configured itself.
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
    /**
     * cloud-early-config has finished: its unit is a oneshot with RemainAfterExit, so it is "activating" while it runs and "active" when it is done.
     * That is when the VM has written its authorized_keys from the boot arguments (the file is replaced, not appended to, so a key added earlier is
     * lost) and set up its network. Waiting for port 3922 to open is not enough: the template's sshd listens on it from the start (lab 2026-10-22).
     */
    static final String READY_CHECK = "[ \"$(systemctl is-active cloud-early-config)\" = active ] && ss -ltn | grep -q ':3922 '";

    /** The directory the system VM reads its patch files from. */
    static final String CACHE_DIR = "/var/cache/cloud/";
    /** The script signature the VM keeps, and whether it has an agent: what cloud-early-config looks at to decide whether to patch. */
    static final String STATE_CHECK = "echo \"SIG=$(cat " + CACHE_DIR + "cloud-scripts-signature 2>/dev/null)\"; "
            + "if [ -n \"$(ls -A /usr/local/cloud/systemvm 2>/dev/null)\" ]; then echo AGENT=yes; else echo AGENT=no; fi";
    /** How long the VM is given to open its SSH port after it has its boot arguments (it waits two minutes for the patch files itself). */
    static final int SHELL_WAIT_SECONDS = 120;
    static final int COMMAND_TIMEOUT_MS = 30_000;
    /** What to look at in a VM that could not be logged in to: its addresses, what listens, the units that bring up the network and sshd, and its own log. */
    static final String DIAGNOSE = "echo '--- addresses'; ip -br addr; echo '--- listening'; ss -ltn; echo '--- units'; "
            + "systemctl is-active ssh cloud-early-config cloud; echo '--- cloud.log'; tail -n 25 /var/log/cloud.log";
    static final int UPLOAD_TIMEOUT_MS = 300_000;

    private static final Logger LOGGER = LogManager.getLogger(ProxmoxSystemVms.class);

    private final ProxmoxRouterDeployer deployer;
    private final VirtualRoutingResource routing;
    private final Supplier<ProxmoxPatchFiles> patchFiles;
    private final Supplier<String> sshKeys;
    private final Function<String, ProxmoxVmShell> shells;
    private final LongConsumer pauser;
    private final LongSupplier clock;

    /**
     * @param shells opens a login in a VM by its control address (an SSH tunnel through the node the VM runs on, see TunnelVmShell); the VM is given
     *               {@link #SHELL_WAIT_SECONDS} to answer
     */
    public ProxmoxSystemVms(ProxmoxRouterDeployer deployer, VirtualRoutingResource routing, Supplier<ProxmoxPatchFiles> patchFiles,
            Supplier<String> sshKeys, Function<String, ProxmoxVmShell> shells, LongConsumer pauser, LongSupplier clock) {
        this.deployer = deployer;
        this.routing = routing;
        this.patchFiles = patchFiles;
        this.sshKeys = sshKeys;
        this.shells = shells;
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
     * What cloud-early-config of the VM needs, as KVM copies it over SSH: a secondary storage VM or console proxy VM that has no agent yet (or a
     * different script signature) gets agent.zip; it and every router get cloud-scripts.tgz and patch-sysvms.sh. The files are copied through the
     * node (TunnelVmShell) as {@code .part} files, each checked against the checksum of the management server's file, and moved into place together,
     * cloud-scripts.tgz last, because that is the file the VM waits for. A restart of a patched SSVM or CPVM needs nothing.
     *
     * @return null when the VM has what it needs, otherwise the reason it has not (the start is then failed with it)
     */
    private String deliverPatchFiles(VirtualMachineTO vm, String controlIp) {
        ProxmoxPatchFiles files = patchFiles.get();
        boolean agentVm = ProxmoxVmConfig.needsAgentPatch(vm);
        long begin = clock.getAsLong();
        try (ProxmoxVmShell shell = shells.apply(controlIp)) {
            ExecResult state = shell.exec(STATE_CHECK, COMMAND_TIMEOUT_MS);
            if (!state.isSuccess()) {
                return "Unable to look at the patch state of " + vm.getName() + ": " + state.describeFailure();
            }
            String details = state.getStdout();
            boolean sameScripts = ("SIG=" + files.sha512(ProxmoxPatchFiles.SCRIPTS)).equals(details.lines().filter(l -> l.startsWith("SIG=")).findFirst().orElse(""));
            if (agentVm && sameScripts && details.contains("AGENT=yes")) {
                LOGGER.info("{} has the agent and the current scripts, nothing to patch", vm.getName());
                return null;
            }
            List<String> names = new ArrayList<>(agentVm ? Arrays.asList(ProxmoxPatchFiles.AGENT_ZIP) : new ArrayList<String>());
            names.add(ProxmoxPatchFiles.PATCH_SCRIPT);
            names.add(ProxmoxPatchFiles.SCRIPTS);
            StringBuilder move = new StringBuilder("cd " + CACHE_DIR);
            for (String name : names) {
                String problem = copyChecked(shell, files, vm, name);
                if (problem != null) {
                    return problem;
                }
                move.append(" && mv -f ").append(name).append(".part ").append(name);
            }
            ExecResult moved = shell.exec(move.toString(), COMMAND_TIMEOUT_MS);
            if (!moved.isSuccess()) {
                return "Unable to put the patch files in place in " + vm.getName() + ": " + moved.describeFailure();
            }
            LOGGER.info("Gave {} to {} over SSH in {} s", names, vm.getName(), seconds(begin, clock.getAsLong()));
            return null;
        } catch (ProxmoxSshException e) {
            logGuestState(vm, controlIp);
            return String.format("Unable to give the patch files to %s over SSH: %s", vm.getName(), e.getMessage());
        }
    }

    /** The guest agent still answers when the SSH login does not: its report of the VM goes to the log, to tell a VM that did not come up from a tunnel that did not work. */
    private void logGuestState(VirtualMachineTO vm, String controlIp) {
        try {
            ExecutionResult state = deployer.run(controlIp, DIAGNOSE, null, Duration.standardSeconds(30));
            LOGGER.error("The SSH login to {} failed; what the VM reports through its guest agent (success {}):\n{}", vm.getName(), state.isSuccess(), state.getDetails());
        } catch (RuntimeException e) {
            LOGGER.error("The SSH login to {} failed and the guest agent could not tell why: {}", vm.getName(), e.getMessage());
        }
    }

    /** One file into the VM as {@code <name>.part}, and a check that what arrived is what the management server has. */
    private String copyChecked(ProxmoxVmShell shell, ProxmoxPatchFiles files, VirtualMachineTO vm, String name) {
        String sha512 = files.sha512(name);
        String part = CACHE_DIR + name + ".part";
        shell.upload(files.path(name), part, UPLOAD_TIMEOUT_MS);
        ExecResult sum = shell.exec("sha512sum " + part + " | cut -d' ' -f1", COMMAND_TIMEOUT_MS);
        if (!sum.isSuccess()) {
            return String.format("Unable to check the copy of %s in %s: %s", name, vm.getName(), sum.describeFailure());
        }
        if (!sha512.equals(sum.getStdout().trim())) {
            return String.format("The copy of %s in %s is damaged: its checksum is not %s", name, vm.getName(), sha512);
        }
        return null;
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

        long begin = clock.getAsLong();
        try {
            if (!waitForAgent(client, node, vmid)) {
                return String.format("The guest agent of %s did not answer within %d seconds: the system VM template needs qemu-guest-agent, "
                        + "and the VM needs the agent option (set by CloudStack)", vm.getName(), AGENT_WAIT_SECONDS);
            }
            long agent = clock.getAsLong();
            String problem = writeBootArguments(client, node, vmid, vm);
            if (problem != null) {
                return problem;
            }
            long arguments = clock.getAsLong();
            problem = deliverPatchFiles(vm, controlIp);
            if (problem != null) {
                return problem;
            }
            long patched = clock.getAsLong();
            if (!waitUntilConfigured(controlIp)) {
                return String.format("The system VM %s did not finish configuring itself within %d seconds after it received its boot "
                        + "arguments; see /var/log/cloud.log in the VM", vm.getName(), READY_WAIT_SECONDS);
            }
            long configured = clock.getAsLong();
            installLoginKeys(vm, controlIp);
            long keys = clock.getAsLong();
            if (!routing.isSystemVMSetup(vm.getName(), controlIp)) {
                return "The system VM " + vm.getName() + " did not complete its setup";
            }
            long end = clock.getAsLong();
            LOGGER.info("{} is ready {} s after Proxmox started it: guest agent after {} s, boot arguments {} s, patch files {} s, configured {} s, "
                    + "login keys {} s, setup check {} s", vm.getName(), seconds(begin, end), seconds(begin, agent), seconds(agent, arguments),
                    seconds(arguments, patched), seconds(patched, configured), seconds(configured, keys), seconds(keys, end));
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

    private static long seconds(long from, long to) {
        return (to - from) / 1000;
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

    /** Waits until the VM has configured itself (after a reboot too: the unit is "activating" while it runs); false after {@code READY_WAIT_SECONDS}. */
    public boolean waitForConfigured(String controlIp) {
        return waitUntilConfigured(controlIp);
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

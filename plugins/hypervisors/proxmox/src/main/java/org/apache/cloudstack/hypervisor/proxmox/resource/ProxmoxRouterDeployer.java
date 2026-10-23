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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.Duration;

import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.IpAssocVpcCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.SetSourceNatCommand;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.resource.virtualnetwork.VRScripts;
import com.cloud.agent.resource.virtualnetwork.VirtualRouterDeployer;
import com.cloud.utils.ExecutionResult;

/**
 * How core's {@code VirtualRoutingResource} reaches a system VM or a router on Proxmox: through the QEMU guest agent of the VM, by the API,
 * instead of SSH to the VM's link-local address (which the management server cannot reach).
 * <p>
 * Core passes the VM's control address around as the key of every call. The address is never connected to: it is looked up in a map
 * (filled when the VM is started and by every network command, which carries the VM's name) that gives the VM name, and the VM is found
 * in the cluster by that name, so the call works on whichever node the VM runs on.
 */
public class ProxmoxRouterDeployer implements VirtualRouterDeployer {
    static final String SCRIPT_DIR = "/opt/cloud/bin/";
    /** A call carries at most 64 KiB of standard input; 48,000 bytes make 64,000 characters of base64. */
    static final int CHUNK_BYTES = 48000;
    static final long POLL_MILLIS = 300;
    /** The default public interface of the router of an isolated network: guest eth0, control eth1, public eth2. */
    static final int DEFAULT_PUBLIC_NIC = 2;

    private static final Logger LOGGER = LogManager.getLogger(ProxmoxRouterDeployer.class);

    private final Supplier<ProxmoxApiClient> clients;
    private final LongConsumer pauser;
    private final LongSupplier clock;
    private final Map<String, String> vmNames = new ConcurrentHashMap<>();

    public ProxmoxRouterDeployer(Supplier<ProxmoxApiClient> clients, LongConsumer pauser, LongSupplier clock) {
        this.clients = clients;
        this.pauser = pauser;
        this.clock = clock;
    }

    /** Remembers which VM a control address belongs to. */
    public void register(String controlIp, String vmName) {
        if (StringUtils.isNoneBlank(controlIp, vmName)) {
            vmNames.put(controlIp, vmName);
        }
    }

    ProxmoxApiClient.VmLocation locate(ProxmoxApiClient client, String controlIp) {
        String vmName = vmNames.get(controlIp);
        if (vmName == null) {
            throw new ProxmoxApiException("No Proxmox VM is known for the address " + controlIp);
        }
        ProxmoxApiClient.VmLocation location = client.findVmByName(vmName);
        if (location == null) {
            throw new ProxmoxApiException("Proxmox has no VM " + vmName);
        }
        return location;
    }

    @Override
    public ExecutionResult executeInVR(String routerIp, String script, String args) {
        return executeInVR(routerIp, script, args, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
    }

    @Override
    public ExecutionResult executeInVR(String routerIp, String script, String args, Duration timeout) {
        return run(routerIp, SCRIPT_DIR + script + (StringUtils.isEmpty(args) ? "" : " " + args), null, timeout);
    }

    /** Runs a shell command line in the VM; the result is successful when it exits with 0. */
    ExecutionResult run(String routerIp, String commandLine, String input, Duration timeout) {
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation vm = locate(client, routerIp);
            long pid = client.agentExec(vm.getNode(), vm.getVmid(), Arrays.asList("/bin/sh", "-c", commandLine), input);
            long deadline = clock.getAsLong() + timeout.getMillis();
            while (true) {
                ProxmoxApiClient.AgentExecStatus status = client.agentExecStatus(vm.getNode(), vm.getVmid(), pid);
                if (status.isExited()) {
                    boolean success = status.getExitCode() == 0;
                    String details = StringUtils.isNotEmpty(status.getOut()) || success ? status.getOut() : status.getErr();
                    return new ExecutionResult(success, details);
                }
                if (clock.getAsLong() >= deadline) {
                    return new ExecutionResult(false, String.format("The command in %s did not finish within %d seconds: %s", vm.getName(),
                            timeout.getStandardSeconds(), StringUtils.abbreviate(commandLine, 200)));
                }
                pauser.accept(POLL_MILLIS);
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Unable to run a command in the VM with the address {}: {}", routerIp, e.getMessage());
            return new ExecutionResult(false, e.getMessage());
        }
    }

    @Override
    public ExecutionResult createFileInVR(String routerIp, String path, String filename, String content) {
        String file = path.endsWith("/") ? path + filename : path + "/" + filename;
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (data.length <= CHUNK_BYTES) {
                ProxmoxApiClient client = clients.get();
                ProxmoxApiClient.VmLocation vm = locate(client, routerIp);
                client.agentFileWrite(vm.getNode(), vm.getVmid(), file, content);
                return new ExecutionResult(true, "Created file " + file);
            }
            // one call can carry only so much: the file is appended piece by piece
            return writeBytes(routerIp, file, data);
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Unable to create the file {} in the VM with the address {}: {}", file, routerIp, e.getMessage());
            return new ExecutionResult(false, e.getMessage());
        }
    }

    /**
     * Writes a file of any size (and any content, it travels as base64) in the VM: appended piece by piece to a temporary name and moved into
     * place when complete, so that a VM that watches for the file (cloud-early-config waits for cloud-scripts.tgz) never sees half of it.
     */
    ExecutionResult writeBytes(String routerIp, String file, byte[] data) {
        String partial = file + ".part";
        ExecutionResult result = run(routerIp, "rm -f " + quote(partial), null, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
        for (int offset = 0; result.isSuccess() && offset < data.length; offset += CHUNK_BYTES) {
            byte[] piece = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + CHUNK_BYTES));
            result = run(routerIp, "base64 -d >> " + quote(partial), Base64.getEncoder().encodeToString(piece), VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
        }
        if (result.isSuccess()) {
            result = run(routerIp, "mv -f " + quote(partial) + " " + quote(file), null, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
        }
        return result.isSuccess() ? new ExecutionResult(true, "Created file " + file) : result;
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    @Override
    public ExecutionResult prepareCommand(NetworkElementCommand cmd) {
        // the address core uses for the router; here it is only the key that finds the VM by name
        cmd.setRouterAccessIp(cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP));
        register(cmd.getRouterAccessIp(), cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME));
        try {
            if (cmd instanceof IpAssocVpcCommand) {
                return prepareIpAssocVpc((IpAssocVpcCommand) cmd);
            } else if (cmd instanceof IpAssocCommand) {
                return prepareIpAssoc((IpAssocCommand) cmd);
            } else if (cmd instanceof SetupGuestNetworkCommand) {
                return prepareGuestNetwork((SetupGuestNetworkCommand) cmd);
            } else if (cmd instanceof SetSourceNatCommand) {
                return prepareSourceNat((SetSourceNatCommand) cmd);
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Unable to prepare {} for the VM with the address {}: {}", cmd.getClass().getSimpleName(), cmd.getRouterAccessIp(), e.getMessage());
            return new ExecutionResult(false, e.getMessage());
        }
        return new ExecutionResult(true, null);
    }

    private List<ProxmoxRouterNics.Nic> nics(ProxmoxApiClient client, ProxmoxApiClient.VmLocation vm) {
        return ProxmoxRouterNics.parse(client.getVmConfig(vm.getNode(), vm.getVmid()));
    }

    /** Core says which interface each public IP lives on by its MAC address; the guest's number for it is the position of that MAC. */
    private ExecutionResult prepareIpAssocVpc(IpAssocVpcCommand cmd) {
        ProxmoxApiClient client = clients.get();
        List<ProxmoxRouterNics.Nic> nics = nics(client, locate(client, cmd.getRouterAccessIp()));
        for (IpAddressTO ip : cmd.getIpAddresses()) {
            ip.setNicDevId(ProxmoxRouterNics.numberOf(nics, ip.getVifMacAddress()));
        }
        return new ExecutionResult(true, null);
    }

    /**
     * As KVM does: an IP whose interface (by MAC address) the router does not have yet gets one hot-plugged (on the bridge of the traffic label
     * and with the VLAN of the IP), and the router's network usage counters are told about every interface.
     */
    private ExecutionResult prepareIpAssoc(IpAssocCommand cmd) {
        ProxmoxApiClient client = clients.get();
        ProxmoxApiClient.VmLocation vm = locate(client, cmd.getRouterAccessIp());
        List<ProxmoxRouterNics.Nic> nics = nics(client, vm);
        for (IpAddressTO ip : cmd.getIpAddresses()) {
            boolean newNic = false;
            Integer number = ProxmoxRouterNics.numberOf(nics, ip.getVifMacAddress());
            if (number == null) {
                ExecutionResult plugged = plug(client, vm, cmd.getRouterAccessIp(), nics, ip);
                if (!plugged.isSuccess()) {
                    return plugged;
                }
                nics = nics(client, vm);
                number = ProxmoxRouterNics.numberOf(nics, ip.getVifMacAddress());
                newNic = true;
            }
            if (number == null) {
                return new ExecutionResult(false, "The interface with the MAC address " + ip.getVifMacAddress() + " is not in the configuration of " + vm.getName());
            }
            run(cmd.getRouterAccessIp(), SCRIPT_DIR + "netusage.sh -a eth" + number, null, Duration.standardSeconds(60));
            ip.setNicDevId(number);
            ip.setNewNic(newNic);
        }
        return new ExecutionResult(true, null);
    }

    private ExecutionResult plug(ProxmoxApiClient client, ProxmoxApiClient.VmLocation vm, String routerIp, List<ProxmoxRouterNics.Nic> nics, IpAddressTO ip) {
        String bridge = ip.getNetworkName();
        if (StringUtils.isBlank(bridge)) {
            return new ExecutionResult(false, "The public IP " + ip.getPublicIp() + " has no bridge (the KVM traffic label of the public network) for a new interface of " + vm.getName());
        }
        StringBuilder net = new StringBuilder("virtio=").append(ip.getVifMacAddress()).append(",bridge=").append(bridge);
        String tag = ip.getBroadcastUri() == null ? null : ProxmoxVmConfig.vlanTag(URI.create(ip.getBroadcastUri()));
        if (tag != null) {
            net.append(",tag=").append(tag);
        }
        net.append(",firewall=0");
        client.updateVmConfig(vm.getNode(), vm.getVmid(), java.util.Collections.singletonMap("net" + ProxmoxRouterNics.nextIndex(nics), net.toString()));
        LOGGER.info("Plugged a network interface with the MAC address {} into {}", ip.getVifMacAddress(), vm.getName());
        // the guest sees it a moment later; the scripts that follow need it
        String wait = "for i in $(seq 1 30); do ip -o link | grep -qi " + ip.getVifMacAddress() + " && exit 0; sleep 1; done; exit 1";
        ExecutionResult seen = run(routerIp, wait, null, Duration.standardSeconds(60));
        return seen.isSuccess() ? seen : new ExecutionResult(false, "The new interface " + ip.getVifMacAddress() + " did not show up in " + vm.getName());
    }

    private ExecutionResult prepareGuestNetwork(SetupGuestNetworkCommand cmd) {
        NicTO nic = cmd.getNic();
        ProxmoxApiClient client = clients.get();
        ProxmoxApiClient.VmLocation vm = locate(client, cmd.getRouterAccessIp());
        if (ProxmoxRouterNics.numberOf(nics(client, vm), nic.getMac()) == null) {
            return new ExecutionResult(false, "Can not find nic with mac " + nic.getMac() + " for VM " + vm.getName());
        }
        // a tier that was just plugged shows up in the guest a moment later; the configuration that follows needs it
        ExecutionResult seen = run(cmd.getRouterAccessIp(), "for i in $(seq 1 30); do ip -o link | grep -qi " + nic.getMac() + " && exit 0; sleep 1; done; exit 1", null,
                Duration.standardSeconds(60));
        return seen.isSuccess() ? seen : new ExecutionResult(false, "The interface " + nic.getMac() + " did not show up in " + vm.getName());
    }

    private ExecutionResult prepareSourceNat(SetSourceNatCommand cmd) {
        ProxmoxApiClient client = clients.get();
        ProxmoxApiClient.VmLocation vm = locate(client, cmd.getRouterAccessIp());
        IpAddressTO ip = cmd.getIpAddress();
        Integer number = ProxmoxRouterNics.numberOf(nics(client, vm), ip.getVifMacAddress());
        if (number == null) {
            return new ExecutionResult(false, "Unable to find the interface with the MAC address " + ip.getVifMacAddress() + " for the source NAT of " + vm.getName());
        }
        ip.setNicDevId(number);
        return new ExecutionResult(true, "success");
    }

    /**
     * When the last public IP of an interface is released the interface is removed again, except {@code eth2}, the default public interface of the
     * router of an isolated network (the same rule as on KVM).
     */
    @Override
    public ExecutionResult cleanupCommand(NetworkElementCommand cmd) {
        if (!(cmd instanceof IpAssocCommand) || cmd instanceof IpAssocVpcCommand) {
            return new ExecutionResult(true, null);
        }
        IpAssocCommand assoc = (IpAssocCommand) cmd;
        if (!"true".equalsIgnoreCase(cmd.getAccessDetail(NetworkElementCommand.NETWORK_PUB_LAST_IP))) {
            return new ExecutionResult(true, null);
        }
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation vm = locate(client, cmd.getRouterAccessIp());
            for (IpAddressTO ip : assoc.getIpAddresses()) {
                if (ip.isAdd()) {
                    continue;
                }
                List<ProxmoxRouterNics.Nic> nics = nics(client, vm);
                Integer number = ProxmoxRouterNics.numberOf(nics, ip.getVifMacAddress());
                if (number != null && number != DEFAULT_PUBLIC_NIC) {
                    client.updateVmConfig(vm.getNode(), vm.getVmid(), java.util.Collections.singletonMap("delete", "net" + ProxmoxRouterNics.indexAt(nics, number)));
                    run(cmd.getRouterAccessIp(), SCRIPT_DIR + "netusage.sh -d eth" + number, null, Duration.standardSeconds(60));
                    LOGGER.info("Removed the network interface eth{} of {}", number, vm.getName());
                }
            }
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Unable to clean up after {}: {}", cmd.getClass().getSimpleName(), e.getMessage());
            return new ExecutionResult(false, e.getMessage());
        }
        return new ExecutionResult(true, null);
    }
}

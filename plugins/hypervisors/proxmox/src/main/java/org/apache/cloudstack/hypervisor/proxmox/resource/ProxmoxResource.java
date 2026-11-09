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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.hypervisor.proxmox.ProxmoxConfig;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.hypervisor.proxmox.ssh.TrileadSshLink;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.RevertSnapshotCommand;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateStoragePoolCommand;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.GetStorageStatsAnswer;
import com.cloud.agent.api.GetStorageStatsCommand;
import com.cloud.agent.api.GetVmWebsocketConsoleCommand;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.HostStatsEntry;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.RebootRouterCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.UpdateHostPasswordCommand;
import com.cloud.agent.api.storage.DestroyCommand;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.storage.ResizeVolumeCommand;
import com.cloud.agent.api.check.CheckSshAnswer;
import com.cloud.agent.api.check.CheckSshCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Networks;
import com.cloud.resource.ServerResource;
import com.cloud.storage.Storage;
import com.cloud.vm.VirtualMachine;

/**
 * Represents one Proxmox VE node as a CloudStack host. The resource runs inside the management server and
 * talks to the Proxmox REST API; nothing is installed on the node.
 */
public class ProxmoxResource implements ServerResource {
    public static final String DETAIL_NODE = "node";
    public static final String DETAIL_NODE_IP = "nodeip";
    public static final String DETAIL_ENDPOINT = "endpoint";
    public static final String DETAIL_ENDPOINTS = "endpoints";
    /** The SSH login for the commands the API cannot run (design-phase10-ssh-access.md). The password is kept encrypted by the plugin: only "password" is encrypted by core. */
    public static final String DETAIL_SSH_USER = "sshuser";
    public static final String DETAIL_SSH_PASSWORD = "sshpassword";
    public static final String DETAIL_SSH_FINGERPRINT = "sshfingerprint";
    /** The login the resource uses for the commands that run on the node (volume snapshots). Put in the parameters at every connect, never stored under these names. */
    public static final String PARAM_SSH_USER = "ssh.login.user";
    public static final String PARAM_SSH_PASSWORD = "ssh.login.password";
    public static final String PARAM_SSH_KEY = "ssh.login.key";
    protected static final String CAPABILITIES = "hvm";
    /** A new storage entry is mounted by the node a moment after it is added, so its status is polled for a while. */
    protected static final int STORAGE_ACTIVATION_ATTEMPTS = 15;
    protected static final long STORAGE_ACTIVATION_WAIT_MS = 2000;
    protected static final String STORAGE_CONTENT = "images";
    /** How long the guest agent of a started system VM is waited for after the start answer: 60 times 2 seconds. */
    static final int CHECK_SSH_ATTEMPTS = 60;
    static final long CHECK_SSH_POLL_MILLIS = 2000;

    protected Logger logger = LogManager.getLogger(getClass());

    private final Host.Type type = Host.Type.Routing;
    protected String dcId;
    protected String pod;
    protected String cluster;
    protected String name;
    protected String guid;
    protected String node;
    protected String nodeIp;
    protected String sshFingerprint;
    protected volatile String sshUser;
    protected volatile String sshPassword;
    protected String sshPrivateKey;
    protected String endpoint;
    protected List<String> endpoints = new ArrayList<>();
    protected String tokenId;
    protected String tokenSecret;
    protected volatile ProxmoxApiClient client;
    private ProxmoxVmHandler vmHandler;
    private ProxmoxVmSnapshots vmSnapshots;
    private ProxmoxVolumeSnapshots volumeSnapshots;
    private ProxmoxTemplates templates;
    private ProxmoxRouterDeployer routerDeployer;
    private VirtualRoutingResource routingResource;
    private ProxmoxSystemVms systemVms;
    private Map<String, Object> configParams;
    private IAgentControl agentControl;
    private int runLevel;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        this.name = name;
        this.configParams = params;
        dcId = (String) params.get("zone");
        pod = (String) params.get("pod");
        cluster = (String) params.get("cluster");
        guid = (String) params.get("guid");
        node = (String) params.get(DETAIL_NODE);
        nodeIp = (String) params.get(DETAIL_NODE_IP);
        sshFingerprint = (String) params.get(DETAIL_SSH_FINGERPRINT);
        sshUser = (String) params.get(PARAM_SSH_USER);
        sshPassword = (String) params.get(PARAM_SSH_PASSWORD);
        sshPrivateKey = (String) params.get(PARAM_SSH_KEY);
        tokenId = (String) params.get("username");
        tokenSecret = (String) params.get("password");
        endpoint = (String) params.get(DETAIL_ENDPOINT);
        if (StringUtils.isBlank(endpoint)) {
            String url = (String) params.get("url");
            if (StringUtils.isBlank(url)) {
                throw new ConfigurationException("Unable to find the Proxmox API URL for host " + name);
            }
            endpoint = ProxmoxApiClient.endpointOf(URI.create(url.contains("://") ? url : "https://" + url));
        }
        endpoints = new ArrayList<>();
        endpoints.add(endpoint);
        String extra = (String) params.get(DETAIL_ENDPOINTS);
        if (StringUtils.isNotBlank(extra)) {
            for (String candidate : extra.split(",")) {
                if (StringUtils.isNotBlank(candidate) && !endpoints.contains(candidate.trim())) {
                    endpoints.add(candidate.trim());
                }
            }
        }
        if (StringUtils.isAnyBlank(node, tokenId, tokenSecret)) {
            throw new ConfigurationException("Proxmox node name and API token are required for host " + name);
        }
        client = createClient(tokenId, tokenSecret);
        vmHandler = new ProxmoxVmHandler(() -> client, node, nodeIp, apiPort(), () -> "PVEAPIToken=" + tokenId + "=" + tokenSecret, this::pause,
                ProxmoxConfig.TaskTimeout::value, ProxmoxConfig.MigrationTimeout::value, ProxmoxConfig.HaManage::value);
        vmSnapshots = new ProxmoxVmSnapshots(() -> client, ProxmoxConfig.TaskTimeout::value);
        ProxmoxSecondaryMounts secondaryMounts = new ProxmoxSecondaryMounts(ProxmoxConfig.SecondaryMountOptions::value);
        volumeSnapshots = new ProxmoxVolumeSnapshots(() -> client, this::openLogin, secondaryMounts, ProxmoxConfig.TaskTimeout::value);
        templates = new ProxmoxTemplates(() -> client, node, this::openLogin, secondaryMounts, ProxmoxConfig.TaskTimeout::value, this::pause);
        vmHandler.setTemplates(templates);
        vmHandler.setFullClone(poolId -> Boolean.TRUE.equals(ProxmoxConfig.CreateFullClone.valueIn(poolId)));
        vmHandler.setIsos(new ProxmoxIsos(() -> client, node, this::openLogin, secondaryMounts, ProxmoxConfig.IsoPath::value, this::pause));
        routerDeployer = new ProxmoxRouterDeployer(() -> client, this::pause, System::currentTimeMillis);
        routingResource = new VirtualRoutingResource(routerDeployer);
        routingResource.configure(name, params);
        vmHandler.setControlBridge(ProxmoxConfig.ControlBridge::value);
        systemVms = new ProxmoxSystemVms(routerDeployer, routingResource, ProxmoxPatchFiles::locate, ProxmoxConfig.PatchPath::value,
                ProxmoxConfig.SystemVmSshKey::value, ProxmoxConfig.TaskTimeout::value, this::pause, System::currentTimeMillis);
        vmHandler.setSystemVms(systemVms);
        return true;
    }

    /** The API port of the cluster, from the configured endpoint ("https://10.0.35.25:8006"). */
    private int apiPort() {
        try {
            int port = URI.create(endpoint).getPort();
            return port == -1 ? ProxmoxApiClient.DEFAULT_PORT : port;
        } catch (IllegalArgumentException e) {
            return ProxmoxApiClient.DEFAULT_PORT;
        }
    }

    protected ProxmoxApiClient createClient(String id, String secret) {
        return new ProxmoxApiClient(endpoints, id, secret, ProxmoxConfig.ApiVerifyTls.value(), ProxmoxConfig.ApiConnectTimeout.value(),
                ProxmoxConfig.ApiReadTimeout.value());
    }

    @Override
    public Host.Type getType() {
        return type;
    }

    @Override
    public StartupCommand[] initialize() {
        ProxmoxApiClient.NodeStatus status;
        try {
            status = client.getNodeStatus(node);
        } catch (ProxmoxApiException e) {
            logger.error("Unable to read the status of Proxmox node {}: {}", node, e.getMessage());
            return null;
        }
        StartupRoutingCommand cmd = new StartupRoutingCommand(status.getCpus(), status.getCpuMhz(), status.getMemoryTotalBytes(),
                0L, CAPABILITIES, Hypervisor.HypervisorType.Proxmox, Networks.RouterPrivateIpStrategy.HostLocal);
        cmd.setCpuSockets(status.getSockets());
        cmd.setDataCenter(dcId);
        cmd.setPod(pod);
        cmd.setCluster(cluster);
        cmd.setHostType(type);
        cmd.setName(name);
        cmd.setGuid(guid);
        cmd.setPrivateIpAddress(nodeIp);
        cmd.setStorageIpAddress(nodeIp);
        cmd.setHypervisorVersion(status.getPveVersion());
        cmd.setVersion(ProxmoxResource.class.getPackage().getImplementationVersion());
        Map<String, String> hostDetails = new HashMap<>();
        hostDetails.put(DETAIL_NODE, node);
        // IDE has four drives; without this core allows one CD-ROM per VM
        hostDetails.put(Host.HOST_CDROM_MAX_COUNT, Integer.toString(ProxmoxVmConfig.CDROM_DRIVES.length));
        cmd.setHostDetails(hostDetails);
        return new StartupCommand[] {cmd};
    }

    @Override
    public PingCommand getCurrentStatus(long id) {
        try {
            return new PingRoutingCommand(type, id, getHostVmStateReport());
        } catch (ProxmoxApiException e) {
            logger.warn("Proxmox node {} is not reachable through the API: {}", node, e.getMessage());
            return null;
        }
    }

    /**
     * Reports the VMs that are <b>running</b> on this node right now, keyed by VM name (the CloudStack instance name), as KVM does (a stopped VM
     * is simply not in the report). A VM that exists in Proxmox but is not running is not reported as PowerOff: a VM that Proxmox HA has just
     * moved to this node appears here as stopped for a few seconds before it is started, a VM that was created for a root volume exists before it
     * is first started, and CloudStack treats a PowerOff report as proof of an out-of-band stop (it then releases the VM's resources, lab 2026-10-20).
     * A VM that really stopped on its own is found by CloudStack's missing-report rule after about two ping intervals.
     */
    protected Map<String, HostVmStateReportEntry> getHostVmStateReport() {
        Map<String, HostVmStateReportEntry> report = new HashMap<>();
        List<ProxmoxApiClient.VmInfo> vms = client.listVms(node);
        for (ProxmoxApiClient.VmInfo vm : vms) {
            if (StringUtils.isBlank(vm.getName()) || !vm.isRunning()) {
                continue;
            }
            report.put(vm.getName(), new HostVmStateReportEntry(VirtualMachine.PowerState.PowerOn, node));
        }
        return report;
    }

    @Override
    public Answer executeRequest(Command cmd) {
        try {
            if (cmd instanceof ReadyCommand) {
                return new ReadyAnswer((ReadyCommand) cmd);
            } else if (cmd instanceof CheckHealthCommand) {
                return execute((CheckHealthCommand) cmd);
            } else if (cmd instanceof PingTestCommand) {
                return execute((PingTestCommand) cmd);
            } else if (cmd instanceof CheckNetworkCommand) {
                return new CheckNetworkAnswer((CheckNetworkCommand) cmd, true, "Network setup check by names is done");
            } else if (cmd instanceof MaintainCommand) {
                return new MaintainAnswer((MaintainCommand) cmd);
            } else if (cmd instanceof GetHostStatsCommand) {
                return execute((GetHostStatsCommand) cmd);
            } else if (cmd instanceof UpdateHostPasswordCommand) {
                return execute((UpdateHostPasswordCommand) cmd);
            } else if (cmd instanceof CreateStoragePoolCommand) {
                // As on KVM nothing is done here: the storage entry is created, with the pool's mount options, when the
                // pool is connected to a host (ModifyStoragePoolCommand), which follows for every host.
                return new Answer(cmd, true, "The Proxmox storage entry is created when the pool is connected to the hosts");
            } else if (cmd instanceof ModifyStoragePoolCommand) {
                return execute((ModifyStoragePoolCommand) cmd);
            } else if (cmd instanceof DeleteStoragePoolCommand) {
                return execute((DeleteStoragePoolCommand) cmd);
            } else if (cmd instanceof GetStorageStatsCommand) {
                return execute((GetStorageStatsCommand) cmd);
            } else if (cmd instanceof CopyCommand) {
                if (ProxmoxVolumeSnapshots.isSnapshot(((CopyCommand) cmd).getSrcTO())) {
                    return volumeSnapshots.copy((CopyCommand) cmd);
                }
                return vmHandler.copy((CopyCommand) cmd);
            } else if (cmd instanceof MigrateVolumeCommand) {
                return vmHandler.migrateVolume((MigrateVolumeCommand) cmd);
            } else if (cmd instanceof DeleteCommand) {
                if (ProxmoxVolumeSnapshots.isSnapshot(((DeleteCommand) cmd).getData())) {
                    return volumeSnapshots.delete((DeleteCommand) cmd);
                }
                return vmHandler.delete((DeleteCommand) cmd);
            } else if (cmd instanceof CreateObjectCommand) {
                if (ProxmoxVolumeSnapshots.isSnapshot(((CreateObjectCommand) cmd).getData())) {
                    return volumeSnapshots.take((CreateObjectCommand) cmd);
                }
                return vmHandler.createObject((CreateObjectCommand) cmd);
            } else if (cmd instanceof DestroyCommand) {
                // core's eviction of an unused template from a pool: the template VM that caches it
                return templates.destroy((DestroyCommand) cmd);
            } else if (cmd instanceof RevertSnapshotCommand) {
                return volumeSnapshots.revert((RevertSnapshotCommand) cmd);
            } else if (cmd instanceof AttachCommand) {
                return vmHandler.attach((AttachCommand) cmd);
            } else if (cmd instanceof DettachCommand) {
                return vmHandler.detach((DettachCommand) cmd);
            } else if (cmd instanceof ResizeVolumeCommand) {
                return vmHandler.resize((ResizeVolumeCommand) cmd);
            } else if (cmd instanceof GetVolumeStatsCommand) {
                return vmHandler.volumeStats((GetVolumeStatsCommand) cmd);
            } else if (cmd instanceof GetVmWebsocketConsoleCommand) {
                return vmHandler.console((GetVmWebsocketConsoleCommand) cmd);
            } else if (cmd instanceof PrepareForMigrationCommand) {
                return vmHandler.prepareForMigration((PrepareForMigrationCommand) cmd);
            } else if (cmd instanceof MigrateCommand) {
                return vmHandler.migrate((MigrateCommand) cmd);
            } else if (cmd instanceof CreateVMSnapshotCommand) {
                return vmSnapshots.create((CreateVMSnapshotCommand) cmd);
            } else if (cmd instanceof DeleteVMSnapshotCommand) {
                return vmSnapshots.delete((DeleteVMSnapshotCommand) cmd);
            } else if (cmd instanceof RevertToVMSnapshotCommand) {
                return vmSnapshots.revert((RevertToVMSnapshotCommand) cmd);
            } else if (cmd instanceof CheckVirtualMachineCommand) {
                return vmHandler.checkVirtualMachine((CheckVirtualMachineCommand) cmd);
            } else if (cmd instanceof StartCommand) {
                return vmHandler.start((StartCommand) cmd);
            } else if (cmd instanceof StopCommand) {
                // before RebootCommand: a StopCommand is a RebootCommand
                return vmHandler.stop((StopCommand) cmd);
            } else if (cmd instanceof PlugNicCommand) {
                return vmHandler.plugNic((PlugNicCommand) cmd);
            } else if (cmd instanceof UnPlugNicCommand) {
                return vmHandler.unplugNic((UnPlugNicCommand) cmd);
            } else if (cmd instanceof RebootRouterCommand) {
                // before RebootCommand: a RebootRouterCommand is a RebootCommand
                return execute((RebootRouterCommand) cmd);
            } else if (cmd instanceof NetworkUsageCommand) {
                return ProxmoxNetworkUsage.execute((NetworkUsageCommand) cmd, routerDeployer);
            } else if (cmd instanceof RebootCommand) {
                return vmHandler.reboot((RebootCommand) cmd);
            } else if (cmd instanceof NetworkElementCommand) {
                return routingResource.executeRequest((NetworkElementCommand) cmd);
            } else if (cmd instanceof CheckSshCommand) {
                return execute((CheckSshCommand) cmd);
            }
            return Answer.createUnsupportedCommandAnswer(cmd);
        } catch (ProxmoxApiException e) {
            logger.error("Proxmox API error while executing {}: {}", cmd.getClass().getSimpleName(), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    /**
     * Reboots a router and waits until it has configured itself again, then has its traffic counters made, as the KVM host does after it can
     * reach the router (the router configures itself at boot from its saved boot arguments; nothing is copied).
     */
    private Answer execute(RebootRouterCommand cmd) {
        routerDeployer.register(cmd.getPrivateIpAddress(), cmd.getVmName());
        Answer answer = vmHandler.reboot(cmd);
        if (!answer.getResult()) {
            return answer;
        }
        if (!systemVms.waitForConfigured(cmd.getPrivateIpAddress())) {
            return new Answer(cmd, false, "Virtual router " + cmd.getVmName() + " did not finish configuring itself after the reboot");
        }
        ProxmoxNetworkUsage.usage(routerDeployer, cmd.getPrivateIpAddress(), "create", null, null);
        return answer;
    }

    /**
     * Sent after a system VM or router has started: on KVM it opens a connection to the VM's SSH port, trying for minutes. The management
     * server cannot reach the VM's control address, so the guest agent is asked instead, and for up to {@code CHECK_SSH_ATTEMPTS} times
     * 2 seconds: a router that has just been configured can leave its agent unanswered for a short while (lab 2026-10-23: "The guest agent of
     * r-58-VM does not answer" right after a start that had succeeded).
     */
    private Answer execute(CheckSshCommand cmd) {
        routerDeployer.register(cmd.getIp(), cmd.getName());
        String problem = null;
        for (int attempt = 1; attempt <= CHECK_SSH_ATTEMPTS; attempt++) {
            try {
                ProxmoxApiClient.VmLocation vm = routerDeployer.locate(client, cmd.getIp());
                if (client.agentPing(vm.getNode(), vm.getVmid())) {
                    return new CheckSshAnswer(cmd);
                }
                problem = "The guest agent of " + cmd.getName() + " does not answer";
            } catch (ProxmoxApiException e) {
                problem = e.getMessage();
            }
            if (attempt < CHECK_SSH_ATTEMPTS) {
                pause(CHECK_SSH_POLL_MILLIS);
            }
        }
        problem += " (tried for " + CHECK_SSH_ATTEMPTS * CHECK_SSH_POLL_MILLIS / 1000 + " seconds)";
        logger.warn("{} is not reachable: {}", cmd.getName(), problem);
        return new CheckSshAnswer(cmd, problem);
    }

    /**
     * CloudStack's host investigators send this to the other hosts to ask "can you reach this address?", to tell a
     * dead machine from a dead agent. It must not answer yes blindly: the address is reachable if it belongs to a
     * node that the Proxmox cluster currently reports online.
     */
    private Answer execute(PingTestCommand cmd) {
        String address = cmd.getComputingHostIp();
        if (StringUtils.isBlank(address)) {
            return new Answer(cmd, false, "Pinging a router or VM address is not supported for Proxmox hosts");
        }
        try {
            for (ProxmoxApiClient.NodeInfo nodeInfo : client.getClusterNodes(null)) {
                if (address.equals(nodeInfo.getIp())) {
                    return new Answer(cmd, nodeInfo.isOnline(), String.format("Proxmox node %s is %s", nodeInfo.getName(),
                            nodeInfo.isOnline() ? "online" : "offline"));
                }
            }
            return new Answer(cmd, false, address + " is not a node of this Proxmox cluster");
        } catch (ProxmoxApiException e) {
            return new Answer(cmd, false, "Unable to check " + address + ": " + e.getMessage());
        }
    }

    /**
     * Sent after updateHostPassword with update_passwd_on_host. For Proxmox the username and password of a host are its SSH login (the API token is made
     * by the plugin and kept with the cluster, and is not changed here). Nothing is changed on the node: the login is checked with the node, so that a
     * wrong password is reported now and not when the first command that needs a shell fails. The login is stored by CloudStack before this command.
     */
    private Answer execute(UpdateHostPasswordCommand cmd) {
        if (StringUtils.isAnyBlank(nodeIp, sshFingerprint)) {
            return new Answer(cmd, false, "Proxmox node " + node + " has no stored SSH host key: it was added before SSH logins were kept; delete the host and add it again");
        }
        SshTarget target = new SshTarget(nodeIp, ProxmoxSsh.DEFAULT_PORT, cmd.getUsername(), null, cmd.getNewPassword(), sshFingerprint);
        try (ProxmoxSsh ssh = openSsh(target)) {
            logger.info("The SSH login {} works on Proxmox node {}", cmd.getUsername(), node);
            // the login that the commands on the node use from now on, before the next connect rebuilds it from the host details
            sshUser = cmd.getUsername();
            sshPassword = cmd.getNewPassword();
            return new Answer(cmd, true, "The SSH login " + cmd.getUsername() + " works on Proxmox node " + node);
        } catch (ProxmoxSshException e) {
            logger.warn("The new SSH login for Proxmox node {} was not accepted: {}", node, e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    /** A login to this node for a command that runs there; the host key is the one kept when the host was added. */
    private ProxmoxSsh openLogin() {
        if (StringUtils.isAnyBlank(nodeIp, sshUser, sshFingerprint) || StringUtils.isAllBlank(sshPrivateKey, sshPassword)) {
            throw new ProxmoxSshException("Proxmox node " + node + " has no SSH login or host key for the commands that run on the node: delete the host and add it again");
        }
        return openSsh(new SshTarget(nodeIp, ProxmoxSsh.DEFAULT_PORT, sshUser, sshPrivateKey, sshPassword, sshFingerprint));
    }

    protected ProxmoxSsh openSsh(SshTarget target) {
        return ProxmoxSsh.open(target, () -> new TrileadSshLink(target.getHost(), target.getPort()));
    }

    /**
     * Connects this node to an NFS primary storage pool. The storage entry is cluster-wide, so the first call (from whichever host
     * CloudStack asks first) creates it and the others find it; each call then waits until this node has it mounted and reports
     * the capacity.
     */
    private Answer execute(ModifyStoragePoolCommand cmd) {
        StorageFilerTO pool = cmd.getPool();
        if (pool.getType() != Storage.StoragePoolType.NetworkFilesystem) {
            return new ModifyStoragePoolAnswer(cmd, false, "Only NFS primary storage is supported for Proxmox, not " + pool.getType());
        }
        if (!cmd.getAdd()) {
            // The entry belongs to the whole Proxmox cluster and is removed by DeleteStoragePoolCommand.
            return new ModifyStoragePoolAnswer(cmd, true, "The Proxmox storage entry is cluster-wide and is left in place");
        }
        String id = ProxmoxStorageNames.storageId(pool.getUuid());
        String mountPath = ProxmoxStorageNames.mountPath(pool.getUuid());
        String options = cmd.getDetails() == null ? null : cmd.getDetails().get(ApiConstants.NFS_MOUNT_OPTIONS);
        try {
            String conflict = ensureStorage(id, pool.getHost(), pool.getPath(), mountPath, options);
            if (conflict != null) {
                return new ModifyStoragePoolAnswer(cmd, false, conflict);
            }
            ProxmoxApiClient.StorageStatus status = waitForStorage(id);
            if (status == null) {
                return new ModifyStoragePoolAnswer(cmd, false, String.format("Proxmox node %s did not mount %s:%s as storage %s within %d seconds; "
                        + "check that the NFS server exports it to this node", node, pool.getHost(), pool.getPath(), id,
                        STORAGE_ACTIVATION_ATTEMPTS * STORAGE_ACTIVATION_WAIT_MS / 1000));
            }
            ModifyStoragePoolAnswer answer = new ModifyStoragePoolAnswer(cmd, status.getTotalBytes(), status.getAvailableBytes(), new HashMap<>());
            answer.setPoolInfo(new StoragePoolInfo(pool.getUuid(), pool.getHost(), pool.getPath(), mountPath, pool.getType(),
                    status.getTotalBytes(), status.getAvailableBytes()));
            return answer;
        } catch (ProxmoxApiException e) {
            logger.error("Unable to connect storage {} on Proxmox node {}: {}", id, node, e.getMessage());
            return new ModifyStoragePoolAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Makes sure the cluster has the storage entry for a pool.
     *
     * @return null when the entry exists with the expected server and export, otherwise the reason it cannot be used
     */
    private String ensureStorage(String id, String server, String export, String mountPath, String options) {
        ProxmoxApiClient.StorageConfig existing = client.findStorage(id);
        if (existing == null) {
            try {
                client.createNfsStorage(id, server, export, mountPath, STORAGE_CONTENT, options);
                logger.info("Created Proxmox storage {} for {}:{}", id, server, export);
                return null;
            } catch (ProxmoxApiException e) {
                // another host's command may have created it a moment ago
                existing = client.findStorage(id);
                if (existing == null) {
                    throw e;
                }
            }
        }
        return describeConflict(existing, id, server, export);
    }

    static String describeConflict(ProxmoxApiClient.StorageConfig existing, String id, String server, String export) {
        if (!"nfs".equals(existing.getType()) || !StringUtils.equals(existing.getServer(), server)
                || !StringUtils.equals(StringUtils.removeEnd(existing.getExport(), "/"), StringUtils.removeEnd(export, "/"))) {
            return String.format("Proxmox already has a storage entry %s for %s:%s (type %s); it does not match %s:%s", id, existing.getServer(),
                    existing.getExport(), existing.getType(), server, export);
        }
        return null;
    }

    /** Waits until this node reports the storage active; returns its status, or null if it never became active. */
    private ProxmoxApiClient.StorageStatus waitForStorage(String id) {
        ProxmoxApiClient.StorageStatus status = null;
        for (int attempt = 0; attempt < STORAGE_ACTIVATION_ATTEMPTS; attempt++) {
            try {
                status = client.getStorageStatus(node, id);
                if (status.isActive()) {
                    return status;
                }
            } catch (ProxmoxApiException e) {
                logger.debug("Storage {} is not ready on node {} yet: {}", id, node, e.getMessage());
            }
            pause(STORAGE_ACTIVATION_WAIT_MS);
        }
        return null;
    }

    protected void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Removes a pool's storage entry from the Proxmox cluster. Safe to repeat: CloudStack sends it to several hosts. */
    private Answer execute(DeleteStoragePoolCommand cmd) {
        String id = ProxmoxStorageNames.storageId(cmd.getPool().getUuid());
        try {
            if (client.findStorage(id) == null) {
                return new Answer(cmd, true, "Proxmox storage " + id + " is already gone");
            }
            client.deleteStorage(id);
            logger.info("Removed Proxmox storage {}", id);
            return new Answer(cmd, true, "Removed Proxmox storage " + id);
        } catch (ProxmoxApiException e) {
            logger.error("Unable to remove storage {}: {}", id, e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    private Answer execute(GetStorageStatsCommand cmd) {
        if (cmd.getStorageId() == null || cmd.getStore() != null) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
        try {
            ProxmoxApiClient.StorageStatus status = client.getStorageStatus(node, ProxmoxStorageNames.storageId(cmd.getStorageId()));
            return new GetStorageStatsAnswer(cmd, status.getTotalBytes(), status.getUsedBytes());
        } catch (ProxmoxApiException e) {
            return new GetStorageStatsAnswer(cmd, e.getMessage());
        }
    }

    private Answer execute(CheckHealthCommand cmd) {
        try {
            client.getNodeStatus(node);
            return new CheckHealthAnswer(cmd, true);
        } catch (ProxmoxApiException e) {
            logger.warn("Health check failed for Proxmox node {}: {}", node, e.getMessage());
            return new CheckHealthAnswer(cmd, false);
        }
    }

    private Answer execute(GetHostStatsCommand cmd) {
        ProxmoxApiClient.NodeStatus status = client.getNodeStatus(node);
        HostStatsEntry entry = new HostStatsEntry();
        entry.setEntityType("host");
        entry.setCpuUtilization(status.getCpuUtilization() * 100);
        entry.setTotalMemoryKBs(status.getMemoryTotalBytes() / 1024.0);
        entry.setFreeMemoryKBs(status.getMemoryFreeBytes() / 1024.0);
        return new GetHostStatsAnswer(cmd, entry);
    }

    @Override
    public void disconnected() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public IAgentControl getAgentControl() {
        return agentControl;
    }

    @Override
    public void setAgentControl(IAgentControl agentControl) {
        this.agentControl = agentControl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void setConfigParams(Map<String, Object> params) {
        this.configParams = params;
    }

    @Override
    public Map<String, Object> getConfigParams() {
        return configParams;
    }

    @Override
    public int getRunLevel() {
        return runLevel;
    }

    @Override
    public void setRunLevel(int level) {
        this.runLevel = level;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        disconnected();
        return true;
    }
}

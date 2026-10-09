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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.HostStatsEntry;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Networks;
import com.cloud.resource.ServerResource;
import com.cloud.vm.VirtualMachine;

/**
 * Represents one Proxmox VE node as a CloudStack host. The resource runs inside the management server and
 * talks to the Proxmox REST API; nothing is installed on the node.
 */
public class ProxmoxResource implements ServerResource {
    public static final String DETAIL_NODE = "node";
    public static final String DETAIL_NODE_IP = "nodeip";
    public static final String DETAIL_VERIFY_TLS = "verifytls";
    public static final String DETAIL_ENDPOINT = "endpoint";
    protected static final String CAPABILITIES = "hvm";

    protected Logger logger = LogManager.getLogger(getClass());

    private final Host.Type type = Host.Type.Routing;
    protected String dcId;
    protected String pod;
    protected String cluster;
    protected String name;
    protected String guid;
    protected String node;
    protected String nodeIp;
    protected String endpoint;
    protected String tokenId;
    protected String tokenSecret;
    protected boolean verifyTls = true;
    protected ProxmoxApiClient client;
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
        tokenId = (String) params.get("username");
        tokenSecret = (String) params.get("password");
        verifyTls = !"false".equalsIgnoreCase((String) params.get(DETAIL_VERIFY_TLS));
        endpoint = (String) params.get(DETAIL_ENDPOINT);
        if (StringUtils.isBlank(endpoint)) {
            String url = (String) params.get("url");
            if (StringUtils.isBlank(url)) {
                throw new ConfigurationException("Unable to find the Proxmox API URL for host " + name);
            }
            endpoint = ProxmoxApiClient.endpointOf(URI.create(url.contains("://") ? url : "https://" + url));
        }
        if (StringUtils.isAnyBlank(node, tokenId, tokenSecret)) {
            throw new ConfigurationException("Proxmox node name and API token are required for host " + name);
        }
        client = createClient();
        return true;
    }

    protected ProxmoxApiClient createClient() {
        return new ProxmoxApiClient(endpoint, tokenId, tokenSecret, verifyTls);
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

    /** Reports only the VMs that are on this node right now, keyed by VM name (the CloudStack instance name). */
    protected Map<String, HostVmStateReportEntry> getHostVmStateReport() {
        Map<String, HostVmStateReportEntry> report = new HashMap<>();
        List<ProxmoxApiClient.VmInfo> vms = client.listVms(node);
        for (ProxmoxApiClient.VmInfo vm : vms) {
            if (StringUtils.isBlank(vm.getName())) {
                continue;
            }
            VirtualMachine.PowerState state = vm.isRunning() ? VirtualMachine.PowerState.PowerOn : VirtualMachine.PowerState.PowerOff;
            report.put(vm.getName(), new HostVmStateReportEntry(state, node));
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
                return new Answer(cmd);
            } else if (cmd instanceof CheckNetworkCommand) {
                return new CheckNetworkAnswer((CheckNetworkCommand) cmd, true, "Network setup check by names is done");
            } else if (cmd instanceof MaintainCommand) {
                return new MaintainAnswer((MaintainCommand) cmd);
            } else if (cmd instanceof GetHostStatsCommand) {
                return execute((GetHostStatsCommand) cmd);
            }
            return Answer.createUnsupportedCommandAnswer(cmd);
        } catch (ProxmoxApiException e) {
            logger.error("Proxmox API error while executing {}: {}", cmd.getClass().getSimpleName(), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
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

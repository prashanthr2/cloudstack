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
package org.apache.cloudstack.hypervisor.proxmox.discoverer;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.hypervisor.proxmox.ProxmoxConfig;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxControlBridge;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxResource;

import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.dc.ClusterVO;
import com.cloud.exception.DiscoveryException;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.resource.Discoverer;
import com.cloud.resource.DiscovererBase;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.utils.UuidUtils;

/**
 * Adds a Proxmox VE cluster to a CloudStack cluster. A single "add host" call with the API URL of any node
 * discovers every online node of the Proxmox cluster and creates one host per node.
 * <p>
 * The username is the API token id ("user@realm!tokenname") and the password is the token secret.
 */
public class ProxmoxServerDiscoverer extends DiscovererBase implements Discoverer, ResourceStateAdapter {

    static final String CLUSTER_GUID_PREFIX = "proxmox-cluster:";

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        return true;
    }

    protected ProxmoxApiClient createClient(String endpoint, String tokenId, String tokenSecret) {
        return new ProxmoxApiClient(Collections.singletonList(endpoint), tokenId, tokenSecret, ProxmoxConfig.ApiVerifyTls.value(),
                ProxmoxConfig.ApiConnectTimeout.value(), ProxmoxConfig.ApiReadTimeout.value());
    }

    /**
     * The identity of a Proxmox cluster as kept in the CloudStack cluster's GUID, so that nodes of a different Proxmox
     * cluster cannot be added to the same CloudStack cluster (XenServer does the same with the pool UUID).
     */
    protected String getClusterIdentity(String proxmoxClusterName, List<ProxmoxApiClient.NodeInfo> nodes) {
        String name = proxmoxClusterName;
        if (name == null || name.isEmpty()) {
            // a standalone node has no cluster name; its own name identifies it
            name = nodes.get(0).getName();
        }
        return CLUSTER_GUID_PREFIX + name;
    }

    protected String getResourceGuid(String proxmoxClusterName, String nodeName) {
        return "Proxmox:" + UuidUtils.nameUUIDFromBytes((proxmoxClusterName + "/" + nodeName).getBytes());
    }

    @Override
    public Map<? extends ServerResource, Map<String, String>> find(long dcId, Long podId, Long clusterId, URI uri,
            String username, String password, List<String> hostTags) throws DiscoveryException {
        if (clusterId == null) {
            throw new DiscoveryException("Must specify cluster Id when adding a Proxmox host");
        }
        if (podId == null) {
            throw new DiscoveryException("Must specify pod when adding a Proxmox host");
        }
        ClusterVO cluster = _clusterDao.findById(clusterId);
        if (cluster == null || cluster.getHypervisorType() != Hypervisor.HypervisorType.Proxmox) {
            throw new DiscoveryException("Invalid cluster id or the cluster is not a Proxmox cluster");
        }

        String endpoint;
        try {
            endpoint = ProxmoxApiClient.endpointOf(uri);
        } catch (ProxmoxApiException e) {
            throw new DiscoveryException(e.getMessage(), e);
        }

        List<ProxmoxApiClient.NodeInfo> nodes;
        String proxmoxClusterName;
        try (ProxmoxApiClient client = createClient(endpoint, username, password)) {
            String version = client.getVersion();
            logger.info("Connected to Proxmox VE {} at {}", version, endpoint);
            proxmoxClusterName = client.getClusterName();
            nodes = client.getClusterNodes(uri.getHost());
        } catch (ProxmoxApiException e) {
            logger.error("Unable to discover the Proxmox cluster at {}: {}", endpoint, e.getMessage());
            throw new DiscoveryException("Unable to connect to the Proxmox API at " + endpoint + ": " + e.getMessage(), e);
        }

        if (nodes.isEmpty()) {
            throw new DiscoveryException("No nodes found at " + endpoint);
        }
        checkAndAdoptClusterIdentity(cluster, getClusterIdentity(proxmoxClusterName, nodes), proxmoxClusterName);

        // Any node answers for the whole cluster, so remember every node's address: the one given first, then the
        // others, to carry on when the node used for the API goes down.
        Set<String> endpointSet = new LinkedHashSet<>();
        endpointSet.add(endpoint);
        int apiPort = URI.create(endpoint).getPort();
        for (ProxmoxApiClient.NodeInfo nodeInfo : nodes) {
            if (nodeInfo.isOnline() && nodeInfo.getIp() != null) {
                endpointSet.add("https://" + nodeInfo.getIp() + ":" + apiPort);
            }
        }
        String endpoints = String.join(",", endpointSet);

        Map<ProxmoxResource, Map<String, String>> resources = new LinkedHashMap<>();
        for (ProxmoxApiClient.NodeInfo nodeInfo : nodes) {
            if (!nodeInfo.isOnline()) {
                logger.warn("Skipping Proxmox node {}: it is offline", nodeInfo.getName());
                continue;
            }
            String guid = getResourceGuid(proxmoxClusterName, nodeInfo.getName());
            if (_hostDao.findByGuid(guid) != null) {
                logger.info("Skipping Proxmox node {}: it is already added", nodeInfo.getName());
                continue;
            }
            ensureControlBridge(endpoint, username, password, nodeInfo.getName());
            Map<String, Object> params = new HashMap<>();
            params.put("username", username);
            params.put("password", password);
            params.put("zone", Long.toString(dcId));
            params.put("pod", Long.toString(podId));
            params.put("cluster", Long.toString(clusterId));
            params.put("guid", guid);
            params.put(ProxmoxResource.DETAIL_NODE, nodeInfo.getName());
            params.put(ProxmoxResource.DETAIL_NODE_IP, nodeInfo.getIp());
            params.put(ProxmoxResource.DETAIL_ENDPOINT, endpoint);
            params.put(ProxmoxResource.DETAIL_ENDPOINTS, endpoints);

            ProxmoxResource resource = new ProxmoxResource();
            try {
                resource.start();
                resource.configure(nodeInfo.getName(), params);
            } catch (ConfigurationException e) {
                logger.error("Unable to configure the resource for Proxmox node {}: {}", nodeInfo.getName(), e.getMessage());
                continue;
            }
            // Host details are all that is kept for a host; the resource is rebuilt from them after a management
            // server restart. The "password" detail is encrypted by the host details DAO.
            Map<String, String> details = new HashMap<>();
            details.put("guid", guid);
            details.put("username", username);
            details.put("password", password);
            details.put(ProxmoxResource.DETAIL_NODE, nodeInfo.getName());
            details.put(ProxmoxResource.DETAIL_NODE_IP, nodeInfo.getIp());
            details.put(ProxmoxResource.DETAIL_ENDPOINT, endpoint);
            details.put(ProxmoxResource.DETAIL_ENDPOINTS, endpoints);
            resources.put(resource, details);
        }
        if (resources.isEmpty()) {
            throw new DiscoveryException("No new online Proxmox nodes found at " + endpoint
                    + ": every online node of the Proxmox cluster is already added");
        }
        return resources;
    }

    /**
     * Looks for the control bridge on the node and creates it when it is missing; when that is not possible the host is not added
     * and the message says what to do. Any node answers for the whole cluster, so one client serves every node.
     */
    private void ensureControlBridge(String endpoint, String tokenId, String tokenSecret, String node) throws DiscoveryException {
        String bridge = ProxmoxConfig.ControlBridge.value();
        try (ProxmoxApiClient client = createClient(endpoint, tokenId, tokenSecret)) {
            ProxmoxControlBridge.Outcome outcome = ProxmoxControlBridge.ensure(client, node, bridge, tokenId, ProxmoxConfig.TaskTimeout.value());
            switch (outcome) {
                case CREATED:
                    logger.info("Created the control bridge {} ({}) on Proxmox node {}", bridge, ProxmoxControlBridge.expectedCidr(), node);
                    break;
                case EXISTS_WITH_OTHER_ADDRESS:
                    logger.warn("The control bridge {} on Proxmox node {} does not have the address {}: system VMs can start, but the "
                            + "troubleshooting login from the node will not work until it has", bridge, node, ProxmoxControlBridge.expectedCidr());
                    break;
                default:
                    logger.info("The control bridge {} exists on Proxmox node {}", bridge, node);
            }
        } catch (ProxmoxApiException e) {
            logger.error("Proxmox node {} cannot be added: {}", node, e.getMessage());
            throw new DiscoveryException(e.getMessage(), e);
        }
    }

    /**
     * A CloudStack cluster maps to one Proxmox cluster. The first node added records which one; later additions from
     * a different Proxmox cluster are refused. A GUID that was not set by this plugin (an old random one) is replaced.
     */
    private void checkAndAdoptClusterIdentity(ClusterVO cluster, String identity, String proxmoxClusterName) throws DiscoveryException {
        String current = cluster.getGuid();
        if (current != null && current.startsWith(CLUSTER_GUID_PREFIX)) {
            if (!current.equals(identity)) {
                throw new DiscoveryException(String.format("CloudStack cluster %s already holds nodes of the Proxmox cluster '%s', but the nodes "
                        + "found belong to '%s'. Use a separate CloudStack cluster for each Proxmox cluster.", cluster.getName(),
                        current.substring(CLUSTER_GUID_PREFIX.length()), identity.substring(CLUSTER_GUID_PREFIX.length())));
            }
            return;
        }
        cluster.setGuid(identity);
        _clusterDao.update(cluster.getId(), cluster);
    }

    @Override
    public void postDiscovery(List<HostVO> hosts, long msId) {
    }

    @Override
    public boolean matchHypervisor(String hypervisor) {
        if (hypervisor == null) {
            return true;
        }
        return getHypervisorType().toString().equalsIgnoreCase(hypervisor);
    }

    @Override
    public Hypervisor.HypervisorType getHypervisorType() {
        return Hypervisor.HypervisorType.Proxmox;
    }

    @Override
    public HostVO createHostVOForConnectedAgent(HostVO host, StartupCommand[] cmd) {
        return null;
    }

    @Override
    public HostVO createHostVOForDirectConnectAgent(HostVO host, StartupCommand[] startup, ServerResource resource,
            Map<String, String> details, List<String> hostTags) {
        StartupCommand firstCmd = startup[0];
        if (!(firstCmd instanceof StartupRoutingCommand)) {
            return null;
        }
        StartupRoutingCommand ssCmd = (StartupRoutingCommand) firstCmd;
        if (ssCmd.getHypervisorType() != Hypervisor.HypervisorType.Proxmox) {
            return null;
        }
        return _resourceMgr.fillRoutingHostVO(host, ssCmd, Hypervisor.HypervisorType.Proxmox, details, hostTags);
    }

    @Override
    public DeleteHostAnswer deleteHost(HostVO host, boolean isForced, boolean isForceDeleteStorage) throws UnableDeleteHostException {
        return new DeleteHostAnswer(true);
    }
}

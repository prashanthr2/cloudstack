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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
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

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        return true;
    }

    protected ProxmoxApiClient createClient(String endpoint, String tokenId, String tokenSecret, boolean verifyTls) {
        return new ProxmoxApiClient(endpoint, tokenId, tokenSecret, verifyTls);
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
        if (cluster.getGuid() == null) {
            cluster.setGuid(UUID.randomUUID().toString());
            _clusterDao.update(clusterId, cluster);
        }

        String endpoint;
        boolean verifyTls;
        try {
            endpoint = ProxmoxApiClient.endpointOf(uri);
            verifyTls = ProxmoxApiClient.isTlsVerificationEnabled(uri);
        } catch (ProxmoxApiException e) {
            throw new DiscoveryException(e.getMessage(), e);
        }

        List<ProxmoxApiClient.NodeInfo> nodes;
        String proxmoxClusterName;
        try (ProxmoxApiClient client = createClient(endpoint, username, password, verifyTls)) {
            String version = client.getVersion();
            logger.info("Connected to Proxmox VE {} at {}", version, endpoint);
            proxmoxClusterName = client.getClusterName();
            nodes = client.getClusterNodes(uri.getHost());
        } catch (ProxmoxApiException e) {
            logger.error("Unable to discover the Proxmox cluster at {}: {}", endpoint, e.getMessage());
            throw new DiscoveryException("Unable to connect to the Proxmox API at " + endpoint + ": " + e.getMessage(), e);
        }

        Map<ProxmoxResource, Map<String, String>> resources = new LinkedHashMap<>();
        for (ProxmoxApiClient.NodeInfo nodeInfo : nodes) {
            if (!nodeInfo.isOnline()) {
                logger.warn("Skipping Proxmox node {}: it is offline", nodeInfo.getName());
                continue;
            }
            String guid = getResourceGuid(proxmoxClusterName, nodeInfo.getName());
            Map<String, Object> params = new HashMap<>();
            params.put("username", username);
            params.put("password", password);
            params.put("zone", Long.toString(dcId));
            params.put("pod", Long.toString(podId));
            params.put("cluster", Long.toString(clusterId));
            params.put("guid", guid);
            params.put(ProxmoxResource.DETAIL_NODE, nodeInfo.getName());
            params.put(ProxmoxResource.DETAIL_NODE_IP, nodeInfo.getIp());
            params.put(ProxmoxResource.DETAIL_VERIFY_TLS, Boolean.toString(verifyTls));
            params.put(ProxmoxResource.DETAIL_ENDPOINT, endpoint);

            ProxmoxResource resource = new ProxmoxResource();
            try {
                resource.start();
                resource.configure(nodeInfo.getName(), params);
            } catch (ConfigurationException e) {
                logger.error("Unable to configure the resource for Proxmox node {}: {}", nodeInfo.getName(), e.getMessage());
                continue;
            }
            Map<String, String> details = new HashMap<>();
            details.put("guid", guid);
            details.put(ProxmoxResource.DETAIL_NODE, nodeInfo.getName());
            details.put(ProxmoxResource.DETAIL_NODE_IP, nodeInfo.getIp());
            details.put(ProxmoxResource.DETAIL_VERIFY_TLS, Boolean.toString(verifyTls));
            details.put(ProxmoxResource.DETAIL_ENDPOINT, endpoint);
            resources.put(resource, details);
        }
        if (resources.isEmpty()) {
            throw new DiscoveryException("No online Proxmox nodes found at " + endpoint);
        }
        return resources;
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

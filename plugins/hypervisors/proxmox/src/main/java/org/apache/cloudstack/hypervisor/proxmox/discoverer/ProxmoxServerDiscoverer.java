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

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.hypervisor.proxmox.ProxmoxConfig;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxControlBridge;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxResource;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxApiAccess;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.hypervisor.proxmox.ssh.TrileadSshLink;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.exception.DiscoveryException;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.resource.Discoverer;
import com.cloud.resource.DiscovererBase;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Adds a Proxmox VE cluster to a CloudStack cluster. A single "add host" call with the API URL of any node
 * discovers every online node of the Proxmox cluster and creates one host per node.
 * <p>
 * The username is the API token id ("user@realm!tokenname") and the password is the token secret.
 */
public class ProxmoxServerDiscoverer extends DiscovererBase implements Discoverer, ResourceStateAdapter {

    static final String CLUSTER_GUID_PREFIX = "proxmox-cluster:";

    @Inject
    HypervisorCapabilitiesDao hypervisorCapabilitiesDao;
    @Inject
    ClusterDetailsDao clusterDetailsDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        return true;
    }

    static final int SSH_PORT = 22;

    protected ProxmoxSsh openSsh(SshTarget target) {
        return ProxmoxSsh.open(target, () -> new TrileadSshLink(target.getHost(), target.getPort()));
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

    /**
     * Adding a host: the user name and password (or the management server's key, setting {@code ssh.privatekey}, with no password) are a login on the
     * node, as for a KVM host. Over SSH the plugin makes the API user and one token per node it adds (ProxmoxApiAccess), keeps the host key of each node
     * and the login, and from then on uses the API with the token. design-phase10-ssh-access.md.
     */
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

        String privateKey = _configDao.getValue("ssh.privatekey");
        ProxmoxSsh ssh;
        try {
            ssh = openSsh(new SshTarget(uri.getHost(), SSH_PORT, username, privateKey, password, null));
        } catch (ProxmoxSshException e) {
            logger.error("Unable to log in to the Proxmox node at {}: {}", uri.getHost(), e.getMessage());
            throw new DiscoveryException(e.getMessage(), e);
        }
        try {
            return discover(dcId, podId, clusterId, uri, endpoint, cluster, ssh, username, password, privateKey);
        } catch (ProxmoxSshException e) {
            logger.error("Unable to set up the Proxmox API access at {}: {}", uri.getHost(), e.getMessage());
            throw new DiscoveryException(e.getMessage(), e);
        } finally {
            ssh.close();
        }
    }

    private Map<? extends ServerResource, Map<String, String>> discover(long dcId, Long podId, Long clusterId, URI uri, String endpoint, ClusterVO cluster,
            ProxmoxSsh ssh, String username, String password, String privateKey) throws DiscoveryException {
        // One API token serves the whole Proxmox cluster, and the plugin keeps it with the CloudStack cluster (cluster_details, the secret encrypted like
        // a host's); a host added later in the cluster reads it from there. Only the first host of a cluster makes it over SSH.
        String tokenId = clusterDetailsDao.findDetail(cluster.getId(), "username") == null ? null : clusterDetailsDao.findDetail(cluster.getId(), "username").getValue();
        String tokenSecret = clusterDetailsDao.findDetail(cluster.getId(), "password") == null ? null : clusterDetailsDao.findDetail(cluster.getId(), "password").getValue();
        List<ProxmoxApiClient.NodeInfo> nodes;
        String proxmoxClusterName;
        boolean madeToken = false;
        for (int attempt = 1; ; attempt++) {
            if (StringUtils.isAnyBlank(tokenId, tokenSecret)) {
                String name = proxmoxClusterNameOf(ssh);
                ProxmoxApiAccess.Token token = ProxmoxApiAccess.createClusterToken(ssh, name, ProxmoxConfig.HaManage.value());
                tokenId = token.getId();
                tokenSecret = token.getSecret();
                madeToken = true;
            }
            try (ProxmoxApiClient client = createClient(endpoint, tokenId, tokenSecret)) {
                String version = client.getVersion();
                logger.info("Connected to Proxmox VE {} at {}", version, endpoint);
                proxmoxClusterName = client.getClusterName();
                nodes = client.getClusterNodes(uri.getHost());
                break;
            } catch (ProxmoxApiException e) {
                if (!madeToken && attempt == 1 && e.getStatusCode() == 401) {
                    // the token of the cluster was removed or its secret changed: make a new one (the other hosts take it up when they reconnect)
                    logger.warn("The API token {} of this cluster is not accepted by Proxmox any more: making a new one", tokenId);
                    tokenId = null;
                    tokenSecret = null;
                    continue;
                }
                logger.error("Unable to discover the Proxmox cluster at {}: {}", endpoint, e.getMessage());
                throw new DiscoveryException("Unable to connect to the Proxmox API at " + endpoint + ": " + e.getMessage(), e);
            }
        }

        if (nodes.isEmpty()) {
            throw new DiscoveryException("No nodes found at " + endpoint);
        }
        checkAndAdoptClusterIdentity(cluster, getClusterIdentity(proxmoxClusterName, nodes), proxmoxClusterName);
        if (madeToken) {
            clusterDetailsDao.persist(cluster.getId(), "username", tokenId);
            clusterDetailsDao.persist(cluster.getId(), "password", tokenSecret);
        }

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
            // the same login must work on every node: the host key of each node is kept, and a node that refuses the login is not added
            String fingerprint;
            boolean loginNode = uri.getHost().equals(nodeInfo.getIp());
            if (loginNode) {
                fingerprint = ssh.getHostKeyFingerprint();
            } else {
                try (ProxmoxSsh other = openSsh(new SshTarget(nodeInfo.getIp(), SSH_PORT, username, privateKey, password, null))) {
                    fingerprint = other.getHostKeyFingerprint();
                } catch (ProxmoxSshException e) {
                    logger.warn("Skipping Proxmox node {}: the SSH login does not work there: {}", nodeInfo.getName(), e.getMessage());
                    continue;
                }
            }
            ProxmoxApiAccess.grantNode(ssh, tokenId, nodeInfo.getName());
            ensureControlBridge(endpoint, tokenId, tokenSecret, nodeInfo.getName());
            Map<String, Object> params = new HashMap<>();
            params.put("username", tokenId);
            params.put("password", tokenSecret);
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
            // Host details are kept for a host and the resource is rebuilt from them after a management server restart, together with the cluster's
            // token (buildConfigParams). The SSH password is not under the name "password", which is the one core encrypts, so the plugin encrypts it itself.
            Map<String, String> details = new HashMap<>();
            details.put("guid", guid);
            details.put(ProxmoxResource.DETAIL_NODE, nodeInfo.getName());
            details.put(ProxmoxResource.DETAIL_NODE_IP, nodeInfo.getIp());
            details.put(ProxmoxResource.DETAIL_ENDPOINT, endpoint);
            details.put(ProxmoxResource.DETAIL_ENDPOINTS, endpoints);
            details.put(ProxmoxResource.DETAIL_SSH_USER, username);
            if (StringUtils.isNotEmpty(password)) {
                details.put(ProxmoxResource.DETAIL_SSH_PASSWORD, DBEncryptionUtil.encrypt(password));
            }
            details.put(ProxmoxResource.DETAIL_SSH_FINGERPRINT, fingerprint);
            resources.put(resource, details);
        }
        if (resources.isEmpty()) {
            throw new DiscoveryException("No new online Proxmox nodes found at " + endpoint
                    + ": every online node of the Proxmox cluster is already added, or the SSH login does not work on the others");
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

    /** The resource of a host is rebuilt from its details and the token of its cluster, which is kept with the cluster and not with each host. */
    @Override
    protected HashMap<String, Object> buildConfigParams(HostVO host) {
        HashMap<String, Object> params = super.buildConfigParams(host);
        if (host.getClusterId() != null) {
            Map<String, String> clusterDetails = clusterDetailsDao.findDetails(host.getClusterId());
            for (String key : new String[] {"username", "password"}) {
                // a host added before the token moved to the cluster keeps its own
                if (!params.containsKey(key) && clusterDetails.get(key) != null) {
                    params.put(key, clusterDetails.get(key));
                }
            }
        }
        return params;
    }

    /** The name the cluster's token is made after: the Proxmox cluster, or the node itself when it is not in one. */
    String proxmoxClusterNameOf(ProxmoxSsh ssh) {
        ExecResult status = ssh.sudo("pvesh get /cluster/status --output-format json", null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (status.isSuccess()) {
            try {
                for (JsonNode entry : new ObjectMapper().readTree(status.getStdout())) {
                    if ("cluster".equals(entry.path("type").asText()) && !entry.path("name").asText().isEmpty()) {
                        return entry.path("name").asText();
                    }
                }
            } catch (IOException e) {
                logger.warn("The cluster status of the Proxmox node could not be read: {}", e.getMessage());
            }
        }
        return ssh.sudoOrFail("hostname").trim();
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
        ensureCapabilities(ssCmd.getHypervisorVersion());
        return _resourceMgr.fillRoutingHostVO(host, ssCmd, Hypervisor.HypervisorType.Proxmox, details, hostTags);
    }

    /**
     * Core looks the capabilities of a host up by its exact version ("8.4.21") and does not fall back to "default" when it decides whether a volume or an
     * instance can be moved to another storage, so each version a host reports needs its own row. It is made from the "default" row, with storage
     * motion on (the plugin moves volumes and instances with Proxmox's move_disk and migration), when the host is added and whenever it connects.
     * A failure here does not stop the host from connecting.
     */
    void ensureCapabilities(String version) {
        if (hypervisorCapabilitiesDao == null || version == null || version.isEmpty()) {
            return;
        }
        try {
            if (hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(Hypervisor.HypervisorType.Proxmox, version) != null) {
                return;
            }
            HypervisorCapabilitiesVO defaults = hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(Hypervisor.HypervisorType.Proxmox, "default");
            if (defaults == null) {
                logger.warn("There is no default capabilities row for Proxmox, so none is made for version {}", version);
                return;
            }
            HypervisorCapabilitiesVO row = new HypervisorCapabilitiesVO(defaults);
            row.setHypervisorVersion(version);
            row.setStorageMotionSupported(true);
            hypervisorCapabilitiesDao.persist(row);
            logger.info("Added the hypervisor capabilities of Proxmox {} (storage motion supported)", version);
        } catch (RuntimeException e) {
            logger.warn("Could not add the hypervisor capabilities of Proxmox {}: {}", version, e.getMessage());
        }
    }

    @Override
    public DeleteHostAnswer deleteHost(HostVO host, boolean isForced, boolean isForceDeleteStorage) throws UnableDeleteHostException {
        // the first adapter that answers ends the dispatch, so an answer for another hypervisor's host would hide it from its own adapter
        if (host.getType() != com.cloud.host.Host.Type.Routing || host.getHypervisorType() != Hypervisor.HypervisorType.Proxmox) {
            return null;
        }
        revokeToken(host);
        return new DeleteHostAnswer(true);
    }

    /**
     * The API token belongs to the cluster, so it is removed, over SSH, with the cluster's details, only when the host that is deleted is the last
     * Proxmox host of its CloudStack cluster. Best effort: a host that is deleted because its node is gone cannot be reached, and that does not stop the delete.
     */
    void revokeToken(HostVO host) {
        if (host.getHypervisorType() != Hypervisor.HypervisorType.Proxmox || host.getClusterId() == null) {
            return;
        }
        try {
            for (HostVO other : _hostDao.listByClusterAndHypervisorType(host.getClusterId(), Hypervisor.HypervisorType.Proxmox)) {
                if (other.getId() != host.getId()) {
                    return;
                }
            }
            ClusterDetailsVO tokenDetail = clusterDetailsDao.findDetail(host.getClusterId(), "username");
            if (tokenDetail == null) {
                return;
            }
            _hostDao.loadDetails(host);
            Map<String, String> details = host.getDetails();
            String sshUser = details == null ? null : details.get(ProxmoxResource.DETAIL_SSH_USER);
            String address = details == null ? null : details.get(ProxmoxResource.DETAIL_NODE_IP);
            if (!StringUtils.isAnyBlank(sshUser, address)) {
                String encrypted = details.get(ProxmoxResource.DETAIL_SSH_PASSWORD);
                String sshPassword = StringUtils.isEmpty(encrypted) ? null : DBEncryptionUtil.decrypt(encrypted);
                try (ProxmoxSsh ssh = openSsh(new SshTarget(address, SSH_PORT, sshUser, _configDao.getValue("ssh.privatekey"), sshPassword,
                        details.get(ProxmoxResource.DETAIL_SSH_FINGERPRINT)))) {
                    ProxmoxApiAccess.revokeToken(ssh, tokenDetail.getValue());
                    logger.info("Removed the API token {} of the last Proxmox host of the cluster", tokenDetail.getValue());
                }
            }
            clusterDetailsDao.removeDetail(host.getClusterId(), "username");
            clusterDetailsDao.removeDetail(host.getClusterId(), "password");
        } catch (RuntimeException e) {
            logger.warn("The API token of the cluster of Proxmox host {} was not removed (remove it with: pveum user token remove {} <token name>): {}", host.getName(),
                    ProxmoxApiAccess.USER, e.getMessage());
        }
    }
}

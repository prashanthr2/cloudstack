package org.apache.cloudstack.hypervisor.proxmox.discoverer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxResource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.DiscoveryException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.resource.ServerResource;

public class ProxmoxServerDiscovererTest {
    private static final URI GIVEN = URI.create("http://10.0.35.25:8006");

    private final List<String> createdClients = new ArrayList<>();
    private final List<SshTarget> opened = new ArrayList<>();
    private final Map<String, ScriptedSshLink> links = new HashMap<>();
    private final Map<String, String> clusterDetails = new HashMap<>();
    private ProxmoxApiClient client;
    private ProxmoxServerDiscoverer discoverer;
    private ClusterVO cluster;
    private ClusterDao clusterDao;
    private HostDao hostDao;
    private ConfigurationDao configDao;
    private static final String LOGIN_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPlaceholderKeyForTests+/ cloudstack-systemvm";

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        discoverer = new ProxmoxServerDiscoverer() {
            @Override
            protected ProxmoxApiClient createClient(String endpoint, String tokenId, String tokenSecret) {
                createdClients.add(tokenId + "=" + tokenSecret);
                return ProxmoxServerDiscovererTest.this.client;
            }

            @Override
            protected ProxmoxSsh openSsh(SshTarget target) {
                opened.add(target);
                ScriptedSshLink link = links.computeIfAbsent(target.getHost(), host -> nodeLink());
                return ProxmoxSsh.open(target, () -> link);
            }
        };
        ClusterDetailsDao clusterDetailsDao = mock(ClusterDetailsDao.class);
        when(clusterDetailsDao.findDetail(anyLong(), anyString())).thenAnswer(call -> clusterDetails.containsKey(call.<String>getArgument(1))
                ? new ClusterDetailsVO(call.getArgument(0), call.getArgument(1), clusterDetails.get(call.<String>getArgument(1))) : null);
        when(clusterDetailsDao.findDetails(anyLong())).thenAnswer(call -> new HashMap<>(clusterDetails));
        org.mockito.Mockito.doAnswer(call -> clusterDetails.put(call.getArgument(1), call.getArgument(2))).when(clusterDetailsDao).persist(anyLong(), anyString(), anyString());
        org.mockito.Mockito.doAnswer(call -> clusterDetails.remove(call.<String>getArgument(1))).when(clusterDetailsDao).removeDetail(anyLong(), anyString());
        ReflectionTestUtils.setField(discoverer, "clusterDetailsDao", clusterDetailsDao);
        configDao = mock(ConfigurationDao.class);
        when(configDao.getValue("ssh.privatekey")).thenReturn("MANAGEMENT-KEY");
        ReflectionTestUtils.setField(discoverer, "_configDao", configDao);
        cluster = mock(ClusterVO.class);
        when(cluster.getId()).thenReturn(2L);
        when(cluster.getName()).thenReturn("ProxmoxCluster");
        when(cluster.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.Proxmox);
        when(cluster.getGuid()).thenReturn(null);
        clusterDao = mock(ClusterDao.class);
        when(clusterDao.findById(2L)).thenReturn(cluster);
        hostDao = mock(HostDao.class);
        ReflectionTestUtils.setField(discoverer, "_clusterDao", clusterDao);
        ReflectionTestUtils.setField(discoverer, "_hostDao", hostDao);

        // every node already has the control bridge, unless a test says otherwise
        when(client.getNetworkConfig(anyString())).thenReturn(networkWith("cloud0", "bridge", "169.254.0.1/16", false));
        when(client.getVersion()).thenReturn("8.4.21");
        when(client.getClusterName()).thenReturn("lab");
        when(client.getClusterNodes(anyString())).thenReturn(Arrays.asList(
                new ProxmoxApiClient.NodeInfo("pve1", "10.0.32.196", true),
                new ProxmoxApiClient.NodeInfo("pve2", "10.0.32.197", true),
                new ProxmoxApiClient.NodeInfo("pve3", "10.0.32.198", false)));
    }

    private int tokens;

    /** A node that makes a token for every "user token add" and shows its own host key. */
    private ScriptedSshLink nodeLink() {
        ScriptedSshLink link = new ScriptedSshLink();
        link.fingerprint = "SHA256:key" + links.size();
        link.answers = command -> {
            if (command.contains("user token add")) {
                tokens++;
                String name = command.replaceAll(".*user token add cloudstack@pve (\\S+) .*", "$1");
                return new ExecResult(0, "{\"full-tokenid\":\"cloudstack@pve!" + name + "\",\"value\":\"secret-" + tokens + "\"}", "");
            }
            if (command.startsWith("cat ") && command.contains("cloudstack_systemvm.pub")) {
                return new ExecResult(0, LOGIN_KEY + "\n", "");
            }
            if (command.contains("pvesh get /cluster/status")) {
                return new ExecResult(0, "[{\"type\":\"cluster\",\"name\":\"lab\",\"nodes\":3}]", "");
            }
            return new ExecResult(0, "", "");
        };
        return link;
    }

    private static ProxmoxApiClient.NetworkConfig networkWith(String name, String type, String cidr, boolean pending) {
        return new ProxmoxApiClient.NetworkConfig(Arrays.asList(new ProxmoxApiClient.NetworkInterface("vmbr0", "bridge", "10.0.35.25/20"),
                new ProxmoxApiClient.NetworkInterface(name, type, cidr)), pending);
    }

    private Map<? extends ServerResource, Map<String, String>> find() throws DiscoveryException {
        return discoverer.find(1L, 1L, 2L, GIVEN, "root", "secret", null);
    }

    @Test
    public void everyOnlineNodeBecomesAHostAndKeepsTheCredentialsNeededToReconnect() throws Exception {
        Map<? extends ServerResource, Map<String, String>> found = find();

        assertEquals("offline nodes are skipped", 2, found.size());
        for (Map.Entry<? extends ServerResource, Map<String, String>> entry : found.entrySet()) {
            assertNotNull(entry.getKey());
            Map<String, String> details = entry.getValue();
            // these are rebuilt into the resource after a management server restart
            assertNull("the token is kept with the cluster, not with the host", details.get("username"));
            assertNull(details.get("password"));
            assertEquals("https://10.0.35.25:8006", details.get(ProxmoxResource.DETAIL_ENDPOINT));
            assertNotNull(details.get(ProxmoxResource.DETAIL_NODE));
            assertNotNull(details.get(ProxmoxResource.DETAIL_NODE_IP));
            assertNotNull(details.get("guid"));
            // the given address first, then every online node, so the API stays reachable if one node is lost
            assertEquals("https://10.0.35.25:8006,https://10.0.32.196:8006,https://10.0.32.197:8006",
                    details.get(ProxmoxResource.DETAIL_ENDPOINTS));
        }
    }

    @Test
    public void aPortOtherThanTheDefaultIsUsedForEveryNode() throws Exception {
        Map<? extends ServerResource, Map<String, String>> found = discoverer.find(1L, 1L, 2L, URI.create("http://10.0.35.25:8443"),
                "root@pam!cloudstack", "secret", null);
        for (Map<String, String> details : found.values()) {
            assertEquals("https://10.0.35.25:8443,https://10.0.32.196:8443,https://10.0.32.197:8443",
                    details.get(ProxmoxResource.DETAIL_ENDPOINTS));
        }
    }

    @Test
    public void theFirstAdditionRecordsWhichProxmoxClusterTheCloudStackClusterHolds() throws Exception {
        find();
        verify(cluster).setGuid("proxmox-cluster:lab");
        verify(clusterDao).update(2L, cluster);
    }

    @Test
    public void anOldRandomClusterGuidIsReplaced() throws Exception {
        when(cluster.getGuid()).thenReturn("6f1c2b9e-3b1b-4e7e-9d63-5a4b2c1d0e9f");
        find();
        verify(cluster).setGuid("proxmox-cluster:lab");
    }

    @Test
    public void addingNodesOfTheSameProxmoxClusterAgainLeavesTheGuidAlone() throws Exception {
        when(cluster.getGuid()).thenReturn("proxmox-cluster:lab");
        find();
        verify(cluster, never()).setGuid(anyString());
    }

    @Test
    public void nodesOfAnotherProxmoxClusterAreRefused() throws Exception {
        when(cluster.getGuid()).thenReturn("proxmox-cluster:other");
        try {
            find();
            fail("expected the add to be refused");
        } catch (DiscoveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("other") && e.getMessage().contains("lab"));
        }
        verify(clusterDao, never()).update(anyLong(), any(ClusterVO.class));
    }

    @Test
    public void aStandaloneNodeIsIdentifiedByItsOwnName() throws Exception {
        when(client.getClusterName()).thenReturn("");
        when(client.getClusterNodes(anyString())).thenReturn(Arrays.asList(new ProxmoxApiClient.NodeInfo("solo", "10.0.32.196", true)));
        find();
        verify(cluster).setGuid("proxmox-cluster:solo");
    }

    @Test
    public void nodesThatAreAlreadyAddedAreSkippedSoANewNodeCanBePickedUpByAddingAgain() throws Exception {
        when(hostDao.findByGuid(discoverer.getResourceGuid("lab", "pve1"))).thenReturn(mock(HostVO.class));

        Map<? extends ServerResource, Map<String, String>> found = find();

        assertEquals(1, found.size());
        assertEquals("pve2", found.values().iterator().next().get(ProxmoxResource.DETAIL_NODE));
    }

    @Test
    public void addingAgainWhenEveryNodeIsAlreadyAddedSaysSo() throws Exception {
        when(hostDao.findByGuid(anyString())).thenReturn(mock(HostVO.class));
        try {
            find();
            fail("expected a message that nothing is new");
        } catch (DiscoveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("already added"));
        }
    }

    @Test(expected = DiscoveryException.class)
    public void findFailsWhenNoNodeIsOnline() throws Exception {
        List<ProxmoxApiClient.NodeInfo> allOffline = Arrays.asList(new ProxmoxApiClient.NodeInfo("pve1", "10.0.32.196", false));
        when(client.getClusterNodes(anyString())).thenReturn(allOffline);
        find();
    }

    @Test
    public void anExistingControlBridgeIsLeftAlone() throws Exception {
        find();

        verify(client, never()).createBridge(anyString(), anyString(), anyString());
        verify(client, never()).applyNetworkConfig(anyString(), anyInt());
    }

    @Test
    public void aMissingControlBridgeIsCreatedAndAppliedOnEveryNewNode() throws Exception {
        when(client.getNetworkConfig(anyString())).thenReturn(networkWith("vmbr1", "bridge", null, false));

        Map<? extends ServerResource, Map<String, String>> found = find();

        assertEquals(2, found.size());
        verify(client).createBridge("pve1", "cloud0", "169.254.0.1/16");
        verify(client).createBridge("pve2", "cloud0", "169.254.0.1/16");
        verify(client, times(2)).applyNetworkConfig(anyString(), anyInt());
    }

    @Test
    public void theControlBridgeIsOnlyLookedForOnNodesThatAreAdded() throws Exception {
        when(hostDao.findByGuid(discoverer.getResourceGuid("lab", "pve1"))).thenReturn(mock(HostVO.class));
        when(client.getNetworkConfig(anyString())).thenReturn(networkWith("vmbr1", "bridge", null, false));

        find();

        verify(client, never()).getNetworkConfig("pve1");
        verify(client).createBridge(eq("pve2"), anyString(), anyString());
    }

    @Test
    public void aTokenThatMayNotCreateTheBridgeFailsTheAddWithWhatToDo() throws Exception {
        when(client.getNetworkConfig(anyString())).thenReturn(networkWith("vmbr1", "bridge", null, false));
        doThrow(new ProxmoxApiException("denied", 403, null)).when(client).createBridge(anyString(), anyString(), anyString());
        try {
            find();
            fail("expected the add to fail");
        } catch (DiscoveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Sys.Modify") && e.getMessage().contains("pve1")
                    && e.getMessage().contains("cloud0") && e.getMessage().contains("by hand"));
        }
        verify(client, never()).applyNetworkConfig(anyString(), anyInt());
    }

    private HypervisorCapabilitiesDao capabilitiesWithDefault() {
        HypervisorCapabilitiesDao dao = mock(HypervisorCapabilitiesDao.class);
        HypervisorCapabilitiesVO defaults = new HypervisorCapabilitiesVO(Hypervisor.HypervisorType.Proxmox, "default", 1000L, false, false);
        defaults.setMaxDataVolumesLimit(30);
        when(dao.findByHypervisorTypeAndVersion(Hypervisor.HypervisorType.Proxmox, "default")).thenReturn(defaults);
        ReflectionTestUtils.setField(discoverer, "hypervisorCapabilitiesDao", dao);
        return dao;
    }

    @Test
    public void aHostVersionWithoutACapabilitiesRowGetsOneWithStorageMotionOn() {
        HypervisorCapabilitiesDao dao = capabilitiesWithDefault();

        discoverer.ensureCapabilities("8.4.21");

        org.mockito.ArgumentCaptor<HypervisorCapabilitiesVO> row = org.mockito.ArgumentCaptor.forClass(HypervisorCapabilitiesVO.class);
        verify(dao).persist(row.capture());
        org.junit.Assert.assertEquals("8.4.21", row.getValue().getHypervisorVersion());
        org.junit.Assert.assertTrue(row.getValue().isStorageMotionSupported());
        org.junit.Assert.assertEquals(Integer.valueOf(30), row.getValue().getMaxDataVolumesLimit());
    }

    @Test
    public void aVersionThatHasARowIsLeftAlone() {
        HypervisorCapabilitiesDao dao = capabilitiesWithDefault();
        when(dao.findByHypervisorTypeAndVersion(Hypervisor.HypervisorType.Proxmox, "8.4.21")).thenReturn(mock(HypervisorCapabilitiesVO.class));

        discoverer.ensureCapabilities("8.4.21");

        verify(dao, org.mockito.Mockito.never()).persist(org.mockito.ArgumentMatchers.any(HypervisorCapabilitiesVO.class));
    }

    @Test
    public void aFailureToAddTheRowDoesNotStopTheHost() {
        HypervisorCapabilitiesDao dao = capabilitiesWithDefault();
        when(dao.persist(org.mockito.ArgumentMatchers.any(HypervisorCapabilitiesVO.class))).thenThrow(new RuntimeException("db down"));

        discoverer.ensureCapabilities("8.4.21");
        discoverer.ensureCapabilities(null);
    }

    @Test
    public void theResourceOfANewHostKnowsTheSshHostKeyOfItsNode() throws Exception {
        Map<? extends ServerResource, Map<String, String>> found = find();

        for (Map.Entry<? extends ServerResource, Map<String, String>> entry : found.entrySet()) {
            assertEquals(entry.getValue().get(ProxmoxResource.DETAIL_SSH_FINGERPRINT), ReflectionTestUtils.getField(entry.getKey(), "sshFingerprint"));
            assertNotNull(entry.getValue().get(ProxmoxResource.DETAIL_SSH_FINGERPRINT));
        }
    }

    @Test
    public void addHostPutsTheSystemVmLoginKeyIntoTheSettingWhenItIsEmpty() throws Exception {
        find();

        verify(configDao).update("proxmox.systemvm.ssh.key", LOGIN_KEY);
    }

    @Test
    public void theAdministratorsKeysStayWhenTheLoginKeyIsAdded() throws Exception {
        when(configDao.getValue("proxmox.systemvm.ssh.key")).thenReturn("ssh-rsa AAAAB3Admin me@work");

        find();

        verify(configDao).update("proxmox.systemvm.ssh.key", "ssh-rsa AAAAB3Admin me@work|" + LOGIN_KEY);
    }

    @Test
    public void aSettingThatHasTheLoginKeyIsNotChanged() throws Exception {
        when(configDao.getValue("proxmox.systemvm.ssh.key")).thenReturn(LOGIN_KEY);

        find();

        verify(configDao, org.mockito.Mockito.never()).update(anyString(), anyString());
    }

    @Test
    public void aLoginKeyThatCannotBeMadeDoesNotStopTheHostFromBeingAdded() throws Exception {
        ScriptedSshLink node = nodeLink();
        java.util.function.Function<String, ExecResult> normal = node.answers;
        node.answers = command -> command.contains("cloudstack_systemvm") || command.startsWith("test -f") ? new ExecResult(1, "", "Permission denied") : normal.apply(command);
        links.put("10.0.35.25", node);

        find();

        verify(configDao, org.mockito.Mockito.never()).update(anyString(), anyString());
        assertEquals("cloudstack@pve!cs-lab", clusterDetails.get("username"));
    }

    @Test
    public void oneTokenServesTheWholeClusterAndIsKeptWithTheCluster() throws Exception {
        find();

        assertEquals("cloudstack@pve!cs-lab", clusterDetails.get("username"));
        assertEquals("secret-1", clusterDetails.get("password"));
        assertEquals("one token, made once", 1, tokens);
        assertEquals("the cluster is read with it", "cloudstack@pve!cs-lab=secret-1", createdClients.get(0));
        ScriptedSshLink login = links.get("10.0.35.25");
        assertEquals(1, login.commandsWith("acl modify /nodes/pve1 ").size());
        assertEquals(1, login.commandsWith("acl modify /nodes/pve2 ").size());
        assertTrue(login.commandsWith("acl modify /nodes/pve3 ").isEmpty());
    }

    @Test
    public void theTokenOfTheClusterIsReusedWhenAHostIsAddedLater() throws Exception {
        clusterDetails.put("username", "cloudstack@pve!cs-lab");
        clusterDetails.put("password", "kept-secret");

        find();

        assertEquals("no token was made", 0, tokens);
        assertEquals("cloudstack@pve!cs-lab=kept-secret", createdClients.get(0));
        assertEquals("kept-secret", clusterDetails.get("password"));
    }

    @Test
    public void aTokenThatProxmoxNoLongerAcceptsIsReplaced() throws Exception {
        clusterDetails.put("username", "cloudstack@pve!cs-lab");
        clusterDetails.put("password", "old-secret");
        when(client.getVersion()).thenThrow(new ProxmoxApiException("HTTP 401", 401, null)).thenReturn("8.4.21");

        find();

        assertEquals(1, tokens);
        assertEquals("secret-1", clusterDetails.get("password"));
    }

    @Test
    public void theLoginAndTheHostKeyOfEachNodeAreKeptWithTheHost() throws Exception {
        Map<? extends ServerResource, Map<String, String>> found = find();

        for (Map<String, String> details : found.values()) {
            assertEquals("root", details.get(ProxmoxResource.DETAIL_SSH_USER));
            assertNotNull(details.get(ProxmoxResource.DETAIL_SSH_PASSWORD));
            assertTrue(details.get(ProxmoxResource.DETAIL_SSH_FINGERPRINT).startsWith("SHA256:key"));
        }
        assertEquals("every online node was logged in to", 3, opened.size());
        assertEquals("MANAGEMENT-KEY", opened.get(0).getPrivateKey());
    }

    @Test
    public void noPasswordIsKeptWhenTheManagementKeyDidTheLogin() throws Exception {
        Map<? extends ServerResource, Map<String, String>> found = discoverer.find(1L, 1L, 2L, GIVEN, "root", null, null);

        for (Map<String, String> details : found.values()) {
            assertNull(details.get(ProxmoxResource.DETAIL_SSH_PASSWORD));
        }
    }

    @Test
    public void aNodeThatRefusesTheLoginIsSkippedAndKeepsNoToken() throws Exception {
        ScriptedSshLink refuses = nodeLink();
        refuses.acceptsKey = false;
        refuses.acceptsPassword = false;
        links.put("10.0.32.197", refuses);

        Map<? extends ServerResource, Map<String, String>> found = find();

        assertEquals(1, found.size());
        assertTrue(links.get("10.0.35.25").commandsWith("user token add cloudstack@pve cs-pve2").isEmpty());
    }

    @Test
    public void aRefusedLoginOnTheGivenAddressFailsTheAdd() {
        ScriptedSshLink refuses = nodeLink();
        refuses.acceptsKey = false;
        refuses.acceptsPassword = false;
        links.put("10.0.35.25", refuses);
        try {
            find();
            fail();
        } catch (DiscoveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("refused"));
        }
    }

    @Test
    public void deleteHostAnswersForAProxmoxHostOnly() throws Exception {
        com.cloud.host.HostVO other = mock(com.cloud.host.HostVO.class);
        when(other.getType()).thenReturn(com.cloud.host.Host.Type.Routing);
        when(other.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        assertNull("another hypervisor's host is left to its own adapter", discoverer.deleteHost(other, false, false));

        com.cloud.host.HostVO storage = mock(com.cloud.host.HostVO.class);
        when(storage.getType()).thenReturn(com.cloud.host.Host.Type.SecondaryStorage);
        when(storage.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.Proxmox);
        assertNull("a host that is not a hypervisor host is left to its own adapter", discoverer.deleteHost(storage, false, false));

        assertTrue(discoverer.deleteHost(hostToDelete(), false, false).getIsContinue());
    }

    private com.cloud.host.HostVO hostToDelete() {
        com.cloud.host.HostVO host = mock(com.cloud.host.HostVO.class);
        when(host.getId()).thenReturn(5L);
        when(host.getClusterId()).thenReturn(2L);
        when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.Proxmox);
        when(host.getType()).thenReturn(com.cloud.host.Host.Type.Routing);
        when(host.getName()).thenReturn("pve2");
        Map<String, String> details = new HashMap<>();
        details.put(ProxmoxResource.DETAIL_SSH_USER, "root");
        details.put(ProxmoxResource.DETAIL_SSH_PASSWORD, "secret");
        details.put(ProxmoxResource.DETAIL_NODE, "pve2");
        details.put(ProxmoxResource.DETAIL_NODE_IP, "10.0.32.197");
        details.put(ProxmoxResource.DETAIL_SSH_FINGERPRINT, "SHA256:key9");
        when(host.getDetails()).thenReturn(details);
        clusterDetails.put("username", "cloudstack@pve!cs-lab");
        clusterDetails.put("password", "secret-1");
        return host;
    }

    @Test
    public void deletingTheLastHostOfTheClusterRemovesTheClustersToken() {
        com.cloud.host.HostVO host = hostToDelete();
        when(hostDao.listIdsByClusterId(2L)).thenReturn(Arrays.asList(5L));
        ScriptedSshLink node = nodeLink();
        node.fingerprint = "SHA256:key9";
        links.put("10.0.32.197", node);

        discoverer.revokeToken(host);

        assertEquals(1, node.commandsWith("user token remove cloudstack@pve cs-lab").size());
        assertEquals("the user goes with the token when it has none left", 1, node.commandsWith("pveum user delete cloudstack@pve").size());
        assertEquals(1, node.commandsWith("pveum role delete CloudStackNet").size());
        assertEquals("SHA256:key9", opened.get(0).getPinnedFingerprint());
        assertTrue("the cluster no longer keeps the token", clusterDetails.isEmpty());
    }

    @Test
    public void deletingAHostWhenOthersRemainKeepsTheToken() {
        com.cloud.host.HostVO host = hostToDelete();
        when(hostDao.listIdsByClusterId(2L)).thenReturn(Arrays.asList(5L, 6L));

        discoverer.revokeToken(host);

        assertTrue(opened.isEmpty());
        assertEquals("cloudstack@pve!cs-lab", clusterDetails.get("username"));
    }

    @Test
    public void aTokenThatCannotBeRemovedDoesNotStopTheDelete() {
        com.cloud.host.HostVO host = hostToDelete();
        when(hostDao.listIdsByClusterId(2L)).thenReturn(Arrays.asList(5L));
        ScriptedSshLink node = nodeLink();
        node.fingerprint = "SHA256:key9";
        node.answers = command -> new ExecResult(1, "", "connection lost");
        links.put("10.0.32.197", node);

        discoverer.revokeToken(host);
    }

    @Test
    public void theLoginAnUpdateHostPasswordStoredIsUsedToRemoveTheToken() {
        com.cloud.host.HostVO host = hostToDelete();
        host.getDetails().put("username", "csadmin");
        host.getDetails().put("password", "new-password");
        when(hostDao.listIdsByClusterId(2L)).thenReturn(Arrays.asList(5L));
        ScriptedSshLink node = nodeLink();
        node.fingerprint = "SHA256:key9";
        links.put("10.0.32.197", node);

        discoverer.revokeToken(host);

        assertEquals("csadmin", opened.get(0).getUser());
        assertEquals("new-password", opened.get(0).getPassword());
        assertEquals(1, node.commandsWith("user token remove cloudstack@pve cs-lab").size());
    }

    @Test
    public void aFailureToRemoveTheUserDoesNotKeepTheClustersTokenDetails() {
        com.cloud.host.HostVO host = hostToDelete();
        when(hostDao.listIdsByClusterId(2L)).thenReturn(Arrays.asList(5L));
        ScriptedSshLink node = nodeLink();
        node.fingerprint = "SHA256:key9";
        java.util.function.Function<String, ExecResult> normal = node.answers;
        node.answers = command -> command.contains("user delete") ? new ExecResult(1, "", "permission denied") : normal.apply(command);
        links.put("10.0.32.197", node);

        discoverer.revokeToken(host);

        assertTrue(clusterDetails.isEmpty());
    }

    @Test
    public void aHostThatIsNotRunningStillCountsAsAnotherHostOfTheCluster() {
        com.cloud.host.HostVO host = hostToDelete();
        when(hostDao.listIdsByClusterId(2L)).thenReturn(Arrays.asList(5L, 7L));

        discoverer.revokeToken(host);

        assertTrue(opened.isEmpty());
        assertEquals("cloudstack@pve!cs-lab", clusterDetails.get("username"));
    }

    @Test
    public void theClustersTokenIsUsedAndAHostsOwnUsernameAndPasswordAreAnSshLoginNotAToken() {
        clusterDetails.put("username", "cloudstack@pve!cs-lab");
        clusterDetails.put("password", "kept-secret");
        Map<String, String> details = new HashMap<>();
        details.put("username", "csadmin");
        details.put("password", "ssh-password");

        java.util.HashMap<String, Object> params = discoverer.buildConfigParams(hostWithDetails(details));

        assertEquals("cloudstack@pve!cs-lab", params.get("username"));
        assertEquals("kept-secret", params.get("password"));
    }

    @Test
    public void theSshLoginIsTheOneUpdateHostPasswordStoredOtherwiseTheOneFromAddHost() {
        Map<String, String> added = new HashMap<>();
        added.put(ProxmoxResource.DETAIL_SSH_USER, "root");
        assertEquals("root", ProxmoxServerDiscoverer.sshLoginOf(added)[0]);
        assertNull("a login by key has no password", ProxmoxServerDiscoverer.sshLoginOf(added)[1]);

        added.put("username", "csadmin");
        added.put("password", "new-password");
        assertEquals("csadmin", ProxmoxServerDiscoverer.sshLoginOf(added)[0]);
        assertEquals("new-password", ProxmoxServerDiscoverer.sshLoginOf(added)[1]);
    }

    private com.cloud.host.HostVO hostWithDetails(Map<String, String> details) {
        com.cloud.host.HostVO host = mock(com.cloud.host.HostVO.class);
        when(host.getClusterId()).thenReturn(2L);
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getGuid()).thenReturn("Proxmox:abc");
        when(host.getDetails()).thenReturn(details);
        return host;
    }

    @Test
    public void aHostIsRebuiltWithTheTokenOfItsCluster() {
        clusterDetails.put("username", "cloudstack@pve!cs-lab");
        clusterDetails.put("password", "kept-secret");
        Map<String, String> details = new HashMap<>();
        details.put(ProxmoxResource.DETAIL_NODE, "pve1");

        java.util.HashMap<String, Object> params = discoverer.buildConfigParams(hostWithDetails(details));

        assertEquals("cloudstack@pve!cs-lab", params.get("username"));
        assertEquals("kept-secret", params.get("password"));
        assertEquals("pve1", params.get(ProxmoxResource.DETAIL_NODE));
    }

    @Test
    public void aHostThatKeepsItsOwnTokenFromBeforeIsRebuiltWithItWhenTheClusterHasNone() {
        Map<String, String> details = new HashMap<>();
        details.put("username", "root@pam!old");
        details.put("password", "own-secret");

        java.util.HashMap<String, Object> params = discoverer.buildConfigParams(hostWithDetails(details));

        assertEquals("root@pam!old", params.get("username"));
        assertEquals("own-secret", params.get("password"));
    }
}

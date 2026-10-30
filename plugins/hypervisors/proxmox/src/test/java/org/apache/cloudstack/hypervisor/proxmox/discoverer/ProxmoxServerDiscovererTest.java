package org.apache.cloudstack.hypervisor.proxmox.discoverer;

import static org.junit.Assert.assertEquals;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxResource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    private ProxmoxApiClient client;
    private ProxmoxServerDiscoverer discoverer;
    private ClusterVO cluster;
    private ClusterDao clusterDao;
    private HostDao hostDao;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        discoverer = new ProxmoxServerDiscoverer() {
            @Override
            protected ProxmoxApiClient createClient(String endpoint, String tokenId, String tokenSecret) {
                return ProxmoxServerDiscovererTest.this.client;
            }
        };
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

    private static ProxmoxApiClient.NetworkConfig networkWith(String name, String type, String cidr, boolean pending) {
        return new ProxmoxApiClient.NetworkConfig(Arrays.asList(new ProxmoxApiClient.NetworkInterface("vmbr0", "bridge", "10.0.35.25/20"),
                new ProxmoxApiClient.NetworkInterface(name, type, cidr)), pending);
    }

    private Map<? extends ServerResource, Map<String, String>> find() throws DiscoveryException {
        return discoverer.find(1L, 1L, 2L, GIVEN, "root@pam!cloudstack", "secret", null);
    }

    @Test
    public void everyOnlineNodeBecomesAHostAndKeepsTheCredentialsNeededToReconnect() throws Exception {
        Map<? extends ServerResource, Map<String, String>> found = find();

        assertEquals("offline nodes are skipped", 2, found.size());
        for (Map.Entry<? extends ServerResource, Map<String, String>> entry : found.entrySet()) {
            assertNotNull(entry.getKey());
            Map<String, String> details = entry.getValue();
            // these are rebuilt into the resource after a management server restart
            assertEquals("root@pam!cloudstack", details.get("username"));
            assertEquals("secret", details.get("password"));
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
}

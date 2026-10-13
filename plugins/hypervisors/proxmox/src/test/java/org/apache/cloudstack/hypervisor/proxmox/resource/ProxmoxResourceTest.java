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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CreateStoragePoolCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.GetStorageStatsAnswer;
import com.cloud.agent.api.GetStorageStatsCommand;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.UpdateHostPasswordCommand;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.vm.VirtualMachine;

public class ProxmoxResourceTest {
    private static final String NODE = "pve1";

    private ProxmoxApiClient client;
    private ProxmoxApiClient newClient;
    private ProxmoxApiClient rejectedClient;

    private ProxmoxApiClient clientFor(String secret) {
        if ("new-secret".equals(secret)) {
            return newClient;
        }
        if ("bad-secret".equals(secret)) {
            return rejectedClient;
        }
        return client;
    }
    private ProxmoxResource resource;

    @Before
    public void setUp() throws ConfigurationException {
        client = mock(ProxmoxApiClient.class);
        newClient = mock(ProxmoxApiClient.class);
        rejectedClient = mock(ProxmoxApiClient.class);
        resource = new ProxmoxResource() {
            @Override
            protected ProxmoxApiClient createClient(String id, String secret) {
                return clientFor(secret);
            }

            @Override
            protected void pause(long millis) {
                // do not wait in tests
            }
        };
        Map<String, Object> params = new HashMap<>();
        params.put("zone", "1");
        params.put("pod", "2");
        params.put("cluster", "3");
        params.put("guid", "Proxmox:abc");
        params.put("username", "root@pam!cloudstack");
        params.put("password", "secret");
        params.put(ProxmoxResource.DETAIL_NODE, NODE);
        params.put(ProxmoxResource.DETAIL_NODE_IP, "10.0.32.198");
        params.put(ProxmoxResource.DETAIL_ENDPOINT, "https://10.0.35.25:8006");
        resource.configure(NODE, params);
    }

    @Test(expected = ConfigurationException.class)
    public void configureFailsWithoutToken() throws ConfigurationException {
        new ProxmoxResource().configure(NODE, new HashMap<>());
    }

    @Test
    public void initializeReportsRealCapacity() {
        when(client.getNodeStatus(NODE)).thenReturn(new ProxmoxApiClient.NodeStatus(3, 1, 2400L, 8330162176L, 1L, 2L, 0.1, "pve-manager/8.4.21"));
        StartupCommand[] startup = resource.initialize();
        assertNotNull(startup);
        StartupRoutingCommand cmd = (StartupRoutingCommand) startup[0];
        assertEquals(Hypervisor.HypervisorType.Proxmox, cmd.getHypervisorType());
        assertEquals(3, cmd.getCpus());
        assertEquals(2400L, cmd.getSpeed());
        assertEquals(8330162176L, cmd.getMemory());
        assertEquals("10.0.32.198", cmd.getPrivateIpAddress());
        assertEquals("Proxmox:abc", cmd.getGuid());
    }

    @Test
    public void initializeReturnsNullWhenTheApiIsUnreachable() {
        when(client.getNodeStatus(anyString())).thenThrow(new ProxmoxApiException("boom"));
        assertNull(resource.initialize());
    }

    @Test
    public void pingReportsOnlyVmsOnThisNode() {
        when(client.listVms(NODE)).thenReturn(Arrays.asList(
                new ProxmoxApiClient.VmInfo(100, "i-2-10-VM", "running"),
                new ProxmoxApiClient.VmInfo(101, "i-2-11-VM", "stopped")));
        PingRoutingCommand ping = (PingRoutingCommand) resource.getCurrentStatus(7L);
        Map<String, HostVmStateReportEntry> report = ping.getHostVmStateReport();
        assertEquals(2, report.size());
        assertEquals(VirtualMachine.PowerState.PowerOn, report.get("i-2-10-VM").getState());
        assertEquals(VirtualMachine.PowerState.PowerOff, report.get("i-2-11-VM").getState());
        assertEquals(NODE, report.get("i-2-10-VM").getHost());
    }

    @Test
    public void pingReturnsNullWhenTheApiIsUnreachable() {
        when(client.listVms(anyString())).thenThrow(new ProxmoxApiException("down"));
        assertNull(resource.getCurrentStatus(7L));
    }

    @Test
    public void readyAndHealthCommandsAreAnswered() {
        assertTrue(resource.executeRequest(new ReadyCommand(1L)) instanceof ReadyAnswer);
        when(client.getNodeStatus(NODE)).thenReturn(new ProxmoxApiClient.NodeStatus(1, 1, 1L, 1L, 0L, 1L, 0, null));
        CheckHealthAnswer healthy = (CheckHealthAnswer) resource.executeRequest(new CheckHealthCommand());
        assertTrue(healthy.getResult());

        when(client.getNodeStatus(NODE)).thenThrow(new ProxmoxApiException("down"));
        CheckHealthAnswer unhealthy = (CheckHealthAnswer) resource.executeRequest(new CheckHealthCommand());
        assertFalse(unhealthy.getResult());
    }

    @Test
    public void hostStatsAreConvertedToPercentAndKilobytes() {
        when(client.getNodeStatus(NODE)).thenReturn(new ProxmoxApiClient.NodeStatus(3, 1, 2400L, 2048L, 1024L, 1024L, 0.25, null));
        GetHostStatsAnswer answer = (GetHostStatsAnswer) resource.executeRequest(new GetHostStatsCommand("g", NODE, 7L));
        assertEquals(25.0, answer.getCpuUtilization(), 0.001);
        assertEquals(2.0, answer.getTotalMemoryKBs(), 0.001);
        assertEquals(1.0, answer.getFreeMemoryKBs(), 0.001);
    }

    @Test
    public void unsupportedCommandsAreRejectedNotSilentlyAccepted() {
        Answer answer = resource.executeRequest(new GetVncPortCommand(1L, "i-2-10-VM"));
        assertFalse(answer.getResult());
    }

    @Test
    public void everyKnownEndpointIsKeptWithTheConfiguredOneFirst() throws ConfigurationException {
        Map<String, Object> params = new HashMap<>();
        params.put("username", "root@pam!cloudstack");
        params.put("password", "secret");
        params.put(ProxmoxResource.DETAIL_NODE, NODE);
        params.put(ProxmoxResource.DETAIL_ENDPOINT, "https://10.0.35.25:8006");
        params.put(ProxmoxResource.DETAIL_ENDPOINTS, "https://10.0.35.25:8006, https://10.0.32.74:8006,https://10.0.32.198:8006");

        ProxmoxResource other = new ProxmoxResource() {
            @Override
            protected ProxmoxApiClient createClient(String id, String secret) {
                return clientFor(secret);
            }

            @Override
            protected void pause(long millis) {
                // do not wait in tests
            }
        };
        other.configure(NODE, params);

        assertEquals(Arrays.asList("https://10.0.35.25:8006", "https://10.0.32.74:8006", "https://10.0.32.198:8006"), other.endpoints);
    }

    @Test
    public void pingTestSucceedsOnlyForAnAddressOfAnOnlineNode() {
        when(client.getClusterNodes(null)).thenReturn(Arrays.asList(
                new ProxmoxApiClient.NodeInfo("pve1", "10.0.35.25", false),
                new ProxmoxApiClient.NodeInfo("pve2", "10.0.32.74", true)));

        assertTrue(resource.executeRequest(new PingTestCommand("10.0.32.74")).getResult());
        assertFalse("a powered-off node must not look reachable", resource.executeRequest(new PingTestCommand("10.0.35.25")).getResult());
        assertFalse("an address outside the cluster is not vouched for", resource.executeRequest(new PingTestCommand("10.9.9.9")).getResult());
    }

    @Test
    public void pingTestFailsWhenTheClusterCannotBeQueried() {
        when(client.getClusterNodes(null)).thenThrow(new ProxmoxApiException("down"));
        assertFalse(resource.executeRequest(new PingTestCommand("10.0.32.74")).getResult());
    }

    @Test
    public void pingTestForRoutersAndVmsIsNotClaimedAsSuccessful() {
        assertFalse(resource.executeRequest(new PingTestCommand("169.254.0.1", "10.1.1.5")).getResult());
    }

    @Test
    public void aNewTokenThatProxmoxAcceptsIsUsedFromNowOn() {
        when(newClient.getNodeStatus(NODE)).thenReturn(new ProxmoxApiClient.NodeStatus(1, 1, 1L, 1L, 0L, 1L, 0, null));
        when(newClient.listVms(NODE)).thenReturn(Arrays.asList(new ProxmoxApiClient.VmInfo(100, "i-2-10-VM", "running")));
        when(client.listVms(NODE)).thenReturn(Arrays.asList(new ProxmoxApiClient.VmInfo(200, "i-2-20-VM", "running")));

        Answer answer = resource.executeRequest(new UpdateHostPasswordCommand("root@pam!cloudstack", "new-secret", "10.0.32.74"));

        assertTrue(answer.getResult());
        PingRoutingCommand ping = (PingRoutingCommand) resource.getCurrentStatus(7L);
        assertTrue("the ping must now go through the new token", ping.getHostVmStateReport().containsKey("i-2-10-VM"));
        assertFalse(ping.getHostVmStateReport().containsKey("i-2-20-VM"));
    }

    @Test
    public void aNewTokenThatProxmoxRejectsIsNotAdoptedAndTheOldOneKeepsWorking() {
        when(rejectedClient.getNodeStatus(NODE)).thenThrow(new ProxmoxApiException("HTTP 401", 401, null));
        when(client.listVms(NODE)).thenReturn(Arrays.asList(new ProxmoxApiClient.VmInfo(200, "i-2-20-VM", "running")));

        Answer answer = resource.executeRequest(new UpdateHostPasswordCommand("root@pam!cloudstack", "bad-secret", "10.0.32.74"));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("rejected the API token"));
        PingRoutingCommand ping = (PingRoutingCommand) resource.getCurrentStatus(7L);
        assertTrue(ping.getHostVmStateReport().containsKey("i-2-20-VM"));
        verify(rejectedClient).close();
    }

    @Test
    public void aRefusedTokenIsExplainedInWordsAnOperatorCanActOn() {
        assertTrue(ProxmoxResource.describeTokenFailure(new ProxmoxApiException("x", 401, null)).contains("id or the secret is wrong"));
        assertTrue(ProxmoxResource.describeTokenFailure(new ProxmoxApiException("x", 403, null)).contains("permissions"));
        assertTrue(ProxmoxResource.describeTokenFailure(new ProxmoxApiException("Unable to reach the API")).contains("Unable to reach the API"));
    }

    // ---- primary storage

    private static final String POOL_UUID = "047ccc61-dc29-3d58-9c73-dd1ab2c100cf";
    private static final String STORAGE_ID = "cs-" + POOL_UUID;

    private StoragePool pool(Storage.StoragePoolType type) {
        StoragePool pool = mock(StoragePool.class);
        when(pool.getId()).thenReturn(4L);
        when(pool.getUuid()).thenReturn(POOL_UUID);
        when(pool.getHostAddress()).thenReturn("10.0.32.4");
        when(pool.getPath()).thenReturn("/acs/primary/lab");
        when(pool.getPort()).thenReturn(2049);
        when(pool.getPoolType()).thenReturn(type);
        return pool;
    }

    private ModifyStoragePoolCommand connect(Map<String, String> details) {
        return new ModifyStoragePoolCommand(true, pool(Storage.StoragePoolType.NetworkFilesystem), details);
    }

    private ProxmoxApiClient.StorageConfig entry(String server, String export) {
        return new ProxmoxApiClient.StorageConfig(STORAGE_ID, "nfs", server, export, "/mnt/" + POOL_UUID, "images", null);
    }

    private ProxmoxApiClient.StorageStatus active() {
        return new ProxmoxApiClient.StorageStatus(true, true, 4000L, 1000L, 3000L);
    }

    @Test
    public void creatingAPoolDoesNothingOnProxmoxBecauseTheEntryIsCreatedWhenTheHostsConnect() {
        Answer answer = resource.executeRequest(new CreateStoragePoolCommand(true, pool(Storage.StoragePoolType.NetworkFilesystem)));

        assertTrue(answer.getResult());
        verify(client, never()).createNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void connectingAPoolCreatesTheClusterWideEntryAndReportsCapacity() {
        when(client.findStorage(STORAGE_ID)).thenReturn(null);
        when(client.getStorageStatus(NODE, STORAGE_ID)).thenReturn(active());
        Map<String, String> details = new HashMap<>();
        details.put(ApiConstants.NFS_MOUNT_OPTIONS, "vers=3");

        ModifyStoragePoolAnswer answer = (ModifyStoragePoolAnswer) resource.executeRequest(connect(details));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).createNfsStorage(STORAGE_ID, "10.0.32.4", "/acs/primary/lab", "/mnt/" + POOL_UUID, "images", "vers=3");
        assertEquals(4000L, answer.getPoolInfo().getCapacityBytes());
        assertEquals(3000L, answer.getPoolInfo().getAvailableBytes());
        assertEquals("/mnt/" + POOL_UUID, answer.getPoolInfo().getLocalPath());
        assertEquals(POOL_UUID, answer.getPoolInfo().getUuid());
    }

    @Test
    public void anExistingEntryForTheSameShareIsReusedEvenWithATrailingSlash() {
        when(client.findStorage(STORAGE_ID)).thenReturn(entry("10.0.32.4", "/acs/primary/lab/"));
        when(client.getStorageStatus(NODE, STORAGE_ID)).thenReturn(active());

        Answer answer = resource.executeRequest(connect(null));

        assertTrue(answer.getResult());
        verify(client, never()).createNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void anEntryThatPointsAtAnotherShareIsRefusedNotSilentlyUsed() {
        when(client.findStorage(STORAGE_ID)).thenReturn(entry("10.0.32.99", "/other/export"));

        Answer answer = resource.executeRequest(connect(null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("does not match"));
        verify(client, never()).createNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void whenAnotherHostCreatesTheEntryFirstThisOneJustUsesIt() {
        when(client.findStorage(STORAGE_ID)).thenReturn(null, entry("10.0.32.4", "/acs/primary/lab"));
        org.mockito.Mockito.doThrow(new ProxmoxApiException("storage ID already defined", 400, null)).when(client)
                .createNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        when(client.getStorageStatus(NODE, STORAGE_ID)).thenReturn(active());

        assertTrue(resource.executeRequest(connect(null)).getResult());
    }

    @Test
    public void aCreationFailureThatIsNotARaceIsReportedWithTheProxmoxReason() {
        when(client.findStorage(STORAGE_ID)).thenReturn(null);
        org.mockito.Mockito.doThrow(new ProxmoxApiException("Proxmox API POST /api2/json/storage failed: HTTP 403 Forbidden", 403, null)).when(client)
                .createNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        Answer answer = resource.executeRequest(connect(null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("403"));
    }

    @Test
    public void aNodeThatCannotMountTheShareReportsThatInsteadOfPretendingItWorks() {
        when(client.findStorage(STORAGE_ID)).thenReturn(entry("10.0.32.4", "/acs/primary/lab"));
        when(client.getStorageStatus(NODE, STORAGE_ID)).thenReturn(new ProxmoxApiClient.StorageStatus(false, true, 0L, 0L, 0L));

        Answer answer = resource.executeRequest(connect(null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("did not mount"));
    }

    @Test
    public void aStorageThatBecomesActiveAfterAWhileIsWaitedFor() {
        when(client.findStorage(STORAGE_ID)).thenReturn(entry("10.0.32.4", "/acs/primary/lab"));
        when(client.getStorageStatus(NODE, STORAGE_ID)).thenReturn(new ProxmoxApiClient.StorageStatus(false, true, 0L, 0L, 0L), active());

        assertTrue(resource.executeRequest(connect(null)).getResult());
    }

    @Test
    public void onlyNfsPoolsAreAccepted() {
        Answer answer = resource.executeRequest(new ModifyStoragePoolCommand(true, pool(Storage.StoragePoolType.Filesystem), (Map<String, String>) null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Only NFS"));
    }

    @Test
    public void takingOneHostOutOfThePoolLeavesTheClusterWideEntryAlone() {
        Answer answer = resource.executeRequest(new ModifyStoragePoolCommand(false, pool(Storage.StoragePoolType.NetworkFilesystem), (Map<String, String>) null));

        assertTrue(answer.getResult());
        verify(client, never()).deleteStorage(anyString());
    }

    @Test
    public void removingAPoolDeletesTheEntryAndCanBeRepeatedForTheNextHost() {
        when(client.findStorage(STORAGE_ID)).thenReturn(entry("10.0.32.4", "/acs/primary/lab"), (ProxmoxApiClient.StorageConfig) null);
        DeleteStoragePoolCommand delete = new DeleteStoragePoolCommand(pool(Storage.StoragePoolType.NetworkFilesystem));

        assertTrue(resource.executeRequest(delete).getResult());
        assertTrue("a second host asking again is not an error", resource.executeRequest(delete).getResult());
        verify(client, org.mockito.Mockito.times(1)).deleteStorage(STORAGE_ID);
    }

    @Test
    public void aFailedRemovalIsReportedWithTheProxmoxReason() {
        when(client.findStorage(STORAGE_ID)).thenReturn(entry("10.0.32.4", "/acs/primary/lab"));
        org.mockito.Mockito.doThrow(new ProxmoxApiException("HTTP 403 Forbidden", 403, null)).when(client).deleteStorage(STORAGE_ID);

        Answer answer = resource.executeRequest(new DeleteStoragePoolCommand(pool(Storage.StoragePoolType.NetworkFilesystem)));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("403"));
    }

    @Test
    public void storageStatisticsComeFromTheNodesView() {
        when(client.getStorageStatus(NODE, STORAGE_ID)).thenReturn(active());

        GetStorageStatsAnswer answer = (GetStorageStatsAnswer) resource.executeRequest(
                new GetStorageStatsCommand(POOL_UUID, Storage.StoragePoolType.NetworkFilesystem));

        assertTrue(answer.getResult());
        assertEquals(4000L, answer.getCapacityBytes());
        assertEquals(1000L, answer.getByteUsed());
    }

    @Test
    public void storageStatisticsFailCleanlyWhenTheStorageIsGone() {
        when(client.getStorageStatus(eq(NODE), anyString())).thenThrow(new ProxmoxApiException("storage does not exist", 500, null));

        Answer answer = resource.executeRequest(new GetStorageStatsCommand(POOL_UUID, Storage.StoragePoolType.NetworkFilesystem));

        assertFalse(answer.getResult());
    }
}

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.vm.VirtualMachine;

public class ProxmoxResourceTest {
    private static final String NODE = "pve1";

    private ProxmoxApiClient client;
    private ProxmoxResource resource;

    @Before
    public void setUp() throws ConfigurationException {
        client = mock(ProxmoxApiClient.class);
        resource = new ProxmoxResource() {
            @Override
            protected ProxmoxApiClient createClient() {
                return ProxmoxResourceTest.this.client;
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
        Answer answer = resource.executeRequest(mock(StartCommand.class));
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
            protected ProxmoxApiClient createClient() {
                return ProxmoxResourceTest.this.client;
            }
        };
        other.configure(NODE, params);

        assertEquals(Arrays.asList("https://10.0.35.25:8006", "https://10.0.32.74:8006", "https://10.0.32.198:8006"), other.endpoints);
    }
}

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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.NetworkUsageAnswer;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.utils.ExecutionResult;

public class ProxmoxNetworkUsageTest {
    private ProxmoxRouterDeployer deployer;

    @Before
    public void setUp() {
        deployer = mock(ProxmoxRouterDeployer.class);
    }

    private void script(String name, String args, ExecutionResult result) {
        when(deployer.executeInVR(eq("169.254.1.1"), eq(name), eq(args), org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(result);
    }

    @Test
    public void theArgumentsAreThoseTheKvmHostGives() {
        assertEquals("-g", ProxmoxNetworkUsage.usageArguments("get", null, null));
        assertEquals("-g -l 10.0.55.9", ProxmoxNetworkUsage.usageArguments("get", null, "10.0.55.9"));
        assertEquals("-c", ProxmoxNetworkUsage.usageArguments("create", null, null));
        assertEquals("-r", ProxmoxNetworkUsage.usageArguments("reset", null, null));
        assertEquals("-a eth3", ProxmoxNetworkUsage.usageArguments("addVif", "eth3", null));
        assertEquals("-d eth3", ProxmoxNetworkUsage.usageArguments("deleteVif", "eth3", null));
        assertNull(ProxmoxNetworkUsage.usageArguments("nonsense", null, null));
        assertEquals("-l 10.0.55.9 -g", ProxmoxNetworkUsage.vpcUsageArguments("10.0.55.9", "get", null));
        assertEquals("-l 10.0.55.9 -c -v 10.1.0.0/16", ProxmoxNetworkUsage.vpcUsageArguments("10.0.55.9", "create", "10.1.0.0/16"));
        assertEquals("-l 10.0.55.9 -n", ProxmoxNetworkUsage.vpcUsageArguments("10.0.55.9", "vpn", null));
        assertEquals("-l 10.0.55.9 -d", ProxmoxNetworkUsage.vpcUsageArguments("10.0.55.9", "remove", null));
    }

    @Test
    public void theCountersAreSummedPerDirection() {
        assertArrayEquals(new long[] {400, 600}, ProxmoxNetworkUsage.stats("100:200:300:400"));
        assertArrayEquals(new long[] {0, 0}, ProxmoxNetworkUsage.stats(null));
        assertArrayEquals(new long[] {7, 9}, ProxmoxNetworkUsage.stats("7:9"));
    }

    @Test
    public void theStatsOfARouterAreReadFromTheLastLineOfTheScript() {
        script("netusage.sh", "-g", new ExecutionResult(true, "warming up\n1500:2500\n"));
        NetworkUsageCommand cmd = new NetworkUsageCommand("169.254.1.1", "r-12-VM", false, null);

        Answer answer = ProxmoxNetworkUsage.execute(cmd, deployer);

        verify(deployer).register("169.254.1.1", "r-12-VM");
        assertTrue(answer instanceof NetworkUsageAnswer);
        assertEquals(Long.valueOf(1500), ((NetworkUsageAnswer) answer).getBytesSent());
        assertEquals(Long.valueOf(2500), ((NetworkUsageAnswer) answer).getBytesReceived());
    }

    @Test
    public void theCountersAreCreatedWithTheCreateOption() {
        script("netusage.sh", "-c", new ExecutionResult(true, "ok"));

        Answer answer = ProxmoxNetworkUsage.execute(new NetworkUsageCommand("169.254.1.1", "r-12-VM", "create", false), deployer);

        assertTrue(answer.getResult());
        assertEquals("ok", answer.getDetails());
    }

    @Test
    public void aScriptThatFailsGivesZerosNotAnError() {
        script("netusage.sh", "-g", new ExecutionResult(false, "no route"));

        NetworkUsageAnswer answer = (NetworkUsageAnswer) ProxmoxNetworkUsage.execute(new NetworkUsageCommand("169.254.1.1", "r-12-VM", false, null), deployer);

        assertEquals(Long.valueOf(0), answer.getBytesSent());
        assertEquals(Long.valueOf(0), answer.getBytesReceived());
    }

    @Test
    public void aVpcRouterUsesTheVpcScriptWithItsPublicGateway() {
        script("vpc_netusage.sh", "-l 10.0.48.9 -g", new ExecutionResult(true, "10:20"));

        NetworkUsageAnswer answer = (NetworkUsageAnswer) ProxmoxNetworkUsage.execute(new NetworkUsageCommand("169.254.1.1", "r-13-VM", true, "10.0.48.9"), deployer);

        assertEquals(Long.valueOf(10), answer.getBytesSent());
        assertEquals(Long.valueOf(20), answer.getBytesReceived());
        verify(deployer).executeInVR(eq("169.254.1.1"), eq("vpc_netusage.sh"), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }
}

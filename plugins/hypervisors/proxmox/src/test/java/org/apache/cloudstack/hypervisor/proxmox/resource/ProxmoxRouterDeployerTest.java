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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.utils.ExecutionResult;

public class ProxmoxRouterDeployerTest {
    private static final String IP = "169.254.3.7";
    private static final String NAME = "r-12-VM";

    private ProxmoxApiClient client;
    private ProxmoxRouterDeployer deployer;
    private final AtomicLong now = new AtomicLong();

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        deployer = new ProxmoxRouterDeployer(() -> client, millis -> now.addAndGet(millis), now::get);
        deployer.register(IP, NAME);
        when(client.findVmByName(NAME)).thenReturn(new ProxmoxApiClient.VmLocation(9300, NAME, "pve2", "running"));
    }

    private static ProxmoxApiClient.AgentExecStatus exited(int code, String out, String err) {
        return new ProxmoxApiClient.AgentExecStatus(true, code, out, err);
    }

    @Test
    public void aScriptRunsInTheVmOnTheNodeItIsOnWithItsArgumentsAndTheOutputIsTheResult() {
        when(client.agentExec(eq("pve2"), eq(9300L), anyList(), isNull())).thenReturn(77L);
        when(client.agentExecStatus("pve2", 9300L, 77L)).thenReturn(exited(0, "6.0.1\n", ""));

        ExecutionResult result = deployer.executeInVR(IP, "get_template_version.sh", "-v 1");

        assertTrue(result.isSuccess());
        assertEquals("6.0.1\n", result.getDetails());
        ArgumentCaptor<List<String>> command = captor();
        verify(client).agentExec(eq("pve2"), eq(9300L), command.capture(), isNull());
        assertEquals(Arrays.asList("/bin/sh", "-c", "/opt/cloud/bin/get_template_version.sh -v 1"), command.getValue());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> captor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static List<String> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    @Test
    public void aScriptWithoutArgumentsHasNoTrailingSpace() {
        when(client.agentExec(anyString(), anyLong(), anyList(), isNull())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong())).thenReturn(exited(0, "", ""));

        deployer.executeInVR(IP, "patched.sh", null);

        ArgumentCaptor<List<String>> command = captor();
        verify(client).agentExec(anyString(), anyLong(), command.capture(), isNull());
        assertEquals("/opt/cloud/bin/patched.sh", command.getValue().get(2));
    }

    @Test
    public void aScriptThatFailsReportsItsExitAndTheErrorWhenItPrintedNothing() {
        when(client.agentExec(anyString(), anyLong(), anyList(), isNull())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong())).thenReturn(exited(3, "", "no such file"));

        ExecutionResult result = deployer.executeInVR(IP, "patched.sh", "/x");

        assertFalse(result.isSuccess());
        assertEquals("no such file", result.getDetails());
    }

    @Test
    public void aScriptThatIsStillRunningIsPolledUntilItEnds() {
        when(client.agentExec(anyString(), anyLong(), anyList(), isNull())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong()))
                .thenReturn(new ProxmoxApiClient.AgentExecStatus(false, -1, "", ""))
                .thenReturn(new ProxmoxApiClient.AgentExecStatus(false, -1, "", ""))
                .thenReturn(exited(0, "done", ""));

        ExecutionResult result = deployer.executeInVR(IP, "x.sh", null);

        assertTrue(result.isSuccess());
        verify(client, times(3)).agentExecStatus(anyString(), anyLong(), anyLong());
        assertEquals(2 * ProxmoxRouterDeployer.POLL_MILLIS, now.get());
    }

    @Test
    public void aScriptThatRunsTooLongFailsWithTheTimeout() {
        when(client.agentExec(anyString(), anyLong(), anyList(), isNull())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong())).thenReturn(new ProxmoxApiClient.AgentExecStatus(false, -1, "", ""));

        ExecutionResult result = deployer.executeInVR(IP, "x.sh", null, Duration.standardSeconds(5));

        assertFalse(result.isSuccess());
        assertTrue(result.getDetails(), result.getDetails().contains("5 seconds"));
    }

    @Test
    public void anAddressNoVmIsKnownForFailsWithoutCallingProxmoxAboutAVm() {
        ExecutionResult result = deployer.executeInVR("169.254.9.9", "x.sh", null);

        assertFalse(result.isSuccess());
        assertTrue(result.getDetails(), result.getDetails().contains("169.254.9.9"));
        verify(client, never()).agentExec(anyString(), anyLong(), anyList(), isNull());
    }

    @Test
    public void aVmThatProxmoxHasNoLongerFailsTheCall() {
        when(client.findVmByName(NAME)).thenReturn(null);

        ExecutionResult result = deployer.executeInVR(IP, "x.sh", null);

        assertFalse(result.isSuccess());
        assertTrue(result.getDetails(), result.getDetails().contains(NAME));
    }

    @Test
    public void anApiErrorBecomesAFailedResultWithItsMessage() {
        when(client.agentExec(anyString(), anyLong(), anyList(), isNull())).thenThrow(new ProxmoxApiException("QEMU guest agent is not running", 500, null));

        ExecutionResult result = deployer.executeInVR(IP, "x.sh", null);

        assertFalse(result.isSuccess());
        assertEquals("QEMU guest agent is not running", result.getDetails());
    }

    @Test
    public void aSmallFileIsWrittenWithOneCallAtThePathAndName() {
        ExecutionResult result = deployer.createFileInVR(IP, "/var/cache/cloud/", "vm_metadata.json", "{\"a\":1}");

        assertTrue(result.isSuccess());
        verify(client).agentFileWrite("pve2", 9300L, "/var/cache/cloud/vm_metadata.json", "{\"a\":1}");
    }

    @Test
    public void aPathWithoutATrailingSlashGetsOne() {
        deployer.createFileInVR(IP, "/etc/cloudstack", "x.json", "{}");

        verify(client).agentFileWrite("pve2", 9300L, "/etc/cloudstack/x.json", "{}");
    }

    @Test
    public void aLargeFileIsAppendedInPiecesThatAreEachSmallEnoughForOneCall() {
        StringBuilder content = new StringBuilder();
        while (content.length() < 2 * ProxmoxRouterDeployer.CHUNK_BYTES + 100) {
            content.append("0123456789");
        }
        when(client.agentExec(anyString(), anyLong(), anyList(), org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong())).thenReturn(exited(0, "", ""));

        ExecutionResult result = deployer.createFileInVR(IP, "/var/cache/cloud/", "big.json", content.toString());

        assertTrue(result.getDetails(), result.isSuccess());
        verify(client, never()).agentFileWrite(anyString(), anyLong(), anyString(), anyString());
        ArgumentCaptor<List<String>> commands = captor();
        ArgumentCaptor<String> inputs = ArgumentCaptor.forClass(String.class);
        verify(client, times(5)).agentExec(eq("pve2"), eq(9300L), commands.capture(), inputs.capture());
        // an earlier partial file is removed, three pieces are appended to a temporary name, and it is moved into place when complete
        assertEquals("rm -f '/var/cache/cloud/big.json.part'", commands.getAllValues().get(0).get(2));
        StringBuilder joined = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            assertEquals("base64 -d >> '/var/cache/cloud/big.json.part'", commands.getAllValues().get(i).get(2));
            assertTrue(inputs.getAllValues().get(i).length() <= 64000);
            joined.append(new String(Base64.getDecoder().decode(inputs.getAllValues().get(i)), StandardCharsets.UTF_8));
        }
        assertEquals("mv -f '/var/cache/cloud/big.json.part' '/var/cache/cloud/big.json'", commands.getAllValues().get(4).get(2));
        assertEquals(content.toString(), joined.toString());
    }

    @Test
    public void binaryContentIsWrittenAsBase64PiecesAndMovedIntoPlaceLast() {
        byte[] data = new byte[ProxmoxRouterDeployer.CHUNK_BYTES + 10];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7);
        }
        when(client.agentExec(anyString(), anyLong(), anyList(), org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong())).thenReturn(exited(0, "", ""));

        ExecutionResult result = deployer.writeBytes(IP, "/var/cache/cloud/cloud-scripts.tgz", data);

        assertTrue(result.isSuccess());
        ArgumentCaptor<List<String>> commands = captor();
        ArgumentCaptor<String> inputs = ArgumentCaptor.forClass(String.class);
        verify(client, times(4)).agentExec(eq("pve2"), eq(9300L), commands.capture(), inputs.capture());
        byte[] joined = new byte[data.length];
        int length = 0;
        for (int i = 1; i <= 2; i++) {
            byte[] piece = Base64.getDecoder().decode(inputs.getAllValues().get(i));
            System.arraycopy(piece, 0, joined, length, piece.length);
            length += piece.length;
        }
        assertEquals(data.length, length);
        assertTrue(java.util.Arrays.equals(data, joined));
        assertTrue(commands.getAllValues().get(3).get(2).startsWith("mv -f "));
    }

    @Test
    public void aFailedPieceStopsTheCopyAndIsReported() {
        StringBuilder content = new StringBuilder();
        while (content.length() < 3 * ProxmoxRouterDeployer.CHUNK_BYTES) {
            content.append("0123456789");
        }
        when(client.agentExec(anyString(), anyLong(), anyList(), org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(client.agentExecStatus(anyString(), anyLong(), anyLong())).thenReturn(exited(0, "", "")).thenReturn(exited(1, "", "disk full"));

        ExecutionResult result = deployer.createFileInVR(IP, "/tmp/", "big", content.toString());

        assertFalse(result.isSuccess());
        assertEquals("disk full", result.getDetails());
        verify(client, times(2)).agentExec(anyString(), anyLong(), anyList(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void everyNetworkCommandTeachesTheDeployerWhichVmAnAddressBelongsTo() {
        NetworkElementCommand cmd = mock(NetworkElementCommand.class);
        when(cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP)).thenReturn("169.254.8.8");
        when(cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME)).thenReturn("r-99-VM");
        when(cmd.getRouterAccessIp()).thenReturn("169.254.8.8");
        when(client.findVmByName("r-99-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9400, "r-99-VM", "pve1", "running"));
        when(client.agentExec(eq("pve1"), eq(9400L), anyList(), isNull())).thenReturn(5L);
        when(client.agentExecStatus("pve1", 9400L, 5L)).thenReturn(exited(0, "ok", ""));

        assertTrue(deployer.prepareCommand(cmd).isSuccess());
        verify(cmd).setRouterAccessIp("169.254.8.8");
        assertTrue(deployer.executeInVR("169.254.8.8", "x.sh", null).isSuccess());
        assertTrue(deployer.cleanupCommand(cmd).isSuccess());
    }
}

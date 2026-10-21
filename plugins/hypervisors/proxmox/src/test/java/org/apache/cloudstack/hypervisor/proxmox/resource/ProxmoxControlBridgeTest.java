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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

public class ProxmoxControlBridgeTest {
    private static final String TOKEN = "cloudstack@pve!cs";
    private static final String CIDR = "169.254.0.1/16";

    private ProxmoxApiClient client;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
    }

    private static ProxmoxApiClient.NetworkConfig config(boolean pending, ProxmoxApiClient.NetworkInterface... extra) {
        ProxmoxApiClient.NetworkInterface[] all = new ProxmoxApiClient.NetworkInterface[extra.length + 1];
        all[0] = new ProxmoxApiClient.NetworkInterface("vmbr0", "bridge", "10.0.35.25/20");
        System.arraycopy(extra, 0, all, 1, extra.length);
        return new ProxmoxApiClient.NetworkConfig(Arrays.asList(all), pending);
    }

    private ProxmoxControlBridge.Outcome ensure() {
        return ProxmoxControlBridge.ensure(client, "pve1", "cloud0", TOKEN, 30);
    }

    private String refusal() {
        try {
            ensure();
        } catch (ProxmoxApiException e) {
            return e.getMessage();
        }
        fail("expected a refusal");
        return null;
    }

    @Test
    public void theAddressIsTheLinkLocalGatewayWithThePrefixOfTheLinkLocalNetwork() {
        assertEquals(CIDR, ProxmoxControlBridge.expectedCidr());
    }

    @Test
    public void aBridgeThatExistsIsLeftAloneWhoeverMadeIt() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false, new ProxmoxApiClient.NetworkInterface("cloud0", "bridge", CIDR)));

        assertEquals(ProxmoxControlBridge.Outcome.EXISTS, ensure());

        verify(client, never()).createBridge(anyString(), anyString(), anyString());
        verify(client, never()).applyNetworkConfig(anyString(), anyInt());
    }

    @Test
    public void anExistingBridgeWithPendingChangesElsewhereIsStillLeftAlone() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(true, new ProxmoxApiClient.NetworkInterface("cloud0", "bridge", CIDR)));

        assertEquals(ProxmoxControlBridge.Outcome.EXISTS, ensure());
    }

    @Test
    public void aBridgeWithAnotherAddressOrNoneIsAcceptedButReported() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false, new ProxmoxApiClient.NetworkInterface("cloud0", "bridge", null)));
        assertEquals(ProxmoxControlBridge.Outcome.EXISTS_WITH_OTHER_ADDRESS, ensure());

        when(client.getNetworkConfig("pve1")).thenReturn(config(false, new ProxmoxApiClient.NetworkInterface("cloud0", "bridge", "10.1.1.1/24")));
        assertEquals(ProxmoxControlBridge.Outcome.EXISTS_WITH_OTHER_ADDRESS, ensure());
        verify(client, never()).createBridge(anyString(), anyString(), anyString());
    }

    @Test
    public void anOpenVSwitchBridgeCounts() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false, new ProxmoxApiClient.NetworkInterface("cloud0", "OVSBridge", CIDR)));

        assertEquals(ProxmoxControlBridge.Outcome.EXISTS, ensure());
    }

    @Test
    public void somethingElseWithTheNameIsRefusedAndNeverChanged() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false, new ProxmoxApiClient.NetworkInterface("cloud0", "eth", null)));

        String message = refusal();

        assertTrue(message, message.contains("not a bridge") && message.contains("proxmox.control.bridge"));
        verify(client, never()).createBridge(anyString(), anyString(), anyString());
    }

    @Test
    public void aMissingBridgeIsCreatedThenApplied() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false));

        assertEquals(ProxmoxControlBridge.Outcome.CREATED, ensure());

        InOrder order = inOrder(client);
        order.verify(client).createBridge("pve1", "cloud0", CIDR);
        order.verify(client).applyNetworkConfig("pve1", 30);
    }

    @Test
    public void pendingNetworkChangesAreNeverAppliedByUs() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(true));

        String message = refusal();

        assertTrue(message, message.contains("pending network changes") && message.contains("pve1") && message.contains("by hand"));
        verify(client, never()).createBridge(anyString(), anyString(), anyString());
        verify(client, never()).applyNetworkConfig(anyString(), anyInt());
    }

    @Test
    public void aTokenWithoutSysModifyIsToldWhatToGrantAndHowToDoItByHand() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false));
        doThrow(new ProxmoxApiException("HTTP 403", 403, null)).when(client).createBridge(anyString(), anyString(), anyString());

        String message = refusal();

        assertTrue(message, message.contains("Sys.Modify"));
        assertTrue(message, message.contains("pveum role add CloudStackNet --privs Sys.Modify"));
        assertTrue(message, message.contains("pveum acl modify /nodes/pve1 --users cloudstack@pve --tokens 'cloudstack@pve!cs' --roles CloudStackNet --propagate 0"));
        assertTrue(message, message.contains("create the bridge 'cloud0' by hand on every node"));
        verify(client, never()).applyNetworkConfig(anyString(), anyInt());
    }

    @Test
    public void aRefusalToApplyDiscardsWhatWasSaved() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false));
        doThrow(new ProxmoxApiException("task failed")).when(client).applyNetworkConfig(anyString(), anyInt());

        String message = refusal();

        assertTrue(message, message.contains("did not apply") && message.contains("task failed") && message.contains("discarded"));
        verify(client).revertNetworkConfig("pve1");
    }

    @Test
    public void whenTheSavedChangeCannotBeDiscardedTheMessageSaysWhereToLook() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false));
        doThrow(new ProxmoxApiException("task failed")).when(client).applyNetworkConfig(anyString(), anyInt());
        doThrow(new ProxmoxApiException("HTTP 403", 403, null)).when(client).revertNetworkConfig(anyString());

        String message = refusal();

        assertTrue(message, message.contains("/etc/network/interfaces.new"));
    }

    @Test
    public void anotherFailureToCreateIsReportedWithProxmoxsReasonAndTheManualWay() {
        when(client.getNetworkConfig("pve1")).thenReturn(config(false));
        doThrow(new ProxmoxApiException("HTTP 500 interface already exists")).when(client).createBridge(anyString(), anyString(), anyString());

        String message = refusal();

        assertTrue(message, message.contains("did not create") && message.contains("interface already exists") && message.contains("by hand"));
        assertFalse(message, message.contains("Sys.Modify"));
    }

    @Test
    public void aTokenThatCannotReadTheNetworkIsToldSo() {
        doThrow(new ProxmoxApiException("HTTP 403", 403, null)).when(client).getNetworkConfig(anyString());

        String message = refusal();

        assertTrue(message, message.contains("not allowed to read the network configuration") && message.contains("Sys.Audit"));
    }

    @Test
    public void aBridgeNameLinuxCannotUseIsRefusedBeforeAnythingIsCalled() {
        for (String bad : new String[] {"", "1cloud", "cloud-0", "averyveryverylongname", "cloud 0"}) {
            try {
                ProxmoxControlBridge.ensure(client, "pve1", bad, TOKEN, 30);
                fail("expected '" + bad + "' to be refused");
            } catch (ProxmoxApiException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("proxmox.control.bridge"));
            }
        }
        verify(client, never()).getNetworkConfig(anyString());
    }
}

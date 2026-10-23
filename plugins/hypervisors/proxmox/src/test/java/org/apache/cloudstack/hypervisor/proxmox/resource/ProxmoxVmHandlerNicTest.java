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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;

public class ProxmoxVmHandlerNicTest {
    private static final String NODE = "pve1";

    private ProxmoxApiClient client;
    private ProxmoxVmHandler handler;
    private Map<String, String> config;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        handler = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "PVEAPIToken=u@pam!t=s", millis -> { }, () -> 600);
        when(client.findVmByName("r-58-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9300, "r-58-VM", NODE, "running"));
        config = new LinkedHashMap<>();
        config.put("net0", "virtio=02:00:00:aa:bb:01,bridge=vmbr1,tag=100,firewall=0");
        config.put("net1", "virtio=0e:00:a9:fe:a6:57,bridge=cloud0,firewall=0");
        when(client.getVmConfig(NODE, 9300L)).thenReturn(config);
    }

    private static NicTO nic(int deviceId, String mac, String bridge, String broadcastUri) {
        NicTO nic = mock(NicTO.class);
        when(nic.getDeviceId()).thenReturn(deviceId);
        when(nic.getMac()).thenReturn(mac);
        when(nic.getName()).thenReturn(bridge);
        when(nic.getBroadcastUri()).thenReturn(broadcastUri == null ? null : URI.create(broadcastUri));
        return nic;
    }

    private static PlugNicCommand plug(NicTO nic, VirtualMachine.Type type, Map<String, String> details) {
        PlugNicCommand cmd = mock(PlugNicCommand.class);
        when(cmd.getNic()).thenReturn(nic);
        when(cmd.getVmName()).thenReturn("r-58-VM");
        when(cmd.getVMType()).thenReturn(type);
        when(cmd.getDetails()).thenReturn(details);
        return cmd;
    }

    private static UnPlugNicCommand unplug(NicTO nic) {
        UnPlugNicCommand cmd = mock(UnPlugNicCommand.class);
        when(cmd.getNic()).thenReturn(nic);
        when(cmd.getVmName()).thenReturn("r-58-VM");
        return cmd;
    }

    @Test
    public void aTierIsPluggedIntoARouterAsTheNextInterfaceOnItsBridgeWithItsVlan() {
        Answer answer = handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", "vmbr1", "vlan://200"), VirtualMachine.Type.DomainRouter, null));

        assertTrue(answer instanceof PlugNicAnswer);
        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).updateVmConfig(NODE, 9300L, Collections.singletonMap("net2", "virtio=02:00:00:aa:bb:02,bridge=vmbr1,tag=200,firewall=0"));
    }

    @Test
    public void whenTheDeviceIdIsTakenTheNextFreeNumberIsUsed() {
        config.put("net2", "virtio=02:00:00:aa:bb:09,bridge=vmbr1,tag=51,firewall=0");

        handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", "vmbr1", "vlan://200"), VirtualMachine.Type.DomainRouter, null));

        verify(client).updateVmConfig(NODE, 9300L, Collections.singletonMap("net3", "virtio=02:00:00:aa:bb:02,bridge=vmbr1,tag=200,firewall=0"));
    }

    @Test
    public void anInterfaceTheVmAlreadyHasIsASuccessAndChangesNothing() {
        Answer answer = handler.plugNic(plug(nic(5, "02:00:00:AA:BB:01", "vmbr1", "vlan://100"), VirtualMachine.Type.DomainRouter, null));

        assertTrue(answer.getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aUserVmGetsTheAdapterModelOfItsDetailsAndAnUntaggedNetworkNoTag() {
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.NIC_ADAPTER, "e1000");

        handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", "vmbr1", "vlan://untagged"), VirtualMachine.Type.User, details));

        verify(client).updateVmConfig(NODE, 9300L, Collections.singletonMap("net2", "e1000=02:00:00:aa:bb:02,bridge=vmbr1,firewall=0"));
    }

    @Test
    public void aRouterAlwaysGetsVirtioWhateverTheDetailsSay() {
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.NIC_ADAPTER, "e1000");

        handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", "vmbr1", null), VirtualMachine.Type.DomainRouter, details));

        verify(client).updateVmConfig(NODE, 9300L, Collections.singletonMap("net2", "virtio=02:00:00:aa:bb:02,bridge=vmbr1,firewall=0"));
    }

    @Test
    public void aNetworkWithoutABridgeIsRefusedWithAMessageAndNothingIsChanged() {
        Answer answer = handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", null, "vlan://200"), VirtualMachine.Type.User, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("traffic label"));
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aVmProxmoxDoesNotKnowCannotGetAnInterface() {
        when(client.findVmByName("r-58-VM")).thenReturn(null);

        Answer answer = handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", "vmbr1", null), VirtualMachine.Type.User, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("r-58-VM"));
    }

    @Test
    public void aRefusalByProxmoxIsReportedWithTheHotplugHint() {
        org.mockito.Mockito.doThrow(new ProxmoxApiException("HTTP 500 hotplug", 500, null)).when(client).updateVmConfig(anyString(), anyLong(), anyMap());

        Answer answer = handler.plugNic(plug(nic(2, "02:00:00:aa:bb:02", "vmbr1", null), VirtualMachine.Type.User, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("hotplug"));
    }

    @Test
    public void theInterfaceWithTheMacOfTheCommandIsRemoved() {
        Answer answer = handler.unplugNic(unplug(nic(1, "0E:00:A9:FE:A6:57", null, null)));

        assertTrue(answer instanceof UnPlugNicAnswer);
        assertTrue(answer.getResult());
        verify(client).updateVmConfig(NODE, 9300L, Collections.singletonMap("delete", "net1"));
    }

    @Test
    public void removingAnInterfaceTheVmDoesNotHaveIsASuccess() {
        Answer answer = handler.unplugNic(unplug(nic(7, "aa:bb:cc:dd:ee:ff", null, null)));

        assertTrue(answer.getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void removingAnInterfaceOfAVmThatIsGoneIsASuccess() {
        when(client.findVmByName("r-58-VM")).thenReturn(null);

        assertTrue(handler.unplugNic(unplug(nic(1, "02:00:00:aa:bb:01", null, null))).getResult());
    }
}

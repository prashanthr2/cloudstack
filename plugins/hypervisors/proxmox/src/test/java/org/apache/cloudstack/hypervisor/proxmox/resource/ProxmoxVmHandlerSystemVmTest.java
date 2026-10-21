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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.network.Networks.TrafficType;
import com.cloud.storage.Volume;
import com.cloud.vm.VirtualMachine;

public class ProxmoxVmHandlerSystemVmTest {
    private static final String NODE = "pve1";

    private ProxmoxApiClient client;
    private ProxmoxSystemVms systemVms;
    private ProxmoxVmHandler handler;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        systemVms = mock(ProxmoxSystemVms.class);
        handler = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "PVEAPIToken=u@pam!t=s", millis -> { }, () -> 600);
        handler.setSystemVms(systemVms);
        handler.setControlBridge(() -> "cloud0");
        when(client.findVm(9300L)).thenReturn(new ProxmoxApiClient.VmLocation(9300, "s-1-VM", NODE, "stopped"));
        when(client.startVm(NODE, 9300L)).thenReturn("UPID:pve1:1:2:3:qmstart:9300:u:");
        when(client.getVmStatus(NODE, 9300L)).thenReturn("running");
        when(client.listBridges(NODE)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeBridge("cloud0", true, false)));
    }

    private StartCommand startCommand(VirtualMachine.Type type) {
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getPath()).thenReturn("9300/vm-9300-disk-0.qcow2");
        DiskTO root = mock(DiskTO.class);
        when(root.getType()).thenReturn(Volume.Type.ROOT);
        when(root.getData()).thenReturn(volume);
        NicTO guest = mock(NicTO.class);
        when(guest.getDeviceId()).thenReturn(0);
        when(guest.getMac()).thenReturn("02:00:00:aa:bb:01");
        when(guest.getName()).thenReturn("vmbr1");
        when(guest.getType()).thenReturn(TrafficType.Guest);
        NicTO control = mock(NicTO.class);
        when(control.getDeviceId()).thenReturn(1);
        when(control.getMac()).thenReturn("0e:00:a9:fe:03:07");
        when(control.getType()).thenReturn(TrafficType.Control);
        // a user VM has no control NIC: its second NIC is an ordinary one with a traffic label
        when(control.getName()).thenReturn(type == VirtualMachine.Type.User ? "vmbr2" : null);
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn("s-1-VM");
        when(vm.getType()).thenReturn(type);
        when(vm.getCpus()).thenReturn(1);
        when(vm.getMaxRam()).thenReturn(512L * 1024 * 1024);
        when(vm.getPlatformEmulator()).thenReturn("l26");
        when(vm.getDisks()).thenReturn(new DiskTO[] {root});
        when(vm.getNics()).thenReturn(new NicTO[] {guest, control});
        StartCommand cmd = mock(StartCommand.class);
        when(cmd.getVirtualMachine()).thenReturn(vm);
        return cmd;
    }

    @Test
    public void aSystemVmIsStartedWithTheAgentAndItsControlNicOnTheControlBridgeThenSetUp() {
        StartCommand cmd = startCommand(VirtualMachine.Type.SecondaryStorageVm);
        when(systemVms.afterStart(client, cmd.getVirtualMachine(), NODE, 9300L)).thenReturn(null);

        Answer answer = handler.start(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        InOrder order = inOrder(client, systemVms);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> options = ArgumentCaptor.forClass(Map.class);
        order.verify(client).updateVmConfig(eq(NODE), eq(9300L), options.capture());
        order.verify(client).startVm(NODE, 9300L);
        order.verify(systemVms).afterStart(client, cmd.getVirtualMachine(), NODE, 9300L);
        assertEquals("1", options.getValue().get("agent"));
        assertEquals("virtio=0e:00:a9:fe:03:07,bridge=cloud0,firewall=0", options.getValue().get("net1"));
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,firewall=0", options.getValue().get("net0"));
    }

    @Test
    public void aSystemVmThatIsNotUsableAfterItStartedFailsTheStartWithTheReason() {
        StartCommand cmd = startCommand(VirtualMachine.Type.ConsoleProxy);
        when(systemVms.afterStart(client, cmd.getVirtualMachine(), NODE, 9300L)).thenReturn("The guest agent of s-1-VM did not answer");

        Answer answer = handler.start(cmd);

        assertFalse(answer.getResult());
        assertEquals("The guest agent of s-1-VM did not answer", answer.getDetails());
    }

    @Test
    public void aNodeWithoutTheControlBridgeCannotStartASystemVmAndSaysWhatToDo() {
        when(client.listBridges(NODE)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeBridge("vmbr0", true, false)));

        Answer answer = handler.start(startCommand(VirtualMachine.Type.DomainRouter));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("pve1") && answer.getDetails().contains("'cloud0'")
                && answer.getDetails().contains("link-local control bridge"));
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
        verify(client, never()).startVm(anyString(), anyLong());
    }

    @Test
    public void theControlBridgeNameIsTheSetting() {
        handler.setControlBridge(() -> "ctl9");
        when(client.listBridges(NODE)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeBridge("ctl9", true, false)));

        Answer answer = handler.start(startCommand(VirtualMachine.Type.DomainRouter));

        assertTrue(answer.getDetails(), answer.getResult());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> options = ArgumentCaptor.forClass(Map.class);
        verify(client).updateVmConfig(eq(NODE), eq(9300L), options.capture());
        assertEquals("virtio=0e:00:a9:fe:03:07,bridge=ctl9,firewall=0", options.getValue().get("net1"));
    }

    @Test
    public void aUserVmNeedsNoControlBridgeAndIsNotSetUpAsASystemVm() {
        StartCommand cmd = startCommand(VirtualMachine.Type.User);

        Answer answer = handler.start(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client, never()).listBridges(anyString());
        verify(systemVms, never()).afterStart(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyString(), anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> options = ArgumentCaptor.forClass(Map.class);
        verify(client).updateVmConfig(eq(NODE), eq(9300L), options.capture());
        assertFalse(options.getValue().containsKey("agent"));
    }

    @Test
    public void theAgentFileIsPreparedOnTheNodeBeforeAnythingIsChangedOrStarted() {
        StartCommand cmd = startCommand(VirtualMachine.Type.SecondaryStorageVm);
        when(systemVms.beforeStart(client, cmd.getVirtualMachine(), NODE)).thenReturn(null);

        Answer answer = handler.start(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        InOrder order = inOrder(client, systemVms);
        order.verify(systemVms).beforeStart(client, cmd.getVirtualMachine(), NODE);
        order.verify(client).updateVmConfig(eq(NODE), eq(9300L), anyMap());
        order.verify(client).startVm(NODE, 9300L);
    }

    @Test
    public void aStartThatCannotGetItsAgentFileOntoTheNodeChangesNothing() {
        StartCommand cmd = startCommand(VirtualMachine.Type.ConsoleProxy);
        when(systemVms.beforeStart(client, cmd.getVirtualMachine(), NODE)).thenReturn("Unable to prepare the system VM patch file on Proxmox node pve1: HTTP 403");

        Answer answer = handler.start(cmd);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("HTTP 403"));
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
        verify(client, never()).startVm(anyString(), anyLong());
    }
}

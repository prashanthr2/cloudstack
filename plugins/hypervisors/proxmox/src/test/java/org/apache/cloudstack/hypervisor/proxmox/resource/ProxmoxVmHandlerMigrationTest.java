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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Volume;
import com.cloud.vm.VirtualMachine;

/** Phase 5: moving a VM between nodes, leaving the source of a failed move alone, waiting for the real state, and Proxmox HA. */
public class ProxmoxVmHandlerMigrationTest {
    private static final String NODE = "pve1";
    private static final String VM = "i-2-10-VM";

    private ProxmoxApiClient client;
    private ProxmoxVmHandler handler;
    private AtomicLong now;
    private boolean manageHa;
    private int pauses;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        now = new AtomicLong(1_000_000);
        handler = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "PVEAPIToken=u@pam!t=s", millis -> {
            pauses++;
            now.addAndGet(millis);
        }, () -> 600, () -> 3600, () -> manageHa);
        handler.setClock(now::get);
        when(client.getClusterNodes(null)).thenReturn(Arrays.asList(new ProxmoxApiClient.NodeInfo("pve1", "10.0.32.198", true),
                new ProxmoxApiClient.NodeInfo("pve2", "10.0.32.199", true)));
    }

    private static ProxmoxApiClient.VmLocation vm(String node, String status) {
        return new ProxmoxApiClient.VmLocation(9204, VM, node, status);
    }

    private VirtualMachineTO machine(String bridge, String broadcast, boolean ha) {
        NicTO nic = mock(NicTO.class);
        when(nic.getDeviceId()).thenReturn(0);
        when(nic.getName()).thenReturn(bridge);
        when(nic.getBroadcastUri()).thenReturn(broadcast == null ? null : URI.create(broadcast));
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getPath()).thenReturn("9204/vm-9204-disk-0.qcow2");
        DiskTO root = mock(DiskTO.class);
        when(root.getType()).thenReturn(Volume.Type.ROOT);
        when(root.getData()).thenReturn(volume);
        VirtualMachineTO machine = mock(VirtualMachineTO.class);
        when(machine.getName()).thenReturn(VM);
        when(machine.getCpus()).thenReturn(1);
        when(machine.getMaxRam()).thenReturn(1024L * 1024 * 1024);
        when(machine.getNics()).thenReturn(new NicTO[] {nic});
        when(machine.getDisks()).thenReturn(new DiskTO[] {root});
        when(machine.isEnableHA()).thenReturn(ha);
        return machine;
    }

    private PrepareForMigrationCommand prepare(VirtualMachineTO machine) {
        PrepareForMigrationCommand cmd = mock(PrepareForMigrationCommand.class);
        when(cmd.getVirtualMachine()).thenReturn(machine);
        return cmd;
    }

    private void thePreparedVmCanMove() {
        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));
        when(client.listBridges(NODE)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeBridge("vmbr1", true, true)));
        when(client.getMigrationProblems("pve2", 9204L, NODE)).thenReturn(Collections.emptyList());
    }

    private StopCommand stop() {
        StopCommand cmd = mock(StopCommand.class);
        when(cmd.getVmName()).thenReturn(VM);
        return cmd;
    }

    // ---- PrepareForMigrationCommand ----

    @Test
    public void aVmThatCanRunHereIsPreparedWithoutChangingAnything() {
        thePreparedVmCanMove();

        Answer answer = handler.prepareForMigration(prepare(machine("vmbr1", "vlan://100", false)));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client, never()).migrateVm(anyString(), anyLong(), anyString());
        verify(client, never()).migrateRunningVm(anyString(), anyLong(), anyString());
    }

    @Test
    public void aMissingBridgeOrANonVlanAwareOneRefusesThePreparation() {
        thePreparedVmCanMove();
        Answer answer = handler.prepareForMigration(prepare(machine("vmbr7", null, false)));
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("vmbr7"));

        when(client.listBridges(NODE)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeBridge("vmbr1", true, false)));
        answer = handler.prepareForMigration(prepare(machine("vmbr1", "vlan://100", false)));
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("VLAN-aware"));
        // without a tag the same bridge is fine
        assertTrue(handler.prepareForMigration(prepare(machine("vmbr1", null, false))).getResult());

        when(client.listBridges(NODE)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeBridge("vmbr1", false, true)));
        assertFalse(handler.prepareForMigration(prepare(machine("vmbr1", null, false))).getResult());
    }

    @Test
    public void proxmoxsReasonsAgainstAMoveAreCarriedInTheAnswer() {
        thePreparedVmCanMove();
        when(client.getMigrationProblems("pve2", 9204L, NODE)).thenReturn(Collections.singletonList("the disk local:9204/x is not on shared storage"));

        Answer answer = handler.prepareForMigration(prepare(machine("vmbr1", null, false)));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("not on shared storage"));
    }

    @Test
    public void aVmAlreadyHereOrUnknownOrAnOfflineNodeRefusesThePreparation() {
        thePreparedVmCanMove();
        when(client.findVmByName(VM)).thenReturn(vm(NODE, "running"));
        assertFalse(handler.prepareForMigration(prepare(machine("vmbr1", null, false))).getResult());

        when(client.findVmByName(VM)).thenReturn(null);
        assertFalse(handler.prepareForMigration(prepare(machine("vmbr1", null, false))).getResult());

        thePreparedVmCanMove();
        when(client.getClusterNodes(null)).thenReturn(Collections.singletonList(new ProxmoxApiClient.NodeInfo("pve1", "10.0.32.198", false)));
        assertFalse(handler.prepareForMigration(prepare(machine("vmbr1", null, false))).getResult());
    }

    @Test
    public void aProxmoxErrorWhilePreparingIsAFailedAnswer() {
        when(client.getClusterNodes(null)).thenThrow(new ProxmoxApiException("HTTP 500"));
        Answer answer = handler.prepareForMigration(prepare(machine("vmbr1", null, false)));
        assertFalse(answer.getResult());
        assertEquals("HTTP 500", answer.getDetails());
    }

    // ---- MigrateCommand ----

    private MigrateCommand migrateTo(String ip) {
        MigrateCommand cmd = mock(MigrateCommand.class);
        when(cmd.getVmName()).thenReturn(VM);
        when(cmd.getDestinationIp()).thenReturn(ip);
        return cmd;
    }

    @Test
    public void aRunningVmIsMovedWithItsMemoryAndTheAnswerWaitsUntilItIsThereAndRunning() {
        // the VM is on pve2 (the source); this is the source's handler
        ProxmoxVmHandler source = new ProxmoxVmHandler(() -> client, "pve2", "10.0.32.199", 8006, () -> "x", millis -> pauses++, () -> 600,
                () -> 3600, () -> false);
        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));
        when(client.migrateRunningVm("pve2", 9204L, "pve1")).thenReturn("UPID:pve2:1:2:3:qmigrate:9204:u:");
        // an HA move is only queued: the VM is still on pve2 for a while
        when(client.getVmStatus("pve1", 9204L)).thenReturn(null, null, "running");
        when(client.findVm(9204L)).thenReturn(vm("pve2", "running"));

        Answer answer = source.migrate(migrateTo("10.0.32.198"));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).waitForTask("UPID:pve2:1:2:3:qmigrate:9204:u:", 3600);
        assertEquals(2, pauses);
    }

    @Test
    public void aMoveToTheNodeTheVmIsOnIsASuccessWithoutCallingProxmox() {
        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));

        assertTrue(handler.migrate(migrateTo("10.0.32.199")).getResult());
        verify(client, never()).migrateRunningVm(anyString(), anyLong(), anyString());
    }

    @Test
    public void anAddressThatIsNoNodeOrAVmProxmoxDoesNotKnowFailsTheMove() {
        assertFalse(handler.migrate(migrateTo("10.9.9.9")).getResult());

        when(client.findVmByName(VM)).thenReturn(null);
        assertFalse(handler.migrate(migrateTo("10.0.32.198")).getResult());
    }

    @Test
    public void aFailedMoveCarriesProxmoxsReasonAndAMoveThatNeverArrivesTimesOut() {
        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));
        when(client.migrateRunningVm("pve2", 9204L, "pve1")).thenThrow(new ProxmoxApiException("HTTP 500 no such node"));
        Answer answer = handler.migrate(migrateTo("10.0.32.198"));
        assertFalse(answer.getResult());
        assertEquals("HTTP 500 no such node", answer.getDetails());

        org.mockito.Mockito.doReturn("UPID:pve2:1:2:3:hamigrate:9204:u:").when(client).migrateRunningVm("pve2", 9204L, "pve1");
        when(client.findVm(9204L)).thenReturn(vm("pve2", "running"));
        answer = handler.migrate(migrateTo("10.0.32.198"));
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("3600 seconds"));
    }

    // ---- CheckVirtualMachineCommand ----

    private CheckVirtualMachineCommand check() {
        CheckVirtualMachineCommand cmd = mock(CheckVirtualMachineCommand.class);
        when(cmd.getVmName()).thenReturn(VM);
        return cmd;
    }

    @Test
    public void aVmRunningOnThisNodeIsPowerOnAndAnyOtherIsPowerOff() {
        when(client.findVmByName(VM)).thenReturn(vm(NODE, "running"));
        assertEquals(VirtualMachine.PowerState.PowerOn, ((CheckVirtualMachineAnswer) handler.checkVirtualMachine(check())).getState());

        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));
        assertEquals(VirtualMachine.PowerState.PowerOff, ((CheckVirtualMachineAnswer) handler.checkVirtualMachine(check())).getState());

        when(client.findVmByName(VM)).thenReturn(vm(NODE, "stopped"));
        assertEquals(VirtualMachine.PowerState.PowerOff, ((CheckVirtualMachineAnswer) handler.checkVirtualMachine(check())).getState());

        when(client.findVmByName(VM)).thenReturn(null);
        assertEquals(VirtualMachine.PowerState.PowerOff, ((CheckVirtualMachineAnswer) handler.checkVirtualMachine(check())).getState());
    }

    // ---- the clean-up of a failed migration ----

    @Test
    public void aStopForTheTargetOfAFailedMoveLeavesTheVmRunningOnTheSourceAlone() {
        thePreparedVmCanMove();
        handler.prepareForMigration(prepare(machine("vmbr1", null, false)));

        Answer answer = handler.stop(stop());

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client, never()).stopVm(anyString(), anyLong());
        verify(client, never()).shutdownVm(anyString(), anyLong(), anyInt());
        // used up: a second stop is a real one
        when(client.shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS)).thenReturn("UPID:pve2:1:2:3:qmshutdown:9204:u:");
        assertTrue(handler.stop(stop()).getResult());
        verify(client).shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS);
    }

    @Test
    public void aRebootIsLeftAloneTheSameWay() {
        thePreparedVmCanMove();
        handler.prepareForMigration(prepare(machine("vmbr1", null, false)));
        RebootCommand cmd = mock(RebootCommand.class);
        when(cmd.getVmName()).thenReturn(VM);

        assertTrue(handler.reboot(cmd).getResult());
        verify(client, never()).rebootVm(anyString(), anyLong());
    }

    @Test
    public void aStopOfAVmOnThisNodeIsRealEvenWithAMark() {
        thePreparedVmCanMove();
        handler.prepareForMigration(prepare(machine("vmbr1", null, false)));
        when(client.findVmByName(VM)).thenReturn(vm(NODE, "running"));
        when(client.shutdownVm(NODE, 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS)).thenReturn("UPID:pve1:1:2:3:qmshutdown:9204:u:");

        assertTrue(handler.stop(stop()).getResult());
        verify(client).shutdownVm(NODE, 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS);
    }

    @Test
    public void aStopWithoutAMarkActsOnTheNodeTheVmIsReallyOn() {
        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));
        when(client.shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS)).thenReturn("UPID:pve2:1:2:3:qmshutdown:9204:u:");

        assertTrue(handler.stop(stop()).getResult());
        verify(client).shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS);
    }

    @Test
    public void aMarkEndsWithAVerifiedMigrationAndExpiresOtherwise() {
        thePreparedVmCanMove();
        handler.prepareForMigration(prepare(machine("vmbr1", null, false)));
        // the migration succeeded: the VM is here and running
        when(client.findVmByName(VM)).thenReturn(vm(NODE, "running"));
        handler.checkVirtualMachine(check());
        // later the VM runs elsewhere (moved by Proxmox HA): a stop is real
        when(client.findVmByName(VM)).thenReturn(vm("pve2", "running"));
        when(client.shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS)).thenReturn("UPID:pve2:1:2:3:qmshutdown:9204:u:");
        assertTrue(handler.stop(stop()).getResult());
        verify(client).shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS);

        thePreparedVmCanMove();
        handler.prepareForMigration(prepare(machine("vmbr1", null, false)));
        now.addAndGet((3600 + 301) * 1000L);
        assertTrue(handler.stop(stop()).getResult());
        verify(client, org.mockito.Mockito.times(2)).shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS);
    }

    @Test
    public void rollingBackThePreparationEndsTheMark() {
        thePreparedVmCanMove();
        handler.prepareForMigration(prepare(machine("vmbr1", null, false)));
        PrepareForMigrationCommand rollback = prepare(machine("vmbr1", null, false));
        when(rollback.isRollback()).thenReturn(true);
        assertTrue(handler.prepareForMigration(rollback).getResult());
        when(client.shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS)).thenReturn("UPID:pve2:1:2:3:qmshutdown:9204:u:");

        assertTrue(handler.stop(stop()).getResult());
        verify(client).shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS);
    }

    // ---- waiting for the real state (an HA request is only queued) ----

    @Test
    public void aStopWaitsUntilTheVmIsReallyStoppedAndFailsIfItNeverIs() {
        when(client.findVmByName(VM)).thenReturn(vm(NODE, "running"));
        when(client.shutdownVm(anyString(), anyLong(), anyInt())).thenReturn("UPID:pve1:1:2:3:hastop:9204:u:");
        when(client.getVmStatus(NODE, 9204L)).thenReturn("running", "running", "stopped");

        assertTrue(handler.stop(stop()).getResult());
        assertEquals(2, pauses);

        when(client.getVmStatus(NODE, 9204L)).thenReturn("running");
        Answer answer = handler.stop(stop());
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("did not stop within 600 seconds"));
    }

    // ---- Proxmox HA, with proxmox.ha.manage ----

    private StartCommand start(boolean ha) {
        VirtualMachineTO machine = machine("vmbr1", null, ha);
        StartCommand cmd = mock(StartCommand.class);
        when(cmd.getVirtualMachine()).thenReturn(machine);
        when(client.findVm(9204L)).thenReturn(vm(NODE, "stopped"));
        when(client.startVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstart:9204:u:");
        when(client.getVmStatus(NODE, 9204L)).thenReturn("running");
        return cmd;
    }

    @Test
    public void aVmWithTheHaFlagIsPutUnderProxmoxHaWhenTheSettingIsOn() {
        manageHa = true;

        assertTrue(handler.start(start(true)).getResult());

        verify(client).createHaResource(9204L, "CloudStack");
    }

    @Test
    public void nothingIsChangedInProxmoxHaWhenTheSettingIsOff() {
        assertTrue(handler.start(start(true)).getResult());

        verify(client, never()).findHaResource(anyLong());
        verify(client, never()).createHaResource(anyLong(), anyString());
        verify(client, never()).deleteHaResource(anyLong());
    }

    @Test
    public void anExistingResourceIsKeptAndNotCreatedAgain() {
        manageHa = true;
        when(client.findHaResource(9204L)).thenReturn(new ProxmoxApiClient.HaResource("vm:9204", "stopped", "CloudStack"));

        assertTrue(handler.start(start(true)).getResult());

        verify(client, never()).createHaResource(anyLong(), anyString());
    }

    @Test
    public void aVmWithoutTheFlagLosesTheResourceThePluginMadeButNotOneAnAdministratorMade() {
        manageHa = true;
        when(client.findHaResource(9204L)).thenReturn(new ProxmoxApiClient.HaResource("vm:9204", "started", "CloudStack"));
        assertTrue(handler.start(start(false)).getResult());
        verify(client).deleteHaResource(9204L);

        when(client.findHaResource(9204L)).thenReturn(new ProxmoxApiClient.HaResource("vm:9204", "started", "by the admin"));
        assertTrue(handler.start(start(false)).getResult());
        verify(client, org.mockito.Mockito.times(1)).deleteHaResource(9204L);
    }

    @Test
    public void aTokenWithoutThePrivilegesFailsTheStartOfAVmThatShouldBeProtected() {
        manageHa = true;
        when(client.findHaResource(9204L)).thenReturn(null);
        org.mockito.Mockito.doThrow(new ProxmoxApiException("HTTP 403", 403, null)).when(client).createHaResource(eq(9204L), anyString());

        Answer answer = handler.start(start(true));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Sys.Audit and Sys.Console"));
    }

    @Test
    public void aTokenWithoutThePrivilegesStillStartsAVmThatDoesNotNeedProtection() {
        manageHa = true;
        when(client.findHaResource(9204L)).thenThrow(new ProxmoxApiException("HTTP 403", 403, null));

        assertTrue(handler.start(start(false)).getResult());
    }

    @Test
    public void startingWaitsUntilTheVmIsRunningOnThisNode() {
        StartCommand cmd = start(false);
        when(client.getVmStatus(NODE, 9204L)).thenReturn("stopped", "stopped", "running");

        assertTrue(handler.start(cmd).getResult());
        assertEquals(2, pauses);
        verify(client, never()).updateVmConfig(anyString(), anyLong(), eq(Collections.emptyMap()));
        verify(client).updateVmConfig(eq(NODE), eq(9204L), anyMap());
    }
}

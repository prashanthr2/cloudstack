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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CreateVMSnapshotAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.RevertToVMSnapshotAnswer;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.snapshot.VMSnapshot;

public class ProxmoxVmSnapshotsTest {
    private static final String NAME = "i-2-10-VM";
    private static final String SNAPSHOT = "i-2-10-VM_VS_20261103121212";
    private static final String UPID = "UPID:pve2:1:2:3:qmsnapshot:103:u:";

    private ProxmoxApiClient client;
    private ProxmoxVmSnapshots snapshots;
    private final java.util.List<VolumeObjectTO> volumes = Collections.singletonList(mock(VolumeObjectTO.class));

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        snapshots = new ProxmoxVmSnapshots(() -> client, () -> 600);
        when(client.createSnapshot(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean())).thenReturn(UPID);
        when(client.rollbackSnapshot(anyString(), anyLong(), anyString())).thenReturn(UPID);
        when(client.deleteSnapshot(anyString(), anyLong(), anyString(), anyBoolean())).thenReturn(UPID);
    }

    private void vmIs(String status) {
        when(client.findVmByName(NAME)).thenReturn(new ProxmoxApiClient.VmLocation(103, NAME, "pve2", status));
    }

    private static VMSnapshotTO target(VMSnapshot.Type type) {
        return new VMSnapshotTO(5L, SNAPSHOT, type, null, "before the upgrade", false, null, true);
    }

    private CreateVMSnapshotCommand create(VMSnapshot.Type type) {
        CreateVMSnapshotCommand cmd = new CreateVMSnapshotCommand(NAME, "uuid", target(type), volumes, "Other");
        cmd.setWait(1800);
        return cmd;
    }

    // ---- names ----

    @Test
    public void aSnapshotNameKeepsWhatProxmoxAcceptsAndFixesTheRest() {
        assertEquals(SNAPSHOT, ProxmoxVmSnapshots.snapshotName(SNAPSHOT));
        assertEquals("my_snap_1", ProxmoxVmSnapshots.snapshotName("my snap.1"));
        assertEquals("s1abc", ProxmoxVmSnapshots.snapshotName("1abc"));
        assertEquals("s", ProxmoxVmSnapshots.snapshotName(null));
        String name = ProxmoxVmSnapshots.snapshotName("a" + "b".repeat(60) + "END");
        assertEquals(40, name.length());
        assertTrue(name, name.startsWith("s") && name.endsWith("bEND"));
    }

    // ---- create ----

    @Test
    public void aDiskOnlySnapshotOfARunningVmIsTakenWithoutMemoryAndTheCommandsVolumesAreAnswered() {
        vmIs("running");

        Answer answer = snapshots.create(create(VMSnapshot.Type.Disk));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).createSnapshot("pve2", 103L, SNAPSHOT, "before the upgrade", false);
        verify(client).waitForTask(UPID, 1800);
        assertSame(volumes, ((CreateVMSnapshotAnswer) answer).getVolumeTOs());
        assertEquals(SNAPSHOT, ((CreateVMSnapshotAnswer) answer).getVmSnapshotTo().getSnapshotName());
    }

    @Test
    public void aSnapshotWithMemoryIsTakenOfARunningVm() {
        vmIs("running");

        assertTrue(snapshots.create(create(VMSnapshot.Type.DiskAndMemory)).getResult());

        verify(client).createSnapshot("pve2", 103L, SNAPSHOT, "before the upgrade", true);
    }

    @Test
    public void aDiskOnlySnapshotOfAStoppedVmIsTaken() {
        vmIs("stopped");

        assertTrue(snapshots.create(create(VMSnapshot.Type.Disk)).getResult());

        verify(client).createSnapshot("pve2", 103L, SNAPSHOT, "before the upgrade", false);
    }

    @Test
    public void aSnapshotWithMemoryOfAStoppedVmIsRefusedBeforeProxmoxIsAsked() {
        vmIs("stopped");

        Answer answer = snapshots.create(create(VMSnapshot.Type.DiskAndMemory));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("needs a running instance"));
        verify(client, never()).createSnapshot(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean());
    }

    @Test
    public void aVmProxmoxDoesNotHaveFailsTheSnapshot() {
        Answer answer = snapshots.create(create(VMSnapshot.Type.Disk));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("no VM " + NAME));
    }

    @Test
    public void aSnapshotNameThatTheVmHasAlreadyIsNotTakenAgain() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Collections.singletonList(SNAPSHOT));

        Answer answer = snapshots.create(create(VMSnapshot.Type.Disk));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("has a snapshot"));
        verify(client, never()).createSnapshot(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean());
    }

    @Test
    public void aFailedTaskIsReportedWithProxmoxsText() {
        vmIs("running");
        doThrow(new ProxmoxApiException("snapshot feature is not available")).when(client).waitForTask(UPID, 1800);

        Answer answer = snapshots.create(create(VMSnapshot.Type.Disk));

        assertFalse(answer.getResult());
        assertEquals("snapshot feature is not available", answer.getDetails());
    }

    @Test
    public void theTaskTimeoutCountsWhenTheCommandsWaitIsShorter() {
        vmIs("running");
        CreateVMSnapshotCommand cmd = create(VMSnapshot.Type.Disk);
        cmd.setWait(30);

        snapshots.create(cmd);

        verify(client).waitForTask(UPID, 600);
    }

    // ---- delete ----

    private DeleteVMSnapshotCommand delete() {
        return new DeleteVMSnapshotCommand(NAME, target(VMSnapshot.Type.Disk), volumes, "Other");
    }

    @Test
    public void deletingASnapshotRemovesItFromProxmoxWithoutForce() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Arrays.asList("other", SNAPSHOT));

        Answer answer = snapshots.delete(delete());

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).deleteSnapshot("pve2", 103L, SNAPSHOT, false);
        assertSame(volumes, ((DeleteVMSnapshotAnswer) answer).getVolumeTOs());
    }

    @Test
    public void aSnapshotProxmoxDoesNotHaveIsAlreadyDeleted() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Collections.singletonList("other"));

        assertTrue(snapshots.delete(delete()).getResult());

        verify(client, never()).deleteSnapshot(anyString(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    public void aVmProxmoxDoesNotHaveHasNoSnapshotsToDelete() {
        assertTrue(snapshots.delete(delete()).getResult());

        verify(client, never()).deleteSnapshot(anyString(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    public void aDeleteThatProxmoxRefusesFailsAndKeepsCloudStacksRecord() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Collections.singletonList(SNAPSHOT));
        doThrow(new ProxmoxApiException("can't lock file")).when(client).waitForTask(eq(UPID), anyInt());

        Answer answer = snapshots.delete(delete());

        assertFalse(answer.getResult());
        assertEquals("can't lock file", answer.getDetails());
    }

    // ---- revert ----

    private RevertToVMSnapshotCommand revert(VMSnapshot.Type type) {
        return new RevertToVMSnapshotCommand(NAME, "uuid", target(type), volumes, "Other");
    }

    @Test
    public void aRollbackToASnapshotWithMemoryLeavesTheVmRunningAndTellsCloudStack() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Collections.singletonList(SNAPSHOT));
        when(client.getVmStatus("pve2", 103L)).thenReturn("running");

        Answer answer = snapshots.revert(revert(VMSnapshot.Type.DiskAndMemory));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).rollbackSnapshot("pve2", 103L, SNAPSHOT);
        assertEquals(VirtualMachine.PowerState.PowerOn, ((RevertToVMSnapshotAnswer) answer).getVmState());
        assertSame(volumes, ((RevertToVMSnapshotAnswer) answer).getVolumeTOs());
    }

    @Test
    public void aRollbackToADiskOnlySnapshotLeavesTheVmStopped() {
        vmIs("stopped");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Collections.singletonList(SNAPSHOT));
        when(client.getVmStatus("pve2", 103L)).thenReturn("stopped");

        Answer answer = snapshots.revert(revert(VMSnapshot.Type.Disk));

        assertTrue(answer.getResult());
        assertEquals(VirtualMachine.PowerState.PowerOff, ((RevertToVMSnapshotAnswer) answer).getVmState());
    }

    @Test
    public void aRollbackToASnapshotProxmoxDoesNotHaveFailsAndTouchesNothing() {
        vmIs("stopped");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Collections.singletonList("other"));

        Answer answer = snapshots.revert(revert(VMSnapshot.Type.Disk));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("has no snapshot " + SNAPSHOT));
        verify(client, never()).rollbackSnapshot(anyString(), anyLong(), anyString());
    }

    @Test
    public void aRollbackOfAVmProxmoxDoesNotHaveFails() {
        assertFalse(snapshots.revert(revert(VMSnapshot.Type.Disk)).getResult());
    }

    // ---- before the VM is destroyed ----

    @Test
    public void everySnapshotIsDeletedWithForceChildrenBeforeParents() {
        ProxmoxApiClient.VmLocation vm = new ProxmoxApiClient.VmLocation(103, NAME, "pve2", "stopped");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Arrays.asList("s1", "s2", "s3"));

        snapshots.deleteAll(client, vm);

        InOrder order = inOrder(client);
        order.verify(client).deleteSnapshot("pve2", 103L, "s3", true);
        order.verify(client).deleteSnapshot("pve2", 103L, "s2", true);
        order.verify(client).deleteSnapshot("pve2", 103L, "s1", true);
    }

    @Test
    public void aSnapshotThatCannotBeDeletedDoesNotStopTheOthersOrTheDestroy() {
        ProxmoxApiClient.VmLocation vm = new ProxmoxApiClient.VmLocation(103, NAME, "pve2", "stopped");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Arrays.asList("s1", "s2"));
        when(client.deleteSnapshot("pve2", 103L, "s2", true)).thenThrow(new ProxmoxApiException("storage offline"));

        snapshots.deleteAll(client, vm);

        verify(client).deleteSnapshot("pve2", 103L, "s1", true);
    }

    @Test
    public void snapshotsThatCannotBeListedDoNotStopTheDestroy() {
        ProxmoxApiClient.VmLocation vm = new ProxmoxApiClient.VmLocation(103, NAME, "pve2", "stopped");
        when(client.listSnapshotNames("pve2", 103L)).thenThrow(new ProxmoxApiException("HTTP 500"));

        snapshots.deleteAll(client, vm);

        verify(client, never()).deleteSnapshot(anyString(), anyLong(), anyString(), anyBoolean());
    }
}

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachAnswer;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.storage.ResizeVolumeAnswer;
import com.cloud.agent.api.storage.ResizeVolumeCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.offering.DiskOffering.DiskCacheMode;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;

/** Phase 7: data volumes, disk images owned by the holder vmid of the account (design-phase7-data-volumes.md). */
public class ProxmoxVmHandlerDataVolumeTest {
    private static final String NODE = "pve1";
    private static final String VM = "i-2-10-VM";
    private static final String POOL_UUID = "7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final String POOL = "cs-" + POOL_UUID;
    private static final String UUID = "0f2a9c0e-1d3b-4c55-9a0b-0d1e2f3a4b5c";
    private static final String PATH = "900000002/vm-900000002-" + UUID + ".qcow2";
    private static final String VOLUME_ID = POOL + ":" + PATH;
    private static final long GIB = 1024L * 1024 * 1024;

    private ProxmoxApiClient client;
    private ProxmoxVmHandler handler;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        handler = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "x", millis -> { }, () -> 600);
    }

    private static VolumeObjectTO volume(Volume.Type type, Long size) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL_UUID);
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getVolumeType()).thenReturn(type);
        when(volume.getSize()).thenReturn(size);
        when(volume.getUuid()).thenReturn(UUID);
        when(volume.getAccountId()).thenReturn(2L);
        when(volume.getDataStore()).thenReturn(pool);
        when(volume.getPath()).thenReturn(PATH);
        return volume;
    }

    private static DiskTO dataDisk(VolumeObjectTO volume, long deviceId) {
        return new DiskTO(volume, deviceId, null, Volume.Type.DATADISK);
    }

    private void theVmIs(String state, Map<String, String> config) {
        when(client.findVmByName(VM)).thenReturn(new ProxmoxApiClient.VmLocation(9204, VM, NODE, state));
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config);
        when(client.getRunningVmConfig(NODE, 9204L)).thenReturn(config);
    }

    private static Map<String, String> config(String... keyValues) {
        Map<String, String> config = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            config.put(keyValues[i], keyValues[i + 1]);
        }
        return config;
    }

    // ---- create ----

    @Test
    public void aDataVolumeIsAnImageOfTheAccountsHolderNamedAfterTheVolumeAndRoundedUpToWholeGib() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(null);

        Answer answer = handler.createObject(new CreateObjectCommand(volume(Volume.Type.DATADISK, 5 * GIB + 1)));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).allocateImage(NODE, POOL, 900000002L, "vm-900000002-" + UUID + ".qcow2", 6);
        VolumeObjectTO created = (VolumeObjectTO) ((CreateObjectAnswer) answer).getData();
        assertEquals(PATH, created.getPath());
        assertEquals(Long.valueOf(6 * GIB), created.getSize());
        assertEquals(Storage.ImageFormat.QCOW2, created.getFormat());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    @Test
    public void creatingTheSameVolumeAgainAnswersWithTheExistingImage() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(new ProxmoxApiClient.ImageInfo(10 * GIB, 1024, "qcow2", "/mnt/x"));

        Answer answer = handler.createObject(new CreateObjectCommand(volume(Volume.Type.DATADISK, 5 * GIB)));

        assertTrue(answer.getResult());
        assertEquals(Long.valueOf(10 * GIB), ((VolumeObjectTO) ((CreateObjectAnswer) answer).getData()).getSize());
        verify(client, never()).allocateImage(anyString(), anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    public void anAccountOutsideTheHoldersRangeFailsTheCreate() {
        VolumeObjectTO volume = volume(Volume.Type.DATADISK, GIB);
        when(volume.getAccountId()).thenReturn(0L);

        Answer answer = handler.createObject(new CreateObjectCommand(volume));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("account id 0"));
    }

    @Test
    public void aVmIdInTheRangeReservedForTheHoldersIsNeverUsedForAVm() {
        when(client.nextVmId()).thenReturn(900000005L);
        VolumeObjectTO root = volume(Volume.Type.ROOT, GIB);

        Answer answer = handler.createObject(new CreateObjectCommand(root));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("900000005"));
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    // ---- attach ----

    @Test
    public void aDataVolumeIsPutOnTheDriveOfItsDeviceIdAndNeedsTheDriveInTheRunningConfiguration() {
        theVmIs("running", config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,size=8G", "scsi1", VOLUME_ID));
        Map<String, String> before = config("scsi0", "x");
        when(client.getVmConfig(NODE, 9204L)).thenReturn(before);

        DiskTO disk = dataDisk(volume(Volume.Type.DATADISK, GIB), 1);
        Answer answer = handler.attach(new AttachCommand(disk, VM, Collections.emptyMap()));

        // core casts the answer to an AttachAnswer, for a failure too, and reads the disk from it
        assertTrue(answer instanceof AttachAnswer);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(disk, ((AttachAnswer) answer).getDisk());
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("scsi1", VOLUME_ID));
        verify(client, never()).updateVmConfig(eq(NODE), eq(9204L), eq(Collections.singletonMap("revert", "scsi1")));
    }

    @Test
    public void theOptionsOfTheDiskOfferingAreAppliedToTheDrive() {
        VolumeObjectTO volume = volume(Volume.Type.DATADISK, GIB);
        when(volume.getCacheMode()).thenReturn(DiskCacheMode.WRITEBACK);
        when(volume.getIopsReadRate()).thenReturn(500L);
        when(volume.getBytesWriteRate()).thenReturn(10L * 1024 * 1024);
        theVmIs("stopped", config("scsi0", "x"));

        assertTrue(handler.attach(new AttachCommand(dataDisk(volume, 2), VM, Collections.emptyMap())).getResult());

        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("scsi2", VOLUME_ID + ",cache=writeback,iops_rd=500,mbps_wr=10"));
    }

    @Test
    public void aStoppedVmNeedsNoHotPlug() {
        theVmIs("stopped", config("scsi0", "x"));

        assertTrue(handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM, Collections.emptyMap())).getResult());

        verify(client, never()).getRunningVmConfig(anyString(), anyLong());
    }

    @Test
    public void aDriveThatCannotBeHotPluggedIsTakenBackAndTheAnswerSaysToRestart() {
        theVmIs("running", config("sata0", "x"));
        when(client.getRunningVmConfig(NODE, 9204L)).thenReturn(config("sata0", "x"));
        Map<String, String> details = Collections.singletonMap("rootDiskController", "sata");

        Answer answer = handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM, details));

        assertTrue(answer instanceof AttachAnswer);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("attach the volume while it is stopped"));
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("sata1", VOLUME_ID));
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("revert", "sata1"));
    }

    @Test
    public void theDataDiskControllerDetailPicksTheController() {
        theVmIs("stopped", config("scsi0", "x"));

        assertTrue(handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 3), VM,
                Collections.singletonMap("dataDiskController", "virtio"))).getResult());

        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("virtio3", VOLUME_ID));
    }

    @Test
    public void aDriveInUseByAnotherDiskFailsAndTheSameVolumeIsAlreadyDone() {
        theVmIs("stopped", config("scsi1", POOL + ":900000002/vm-900000002-other.qcow2"));
        Answer inUse = handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM, Collections.emptyMap()));
        assertTrue(inUse instanceof AttachAnswer);
        assertFalse(inUse.getResult());
        assertTrue(inUse.getDetails(), inUse.getDetails().contains("already holds another disk"));

        theVmIs("stopped", config("scsi1", VOLUME_ID + ",size=1G"));
        assertTrue(handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM, Collections.emptyMap())).getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aDeviceIdTheControllerDoesNotHaveFailsAndAnUnknownVmFails() {
        theVmIs("stopped", config("scsi0", "x"));
        Answer tooHigh = handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 6), VM, Collections.singletonMap("dataDiskController", "sata")));
        assertFalse(tooHigh.getResult());
        assertTrue(tooHigh.getDetails(), tooHigh.getDetails().contains("sata"));

        when(client.findVmByName(VM)).thenReturn(null);
        Answer noVm = handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM, Collections.emptyMap()));
        assertTrue(noVm instanceof AttachAnswer);
        assertFalse(noVm.getResult());
    }

    // ---- detach ----

    @Test
    public void aDetachRemovesTheDriveThatHoldsTheVolume() {
        theVmIs("running", config("scsi0", "x", "scsi2", VOLUME_ID + ",size=1G"));
        when(client.getRunningVmConfig(NODE, 9204L)).thenReturn(config("scsi0", "x"));

        Answer answer = handler.detach(new DettachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 2), VM));

        assertTrue(answer instanceof DettachAnswer);
        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("delete", "scsi2"));
    }

    @Test
    public void detachingAVolumeThatIsNotOnTheVmIsASuccessWithoutAChange() {
        theVmIs("running", config("scsi0", "x"));

        Answer answer = handler.detach(new DettachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 2), VM));

        assertTrue(answer instanceof DettachAnswer);
        assertTrue(answer.getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aDriveThatCannotBeUnpluggedWhileRunningIsKeptAndTheAnswerSaysToStop() {
        theVmIs("running", config("sata1", VOLUME_ID));

        Answer answer = handler.detach(new DettachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM));

        assertTrue(answer instanceof DettachAnswer);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("stop the instance"));
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("revert", "sata1"));
    }

    // ---- start ----

    private StartCommand start(Map<String, String> details, VolumeObjectTO root, DiskTO... data) {
        DiskTO rootDisk = mock(DiskTO.class);
        when(rootDisk.getType()).thenReturn(Volume.Type.ROOT);
        when(rootDisk.getData()).thenReturn(root);
        DiskTO[] disks = new DiskTO[data.length + 1];
        disks[0] = rootDisk;
        System.arraycopy(data, 0, disks, 1, data.length);
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn(VM);
        when(vm.getCpus()).thenReturn(1);
        when(vm.getMaxRam()).thenReturn(GIB);
        when(vm.getDisks()).thenReturn(disks);
        when(vm.getDetails()).thenReturn(details);
        StartCommand cmd = mock(StartCommand.class);
        when(cmd.getVirtualMachine()).thenReturn(vm);
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, VM, NODE, "stopped"));
        when(client.startVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstart:9204:u:");
        when(client.getVmStatus(NODE, 9204L)).thenReturn("running");
        return cmd;
    }

    private VolumeObjectTO rootVolume() {
        VolumeObjectTO root = volume(Volume.Type.ROOT, 8 * GIB);
        when(root.getPath()).thenReturn("9204/vm-9204-disk-0.qcow2");
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> startOptions() {
        ArgumentCaptor<Map<String, String>> options = ArgumentCaptor.forClass(Map.class);
        verify(client).updateVmConfig(eq(NODE), eq(9204L), options.capture());
        return options.getValue();
    }

    @Test
    public void everyDataVolumeOfTheStartCommandBecomesADrive() {
        StartCommand cmd = start(null, rootVolume(), dataDisk(volume(Volume.Type.DATADISK, GIB), 1));

        Answer answer = handler.start(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(VOLUME_ID, startOptions().get("scsi1"));
    }

    @Test
    public void aDataVolumeThatWasDetachedSinceTheLastStartLosesItsDrive() {
        StartCommand cmd = start(null, rootVolume());
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,size=8G", "scsi2", VOLUME_ID));

        assertTrue(handler.start(cmd).getResult());

        Map<String, String> options = startOptions();
        assertEquals("scsi2", options.get("delete"));
    }

    @Test
    public void theDeletesOfAMovedRootDiskAndOfADetachedVolumeAreOneList() {
        StartCommand cmd = start(Collections.singletonMap("rootDiskController", "sata"), rootVolume());
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,size=8G", "scsi2", VOLUME_ID));

        assertTrue(handler.start(cmd).getResult());

        Map<String, String> options = startOptions();
        assertEquals("scsi0,scsi2", options.get("delete"));
        assertEquals(POOL + ":9204/vm-9204-disk-0.qcow2", options.get("sata0"));
    }

    @Test
    public void twoDisksOnTheSameDriveFailTheStart() {
        StartCommand cmd = start(null, rootVolume(), dataDisk(volume(Volume.Type.DATADISK, GIB), 1), dataDisk(volume(Volume.Type.DATADISK, GIB), 1));

        Answer answer = handler.start(cmd);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("used by two disks"));
        verify(client, never()).startVm(anyString(), anyLong());
    }

    @Test
    public void theLimitsOfTheRootDiskOfferingAreAppliedOnceAndNotAgain() {
        VolumeObjectTO root = rootVolume();
        when(root.getIopsReadRate()).thenReturn(200L);
        StartCommand cmd = start(null, root);
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,size=8G"));

        assertTrue(handler.start(cmd).getResult());
        assertEquals(POOL + ":9204/vm-9204-disk-0.qcow2,iops_rd=200", startOptions().get("scsi0"));
    }

    @Test
    public void aRootDiskThatHasTheLimitsAlreadyIsNotSetAgain() {
        VolumeObjectTO root = rootVolume();
        when(root.getIopsReadRate()).thenReturn(200L);
        StartCommand cmd = start(null, root);
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,iops_rd=200,size=8G"));

        assertTrue(handler.start(cmd).getResult());
        assertFalse(startOptions().containsKey("scsi0"));
    }

    // ---- delete ----

    private DeleteCommand delete(VolumeObjectTO volume) {
        return new DeleteCommand(volume);
    }

    @Test
    public void deletingADataVolumeDeletesItsImageAndNeverAVm() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(new ProxmoxApiClient.ImageInfo(GIB, 1, "qcow2", "/x"));
        when(client.deleteImage(NODE, VOLUME_ID)).thenReturn("UPID:pve1:1:2:3:imgdel:900000002:u:");

        Answer answer = handler.delete(delete(volume(Volume.Type.DATADISK, GIB)));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).waitForTask("UPID:pve1:1:2:3:imgdel:900000002:u:", 600);
        verify(client, never()).destroyVm(anyString(), anyLong());
        verify(client, never()).stopVm(anyString(), anyLong());
        // the only VM looked up is the account's holder (Phase 9), never the VM that the path might be taken for
        verify(client).findVm(900000002L);
    }

    @Test
    public void aDataVolumeThatTheHolderVmListsIsDeletedWithItsEntry() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(900000002L, "cs-holder-2", "pve2", "stopped");
        when(client.findVm(900000002L)).thenReturn(holder);
        Map<String, String> holderConfig = new HashMap<>();
        holderConfig.put("unused3", VOLUME_ID);
        when(client.getVmConfig("pve2", 900000002L)).thenReturn(holderConfig);
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(null);

        Answer answer = handler.delete(delete(volume(Volume.Type.DATADISK, GIB)));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).updateVmConfig("pve2", 900000002L, Collections.singletonMap("delete", "unused3"));
        verify(client, never()).deleteImage(anyString(), anyString());
    }

    @Test
    public void aListedVolumeWhoseFileIsStillThereAfterTheEntryWentIsDeletedAsAnImage() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(900000002L, "cs-holder-2", NODE, "stopped");
        when(client.findVm(900000002L)).thenReturn(holder);
        Map<String, String> holderConfig = new HashMap<>();
        holderConfig.put("unused3", VOLUME_ID);
        when(client.getVmConfig(NODE, 900000002L)).thenReturn(holderConfig);
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(new ProxmoxApiClient.ImageInfo(GIB, 1, "qcow2", "/x"));
        when(client.deleteImage(NODE, VOLUME_ID)).thenReturn("UPID:pve1:1:2:3:imgdel:900000002:u:");

        assertTrue(handler.delete(delete(volume(Volume.Type.DATADISK, GIB))).getResult());

        verify(client).deleteImage(NODE, VOLUME_ID);
    }

    @Test
    public void aDataVolumeWhoseImageIsGoneIsASuccessAndAHolderPathIsNeverTakenForAVm() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(null);

        assertTrue(handler.delete(delete(volume(Volume.Type.DATADISK, GIB))).getResult());
        // the type is not trusted alone: a path in the reserved range is a data volume
        assertTrue(handler.delete(delete(volume(Volume.Type.UNKNOWN, GIB))).getResult());

        verify(client, never()).deleteImage(anyString(), anyString());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    // ---- resize ----

    private ResizeVolumeCommand resize(long current, long wanted, String instance) {
        return new ResizeVolumeCommand(PATH, null, current, wanted, false, instance);
    }

    @Test
    public void anAttachedVolumeIsGrownToTheNewSizeInGib() {
        theVmIs("running", config("scsi1", VOLUME_ID + ",size=1G"));
        when(client.resizeDisk(NODE, 9204L, "scsi1", "5G")).thenReturn("UPID:pve1:1:2:3:resize:9204:u:");

        Answer answer = handler.resize(resize(GIB, 5 * GIB, VM));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(5 * GIB, ((ResizeVolumeAnswer) answer).getNewSize());
        verify(client).waitForTask("UPID:pve1:1:2:3:resize:9204:u:", 600);
    }

    @Test
    public void aShrinkIsRefusedAndTheSameSizeIsASuccessWithoutACall() {
        theVmIs("stopped", config("scsi1", VOLUME_ID + ",size=3G"));

        Answer shrink = handler.resize(resize(3 * GIB, GIB, VM));
        assertFalse(shrink.getResult());
        assertTrue(shrink.getDetails(), shrink.getDetails().contains("cannot shrink"));

        assertTrue(handler.resize(resize(3 * GIB, 3 * GIB, VM)).getResult());
        verify(client, never()).resizeDisk(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    public void aVolumeThatNoInstanceHasAttachedCannotBeResized() {
        Answer noInstance = handler.resize(resize(GIB, 2 * GIB, null));
        assertFalse(noInstance.getResult());
        assertTrue(noInstance.getDetails(), noInstance.getDetails().contains("attach the volume first"));

        theVmIs("running", config("scsi0", "x"));
        Answer notOnTheVm = handler.resize(resize(GIB, 2 * GIB, VM));
        assertFalse(notOnTheVm.getResult());
        assertTrue(notOnTheVm.getDetails(), notOnTheVm.getDetails().contains("not on a drive"));
    }

    // ---- statistics ----

    @Test
    public void volumeStatsAreTheSizeAndTheUsedSpaceOfEachImageAndAMissingImageIsLeftOut() {
        when(client.getImageInfo(NODE, POOL + ":" + PATH)).thenReturn(new ProxmoxApiClient.ImageInfo(10 * GIB, 524288, "qcow2", "/x"));
        when(client.getImageInfo(NODE, POOL + ":9204/vm-9204-gone.qcow2")).thenReturn(null);
        GetVolumeStatsCommand cmd = new GetVolumeStatsCommand(Storage.StoragePoolType.NetworkFilesystem, POOL_UUID, java.util.Arrays.asList(PATH, "9204/vm-9204-gone.qcow2"));

        GetVolumeStatsAnswer answer = (GetVolumeStatsAnswer) handler.volumeStats(cmd);

        assertTrue(answer.getResult());
        assertEquals(1, answer.getVolumeStats().size());
        assertEquals(524288, answer.getVolumeStats().get(PATH).getPhysicalSize());
        assertEquals(10 * GIB, answer.getVolumeStats().get(PATH).getVirtualSize());
        verify(client, never()).deleteImage(anyString(), anyString());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    // ---- renamed volumes: the alias map of the holder VM (Phase 9) ----

    private static final String RENAMED = POOL + ":900000002/vm-900000002-disk-3.qcow2";

    /** The volume's stored path no longer exists: the plugin handed it to the holder VM, which renamed it (alias recorded on the holder). */
    private void theVolumeWasRenamed() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(null);
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(900000002L, "cs-holder-2", NODE, "stopped");
        when(client.findVm(900000002L)).thenReturn(holder);
        Map<String, String> holderConfig = new HashMap<>();
        holderConfig.put("description", ProxmoxVolumeAliases.render("text", ProxmoxVolumeAliases.record(new HashMap<>(), VOLUME_ID, RENAMED)));
        holderConfig.put("unused0", RENAMED);
        when(client.getVmConfig(NODE, 900000002L)).thenReturn(holderConfig);
    }

    @Test
    public void attachingARenamedVolumeUsesItsCurrentVolumeId() {
        theVolumeWasRenamed();
        theVmIs("stopped", config("scsi0", "x"));

        assertTrue(handler.attach(new AttachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM, Collections.emptyMap())).getResult());

        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("scsi1", RENAMED));
    }

    @Test
    public void detachingARenamedVolumeFindsItsDrive() {
        theVolumeWasRenamed();
        theVmIs("stopped", config("scsi0", "x", "scsi1", RENAMED + ",size=1G"));

        assertTrue(handler.detach(new DettachCommand(dataDisk(volume(Volume.Type.DATADISK, GIB), 1), VM)).getResult());

        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("delete", "scsi1"));
    }

    @Test
    public void deletingARenamedVolumeDeletesTheCurrentOneAndForgetsTheAlias() {
        theVolumeWasRenamed();
        when(client.getImageInfo(NODE, RENAMED)).thenReturn(new ProxmoxApiClient.ImageInfo(GIB, 1, "qcow2", "/x"), (ProxmoxApiClient.ImageInfo) null);
        when(client.deleteImage(NODE, RENAMED)).thenReturn("UPID:pve1:1:2:3:imgdel:900000002:u:");

        assertTrue(handler.delete(delete(volume(Volume.Type.DATADISK, GIB))).getResult());

        verify(client).updateVmConfig(NODE, 900000002L, Collections.singletonMap("delete", "unused0"));
    }

    @Test
    public void resizingARenamedVolumeFindsItsDriveThroughTheAlias() {
        theVolumeWasRenamed();
        theVmIs("running", config("scsi1", RENAMED + ",size=1G"));
        when(client.resizeDisk(NODE, 9204L, "scsi1", "5G")).thenReturn("UPID:pve1:1:2:3:resize:9204:u:");
        com.cloud.agent.api.to.StorageFilerTO pool = mock(com.cloud.agent.api.to.StorageFilerTO.class);
        when(pool.getUuid()).thenReturn(POOL_UUID);

        Answer answer = handler.resize(new ResizeVolumeCommand(PATH, pool, GIB, 5 * GIB, false, VM));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).resizeDisk(NODE, 9204L, "scsi1", "5G");
    }

    // ---- copy to another primary storage (Phase 9, detached volume) ----

    private static final String OTHER_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER = "cs-" + OTHER_UUID;

    private static VolumeObjectTO volumeOn(String poolUuid, String path) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(poolUuid);
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getUuid()).thenReturn(UUID);
        when(volume.getAccountId()).thenReturn(2L);
        when(volume.getDataStore()).thenReturn(pool);
        when(volume.getPath()).thenReturn(path);
        when(volume.getSize()).thenReturn(5 * GIB);
        return volume;
    }

    private static CopyCommand copyOf(VolumeObjectTO src, VolumeObjectTO dest) {
        CopyCommand cmd = mock(CopyCommand.class);
        when(cmd.getSrcTO()).thenReturn(src);
        when(cmd.getDestTO()).thenReturn(dest);
        return cmd;
    }

    @Test
    public void aDetachedDataVolumeIsCopiedByMovingTheHoldersEntryAndKeepsTheSource() {
        Map<String, String> holderConfig = new HashMap<>();
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(new ProxmoxApiClient.ImageInfo(5 * GIB, 1024, "qcow2", "/mnt/x"));
        when(client.getImageInfo(NODE, OTHER + ":900000002/vm-900000002-disk-1.qcow2"))
                .thenReturn(new ProxmoxApiClient.ImageInfo(5 * GIB, 2048, "qcow2", "/mnt/y"));
        when(client.findVm(900000002L)).thenReturn(new ProxmoxApiClient.VmLocation(900000002L, "cs-holder-2", NODE, "stopped"));
        when(client.getVmConfig(NODE, 900000002L)).thenReturn(holderConfig);
        org.mockito.Mockito.doAnswer(call -> {
            holderConfig.put("unused0", OTHER + ":900000002/vm-900000002-disk-1.qcow2");
            return "UPID:move";
        }).when(client).moveDisk(NODE, 900000002L, "unused0", OTHER, false);

        Answer answer = handler.copy(copyOf(volumeOn(POOL_UUID, PATH), volumeOn(OTHER_UUID, null)));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("900000002/vm-900000002-disk-1.qcow2", ((VolumeObjectTO) ((CopyCmdAnswer) answer).getNewData()).getPath());
        verify(client).updateVmConfig(NODE, 900000002L, Collections.singletonMap("unused0", VOLUME_ID));
        verify(client, never()).moveDisk(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    public void copyingToTheStorageTheVolumeIsOnFails() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(new ProxmoxApiClient.ImageInfo(5 * GIB, 1024, "qcow2", "/mnt/x"));

        Answer answer = handler.copy(copyOf(volumeOn(POOL_UUID, PATH), volumeOn(POOL_UUID, null)));

        assertFalse(answer.getResult());
        verify(client, never()).moveDisk(anyString(), anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void copyingAVolumeWithoutAnImageFailsAndChangesNothing() {
        when(client.getImageInfo(NODE, VOLUME_ID)).thenReturn(null);

        Answer answer = handler.copy(copyOf(volumeOn(POOL_UUID, PATH), volumeOn(OTHER_UUID, null)));

        assertFalse(answer.getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    // ---- live move of an attached volume, and the way back to the holder (Phase 9) ----

    private static final String OWNED_PATH = "9204/vm-9204-disk-2.qcow2";
    private static final String OWNED_ID = POOL + ":" + OWNED_PATH;

    private static MigrateVolumeCommand liveMove(String path, String poolUuid) {
        StorageFilerTO pool = mock(StorageFilerTO.class);
        when(pool.getUuid()).thenReturn(poolUuid);
        MigrateVolumeCommand cmd = mock(MigrateVolumeCommand.class);
        when(cmd.getAttachedVmName()).thenReturn(VM);
        when(cmd.getVolumePath()).thenReturn(path);
        when(cmd.getPool()).thenReturn(pool);
        return cmd;
    }

    @Test
    public void aLiveMoveMovesTheDriveAndAnswersWithThePathTheVmOwnsNow() {
        Map<String, String> vmConfig = config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,size=8G", "scsi1", VOLUME_ID + ",size=5G");
        theVmIs("running", vmConfig);
        org.mockito.Mockito.doAnswer(call -> {
            vmConfig.put("scsi1", OTHER + ":9204/vm-9204-disk-1.qcow2,size=5G");
            return "UPID:move";
        }).when(client).moveDisk(NODE, 9204L, "scsi1", OTHER);

        Answer answer = handler.migrateVolume(liveMove(PATH, OTHER_UUID));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("9204/vm-9204-disk-1.qcow2", ((MigrateVolumeAnswer) answer).getVolumePath());
    }

    @Test
    public void aLiveMoveToTheStorageTheVolumeIsOnFailsWithoutMovingAnything() {
        theVmIs("running", config("scsi1", VOLUME_ID + ",size=5G"));

        Answer answer = handler.migrateVolume(liveMove(PATH, POOL_UUID));

        assertFalse(answer.getResult());
        verify(client, never()).moveDisk(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    public void detachingAVolumeTheInstanceOwnsHandsItToTheHolderAndTellsCloudStackThePath() {
        Map<String, String> vmConfig = config("scsi1", OWNED_ID + ",size=5G");
        theVmIs("running", vmConfig);
        Map<String, String> holderConfig = new HashMap<>();
        when(client.findVm(900000002L)).thenReturn(new ProxmoxApiClient.VmLocation(900000002L, "cs-holder-2", NODE, "stopped"));
        when(client.getVmConfig(NODE, 900000002L)).thenReturn(holderConfig);
        org.mockito.Mockito.doAnswer(call -> {
            vmConfig.remove("scsi1");
            vmConfig.put("unused0", OWNED_ID);
            return null;
        }).when(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("delete", "scsi1"));
        org.mockito.Mockito.doAnswer(call -> {
            holderConfig.put("unused0", POOL + ":900000002/vm-900000002-disk-1.qcow2");
            return "UPID:reassign";
        }).when(client).reassignDisk(NODE, 9204L, "unused0", 900000002L, "unused0");
        VolumeObjectTO volume = volumeOn(POOL_UUID, OWNED_PATH);
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        DettachCommand cmd = mock(DettachCommand.class);
        when(cmd.getVmName()).thenReturn(VM);
        when(cmd.getDisk()).thenReturn(dataDisk(volume, 1));

        Answer answer = handler.detach(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("900000002/vm-900000002-disk-1.qcow2", answer.getContextParam("volumePath"));
    }

    @Test
    public void deletingAVolumeThatAStoppedInstanceStillListsRemovesItsEntriesAndTheDisk() {
        Map<String, String> vmConfig = config("scsi1", OWNED_ID + ",size=5G");
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204L, VM, NODE, "stopped"));
        when(client.getVmConfig(NODE, 9204L)).thenReturn(vmConfig);
        org.mockito.Mockito.doAnswer(call -> {
            vmConfig.remove("scsi1");
            vmConfig.put("unused0", OWNED_ID);
            return null;
        }).when(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("delete", "scsi1"));
        VolumeObjectTO volume = volumeOn(POOL_UUID, OWNED_PATH);
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        DeleteCommand cmd = mock(DeleteCommand.class);
        when(cmd.getData()).thenReturn(volume);

        Answer answer = handler.delete(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("delete", "unused0"));
    }
}


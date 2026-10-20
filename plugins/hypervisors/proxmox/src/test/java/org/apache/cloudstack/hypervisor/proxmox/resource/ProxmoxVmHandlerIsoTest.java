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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate.BootloaderType;

/** Phase 6: ISOs (attach, detach, the drives at start) and the blank root volume of a VM deployed from an ISO. */
public class ProxmoxVmHandlerIsoTest {
    private static final String NODE = "pve1";
    private static final String VM = "i-2-10-VM";
    private static final String FOLDER = "template/tmpl/2/211";
    private static final String FILE = "211-2-654b5686.iso";
    private static final String STORE_ENTRY = ProxmoxStorageNames.secondaryStorageId("10.0.32.4", "/acs/secondary");
    private static final String STORE_MOUNT = "/mnt/" + STORE_ENTRY;
    private static final String ISO_ENTRY = ProxmoxStorageNames.isoStorageId("10.0.32.4", "/acs/secondary", FOLDER);
    private static final String ISO_VOLUME = ISO_ENTRY + ":iso/" + FILE;
    private static final String POOL = "cs-7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final long GIB = 1024L * 1024 * 1024;

    private ProxmoxApiClient client;
    private ProxmoxVmHandler handler;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        handler = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "x", millis -> { }, () -> 600);
        storageBehavesLikeProxmox();
    }

    private static ProxmoxApiClient.StorageConfig aStorage(String id) {
        return new ProxmoxApiClient.StorageConfig(id, "dir", null, null, null, "iso", null);
    }

    /** Proxmox as far as entries go: one that was created is found afterwards and is active on the node. */
    private void storageBehavesLikeProxmox() {
        Set<String> created = new HashSet<>();
        when(client.findStorage(anyString())).thenAnswer(invocation -> created.contains(invocation.<String>getArgument(0))
                ? aStorage(invocation.getArgument(0)) : null);
        doAnswer(invocation -> created.add(invocation.getArgument(0))).when(client)
                .createSecondaryNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString());
        doAnswer(invocation -> created.add(invocation.getArgument(0))).when(client).createIsoDirStorage(anyString(), anyString(), anyString());
        when(client.getStorageStatus(eq(NODE), anyString())).thenReturn(new ProxmoxApiClient.StorageStatus(true, true, 1L, 1L, 1L));
    }

    private static TemplateObjectTO iso(String path) {
        NfsTO store = mock(NfsTO.class);
        when(store.getUrl()).thenReturn("nfs://10.0.32.4/acs/secondary");
        when(store.getNfsVersion()).thenReturn("3");
        TemplateObjectTO iso = mock(TemplateObjectTO.class);
        when(iso.getDataStore()).thenReturn(store);
        when(iso.getPath()).thenReturn(path);
        return iso;
    }

    private static DiskTO isoDisk(TemplateObjectTO iso, long slot) {
        return new DiskTO(iso, slot, null, Volume.Type.ISO);
    }

    private void theVmIs(String state, Map<String, String> runningConfig) {
        when(client.findVmByName(VM)).thenReturn(new ProxmoxApiClient.VmLocation(9204, VM, NODE, state));
        when(client.getRunningVmConfig(NODE, 9204L)).thenReturn(runningConfig);
        when(client.getVmConfig(NODE, 9204L)).thenReturn(runningConfig);
    }

    private static Map<String, String> config(String... keyValues) {
        Map<String, String> config = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            config.put(keyValues[i], keyValues[i + 1]);
        }
        return config;
    }

    // ---- attach ----

    @Test
    public void anIsoIsPutInTheDriveOfItsSlotAfterItsFolderGotAnEntryOnTheMountedStore() {
        theVmIs("running", config("ide2", "none,media=cdrom"));

        Answer answer = handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).createSecondaryNfsStorage(STORE_ENTRY, "10.0.32.4", "/acs/secondary", STORE_MOUNT, "vers=3,ro");
        verify(client).createIsoDirStorage(ISO_ENTRY, STORE_MOUNT, FOLDER);
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("ide2", ISO_VOLUME + ",media=cdrom"));
    }

    @Test
    public void theSecondSlotIsTheSecondDriveAndAnotherAttachOfTheSameIsoCreatesNoEntry() {
        theVmIs("running", config("ide2", "none,media=cdrom", "ide3", "none,media=cdrom"));
        assertTrue(handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM)).getResult());

        assertTrue(handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 4), VM)).getResult());

        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("ide3", ISO_VOLUME + ",media=cdrom"));
        verify(client, times(1)).createIsoDirStorage(anyString(), anyString(), anyString());
        verify(client, times(1)).createSecondaryNfsStorage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void aRunningVmWithoutTheDriveIsToldToRestartAndNothingIsChanged() {
        // Proxmox keeps a drive added to a running VM pending, and answers 200: it must not be tried
        theVmIs("running", config("scsi0", "x"));

        Answer answer = handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("stop and start"));
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aStoppedVmGetsTheDriveEvenIfItHadNone() {
        theVmIs("stopped", config("scsi0", "x"));

        assertTrue(handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM)).getResult());
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("ide2", ISO_VOLUME + ",media=cdrom"));
    }

    @Test
    public void anIsoThatCannotBeUsedFailsWithAReason() {
        theVmIs("running", config("ide2", "none,media=cdrom"));

        Answer wrongSlot = handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 9), VM));
        assertFalse(wrongSlot.getResult());
        assertTrue(wrongSlot.getDetails(), wrongSlot.getDetails().contains("CD-ROM slot 9"));

        Answer wrongName = handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/template.properties"), 3), VM));
        assertFalse(wrongName.getResult());
        assertTrue(wrongName.getDetails(), wrongName.getDetails().contains(".iso"));

        Answer noFolder = handler.attachIso(new AttachCommand(isoDisk(iso(FILE), 3), VM));
        assertFalse(noFolder.getResult());

        TemplateObjectTO onPrimary = mock(TemplateObjectTO.class);
        when(onPrimary.getDataStore()).thenReturn(mock(PrimaryDataStoreTO.class));
        Answer notNfs = handler.attachIso(new AttachCommand(isoDisk(onPrimary, 3), VM));
        assertFalse(notNfs.getResult());
        assertTrue(notNfs.getDetails(), notNfs.getDetails().contains("NFS secondary"));
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aVmProxmoxDoesNotKnowCannotTakeAnIso() {
        assertFalse(handler.attachIso(new AttachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM)).getResult());
    }

    @Test
    public void aDataVolumeIsStillUnsupported() {
        DiskTO data = new DiskTO(mock(VolumeObjectTO.class), 1L, null, Volume.Type.DATADISK);

        assertTrue(handler.attachIso(new AttachCommand(data, VM)) instanceof UnsupportedAnswer);
        assertTrue(handler.detachIso(new DettachCommand(data, VM)) instanceof UnsupportedAnswer);
    }

    // ---- detach ----

    @Test
    public void detachingEjectsTheDiscAndKeepsTheDrive() {
        theVmIs("running", config("ide2", ISO_VOLUME + ",media=cdrom,size=350K"));

        Answer answer = handler.detachIso(new DettachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).updateVmConfig(NODE, 9204L, Collections.singletonMap("ide2", "none,media=cdrom"));
    }

    @Test
    public void detachingAnEmptyDriveOrAnotherIsoOrAGoneVmIsASuccessWithoutAChange() {
        theVmIs("running", config("ide2", "none,media=cdrom"));
        assertTrue(handler.detachIso(new DettachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM)).getResult());

        theVmIs("running", config("ide2", ISO_ENTRY + ":iso/other.iso,media=cdrom,size=1M"));
        assertTrue(handler.detachIso(new DettachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM)).getResult());

        when(client.findVmByName(VM)).thenReturn(null);
        assertTrue(handler.detachIso(new DettachCommand(isoDisk(iso(FOLDER + "/" + FILE), 3), VM)).getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    // ---- the drives at start ----

    private StartCommand startWithIsos(BootloaderType bootloader, DiskTO... isos) {
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getPath()).thenReturn("9204/vm-9204-disk-0.qcow2");
        DiskTO root = mock(DiskTO.class);
        when(root.getType()).thenReturn(Volume.Type.ROOT);
        when(root.getData()).thenReturn(volume);
        DiskTO[] disks = new DiskTO[isos.length + 1];
        disks[0] = root;
        System.arraycopy(isos, 0, disks, 1, isos.length);
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn(VM);
        when(vm.getCpus()).thenReturn(1);
        when(vm.getMaxRam()).thenReturn(GIB);
        when(vm.getDisks()).thenReturn(disks);
        when(vm.getBootloader()).thenReturn(bootloader);
        StartCommand cmd = mock(StartCommand.class);
        when(cmd.getVirtualMachine()).thenReturn(vm);
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, VM, NODE, "stopped"));
        when(client.startVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstart:9204:u:");
        when(client.getVmStatus(NODE, 9204L)).thenReturn("running");
        return cmd;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> startOptions() {
        ArgumentCaptor<Map<String, String>> options = ArgumentCaptor.forClass(Map.class);
        verify(client).updateVmConfig(eq(NODE), eq(9204L), options.capture());
        return options.getValue();
    }

    @Test
    public void everyCdromSlotOfTheStartCommandBecomesADriveAndABootableIsoIsBootedFirst() {
        TemplateObjectTO empty = mock(TemplateObjectTO.class);
        StartCommand cmd = startWithIsos(BootloaderType.CD, isoDisk(iso(FOLDER + "/" + FILE), 3), isoDisk(empty, 4));

        Answer answer = handler.start(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        Map<String, String> options = startOptions();
        assertEquals(ISO_VOLUME + ",media=cdrom", options.get("ide2"));
        assertEquals("none,media=cdrom", options.get("ide3"));
        assertEquals("order=ide2;scsi0", options.get("boot"));
    }

    @Test
    public void withoutABootableIsoTheVmBootsFromItsDisk() {
        StartCommand cmd = startWithIsos(null, isoDisk(iso(FOLDER + "/" + FILE), 3));

        assertTrue(handler.start(cmd).getResult());
        Map<String, String> options = startOptions();
        assertEquals("order=scsi0", options.get("boot"));
        assertEquals(ISO_VOLUME + ",media=cdrom", options.get("ide2"));
    }

    @Test
    public void aDriveThatHoldsADetachedIsoFromAnEarlierStartIsEmptied() {
        StartCommand cmd = startWithIsos(null, isoDisk(mock(TemplateObjectTO.class), 3));
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config("ide2", ISO_VOLUME + ",media=cdrom", "ide3", ISO_VOLUME + ",media=cdrom", "scsi0", "x"));

        assertTrue(handler.start(cmd).getResult());
        Map<String, String> options = startOptions();
        assertEquals("none,media=cdrom", options.get("ide2"));
        assertEquals("none,media=cdrom", options.get("ide3"));
        assertFalse(options.containsKey("scsi0"));
    }

    @Test
    public void anIsoThatCannotBeUsedFailsTheStartWithTheReason() {
        StartCommand cmd = startWithIsos(null, isoDisk(iso(FOLDER + "/template.properties"), 3));

        Answer answer = handler.start(cmd);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains(".iso"));
        verify(client, never()).startVm(anyString(), anyLong());
    }

    // ---- a blank root volume ----

    private CreateObjectCommand blankRootVolume(Long size, Volume.Type type) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn("7fd5565c-6c1e-3032-81ec-d404812915ab");
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getVolumeType()).thenReturn(type);
        when(volume.getSize()).thenReturn(size);
        when(volume.getDataStore()).thenReturn(pool);
        when(volume.getVmName()).thenReturn(VM);
        return new CreateObjectCommand(volume);
    }

    @Test
    public void aBlankRootVolumeIsAVmWithAnEmptyDiskRoundedUpToWholeGibAndHasTheSamePathFormAsAnyOther() {
        when(client.nextVmId()).thenReturn(9204L);
        when(client.createVm(eq(NODE), eq(9204L), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:9204:u:");
        when(client.getVmConfig(NODE, 9204L)).thenReturn(config("scsi0", POOL + ":9204/vm-9204-disk-0.qcow2,size=6G"));

        Answer answer = handler.createObject(blankRootVolume(5 * GIB + 1, Volume.Type.ROOT));

        assertTrue(answer.getDetails(), answer.getResult());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(client).createVm(eq(NODE), eq(9204L), params.capture());
        assertEquals(POOL + ":6,format=qcow2", params.getValue().get("scsi0"));
        assertEquals(VM, params.getValue().get("name"));
        VolumeObjectTO created = (VolumeObjectTO) ((CreateObjectAnswer) answer).getData();
        assertEquals("9204/vm-9204-disk-0.qcow2", created.getPath());
        assertEquals(Long.valueOf(6 * GIB), created.getSize());
        verify(client, never()).resizeDisk(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    public void onlyRootVolumesWithASizeAreCreated() {
        assertTrue(handler.createObject(blankRootVolume(GIB, Volume.Type.DATADISK)) instanceof UnsupportedAnswer);

        Answer noSize = handler.createObject(blankRootVolume(null, Volume.Type.ROOT));
        assertFalse(noSize.getResult());
    }
}

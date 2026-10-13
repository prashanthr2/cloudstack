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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;

public class ProxmoxVmHandlerTest {
    private static final String NODE = "pve1";
    private static final String POOL_UUID = "7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final String POOL = "cs-" + POOL_UUID;
    private static final String TEMPLATE_PATH = "template/tmpl/1/4/33522506-94bb-35e2-9eed-ef5b7ba78036.qcow2";
    private static final String TEMPLATE_ENTRY = ProxmoxStorageNames.templateStorageId("10.0.32.4", "/acs/secondary", "template/tmpl/1/4");
    private static final String TEMPLATE_VOLUME = TEMPLATE_ENTRY + ":import/33522506-94bb-35e2-9eed-ef5b7ba78036.qcow2";
    private static final long GIB = 1024L * 1024 * 1024;

    private ProxmoxApiClient client;
    private ProxmoxVmHandler handler;
    private int pauses;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        handler = new ProxmoxVmHandler(() -> client, NODE, millis -> pauses++, () -> 600);
    }

    // ---- template to primary storage ----

    private TemplateObjectTO secondaryTemplate(String nfsVersion, String path) {
        NfsTO store = mock(NfsTO.class);
        when(store.getUrl()).thenReturn("nfs://10.0.32.4/acs/secondary");
        when(store.getNfsVersion()).thenReturn(nfsVersion);
        TemplateObjectTO template = mock(TemplateObjectTO.class);
        when(template.getDataStore()).thenReturn(store);
        when(template.getPath()).thenReturn(path);
        when(template.getSize()).thenReturn(8L * GIB);
        return template;
    }

    private CopyCommand templateToPrimary(TemplateObjectTO source) {
        TemplateObjectTO dest = mock(TemplateObjectTO.class);
        when(dest.getDataStore()).thenReturn(mock(PrimaryDataStoreTO.class));
        CopyCommand cmd = mock(CopyCommand.class);
        when(cmd.getSrcTO()).thenReturn(source);
        when(cmd.getDestTO()).thenReturn(dest);
        return cmd;
    }

    private void templateIsVisible() {
        when(client.listImportVolumes(NODE, TEMPLATE_ENTRY)).thenReturn(Collections.singletonList(
                new ProxmoxApiClient.ImportVolume(TEMPLATE_VOLUME, "qcow2", 1769537536L)));
    }

    @Test
    public void aTemplateIsExposedThroughAReadOnlyEntryOnItsFolderAndNothingIsCopied() {
        templateIsVisible();

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(templateToPrimary(secondaryTemplate("3", TEMPLATE_PATH)));

        verify(client).createImportStorage(TEMPLATE_ENTRY, "10.0.32.4", "/acs/secondary", "template/tmpl/1/4", "vers=3,ro");
        assertTrue(answer.getDetails(), answer.getResult());
        TemplateObjectTO onPrimary = (TemplateObjectTO) answer.getNewData();
        assertEquals(TEMPLATE_VOLUME, onPrimary.getPath());
        assertEquals(Long.valueOf(8L * GIB), onPrimary.getSize());
        assertEquals(Storage.ImageFormat.QCOW2, onPrimary.getFormat());
    }

    @Test
    public void withoutAnNfsVersionTheEntryIsOnlyReadOnly() {
        templateIsVisible();

        handler.copy(templateToPrimary(secondaryTemplate(null, TEMPLATE_PATH)));

        verify(client).createImportStorage(TEMPLATE_ENTRY, "10.0.32.4", "/acs/secondary", "template/tmpl/1/4", "ro");
    }

    @Test
    public void anExistingEntryIsReusedWhenTheTemplateIsUsedAgain() {
        when(client.findStorage(TEMPLATE_ENTRY)).thenReturn(new ProxmoxApiClient.StorageConfig(TEMPLATE_ENTRY, "nfs", "10.0.32.4",
                "/acs/secondary", null, "import", "vers=3,ro"));
        templateIsVisible();

        Answer answer = handler.copy(templateToPrimary(secondaryTemplate("3", TEMPLATE_PATH)));

        assertTrue(answer.getResult());
        verify(client, never()).createImportStorage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void anEntryAnotherCommandCreatedAMomentAgoIsAccepted() {
        when(client.findStorage(TEMPLATE_ENTRY)).thenReturn(null, new ProxmoxApiClient.StorageConfig(TEMPLATE_ENTRY, "nfs", null, null, null,
                "import", null));
        doThrow(new ProxmoxApiException("storage ID already defined")).when(client)
                .createImportStorage(anyString(), anyString(), anyString(), anyString(), anyString());
        templateIsVisible();

        assertTrue(handler.copy(templateToPrimary(secondaryTemplate("3", TEMPLATE_PATH))).getResult());
    }

    @Test
    public void whenTheEntryCannotBeCreatedTheReasonFromProxmoxIsReported() {
        doThrow(new ProxmoxApiException("Proxmox API POST /api2/json/storage failed: HTTP 403 Forbidden")).when(client)
                .createImportStorage(anyString(), anyString(), anyString(), anyString(), anyString());

        Answer answer = handler.copy(templateToPrimary(secondaryTemplate("3", TEMPLATE_PATH)));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("403"));
    }

    @Test
    public void aTemplateFileTheNodeCannotSeeGetsAnAnswerThatSaysWhatToCheck() {
        when(client.listImportVolumes(NODE, TEMPLATE_ENTRY)).thenReturn(Collections.emptyList());

        Answer answer = handler.copy(templateToPrimary(secondaryTemplate("3", TEMPLATE_PATH)));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("NFS server exports"));
        assertEquals(ProxmoxVmHandler.ENTRY_ACTIVATION_ATTEMPTS, pauses);
    }

    @Test
    public void theEntryIsGivenTimeToBeMountedOnTheNode() {
        when(client.listImportVolumes(NODE, TEMPLATE_ENTRY)).thenThrow(new ProxmoxApiException("storage is not online"))
                .thenReturn(Collections.singletonList(new ProxmoxApiClient.ImportVolume(TEMPLATE_VOLUME, "qcow2", 1L)));

        assertTrue(handler.copy(templateToPrimary(secondaryTemplate("3", TEMPLATE_PATH))).getResult());
        assertEquals(1, pauses);
    }

    @Test
    public void aTemplatePathWithoutAFolderIsRefused() {
        Answer answer = handler.copy(templateToPrimary(secondaryTemplate("3", "just-a-file.qcow2")));

        assertFalse(answer.getResult());
        verify(client, never()).createImportStorage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void copyingSomethingElseIsRefusedWithACopyAnswer() {
        CopyCommand cmd = mock(CopyCommand.class);
        when(cmd.getSrcTO()).thenReturn(mock(VolumeObjectTO.class));
        when(cmd.getDestTO()).thenReturn(mock(VolumeObjectTO.class));

        Answer answer = handler.copy(cmd);

        assertTrue(answer instanceof CopyCmdAnswer);
        assertFalse(answer.getResult());
    }

    // ---- root volume ----

    private CopyCommand rootVolumeFromTemplate(Long size) {
        TemplateObjectTO source = mock(TemplateObjectTO.class);
        when(source.getDataStore()).thenReturn(mock(PrimaryDataStoreTO.class));
        when(source.getPath()).thenReturn(TEMPLATE_VOLUME);
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL_UUID);
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getDataStore()).thenReturn(pool);
        when(volume.getVmName()).thenReturn("i-2-10-VM");
        when(volume.getSize()).thenReturn(size);
        CopyCommand cmd = mock(CopyCommand.class);
        when(cmd.getSrcTO()).thenReturn(source);
        when(cmd.getDestTO()).thenReturn(volume);
        return cmd;
    }

    private void vmIsCreatedWithDisk(long vmid, String disk) {
        when(client.nextVmId()).thenReturn(vmid);
        when(client.createVm(eq(NODE), eq(vmid), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:" + vmid + ":u:");
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", disk);
        when(client.getVmConfig(NODE, vmid)).thenReturn(config);
    }

    @Test
    public void theRootVolumeIsAVmCreatedWithTheTemplateFileImportedAsItsDisk() {
        vmIsCreatedWithDisk(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=8G");

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(8L * GIB));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(client).createVm(eq(NODE), eq(9204L), params.capture());
        assertEquals("i-2-10-VM", params.getValue().get("name"));
        assertEquals(POOL + ":0,import-from=" + TEMPLATE_VOLUME + ",format=qcow2", params.getValue().get("scsi0"));
        verify(client).waitForTask("UPID:pve1:1:2:3:qmcreate:9204:u:", 600);
        verify(client, never()).resizeDisk(anyString(), anyLong(), anyString(), anyString());
        assertTrue(answer.getDetails(), answer.getResult());
        VolumeObjectTO created = (VolumeObjectTO) answer.getNewData();
        assertEquals("9204/vm-9204-disk-0.qcow2", created.getPath());
        assertEquals(Long.valueOf(8L * GIB), created.getSize());
        assertEquals(Storage.ImageFormat.QCOW2, created.getFormat());
    }

    @Test
    public void aRootDiskBiggerThanTheTemplateIsGrown() {
        vmIsCreatedWithDisk(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=8G");

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(20L * GIB));

        verify(client).resizeDisk(NODE, 9204L, "scsi0", Long.toString(20L * GIB));
        assertEquals(Long.valueOf(20L * GIB), ((VolumeObjectTO) answer.getNewData()).getSize());
    }

    @Test
    public void aVmIdTakenByAnotherCallerIsReplacedAndTheOtherVmIsLeftAlone() {
        when(client.nextVmId()).thenReturn(9200L, 9201L);
        when(client.createVm(eq(NODE), eq(9200L), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:9200:u:");
        when(client.createVm(eq(NODE), eq(9201L), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:9201:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmcreate failed: unable to create VM 9200 - VM 9200 already exists on node 'pve2'"))
                .when(client).waitForTask("UPID:pve1:1:2:3:qmcreate:9200:u:", 600);
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", POOL + ":9201/vm-9201-disk-0.qcow2,size=8G");
        when(client.getVmConfig(NODE, 9201L)).thenReturn(config);

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(null));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("9201/vm-9201-disk-0.qcow2", ((VolumeObjectTO) answer.getNewData()).getPath());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    @Test
    public void aFailedCreationRemovesWhatItLeftBehind() {
        when(client.nextVmId()).thenReturn(9204L);
        when(client.createVm(eq(NODE), eq(9204L), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:9204:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmcreate failed: qemu-img: could not open the source")).when(client)
                .waitForTask("UPID:pve1:1:2:3:qmcreate:9204:u:", 600);
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "stopped"));
        when(client.destroyVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmdestroy:9204:u:");

        Answer answer = handler.copy(rootVolumeFromTemplate(null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("could not open the source"));
        verify(client).destroyVm(NODE, 9204L);
    }

    @Test
    public void aRootVolumeNeedsATemplateThatCameFromTheImportEntry() {
        CopyCommand cmd = rootVolumeFromTemplate(null);
        when(((TemplateObjectTO) cmd.getSrcTO()).getPath()).thenReturn("some/other/path");

        Answer answer = handler.copy(cmd);

        assertFalse(answer.getResult());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    // ---- start ----

    private StartCommand startCommand(String rootPath) {
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getPath()).thenReturn(rootPath);
        DiskTO root = mock(DiskTO.class);
        when(root.getType()).thenReturn(Volume.Type.ROOT);
        when(root.getData()).thenReturn(volume);
        NicTO nic = mock(NicTO.class);
        when(nic.getDeviceId()).thenReturn(0);
        when(nic.getMac()).thenReturn("02:00:00:aa:bb:01");
        when(nic.getName()).thenReturn("vmbr1");
        when(nic.getBroadcastUri()).thenReturn(URI.create("vlan://100"));
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn("i-2-10-VM");
        when(vm.getCpus()).thenReturn(2);
        when(vm.getMaxRam()).thenReturn(1024L * 1024 * 1024);
        when(vm.getPlatformEmulator()).thenReturn("l26");
        when(vm.getDisks()).thenReturn(new DiskTO[] {root});
        when(vm.getNics()).thenReturn(new NicTO[] {nic});
        StartCommand cmd = mock(StartCommand.class);
        when(cmd.getVirtualMachine()).thenReturn(vm);
        return cmd;
    }

    @Test
    public void aVmOnThisNodeIsConfiguredAndStarted() {
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "stopped"));
        when(client.startVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstart:9204:u:");

        Answer answer = handler.start(startCommand("9204/vm-9204-disk-0.qcow2"));

        assertTrue(answer instanceof StartAnswer);
        assertTrue(answer.getDetails(), answer.getResult());
        InOrder order = inOrder(client);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> options = ArgumentCaptor.forClass(Map.class);
        order.verify(client).updateVmConfig(eq(NODE), eq(9204L), options.capture());
        order.verify(client).startVm(NODE, 9204L);
        order.verify(client).waitForTask("UPID:pve1:1:2:3:qmstart:9204:u:", 600);
        assertEquals("2", options.getValue().get("cores"));
        assertEquals("1024", options.getValue().get("memory"));
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,tag=100,firewall=0", options.getValue().get("net0"));
        verify(client, never()).migrateVm(anyString(), anyLong(), anyString());
    }

    @Test
    public void aVmThatIsOnAnotherNodeIsMovedHereBeforeItIsStarted() {
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", "pve2", "stopped"));
        when(client.migrateVm("pve2", 9204L, NODE)).thenReturn("UPID:pve2:1:2:3:qmigrate:9204:u:");
        when(client.startVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstart:9204:u:");

        Answer answer = handler.start(startCommand("9204/vm-9204-disk-0.qcow2"));

        assertTrue(answer.getDetails(), answer.getResult());
        InOrder order = inOrder(client);
        order.verify(client).migrateVm("pve2", 9204L, NODE);
        order.verify(client).waitForTask("UPID:pve2:1:2:3:qmigrate:9204:u:", 600);
        order.verify(client).updateVmConfig(eq(NODE), eq(9204L), anyMap());
        order.verify(client).startVm(NODE, 9204L);
    }

    @Test
    public void startingAVmThatIsAlreadyRunningChangesNothing() {
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "running"));

        assertTrue(handler.start(startCommand("9204/vm-9204-disk-0.qcow2")).getResult());
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
        verify(client, never()).startVm(anyString(), anyLong());
    }

    @Test
    public void aVmProxmoxDoesNotKnowCannotBeStarted() {
        Answer answer = handler.start(startCommand("9204/vm-9204-disk-0.qcow2"));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("9204"));
    }

    @Test
    public void aRootVolumeThatIsNotAProxmoxDiskCannotBeStarted() {
        assertFalse(handler.start(startCommand("some-uuid")).getResult());
    }

    @Test
    public void aFailedStartCarriesProxmoxsReason() {
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "stopped"));
        when(client.startVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstart:9204:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmstart failed: bridge 'vmbr9' does not exist")).when(client)
                .waitForTask("UPID:pve1:1:2:3:qmstart:9204:u:", 600);

        Answer answer = handler.start(startCommand("9204/vm-9204-disk-0.qcow2"));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("vmbr9"));
    }

    // ---- stop and reboot ----

    private StopCommand stopCommand(boolean force) {
        StopCommand cmd = mock(StopCommand.class);
        when(cmd.getVmName()).thenReturn("i-2-10-VM");
        when(cmd.isForceStop()).thenReturn(force);
        return cmd;
    }

    @Test
    public void aRunningVmIsAskedToShutDownAndStoppedByForceIfItDoesNotComply() {
        when(client.findVmByName("i-2-10-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", "pve2", "running"));
        when(client.shutdownVm("pve2", 9204L, ProxmoxVmHandler.SHUTDOWN_TIMEOUT_SECONDS)).thenReturn("UPID:pve2:1:2:3:qmshutdown:9204:u:");

        Answer answer = handler.stop(stopCommand(false));

        assertTrue(answer instanceof StopAnswer);
        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).waitForTask("UPID:pve2:1:2:3:qmshutdown:9204:u:", 600);
        verify(client, never()).stopVm(anyString(), anyLong());
    }

    @Test
    public void aForcedStopPullsThePowerAtOnce() {
        when(client.findVmByName("i-2-10-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "running"));
        when(client.stopVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstop:9204:u:");

        assertTrue(handler.stop(stopCommand(true)).getResult());
        verify(client, never()).shutdownVm(anyString(), anyLong(), anyInt());
    }

    @Test
    public void stoppingAVmThatIsStoppedOrGoneIsASuccess() {
        assertTrue(handler.stop(stopCommand(false)).getResult());

        when(client.findVmByName("i-2-10-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "stopped"));
        assertTrue(handler.stop(stopCommand(false)).getResult());
        verify(client, never()).shutdownVm(anyString(), anyLong(), anyInt());
    }

    @Test
    public void aStopThatFailsInProxmoxIsAFailedAnswer() {
        when(client.findVmByName("i-2-10-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "running"));
        when(client.shutdownVm(anyString(), anyLong(), anyInt())).thenThrow(new ProxmoxApiException("HTTP 500"));

        Answer answer = handler.stop(stopCommand(false));

        assertFalse(answer.getResult());
        assertEquals("HTTP 500", answer.getDetails());
    }

    @Test
    public void aVmIsRebootedWhereverItIs() {
        RebootCommand cmd = mock(RebootCommand.class);
        when(cmd.getVmName()).thenReturn("i-2-10-VM");
        when(client.findVmByName("i-2-10-VM")).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", "pve2", "running"));
        when(client.rebootVm("pve2", 9204L)).thenReturn("UPID:pve2:1:2:3:qmreboot:9204:u:");

        Answer answer = handler.reboot(cmd);

        assertTrue(answer instanceof RebootAnswer);
        assertTrue(answer.getResult());
        verify(client).waitForTask("UPID:pve2:1:2:3:qmreboot:9204:u:", 600);
    }

    @Test
    public void rebootingAVmProxmoxDoesNotKnowFails() {
        RebootCommand cmd = mock(RebootCommand.class);
        when(cmd.getVmName()).thenReturn("i-2-10-VM");

        assertFalse(handler.reboot(cmd).getResult());
    }

    // ---- delete ----

    private DeleteCommand delete(DataTO data) {
        DeleteCommand cmd = mock(DeleteCommand.class);
        when(cmd.getData()).thenReturn(data);
        return cmd;
    }

    private VolumeObjectTO volumeAt(String path) {
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getPath()).thenReturn(path);
        return volume;
    }

    @Test
    public void deletingARootVolumeDestroysItsVmWithTheDisk() {
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", "pve2", "stopped"));
        when(client.destroyVm("pve2", 9204L)).thenReturn("UPID:pve2:1:2:3:qmdestroy:9204:u:");

        Answer answer = handler.delete(delete(volumeAt("9204/vm-9204-disk-0.qcow2")));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).waitForTask("UPID:pve2:1:2:3:qmdestroy:9204:u:", 600);
        verify(client, never()).stopVm(anyString(), anyLong());
    }

    @Test
    public void aVmThatIsStillRunningIsStoppedBeforeItIsDestroyed() {
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "running"));
        when(client.stopVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmstop:9204:u:");
        when(client.destroyVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmdestroy:9204:u:");

        assertTrue(handler.delete(delete(volumeAt("9204/vm-9204-disk-0.qcow2"))).getResult());

        InOrder order = inOrder(client);
        order.verify(client).stopVm(NODE, 9204L);
        order.verify(client).destroyVm(NODE, 9204L);
    }

    @Test
    public void deletingAVolumeWhoseVmIsAlreadyGoneIsASuccess() {
        assertTrue(handler.delete(delete(volumeAt("9204/vm-9204-disk-0.qcow2"))).getResult());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    @Test
    public void deletingAVolumeThatIsNotAProxmoxDiskTouchesNothing() {
        assertTrue(handler.delete(delete(volumeAt("some-uuid"))).getResult());
        verify(client, never()).findVm(anyLong());
    }

    @Test
    public void deletingATemplateFromThePrimaryStorageRemovesItsEntry() {
        TemplateObjectTO template = mock(TemplateObjectTO.class);
        when(template.getPath()).thenReturn(TEMPLATE_VOLUME);
        when(client.findStorage(TEMPLATE_ENTRY)).thenReturn(new ProxmoxApiClient.StorageConfig(TEMPLATE_ENTRY, "nfs", null, null, null, "import", null));

        assertTrue(handler.delete(delete(template)).getResult());

        verify(client, times(1)).deleteStorage(TEMPLATE_ENTRY);
    }

    @Test
    public void onlyAStorageEntryOfAPrimaryTemplateIsEverRemoved() {
        TemplateObjectTO template = mock(TemplateObjectTO.class);
        when(template.getPath()).thenReturn(POOL + ":something");

        assertTrue(handler.delete(delete(template)).getResult());

        verify(client, never()).deleteStorage(any());
    }
}

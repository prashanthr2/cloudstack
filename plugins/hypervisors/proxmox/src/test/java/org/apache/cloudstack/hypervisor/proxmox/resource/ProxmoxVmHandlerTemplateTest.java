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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;

/** The template VMs as the handler uses them: the copy of a template to a pool, and the root volume that is a clone of it. */
public class ProxmoxVmHandlerTemplateTest {
    private static final String NODE = "pve1";
    private static final String POOL_UUID = "7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final String POOL = "cs-" + POOL_UUID;
    private static final String RELATIVE = "template/tmpl/1/3/6ddd9e96-1804-4324-b39a-a04a2c5e127e.qcow2";
    private static final String SOURCE_KEY = "3|10.0.32.4|/acs/secondary|" + RELATIVE;
    /** What core keeps for a template VM (VM id 9050 on pve2): the base disk in the pool. */
    private static final String PATH = "9050/base-9050-disk-0.qcow2";
    private static final String CLONE_UPID = "UPID:pve1:1:2:3:qmclone:9204:u:";
    private static final long GIB = 1024L * 1024 * 1024;

    private ProxmoxApiClient client;
    private ProxmoxVmHandler handler;
    private ScriptedSshLink link;
    private int pauses;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        when(client.atEndpoint(anyString())).thenReturn(client);
        link = new ScriptedSshLink();
        link.answers = command -> command.contains("qemu-img info") ? new ExecResult(0, "{\"format\": \"qcow2\", \"virtual-size\": 5242880000}", "")
                : new ExecResult(0, "", "");
        handler = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "PVEAPIToken=u@pam!t=s", millis -> pauses++, () -> 600);
        handler.setTemplates(new ProxmoxTemplates(() -> client, NODE, () -> ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "pw", null), () -> link),
                new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> 600, millis -> pauses++));
    }

    private static String templateVmName() {
        return ProxmoxTemplates.vmName(POOL_UUID, SOURCE_KEY);
    }

    private CopyCommand templateToPrimary() {
        NfsTO store = new NfsTO("nfs://10.0.32.4/acs/secondary", DataStoreRole.Image);
        store.setNfsVersion("3");
        TemplateObjectTO source = mock(TemplateObjectTO.class);
        when(source.getDataStore()).thenReturn(store);
        when(source.getPath()).thenReturn(RELATIVE);
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL_UUID);
        TemplateObjectTO dest = mock(TemplateObjectTO.class);
        when(dest.getDataStore()).thenReturn(pool);
        CopyCommand cmd = mock(CopyCommand.class);
        when(cmd.getSrcTO()).thenReturn(source);
        when(cmd.getDestTO()).thenReturn(dest);
        return cmd;
    }

    private CopyCommand rootVolumeFromTemplate(String templatePath, Long size) {
        TemplateObjectTO source = mock(TemplateObjectTO.class);
        when(source.getDataStore()).thenReturn(mock(PrimaryDataStoreTO.class));
        when(source.getPath()).thenReturn(templatePath);
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

    /** The template VM exists on pve2, and a clone gets VM id {@code vmid} with the root disk {@code disk}. */
    private void templateVmExistsAndCloneGets(long vmid, String disk) {
        templateVmExists();
        cloneGets(vmid, disk);
    }

    private void templateVmExists() {
        when(client.findVmOrTemplateById(9050L)).thenReturn(new ProxmoxApiClient.VmLocation(9050, templateVmName(), "pve2", "stopped", true));
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", POOL + ":" + PATH + ",size=5G");
        config.put("name", templateVmName());
        config.put("template", "1");
        when(client.getVmConfig("pve2", 9050L)).thenReturn(config);
    }

    private void cloneGets(long vmid, String disk) {
        when(client.nextVmId()).thenReturn(vmid);
        when(client.cloneVm(anyString(), anyLong(), eq(vmid), anyString(), anyBoolean(), anyString(), anyString()))
                .thenReturn("UPID:pve1:1:2:3:qmclone:" + vmid + ":u:");
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", disk);
        when(client.getVmConfig(NODE, vmid)).thenReturn(config);
    }

    // ---- copy to primary ----

    @Test
    public void theCopyOfATemplateToAPoolMakesTheTemplateVmAndAnswersThePathCoreKeeps() {
        when(client.nextVmId()).thenReturn(9100L);
        when(client.createVm(eq(NODE), eq(9100L), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:9100:u:");
        Map<String, String> blank = new HashMap<>();
        blank.put("scsi0", POOL + ":9100/vm-9100-disk-0.qcow2,size=5G");
        Map<String, String> base = new HashMap<>();
        base.put("scsi0", POOL + ":9100/base-9100-disk-0.qcow2,size=5G");
        when(client.getVmConfig(NODE, 9100L)).thenReturn(blank, base);

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(templateToPrimary());

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("9100/base-9100-disk-0.qcow2", ((TemplateObjectTO) answer.getNewData()).getPath());
        verify(client).convertToTemplate(NODE, 9100L);
    }

    @Test
    public void withoutTheSshLoginATemplateCannotBeCopiedAndTheReasonSaysSo() {
        ProxmoxVmHandler without = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "x", millis -> { }, () -> 600);

        Answer answer = without.copy(templateToPrimary());

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("no SSH login"));
    }

    @Test
    public void aTemplateThatCannotBeCopiedAnswersTheReasonAndNeverThrows() {
        link.answers = command -> command.contains("mount -t nfs") ? new ExecResult(32, "", "mount.nfs: Connection timed out") : new ExecResult(0, "", "");

        Answer answer = handler.copy(templateToPrimary());

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Connection timed out"));
    }

    // ---- root volume ----

    @Test
    public void theRootVolumeIsACloneOfTheTemplateVmMadeOnThisNodeOnThePoolAndNeedsNoSshLogin() {
        templateVmExistsAndCloneGets(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=5G");
        when(client.getImageInfo(NODE, POOL + ":9204/vm-9204-disk-0.qcow2")).thenReturn(new ProxmoxApiClient.ImageInfo(5242880000L, 0, "qcow2", "/x"));

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, 5242880000L));

        verify(client).cloneVm("pve2", 9050L, 9204L, "i-2-10-VM", true, NODE, POOL);
        verify(client).waitForTask(CLONE_UPID, 600);
        assertTrue(answer.getDetails(), answer.getResult());
        VolumeObjectTO created = (VolumeObjectTO) answer.getNewData();
        assertEquals("9204/vm-9204-disk-0.qcow2", created.getPath());
        assertEquals(Long.valueOf(5242880000L), created.getSize());
        assertEquals(Storage.ImageFormat.QCOW2, created.getFormat());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
        assertTrue("a deploy needs neither an SSH login nor the secondary store: " + link.commands, link.commands.isEmpty());
    }

    @Test
    public void theDiskIsGrownFromTheSizeOfTheImageNotFromTheRoundedSizeInTheConfiguration() {
        // the clone's configuration says 5G (the blank disk of the template VM, rounded up) while the image is 4.88 GiB: the volume of 5 GiB must be grown
        templateVmExistsAndCloneGets(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=5G");
        when(client.getImageInfo(NODE, POOL + ":9204/vm-9204-disk-0.qcow2")).thenReturn(new ProxmoxApiClient.ImageInfo(5242880000L, 0, "qcow2", "/x"));

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, 5L * GIB));

        verify(client).resizeDisk(NODE, 9204L, "scsi0", Long.toString(5L * GIB));
        assertEquals(Long.valueOf(5L * GIB), ((VolumeObjectTO) answer.getNewData()).getSize());
    }

    @Test
    public void aRootDiskBiggerThanTheTemplateIsGrown() {
        templateVmExistsAndCloneGets(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=5G");

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, 20L * GIB));

        verify(client).resizeDisk(NODE, 9204L, "scsi0", Long.toString(20L * GIB));
        assertEquals(Long.valueOf(20L * GIB), ((VolumeObjectTO) answer.getNewData()).getSize());
    }

    @Test
    public void aVmIdTakenByAnotherCallerIsReplacedAndTheOtherVmIsLeftAlone() {
        templateVmExists();
        when(client.nextVmId()).thenReturn(9200L, 9201L);
        when(client.cloneVm(anyString(), anyLong(), eq(9200L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn("UPID:pve1:1:2:3:qmclone:9200:u:");
        when(client.cloneVm(anyString(), anyLong(), eq(9201L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn("UPID:pve1:1:2:3:qmclone:9201:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmclone failed: VM 9200 already exists on node 'pve2'")).when(client)
                .waitForTask("UPID:pve1:1:2:3:qmclone:9200:u:", 600);
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", POOL + ":9201/vm-9201-disk-0.qcow2,size=5G");
        when(client.getVmConfig(NODE, 9201L)).thenReturn(config);

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, null));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("9201/vm-9201-disk-0.qcow2", ((VolumeObjectTO) answer.getNewData()).getPath());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    @Test
    public void aCloneThatCouldNotGetProxmoxsLockOnThePoolIsTriedAgainWithANewId() {
        templateVmExists();
        when(client.nextVmId()).thenReturn(9200L, 9201L);
        when(client.cloneVm(anyString(), anyLong(), eq(9200L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn("UPID:pve1:1:2:3:qmclone:9200:u:");
        when(client.cloneVm(anyString(), anyLong(), eq(9201L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn("UPID:pve1:1:2:3:qmclone:9201:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmclone failed: clone failed: cfs-lock 'storage-" + POOL + "' error: got lock request timeout")).when(client)
                .waitForTask("UPID:pve1:1:2:3:qmclone:9200:u:", 600);
        when(client.findVm(9200L)).thenReturn(new ProxmoxApiClient.VmLocation(9200, "i-2-10-VM", NODE, "stopped"));
        when(client.destroyVm(NODE, 9200L)).thenReturn("UPID:pve1:1:2:3:qmdestroy:9200:u:");
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", POOL + ":9201/vm-9201-disk-0.qcow2,size=5G");
        when(client.getVmConfig(NODE, 9201L)).thenReturn(config);

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, null));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("9201/vm-9201-disk-0.qcow2", ((VolumeObjectTO) answer.getNewData()).getPath());
        verify(client).destroyVm(NODE, 9200L);
        assertEquals(1, pauses);
    }

    @Test
    public void aCloneThatTimedOutInsideProxmoxsLockedCommandIsTriedAgainToo() {
        templateVmExists();
        when(client.nextVmId()).thenReturn(9200L, 9201L);
        when(client.cloneVm(anyString(), anyLong(), eq(9200L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn("UPID:pve1:1:2:3:qmclone:9200:u:");
        when(client.cloneVm(anyString(), anyLong(), eq(9201L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn("UPID:pve1:1:2:3:qmclone:9201:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmclone failed: clone failed: unable to create image: 'storage-" + POOL + "'-locked command timed out - aborting")).when(client)
                .waitForTask("UPID:pve1:1:2:3:qmclone:9200:u:", 600);
        when(client.findVm(9200L)).thenReturn(new ProxmoxApiClient.VmLocation(9200, "i-2-10-VM", NODE, "stopped"));
        when(client.destroyVm(NODE, 9200L)).thenReturn("UPID:pve1:1:2:3:qmdestroy:9200:u:");
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", POOL + ":9201/vm-9201-disk-0.qcow2,size=5G");
        when(client.getVmConfig(NODE, 9201L)).thenReturn(config);

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, null));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).destroyVm(NODE, 9200L);
    }

    @Test
    public void aFailedCloneRemovesWhatItLeftBehindAndAnswersTheReason() {
        templateVmExists();
        when(client.nextVmId()).thenReturn(9204L);
        when(client.cloneVm(anyString(), anyLong(), eq(9204L), anyString(), anyBoolean(), anyString(), anyString())).thenReturn(CLONE_UPID);
        doThrow(new ProxmoxApiException("Proxmox task qmclone failed: unable to create image: No space left on device")).when(client).waitForTask(CLONE_UPID, 600);
        when(client.findVm(9204L)).thenReturn(new ProxmoxApiClient.VmLocation(9204, "i-2-10-VM", NODE, "stopped"));
        when(client.destroyVm(NODE, 9204L)).thenReturn("UPID:pve1:1:2:3:qmdestroy:9204:u:");

        Answer answer = handler.copy(rootVolumeFromTemplate(PATH, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("No space left on device"));
        verify(client).destroyVm(NODE, 9204L);
    }

    @Test
    public void aTemplateVmThatWasRemovedFailsTheDeployWithWhatToDoAndCreatesNoVm() {
        Answer answer = handler.copy(rootVolumeFromTemplate(PATH, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("removed outside CloudStack"));
        verify(client, never()).cloneVm(anyString(), anyLong(), anyLong(), anyString(), anyBoolean(), anyString(), anyString());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
        assertTrue("a deploy needs no SSH login, and makes nothing from the store: " + link.commands, link.commands.isEmpty());
    }

    @Test
    public void withoutTheSshLoginATemplateOnAPoolCannotBeUsedAndTheReasonSaysSo() {
        ProxmoxVmHandler without = new ProxmoxVmHandler(() -> client, NODE, "10.0.32.198", 8006, () -> "x", millis -> { }, () -> 600);

        Answer answer = without.copy(rootVolumeFromTemplate(PATH, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("no SSH login"));
    }

    @Test
    public void aTemplatePathThatIsNotOneThePluginMadeIsRefusedWithAnAnswer() {
        Answer answer = handler.copy(rootVolumeFromTemplate("secondary:3|10.0.32.4|/acs|a/b.qcow2", null));

        assertFalse(answer.getResult());
        verify(client, never()).cloneVm(anyString(), anyLong(), anyLong(), anyString(), anyBoolean(), anyString(), anyString());
    }
    // ---- linked clones (design-phase12-linked-clones.md) ----

    private static final String LINKED_DISK = POOL + ":9050/base-9050-disk-0.qcow2/9204/vm-9204-disk-0.qcow2,size=5G";

    @Test
    public void withTheSettingOffTheRootVolumeIsALinkedCloneAndItsPathIsTheShortForm() {
        handler.setFullClone(poolId -> false);
        templateVmExistsAndCloneGets(9204, LINKED_DISK);
        when(client.getImageInfo(NODE, POOL + ":9050/base-9050-disk-0.qcow2/9204/vm-9204-disk-0.qcow2")).thenReturn(new ProxmoxApiClient.ImageInfo(5242880000L, 0, "qcow2", "/x"));

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, 5242880000L));

        verify(client).cloneVm("pve2", 9050L, 9204L, "i-2-10-VM", false, NODE, POOL);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("9204/vm-9204-disk-0.qcow2", ((VolumeObjectTO) answer.getNewData()).getPath());
    }

    @Test
    public void theSettingIsReadForThePoolOfTheVolume() {
        long[] poolIds = new long[1];
        handler.setFullClone(poolId -> {
            poolIds[0] = poolId;
            return true;
        });
        templateVmExistsAndCloneGets(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=5G");
        CopyCommand cmd = rootVolumeFromTemplate(PATH, 5242880000L);
        PrimaryDataStoreTO pool = (PrimaryDataStoreTO) ((VolumeObjectTO) cmd.getDestTO()).getDataStore();
        when(pool.getId()).thenReturn(42L);

        handler.copy(cmd);

        assertEquals(42L, poolIds[0]);
        verify(client).cloneVm("pve2", 9050L, 9204L, "i-2-10-VM", true, NODE, POOL);
    }

    @Test
    public void aSparseOrFatOfferingIsAFullCopyWhateverTheSetting() {
        handler.setFullClone(poolId -> false);
        templateVmExistsAndCloneGets(9204, POOL + ":9204/vm-9204-disk-0.qcow2,size=5G");
        CopyCommand cmd = rootVolumeFromTemplate(PATH, 5242880000L);
        when(((VolumeObjectTO) cmd.getDestTO()).getProvisioningType()).thenReturn(Storage.ProvisioningType.FAT);

        handler.copy(cmd);

        verify(client).cloneVm("pve2", 9050L, 9204L, "i-2-10-VM", true, NODE, POOL);
    }

    @Test
    public void aLinkedCloneBiggerThanTheTemplateIsGrownAndKeepsTheShortPath() {
        handler.setFullClone(poolId -> false);
        templateVmExistsAndCloneGets(9204, LINKED_DISK);

        CopyCmdAnswer answer = (CopyCmdAnswer) handler.copy(rootVolumeFromTemplate(PATH, 20L * GIB));

        verify(client).resizeDisk(NODE, 9204L, "scsi0", Long.toString(20L * GIB));
        assertEquals("9204/vm-9204-disk-0.qcow2", ((VolumeObjectTO) answer.getNewData()).getPath());
        assertEquals(Long.valueOf(20L * GIB), ((VolumeObjectTO) answer.getNewData()).getSize());
    }
}

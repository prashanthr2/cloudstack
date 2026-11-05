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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.DestroyCommand;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.VolumeTO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;

public class ProxmoxTemplatesTest {
    private static final String NODE = "pve1";
    private static final String POOL_UUID = "7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final String POOL = "cs-" + POOL_UUID;
    private static final String URL = "nfs://10.0.32.4/acs/primary/lab/secondary";
    private static final String RELATIVE = "template/tmpl/1/3/6ddd9e96-1804-4324-b39a-a04a2c5e127e.qcow2";
    private static final String PATH = "secondary:3|10.0.32.4|/acs/primary/lab/secondary|" + RELATIVE;
    private static final String UPID = "UPID:pve1:1:2:3:qmcreate:9100:u:";
    private static final String UPID_TEMPLATE = "UPID:pve1:1:2:3:qmtemplate:9100:u:";
    private static final long GIB = 1024L * 1024 * 1024;

    private ProxmoxApiClient client;
    private ScriptedSshLink link;
    private ProxmoxTemplates templates;
    private final Map<String, ExecResult> replies = new LinkedHashMap<>();
    private String info = "{\"format\": \"qcow2\", \"virtual-size\": 5242880000}";
    private int pauses;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        link = new ScriptedSshLink();
        link.answers = command -> {
            for (Map.Entry<String, ExecResult> reply : replies.entrySet()) {
                if (command.contains(reply.getKey())) {
                    return reply.getValue();
                }
            }
            if (command.contains("qemu-img info")) {
                return new ExecResult(0, info, "");
            }
            return new ExecResult(0, "", "");
        };
        ProxmoxSecondaryMounts mounts = new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS);
        templates = new ProxmoxTemplates(() -> client, NODE, () -> ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "pw", null), () -> link), mounts,
                () -> 600, millis -> pauses++);
        when(client.nextVmId()).thenReturn(9100L);
        when(client.createVm(eq(NODE), anyLong(), anyMap())).thenReturn(UPID);
        when(client.convertToTemplate(eq(NODE), anyLong())).thenReturn(UPID_TEMPLATE);
        when(client.destroyVm(anyString(), anyLong())).thenReturn("UPID:pve1:1:2:3:qmdestroy:9100:u:");
        vmGetsDisk(9100L);
    }

    private void vmGetsDisk(long vmid) {
        Map<String, String> config = new HashMap<>();
        config.put("scsi0", POOL + ":" + vmid + "/vm-" + vmid + "-disk-0.qcow2,size=5G");
        when(client.getVmConfig(NODE, vmid)).thenReturn(config);
    }

    private static NfsTO store(String version) {
        NfsTO store = new NfsTO(URL, DataStoreRole.Image);
        store.setNfsVersion(version);
        return store;
    }

    private static TemplateObjectTO source(String path) {
        TemplateObjectTO template = mock(TemplateObjectTO.class);
        when(template.getDataStore()).thenReturn(store("3"));
        when(template.getPath()).thenReturn(path);
        when(template.getPhysicalSize()).thenReturn(1786445824L);
        return template;
    }

    private static TemplateObjectTO destination(String poolUuid) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(poolUuid);
        TemplateObjectTO template = mock(TemplateObjectTO.class);
        when(template.getDataStore()).thenReturn(pool);
        return template;
    }

    private CopyCmdAnswer copy(String relativePath) {
        return templates.copyToPrimary(source(relativePath), destination(POOL_UUID));
    }

    private boolean ran(String text) {
        return !link.commandsWith(text).isEmpty();
    }

    private static String name() {
        return ProxmoxTemplates.vmName(POOL_UUID, PATH);
    }

    // ---- the path and the name ----

    @Test
    public void thePathCarriesTheVersionTheStoreAndTheFile() {
        assertEquals(PATH, ProxmoxTemplates.path(store("3"), RELATIVE));
        assertEquals("secondary:|10.0.32.4|/acs/primary/lab/secondary|" + RELATIVE, ProxmoxTemplates.path(store(null), RELATIVE));
        assertTrue(ProxmoxTemplates.isTemplatePath(PATH));
        assertFalse(ProxmoxTemplates.isTemplatePath("cst-3f2a8c1e-0000-3000-8000-000000000000:4/f.qcow2"));
        assertFalse(ProxmoxTemplates.isTemplatePath(null));
    }

    @Test
    public void aPathIsParsedBackIntoTheStoreAndTheFile() {
        ProxmoxTemplates.Source source = ProxmoxTemplates.parse(PATH);

        assertEquals(RELATIVE, source.getRelative());
        assertEquals("nfs://10.0.32.4/acs/primary/lab/secondary", source.store().getUrl());
        assertEquals("3", source.store().getNfsVersion());
        assertEquals(null, ProxmoxTemplates.parse("secondary:|10.0.32.4|/acs/x|a/b.qcow2").store().getNfsVersion());
    }

    @Test
    public void aPathThatIsNotOneThePluginMadeIsRefusedWhateverIsWrongWithIt() {
        for (String bad : new String[] {"cst-1:4/f.qcow2", "secondary:", "secondary:3|10.0.32.4|/acs|a|b", "secondary:3|10.0.32.4;id|/acs|a/b.qcow2",
                "secondary:3;id|10.0.32.4|/acs|a/b.qcow2", "secondary:v3|10.0.32.4|/acs|a/b.qcow2", "secondary:3|10.0.32.4|/acs/../x|a/b.qcow2",
                "secondary:3|10.0.32.4|/acs|../etc/x", "secondary:3|10.0.32.4|/acs|a b.qcow2", "secondary:3|10.0.32.4|/acs|", "secondary:3|10.0.32.4||a/b.qcow2", null}) {
            try {
                ProxmoxTemplates.parse(bad);
                fail(bad + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(bad + ": " + e.getMessage(), e.getMessage().contains("template") || e.getMessage().contains("secondary store")
                        || e.getMessage().contains("NFS version"));
            }
        }
    }

    @Test
    public void aPathThatDoesNotFitTheColumnIsRefusedWithTheLimit() {
        String longFile = "template/tmpl/1/3/" + "a".repeat(240) + ".qcow2";

        try {
            ProxmoxTemplates.path(store("3"), longFile);
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("255"));
        }
    }

    @Test
    public void theNameOfATemplateVmIsTheSameForTheCopyTheDeployAndTheEvictionAndDiffersPerPoolAndPath() {
        String name = ProxmoxTemplates.vmName(POOL_UUID, PATH);

        assertEquals(name, ProxmoxTemplates.vmName(POOL_UUID, PATH));
        assertTrue(name, name.startsWith("cs-tmpl-"));
        assertTrue(name, name.length() <= 63);
        assertNotEquals(name, ProxmoxTemplates.vmName("047ccc61-dc29-3d58-9c73-dd1ab2c100cf", PATH));
        assertNotEquals(name, ProxmoxTemplates.vmName(POOL_UUID, PATH.replace("/3/", "/4/")));
    }

    @Test
    public void aPoolUuidThatIsNotOneThePluginUsesIsRefused() {
        for (String bad : new String[] {"abc; reboot", "", null, "a b"}) {
            try {
                ProxmoxTemplates.vmName(bad, PATH);
                fail(bad + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("primary storage uuid"));
            }
        }
    }

    // ---- copy to primary ----

    @Test
    public void aNewTemplateBecomesATemplateVmWithTheConvertedImageAsItsDisk() {
        CopyCmdAnswer answer = copy(RELATIVE);

        assertTrue(answer.getDetails(), answer.getResult());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(client).createVm(eq(NODE), eq(9100L), params.capture());
        assertEquals(name(), params.getValue().get("name"));
        assertEquals("256", params.getValue().get("memory"));
        // 5,242,880,000 bytes are 4.88 GiB, which makes a blank disk of 5 GiB
        assertEquals(POOL + ":5,format=qcow2", params.getValue().get("scsi0"));
        String mountCommand = link.commandsWith("mount -t nfs").get(0);
        assertTrue(mountCommand, mountCommand.contains("-o 'ro,vers=3,soft,timeo=30,retrans=2,nosharecache' '10.0.32.4:/acs/primary/lab/secondary'"));
        String directory = mountCommand.substring(mountCommand.indexOf('\'') + 1, mountCommand.indexOf('\'', mountCommand.indexOf('\'') + 1));
        String file = "/mnt/" + POOL_UUID + "/images/9100/vm-9100-disk-0.qcow2";
        String convert = "qemu-img convert -O qcow2 '" + directory + "/" + RELATIVE + "' '" + file + ".tmp'";
        String move = "mv -f '" + file + ".tmp' '" + file + "'";
        assertTrue(link.commands.toString(), link.commands.contains(convert));
        assertTrue(link.commands.toString(), link.commands.contains(move));
        assertTrue(link.commands.indexOf(convert) < link.commands.indexOf(move));
        verify(client).convertToTemplate(NODE, 9100L);
        verify(client).waitForTask(UPID_TEMPLATE, 600);
        // the mount is released after all that
        String release = link.commandsWith("umount '" + directory + "'").get(0);
        assertTrue(link.commands.indexOf(move) < link.commands.indexOf(release));
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    @Test
    public void theAnswerIsThePathCoreKeepsAndTheSizeOfTheImage() {
        CopyCmdAnswer answer = copy(RELATIVE);

        TemplateObjectTO onPrimary = (TemplateObjectTO) answer.getNewData();
        assertEquals(PATH, onPrimary.getPath());
        assertEquals(Long.valueOf(5242880000L), onPrimary.getSize());
        assertEquals(Long.valueOf(1786445824L), onPrimary.getPhysicalSize());
        assertEquals(Storage.ImageFormat.QCOW2, onPrimary.getFormat());
    }

    @Test
    public void aTemplateThatIsOnThePoolAlreadyIsNotMadeAgain() {
        when(client.findVmOrTemplateByName(name())).thenReturn(new ProxmoxApiClient.VmLocation(9050, name(), "pve2", "stopped", true));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
        verify(client, never()).convertToTemplate(anyString(), anyLong());
        assertFalse(ran("qemu-img convert"));
        assertEquals(PATH, ((TemplateObjectTO) answer.getNewData()).getPath());
        // the image is still checked, so a template that was changed on the store is noticed
        assertTrue(ran("qemu-img info"));
    }

    @Test
    public void aVmThatAnInterruptedCopyLeftIsRemovedBeforeTheTemplateIsMadeAgain() {
        when(client.findVmOrTemplateByName(name())).thenReturn(new ProxmoxApiClient.VmLocation(9050, name(), "pve2", "stopped", false));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).destroyVm("pve2", 9050L);
        verify(client).createVm(eq(NODE), eq(9100L), anyMap());
        verify(client).convertToTemplate(NODE, 9100L);
    }

    @Test
    public void aFailedConversionRemovesTheVmAndTheTemporaryFileAndMakesNoTemplate() {
        replies.put("qemu-img convert", new ExecResult(1, "", "No space left on device"));
        when(client.findVmOrTemplateById(9100L)).thenReturn(new ProxmoxApiClient.VmLocation(9100, name(), NODE, "stopped"));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("No space left on device"));
        verify(client).destroyVm(NODE, 9100L);
        verify(client, never()).convertToTemplate(anyString(), anyLong());
        assertTrue(ran("rm -f '/mnt/" + POOL_UUID + "/images/9100/vm-9100-disk-0.qcow2.tmp'"));
        assertFalse(ran("mv -f"));
        assertEquals(1, link.commandsWith("rmdir '/mnt/css-tmp-").size());
    }

    @Test
    public void aFailedTemplateCallRemovesTheVm() {
        doThrow(new ProxmoxApiException("Proxmox task qmtemplate failed: no permission")).when(client).waitForTask(UPID_TEMPLATE, 600);
        when(client.findVmOrTemplateById(9100L)).thenReturn(new ProxmoxApiClient.VmLocation(9100, name(), NODE, "stopped"));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("no permission"));
        verify(client).destroyVm(NODE, 9100L);
    }

    @Test
    public void aTemplateWithABackingFileIsRefusedBeforeAnyVmIsMade() {
        info = "{\"format\": \"qcow2\", \"virtual-size\": 1073741824, \"backing-filename\": \"/etc/hostname\", \"full-backing-filename\": \"/etc/hostname\"}";

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("backing file"));
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void aTemplateFileThatIsNotOnTheStoreIsReportedAndNothingIsMade() {
        replies.put("test -f '/mnt/css-tmp-", new ExecResult(1, "", ""));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("does not exist on the secondary store"));
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aSecondaryStoreThatCannotBeMountedIsReportedWithTheReasonOfTheMount() {
        replies.put("mount -t nfs", new ExecResult(32, "", "mount.nfs: access denied by server while mounting"));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("access denied by server"));
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aVmIdAnotherCallerWasGivenIsReplacedAndTheOtherVmIsLeftAlone() {
        when(client.nextVmId()).thenReturn(9100L, 9101L);
        when(client.createVm(eq(NODE), eq(9101L), anyMap())).thenReturn("UPID:pve1:1:2:3:qmcreate:9101:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmcreate failed: unable to create VM 9100 - VM 9100 already exists on node 'pve2'")).when(client)
                .waitForTask(UPID, 600);
        vmGetsDisk(9101L);

        CopyCmdAnswer answer = copy(RELATIVE);

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).convertToTemplate(NODE, 9101L);
        verify(client, never()).destroyVm(anyString(), anyLong());
        assertEquals(1, pauses);
    }

    @Test
    public void aVmIdThatIsKeptForDataVolumeHoldersIsNeverUsed() {
        when(client.nextVmId()).thenReturn(900000005L);

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("900000001"));
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aVmWithoutADiskThePluginCanFillIsRemoved() {
        when(client.getVmConfig(NODE, 9100L)).thenReturn(new HashMap<>());
        when(client.findVmOrTemplateById(9100L)).thenReturn(new ProxmoxApiClient.VmLocation(9100, name(), NODE, "stopped"));

        CopyCmdAnswer answer = copy(RELATIVE);

        assertFalse(answer.getResult());
        verify(client).destroyVm(NODE, 9100L);
        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void aTemplatePathThatLeavesTheStoreOrNamesNoPoolIsRefusedBeforeAnyCommandRuns() {
        for (String bad : new String[] {"../../etc/x.qcow2", "a b.qcow2", "x;id", "", null}) {
            CopyCmdAnswer answer = copy(bad);

            assertFalse(String.valueOf(bad), answer.getResult());
        }
        assertFalse(templates.copyToPrimary(source(RELATIVE), destination("pool; reboot")).getResult());
        assertTrue(link.commands.isEmpty());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    // ---- a template VM that is needed at a deploy ----

    @Test
    public void aTemplateVmThatIsThereIsFoundWithoutAnySshLoginOrSecondaryStore() {
        ProxmoxApiClient.VmLocation vm = new ProxmoxApiClient.VmLocation(9050, name(), "pve2", "stopped", true);
        when(client.findVmOrTemplateByName(name())).thenReturn(vm);

        assertEquals(9050L, templates.ensureTemplateVm(POOL_UUID, PATH).getVmid());
        assertTrue(link.commands.isEmpty());
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aTemplateVmThatWasLostIsMadeAgainFromTheStoreNamedInThePath() {
        ProxmoxApiClient.VmLocation made = templates.ensureTemplateVm(POOL_UUID, PATH);

        assertEquals(9100L, made.getVmid());
        assertEquals(NODE, made.getNode());
        assertTrue(made.isTemplate());
        String mountCommand = link.commandsWith("mount -t nfs").get(0);
        assertTrue(mountCommand, mountCommand.contains("-o 'ro,vers=3,soft,timeo=30,retrans=2,nosharecache' '10.0.32.4:/acs/primary/lab/secondary'"));
        verify(client).convertToTemplate(NODE, 9100L);
    }

    @Test
    public void aTemplateVmThatCannotBeMadeAgainBecauseTheFileIsGoneFailsTheDeploy() {
        replies.put("test -f '/mnt/css-tmp-", new ExecResult(1, "", ""));

        try {
            templates.ensureTemplateVm(POOL_UUID, PATH);
            fail("no exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("does not exist on the secondary store"));
        }
        verify(client, never()).createVm(anyString(), anyLong(), anyMap());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aPathThatIsNotATemplatePathIsRefusedAtADeploy() {
        templates.ensureTemplateVm(POOL_UUID, "cst-3f2a8c1e-0000-3000-8000-000000000000:4/f.qcow2");
    }

    // ---- eviction by core (DestroyCommand) ----

    private static DestroyCommand destroyCommand(String poolUuid, String path) {
        VolumeTO volume = mock(VolumeTO.class);
        when(volume.getPath()).thenReturn(path);
        when(volume.getPoolUuid()).thenReturn(poolUuid);
        DestroyCommand cmd = mock(DestroyCommand.class);
        when(cmd.getVolume()).thenReturn(volume);
        return cmd;
    }

    @Test
    public void theEvictionOfATemplateDestroysItsTemplateVm() {
        when(client.findVmOrTemplateByName(name())).thenReturn(new ProxmoxApiClient.VmLocation(9050, name(), "pve2", "stopped", true));
        when(client.destroyVm("pve2", 9050L)).thenReturn("UPID:pve2:1:2:3:qmdestroy:9050:u:");

        Answer answer = templates.destroy(destroyCommand(POOL_UUID, PATH));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).destroyVm("pve2", 9050L);
        verify(client).waitForTask("UPID:pve2:1:2:3:qmdestroy:9050:u:", 600);
    }

    @Test
    public void theEvictionOfATemplateWhoseVmIsGoneIsASuccess() {
        Answer answer = templates.destroy(destroyCommand(POOL_UUID, PATH));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    @Test
    public void proxmoxRefusingToRemoveABaseThatCloneUseIsAFailureWithItsTextSoCoreKeepsTheRow() {
        when(client.findVmOrTemplateByName(name())).thenReturn(new ProxmoxApiClient.VmLocation(9050, name(), "pve2", "stopped", true));
        when(client.destroyVm("pve2", 9050L)).thenReturn("UPID:pve2:1:2:3:qmdestroy:9050:u:");
        doThrow(new ProxmoxApiException("Proxmox task qmdestroy failed: base volume 'base-9050-disk-0.qcow2' is still in use by linked clones")).when(client)
                .waitForTask("UPID:pve2:1:2:3:qmdestroy:9050:u:", 600);

        Answer answer = templates.destroy(destroyCommand(POOL_UUID, PATH));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("still in use by linked clones"));
    }

    @Test
    public void theEvictionOfATemplateFromBeforeTemplateVmsIsASuccessThatDoesNothing() {
        Answer answer = templates.destroy(destroyCommand(POOL_UUID, "cst-3f2a8c1e-0000-3000-8000-000000000000:4/f.qcow2"));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client, never()).findVmOrTemplateByName(anyString());
    }

    @Test
    public void aDestroyOfSomethingElseIsNotSupported() {
        Answer answer = templates.destroy(destroyCommand(POOL_UUID, "9204/vm-9204-disk-0.qcow2"));

        assertFalse(answer.getResult());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }

    @Test
    public void aDestroyForAPoolThePluginDoesNotKnowIsAFailureAndRemovesNothing() {
        Answer answer = templates.destroy(destroyCommand("pool; reboot", PATH));

        assertFalse(answer.getResult());
        verify(client, never()).destroyVm(anyString(), anyLong());
    }
}

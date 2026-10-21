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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.InOrder;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.network.Networks.TrafficType;
import com.cloud.utils.ExecutionResult;
import com.cloud.vm.VirtualMachine;

public class ProxmoxSystemVmsTest {
    private static final String BOOT_ARGS = "template=domP type=secstorage name=s-1-VM eth0ip=169.254.3.7";
    private static final String IP = "169.254.3.7";
    private static final String PATCH_PATH = "/var/lib/cloudstack-patch";
    private static final String STORAGE = ProxmoxStorageNames.patchStorageId(PATCH_PATH);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ProxmoxApiClient client;
    private ProxmoxRouterDeployer deployer;
    private VirtualRoutingResource routing;
    private ProxmoxPatchFiles files;
    private ProxmoxSystemVms systemVms;
    private String agentSha;
    private String scriptsSha;
    private final AtomicLong now = new AtomicLong();

    @Before
    public void setUp() throws Exception {
        Path dir = folder.newFolder().toPath();
        Files.write(dir.resolve(ProxmoxPatchFiles.AGENT_ZIP), "agent-bytes-0123456789".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(ProxmoxPatchFiles.SCRIPTS), "scripts-bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(ProxmoxPatchFiles.PATCH_SCRIPT), "#!/bin/bash\n".getBytes(StandardCharsets.UTF_8));
        files = new ProxmoxPatchFiles(dir);
        agentSha = files.sha512(ProxmoxPatchFiles.AGENT_ZIP);
        scriptsSha = files.sha512(ProxmoxPatchFiles.SCRIPTS);
        client = mock(ProxmoxApiClient.class);
        deployer = mock(ProxmoxRouterDeployer.class);
        routing = mock(VirtualRoutingResource.class);
        systemVms = new ProxmoxSystemVms(deployer, routing, () -> files, () -> PATCH_PATH, () -> 600, millis -> now.addAndGet(millis), now::get);
        when(client.agentPing("pve1", 9300L)).thenReturn(true);
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any())).thenReturn(new ExecutionResult(true, ""));
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.STATE_CHECK), any(), any())).thenReturn(new ExecutionResult(true, "SIG=\nAGENT=no\n"));
        when(deployer.writeBytes(eq(IP), anyString(), any())).thenReturn(new ExecutionResult(true, "ok"));
        when(routing.isSystemVMSetup(anyString(), eq(IP))).thenReturn(true);
    }

    private static VirtualMachineTO vm(VirtualMachine.Type type, String bootArgs, String controlIp) {
        NicTO control = mock(NicTO.class);
        when(control.getType()).thenReturn(TrafficType.Control);
        when(control.getIp()).thenReturn(controlIp);
        NicTO management = mock(NicTO.class);
        when(management.getType()).thenReturn(TrafficType.Management);
        when(management.getIp()).thenReturn("10.0.35.100");
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn("s-1-VM");
        when(vm.getType()).thenReturn(type);
        when(vm.getBootArgs()).thenReturn(bootArgs);
        when(vm.getNics()).thenReturn(new NicTO[] {management, control});
        return vm;
    }

    private static VirtualMachineTO router() {
        return vm(VirtualMachine.Type.DomainRouter, BOOT_ARGS, IP);
    }

    private static VirtualMachineTO ssvm() {
        return vm(VirtualMachine.Type.SecondaryStorageVm, BOOT_ARGS, IP);
    }

    @Test
    public void theControlAddressIsThatOfTheControlNicNotTheFirstNic() {
        assertEquals(IP, ProxmoxSystemVms.controlIp(router()));
    }

    // ---- after the start

    @Test
    public void aRouterGetsItsBootArgumentsThenItsScriptsAndIsWaitedForUntilItIsSetUp() throws Exception {
        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        verify(deployer).register(IP, "s-1-VM");
        InOrder order = inOrder(client, deployer, routing);
        order.verify(client).agentPing("pve1", 9300L);
        order.verify(client).agentFileWrite("pve1", 9300L, ProxmoxSystemVms.CMDLINE, BOOT_ARGS);
        order.verify(deployer).run(eq(IP), eq(ProxmoxSystemVms.STATE_CHECK), any(), any());
        order.verify(deployer).writeBytes(eq(IP), eq("/var/cache/cloud/cloud-scripts.tgz"), any());
        order.verify(deployer).writeBytes(eq(IP), eq("/var/cache/cloud/patch-sysvms.sh"), any());
        order.verify(deployer).run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any());
        order.verify(routing).isSystemVMSetup("s-1-VM", IP);
        // a router has no agent to carry in: no CD is touched
        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
    }

    @Test
    public void aRouterWithTheCurrentScriptsStillGetsThemBecauseItRemovesItsAgentDirectoryAtEveryBoot() {
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.STATE_CHECK), any(), any())).thenReturn(new ExecutionResult(true, "SIG=" + scriptsSha + "\nAGENT=no\n"));

        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        verify(deployer).writeBytes(eq(IP), eq("/var/cache/cloud/cloud-scripts.tgz"), any());
    }

    @Test
    public void aNewSecondaryStorageVmGetsTheAgentFromThePayloadCdThenTheScriptsLast() {
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("head -c"), any(), any())).thenReturn(new ExecutionResult(true, agentSha + "\n"));
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.startsWith("mv -f"), any(), any())).thenReturn(new ExecutionResult(true, ""));

        assertNull(systemVms.afterStart(client, ssvm(), "pve1", 9300L));

        String volume = STORAGE + ":iso/" + ProxmoxStorageNames.patchFileName(agentSha);
        InOrder order = inOrder(client, deployer);
        order.verify(client).agentFileWrite("pve1", 9300L, ProxmoxSystemVms.CMDLINE, BOOT_ARGS);
        order.verify(client).updateVmConfig("pve1", 9300L, Collections.singletonMap("ide2", volume + ",media=cdrom"));
        order.verify(deployer).run(eq(IP), org.mockito.ArgumentMatchers.contains("head -c 22 /dev/sr0 > /var/cache/cloud/agent.zip.part"), any(), any());
        order.verify(deployer).run(eq(IP), eq("mv -f /var/cache/cloud/agent.zip.part /var/cache/cloud/agent.zip"), any(), any());
        order.verify(client).updateVmConfig("pve1", 9300L, Collections.singletonMap("ide2", "none,media=cdrom"));
        order.verify(deployer).writeBytes(eq(IP), eq("/var/cache/cloud/cloud-scripts.tgz"), any());
        order.verify(deployer).writeBytes(eq(IP), eq("/var/cache/cloud/patch-sysvms.sh"), any());
    }

    @Test
    public void theCopyWaitsForTheGuestToSeeAllWholeSectorsOfThePaddedFile() {
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("head -c"), any(), any())).thenReturn(new ExecutionResult(true, agentSha));
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.startsWith("mv -f"), any(), any())).thenReturn(new ExecutionResult(true, ""));

        systemVms.afterStart(client, ssvm(), "pve1", 9300L);

        // 22 bytes pad to one 2048 byte sector, which is 4 sectors of 512 bytes in /sys/block/sr0/size
        verify(deployer).run(eq(IP), org.mockito.ArgumentMatchers.contains("-ge 4 ]"), any(), any());
    }

    @Test
    public void aRestartedSecondaryStorageVmThatHasTheAgentAndTheCurrentScriptsNeedsNothing() {
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.STATE_CHECK), any(), any())).thenReturn(new ExecutionResult(true, "SIG=" + scriptsSha + "\nAGENT=yes\n"));

        assertNull(systemVms.afterStart(client, ssvm(), "pve1", 9300L));

        verify(client, never()).updateVmConfig(anyString(), anyLong(), anyMap());
        verify(deployer, never()).writeBytes(anyString(), anyString(), any());
    }

    @Test
    public void aSecondaryStorageVmWithOldScriptsIsPatchedAgainEvenIfItHasAnAgent() {
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.STATE_CHECK), any(), any())).thenReturn(new ExecutionResult(true, "SIG=oldsignature\nAGENT=yes\n"));
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("head -c"), any(), any())).thenReturn(new ExecutionResult(true, agentSha));
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.startsWith("mv -f"), any(), any())).thenReturn(new ExecutionResult(true, ""));

        assertNull(systemVms.afterStart(client, ssvm(), "pve1", 9300L));

        verify(deployer).writeBytes(eq(IP), eq("/var/cache/cloud/cloud-scripts.tgz"), any());
    }

    @Test
    public void aDamagedCopyOfTheAgentFailsTheStartAndTheCdIsEjectedAnyway() {
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("head -c"), any(), any())).thenReturn(new ExecutionResult(true, "deadbeef"));

        String problem = systemVms.afterStart(client, ssvm(), "pve1", 9300L);

        assertTrue(problem, problem.contains("damaged") && problem.contains("agent.zip"));
        verify(client).updateVmConfig("pve1", 9300L, Collections.singletonMap("ide2", "none,media=cdrom"));
        verify(deployer, never()).writeBytes(eq(IP), eq("/var/cache/cloud/cloud-scripts.tgz"), any());
    }

    @Test
    public void aFailedScriptsFileFailsTheStartWithItsName() {
        when(deployer.writeBytes(eq(IP), eq("/var/cache/cloud/cloud-scripts.tgz"), any())).thenReturn(new ExecutionResult(false, "no space left"));

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("cloud-scripts.tgz") && problem.contains("no space left"));
    }

    @Test
    public void theBootArgumentsAreWrittenAsSoonAsTheAgentAnswersBecauseTheVmWaitsOnlyAMinute() {
        when(client.agentPing("pve1", 9300L)).thenReturn(false).thenReturn(false).thenReturn(true);

        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        verify(client, times(3)).agentPing("pve1", 9300L);
        assertEquals(2 * ProxmoxSystemVms.AGENT_POLL_MILLIS, now.get());
    }

    @Test
    public void aGuestAgentThatNeverAnswersFailsTheStartWithWhatToLookAt() {
        when(client.agentPing("pve1", 9300L)).thenReturn(false);

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertNotNull(problem);
        assertTrue(problem, problem.contains("qemu-guest-agent") && problem.contains("s-1-VM"));
        verify(client, never()).agentFileWrite(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    public void aFailedWriteIsRetriedAndThenReported() {
        doThrow(new ProxmoxApiException("QEMU guest agent is not running", 500, null)).when(client).agentFileWrite(anyString(), anyLong(), anyString(), anyString());

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("boot arguments") && problem.contains("QEMU guest agent is not running"));
        verify(client, times(ProxmoxSystemVms.WRITE_ATTEMPTS)).agentFileWrite(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    public void aSystemVmThatNeverBringsItsNetworkUpFailsTheStart() {
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any())).thenReturn(new ExecutionResult(false, ""));

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("did not finish configuring") && problem.contains("cloud.log"));
    }

    @Test
    public void aSystemVmThatIsNotSetUpFailsTheStart() throws Exception {
        when(routing.isSystemVMSetup("s-1-VM", IP)).thenReturn(false);

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("did not complete its setup"));
    }

    @Test
    public void aVmWithoutAControlNicOrBootArgumentsIsRefusedBeforeAnythingIsCalled() {
        assertTrue(systemVms.afterStart(client, vm(VirtualMachine.Type.DomainRouter, BOOT_ARGS, null), "pve1", 9300L).contains("no control NIC"));
        assertTrue(systemVms.afterStart(client, vm(VirtualMachine.Type.DomainRouter, "", IP), "pve1", 9300L).contains("no boot arguments"));
        verify(client, never()).agentPing(anyString(), anyLong());
    }

    @Test
    public void anApiErrorWhileWaitingIsTheReason() {
        when(client.agentPing("pve1", 9300L)).thenThrow(new ProxmoxApiException("Proxmox API POST failed: HTTP 403 Forbidden", 403, null));

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("403"));
    }

    // ---- before the start: the payload on the node

    private void storageIsActive() {
        when(client.getStorageStatus(eq("pve1"), anyString())).thenReturn(new ProxmoxApiClient.StorageStatus(true, true, 1L, 1L, 1L));
    }

    @Test
    public void theEntryAndTheUploadAreMadeWhenTheNodeHasNeitherAndOlderFilesAreRemoved() {
        storageIsActive();
        when(client.uploadIso(anyString(), anyString(), anyString(), any(), anyInt())).thenReturn("UPID:pve1:1:2:3:imgcopy::u:");
        String current = STORAGE + ":iso/" + ProxmoxStorageNames.patchFileName(agentSha);
        String old = STORAGE + ":iso/cs-patch-0123456789abcdef.iso";
        String foreign = STORAGE + ":iso/debian.iso";
        when(client.listContent("pve1", STORAGE, "iso")).thenReturn(java.util.Arrays.asList(new ProxmoxApiClient.ContentItem(current, 2048),
                new ProxmoxApiClient.ContentItem(old, 2048), new ProxmoxApiClient.ContentItem(foreign, 5)));

        assertNull(systemVms.beforeStart(client, ssvm(), "pve1"));

        verify(client).createPatchStorage(STORAGE, PATCH_PATH);
        verify(client).uploadIso(eq("pve1"), eq(STORAGE), eq(ProxmoxStorageNames.patchFileName(agentSha)), eq(files.path(ProxmoxPatchFiles.AGENT_ZIP)), eq(2048));
        verify(client).waitForTask("UPID:pve1:1:2:3:imgcopy::u:", 600);
        verify(client).deleteImage("pve1", old);
        verify(client, never()).deleteImage("pve1", foreign);
        verify(client, never()).deleteImage("pve1", current);
    }

    @Test
    public void aFileThatIsAlreadyOnTheNodeWithTheRightSizeIsNotUploadedAgain() {
        storageIsActive();
        when(client.findStorage(STORAGE)).thenReturn(mock(ProxmoxApiClient.StorageConfig.class));
        when(client.getImageInfo("pve1", STORAGE + ":iso/" + ProxmoxStorageNames.patchFileName(agentSha))).thenReturn(new ProxmoxApiClient.ImageInfo(2048, 2048, "iso", null));

        assertNull(systemVms.beforeStart(client, ssvm(), "pve1"));

        verify(client, never()).createPatchStorage(anyString(), anyString());
        verify(client, never()).uploadIso(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    public void aFileOfAnotherSizeIsUploadedAgain() {
        storageIsActive();
        when(client.findStorage(STORAGE)).thenReturn(mock(ProxmoxApiClient.StorageConfig.class));
        when(client.getImageInfo(eq("pve1"), anyString())).thenReturn(new ProxmoxApiClient.ImageInfo(100, 100, "iso", null));
        when(client.uploadIso(anyString(), anyString(), anyString(), any(), anyInt())).thenReturn("UPID:pve1:1:2:3:imgcopy::u:");

        assertNull(systemVms.beforeStart(client, ssvm(), "pve1"));

        verify(client).uploadIso(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    public void anEntryThatAnotherStartMadeAMomentAgoIsUsed() {
        storageIsActive();
        when(client.findStorage(STORAGE)).thenReturn(null).thenReturn(mock(ProxmoxApiClient.StorageConfig.class));
        doThrow(new ProxmoxApiException("storage ID already defined", 500, null)).when(client).createPatchStorage(anyString(), anyString());
        when(client.getImageInfo(eq("pve1"), anyString())).thenReturn(new ProxmoxApiClient.ImageInfo(2048, 2048, "iso", null));

        assertNull(systemVms.beforeStart(client, ssvm(), "pve1"));
    }

    @Test
    public void theEntryIsWaitedForUntilTheNodeHasItActive() {
        when(client.findStorage(STORAGE)).thenReturn(mock(ProxmoxApiClient.StorageConfig.class));
        when(client.getStorageStatus(eq("pve1"), anyString())).thenReturn(new ProxmoxApiClient.StorageStatus(false, true, 0, 0, 0))
                .thenReturn(new ProxmoxApiClient.StorageStatus(true, true, 1L, 1L, 1L));
        when(client.getImageInfo(eq("pve1"), anyString())).thenReturn(new ProxmoxApiClient.ImageInfo(2048, 2048, "iso", null));

        assertNull(systemVms.beforeStart(client, ssvm(), "pve1"));

        assertEquals(ProxmoxSystemVms.STORAGE_ACTIVATION_WAIT_MS, now.get());
    }

    @Test
    public void aRefusedUploadFailsTheStartWithWhatTheTokenNeeds() {
        storageIsActive();
        when(client.findStorage(STORAGE)).thenReturn(mock(ProxmoxApiClient.StorageConfig.class));
        when(client.uploadIso(anyString(), anyString(), anyString(), any(), anyInt())).thenThrow(new ProxmoxApiException("HTTP 403", 403, null));

        String problem = systemVms.beforeStart(client, ssvm(), "pve1");

        assertTrue(problem, problem.contains("pve1") && problem.contains("403") && problem.contains("PVEDatastoreAdmin") && problem.contains(PATCH_PATH));
    }

    @Test
    public void aRouterNeedsNoPayloadOnTheNode() {
        assertNull(systemVms.beforeStart(client, router(), "pve1"));
        assertNull(systemVms.beforeStart(client, vm(VirtualMachine.Type.User, "", IP), "pve1"));

        verify(client, never()).findStorage(anyString());
    }

    @Test
    public void aMissingPatchFileFailsTheStartWithItsNameAndDirectory() {
        storageIsActive();
        when(client.findStorage(STORAGE)).thenReturn(mock(ProxmoxApiClient.StorageConfig.class));
        files = new ProxmoxPatchFiles(folder.getRoot().toPath().resolve("nowhere"));

        String problem = systemVms.beforeStart(client, vm(VirtualMachine.Type.ConsoleProxy, BOOT_ARGS, IP), "pve1");

        assertTrue(problem, problem.contains("agent.zip") && problem.contains("cloudstack-common"));
    }

    @Test
    public void aPathThatIsNotADirectoryPathIsRefused() {
        systemVms = new ProxmoxSystemVms(deployer, routing, () -> files, () -> "relative path", () -> 600, millis -> { }, now::get);

        String problem = systemVms.beforeStart(client, ssvm(), "pve1");

        assertTrue(problem, problem.contains("proxmox.patch.path"));
        verify(client, never()).createPatchStorage(anyString(), anyString());
    }
}

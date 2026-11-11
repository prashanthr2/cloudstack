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
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxVmShell;
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
    private static final String CACHE = "/var/cache/cloud/";

    /** A login in the VM for tests: it records what is run and copied, and answers the state check and the checksums as a real VM would. */
    private class FakeShell implements ProxmoxVmShell {
        final List<String> commands = new ArrayList<>();
        final List<String> uploads = new ArrayList<>();
        String state = "SIG=\nAGENT=no\n";
        /** The checksum the VM reports for a file it was given; by default the right one. */
        Function<String, String> sums = name -> {
            try {
                return files.sha512(name);
            } catch (ProxmoxApiException e) {
                return "";
            }
        };
        ExecResult moveResult = new ExecResult(0, "", "");
        boolean closed;

        @Override
        public ExecResult exec(String command, int timeoutMillis) {
            commands.add(command);
            if (command.equals(ProxmoxSystemVms.STATE_CHECK)) {
                return new ExecResult(0, state, "");
            }
            if (command.startsWith("sha512sum ")) {
                String name = command.substring(command.lastIndexOf('/') + 1, command.indexOf(".part"));
                return new ExecResult(0, sums.apply(name) + "\n", "");
            }
            if (command.startsWith("cd ")) {
                return moveResult;
            }
            return new ExecResult(0, "", "");
        }

        @Override
        public void upload(Path file, String remotePath, int timeoutMillis) {
            uploads.add(remotePath);
            commands.add("upload " + remotePath);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ProxmoxApiClient client;
    private ProxmoxRouterDeployer deployer;
    private VirtualRoutingResource routing;
    private ProxmoxPatchFiles files;
    private ProxmoxSystemVms systemVms;
    private FakeShell shell;
    private RuntimeException shellFailure;
    private String sshKeys = "";
    private final AtomicLong now = new AtomicLong();

    @Before
    public void setUp() throws Exception {
        Path dir = folder.newFolder().toPath();
        Files.write(dir.resolve(ProxmoxPatchFiles.AGENT_ZIP), "agent-bytes-0123456789".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(ProxmoxPatchFiles.SCRIPTS), "scripts-bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(ProxmoxPatchFiles.PATCH_SCRIPT), "#!/bin/bash\n".getBytes(StandardCharsets.UTF_8));
        files = new ProxmoxPatchFiles(dir);
        shell = new FakeShell();
        client = mock(ProxmoxApiClient.class);
        deployer = mock(ProxmoxRouterDeployer.class);
        routing = mock(VirtualRoutingResource.class);
        systemVms = new ProxmoxSystemVms(deployer, routing, () -> files, () -> sshKeys, controlIp -> {
            if (shellFailure != null) {
                throw shellFailure;
            }
            assertEquals(IP, controlIp);
            return shell;
        }, millis -> now.addAndGet(millis), now::get);
        when(client.agentPing("pve1", 9300L)).thenReturn(true);
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any())).thenReturn(new ExecutionResult(true, ""));
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
    public void aRouterGetsItsBootArgumentsThenItsScriptsOverSshAndIsWaitedForUntilItIsSetUp() throws Exception {
        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        verify(deployer).register(IP, "s-1-VM");
        InOrder order = inOrder(client, deployer, routing);
        order.verify(client).agentPing("pve1", 9300L);
        order.verify(client).agentFileWrite("pve1", 9300L, ProxmoxSystemVms.CMDLINE, BOOT_ARGS);
        order.verify(deployer).run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any());
        order.verify(routing).isSystemVMSetup("s-1-VM", IP);
        // a router has no agent to carry in, and the scripts come last: the VM waits for cloud-scripts.tgz
        assertEquals(java.util.Arrays.asList(CACHE + "patch-sysvms.sh.part", CACHE + "cloud-scripts.tgz.part"), shell.uploads);
        assertEquals("cd /var/cache/cloud/ && mv -f patch-sysvms.sh.part patch-sysvms.sh && mv -f cloud-scripts.tgz.part cloud-scripts.tgz",
                shell.commands.get(shell.commands.size() - 1));
        assertTrue(shell.closed);
        verify(client, never()).updateVmConfig(anyString(), anyLong(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    public void theFilesAreMovedIntoPlaceOnlyAfterEveryCopyWasChecked() {
        assertNull(systemVms.afterStart(client, ssvm(), "pve1", 9300L));

        List<String> expected = java.util.Arrays.asList(
                ProxmoxSystemVms.STATE_CHECK,
                "upload " + CACHE + "agent.zip.part", "sha512sum " + CACHE + "agent.zip.part | cut -d' ' -f1",
                "upload " + CACHE + "patch-sysvms.sh.part", "sha512sum " + CACHE + "patch-sysvms.sh.part | cut -d' ' -f1",
                "upload " + CACHE + "cloud-scripts.tgz.part", "sha512sum " + CACHE + "cloud-scripts.tgz.part | cut -d' ' -f1",
                "cd /var/cache/cloud/ && mv -f agent.zip.part agent.zip && mv -f patch-sysvms.sh.part patch-sysvms.sh && mv -f cloud-scripts.tgz.part cloud-scripts.tgz");
        assertEquals(expected, shell.commands);
    }

    @Test
    public void aRouterWithTheCurrentScriptsStillGetsThemBecauseItRemovesItsAgentDirectoryAtEveryBoot() {
        shell.state = "SIG=" + files.sha512(ProxmoxPatchFiles.SCRIPTS) + "\nAGENT=no\n";

        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        assertTrue(shell.uploads.contains(CACHE + "cloud-scripts.tgz.part"));
    }

    @Test
    public void aRestartedSecondaryStorageVmThatHasTheAgentAndTheCurrentScriptsNeedsNothing() {
        shell.state = "SIG=" + files.sha512(ProxmoxPatchFiles.SCRIPTS) + "\nAGENT=yes\n";

        assertNull(systemVms.afterStart(client, ssvm(), "pve1", 9300L));

        assertTrue(shell.uploads.isEmpty());
        assertEquals(java.util.Collections.singletonList(ProxmoxSystemVms.STATE_CHECK), shell.commands);
        assertTrue(shell.closed);
    }

    @Test
    public void aSecondaryStorageVmWithOldScriptsIsPatchedAgainEvenIfItHasAnAgent() {
        shell.state = "SIG=oldsignature\nAGENT=yes\n";

        assertNull(systemVms.afterStart(client, ssvm(), "pve1", 9300L));

        assertTrue(shell.uploads.contains(CACHE + "agent.zip.part"));
        assertTrue(shell.uploads.contains(CACHE + "cloud-scripts.tgz.part"));
    }

    @Test
    public void aDamagedCopyOfTheAgentFailsTheStartAndNothingIsMovedIntoPlace() {
        shell.sums = name -> name.equals(ProxmoxPatchFiles.AGENT_ZIP) ? "deadbeef" : files.sha512(name);

        String problem = systemVms.afterStart(client, ssvm(), "pve1", 9300L);

        assertTrue(problem, problem.contains("damaged") && problem.contains("agent.zip"));
        assertTrue(shell.commands.stream().noneMatch(c -> c.startsWith("cd ")));
        assertTrue(shell.closed);
    }

    @Test
    public void aDamagedScriptsFileIsCaughtToo() {
        shell.sums = name -> name.equals(ProxmoxPatchFiles.SCRIPTS) ? "deadbeef" : files.sha512(name);

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("damaged") && problem.contains("cloud-scripts.tgz"));
    }

    @Test
    public void aFailedMoveFailsTheStartWithWhatTheVmSaid() {
        shell.moveResult = new ExecResult(1, "", "mv: cannot move: no space left");

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("in place") && problem.contains("no space left"));
    }

    @Test
    public void aVmThatCannotBeLoggedInToFailsTheStartWithTheReason() {
        shellFailure = new ProxmoxSshException("Unable to log in over SSH to the system VM at 169.254.3.7 through Proxmox node root@10.0.0.1:22 within 120 seconds: Connection refused");

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("s-1-VM") && problem.contains("Connection refused") && problem.contains("over SSH"));
        verify(deployer, never()).run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any());
    }

    @Test
    public void whenTheLoginFailsTheVmIsAskedThroughTheGuestAgentWhatItLooksLike() {
        shellFailure = new ProxmoxSshException("no answer");
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.DIAGNOSE), any(), any())).thenReturn(new ExecutionResult(true, "--- addresses\nlo UNKNOWN"));

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("no answer"));
        verify(deployer).run(eq(IP), eq(ProxmoxSystemVms.DIAGNOSE), any(), any());
    }

    @Test
    public void aGuestAgentThatCannotAnswerTheDiagnosisDoesNotChangeTheReason() {
        shellFailure = new ProxmoxSshException("no answer");
        when(deployer.run(eq(IP), eq(ProxmoxSystemVms.DIAGNOSE), any(), any())).thenThrow(new ProxmoxApiException("agent gone", 500, null));

        String problem = systemVms.afterStart(client, router(), "pve1", 9300L);

        assertTrue(problem, problem.contains("no answer") && !problem.contains("agent gone"));
    }

    @Test
    public void aMissingPatchFileFailsTheStartWithItsNameAndDirectory() {
        files = new ProxmoxPatchFiles(folder.getRoot().toPath().resolve("nowhere"));

        String problem = systemVms.afterStart(client, vm(VirtualMachine.Type.ConsoleProxy, BOOT_ARGS, IP), "pve1", 9300L);

        assertTrue(problem, problem.contains("cloud-scripts.tgz") && problem.contains("cloudstack-common"));
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

    // ---- the administrator's login keys

    private static final String KEY1 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ0example1 cloudstack-systemvm";
    private static final String KEY2 = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQexample2= root@pve1";

    @Test
    public void withoutTheSettingNothingIsAddedToTheAuthorizedKeys() {
        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        verify(deployer, never()).run(eq(IP), org.mockito.ArgumentMatchers.contains("authorized_keys"), any(), any());
    }

    @Test
    public void theKeyIsAddedOnceAfterTheVmIsConfiguredAndBeforeItIsCheckedForSetup() throws Exception {
        sshKeys = KEY1;
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("authorized_keys"), any(), any())).thenReturn(new ExecutionResult(true, ""));

        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        InOrder order = inOrder(deployer, routing);
        order.verify(deployer).run(eq(IP), eq(ProxmoxSystemVms.READY_CHECK), any(), any());
        order.verify(deployer).run(eq(IP), eq("mkdir -p /root/.ssh && chmod 700 /root/.ssh && touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys"
                + " && (grep -qxF -- '" + KEY1 + "' /root/.ssh/authorized_keys || echo '" + KEY1 + "' >> /root/.ssh/authorized_keys)"), any(), any());
        order.verify(routing).isSystemVMSetup("s-1-VM", IP);
    }

    @Test
    public void severalKeysSeparatedByABarOrALineAreAllAdded() {
        sshKeys = KEY1 + "|" + KEY2 + "\n  \n";
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("authorized_keys"), any(), any())).thenReturn(new ExecutionResult(true, ""));

        systemVms.afterStart(client, router(), "pve1", 9300L);

        verify(deployer).run(eq(IP), org.mockito.ArgumentMatchers.argThat(command -> command.contains(KEY1) && command.contains(KEY2)), any(), any());
    }

    @Test
    public void anEntryThatIsNotAPublicKeyIsIgnoredSoNothingOddReachesAShell() {
        sshKeys = "ssh-ed25519 AAAA'; rm -rf / #|not a key|" + KEY2;
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("authorized_keys"), any(), any())).thenReturn(new ExecutionResult(true, ""));

        systemVms.afterStart(client, router(), "pve1", 9300L);

        verify(deployer).run(eq(IP), org.mockito.ArgumentMatchers.argThat(command -> command.contains(KEY2) && !command.contains("rm -rf") && !command.contains("not a key")),
                any(), any());
    }

    @Test
    public void aSettingWithNoUsableKeyRunsNothing() {
        sshKeys = "just some text";

        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));

        verify(deployer, never()).run(eq(IP), org.mockito.ArgumentMatchers.contains("authorized_keys"), any(), any());
    }

    @Test
    public void aFailureToAddTheKeysDoesNotFailTheStart() {
        sshKeys = KEY1;
        when(deployer.run(eq(IP), org.mockito.ArgumentMatchers.contains("authorized_keys"), any(), any())).thenReturn(new ExecutionResult(false, "read-only file system"));

        assertNull(systemVms.afterStart(client, router(), "pve1", 9300L));
    }

    @Test
    public void thePublicKeyCheckAcceptsTheCommonKeyTypes() {
        for (String key : new String[] {KEY1, KEY2, "ecdsa-sha2-nistp256 AAAAE2VjZHNhexample3= admin", "sk-ssh-ed25519@openssh.com AAAAGnNrexample4 token",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ0example1"}) {
            assertTrue(key, ProxmoxSystemVms.PUBLIC_KEY.matcher(key).matches());
        }
        for (String key : new String[] {"", "AAAA", "ssh-ed25519", "ssh-ed25519 AAAA with'quote", "ssh-ed25519 AAAA $(id)", "ssh-ed25519 AAAA; id"}) {
            assertTrue(key, !ProxmoxSystemVms.PUBLIC_KEY.matcher(key).matches());
        }
    }
}

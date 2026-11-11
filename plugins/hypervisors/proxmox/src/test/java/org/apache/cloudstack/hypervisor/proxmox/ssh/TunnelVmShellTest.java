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
package org.apache.cloudstack.hypervisor.proxmox.ssh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;

public class TunnelVmShellTest {
    private static final String VM_ADDRESS = "169.254.3.7";
    private static final String KEY = "-----BEGIN EC PRIVATE KEY-----\nabc\n-----END EC PRIVATE KEY-----\n";

    private ScriptedSshLink nodeLink;
    private ProxmoxSsh node;
    private final List<Integer> localPorts = new ArrayList<>();
    private final List<ScriptedSshLink> vmLinks = new ArrayList<>();
    private final AtomicLong now = new AtomicLong();
    private int refusals;
    private boolean keyAccepted = true;

    @Before
    public void setUp() {
        nodeLink = new ScriptedSshLink();
        node = ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "secret", null), () -> nodeLink);
    }

    private TunnelVmShell open(String key) {
        return TunnelVmShell.open(node, VM_ADDRESS, key, port -> {
            localPorts.add(port);
            ScriptedSshLink link = new ScriptedSshLink() {
                @Override
                public void connect(int timeoutMillis, HostKeyCheck check) throws IOException {
                    if (refusals > 0) {
                        refusals--;
                        throw new IOException("Connection closed");
                    }
                    super.connect(timeoutMillis, check);
                }
            };
            link.acceptsKey = keyAccepted;
            vmLinks.add(link);
            return link;
        }, 120, millis -> now.addAndGet(millis), now::get);
    }

    @Test
    public void thePortOfTheVmIsForwardedByTheNodeAndTheLoginUsesTheLocalEnd() {
        nodeLink.forwardedPort = 41234;

        TunnelVmShell shell = open(KEY);

        assertEquals("169.254.3.7:3922", nodeLink.forwardedTo);
        assertEquals(java.util.Collections.singletonList(41234), localPorts);
        assertEquals(1, vmLinks.size());
        assertFalse(vmLinks.get(0).closed);
        shell.close();
    }

    @Test
    public void theVmIsGivenTimeToOpenItsPortAndARefusedConnectionIsTriedAgain() {
        refusals = 2;

        TunnelVmShell shell = open(KEY);

        assertEquals(3, vmLinks.size());
        assertTrue(vmLinks.get(0).closed && vmLinks.get(1).closed);
        assertFalse(vmLinks.get(2).closed);
        assertEquals(2 * TunnelVmShell.RETRY_MILLIS, now.get());
        shell.close();
    }

    @Test
    public void aVmThatNeverAnswersFailsAfterTheWaitAndTheNodeLoginIsClosed() {
        refusals = Integer.MAX_VALUE;

        try {
            open(KEY);
            fail("the login should have failed");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(VM_ADDRESS) && e.getMessage().contains("120 seconds") && e.getMessage().contains("Connection closed"));
        }
        assertTrue(nodeLink.closed);
        assertTrue(now.get() >= 120_000);
    }

    @Test
    public void aKeyTheVmDoesNotAcceptIsReportedAsThatAfterTheWait() {
        keyAccepted = false;

        try {
            open(KEY);
            fail("the login should have failed");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not accept the management server's key"));
        }
        assertTrue(nodeLink.closed);
        assertTrue(vmLinks.stream().allMatch(l -> l.closed));
    }

    @Test
    public void withoutAPrivateKeyNothingIsOpenedAndTheNodeLoginIsClosed() {
        try {
            open(" ");
            fail("the login should have failed");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ssh.privatekey"));
        }
        assertTrue(nodeLink.closed);
        assertTrue(vmLinks.isEmpty());
        assertEquals(null, nodeLink.forwardedTo);
    }

    @Test
    public void aCommandAndAFileGoToTheVmAndClosingEndsBothLogins() {
        TunnelVmShell shell = open(KEY);

        ExecResult result = shell.exec("echo hi", 1000);
        shell.upload(Paths.get("/tmp/agent.zip"), "/var/cache/cloud/agent.zip.part", 1000);
        shell.close();

        assertTrue(result.isSuccess());
        assertEquals(java.util.Collections.singletonList("echo hi"), vmLinks.get(0).commands);
        assertEquals(java.util.Collections.singletonList("agent.zip -> /var/cache/cloud/agent.zip.part"), vmLinks.get(0).uploaded);
        assertTrue(vmLinks.get(0).closed);
        assertTrue(nodeLink.closed);
    }
}

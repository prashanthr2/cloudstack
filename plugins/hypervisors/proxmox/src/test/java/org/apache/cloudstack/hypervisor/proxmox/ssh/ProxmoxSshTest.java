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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

public class ProxmoxSshTest {
    private static final String KEY_FINGERPRINT = "SHA256:x5aXc/0jcbNxnAqPbL1diu0q8h1/iGBrnkMtBOCZHdY";

    /** A node that accepts what it is told to accept and answers commands as the test says. */
    private static class FakeLink implements SshLink {
        String fingerprint = KEY_FINGERPRINT;
        boolean acceptsKey;
        boolean acceptsPassword;
        final List<String> logins = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<byte[]> inputs = new ArrayList<>();
        Function<String, ExecResult> answers = command -> new ExecResult(0, "", "");
        boolean closed;

        @Override
        public void connect(int timeoutMillis, HostKeyCheck check) throws IOException {
            if (!check.accept(fingerprint)) {
                throw new IOException("the host key was refused");
            }
        }

        @Override
        public boolean authenticateWithKey(String user, String privateKeyPem) {
            logins.add("key");
            return acceptsKey;
        }

        @Override
        public boolean authenticateWithPassword(String user, String password) {
            logins.add("password");
            return acceptsPassword;
        }

        @Override
        public ExecResult exec(String command, byte[] stdin, int timeoutMillis) {
            commands.add(command);
            inputs.add(stdin);
            return answers.apply(command);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static SshTarget target(String user, String key, String password, String pinned) {
        return new SshTarget("10.0.32.74", 22, user, key, password, pinned);
    }

    @Test
    public void theKeyIsTriedFirstAndThePasswordOnlyWhenTheKeyIsRefused() {
        FakeLink link = new FakeLink();
        link.acceptsPassword = true;

        ProxmoxSsh.open(target("root", "KEY", "secret", null), () -> link);

        assertEquals(java.util.Arrays.asList("key", "password"), link.logins);
    }

    @Test
    public void anAcceptedKeyNeedsNoPassword() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;

        ProxmoxSsh.open(target("root", "KEY", "secret", null), () -> link);

        assertEquals(java.util.Collections.singletonList("key"), link.logins);
    }

    @Test
    public void withoutAKeyOnlyThePasswordIsTried() {
        FakeLink link = new FakeLink();
        link.acceptsPassword = true;

        ProxmoxSsh.open(target("root", null, "secret", null), () -> link);

        assertEquals(java.util.Collections.singletonList("password"), link.logins);
    }

    @Test
    public void aRefusedLoginSaysWhichWayWasTriedAndClosesTheConnection() {
        FakeLink link = new FakeLink();
        try {
            ProxmoxSsh.open(target("root", "KEY", null, null), () -> link);
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not authorised") && e.getMessage().contains("no password was given"));
            assertTrue(link.closed);
        }
    }

    @Test
    public void aLoginNeedsAKeyOrAPassword() {
        try {
            ProxmoxSsh.open(target("root", " ", "", null), FakeLink::new);
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("private key"));
        }
    }

    @Test
    public void theFirstConnectionReportsTheHostKeyToKeep() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;

        ProxmoxSsh ssh = ProxmoxSsh.open(target("root", "KEY", null, null), () -> link);

        assertEquals(KEY_FINGERPRINT, ssh.getHostKeyFingerprint());
    }

    @Test
    public void aChangedHostKeyFailsTheConnectionAndSaysSo() {
        FakeLink link = new FakeLink();
        link.fingerprint = "SHA256:other";
        link.acceptsKey = true;
        try {
            ProxmoxSsh.open(target("root", "KEY", null, KEY_FINGERPRINT), () -> link);
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("host key") && e.getMessage().contains("changed"));
            assertTrue(link.logins.isEmpty());
        }
    }

    @Test
    public void theKeptHostKeyIsAccepted() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;

        ProxmoxSsh.open(target("root", "KEY", null, KEY_FINGERPRINT), () -> link);

        assertEquals(java.util.Collections.singletonList("key"), link.logins);
    }

    @Test
    public void rootRunsCommandsAsTheyAre() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;
        ProxmoxSsh ssh = ProxmoxSsh.open(target("root", "KEY", null, null), () -> link);

        ssh.sudoOrFail("pveum user list");

        assertEquals(java.util.Collections.singletonList("pveum user list"), link.commands);
    }

    @Test
    public void aUserWhoNeedsNoPasswordForSudoRunsCommandsThroughSudoN() {
        FakeLink link = new FakeLink();
        link.acceptsPassword = true;
        ProxmoxSsh ssh = ProxmoxSsh.open(target("csadmin", null, "secret", null), () -> link);

        ssh.sudoOrFail("pveum user list");

        assertEquals("sudo -n true", link.commands.get(0));
        assertEquals("sudo -n sh -c 'pveum user list'", link.commands.get(1));
    }

    @Test
    public void aUserWhoNeedsAPasswordForSudoGivesItOnStandardInput() {
        FakeLink link = new FakeLink();
        link.acceptsPassword = true;
        link.answers = command -> command.equals("sudo -n true") ? new ExecResult(1, "", "a password is required") : new ExecResult(0, "ok", "");
        ProxmoxSsh ssh = ProxmoxSsh.open(target("csadmin", null, "secret", null), () -> link);

        ssh.sudo("pveum user list", "more".getBytes(StandardCharsets.UTF_8), 1000);

        assertEquals("sudo -S -p '' sh -c 'pveum user list'", link.commands.get(1));
        assertEquals("secret\nmore", new String(link.inputs.get(1), StandardCharsets.UTF_8));
    }

    @Test
    public void aUserWhoNeedsAPasswordForSudoAndHasNoneGetsAMessageAboutNopasswd() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;
        link.answers = command -> new ExecResult(1, "", "a password is required");
        ProxmoxSsh ssh = ProxmoxSsh.open(target("csadmin", "KEY", null, null), () -> link);
        try {
            ssh.sudoOrFail("true");
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("NOPASSWD"));
        }
    }

    @Test
    public void aFailedCommandCarriesWhatItPrinted() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;
        link.answers = command -> new ExecResult(2, "", "unable to find user");
        ProxmoxSsh ssh = ProxmoxSsh.open(target("root", "KEY", null, null), () -> link);
        try {
            ssh.sudoOrFail("pveum user delete x@pve");
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unable to find user") && e.getMessage().contains("pveum user delete"));
        }
    }

    @Test
    public void aFileIsWrittenFromStandardInputWithItsModeAndItsPathQuoted() {
        FakeLink link = new FakeLink();
        link.acceptsKey = true;
        ProxmoxSsh ssh = ProxmoxSsh.open(target("root", "KEY", null, null), () -> link);

        ssh.writeFile("/etc/pve/priv/my key", "line".getBytes(StandardCharsets.UTF_8), "600");

        assertEquals("umask 077 && cat > '/etc/pve/priv/my key' && chmod '600' '/etc/pve/priv/my key'", link.commands.get(0));
        assertEquals("line", new String(link.inputs.get(0), StandardCharsets.UTF_8));
    }

    @Test
    public void aSingleQuoteInAWordIsEscaped() {
        assertEquals("'it'\\''s'", ProxmoxSsh.quote("it's"));
        assertFalse(ProxmoxSsh.quote("a b").contains("\""));
    }

    @Test
    public void theFingerprintHasTheFormOfSshKeygen() {
        String fingerprint = TrileadSshLink.fingerprintOf("any key blob".getBytes(StandardCharsets.UTF_8));

        assertTrue(fingerprint, fingerprint.startsWith("SHA256:"));
        assertFalse(fingerprint.contains("="));
        assertEquals(7 + 43, fingerprint.length());
    }
}

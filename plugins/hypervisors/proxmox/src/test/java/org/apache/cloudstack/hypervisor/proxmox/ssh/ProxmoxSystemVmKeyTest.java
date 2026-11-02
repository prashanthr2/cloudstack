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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProxmoxSystemVmKeyTest {
    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPlaceholderKeyForTests+/ cloudstack-systemvm";

    private ProxmoxSsh open(ScriptedSshLink link) {
        return ProxmoxSsh.open(new SshTarget("10.0.32.196", 22, "root", null, "pw", null), () -> link);
    }

    @Test
    public void anExistingPairIsReadAndNotMadeAgain() {
        ScriptedSshLink link = new ScriptedSshLink();
        link.answers = command -> command.startsWith("cat ") ? new ExecResult(0, KEY + "\n", "") : new ExecResult(1, "", "unexpected");

        assertEquals(KEY, ProxmoxSystemVmKey.ensure(open(link)));

        assertTrue(link.commandsWith("ssh-keygen").isEmpty());
    }

    @Test
    public void aMissingPairIsMadeOnTheNodeInTheClusterWidePrivateDirectory() {
        ScriptedSshLink link = new ScriptedSshLink();
        link.answers = command -> {
            if (command.startsWith("cat ")) {
                return link.commandsWith("ssh-keygen -q -t ed25519").isEmpty() ? new ExecResult(1, "", "No such file") : new ExecResult(0, KEY, "");
            }
            return command.startsWith("test -f") ? new ExecResult(1, "", "") : new ExecResult(0, "", "");
        };

        assertEquals(KEY, ProxmoxSystemVmKey.ensure(open(link)));

        assertEquals(1, link.commandsWith("ssh-keygen -q -t ed25519 -N '' -C cloudstack-systemvm -f '/etc/pve/priv/cloudstack_systemvm'").size());
    }

    @Test
    public void aPrivateKeyWithoutItsPublicFileIsNotReplaced() {
        ScriptedSshLink link = new ScriptedSshLink();
        link.answers = command -> command.startsWith("cat ") ? new ExecResult(1, "", "No such file")
                : command.startsWith("ssh-keygen -y") ? new ExecResult(0, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPlaceholderKeyForTests+/\n", "")
                : new ExecResult(0, "", "");

        String key = ProxmoxSystemVmKey.ensure(open(link));

        assertTrue(key.startsWith("ssh-ed25519 AAAA"));
        assertTrue(link.commandsWith("ssh-keygen -q").isEmpty());
    }

    @Test
    public void aKeyThatCannotBeMadeIsAnError() {
        ScriptedSshLink link = new ScriptedSshLink();
        link.answers = command -> command.startsWith("test -f") || command.startsWith("cat ") || command.startsWith("ssh-keygen") ? new ExecResult(1, "", "Permission denied")
                : new ExecResult(0, "", "");

        try {
            ProxmoxSystemVmKey.ensure(open(link));
            fail("expected an error");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Permission denied"));
        }
    }

    @Test
    public void theKeyIsAddedToAnEmptySettingAndAfterTheAdministratorsKeys() {
        assertEquals(KEY, ProxmoxSystemVmKey.addTo("", KEY));
        assertEquals(KEY, ProxmoxSystemVmKey.addTo(null, KEY));
        assertEquals("ssh-rsa AAAAB3Admin me@work|" + KEY, ProxmoxSystemVmKey.addTo("ssh-rsa AAAAB3Admin me@work", KEY));
        assertEquals("ssh-rsa AAAAB3Admin me@work|ssh-ed25519 AAAAOther x|" + KEY, ProxmoxSystemVmKey.addTo("ssh-rsa AAAAB3Admin me@work | ssh-ed25519 AAAAOther x", KEY));
    }

    @Test
    public void aSettingThatHasTheKeyIsLeftAlone() {
        assertNull(ProxmoxSystemVmKey.addTo(KEY, KEY));
        assertNull(ProxmoxSystemVmKey.addTo("ssh-rsa AAAAB3Admin me@work|" + KEY, KEY));
        // the comment is not part of the key
        assertNull(ProxmoxSystemVmKey.addTo("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPlaceholderKeyForTests+/ renamed", KEY));
    }
}

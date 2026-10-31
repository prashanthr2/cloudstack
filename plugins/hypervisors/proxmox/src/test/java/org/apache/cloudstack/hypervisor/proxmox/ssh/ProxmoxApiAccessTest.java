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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProxmoxApiAccessTest {
    private static final String TOKEN_JSON = "{\"full-tokenid\":\"cloudstack@pve!cs-pve1\",\"info\":{\"privsep\":\"1\"},\"value\":\"17357352-5a28-4716-bd13-de375e83dbd4\"}";

    private ScriptedSshLink node() {
        ScriptedSshLink link = new ScriptedSshLink();
        link.answers = command -> command.contains("user token add") ? new ExecResult(0, TOKEN_JSON, "") : new ExecResult(0, "", "");
        return link;
    }

    private ProxmoxSsh open(ScriptedSshLink link) {
        return ProxmoxSsh.open(new SshTarget("10.0.32.74", 22, "root", null, "secret", null), () -> link);
    }

    @Test
    public void aTokenIsMadeForTheNodeAndItsIdAndSecretAreRead() {
        ScriptedSshLink link = node();

        ProxmoxApiAccess.Token token = ProxmoxApiAccess.create(open(link), "pve1", false);

        assertEquals("cloudstack@pve!cs-pve1", token.getId());
        assertEquals("17357352-5a28-4716-bd13-de375e83dbd4", token.getSecret());
        assertEquals(1, link.commandsWith("user token add cloudstack@pve cs-pve1 --privsep 1 --output-format json").size());
    }

    @Test
    public void anOldTokenOfTheSameNameIsRemovedFirst() {
        ScriptedSshLink link = node();

        ProxmoxApiAccess.create(open(link), "pve1", false);

        assertTrue(link.commands.indexOf("pveum user token remove cloudstack@pve cs-pve1") >= 0);
        assertTrue(link.commands.indexOf("pveum user token remove cloudstack@pve cs-pve1") < link.commandsWith("user token add").size() + link.commands.indexOf(link.commandsWith("user token add").get(0)));
    }

    @Test
    public void theUserAndTheRolesAreMadeWhenMissingAndKeptWhenTheyExist() {
        ScriptedSshLink link = node();
        link.answers = command -> command.contains("user token add") ? new ExecResult(0, TOKEN_JSON, "")
                : command.contains("user add") || command.contains("role add") ? new ExecResult(255, "", "user 'cloudstack@pve' already exists") : new ExecResult(0, "", "");

        ProxmoxApiAccess.create(open(link), "pve1", false);

        assertEquals(1, link.commandsWith("pveum user add cloudstack@pve").size());
    }

    @Test
    public void everyRoleIsGivenToTheUserAndTheTokenWithTheirPaths() {
        ScriptedSshLink link = node();

        ProxmoxApiAccess.create(open(link), "pve1", false);

        for (String grant : new String[] {"acl modify / --users cloudstack@pve --tokens", "PVEAuditor", "acl modify /vms", "PVEVMAdmin", "acl modify /storage", "PVEDatastoreAdmin",
            "acl modify /sdn/zones/localnetwork", "PVESDNUser", "acl modify /nodes/pve1", "CloudStackNet --propagate 0"}) {
            assertEquals(grant, 1, link.commandsWith(grant).size());
        }
        assertTrue(link.commandsWith("CloudStackHA").isEmpty());
    }

    @Test
    public void theHaRoleIsGrantedOnlyWhenCloudStackManagesHa() {
        ScriptedSshLink link = node();

        ProxmoxApiAccess.create(open(link), "pve1", true);

        assertEquals(1, link.commandsWith("role add CloudStackHA --privs 'Sys.Audit Sys.Console'").size());
        assertEquals(1, link.commandsWith("acl modify / --users cloudstack@pve --tokens 'cloudstack@pve!cs-pve1' --roles CloudStackHA --propagate 0").size());
    }

    @Test
    public void aFailureOtherThanAlreadyExistingStopsTheSetup() {
        ScriptedSshLink link = node();
        link.answers = command -> command.contains("user add") ? new ExecResult(1, "", "permission denied") : new ExecResult(0, "", "");
        try {
            ProxmoxApiAccess.create(open(link), "pve1", false);
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("permission denied"));
        }
    }

    @Test
    public void somethingThatIsNotATokenIsAnError() {
        ScriptedSshLink link = node();
        link.answers = command -> command.contains("user token add") ? new ExecResult(0, "not json", "") : new ExecResult(0, "", "");
        try {
            ProxmoxApiAccess.create(open(link), "pve1", false);
            fail();
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not a token"));
        }
    }

    @Test
    public void revokingRemovesTheNodesTokenAndForgivesOneThatIsGone() {
        ScriptedSshLink link = node();
        link.answers = command -> new ExecResult(1, "", "no such token 'cs-pve1'");

        ProxmoxApiAccess.revoke(open(link), "pve1");

        assertEquals(1, link.commandsWith("pveum user token remove cloudstack@pve cs-pve1").size());
    }
}

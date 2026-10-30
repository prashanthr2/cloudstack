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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Test;

/** Phase 9: the alias map of renamed volumes (design-phase9-storage-migration.md, section 10). */
public class ProxmoxVolumeAliasesTest {
    private static final String TEXT = "Holds the data volumes of CloudStack account 2.";
    private static final String OLD = "cs-7fd5565c:9101/vm-9101-disk-0.qcow2";
    private static final String NEW = "cs-7fd5565c:900000002/vm-900000002-disk-3.qcow2";

    @Test
    public void aDescriptionWithoutTheMarkerHasNoAliases() {
        assertTrue(ProxmoxVolumeAliases.parse(TEXT).isEmpty());
        assertTrue(ProxmoxVolumeAliases.parse(null).isEmpty());
    }

    @Test
    public void renderAndParseAreInverse() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(OLD, NEW);
        aliases.put("cs-aaaa:9102/vm-9102-disk-1.qcow2", "cs-aaaa:900000002/vm-900000002-disk-4.qcow2");

        String description = ProxmoxVolumeAliases.render(TEXT, aliases);

        assertTrue(description.startsWith(TEXT + "\n" + ProxmoxVolumeAliases.MARKER));
        assertEquals(aliases, ProxmoxVolumeAliases.parse(description));
        assertEquals(TEXT, ProxmoxVolumeAliases.textOf(description));
    }

    @Test
    public void renderingNoAliasesLeavesOnlyTheText() {
        String withAlias = ProxmoxVolumeAliases.render(TEXT, ProxmoxVolumeAliases.record(new LinkedHashMap<>(), OLD, NEW));

        assertEquals(TEXT, ProxmoxVolumeAliases.render(withAlias, new LinkedHashMap<>()));
    }

    @Test
    public void resolveFollowsTheAliasAndKeepsAnUnknownIdAsItIs() {
        Map<String, String> aliases = ProxmoxVolumeAliases.record(new LinkedHashMap<>(), OLD, NEW);

        assertEquals(NEW, ProxmoxVolumeAliases.resolve(aliases, OLD));
        assertEquals("cs-x:1/vm-1-disk-0.qcow2", ProxmoxVolumeAliases.resolve(aliases, "cs-x:1/vm-1-disk-0.qcow2"));
    }

    @Test
    public void aSecondRenameKeepsTheLookupToOneStepAndDropsTheStopOnTheWay() {
        String third = "cs-7fd5565c:900000002/vm-900000002-disk-5.qcow2";
        Map<String, String> aliases = ProxmoxVolumeAliases.record(new LinkedHashMap<>(), OLD, NEW);

        aliases = ProxmoxVolumeAliases.record(aliases, NEW, third);

        assertEquals(third, aliases.get(OLD));
        assertNull(aliases.get(NEW));
    }

    @Test
    public void anAliasThatStartsAtAnIdThatIsInUseAgainIsDropped() {
        // an earlier volume left "vm-121-disk-1" for the holder; a later volume of the VM is given that name again
        String reused = "cs-7fd5565c:121/vm-121-disk-1.qcow2";
        Map<String, String> aliases = ProxmoxVolumeAliases.record(new LinkedHashMap<>(), reused, NEW);

        aliases = ProxmoxVolumeAliases.record(aliases, OLD, reused);

        assertEquals(reused, aliases.get(OLD));
        assertEquals(reused, ProxmoxVolumeAliases.resolve(aliases, OLD));
        assertNull(aliases.get(reused));
    }

    @Test
    public void forgetRemovesAliasesFromAndToTheVolume() {
        Map<String, String> aliases = ProxmoxVolumeAliases.record(new LinkedHashMap<>(), OLD, NEW);
        aliases = ProxmoxVolumeAliases.record(aliases, "cs-7fd5565c:9103/vm-9103-disk-0.qcow2", "cs-7fd5565c:900000002/vm-900000002-disk-7.qcow2");

        assertEquals(1, ProxmoxVolumeAliases.forget(aliases, NEW).size());
        assertEquals(1, ProxmoxVolumeAliases.forget(aliases, OLD).size());
        assertEquals(2, ProxmoxVolumeAliases.forget(aliases, "cs-7fd5565c:1/none").size());
    }

    @Test
    public void aFullDescriptionIsRefusedAndCheckedBeforeARename() {
        Map<String, String> aliases = new LinkedHashMap<>();
        int n = 0;
        while (ProxmoxVolumeAliases.hasRoomFor(TEXT, aliases, "cs-7fd5565c-6c1e-3032-81ec-d404812915ab:" + (9000 + n) + "/vm-" + (9000 + n) + "-disk-0.qcow2",
                "cs-7fd5565c-6c1e-3032-81ec-d404812915ab:900000002/vm-900000002-disk-" + n + ".qcow2")) {
            aliases.put("cs-7fd5565c-6c1e-3032-81ec-d404812915ab:" + (9000 + n) + "/vm-" + (9000 + n) + "-disk-0.qcow2",
                    "cs-7fd5565c-6c1e-3032-81ec-d404812915ab:900000002/vm-900000002-disk-" + n + ".qcow2");
            n++;
        }
        assertTrue("room for a useful number of aliases: " + n, n >= 60);
        assertFalse(ProxmoxVolumeAliases.hasRoomFor(TEXT, aliases, OLD, NEW));
        try {
            ProxmoxVolumeAliases.render(TEXT, ProxmoxVolumeAliases.record(aliases, OLD, NEW));
            fail();
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage().contains("full"));
        }
    }
}

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Phase 9: the holder VM of an account (design-phase9-storage-migration.md, section 6). */
public class ProxmoxHolderVmsTest {
    private static final String NODE = "pve1";
    private static final long HOLDER = 900000002L;
    private static final String VOLUME = "cs-7fd5565c:900000002/vm-900000002-0f2a9c0e.qcow2";

    private ProxmoxApiClient client;
    private ProxmoxHolderVms holders;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        holders = new ProxmoxHolderVms(client, () -> 60);
    }

    @Test
    public void ensureHolderCreatesAProtectedEmptyVmWhenThereIsNone() {
        when(client.findVm(HOLDER)).thenReturn(null);
        when(client.createVm(eq(NODE), eq(HOLDER), anyMap())).thenReturn("UPID:create");

        ProxmoxApiClient.VmLocation location = holders.ensureHolder(2, NODE);

        assertEquals(NODE, location.getNode());
        assertEquals(HOLDER, location.getVmid());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(client).createVm(eq(NODE), eq(HOLDER), params.capture());
        assertEquals("1", params.getValue().get("protection"));
        assertEquals("cs-holder-2", params.getValue().get("name"));
        assertFalse("an empty VM: no disk", params.getValue().keySet().stream().anyMatch(k -> k.matches("(scsi|virtio|sata|ide)\\d+")));
        verify(client).waitForTask("UPID:create", 60);
    }

    @Test
    public void ensureHolderLeavesAHolderThatIsOnTheNodeAlone() {
        ProxmoxApiClient.VmLocation there = new ProxmoxApiClient.VmLocation(HOLDER, "cs-holder-2", NODE, "stopped");
        when(client.findVm(HOLDER)).thenReturn(there);

        assertEquals(there, holders.ensureHolder(2, NODE));

        verify(client, never()).createVm(anyString(), eq(HOLDER), anyMap());
        verify(client, never()).migrateVm(anyString(), eq(HOLDER), anyString());
    }

    @Test
    public void ensureHolderMovesAHolderThatIsOnAnotherNode() {
        when(client.findVm(HOLDER)).thenReturn(new ProxmoxApiClient.VmLocation(HOLDER, "cs-holder-2", "pve2", "stopped"));
        when(client.migrateVm("pve2", HOLDER, NODE)).thenReturn("UPID:migrate");

        ProxmoxApiClient.VmLocation location = holders.ensureHolder(2, NODE);

        assertEquals(NODE, location.getNode());
        verify(client).waitForTask("UPID:migrate", 60);
    }

    @Test
    public void ensureHolderRefusesAnAccountOutsideTheRange() {
        try {
            holders.ensureHolder(0, NODE);
            fail();
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage().contains("outside"));
        }
    }

    @Test
    public void adoptListsTheVolumeInTheFirstFreeEntry() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("unused0", "cs-a:900000002/vm-900000002-x.qcow2");
        config.put("unused1", "cs-a:900000002/vm-900000002-y.qcow2");
        config.put("unused3", "cs-a:900000002/vm-900000002-z.qcow2");
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(config);

        String entry = holders.adopt(new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped"), VOLUME);

        assertEquals("unused2", entry);
        verify(client).updateVmConfig(NODE, HOLDER, Collections.singletonMap("unused2", VOLUME));
    }

    @Test
    public void adoptTwiceChangesNothingTheSecondTime() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("unused4", VOLUME + ",size=1G");
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(config);

        assertEquals("unused4", holders.adopt(new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped"), VOLUME));

        verify(client, never()).updateVmConfig(anyString(), eq(HOLDER), anyMap());
    }

    @Test
    public void adoptFailsWhenAllEntriesAreUsed() {
        Map<String, String> config = new LinkedHashMap<>();
        for (int n = 0; n < ProxmoxHolderVms.MAX_UNUSED; n++) {
            config.put("unused" + n, "cs-a:900000002/vm-900000002-v" + n + ".qcow2");
        }
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(config);
        try {
            holders.adopt(new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped"), VOLUME);
            fail();
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage().contains("256"));
        }
    }

    @Test
    public void handBackReassignsTheInstancesEntryToTheHolderAndReturnsTheNewVolumeId() {
        String moved = "cs-b:900000002/vm-900000002-disk-0.qcow2";
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        after.put("unused0", moved);
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(before, after);
        when(client.reassignDisk(NODE, 9101, "unused0", HOLDER, "unused0")).thenReturn("UPID:move");

        assertEquals(moved, holders.handBack(NODE, 9101, "unused0", HOLDER));

        verify(client).waitForTask("UPID:move", 60);
    }

    @Test
    public void handBackGoesOnWhenTheTaskCouldNotBeFollowedButTheHolderListsTheVolume() {
        String moved = "cs-b:900000002/vm-900000002-disk-0.qcow2";
        Map<String, String> after = new LinkedHashMap<>();
        after.put("unused0", moved);
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(new LinkedHashMap<>(), after, after);
        when(client.reassignDisk(NODE, 9101, "unused0", HOLDER, "unused0")).thenReturn("UPID:move");
        org.mockito.Mockito.doThrow(new ProxmoxApiException("bad task id")).when(client).waitForTask("UPID:move", 60);

        assertEquals(moved, holders.handBack(NODE, 9101, "unused0", HOLDER));
    }

    @Test
    public void handBackStillFailsWhenTheTaskCouldNotBeFollowedAndNothingMoved() {
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(new LinkedHashMap<>());
        when(client.reassignDisk(NODE, 9101, "unused0", HOLDER, "unused0")).thenReturn("UPID:move");
        org.mockito.Mockito.doThrow(new ProxmoxApiException("bad task id")).when(client).waitForTask("UPID:move", 60);
        try {
            holders.handBack(NODE, 9101, "unused0", HOLDER);
            fail();
        } catch (ProxmoxApiException e) {
            assertEquals("bad task id", e.getMessage());
        }
    }

    @Test
    public void handBackFailsWhenTheHolderDoesNotListTheVolumeAfterwards() {
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(new LinkedHashMap<>());
        when(client.reassignDisk(NODE, 9101, "unused0", HOLDER, "unused0")).thenReturn("UPID:move");
        try {
            holders.handBack(NODE, 9101, "unused0", HOLDER);
            fail();
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage().contains("does not list"));
        }
    }

    @Test
    public void handToReassignsTheHoldersEntryToTheInstanceDrive() {
        Map<String, String> instance = new LinkedHashMap<>();
        instance.put("scsi1", "cs-b:9101/vm-9101-disk-0.qcow2,size=1G");
        when(client.getVmConfig(NODE, 9101)).thenReturn(instance);
        when(client.reassignDisk(NODE, HOLDER, "unused5", 9101, "scsi1")).thenReturn("UPID:move");

        assertEquals("cs-b:9101/vm-9101-disk-0.qcow2", holders.handTo(NODE, HOLDER, "unused5", 9101, "scsi1"));
    }

    @Test
    public void entryOfIgnoresOptionsAndOtherKeys() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("scsi0", VOLUME);
        config.put("unused2", VOLUME + ",size=1G");
        assertEquals("unused2", ProxmoxHolderVms.entryOf(config, VOLUME));
        assertNull(ProxmoxHolderVms.entryOf(config, "cs-a:900000002/vm-900000002-other.qcow2"));
    }

    @Test
    public void deleteListedRemovesTheEntryAndSaysSo() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("unused7", VOLUME);
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(config);

        assertTrue(holders.deleteListed(new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped"), VOLUME));

        verify(client).updateVmConfig(NODE, HOLDER, Collections.singletonMap("delete", "unused7"));
    }

    @Test
    public void protectionIsOffOnlyWhileTheEntryIsRemoved() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("unused7", VOLUME);
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(config);

        holders.deleteListed(new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped"), VOLUME);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(client);
        order.verify(client).updateVmConfig(NODE, HOLDER, Collections.singletonMap("protection", "0"));
        order.verify(client).updateVmConfig(NODE, HOLDER, Collections.singletonMap("delete", "unused7"));
        order.verify(client).updateVmConfig(NODE, HOLDER, Collections.singletonMap("protection", "1"));
    }

    @Test
    public void deleteListedDoesNothingForAVolumeThatIsNotListed() {
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(new LinkedHashMap<>());

        assertFalse(holders.deleteListed(new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped"), VOLUME));

        verify(client, never()).updateVmConfig(anyString(), eq(HOLDER), anyMap());
    }

    // ---- aliases ----

    private static final String OLD_ID = "cs-7fd5565c:9101/vm-9101-disk-0.qcow2";
    private static final String NEW_ID = "cs-7fd5565c:900000002/vm-900000002-disk-3.qcow2";

    private Map<String, String> holderWithAlias() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("description", ProxmoxVolumeAliases.render("text", ProxmoxVolumeAliases.record(new LinkedHashMap<>(), OLD_ID, NEW_ID)));
        return config;
    }

    @Test
    public void resolveAnswersWithTheRenamedVolume() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped");
        when(client.findVm(HOLDER)).thenReturn(holder);
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(holderWithAlias());

        assertEquals(NEW_ID, holders.resolve(2, OLD_ID));
        assertEquals("cs-7fd5565c:1/vm-1-x.qcow2", holders.resolve(2, "cs-7fd5565c:1/vm-1-x.qcow2"));
    }

    @Test
    public void resolveWithoutAHolderAnswersWithTheSameId() {
        when(client.findVm(HOLDER)).thenReturn(null);

        assertEquals(OLD_ID, holders.resolve(2, OLD_ID));
    }

    @Test
    public void recordAliasWritesTheDescriptionAndKeepsTheText() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped");
        Map<String, String> config = new LinkedHashMap<>();
        config.put("description", "Holds the data volumes.");
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(config);

        holders.recordAlias(holder, OLD_ID, NEW_ID);

        ArgumentCaptor<Map<String, String>> written = captor();
        verify(client).updateVmConfig(eq(NODE), eq(HOLDER), written.capture());
        String description = written.getValue().get("description");
        assertTrue(description.startsWith("Holds the data volumes.\n" + ProxmoxVolumeAliases.MARKER));
        assertEquals(NEW_ID, ProxmoxVolumeAliases.parse(description).get(OLD_ID));
    }

    @Test
    public void forgetAliasesWritesOnlyWhenSomethingChanges() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped");
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(holderWithAlias());

        holders.forgetAliases(holder, "cs-7fd5565c:5/none");
        verify(client, never()).updateVmConfig(anyString(), eq(HOLDER), anyMap());

        holders.forgetAliases(holder, NEW_ID);
        ArgumentCaptor<Map<String, String>> written = captor();
        verify(client).updateVmConfig(eq(NODE), eq(HOLDER), written.capture());
        assertTrue(ProxmoxVolumeAliases.parse(written.getValue().get("description")).isEmpty());
    }

    @Test
    public void handBackWithAliasRenamesAndThenRecordsTheAlias() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped");
        Map<String, String> before = new LinkedHashMap<>();
        before.put("description", "text");
        Map<String, String> after = new LinkedHashMap<>(before);
        after.put("unused0", NEW_ID);
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(before, before, after, before);
        when(client.reassignDisk(NODE, 9101, "scsi1", HOLDER, "unused0")).thenReturn("UPID:move");

        assertEquals(NEW_ID, holders.handBackWithAlias(holder, NODE, 9101, "scsi1", OLD_ID));

        verify(client).waitForTask("UPID:move", 60);
        ArgumentCaptor<Map<String, String>> written = captor();
        verify(client).updateVmConfig(eq(NODE), eq(HOLDER), written.capture());
        assertEquals(NEW_ID, ProxmoxVolumeAliases.parse(written.getValue().get("description")).get(OLD_ID));
    }

    @Test
    public void handBackWithAliasRefusesBeforeRenamingWhenTheDescriptionIsFull() {
        ProxmoxApiClient.VmLocation holder = new ProxmoxApiClient.VmLocation(HOLDER, "h", NODE, "stopped");
        Map<String, String> full = new LinkedHashMap<>();
        StringBuilder filler = new StringBuilder("text");
        while (filler.length() < ProxmoxVolumeAliases.MAX_LENGTH - 10) {
            filler.append(" filler");
        }
        full.put("description", filler.toString());
        when(client.getVmConfig(NODE, HOLDER)).thenReturn(full);
        try {
            holders.handBackWithAlias(holder, NODE, 9101, "scsi1", OLD_ID);
            fail();
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage().contains("renamed volumes"));
        }
        verify(client, never()).reassignDisk(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;

public class ProxmoxIsosTest {
    private static final String NODE = "pve1";
    private static final String DIRECTORY = "/var/lib/cloudstack-iso";
    private static final String ENTRY = ProxmoxStorageNames.isoSharedStorageId(DIRECTORY);
    private static final String RELATIVE = "template/tmpl/2/211/211-2-654b5686.iso";
    private static final String SOURCE_KEY = "3|10.0.32.4|/acs/secondary|" + RELATIVE;
    private static final String NAME = ProxmoxIsos.fileName(SOURCE_KEY, RELATIVE);

    private ProxmoxApiClient client;
    private ScriptedSshLink link;
    private ProxmoxIsos isos;
    private String isoPath = DIRECTORY;
    private boolean entryExists;
    private int logins;
    private final Map<String, ExecResult> replies = new LinkedHashMap<>();
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
            return new ExecResult(0, "", "");
        };
        when(client.findStorage(anyString())).thenAnswer(invocation -> entryExists ? new ProxmoxApiClient.StorageConfig(ENTRY, "dir", null, null, null, "iso", null) : null);
        when(client.getStorageStatus(eq(NODE), anyString())).thenReturn(new ProxmoxApiClient.StorageStatus(true, true, 1L, 1L, 1L));
        isos = new ProxmoxIsos(() -> client, NODE, () -> {
            logins++;
            return ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "pw", null), () -> link);
        }, new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> isoPath, millis -> pauses++);
    }

    private static TemplateObjectTO iso(String path) {
        NfsTO store = new NfsTO("nfs://10.0.32.4/acs/secondary", DataStoreRole.Image);
        store.setNfsVersion("3");
        TemplateObjectTO iso = mock(TemplateObjectTO.class);
        when(iso.getDataStore()).thenReturn(store);
        when(iso.getPath()).thenReturn(path);
        return iso;
    }

    private boolean ran(String text) {
        return !link.commandsWith(text).isEmpty();
    }

    private static void refused(Runnable action, String... words) {
        try {
            action.run();
            fail("no exception");
        } catch (ProxmoxApiException e) {
            for (String word : words) {
                assertTrue(e.getMessage(), e.getMessage().contains(word));
            }
        }
    }

    // ---- the name ----

    @Test
    public void theNameOfABindIsFromTheStoreAndTheFileAndKeepsTheExtensionProxmoxLists() {
        assertTrue(NAME, NAME.matches("cs-[0-9a-f]{16}\\.iso"));
        assertEquals(NAME, ProxmoxIsos.fileName(SOURCE_KEY, RELATIVE));
        assertNotEquals(NAME, ProxmoxIsos.fileName(SOURCE_KEY.replace("211", "212"), RELATIVE));
        assertNotEquals(NAME, ProxmoxIsos.fileName(SOURCE_KEY.replace("10.0.32.4", "10.0.32.5"), RELATIVE));
        assertTrue(ProxmoxIsos.fileName(SOURCE_KEY, "a/b.IMG").endsWith(".img"));
        assertEquals(NAME, isos.nameOf(iso(RELATIVE)));
    }

    // ---- making an ISO available ----

    @Test
    public void anIsoIsBoundFromAReadOnlyMountThatIsReleasedAtOnceAndTheVolumeIsInTheSharedEntry() {
        String volume = isos.volume(iso("/" + RELATIVE));

        assertEquals(ENTRY + ":iso/" + NAME, volume);
        verify(client).createSharedIsoStorage(ENTRY, DIRECTORY);
        String mount = link.commandsWith("mount -t nfs").get(0);
        assertTrue(mount, mount.contains("-o 'ro,vers=3,soft,timeo=30,retrans=2,nosharecache' '10.0.32.4:/acs/secondary'"));
        String directory = mount.substring(mount.indexOf('\'') + 1, mount.indexOf('\'', mount.indexOf('\'') + 1));
        String target = DIRECTORY + "/template/iso/" + NAME;
        String bind = link.commandsWith("mount --bind").get(0);
        assertTrue(bind, bind.contains("mkdir -p '" + DIRECTORY + "/template/iso'") && bind.contains("mountpoint -q '" + target + "'"));
        assertTrue(bind, bind.contains("mount --bind '" + directory + "/" + RELATIVE + "' '" + target + "'") && bind.contains("mount -o remount,bind,ro '" + target + "'"));
        assertTrue(ran("test -f '" + directory + "/" + RELATIVE + "'"));
        assertTrue(ran("test -s '" + target + "'"));
        // the temporary mount is released after the bind; the bind keeps the NFS mount alive
        assertTrue(link.commands.indexOf(bind) < link.commands.indexOf(link.commandsWith("umount '" + directory + "'").get(0)));
    }

    @Test
    public void theEntryIsNotCreatedAgainOnceItExistsAndAnEntryAnotherHostMadeAMomentAgoCounts() {
        entryExists = true;
        isos.volume(iso(RELATIVE));
        verify(client, never()).createSharedIsoStorage(anyString(), anyString());

        entryExists = false;
        doThrow(new ProxmoxApiException("storage ID '" + ENTRY + "' already defined")).when(client).createSharedIsoStorage(ENTRY, DIRECTORY);
        when(client.findStorage(anyString())).thenReturn(null, new ProxmoxApiClient.StorageConfig(ENTRY, "dir", null, null, null, "iso", null));
        assertEquals(ENTRY + ":iso/" + NAME, isos.volume(iso(RELATIVE)));
    }

    @Test
    public void anEntryThatIsNotActiveOnTheNodeFailsWithoutAnySshCommand() {
        when(client.getStorageStatus(eq(NODE), anyString())).thenReturn(new ProxmoxApiClient.StorageStatus(false, true, 0L, 0L, 0L));

        refused(() -> isos.volume(iso(RELATIVE)), "not active on node pve1");
        assertTrue(link.commands.isEmpty());
        assertEquals(ProxmoxIsos.ENTRY_ACTIVATION_ATTEMPTS, pauses);
    }

    @Test
    public void aStoreThatCannotBeMountedIsReportedWithTheReasonAndTheAdviceAndNothingIsBound() {
        replies.put("mount -t nfs", new ExecResult(32, "", "mount.nfs: access denied by server"));

        refused(() -> isos.volume(iso(RELATIVE)), "access denied by server", "Detach the ISO", RELATIVE);
        assertFalse(ran("mount --bind"));
    }

    @Test
    public void anIsoFileThatIsNotOnTheStoreIsReported() {
        replies.put("test -f '/mnt/css-tmp-", new ExecResult(1, "", ""));

        refused(() -> isos.volume(iso(RELATIVE)), "does not exist on the secondary store");
        assertFalse(ran("mount --bind"));
    }

    @Test
    public void aBindThatFailsOrCannotBeReadIsRemovedAgainAndReported() {
        replies.put("mount --bind", new ExecResult(32, "", "mount: wrong fs type"));
        refused(() -> isos.volume(iso(RELATIVE)), "wrong fs type", "Detach the ISO");
        assertTrue(ran("umount '" + DIRECTORY + "/template/iso/" + NAME + "'") && ran("rm -f '" + DIRECTORY + "/template/iso/" + NAME + "'"));

        link.commands.clear();
        replies.clear();
        replies.put("test -s ", new ExecResult(1, "", ""));
        refused(() -> isos.volume(iso(RELATIVE)), "cannot be read");
        assertTrue(ran("rm -f '" + DIRECTORY + "/template/iso/" + NAME + "'"));
    }

    @Test
    public void withoutAnSshLoginTheReasonSaysSoAndNothingIsChanged() {
        ProxmoxIsos without = new ProxmoxIsos(() -> client, NODE, () -> {
            throw new ProxmoxSshException("Proxmox host x has no SSH login");
        }, new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> DIRECTORY, millis -> { });

        refused(() -> without.volume(iso(RELATIVE)), "no SSH login", "Detach the ISO");
    }

    @Test
    public void anIsoThatCannotBeUsedIsRefusedBeforeAnythingHappens() {
        refused(() -> isos.volume(iso(RELATIVE.replace(".iso", ".properties"))), ".iso or .img");
        refused(() -> isos.volume(iso("template/../../etc/x.iso")), "Cannot find the ISO file");
        refused(() -> isos.volume(iso("a b/c.iso")), "relative path");
        refused(() -> isos.volume(iso("x;id.iso")), "relative path");
        refused(() -> isos.volume(iso(null)), "Cannot find the ISO file");
        TemplateObjectTO onPrimary = mock(TemplateObjectTO.class);
        when(onPrimary.getDataStore()).thenReturn(mock(PrimaryDataStoreTO.class));
        refused(() -> isos.volume(onPrimary), "NFS secondary");
        assertTrue(link.commands.isEmpty());
        verify(client, never()).createSharedIsoStorage(anyString(), anyString());
    }

    @Test
    public void aDirectoryThatIsNotAnAbsolutePathOfSafeCharactersIsRefused() {
        for (String bad : new String[] {"", "relative/dir", "/a b", "/a;reboot", "/a/../b", "/a/./b", "/a/..", "/"}) {
            isoPath = bad;
            refused(() -> isos.volume(iso(RELATIVE)), "proxmox.iso.path");
        }
        isoPath = DIRECTORY + "/";
        assertEquals(ENTRY + ":iso/" + NAME, isos.volume(iso(RELATIVE)));
        assertTrue(link.commandsWith("mount --bind").get(0).contains("'" + DIRECTORY + "/template/iso/" + NAME + "'"));
    }

    // ---- the binds that no VM names ----

    @Test
    public void theBindsThatNoVmConfigurationNamesAreRemovedOnceBeforeTheFirstBindAndNotAgain() {
        isos.volume(iso(RELATIVE));
        isos.volume(iso(RELATIVE));

        assertEquals(1, link.commandsWith("/proc/mounts").size());
        String sweep = link.commandsWith("/proc/mounts").get(0);
        assertTrue(sweep, sweep.contains("'" + DIRECTORY + "/template/iso/'") && sweep.contains("/etc/pve/nodes/*/qemu-server/*.conf"));
        assertTrue(sweep, sweep.contains("cs-????????????????.iso|cs-????????????????.img"));
        assertTrue(link.commands.indexOf(sweep) < link.commands.indexOf(link.commandsWith("mount --bind").get(0)));
    }

    @Test
    public void aFailureOfTheSweepIsNotFatalAndNotRepeated() {
        replies.put("/proc/mounts", new ExecResult(1, "", "awk: error"));

        assertEquals(ENTRY + ":iso/" + NAME, isos.volume(iso(RELATIVE)));
        isos.volume(iso(RELATIVE));
        assertEquals(1, link.commandsWith("/proc/mounts").size());
    }

    // ---- release ----

    @Test
    public void theBindIsRemovedWhenNoVmConfigurationNamesTheVolume() {
        isos.release(iso(RELATIVE));

        String release = link.commandsWith("grep -lF").get(0);
        String target = DIRECTORY + "/template/iso/" + NAME;
        assertTrue(release, release.contains("grep -lF -- '" + ENTRY + ":iso/" + NAME + "' /etc/pve/nodes/*/qemu-server/*.conf"));
        assertTrue(release, release.contains("then exit 0; fi") && release.contains("umount '" + target + "'") && release.contains("rm -f '" + target + "'"));
    }

    @Test
    public void aReleaseThatCannotBeDoneIsOnlyLogged() {
        replies.put("grep -lF", new ExecResult(1, "", "umount: busy"));
        isos.release(iso(RELATIVE));

        ProxmoxIsos without = new ProxmoxIsos(() -> client, NODE, () -> {
            throw new ProxmoxSshException("no SSH login");
        }, new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> DIRECTORY, millis -> { });
        without.release(iso(RELATIVE));
        isos.release(iso("../x.iso"));
        assertEquals(1, logins);
        verify(client, times(0)).createSharedIsoStorage(anyString(), anyString());
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.cloudstack.hypervisor.proxmox.ProxmoxConfig;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;

public class ProxmoxSecondaryMountsTest {
    private static final String URL = "nfs://10.0.32.4/acs/primary/lab/secondary";
    private static final String FILE = "/mnt/css-tmp-x/template/tmpl/1/3/t.qcow2";

    private ScriptedSshLink link;
    private ProxmoxSsh ssh;
    private String setting;
    private ProxmoxSecondaryMounts mounts;
    private ExecResult mountResult = new ExecResult(0, "", "");
    private ExecResult infoResult = new ExecResult(0, "", "");
    private ExecResult cleanupResult = new ExecResult(0, "", "");

    @Before
    public void setUp() {
        link = new ScriptedSshLink();
        link.answers = command -> {
            if (command.contains("mount -t nfs")) {
                return mountResult;
            }
            if (command.contains("qemu-img info")) {
                return infoResult;
            }
            if (command.startsWith("for d in")) {
                return cleanupResult;
            }
            return new ExecResult(0, "", "");
        };
        ssh = ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "pw", null), () -> link);
        setting = ProxmoxSecondaryMounts.DEFAULT_OPTIONS;
        mounts = new ProxmoxSecondaryMounts(() -> setting);
    }

    private static NfsTO store(String url, String version) {
        NfsTO store = new NfsTO(url, DataStoreRole.Image);
        store.setNfsVersion(version);
        return store;
    }

    private String mountCommand() {
        return link.commandsWith("mount -t nfs").get(0);
    }

    // ---- the setting ----

    @Test
    public void theDefaultOfTheSettingIsTheOneTheMountsUse() {
        assertEquals(ProxmoxConfig.SecondaryMountOptions.defaultValue(), ProxmoxSecondaryMounts.DEFAULT_OPTIONS);
        assertEquals(ProxmoxConfig.SecondaryMountOptions.key(), ProxmoxSecondaryMounts.SETTING);
        assertEquals("soft,timeo=30,retrans=2,nosharecache", ProxmoxSecondaryMounts.DEFAULT_OPTIONS);
    }

    @Test
    public void theDefaultOptionsAreAccepted() {
        assertEquals("soft,timeo=30,retrans=2,nosharecache", ProxmoxSecondaryMounts.checkOptions(ProxmoxSecondaryMounts.DEFAULT_OPTIONS));
    }

    @Test
    public void everyAllowedOptionIsAcceptedAndSpacesEmptyItemsAndRepeatsAreDropped() {
        assertEquals("hard,nosharecache,noatime,nolock,proto=tcp,timeo=600,retrans=2,nconnect=4,rsize=1048576,wsize=1048576",
                ProxmoxSecondaryMounts.checkOptions(" hard, nosharecache ,noatime,,nolock,proto=tcp,timeo=600,retrans=2,nconnect=4,rsize=1048576,wsize=1048576,hard "));
    }

    @Test
    public void anEmptySettingMeansTheSystemDefaults() {
        assertEquals("", ProxmoxSecondaryMounts.checkOptions(""));
        assertEquals("", ProxmoxSecondaryMounts.checkOptions(null));
        assertEquals("", ProxmoxSecondaryMounts.checkOptions(" , "));
    }

    @Test
    public void anOptionThatIsNotAllowedIsRefusedWithItsName() {
        for (String bad : new String[] {"ro", "rw", "vers=3", "nfsvers=3", "soft;id", "timeo=abc", "timeo=", "timeo=123456", "$(id)", "soft hard", "sec=sys",
                "proto=udp", "nosuid", "retrans=-1", "x=$(reboot)"}) {
            try {
                ProxmoxSecondaryMounts.checkOptions("soft," + bad);
                fail(bad + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("'" + bad + "'"));
                assertTrue(e.getMessage(), e.getMessage().contains(ProxmoxSecondaryMounts.SETTING));
            }
        }
    }

    @Test
    public void softAndHardTogetherAreRefused() {
        try {
            ProxmoxSecondaryMounts.checkOptions("soft,hard");
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("both soft and hard"));
        }
    }

    // ---- the mount of one operation ----

    @Test
    public void theStoreIsMountedInADirectoryOfItsOwnWithTheOptionsAndUnmountedAndRemovedAfterwards() {
        AtomicReference<String> seen = new AtomicReference<>();

        String result = mounts.withMount(ssh, store(URL, "3"), true, directory -> {
            seen.set(directory);
            ssh.sudo("ls " + directory, null, 1000);
            return "done";
        });

        assertEquals("done", result);
        String directory = seen.get();
        assertTrue(directory, directory.startsWith(ProxmoxSecondaryMounts.MOUNT_PREFIX));
        assertEquals("mkdir -p '" + directory + "' && timeout 60 mount -t nfs -o 'ro,vers=3,soft,timeo=30,retrans=2,nosharecache' "
                + "'10.0.32.4:/acs/primary/lab/secondary' '" + directory + "'", mountCommand());
        String release = link.commandsWith("umount '" + directory + "'").get(0);
        assertEquals("(timeout 30 umount '" + directory + "' || timeout 30 umount -l '" + directory + "') 2>/dev/null; rmdir '" + directory + "'", release);
        assertTrue(link.commands.indexOf(mountCommand()) < link.commands.indexOf("ls " + directory));
        assertTrue(link.commands.indexOf("ls " + directory) < link.commands.indexOf(release));
    }

    @Test
    public void aWritableMountIsRwAndTheVersionIsLeftOutWhenTheStoreHasNone() {
        mounts.withMount(ssh, store(URL, null), false, directory -> null);

        assertTrue(mountCommand(), mountCommand().contains("-o 'rw,soft,timeo=30,retrans=2,nosharecache'"));
        assertFalse(mountCommand(), mountCommand().contains("vers="));
    }

    @Test
    public void anEmptySettingMountsWithTheModeAndTheVersionOnly() {
        setting = "";

        mounts.withMount(ssh, store(URL, "4.1"), true, directory -> null);

        assertTrue(mountCommand(), mountCommand().contains("-o 'ro,vers=4.1' "));
    }

    @Test
    public void aChangeOfTheSettingAppliesToTheNextMount() {
        mounts.withMount(ssh, store(URL, null), true, directory -> null);
        setting = "hard";
        mounts.withMount(ssh, store(URL, null), true, directory -> null);

        List<String> commands = link.commandsWith("mount -t nfs");
        assertTrue(commands.get(0), commands.get(0).contains("-o 'ro,soft,"));
        assertTrue(commands.get(1), commands.get(1).contains("-o 'ro,hard'"));
    }

    @Test
    public void everyOperationGetsADirectoryOfItsOwn() {
        List<String> seen = new ArrayList<>();
        mounts.withMount(ssh, store(URL, null), true, seen::add);
        mounts.withMount(ssh, store(URL, null), true, seen::add);

        assertEquals(2, seen.size());
        assertNotEquals(seen.get(0), seen.get(1));
    }

    @Test
    public void theMountIsReleasedAndTheExceptionPassedOnWhenTheActionFails() {
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            mounts.withMount(ssh, store(URL, null), true, directory -> {
                seen.set(directory);
                throw new IllegalStateException("the copy failed");
            });
            fail("no exception");
        } catch (IllegalStateException e) {
            assertEquals("the copy failed", e.getMessage());
        }

        assertEquals(1, link.commandsWith("rmdir '" + seen.get() + "'").size());
    }

    @Test
    public void aMountThatFailsIsReportedWithTheReasonTheDirectoryIsRemovedAndTheActionDoesNotRun() {
        mountResult = new ExecResult(32, "", "mount.nfs: access denied by server while mounting 10.0.32.4:/acs");
        boolean[] ran = new boolean[1];

        try {
            mounts.withMount(ssh, store(URL, null), true, directory -> {
                ran[0] = true;
                return null;
            });
            fail("no exception");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("10.0.32.4:/acs/primary/lab/secondary"));
            assertTrue(e.getMessage(), e.getMessage().contains("access denied by server"));
        }

        assertFalse(ran[0]);
        assertEquals(1, link.commandsWith("rmdir '/mnt/css-tmp-").size());
    }

    @Test
    public void aReleaseThatFailsDoesNotHideTheResultOfTheAction() {
        link.answers = command -> command.contains("umount") ? new ExecResult(1, "", "target is busy") : new ExecResult(0, "", "");

        assertEquals("done", mounts.withMount(ssh, store(URL, null), true, directory -> "done"));
    }

    @Test
    public void aStoreAddressThatIsNotAServerAndExportIsRefusedBeforeAnyCommandRuns() {
        for (String url : new String[] {"nfs://10.0.0.1;reboot/export", "nfs://host/ex port", "nfs://host/../x", "nfs://host/x/..", "nfs://host", null, "x y"}) {
            try {
                mounts.withMount(ssh, store(url, null), true, directory -> null);
                fail(url + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(url + ": " + e.getMessage(), e.getMessage().contains("secondary store address"));
            }
        }
        assertTrue(link.commands.toString(), link.commands.isEmpty());
    }

    @Test
    public void anNfsVersionThatIsNotANumberIsRefusedBeforeAnyCommandRuns() {
        for (String version : new String[] {"3;id", "v3", "3.", "10"}) {
            try {
                mounts.withMount(ssh, store(URL, version), true, directory -> null);
                fail(version + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("NFS version"));
            }
        }
        assertTrue(link.commands.isEmpty());
    }

    @Test
    public void aSettingThatIsNotAllowedFailsTheMountBeforeAnyCommandRuns() {
        setting = "soft;reboot";

        try {
            mounts.withMount(ssh, store(URL, null), true, directory -> null);
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("soft;reboot"));
        }
        assertTrue(link.commands.isEmpty());
    }

    // ---- directories of operations that did not finish ----

    @Test
    public void oldMountDirectoriesAreCleanedUpTheFirstTimeOnly() {
        mounts.withMount(ssh, store(URL, null), true, directory -> null);
        mounts.withMount(ssh, store(URL, null), true, directory -> null);

        List<String> cleanups = link.commandsWith("css-tmp-*");
        assertEquals(1, cleanups.size());
        assertTrue(cleanups.get(0), cleanups.get(0).contains("-mmin +10"));
        assertTrue(cleanups.get(0), cleanups.get(0).contains("umount -l"));
        assertTrue(link.commands.indexOf(cleanups.get(0)) < link.commands.indexOf(mountCommand()));
    }

    @Test
    public void aCleanUpThatFailsDoesNotStopTheMount() {
        cleanupResult = new ExecResult(1, "", "find: /mnt: Input/output error");

        assertEquals("done", mounts.withMount(ssh, store(URL, null), true, directory -> "done"));
    }

    // ---- the check of an image ----

    private static String info(String format, long size, String extra) {
        return "{\"format\": \"" + format + "\", \"virtual-size\": " + size + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    @Test
    public void aQcow2ImageIsAcceptedAndItsSizeIsRead() {
        infoResult = new ExecResult(0, info("qcow2", 5242880000L, "\"format-specific\": {\"type\": \"qcow2\", \"data\": {\"compat\": \"1.1\"}}"), "");

        ProxmoxSecondaryMounts.ImageInfo image = mounts.checkImage(ssh, FILE);

        assertEquals("qcow2", image.getFormat());
        assertEquals(5242880000L, image.getVirtualSize());
        assertEquals("timeout 60 qemu-img info -U --output=json '" + FILE + "'", link.commandsWith("qemu-img info").get(0));
    }

    @Test
    public void aRawImageIsAccepted() {
        infoResult = new ExecResult(0, info("raw", 1048576, ""), "");

        assertEquals("raw", mounts.checkImage(ssh, FILE).getFormat());
    }

    @Test
    public void aNestedVirtualSizeIsNotTakenForTheSizeOfTheImage() {
        infoResult = new ExecResult(0, "{\"children\": [{\"virtual-size\": 522649088}], \"format\": \"qcow2\", \"virtual-size\": 5242880000}", "");

        assertEquals(5242880000L, mounts.checkImage(ssh, FILE).getVirtualSize());
    }

    @Test
    public void anImageWithABackingFileIsRefused() {
        infoResult = new ExecResult(0, info("qcow2", 1073741824, "\"backing-filename-format\": \"raw\", \"full-backing-filename\": \"/etc/hostname\", "
                + "\"backing-filename\": \"/etc/hostname\""), "");

        try {
            mounts.checkImage(ssh, FILE);
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("backing file"));
            assertTrue(e.getMessage(), e.getMessage().contains("/etc/hostname"));
        }
    }

    @Test
    public void anImageWithAnExternalDataFileIsRefused() {
        infoResult = new ExecResult(0, info("qcow2", 1073741824, "\"format-specific\": {\"type\": \"qcow2\", \"data\": {\"data-file\": \"/tmp/df.raw\", "
                + "\"data-file-raw\": true}}"), "");

        try {
            mounts.checkImage(ssh, FILE);
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("external data file"));
        }
    }

    @Test
    public void anImageOfAnotherFormatIsRefused() {
        for (String format : new String[] {"vmdk", "vpc", "qed", ""}) {
            infoResult = new ExecResult(0, info(format, 1048576, ""), "");
            try {
                mounts.checkImage(ssh, FILE);
                fail(format + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("only qcow2 and raw"));
            }
        }
    }

    @Test
    public void anImageWithoutASizeIsRefused() {
        infoResult = new ExecResult(0, "{\"format\": \"qcow2\"}", "");

        try {
            mounts.checkImage(ssh, FILE);
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no size"));
        }
    }

    @Test
    public void aFailedOrUnreadableInfoIsAnSshError() {
        infoResult = new ExecResult(1, "", "qemu-img: Could not open '" + FILE + "': No such file or directory");
        try {
            mounts.checkImage(ssh, FILE);
            fail("accepted");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("No such file or directory"));
        }

        infoResult = new ExecResult(0, "not json", "");
        try {
            mounts.checkImage(ssh, FILE);
            fail("accepted");
        } catch (ProxmoxSshException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("could not be read"));
        }
    }

    // ---- paths ----

    @Test
    public void aRelativePathBelowTheStoreIsAccepted() {
        assertEquals("template/tmpl/1/3/6ddd9e96-1804-4324-b39a-a04a2c5e127e.qcow2",
                ProxmoxSecondaryMounts.relativePath("template/tmpl/1/3/6ddd9e96-1804-4324-b39a-a04a2c5e127e.qcow2", "the template"));
        assertEquals("configdrive/i-2-94-VM.iso", ProxmoxSecondaryMounts.relativePath("configdrive/i-2-94-VM.iso", "the config drive"));
    }

    @Test
    public void aPathThatLeavesTheStoreOrCarriesShellCharactersIsRefused() {
        for (String bad : new String[] {"../x", "a/../b", "./a", "a/.", "/etc/passwd", "a b", "a;id", "a$(id)", "a/b/", "", null, "a//b", "a\nb"}) {
            try {
                ProxmoxSecondaryMounts.relativePath(bad, "the file");
                fail(bad + " was accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("the file"));
            }
        }
    }
}

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.RevertSnapshotCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;

public class ProxmoxVolumeSnapshotsTest {
    private static final String POOL = "7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final String VOLUME_UUID = "3b22f99a-1f4a-4fa3-90b1-447e57b1a432";
    private static final String PATH = "900000002/vm-900000002-" + VOLUME_UUID + ".qcow2";
    private static final String FILE = "/mnt/" + POOL + "/images/" + PATH;
    private static final String SNAPSHOT = "0e8b4a5c-1111-4222-8333-444455556666";
    private static final String UPID = "UPID:pve2:1:2:3:qmsnapshot:103:u:";
    private static final long GIB = 1024L * 1024 * 1024;
    private static final String SECONDARY_URL = "nfs://10.0.32.4/acs/primary/lab/secondary";
    private static final String FOLDER = "snapshots/2/94";

    private ProxmoxApiClient client;
    private ScriptedSshLink link;
    private ProxmoxVolumeSnapshots snapshots;
    /** Replies by a text in the command; the first that matches wins. */
    private final Map<String, ExecResult> replies = new LinkedHashMap<>();
    private long virtualSize = GIB;
    private String snapshotsInFile = "[]";
    private String map = "[]";

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
            if (command.startsWith("qemu-img info")) {
                return ok("{\"virtual-size\": " + virtualSize + ", \"snapshots\": " + snapshotsInFile + "}");
            }
            if (command.startsWith("stat -c %s")) {
                return ok("4096\n");
            }
            if (command.startsWith("qemu-img map")) {
                return ok(map);
            }
            return ok("");
        };
        snapshots = new ProxmoxVolumeSnapshots(() -> client, () -> ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "pw", null), () -> link),
                new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> 600);
        when(client.createSnapshot(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean())).thenReturn(UPID);
        when(client.deleteSnapshot(anyString(), anyLong(), anyString(), anyBoolean())).thenReturn(UPID);
    }

    private static ExecResult ok(String stdout) {
        return new ExecResult(0, stdout, "");
    }

    private static ExecResult failed(String stderr) {
        return new ExecResult(1, "", stderr);
    }

    private static VolumeObjectTO volume(String path, Long size, String vmName) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL);
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getPath()).thenReturn(path);
        when(volume.getSize()).thenReturn(size);
        when(volume.getDataStore()).thenReturn(pool);
        when(volume.getVmName()).thenReturn(vmName);
        return volume;
    }

    private static SnapshotObjectTO onPrimary(VolumeObjectTO volume, String path, String vmName) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL);
        SnapshotObjectTO snapshot = new SnapshotObjectTO();
        snapshot.setVolume(volume);
        snapshot.setDataStore(pool);
        snapshot.setPath(path);
        snapshot.setVmName(vmName);
        return snapshot;
    }

    private static NfsTO secondary(String url, String version) {
        NfsTO store = new NfsTO(url, DataStoreRole.Image);
        store.setNfsVersion(version);
        return store;
    }

    private static SnapshotObjectTO onSecondary(VolumeObjectTO volume, String path) {
        SnapshotObjectTO snapshot = new SnapshotObjectTO();
        snapshot.setVolume(volume);
        snapshot.setDataStore(secondary(SECONDARY_URL, "3"));
        snapshot.setPath(path);
        return snapshot;
    }

    private void vmIs(String status) {
        when(client.findVmByName("i-2-94-VM")).thenReturn(new ProxmoxApiClient.VmLocation(103, "i-2-94-VM", "pve2", status));
    }

    private Answer take(SnapshotObjectTO snapshot) {
        return snapshots.take(new CreateObjectCommand(snapshot));
    }

    private Answer backup(SnapshotObjectTO source, String folder) {
        SnapshotObjectTO target = new SnapshotObjectTO();
        target.setDataStore(secondary(SECONDARY_URL, "3"));
        target.setPath(folder);
        return snapshots.copy(new CopyCommand(source, target, 600, false));
    }

    /** The volume's file is not there yet, unless a test says it is. */
    private Answer createVolume(String backupPath, Long size) {
        replies.putIfAbsent("test -f '" + FILE + "'", failed(""));
        VolumeObjectTO dest = mock(VolumeObjectTO.class);
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL);
        when(dest.getDataStore()).thenReturn(pool);
        when(dest.getAccountId()).thenReturn(2L);
        when(dest.getUuid()).thenReturn(VOLUME_UUID);
        when(dest.getSize()).thenReturn(size);
        return snapshots.copy(new CopyCommand(onSecondary(null, backupPath), dest, 600, false));
    }

    private static String newPath(Answer answer) {
        return ((CreateObjectAnswer) answer).getData().getPath();
    }

    private boolean ran(String text) {
        return !link.commandsWith(text).isEmpty();
    }

    // ---- take ----

    @Test
    public void aDetachedVolumeGetsAnInternalSnapshotAndTheAnswerNamesIt() {
        Answer answer = take(onPrimary(volume(PATH, GIB, null), null, null));

        assertTrue(answer.getDetails(), answer.getResult());
        String name = newPath(answer).substring(PATH.length() + 1);
        assertTrue(newPath(answer), newPath(answer).startsWith(PATH + "/"));
        assertTrue(name, name.matches("[0-9a-f-]{36}"));
        assertTrue(link.commands.toString(), ran("test -f '" + FILE + "'"));
        assertEquals("qemu-img snapshot -c '" + name + "' '" + FILE + "'", link.commandsWith("snapshot -c").get(0));
        verify(client, never()).createSnapshot(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean());
    }

    @Test
    public void aVolumeOfARunningInstanceIsSnapshottedByProxmoxWithoutMemoryAndQemuImgTouchesNothing() {
        vmIs("running");

        Answer answer = take(onPrimary(volume(PATH, GIB, "i-2-94-VM"), null, "i-2-94-VM"));

        assertTrue(answer.getDetails(), answer.getResult());
        String name = newPath(answer).substring(PATH.length() + 1);
        assertTrue(name, name.matches("cs-[0-9a-f-]{36}"));
        verify(client).createSnapshot(eq("pve2"), eq(103L), eq(name), anyString(), eq(false));
        verify(client).waitForTask(eq(UPID), org.mockito.ArgumentMatchers.anyInt());
        assertTrue(link.commands.toString(), link.commands.isEmpty());
    }

    @Test
    public void aVolumeOfAStoppedInstanceIsSnapshottedDirectly() {
        vmIs("stopped");

        Answer answer = take(onPrimary(volume(PATH, GIB, "i-2-94-VM"), null, "i-2-94-VM"));

        assertTrue(answer.getDetails(), answer.getResult());
        assertTrue(ran("qemu-img snapshot -c"));
        verify(client, never()).createSnapshot(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean());
    }

    @Test
    public void aMissingFileFailsTheSnapshotAndNothingIsSnapshotted() {
        replies.put("test -f", failed(""));

        Answer answer = take(onPrimary(volume(PATH, GIB, null), null, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("does not exist"));
        assertFalse(ran("snapshot -c"));
    }

    @Test
    public void aVolumePathThatCouldCarryACommandIsRefusedBeforeAnythingRuns() {
        for (String bad : new String[] {"900000002/vm-1-a.qcow2; rm -rf /", "../etc/passwd", "900000002/vm-900000002-x.qcow2 && id", "900000002/vm-900000002-x.raw", null}) {
            Answer answer = take(onPrimary(volume(bad, GIB, null), null, null));
            assertFalse(String.valueOf(bad), answer.getResult());
        }
        assertTrue(link.commands.toString(), link.commands.isEmpty());
    }

    @Test
    public void aPoolUuidThatIsNotAUuidIsRefused() {
        VolumeObjectTO volume = volume(PATH, GIB, null);
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn("abc; reboot");
        when(volume.getDataStore()).thenReturn(pool);

        assertFalse(take(onPrimary(volume, null, null)).getResult());
        assertTrue(link.commands.isEmpty());
    }

    @Test
    public void anEncryptedVolumeIsRefused() {
        VolumeObjectTO volume = volume(PATH, GIB, null);
        when(volume.requiresEncryption()).thenReturn(true);

        Answer answer = take(onPrimary(volume, null, null));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("encrypted"));
    }

    // ---- back up ----

    @Test
    public void aDirectSnapshotIsCopiedToTheWritableMountAndThenRemovedFromPrimary() {
        snapshotsInFile = "[{\"name\": \"" + SNAPSHOT + "\"}]";

        Answer answer = backup(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null), FOLDER);

        assertTrue(answer.getDetails(), answer.getResult());
        String mountCommand = link.commandsWith("mount -t nfs").get(0);
        assertTrue(mountCommand, mountCommand.startsWith("mkdir -p '/mnt/css-tmp-"));
        assertTrue(mountCommand, mountCommand.contains(" && timeout 60 mount -t nfs -o 'rw,vers=3,soft,timeo=30,retrans=2,nosharecache' '10.0.32.4:/acs/primary/lab/secondary' '/mnt/css-tmp-"));
        String directory = mountCommand.substring(mountCommand.indexOf('\'') + 1, mountCommand.indexOf('\'', mountCommand.indexOf('\'') + 1));
        assertEquals("mkdir -p '" + directory + "/" + FOLDER + "'", link.commandsWith("mkdir -p '" + directory + "/" + FOLDER).get(0));
        assertEquals("qemu-img convert -O qcow2 -l snapshot.name='" + SNAPSHOT + "' '" + FILE + "' '" + directory + "/" + FOLDER + "/" + SNAPSHOT + "'",
                link.commandsWith("qemu-img convert").get(0));
        assertEquals("qemu-img snapshot -d '" + SNAPSHOT + "' '" + FILE + "'", link.commandsWith("snapshot -d").get(0));
        // the mount of this one operation is unmounted and removed after the copy, and before the snapshot is removed from primary
        String release = link.commandsWith("umount '" + directory + "'").get(0);
        assertTrue(release, release.endsWith("; rmdir '" + directory + "'"));
        assertTrue(link.commands.indexOf(link.commandsWith("qemu-img convert").get(0)) < link.commands.indexOf(release));
        SnapshotObjectTO result = (SnapshotObjectTO) ((CopyCmdAnswer) answer).getNewData();
        assertEquals(FOLDER + "/" + SNAPSHOT, result.getPath());
        assertEquals(Long.valueOf(4096), result.getPhysicalSize());
    }

    @Test
    public void theSnapshotOfARunningInstanceIsReadWithForceShareAndItsProxmoxSnapshotIsDeleted() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Arrays.asList("cs-" + SNAPSHOT));

        Answer answer = backup(onPrimary(volume(PATH, GIB, "i-2-94-VM"), PATH + "/cs-" + SNAPSHOT, "i-2-94-VM"), FOLDER);

        assertTrue(answer.getDetails(), answer.getResult());
        String convert = link.commandsWith("qemu-img convert").get(0);
        assertTrue(convert, convert.startsWith("qemu-img convert -O qcow2 -U -l snapshot.name='cs-" + SNAPSHOT + "' '" + FILE + "'"));
        assertTrue(convert, convert.endsWith("/" + FOLDER + "/" + SNAPSHOT + "'"));
        verify(client).deleteSnapshot("pve2", 103L, "cs-" + SNAPSHOT, false);
        assertFalse(ran("snapshot -d"));
        assertEquals(FOLDER + "/" + SNAPSHOT, ((SnapshotObjectTO) ((CopyCmdAnswer) answer).getNewData()).getPath());
    }

    @Test
    public void aFailedCopyRemovesTheHalfFileAndStillRemovesTheSnapshotFromPrimary() {
        snapshotsInFile = "[{\"name\": \"" + SNAPSHOT + "\"}]";
        replies.put("qemu-img convert", failed("No space left on device"));

        Answer answer = backup(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null), FOLDER);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("No space left on device"));
        assertEquals(1, link.commandsWith("rm -f '/mnt/css-tmp-").size());
        assertEquals("the mount is released when the copy failed", 1, link.commandsWith("rmdir '/mnt/css-tmp-").size());
        assertTrue(link.commands.toString(), ran("snapshot -d"));
    }

    @Test
    public void aFolderThatLeavesTheMountIsRefused() {
        for (String bad : new String[] {"../../etc", "snapshots/../../x", "/etc", "a b", "x;id", null}) {
            Answer answer = backup(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null), bad);
            assertFalse(String.valueOf(bad), answer.getResult());
        }
        assertFalse(ran("qemu-img convert"));
        assertFalse(ran("mount -t nfs"));
    }

    @Test
    public void aSnapshotNameThatIsNotOneThePluginMadeIsRefused() {
        Answer answer = backup(onPrimary(volume(PATH, GIB, null), PATH + "/x; reboot", null), FOLDER);

        assertFalse(answer.getResult());
        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void aSecondaryStoreAddressThatIsNotAServerAndExportIsRefused() {
        for (String url : new String[] {"nfs://10.0.0.1;reboot/export", "nfs://host/ex port", "nfs://host/../x", "nfs://host"}) {
            SnapshotObjectTO target = new SnapshotObjectTO();
            target.setDataStore(secondary(url, null));
            target.setPath(FOLDER);
            Answer answer = snapshots.copy(new CopyCommand(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null), target, 600, false));
            assertFalse(url, answer.getResult());
        }
        assertFalse(ran("mount -t nfs"));
    }

    @Test
    public void theNfsVersionIsLeftOutWhenTheStoreHasNone() {
        SnapshotObjectTO target = new SnapshotObjectTO();
        target.setDataStore(secondary(SECONDARY_URL, null));
        target.setPath(FOLDER);

        Answer answer = snapshots.copy(new CopyCommand(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null), target, 600, false));

        assertTrue(answer.getDetails(), answer.getResult());
        assertFalse(link.commandsWith("mount -t nfs").get(0), link.commandsWith("mount -t nfs").get(0).contains("vers="));
    }

    // ---- delete ----

    @Test
    public void aSnapshotThatIsStillInTheFileIsDeleted() {
        snapshotsInFile = "[{\"name\": \"" + SNAPSHOT + "\"}]";

        Answer answer = snapshots.delete(new DeleteCommand(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null)));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("qemu-img snapshot -d '" + SNAPSHOT + "' '" + FILE + "'", link.commandsWith("snapshot -d").get(0));
    }

    @Test
    public void aSnapshotThatIsGoneAlreadyIsDeleted() {
        Answer answer = snapshots.delete(new DeleteCommand(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null)));

        assertTrue(answer.getDetails(), answer.getResult());
        assertFalse(ran("snapshot -d"));
    }

    @Test
    public void aSnapshotOfAFileThatIsGoneIsDeleted() {
        replies.put("test -f", failed(""));

        assertTrue(snapshots.delete(new DeleteCommand(onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null))).getResult());
        assertFalse(ran("snapshot -d"));
    }

    @Test
    public void aProxmoxSnapshotOfAVolumeSnapshotIsDeletedThroughTheApi() {
        vmIs("running");
        when(client.listSnapshotNames("pve2", 103L)).thenReturn(Arrays.asList("cs-" + SNAPSHOT));

        Answer answer = snapshots.delete(new DeleteCommand(onPrimary(volume(PATH, GIB, "i-2-94-VM"), PATH + "/cs-" + SNAPSHOT, "i-2-94-VM")));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(client).deleteSnapshot("pve2", 103L, "cs-" + SNAPSHOT, false);
        assertTrue(link.commands.toString(), link.commands.isEmpty());
    }

    @Test
    public void aSnapshotOnTheSecondaryStoreIsNotDeletedByANode() {
        Answer answer = snapshots.delete(new DeleteCommand(onSecondary(volume(PATH, GIB, null), FOLDER + "/" + SNAPSHOT)));

        assertFalse(answer.getResult());
        assertTrue(link.commands.isEmpty());
    }

    // ---- volume from snapshot ----

    @Test
    public void aVolumeIsMadeUnderATemporaryNameAndMovedIntoPlace() {
        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, GIB);

        assertTrue(answer.getDetails(), answer.getResult());
        String convert = link.commandsWith("qemu-img convert").get(0);
        assertTrue(convert, convert.contains("/" + FOLDER + "/" + SNAPSHOT + "' '" + FILE + ".tmp'"));
        assertEquals("mv '" + FILE + ".tmp' '" + FILE + "'", link.commandsWith("mv ").get(0));
        assertTrue(link.commands.toString(), ran("mkdir -p '/mnt/" + POOL + "/images/900000002'"));
        assertFalse(link.commands.toString(), ran("qemu-img resize"));
        VolumeObjectTOAnswer created = new VolumeObjectTOAnswer(answer);
        assertEquals(PATH, created.volume.getPath());
        assertEquals(Long.valueOf(GIB), created.volume.getSize());
        assertEquals(Storage.ImageFormat.QCOW2, created.volume.getFormat());
    }

    @Test
    public void aCopyThatIsSmallerThanTheVolumeIsGrown() {
        virtualSize = GIB;

        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, 2 * GIB);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("qemu-img resize '" + FILE + ".tmp' " + 2 * GIB, link.commandsWith("qemu-img resize").get(0));
    }

    @Test
    public void aCopyThatIsBiggerIsCutWhenNothingIsStoredBeyondTheSize() {
        virtualSize = 2 * GIB;
        map = "[{\"start\": 0, \"length\": 1048576, \"data\": true}, {\"start\": 1048576, \"length\": 2146435072, \"data\": false}]";

        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, GIB);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("qemu-img resize --shrink '" + FILE + ".tmp' " + GIB, link.commandsWith("qemu-img resize").get(0));
    }

    @Test
    public void aCopyThatHoldsDataBeyondTheSizeIsNeverCutAndTheTemporaryFileGoes() {
        virtualSize = 2 * GIB;
        map = "[{\"start\": 0, \"length\": 1048576, \"data\": true}, {\"start\": 1572864000, \"length\": 1048576, \"data\": true}]";

        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, GIB);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("holds data beyond"));
        assertFalse(ran("--shrink"));
        assertFalse(ran("mv "));
        assertTrue(ran("rm -f '" + FILE + ".tmp'"));
    }

    @Test
    public void aVolumeThatExistsIsAnsweredAsItIsAndNotWrittenAgain() {
        replies.put("test -f '" + FILE + "'", ok(""));

        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, GIB);

        assertTrue(answer.getDetails(), answer.getResult());
        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void aBackupIsReadFromAReadOnlyMountThatIsUnmountedAfterwards() {
        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, GIB);

        assertTrue(answer.getDetails(), answer.getResult());
        assertTrue(link.commandsWith("mount -t nfs").get(0), link.commandsWith("mount -t nfs").get(0).contains("-o 'ro,vers=3,soft,timeo=30,retrans=2,nosharecache'"));
        assertEquals(1, link.commandsWith("rmdir '/mnt/css-tmp-").size());
    }

    @Test
    public void aBackupThatIsNotThereFailsTheVolume() {
        replies.put("test -f '/mnt/css-tmp-", failed(""));

        Answer answer = createVolume(FOLDER + "/" + SNAPSHOT, GIB);

        assertFalse(answer.getResult());
        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void aBackupPathThatLeavesTheMountIsRefusedForAVolume() {
        assertFalse(createVolume("../../root/x", GIB).getResult());
        assertFalse(createVolume("a/b;id", GIB).getResult());
        assertTrue(link.commands.isEmpty());
    }

    @Test
    public void aSnapshotIsNotCopiedToATemplate() {
        Answer answer = snapshots.copy(new CopyCommand(onSecondary(null, FOLDER + "/" + SNAPSHOT), new org.apache.cloudstack.storage.to.TemplateObjectTO(), 600, false));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("not supported"));
    }

    // ---- revert ----

    private Answer revert(Long size, String vmName) {
        SnapshotObjectTO snapshot = onSecondary(volume(PATH, size, vmName), FOLDER + "/" + SNAPSHOT);
        snapshot.setVmName(vmName);
        return snapshots.revert(new RevertSnapshotCommand(snapshot, null));
    }

    @Test
    public void aRevertWritesATemporaryCopyAndMovesItOverTheVolume() {
        Answer answer = revert(GIB, null);

        assertTrue(answer.getDetails(), answer.getResult());
        String convert = link.commandsWith("qemu-img convert").get(0);
        assertTrue(convert, convert.endsWith("/" + FOLDER + "/" + SNAPSHOT + "' '" + FILE + ".tmp'"));
        assertEquals("mv -f '" + FILE + ".tmp' '" + FILE + "'", link.commandsWith("mv ").get(0));
        assertTrue(link.commands.indexOf(convert) < link.commands.indexOf(link.commandsWith("mv ").get(0)));
    }

    @Test
    public void aRevertReadsTheBackupFromAReadOnlyMountThatIsUnmountedAfterwards() {
        Answer answer = revert(GIB, null);

        assertTrue(answer.getDetails(), answer.getResult());
        assertTrue(link.commandsWith("mount -t nfs").get(0), link.commandsWith("mount -t nfs").get(0).contains("-o 'ro,vers=3,soft,timeo=30,retrans=2,nosharecache'"));
        assertEquals(1, link.commandsWith("rmdir '/mnt/css-tmp-").size());
    }

    @Test
    public void aVolumeThatWasGrownSinceTheBackupIsGrownAgainByTheRevert() {
        virtualSize = GIB;

        Answer answer = revert(3 * GIB, null);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("qemu-img resize '" + FILE + ".tmp' " + 3 * GIB, link.commandsWith("qemu-img resize").get(0));
    }

    @Test
    public void aFailedRevertLeavesTheVolumeAsItWas() {
        replies.put("qemu-img convert", failed("Input/output error"));

        Answer answer = revert(GIB, null);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Input/output error"));
        assertFalse(ran("mv "));
        assertTrue(ran("rm -f '" + FILE + ".tmp'"));
    }

    @Test
    public void aRunningInstanceIsNotRevertedUnderQemu() {
        vmIs("running");

        Answer answer = revert(GIB, "i-2-94-VM");

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("stop it"));
        assertTrue(link.commands.isEmpty());
    }

    @Test
    public void aRevertNeedsTheBackupAndTheVolumeToExist() {
        replies.put("test -f", failed(""));

        Answer answer = revert(GIB, null);

        assertFalse(answer.getResult());
        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void aRevertFromASnapshotThatIsNotOnTheSecondaryStoreIsRefused() {
        SnapshotObjectTO snapshot = onPrimary(volume(PATH, GIB, null), PATH + "/" + SNAPSHOT, null);

        assertFalse(snapshots.revert(new RevertSnapshotCommand(snapshot, null)).getResult());
    }

    // ---- names ----

    @Test
    public void theSnapshotNameIsTheLastPartOfThePath() {
        assertEquals(SNAPSHOT, ProxmoxVolumeSnapshots.snapshotName(PATH + "/" + SNAPSHOT));
        assertEquals("cs-" + SNAPSHOT, ProxmoxVolumeSnapshots.snapshotName(PATH + "/cs-" + SNAPSHOT));
    }

    /** The volume that a copy answer carries. */
    private static final class VolumeObjectTOAnswer {
        private final VolumeObjectTO volume;

        private VolumeObjectTOAnswer(Answer answer) {
            assertNotNull(answer);
            this.volume = (VolumeObjectTO) ((CopyCmdAnswer) answer).getNewData();
        }
    }
}

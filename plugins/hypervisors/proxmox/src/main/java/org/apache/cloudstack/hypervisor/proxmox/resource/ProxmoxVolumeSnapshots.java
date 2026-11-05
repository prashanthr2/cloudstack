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

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.RevertSnapshotCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Volume snapshots (design-phase10-volume-snapshots.md): CloudStack's snapshot of one volume, copied to the secondary store. Proxmox has no API for
 * it, so the work is {@code qemu-img} on the node, over the SSH login of the host. A snapshot is an internal qcow2 snapshot of the volume's file that
 * lives only until it is backed up (as for KVM); the backup is a converted copy of it on secondary storage, which the plugin mounts for the one
 * operation (read-write to write the copy, read-only to read it; {@link ProxmoxSecondaryMounts}).
 * <p>
 * A volume that a running instance uses cannot be snapshotted with {@code qemu-img}, as QEMU has the file open, so Proxmox takes the snapshot of the VM
 * ("cs-&lt;uuid&gt;", without memory) and {@code qemu-img} only reads that snapshot, which is never written again. The VM snapshot is deleted after the
 * backup.
 * <p>
 * Every value that CloudStack gives (paths, names, the secondary store's address) ends up in a root shell, so each is checked against a strict
 * pattern first, and quoted as well.
 */
class ProxmoxVolumeSnapshots {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxVolumeSnapshots.class);

    /** The name of the Proxmox VM snapshot that stands for a volume snapshot of a running instance. */
    static final String VM_SNAPSHOT_PREFIX = "cs-";
    private static final String VM_SNAPSHOT_DESCRIPTION = "CloudStack volume snapshot";

    private static final Pattern POOL_UUID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    /** "&lt;vmid or holder&gt;/vm-&lt;vmid or holder&gt;-&lt;uuid or disk-n&gt;.qcow2" */
    private static final Pattern VOLUME_PATH = Pattern.compile("^[0-9]+/vm-[0-9]+-[A-Za-z0-9-]+\\.qcow2$");
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("^[A-Za-z0-9-]{1,40}$");

    /** One operation at a time per volume file. */
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Supplier<ProxmoxApiClient> clients;
    private final Supplier<ProxmoxSsh> logins;
    private final ProxmoxSecondaryMounts secondary;
    private final IntSupplier taskTimeoutSeconds;

    ProxmoxVolumeSnapshots(Supplier<ProxmoxApiClient> clients, Supplier<ProxmoxSsh> logins, ProxmoxSecondaryMounts secondary, IntSupplier taskTimeoutSeconds) {
        this.clients = clients;
        this.logins = logins;
        this.secondary = secondary;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    // ---- what each command is about ----

    static boolean isSnapshot(DataTO data) {
        return data instanceof SnapshotObjectTO;
    }

    private static boolean onPrimary(DataStoreTO store) {
        return store instanceof PrimaryDataStoreTO;
    }

    /** The copies of a snapshot: to secondary storage (the backup), and from there to a volume. */
    Answer copy(CopyCommand cmd) {
        DataTO destination = cmd.getDestTO();
        if (destination instanceof SnapshotObjectTO) {
            return backup(cmd);
        }
        if (destination instanceof VolumeObjectTO) {
            return createVolume(cmd);
        }
        return new CopyCmdAnswer("Copying a snapshot to " + (destination == null ? null : destination.getObjectType()) + " is not supported for Proxmox");
    }

    // ---- take ----

    /** The snapshot on primary storage. See the class comment for the two ways. */
    Answer take(CreateObjectCommand cmd) {
        SnapshotObjectTO snapshot = (SnapshotObjectTO) cmd.getData();
        try {
            VolumeObjectTO volume = snapshot.getVolume();
            String file = volumeFile(volume);
            if (!onPrimary(snapshot.getDataStore())) {
                return new CreateObjectAnswer("A snapshot is taken on the volume's primary storage");
            }
            return withLock(volume.getPath(), () -> {
                String name = UUID.randomUUID().toString();
                ProxmoxApiClient.VmLocation running = runningVm(snapshot.getVmName() != null ? snapshot.getVmName() : volume.getVmName());
                if (running != null) {
                    String vmSnapshot = VM_SNAPSHOT_PREFIX + name;
                    ProxmoxApiClient client = clients.get();
                    client.waitForTask(client.createSnapshot(running.getNode(), running.getVmid(), vmSnapshot, VM_SNAPSHOT_DESCRIPTION, false),
                            timeoutSeconds(cmd.getWait()));
                    LOGGER.info("Took snapshot {} of Proxmox VM {} for the volume {}", vmSnapshot, running.getVmid(), volume.getPath());
                    return answerOfTake(volume, vmSnapshot);
                }
                try (ProxmoxSsh ssh = logins.get()) {
                    requireFile(ssh, file);
                    runOrFail(ssh, "snapshot " + volume.getPath(), "qemu-img snapshot -c " + ProxmoxSsh.quote(name) + " " + ProxmoxSsh.quote(file));
                }
                LOGGER.info("Took snapshot {} of the volume {}", name, volume.getPath());
                return answerOfTake(volume, name);
            });
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("The snapshot of {} could not be taken: {}", describe(snapshot), e.getMessage());
            return new CreateObjectAnswer(e.getMessage());
        }
    }

    private static CreateObjectAnswer answerOfTake(VolumeObjectTO volume, String name) {
        SnapshotObjectTO taken = new SnapshotObjectTO();
        taken.setPath(volume.getPath() + "/" + name);
        return new CreateObjectAnswer(taken);
    }

    // ---- back up ----

    /**
     * Copies the snapshot to secondary storage and then removes it from primary, as KVM does (also when the copy failed), so that nothing stays in the
     * volume's file or in the VM's snapshot list.
     */
    Answer backup(CopyCommand cmd) {
        SnapshotObjectTO source = (SnapshotObjectTO) cmd.getSrcTO();
        DataTO destination = cmd.getDestTO();
        if (!(destination instanceof SnapshotObjectTO) || !(destination.getDataStore() instanceof NfsTO) || !onPrimary(source.getDataStore())) {
            return new CopyCmdAnswer("Proxmox backs a snapshot up from its primary storage to an NFS secondary store only");
        }
        SnapshotObjectTO target = (SnapshotObjectTO) destination;
        VolumeObjectTO volume = source.getVolume();
        String name = null;
        try {
            String file = volumeFile(volume);
            name = snapshotName(source.getPath());
            String copyName = name.startsWith(VM_SNAPSHOT_PREFIX) ? name.substring(VM_SNAPSHOT_PREFIX.length()) : name;
            String folder = relativePath(target.getPath(), "the snapshot folder on the secondary store");
            final String snapshotName = name;
            return withLock(volume.getPath(), () -> {
                try (ProxmoxSsh ssh = logins.get()) {
                    // read-write: the copy is written into the snapshot folder of the store
                    return secondary.withMount(ssh, (NfsTO) target.getDataStore(), false, mount -> {
                        String copy = mount + "/" + folder + "/" + copyName;
                        requireFile(ssh, file);
                        runOrFail(ssh, "create the snapshot folder", "mkdir -p " + ProxmoxSsh.quote(mount + "/" + folder));
                        try {
                            // -U: a running instance has the file open; what is read is a snapshot, which is never written
                            ExecResult converted = ssh.sudo("qemu-img convert -O qcow2" + (isOfVm(snapshotName) ? " -U" : "") + " -l snapshot.name="
                                    + ProxmoxSsh.quote(snapshotName) + " " + ProxmoxSsh.quote(file) + " " + ProxmoxSsh.quote(copy), null, timeoutMillis(cmd.getWait()));
                            if (!converted.isSuccess()) {
                                throw new ProxmoxSshException("qemu-img could not copy the snapshot " + snapshotName + ": " + converted.describeFailure());
                            }
                        } catch (RuntimeException e) {
                            ssh.sudo("rm -f " + ProxmoxSsh.quote(copy), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
                            throw e;
                        }
                        long size = Long.parseLong(runOrFail(ssh, "read the size of the copy", "stat -c %s " + ProxmoxSsh.quote(copy)).trim());
                        SnapshotObjectTO backedUp = new SnapshotObjectTO();
                        backedUp.setPath(folder + "/" + copyName);
                        backedUp.setPhysicalSize(size);
                        LOGGER.info("Copied snapshot {} of {} to {} on the secondary store ({} bytes)", snapshotName, volume.getPath(), backedUp.getPath(), size);
                        return new CopyCmdAnswer(backedUp);
                    });
                }
            });
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("The snapshot of {} could not be backed up: {}", describe(source), e.getMessage());
            return new CopyCmdAnswer(e.getMessage());
        } finally {
            if (name != null && volume != null) {
                removeFromPrimary(volume, name, source.getVmName(), cmd.getWait());
            }
        }
    }

    // ---- delete ----

    /** Deletes a snapshot that is still on primary storage (a backup that failed). One on the secondary store is removed by core with the secondary storage VM. */
    Answer delete(DeleteCommand cmd) {
        SnapshotObjectTO snapshot = (SnapshotObjectTO) cmd.getData();
        if (!onPrimary(snapshot.getDataStore())) {
            return new Answer(cmd, false, "A snapshot on a secondary store is deleted by the secondary storage VM, not by a Proxmox node");
        }
        try {
            VolumeObjectTO volume = snapshot.getVolume();
            volumeFile(volume);
            String name = snapshotName(snapshot.getPath());
            withLock(volume.getPath(), () -> {
                removeFromPrimaryOrFail(volume, name, snapshot.getVmName(), cmd.getWait());
                return null;
            });
            return new Answer(cmd, true, "Deleted the snapshot " + name);
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("The snapshot {} could not be deleted: {}", describe(snapshot), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    // ---- volume from snapshot ----

    /**
     * Makes a data volume of the backup on secondary storage: a file named like any data volume (design: behaviour.md, data volumes), written under a
     * temporary name and moved into place when it is complete. A copy that is not the size CloudStack records for the volume is brought to it.
     */
    Answer createVolume(CopyCommand cmd) {
        SnapshotObjectTO source = (SnapshotObjectTO) cmd.getSrcTO();
        DataTO destination = cmd.getDestTO();
        if (!(source.getDataStore() instanceof NfsTO) || !(destination instanceof VolumeObjectTO) || !onPrimary(destination.getDataStore())) {
            return new CopyCmdAnswer("Proxmox makes a volume of a snapshot on an NFS secondary store, on primary storage, only");
        }
        VolumeObjectTO dest = (VolumeObjectTO) destination;
        try {
            String backup = relativePath(source.getPath(), "the snapshot file on the secondary store");
            String pool = poolUuid(dest.getDataStore());
            String path = ProxmoxVmConfig.dataVolumePath(dest.getAccountId(), dest.getUuid());
            if (!VOLUME_PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("The volume " + dest.getUuid() + " has no name that is safe to use for a disk file");
            }
            String file = ProxmoxStorageNames.mountPath(pool) + "/images/" + path;
            return withLock(path, () -> {
                try (ProxmoxSsh ssh = logins.get()) {
                    // read-only: the backup is only read
                    return secondary.withMount(ssh, (NfsTO) source.getDataStore(), true, mount -> {
                        String from = mount + "/" + backup;
                        requireFile(ssh, from);
                        if (!ssh.sudo("test -f " + ProxmoxSsh.quote(file), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess()) {
                            String temporary = file + ".tmp";
                            runOrFail(ssh, "create the folder of the volume", "mkdir -p " + ProxmoxSsh.quote(StringUtils.substringBeforeLast(file, "/")));
                            try {
                                convertTo(ssh, from, temporary, dest.getSize(), cmd.getWait());
                                runOrFail(ssh, "move the volume into place", "mv " + ProxmoxSsh.quote(temporary) + " " + ProxmoxSsh.quote(file));
                            } catch (RuntimeException e) {
                                ssh.sudo("rm -f " + ProxmoxSsh.quote(temporary), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
                                throw e;
                            }
                            LOGGER.info("Created the volume {} from the snapshot {}", path, backup);
                        } else {
                            LOGGER.info("The volume {} exists already, a repeated command is answered with it", path);
                        }
                        VolumeObjectTO created = new VolumeObjectTO();
                        created.setPath(path);
                        created.setSize(details(ssh, file, false).virtualSize);
                        created.setFormat(Storage.ImageFormat.QCOW2);
                        return new CopyCmdAnswer(created);
                    });
                }
            });
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("A volume could not be made of the snapshot {}: {}", source.getPath(), e.getMessage());
            return new CopyCmdAnswer(e.getMessage());
        }
    }

    // ---- revert ----

    /**
     * Puts the data of the backup back into the volume: a converted copy next to the file is moved over it when it is complete, so a failure leaves the
     * volume as it was. Core allows it for a stopped instance only; a running one is refused here too, as the file would be replaced under QEMU. The
     * internal snapshots in the old file go with it.
     * <p>
     * The copy keeps the size of the backup, which is the size of the volume when the snapshot was taken: after a successful revert core sets the size
     * recorded for the volume to the size of the snapshot ({@code SnapshotManagerImpl.updateVolumeSizeAndPrimaryStorageCount}), so a volume that was grown
     * after the snapshot is the smaller size again, as with KVM. Growing the file to the size CloudStack has before the revert would leave the file bigger
     * than the database says (lab, 2026-11-05).
     */
    Answer revert(RevertSnapshotCommand cmd) {
        SnapshotObjectTO snapshot = cmd.getData();
        try {
            if (!(snapshot.getDataStore() instanceof NfsTO)) {
                return new Answer(cmd, false, "Proxmox reverts a volume from a snapshot on an NFS secondary store only");
            }
            VolumeObjectTO volume = snapshot.getVolume();
            String file = volumeFile(volume);
            String backup = relativePath(snapshot.getPath(), "the snapshot file on the secondary store");
            String vmName = snapshot.getVmName() != null ? snapshot.getVmName() : volume.getVmName();
            if (runningVm(vmName) != null) {
                return new Answer(cmd, false, "The instance " + vmName + " is running: stop it before the volume is reverted");
            }
            return withLock(volume.getPath(), () -> {
                try (ProxmoxSsh ssh = logins.get()) {
                    // read-only: the backup is only read
                    secondary.withMount(ssh, (NfsTO) snapshot.getDataStore(), true, mount -> {
                        String from = mount + "/" + backup;
                        requireFile(ssh, from);
                        requireFile(ssh, file);
                        String temporary = file + ".tmp";
                        try {
                            convertTo(ssh, from, temporary, null, cmd.getWait());
                            runOrFail(ssh, "replace the volume", "mv -f " + ProxmoxSsh.quote(temporary) + " " + ProxmoxSsh.quote(file));
                        } catch (RuntimeException e) {
                            ssh.sudo("rm -f " + ProxmoxSsh.quote(temporary), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
                            throw e;
                        }
                        return null;
                    });
                }
                LOGGER.info("Reverted the volume {} to the snapshot {}", volume.getPath(), backup);
                return new Answer(cmd, true, "Reverted the volume " + volume.getPath());
            });
        } catch (ProxmoxApiException | ProxmoxSshException | IllegalArgumentException e) {
            LOGGER.error("The volume of {} could not be reverted: {}", describe(snapshot), e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }

    // ---- the pieces ----

    /** Copies a backup to {@code target} and brings it to the volume size CloudStack has (when it has one). */
    private void convertTo(ProxmoxSsh ssh, String from, String target, Long wantedSize, long waitSeconds) {
        ExecResult converted = ssh.sudo("qemu-img convert -O qcow2 " + ProxmoxSsh.quote(from) + " " + ProxmoxSsh.quote(target), null, timeoutMillis(waitSeconds));
        if (!converted.isSuccess()) {
            throw new ProxmoxSshException("qemu-img could not copy " + from + ": " + converted.describeFailure());
        }
        if (wantedSize != null && wantedSize > 0) {
            fitToSize(ssh, target, wantedSize);
        }
    }

    /**
     * The copy has the size the volume had when it was backed up. A bigger volume (the volume was grown since, or its offering is bigger) is grown to
     * its size. A copy that is bigger than the size wanted is cut only when nothing is stored beyond that size (lab check V11: the area a snapshot did
     * not have is empty); otherwise it fails and nothing is cut.
     */
    private void fitToSize(ProxmoxSsh ssh, String file, long wantedSize) {
        long size = details(ssh, file, false).virtualSize;
        if (size == wantedSize) {
            return;
        }
        if (size < wantedSize) {
            runOrFail(ssh, "grow the copy", "qemu-img resize " + ProxmoxSsh.quote(file) + " " + wantedSize);
            return;
        }
        if (hasDataBeyond(ssh, file, wantedSize)) {
            throw new ProxmoxSshException(String.format("The copy is %d bytes and CloudStack records %d, and the copy holds data beyond that size: it is not cut", size, wantedSize));
        }
        runOrFail(ssh, "cut the copy", "qemu-img resize --shrink " + ProxmoxSsh.quote(file) + " " + wantedSize);
    }

    private boolean hasDataBeyond(ProxmoxSsh ssh, String file, long size) {
        String map = runOrFail(ssh, "map the copy", "qemu-img map --output=json " + ProxmoxSsh.quote(file));
        try {
            for (JsonNode extent : new ObjectMapper().readTree(map)) {
                if (extent.path("data").asBoolean(false) && extent.path("start").asLong() + extent.path("length").asLong() > size) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new ProxmoxSshException("The map of " + file + " could not be read: " + e.getMessage(), e);
        }
    }

    private static final class ImageDetails {
        private final long virtualSize;
        private final Set<String> snapshots;

        private ImageDetails(long virtualSize, Set<String> snapshots) {
            this.virtualSize = virtualSize;
            this.snapshots = snapshots;
        }
    }

    private ImageDetails details(ProxmoxSsh ssh, String file, boolean shared) {
        String json = runOrFail(ssh, "read " + file, "qemu-img info --output=json" + (shared ? " -U " : " ") + ProxmoxSsh.quote(file));
        try {
            JsonNode info = new ObjectMapper().readTree(json);
            Set<String> names = new HashSet<>();
            for (JsonNode snapshot : info.path("snapshots")) {
                names.add(snapshot.path("name").asText());
            }
            return new ImageDetails(info.path("virtual-size").asLong(), names);
        } catch (IOException e) {
            throw new ProxmoxSshException("The information of " + file + " could not be read: " + e.getMessage(), e);
        }
    }

    /** Removes the snapshot from primary storage after a backup; a failure is logged only, as the backup is what counts and a leftover is removed later. */
    private void removeFromPrimary(VolumeObjectTO volume, String name, String vmName, long waitSeconds) {
        try {
            withLock(volume.getPath(), () -> {
                removeFromPrimaryOrFail(volume, name, vmName, waitSeconds);
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("The snapshot {} of {} stays on primary storage, delete it by hand or delete the snapshot in CloudStack: {}", name, volume.getPath(),
                    e.getMessage());
        }
    }

    /** A snapshot that is gone already is deleted. */
    private void removeFromPrimaryOrFail(VolumeObjectTO volume, String name, String vmName, long waitSeconds) {
        ProxmoxApiClient.VmLocation running = isOfVm(name) ? runningOrStoppedVm(StringUtils.defaultIfBlank(vmName, volume.getVmName())) : null;
        if (isOfVm(name) && running != null) {
            ProxmoxApiClient client = clients.get();
            if (client.listSnapshotNames(running.getNode(), running.getVmid()).contains(name)) {
                client.waitForTask(client.deleteSnapshot(running.getNode(), running.getVmid(), name, false), timeoutSeconds(waitSeconds));
                LOGGER.info("Deleted snapshot {} of Proxmox VM {}", name, running.getVmid());
            }
            return;
        }
        String file = volumeFile(volume);
        try (ProxmoxSsh ssh = logins.get()) {
            if (!ssh.sudo("test -f " + ProxmoxSsh.quote(file), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess()) {
                return;
            }
            if (details(ssh, file, true).snapshots.contains(name)) {
                runOrFail(ssh, "delete the snapshot " + name, "qemu-img snapshot -d " + ProxmoxSsh.quote(name) + " " + ProxmoxSsh.quote(file));
                LOGGER.info("Deleted snapshot {} of the volume {}", name, volume.getPath());
            }
        }
    }

    // ---- checks, quoting and small helpers ----

    private static boolean isOfVm(String snapshotName) {
        return snapshotName.startsWith(VM_SNAPSHOT_PREFIX);
    }

    /** The file of a volume on the node, after the pool and the path have been checked. */
    static String volumeFile(VolumeObjectTO volume) {
        if (volume == null || !onPrimary(volume.getDataStore())) {
            throw new IllegalArgumentException("The snapshot has no volume on a primary storage");
        }
        if (volume.requiresEncryption()) {
            throw new IllegalArgumentException("Snapshots of encrypted volumes are not supported for Proxmox");
        }
        String path = volume.getPath();
        if (path == null || !VOLUME_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("The volume path " + path + " is not a Proxmox qcow2 disk (expected <vmid>/vm-<vmid>-<name>.qcow2)");
        }
        return ProxmoxStorageNames.mountPath(poolUuid(volume.getDataStore())) + "/images/" + path;
    }

    private static String poolUuid(DataStoreTO store) {
        String uuid = ((PrimaryDataStoreTO) store).getUuid();
        if (uuid == null || !POOL_UUID.matcher(uuid).matches()) {
            throw new IllegalArgumentException("The primary storage uuid " + uuid + " is not one this plugin uses");
        }
        return uuid;
    }

    /** The name after the last "/" of a snapshot path. */
    static String snapshotName(String path) {
        String name = StringUtils.substringAfterLast(StringUtils.defaultString(path), "/");
        if (!SNAPSHOT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("The snapshot path " + path + " does not end in a snapshot name this plugin made");
        }
        return name;
    }

    private static String relativePath(String path, String what) {
        return ProxmoxSecondaryMounts.relativePath(path, what);
    }

    /** The VM when it is running, else null (a volume of a stopped or a missing VM is not open anywhere). */
    private ProxmoxApiClient.VmLocation runningVm(String vmName) {
        ProxmoxApiClient.VmLocation vm = runningOrStoppedVm(vmName);
        return vm != null && vm.isRunning() ? vm : null;
    }

    private ProxmoxApiClient.VmLocation runningOrStoppedVm(String vmName) {
        return StringUtils.isBlank(vmName) ? null : clients.get().findVmByName(vmName);
    }

    private void requireFile(ProxmoxSsh ssh, String file) {
        if (!ssh.sudo("test -f " + ProxmoxSsh.quote(file), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess()) {
            throw new ProxmoxSshException("The file " + file + " does not exist on the Proxmox node");
        }
    }

    private String runOrFail(ProxmoxSsh ssh, String what, String command) {
        ExecResult result = ssh.sudo(command, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess()) {
            throw new ProxmoxSshException("Could not " + what + ": " + result.describeFailure());
        }
        return result.getStdout();
    }

    private int timeoutSeconds(long commandWaitSeconds) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(taskTimeoutSeconds.getAsInt(), commandWaitSeconds));
    }

    private int timeoutMillis(long commandWaitSeconds) {
        return (int) Math.min(Integer.MAX_VALUE, timeoutSeconds(commandWaitSeconds) * 1000L);
    }

    private static <T> T withLock(String volumePath, Supplier<T> action) {
        ReentrantLock lock = LOCKS.computeIfAbsent(volumePath, path -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static String describe(SnapshotObjectTO snapshot) {
        return snapshot.getVolume() == null ? "a snapshot without a volume" : snapshot.getVolume().getPath();
    }
}

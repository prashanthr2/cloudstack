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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The holder VM of an account (design-phase9-storage-migration.md, section 6): an empty VM, never started, with the vmid
 * {@link ProxmoxVmConfig#holderVmId(long)} that already owns the account's data volumes. Proxmox hands a disk to another VM only when both
 * are VMs, so a data volume that a live move left owned by an instance goes back to its holder through this VM's "unusedN" entries.
 * It is created on first use with {@code protection=1} (lab M8), and moved to the node of the instance for a hand-over (lab M6).
 */
final class ProxmoxHolderVms {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxHolderVms.class);

    /** Proxmox has "unused0" to "unused255". */
    static final int MAX_UNUSED = 256;
    private static final String HOLDER_MEMORY_MIB = "16";

    private static final Map<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final ProxmoxApiClient client;
    private final IntSupplier taskTimeoutSeconds;

    ProxmoxHolderVms(ProxmoxApiClient client, IntSupplier taskTimeoutSeconds) {
        this.client = client;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    /** One hand-over at a time per holder: its configuration is changed by several steps and may be on one node only. */
    <T> T withLock(long holderVmid, Supplier<T> action) {
        ReentrantLock lock = LOCKS.computeIfAbsent(holderVmid, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The holder VM of the account on {@code node}: created there when it does not exist, moved there (a move of its configuration, its disks
     * are on shared storage) when it is on another node.
     */
    ProxmoxApiClient.VmLocation ensureHolder(long accountId, String node) {
        long holder = ProxmoxVmConfig.holderVmId(accountId);
        ProxmoxApiClient.VmLocation location = client.findVm(holder);
        if (location == null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("name", "cs-holder-" + accountId);
            params.put("memory", HOLDER_MEMORY_MIB);
            params.put("protection", "1");
            params.put("tags", "cloudstack-data");
            params.put("description", "Holds the data volumes of CloudStack account " + accountId + ". Never started; do not delete or edit.");
            client.waitForTask(client.createVm(node, holder, params), taskTimeoutSeconds.getAsInt());
            LOGGER.info("Created the holder VM {} for account {} on node {}", holder, accountId, node);
            return new ProxmoxApiClient.VmLocation(holder, "cs-holder-" + accountId, node, "stopped");
        }
        if (!node.equals(location.getNode())) {
            client.waitForTask(client.migrateVm(location.getNode(), holder, node), taskTimeoutSeconds.getAsInt());
            LOGGER.info("Moved the holder VM {} from node {} to {}", holder, location.getNode(), node);
            return new ProxmoxApiClient.VmLocation(holder, location.getName(), node, location.getStatus());
        }
        return location;
    }

    /** The volume id of a config value: the part before the first comma ("storage:vmid/name,size=1G"). */
    static String volumeIdOf(String configValue) {
        if (configValue == null) {
            return null;
        }
        int comma = configValue.indexOf(',');
        return comma < 0 ? configValue : configValue.substring(0, comma);
    }

    /** The "unusedN" entry of the configuration that holds the volume, or null. */
    static String entryOf(Map<String, String> config, String volumeId) {
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (entry.getKey().matches("unused\\d+") && volumeId.equals(volumeIdOf(entry.getValue()))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The first "unusedN" that the configuration does not use. */
    static String freeEntry(Map<String, String> config) {
        for (int n = 0; n < MAX_UNUSED; n++) {
            if (!config.containsKey("unused" + n)) {
                return "unused" + n;
            }
        }
        throw new ProxmoxApiException("The holder VM already lists " + MAX_UNUSED + " volumes, which is all a Proxmox VM can list");
    }

    /**
     * Puts a volume that is owned by the account number (every Phase 7 volume) on the holder's list, so that it can be moved. The entry
     * is made at once and an existing one is reused, so a repeated command does nothing more. Returns the entry.
     */
    String adopt(ProxmoxApiClient.VmLocation holder, String volumeId) {
        Map<String, String> config = client.getVmConfig(holder.getNode(), holder.getVmid());
        String existing = entryOf(config, volumeId);
        if (existing != null) {
            return existing;
        }
        String entry = freeEntry(config);
        client.updateVmConfig(holder.getNode(), holder.getVmid(), Collections.singletonMap(entry, volumeId));
        LOGGER.info("Listed {} as {} of the holder VM {}", volumeId, entry, holder.getVmid());
        return entry;
    }

    /**
     * Hands the volume that the instance lists as {@code entry} ("unusedN", what Proxmox records when a drive of a running VM is removed,
     * lab M5) to the holder. Both VMs must be on the node. Returns the volume id the volume now has, "&lt;pool&gt;:&lt;holder&gt;/vm-&lt;holder&gt;-disk-&lt;n&gt;.qcow2".
     */
    String handBack(String node, long instanceVmid, String entry, long holderVmid) {
        String holderEntry = freeEntry(client.getVmConfig(node, holderVmid));
        client.waitForTask(client.reassignDisk(node, instanceVmid, entry, holderVmid, holderEntry), taskTimeoutSeconds.getAsInt());
        String volumeId = volumeIdOf(client.getVmConfig(node, holderVmid).get(holderEntry));
        if (volumeId == null) {
            throw new ProxmoxApiException("Proxmox handed " + entry + " of VM " + instanceVmid + " to the holder VM " + holderVmid + " but it does not list it");
        }
        LOGGER.info("Handed {} of VM {} to the holder VM {}: {}", entry, instanceVmid, holderVmid, volumeId);
        return volumeId;
    }

    /**
     * Hands the volume that the holder lists as {@code entry} to the instance as {@code drive} (a running instance plugs it in, lab M5).
     * Returns the volume id it now has.
     */
    String handTo(String node, long holderVmid, String entry, long instanceVmid, String drive) {
        client.waitForTask(client.reassignDisk(node, holderVmid, entry, instanceVmid, drive), taskTimeoutSeconds.getAsInt());
        String volumeId = volumeIdOf(client.getVmConfig(node, instanceVmid).get(drive));
        if (volumeId == null) {
            throw new ProxmoxApiException("Proxmox handed " + entry + " of the holder VM " + holderVmid + " to VM " + instanceVmid + " but it has no " + drive);
        }
        LOGGER.info("Handed {} of the holder VM {} to VM {} as {}: {}", entry, holderVmid, instanceVmid, drive, volumeId);
        return volumeId;
    }

    // ---- aliases of renamed volumes (design-phase9-storage-migration.md, section 10) ----

    private String descriptionOf(ProxmoxApiClient.VmLocation holder) {
        return client.getVmConfig(holder.getNode(), holder.getVmid()).get("description");
    }

    private void writeAliases(ProxmoxApiClient.VmLocation holder, String description, Map<String, String> aliases) {
        client.updateVmConfig(holder.getNode(), holder.getVmid(), Collections.singletonMap("description", ProxmoxVolumeAliases.render(description, aliases)));
    }

    /**
     * What a volume id stands for now: the id it was renamed to, or the id itself. The account's holder VM is only looked up, never created, and a
     * volume with no holder (nothing was ever renamed) is its own answer.
     */
    String resolve(long accountId, String volumeId) {
        return resolve(client.findVm(ProxmoxVmConfig.holderVmId(accountId)), volumeId);
    }

    /** As above for a holder that is already known (null: the account has none). */
    String resolve(ProxmoxApiClient.VmLocation holder, String volumeId) {
        if (holder == null) {
            return volumeId;
        }
        return ProxmoxVolumeAliases.resolve(ProxmoxVolumeAliases.parse(descriptionOf(holder)), volumeId);
    }

    /**
     * What a path that CloudStack still has stands for now, when the pool is not known (a live move command names no source pool): the id of the
     * first alias whose old id ends with the path, resolved, or null.
     */
    String resolveByPath(long accountId, String path) {
        ProxmoxApiClient.VmLocation holder = client.findVm(ProxmoxVmConfig.holderVmId(accountId));
        if (holder == null || path == null) {
            return null;
        }
        Map<String, String> aliases = ProxmoxVolumeAliases.parse(descriptionOf(holder));
        for (String oldId : aliases.keySet()) {
            if (oldId.endsWith(":" + path)) {
                return ProxmoxVolumeAliases.resolve(aliases, oldId);
            }
        }
        return null;
    }

    /** Fails before a rename if the holder's description cannot take one more alias, so that no volume is renamed without a way back. */
    void checkRoomForAlias(ProxmoxApiClient.VmLocation holder, String oldVolumeId, String expectedNewVolumeId) {
        String description = descriptionOf(holder);
        if (!ProxmoxVolumeAliases.hasRoomFor(description, ProxmoxVolumeAliases.parse(description), oldVolumeId, expectedNewVolumeId)) {
            throw new ProxmoxApiException("The holder VM " + holder.getVmid() + " already lists as many renamed volumes as its description can hold; "
                    + "detach volumes of this account while their instances are running, or ask an administrator to look at the holder VM");
        }
    }

    /** Records that {@code oldVolumeId} is now {@code newVolumeId}. One retry: a rename without its alias would lose the volume for core. */
    void recordAlias(ProxmoxApiClient.VmLocation holder, String oldVolumeId, String newVolumeId) {
        for (int attempt = 1; ; attempt++) {
            try {
                String description = descriptionOf(holder);
                writeAliases(holder, description, ProxmoxVolumeAliases.record(ProxmoxVolumeAliases.parse(description), oldVolumeId, newVolumeId));
                LOGGER.info("Recorded the alias {} -> {} on the holder VM {}", oldVolumeId, newVolumeId, holder.getVmid());
                return;
            } catch (ProxmoxApiException e) {
                if (attempt == 2) {
                    LOGGER.error("The volume {} is now {} but the alias could not be recorded on the holder VM {}: {}", oldVolumeId, newVolumeId,
                            holder.getVmid(), e.getMessage());
                    throw e;
                }
            }
        }
    }

    /** Removes the aliases that start at or lead to the volume (it was deleted, or core stores its new path now). */
    void forgetAliases(ProxmoxApiClient.VmLocation holder, String volumeId) {
        String description = descriptionOf(holder);
        Map<String, String> aliases = ProxmoxVolumeAliases.parse(description);
        Map<String, String> remaining = ProxmoxVolumeAliases.forget(aliases, volumeId);
        if (remaining.size() != aliases.size()) {
            writeAliases(holder, description, remaining);
        }
    }

    /**
     * {@link #handBack} for a moment when core cannot be told the new path: the alias is checked for room first and recorded afterwards.
     * Returns the new volume id.
     */
    String handBackWithAlias(ProxmoxApiClient.VmLocation holder, String instanceNode, long instanceVmid, String entry, String oldVolumeId) {
        checkRoomForAlias(holder, oldVolumeId, oldVolumeId.substring(0, oldVolumeId.indexOf(':') + 1) + holder.getVmid() + "/vm-" + holder.getVmid() + "-disk-999.qcow2");
        String newVolumeId = handBack(instanceNode, instanceVmid, entry, holder.getVmid());
        recordAlias(holder, oldVolumeId, newVolumeId);
        return newVolumeId;
    }

    /**
     * Deletes a volume that the holder lists: removing its "unusedN" entry removes the file too, and leaves no dangling entry (a plain delete of
     * the image would). Returns false when the holder does not list it.
     */
    boolean deleteListed(ProxmoxApiClient.VmLocation holder, String volumeId) {
        Map<String, String> config = client.getVmConfig(holder.getNode(), holder.getVmid());
        String entry = entryOf(config, volumeId);
        if (entry == null) {
            return false;
        }
        client.updateVmConfig(holder.getNode(), holder.getVmid(), Collections.singletonMap("delete", entry));
        LOGGER.info("Deleted {} through {} of the holder VM {}", volumeId, entry, holder.getVmid());
        return true;
    }

    /** Removes the entry of a volume whose file is gone (a live move deleted it). Does nothing when the holder does not list it or the file is there. */
    void dropDanglingEntry(ProxmoxApiClient.VmLocation holder, String volumeId) {
        if (client.getImageInfo(holder.getNode(), volumeId) == null) {
            deleteListed(holder, volumeId);
        }
    }
}

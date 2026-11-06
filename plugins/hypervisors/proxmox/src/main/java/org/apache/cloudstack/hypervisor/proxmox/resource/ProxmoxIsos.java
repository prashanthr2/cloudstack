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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.to.NfsTO;

/**
 * An ISO of the secondary store as a CD-ROM of a Proxmox VM (design-phase11-secondary-mounts.md, step 3). Proxmox accepts a CD drive only as a volume of a
 * storage entry, and the plugin makes no entry that mounts the secondary store (a dead export would hang the nodes' own daemons), so the ISO file is
 * <b>bind-mounted</b> from a short-lived read-only mount of the store onto a file of one small directory entry, {@code csiso-...}, that is {@code shared 1}
 * (the same files on every node, so a VM with an ISO can still migrate online). The bind keeps the NFS mount alive on its own; the directory of the mount is
 * unmounted again at once (lab M7). The ISO is then {@code csiso-...:iso/cs-<ISO uuid>.iso}, attached through the API like any volume; the file is named
 * after the ISO's UUID in CloudStack so that it can be traced from either side.
 * <p>
 * A bind is made when an ISO is attached, when a VM with an ISO starts (so a start after a node reboot works) and on the destination of a migration; it
 * is removed when no VM configuration <b>of this node</b> names it any more: after the ISO is detached, after the VM moved to another node or was deleted,
 * and after a start that no longer lists it. The binds that no configuration of the node names are also removed whenever this agent makes a bind, except
 * those made in the last minutes (their drive may not be written yet). An ISO whose mount or bind cannot be made fails the command with the reason.
 * <p>
 * Every value that CloudStack gives ends up in a root shell, so each is checked against a strict pattern first and quoted as well.
 */
class ProxmoxIsos {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxIsos.class);

    static final int ENTRY_ACTIVATION_ATTEMPTS = 15;
    static final long ENTRY_ACTIVATION_WAIT_MS = 2000;
    static final String SETTING = "proxmox.iso.path";
    private static final String ISO_DIR = "/template/iso/";
    private static final String FILE_PREFIX = "cs-";
    private static final int UNMOUNT_TIMEOUT_SECONDS = 30;
    private static final Pattern ISO_PATH = Pattern.compile("^(/[A-Za-z0-9._-]+)+$");
    private static final Pattern UUID_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern NODE_NAME = Pattern.compile("^[A-Za-z0-9.-]{1,64}$");
    /** A bind made less than this long ago is not swept: the command that made it may not have written the drive yet. */
    private static final long SWEEP_GRACE_MS = 10 * 60 * 1000L;

    private final Supplier<ProxmoxApiClient> clients;
    private final String node;
    private final Supplier<ProxmoxSsh> logins;
    private final ProxmoxSecondaryMounts mounts;
    private final Supplier<String> isoPath;
    private final LongConsumer pauser;
    private final Map<String, Long> recentBinds = new ConcurrentHashMap<>();

    ProxmoxIsos(Supplier<ProxmoxApiClient> clients, String node, Supplier<ProxmoxSsh> logins, ProxmoxSecondaryMounts mounts, Supplier<String> isoPath,
            LongConsumer pauser) {
        this.clients = clients;
        this.node = node;
        this.logins = logins;
        this.mounts = mounts;
        this.isoPath = isoPath;
        this.pauser = pauser;
    }

    /** An ISO as CloudStack names it: the store, the file below it, and the name of its bind. */
    private static final class Iso {
        private final NfsTO store;
        private final String relative;
        private final String name;

        private Iso(NfsTO store, String relative, String name) {
            this.store = store;
            this.relative = relative;
            this.name = name;
        }
    }

    /**
     * The name of the bind: "cs-" and the UUID of the ISO in CloudStack, with the extension Proxmox lists (.iso or .img). An ISO without a UUID gets the
     * first 16 characters of the SHA-256 of the store and file instead.
     */
    static String fileName(String uuid, String sourceKey, String file) {
        String extension = file.toLowerCase(Locale.ROOT).endsWith(".img") ? ".img" : ".iso";
        if (uuid != null && UUID_NAME.matcher(uuid).matches()) {
            return FILE_PREFIX + uuid + extension;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sourceKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return FILE_PREFIX + hex + extension;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fileName(String sourceKey, String file) {
        return fileName(null, sourceKey, file);
    }

    /** The files of the shared entry that the drives of a VM configuration name (an ISO that this class bound), as "cs-....iso". */
    static List<String> boundNames(Map<String, String> config) {
        List<String> names = new ArrayList<>();
        for (String drive : ProxmoxVmConfig.CDROM_DRIVES) {
            String value = config.get(drive);
            if (value != null && value.startsWith(ProxmoxStorageNames.ISO_SHARED_STORAGE_ID_PREFIX)) {
                String file = StringUtils.substringBefore(StringUtils.substringAfter(value, ":iso/"), ",");
                if (file.startsWith(FILE_PREFIX) && UUID_NAME.matcher(StringUtils.substringBeforeLast(file, ".")).matches()) {
                    names.add(file);
                }
            }
        }
        return names;
    }

    private Iso parse(TemplateObjectTO iso) {
        if (!(iso.getDataStore() instanceof NfsTO)) {
            throw new ProxmoxApiException("The ISO " + iso.getPath() + " is not on an NFS secondary store; only those are supported for Proxmox");
        }
        NfsTO store = (NfsTO) iso.getDataStore();
        String path = StringUtils.removeStart(StringUtils.defaultString(iso.getPath()), "/");
        String file = StringUtils.substringAfterLast(path, "/");
        if (StringUtils.isBlank(path) || path.contains("..")) {
            throw new ProxmoxApiException(String.format("Cannot find the ISO file from the secondary store %s and the path %s", store.getUrl(), iso.getPath()));
        }
        String lower = StringUtils.defaultIfEmpty(file, path).toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".iso") && !lower.endsWith(".img")) {
            throw new ProxmoxApiException("Proxmox lists only files ending in .iso or .img as ISOs, not " + StringUtils.defaultIfEmpty(file, path));
        }
        try {
            String relative = ProxmoxSecondaryMounts.relativePath(path, "the ISO file");
            return new Iso(store, relative, fileName(iso.getUuid(), ProxmoxTemplates.sourceKey(store, relative), relative));
        } catch (IllegalArgumentException e) {
            throw new ProxmoxApiException(e.getMessage(), e);
        }
    }

    /**
     * The name of the file that this ISO has in the shared entry, which is how a drive of a VM is recognised as holding it. Pure: nothing is made. Throws
     * for an ISO this class cannot make available.
     */
    String nameOf(TemplateObjectTO iso) {
        return parse(iso).name;
    }

    private String directory() {
        String path = StringUtils.removeEnd(StringUtils.defaultString(isoPath.get()).trim(), "/");
        if (!ISO_PATH.matcher(path).matches() || path.contains("/./") || path.contains("/../") || path.endsWith("/.") || path.endsWith("/..")) {
            throw new ProxmoxApiException("The setting " + SETTING + " (" + path + ") must be an absolute path of letters, digits, '.', '_' and '-'");
        }
        return path;
    }

    /**
     * Makes the ISO available on this node and returns its Proxmox volume ("&lt;entry&gt;:iso/&lt;file&gt;"): the entry exists and is active, and the file
     * is bound from the secondary store (nothing is done again for a bind that exists). Throws with the reason, and the advice to detach the ISO, when
     * that cannot be done.
     */
    String volume(TemplateObjectTO iso) {
        Iso parsed = parse(iso);
        String directory = directory();
        String entry = ProxmoxStorageNames.isoSharedStorageId(directory);
        ProxmoxApiClient client = clients.get();
        ensureEntry(client, entry, directory);
        if (!waitForStorage(client, entry)) {
            throw new ProxmoxApiException(String.format("Proxmox storage %s for the ISOs (%s) is not active on node %s", entry, directory, node));
        }
        String target = directory + ISO_DIR + parsed.name;
        try (ProxmoxSsh ssh = logins.get()) {
            recentBinds.put(parsed.name, System.currentTimeMillis());
            sweep(ssh, directory);
            mounts.withMount(ssh, parsed.store, true, mount -> {
                String from = mount + "/" + parsed.relative;
                if (!ssh.sudo("test -f " + ProxmoxSsh.quote(from), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess()) {
                    throw new ProxmoxSshException("The ISO file " + parsed.relative + " does not exist on the secondary store");
                }
                bind(ssh, from, directory, target);
                return null;
            });
        } catch (ProxmoxSshException | IllegalArgumentException e) {
            throw new ProxmoxApiException(String.format("Proxmox node %s could not make the ISO %s of the secondary store %s available: %s. Detach the ISO from the "
                    + "instance and try again", node, parsed.relative, parsed.store.getUrl(), e.getMessage()), e);
        }
        return entry + ":iso/" + parsed.name;
    }

    /** Binds the file onto the target (a read-only bind) unless the target is a mount point already, and checks that it reads. */
    private void bind(ProxmoxSsh ssh, String from, String directory, String target) {
        String quotedTarget = ProxmoxSsh.quote(target);
        ExecResult bound = ssh.sudo("mkdir -p " + ProxmoxSsh.quote(directory + "/template/iso") + " && (mountpoint -q " + quotedTarget
                + " || (touch " + quotedTarget + " && mount --bind " + ProxmoxSsh.quote(from) + " " + quotedTarget + " && mount -o remount,bind,ro " + quotedTarget + "))", null,
                ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!bound.isSuccess()) {
            unbind(ssh, target);
            throw new ProxmoxSshException("Could not bind the ISO file onto " + target + ": " + bound.describeFailure());
        }
        ExecResult read = ssh.sudo("mountpoint -q " + quotedTarget + " && test -s " + quotedTarget, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!read.isSuccess()) {
            unbind(ssh, target);
            throw new ProxmoxSshException("The ISO file bound onto " + target + " cannot be read (is it empty?)");
        }
    }

    private static void unbind(ProxmoxSsh ssh, String target) {
        String quoted = ProxmoxSsh.quote(target);
        ssh.sudo("(mountpoint -q " + quoted + " && (timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount " + quoted + " || timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount -l " + quoted
                + ")); rm -f " + quoted, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
    }

    /** The configurations of the VMs of this node: a bind is only used by a VM of the node it is on. */
    private String configs() {
        if (!NODE_NAME.matcher(node).matches()) {
            throw new ProxmoxApiException("The Proxmox node name " + node + " is not one this plugin can look up VM configurations for");
        }
        return ProxmoxSsh.quote("/etc/pve/nodes/" + node + "/qemu-server/") + "*.conf";
    }

    /**
     * After an ISO is detached: removes its bind from this node when no VM configuration of the node names it (one VM that still has the ISO keeps it
     * for them all). Best effort: a failure is logged and leaves the bind for the sweep.
     */
    void release(TemplateObjectTO iso) {
        try {
            releaseFiles(Collections.singletonList(parse(iso).name));
        } catch (ProxmoxApiException e) {
            LOGGER.warn("Could not remove the bind of an ISO on node {}: {}", node, e.getMessage());
        }
    }

    /**
     * Removes the binds of these files ("cs-....iso") from this node unless a VM configuration of the node still names them: after a VM left the node, was
     * deleted, or started without them. Best effort, as {@link #release(TemplateObjectTO)}.
     */
    void releaseFiles(Collection<String> names) {
        if (names.isEmpty()) {
            return;
        }
        try {
            String directory = directory();
            String configs = configs();
            try (ProxmoxSsh ssh = logins.get()) {
                for (String name : names) {
                    if (!name.matches("cs-[A-Za-z0-9-]+\\.(iso|img)")) {
                        continue;
                    }
                    String volume = ProxmoxStorageNames.isoSharedStorageId(directory) + ":iso/" + name;
                    String target = directory + ISO_DIR + name;
                    ExecResult result = ssh.sudo("if grep -lF -- " + ProxmoxSsh.quote(volume) + " " + configs + " >/dev/null 2>&1; then exit 0; fi; "
                            + "if mountpoint -q " + ProxmoxSsh.quote(target) + "; then timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount " + ProxmoxSsh.quote(target)
                            + " || timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount -l " + ProxmoxSsh.quote(target) + "; fi; rm -f " + ProxmoxSsh.quote(target), null,
                            ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
                    if (!result.isSuccess()) {
                        LOGGER.warn("Could not remove the bind {} on node {}: {}", name, node, result.describeFailure());
                    }
                }
            }
        } catch (ProxmoxApiException | ProxmoxSshException e) {
            LOGGER.warn("Could not remove the ISO binds on node {}: {}", node, e.getMessage());
        }
    }

    /**
     * Whenever this agent makes a bind, the binds of this node that no VM configuration of the node names are removed (an ISO that was left attached when
     * its VM was deleted, or detached while the VM was stopped). The binds this agent made in the last minutes are kept, and other callers wait for the
     * sweep, so no bind that is being made is taken away.
     */
    private synchronized void sweep(ProxmoxSsh ssh, String directory) {
        long now = System.currentTimeMillis();
        recentBinds.values().removeIf(at -> now - at > SWEEP_GRACE_MS);
        String keep = recentBinds.isEmpty() ? "" : String.join("|", recentBinds.keySet()) + ") continue;; ";
        String base = directory + ISO_DIR;
        ExecResult result = ssh.sudo("for t in $(awk -v d=" + ProxmoxSsh.quote(base) + " 'index($2, d) == 1 { print $2 }' /proc/mounts); do b=${t##*/}; "
                + "case \"$b\" in " + keep + "cs-*.iso|cs-*.img) ;; *) continue;; esac; "
                + "grep -lF -- \":iso/$b\" " + configs() + " >/dev/null 2>&1 || "
                + "{ timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount \"$t\" || timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount -l \"$t\"; rm -f \"$t\"; }; done", null,
                ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess()) {
            LOGGER.warn("Could not remove the unused ISO binds on node {}: {}", node, result.describeFailure());
        }
    }

    /** Creates the entry unless it exists; one that another host's command created a moment ago counts as existing. */
    private void ensureEntry(ProxmoxApiClient client, String id, String directory) {
        if (client.findStorage(id) != null) {
            return;
        }
        try {
            client.createSharedIsoStorage(id, directory);
            LOGGER.info("Created Proxmox storage {} for the ISOs in {}", id, directory);
        } catch (ProxmoxApiException e) {
            if (client.findStorage(id) == null) {
                throw e;
            }
        }
    }

    private boolean waitForStorage(ProxmoxApiClient client, String storageId) {
        for (int attempt = 0; attempt < ENTRY_ACTIVATION_ATTEMPTS; attempt++) {
            try {
                if (client.getStorageStatus(node, storageId).isActive()) {
                    return true;
                }
            } catch (ProxmoxApiException e) {
                LOGGER.debug("Storage {} is not ready on node {} yet: {}", storageId, node, e.getMessage());
            }
            pauser.accept(ENTRY_ACTIVATION_WAIT_MS);
        }
        return false;
    }
}

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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.to.NfsTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The secondary store as the plugin reaches it from a node (design-phase11-secondary-mounts.md): a plain NFS mount made over SSH for one operation, in a
 * directory of its own, and unmounted and removed when the operation is over. It is not a Proxmox storage entry, so Proxmox's own daemons never poll it,
 * and nothing stays mounted that a dead export could hang.
 * <p>
 * The mount options come from the global setting {@code proxmox.secondary.mount.options} (default {@link #DEFAULT_OPTIONS}); the plugin adds {@code ro} or
 * {@code rw} and the NFS version of the store, which the setting may not carry. Every value ends up in a root shell, so the options, the address of the
 * store and the paths are checked against strict patterns first, and quoted as well.
 */
class ProxmoxSecondaryMounts {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxSecondaryMounts.class);

    /** The default of {@code proxmox.secondary.mount.options}: a dead export gives an I/O error (after about 100 s) and does not hang. */
    static final String DEFAULT_OPTIONS = "soft,timeo=30,retrans=2,nosharecache";
    static final String SETTING = "proxmox.secondary.mount.options";
    /** A mount directory of one operation is "&lt;prefix&gt;&lt;random uuid&gt;". */
    static final String MOUNT_PREFIX = "/mnt/css-tmp-";
    private static final int MOUNT_TIMEOUT_SECONDS = 60;
    private static final int UNMOUNT_TIMEOUT_SECONDS = 30;
    private static final int IMAGE_TIMEOUT_SECONDS = 60;
    /** A directory left by an operation that did not finish is cleaned up when it is this old. */
    private static final int STALE_MINUTES = 10;

    private static final Pattern NFS_SERVER = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern NFS_EXPORT = Pattern.compile("^(/[A-Za-z0-9._-]+)+$");
    private static final Pattern NFS_VERSION = Pattern.compile("^[0-9](\\.[0-9])?$");
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    /** The options the setting may carry; everything else, {@code ro}, {@code rw} and {@code vers} included, is refused. */
    private static final Pattern ALLOWED_OPTION = Pattern.compile("^(soft|hard|nosharecache|noatime|nolock|proto=tcp|timeo=[0-9]{1,5}|retrans=[0-9]{1,3}"
            + "|nconnect=[0-9]{1,2}|rsize=[0-9]{1,8}|wsize=[0-9]{1,8})$");

    private final Supplier<String> mountOptions;
    private final AtomicBoolean cleaned = new AtomicBoolean();

    ProxmoxSecondaryMounts(Supplier<String> mountOptions) {
        this.mountOptions = mountOptions;
    }

    /** What the check of an image found. */
    static final class ImageInfo {
        private final String format;
        private final long virtualSize;

        ImageInfo(String format, long virtualSize) {
            this.format = format;
            this.virtualSize = virtualSize;
        }

        String getFormat() {
            return format;
        }

        long getVirtualSize() {
            return virtualSize;
        }
    }

    /**
     * Mounts the store in a directory of its own, runs the action with that directory, and then unmounts and removes it, whatever the action did.
     * Nothing is shared between operations, so there are no locks and no mounts that exist already. A directory that an operation of an earlier run
     * left behind is cleaned up the first time this is used.
     */
    <T> T withMount(ProxmoxSsh ssh, NfsTO store, boolean readOnly, Function<String, T> action) {
        String[] address = address(store);
        String options = optionsFor(store, readOnly);
        cleanUpStaleOnce(ssh);
        String directory = MOUNT_PREFIX + UUID.randomUUID();
        ExecResult mounted = ssh.sudo("mkdir -p " + ProxmoxSsh.quote(directory) + " && timeout " + MOUNT_TIMEOUT_SECONDS + " mount -t nfs -o " + ProxmoxSsh.quote(options)
                + " " + ProxmoxSsh.quote(address[0] + ":" + address[1]) + " " + ProxmoxSsh.quote(directory), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!mounted.isSuccess()) {
            release(ssh, directory);
            throw new ProxmoxSshException("Could not mount the secondary store " + address[0] + ":" + address[1] + " on the Proxmox node: " + mounted.describeFailure());
        }
        try {
            return action.apply(directory);
        } finally {
            release(ssh, directory);
        }
    }

    /**
     * Reads the image with {@code qemu-img info} and refuses what the plugin must not hand to {@code qemu-img convert} or to Proxmox: a file that is not a
     * qcow2 or raw image, an image with a backing file (it would read another file of the node into the disk) and a qcow2 with an external data file.
     * A user's template is untrusted, which is why Proxmox's own {@code import-from} makes the same check.
     */
    ImageInfo checkImage(ProxmoxSsh ssh, String file) {
        ExecResult result = ssh.sudo("timeout " + IMAGE_TIMEOUT_SECONDS + " qemu-img info -U --output=json " + ProxmoxSsh.quote(file), null,
                ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess()) {
            throw new ProxmoxSshException("Could not read the image " + file + ": " + result.describeFailure());
        }
        JsonNode info;
        try {
            info = new ObjectMapper().readTree(result.getStdout());
        } catch (IOException e) {
            throw new ProxmoxSshException("The information of the image " + file + " could not be read: " + e.getMessage(), e);
        }
        String format = info.path("format").asText("");
        if (!"qcow2".equals(format) && !"raw".equals(format)) {
            throw new IllegalArgumentException("The image " + file + " is of the format '" + format + "'; only qcow2 and raw images are used");
        }
        if (info.hasNonNull("backing-filename") || info.hasNonNull("full-backing-filename")) {
            throw new IllegalArgumentException("The image " + file + " has a backing file (" + info.path("backing-filename").asText() + "), which is not allowed");
        }
        if (info.path("format-specific").path("data").hasNonNull("data-file")) {
            throw new IllegalArgumentException("The image " + file + " has an external data file, which is not allowed");
        }
        long size = info.path("virtual-size").asLong(0);
        if (size <= 0) {
            throw new IllegalArgumentException("The image " + file + " has no size");
        }
        return new ImageInfo(format, size);
    }

    /**
     * The mount options of the setting, checked. A comma-separated list of {@code soft}, {@code hard}, {@code nosharecache}, {@code noatime},
     * {@code nolock}, {@code proto=tcp}, {@code timeo=}, {@code retrans=}, {@code nconnect=}, {@code rsize=} and {@code wsize=} with a number; empty
     * means the system's defaults. Anything else is refused with its name, because the value reaches a root shell.
     */
    static String checkOptions(String setting) {
        List<String> accepted = new ArrayList<>();
        for (String option : StringUtils.defaultString(setting).split(",")) {
            String trimmed = option.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!ALLOWED_OPTION.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("The setting " + SETTING + " has the option '" + trimmed + "', which is not allowed (allowed: soft, hard, "
                        + "nosharecache, noatime, nolock, proto=tcp, timeo=, retrans=, nconnect=, rsize=, wsize= with a number; ro, rw and vers are set by the plugin)");
            }
            if (!accepted.contains(trimmed)) {
                accepted.add(trimmed);
            }
        }
        if (accepted.contains("soft") && accepted.contains("hard")) {
            throw new IllegalArgumentException("The setting " + SETTING + " has both soft and hard");
        }
        return String.join(",", accepted);
    }

    /** A folder or file below the store's mount; nothing that leaves it. */
    static String relativePath(String path, String what) {
        if (path == null || !RELATIVE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("The path of " + what + " (" + path + ") is not a relative path of letters, digits, '.', '_' and '-'");
        }
        for (String part : path.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("The path of " + what + " (" + path + ") must not contain . or ..");
            }
        }
        return path;
    }

    // ---- the pieces ----

    /** The server and the export of the store, after the address has been checked. */
    private static String[] address(NfsTO store) {
        URI url;
        try {
            url = URI.create(StringUtils.removeEnd(StringUtils.defaultString(store.getUrl()), "/"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The secondary store address " + store.getUrl() + " is not a URL");
        }
        String server = url.getHost();
        String export = url.getPath();
        if (server == null || !NFS_SERVER.matcher(server).matches() || export == null || !NFS_EXPORT.matcher(export).matches() || export.contains("/../")
                || export.endsWith("/..")) {
            throw new IllegalArgumentException("The secondary store address " + store.getUrl() + " is not an NFS server and export this plugin can mount");
        }
        return new String[] {server, export};
    }

    /** {@code ro} or {@code rw}, the store's NFS version when it has one, and the options of the setting. */
    private String optionsFor(NfsTO store, boolean readOnly) {
        List<String> options = new ArrayList<>();
        options.add(readOnly ? "ro" : "rw");
        String version = store.getNfsVersion();
        if (StringUtils.isNotBlank(version)) {
            if (!NFS_VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("The NFS version " + version + " of the secondary store is not a version number");
            }
            options.add("vers=" + version);
        }
        String configured = checkOptions(mountOptions.get());
        if (!configured.isEmpty()) {
            options.add(configured);
        }
        return String.join(",", options);
    }

    /** Unmounts (lazily when the export does not answer) and removes the directory. A failure is logged only: it must not hide what the operation did. */
    private static void release(ProxmoxSsh ssh, String directory) {
        String quoted = ProxmoxSsh.quote(directory);
        try {
            ExecResult result = ssh.sudo("(timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount " + quoted + " || timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount -l " + quoted
                    + ") 2>/dev/null; rmdir " + quoted, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
            if (!result.isSuccess()) {
                LOGGER.warn("The mount directory {} on the Proxmox node was not removed: {}", directory, result.describeFailure());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("The mount directory {} on the Proxmox node was not removed: {}", directory, e.getMessage());
        }
    }

    /** The first time after the host connected: removes the directories of operations that did not finish (a crash), once they are old enough not to be in use. */
    private void cleanUpStaleOnce(ProxmoxSsh ssh) {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }
        try {
            ExecResult result = ssh.sudo("for d in $(find /mnt -maxdepth 1 -type d -name 'css-tmp-*' -mmin +" + STALE_MINUTES + " 2>/dev/null); do "
                    + "(timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount \"$d\" || timeout " + UNMOUNT_TIMEOUT_SECONDS + " umount -l \"$d\") 2>/dev/null; rmdir \"$d\" 2>/dev/null; done; true",
                    null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
            if (!result.isSuccess()) {
                LOGGER.warn("Old mount directories on the Proxmox node could not be cleaned up: {}", result.describeFailure());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Old mount directories on the Proxmox node could not be cleaned up: {}", e.getMessage());
        }
    }
}

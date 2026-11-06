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
import java.util.UUID;

/**
 * How a CloudStack primary storage pool is named on the Proxmox side. Both names are derived from the pool uuid, so the same
 * entry is found again after a restart and an administrator can match everything by the pool uuid.
 */
public final class ProxmoxStorageNames {
    /**
     * A Proxmox storage id has to start with a letter (checked on the lab: an id starting with a digit is refused with "contains
     * illegal characters"), while a CloudStack pool uuid, a name-based UUID, starts with a digit in about 60 % of the cases. The
     * hyphens of the uuid are kept so an administrator can match the entry with the pool at a glance.
     */
    public static final String STORAGE_ID_PREFIX = "cs-";
    /** The same place the KVM agent mounts a pool, /mnt/&lt;pool uuid&gt;. */
    public static final String MOUNT_PATH_PREFIX = "/mnt/";

    /**
     * The directory entries that an earlier version made for the templates of one account folder (a template that was copied then still has a path
     * that names one). No new ones are made: a template is a Proxmox template VM now (ProxmoxTemplates). Only used to recognise the old paths.
     */
    public static final String TEMPLATE_STORAGE_ID_PREFIX = "cst-";

    /**
     * The one directory entry for the ISOs of the secondary stores, which the plugin binds there file by file while they are attached (ProxmoxIsos). It
     * is shared (the same files on every node), so a VM with an ISO can migrate online. Same letter-first rule as above.
     */
    public static final String ISO_SHARED_STORAGE_ID_PREFIX = "csiso-";

    /**
     * The one directory entry for the files that patch system VMs (the payload CD with the agent). It is not shared: each node has its own
     * directory under the path, which Proxmox creates. Same letter-first rule as above.
     */
    public static final String PATCH_STORAGE_ID_PREFIX = "csp-";
    /** Files in the patch entry that this plugin made and may remove again. */
    public static final String PATCH_FILE_PREFIX = "cs-patch-";

    private ProxmoxStorageNames() {
    }

    public static String storageId(String poolUuid) {
        return STORAGE_ID_PREFIX + poolUuid;
    }

    public static String mountPath(String poolUuid) {
        return MOUNT_PATH_PREFIX + poolUuid;
    }

    /** The shared ISO entry for a directory path, derived from it so that every command, management server and restart finds the same one. */
    public static String isoSharedStorageId(String path) {
        return ISO_SHARED_STORAGE_ID_PREFIX + UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8));
    }

    /** The patch entry for a directory path, derived from it so that every command, management server and restart finds the same one. */
    public static String patchStorageId(String path) {
        return PATCH_STORAGE_ID_PREFIX + UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8));
    }

    /** The file of a payload: "cs-patch-" and the first 16 characters of the SHA-512 of the content, so a new version is a new file. */
    public static String patchFileName(String sha512) {
        return PATCH_FILE_PREFIX + sha512.substring(0, 16) + ".iso";
    }

    public static boolean isTemplateStorageId(String id) {
        return id != null && id.startsWith(TEMPLATE_STORAGE_ID_PREFIX);
    }
}

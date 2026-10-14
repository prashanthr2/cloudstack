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
     * A read-only entry that exposes one template's folder on the secondary store, so a VM disk can be imported from the template file.
     * Same letter-first rule as above.
     */
    public static final String TEMPLATE_STORAGE_ID_PREFIX = "cst-";

    private ProxmoxStorageNames() {
    }

    public static String storageId(String poolUuid) {
        return STORAGE_ID_PREFIX + poolUuid;
    }

    public static String mountPath(String poolUuid) {
        return MOUNT_PATH_PREFIX + poolUuid;
    }

    /**
     * The entry for the template folder {@code folder} of the NFS export {@code server:export}. Derived from the three values, so every
     * command for the same template finds the same entry, on any management server and after a restart.
     */
    public static String templateStorageId(String server, String export, String folder) {
        return TEMPLATE_STORAGE_ID_PREFIX + UUID.nameUUIDFromBytes((server + ":" + export + ":" + folder).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isTemplateStorageId(String id) {
        return id != null && id.startsWith(TEMPLATE_STORAGE_ID_PREFIX);
    }
}

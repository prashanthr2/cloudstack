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

/**
 * How a CloudStack primary storage pool is named on the Proxmox side. Both names are derived from the pool uuid, the way the KVM
 * agent names the libvirt pool and its mount point, so the same entry is found again after a restart and an administrator can
 * match everything by the pool uuid.
 */
public final class ProxmoxStorageNames {
    /** The same place the KVM agent mounts a pool, /mnt/&lt;pool uuid&gt;. */
    public static final String MOUNT_PATH_PREFIX = "/mnt/";

    private ProxmoxStorageNames() {
    }

    /**
     * The Proxmox storage id is the pool uuid, as the libvirt pool is named on KVM. Note that Proxmox may refuse an id that
     * starts with a digit while a pool uuid can; the add then fails with Proxmox's own message (see design-phase2-storage.md, S1b).
     */
    public static String storageId(String poolUuid) {
        return poolUuid;
    }

    public static String mountPath(String poolUuid) {
        return MOUNT_PATH_PREFIX + poolUuid;
    }
}

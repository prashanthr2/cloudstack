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
 * How a CloudStack primary storage pool is named on the Proxmox side. Both names are derived from the pool uuid, so the same
 * entry is found again after a restart and an administrator can match them by eye.
 */
public final class ProxmoxStorageNames {
    /** A Proxmox storage id has to start with a letter, and a pool uuid can start with a digit. */
    public static final String STORAGE_ID_PREFIX = "cs-";
    /** The same place the KVM agent mounts a pool, /mnt/&lt;pool uuid&gt;. */
    public static final String MOUNT_PATH_PREFIX = "/mnt/";

    private ProxmoxStorageNames() {
    }

    public static String storageId(String poolUuid) {
        return STORAGE_ID_PREFIX + poolUuid;
    }

    public static String mountPath(String poolUuid) {
        return MOUNT_PATH_PREFIX + poolUuid;
    }
}

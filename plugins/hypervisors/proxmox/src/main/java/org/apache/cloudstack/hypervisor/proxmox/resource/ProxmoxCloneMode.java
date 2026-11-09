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

import com.cloud.storage.Storage;

/**
 * Whether the root volume of an instance is a linked clone of the template VM or a full copy (design-phase12-linked-clones.md). As on KVM, the provisioning
 * type of the offering decides first: only a thin volume may be a linked clone, sparse and fat volumes are always a full copy. A thin volume follows the
 * setting {@code proxmox.create.full.clone} of its primary storage. A volume that is bigger than the template is still a linked clone, grown after it is made.
 */
final class ProxmoxCloneMode {
    private ProxmoxCloneMode() {
    }

    /**
     * @param fullCloneSetting the value of {@code proxmox.create.full.clone} for the primary storage
     * @param provisioningType the provisioning type of the volume's offering, or null when core did not send one (treated as thin)
     * @return true for a linked clone, false for a full copy
     */
    static boolean linked(boolean fullCloneSetting, Storage.ProvisioningType provisioningType) {
        if (provisioningType == Storage.ProvisioningType.SPARSE || provisioningType == Storage.ProvisioningType.FAT) {
            return false;
        }
        return !fullCloneSetting;
    }
}

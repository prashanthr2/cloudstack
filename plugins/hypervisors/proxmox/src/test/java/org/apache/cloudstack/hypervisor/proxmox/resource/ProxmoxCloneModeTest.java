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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cloud.storage.Storage;

public class ProxmoxCloneModeTest {
    @Test
    public void aThinVolumeFollowsTheSetting() {
        assertTrue(ProxmoxCloneMode.linked(false, Storage.ProvisioningType.THIN));
        assertFalse(ProxmoxCloneMode.linked(true, Storage.ProvisioningType.THIN));
    }

    @Test
    public void aVolumeWithoutAProvisioningTypeIsTreatedAsThin() {
        assertTrue(ProxmoxCloneMode.linked(false, null));
        assertFalse(ProxmoxCloneMode.linked(true, null));
    }

    @Test
    public void aSparseOrFatVolumeIsAlwaysAFullCopy() {
        assertFalse(ProxmoxCloneMode.linked(false, Storage.ProvisioningType.SPARSE));
        assertFalse(ProxmoxCloneMode.linked(false, Storage.ProvisioningType.FAT));
        assertFalse(ProxmoxCloneMode.linked(true, Storage.ProvisioningType.SPARSE));
        assertFalse(ProxmoxCloneMode.linked(true, Storage.ProvisioningType.FAT));
    }
}

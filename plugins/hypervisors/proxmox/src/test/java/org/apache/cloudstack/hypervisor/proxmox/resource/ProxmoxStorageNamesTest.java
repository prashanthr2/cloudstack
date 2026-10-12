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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProxmoxStorageNamesTest {

    @Test
    public void theStorageIdKeepsTheUuidWithItsHyphensAndStartsWithALetter() {
        String id = ProxmoxStorageNames.storageId("047ccc61-dc29-3d58-9c73-dd1ab2c100cf");
        assertEquals("cs-047ccc61-dc29-3d58-9c73-dd1ab2c100cf", id);
        assertTrue("a Proxmox storage id has to start with a letter", Character.isLetter(id.charAt(0)));
    }

    @Test
    public void thePoolIsMountedWhereTheKvmAgentMountsIt() {
        assertEquals("/mnt/047ccc61-dc29-3d58-9c73-dd1ab2c100cf", ProxmoxStorageNames.mountPath("047ccc61-dc29-3d58-9c73-dd1ab2c100cf"));
    }
}

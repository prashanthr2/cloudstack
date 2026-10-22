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
import static org.junit.Assert.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ProxmoxRouterNicsTest {
    private static Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("name", "r-12-VM");
        config.put("net2", "virtio=1E:01:B7:00:00:15,bridge=vmbr1,tag=51,firewall=0");
        config.put("net0", "virtio=02:00:A9:FE:00:01,bridge=vmbr1,tag=100,firewall=0");
        config.put("net1", "virtio=0E:00:A9:FE:A6:57,bridge=cloud0,firewall=0");
        config.put("scsi0", "cs-1:115/vm-115-disk-0.qcow2,size=5000M");
        return config;
    }

    @Test
    public void theInterfacesAreListedInTheOrderOfTheirNumbersWithMacAddressInLowerCase() {
        List<ProxmoxRouterNics.Nic> nics = ProxmoxRouterNics.parse(config());

        assertEquals(3, nics.size());
        assertEquals("02:00:a9:fe:00:01", nics.get(0).getMac());
        assertEquals("100", nics.get(0).getTag());
        assertEquals("0e:00:a9:fe:a6:57", nics.get(1).getMac());
        assertNull(nics.get(1).getTag());
        assertEquals("1e:01:b7:00:00:15", nics.get(2).getMac());
        assertEquals("51", nics.get(2).getTag());
    }

    @Test
    public void theNumberInTheGuestIsThePositionOfTheMacAddressWhateverItsCase() {
        List<ProxmoxRouterNics.Nic> nics = ProxmoxRouterNics.parse(config());

        assertEquals(Integer.valueOf(0), ProxmoxRouterNics.numberOf(nics, "02:00:A9:FE:00:01"));
        assertEquals(Integer.valueOf(1), ProxmoxRouterNics.numberOf(nics, "0e:00:a9:fe:a6:57"));
        assertEquals(Integer.valueOf(2), ProxmoxRouterNics.numberOf(nics, "1E:01:B7:00:00:15"));
        assertNull(ProxmoxRouterNics.numberOf(nics, "aa:bb:cc:dd:ee:ff"));
        assertNull(ProxmoxRouterNics.numberOf(nics, null));
    }

    @Test
    public void aNewInterfaceGetsOneMoreThanTheHighestNumberInUse() {
        Map<String, String> config = config();
        config.put("net5", "virtio=02:00:00:00:00:05,bridge=vmbr1");

        List<ProxmoxRouterNics.Nic> nics = ProxmoxRouterNics.parse(config);

        assertEquals(6, ProxmoxRouterNics.nextIndex(nics));
        assertEquals(5, ProxmoxRouterNics.indexAt(nics, 3));
        assertEquals(0, ProxmoxRouterNics.nextIndex(ProxmoxRouterNics.parse(new LinkedHashMap<>())));
    }

    @Test
    public void entriesThatAreNotInterfacesOrHaveNoMacAreSkipped() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("net0", "bridge=vmbr1");
        config.put("netx", "virtio=02:00:00:00:00:01");
        config.put("net1", null);

        assertEquals(0, ProxmoxRouterNics.parse(config).size());
    }
}

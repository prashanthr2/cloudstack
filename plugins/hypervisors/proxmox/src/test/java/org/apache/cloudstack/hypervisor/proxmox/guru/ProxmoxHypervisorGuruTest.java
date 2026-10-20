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
package org.apache.cloudstack.hypervisor.proxmox.guru;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.storage.command.CopyCommand;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine;

public class ProxmoxHypervisorGuruTest {
    private ProxmoxHypervisorGuru guru;
    private GuestOSHypervisorDao mappings;
    private VirtualMachine vm;
    private VirtualMachineTO to;

    @Before
    public void setUp() {
        guru = new ProxmoxHypervisorGuru();
        GuestOSDao guestOsDao = mock(GuestOSDao.class);
        mappings = mock(GuestOSHypervisorDao.class);
        guru.guestOsDao = guestOsDao;
        guru.guestOsHypervisorDao = mappings;
        guru.hostDao = mock(HostDao.class);
        GuestOSVO guestOs = mock(GuestOSVO.class);
        when(guestOs.getId()).thenReturn(42L);
        when(guestOs.getDisplayName()).thenReturn("Windows Server 2019 (64-bit)");
        vm = mock(VirtualMachine.class);
        when(vm.getGuestOSId()).thenReturn(42L);
        when(guestOsDao.findByIdIncludingRemoved(42L)).thenReturn(guestOs);
        to = new VirtualMachineTO(1L, "i-2-10-VM", VirtualMachine.Type.User, 1, 500, 1024, 1024, null, null, false, false, null);
    }

    private static GuestOSHypervisorVO mapping(String name) {
        GuestOSHypervisorVO mapping = mock(GuestOSHypervisorVO.class);
        when(mapping.getGuestOsName()).thenReturn(name);
        return mapping;
    }

    @Test
    public void theVmIsCreatedWithTheOsTypeOfTheGuestOsMapping() {
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorVersion()).thenReturn("8.4.21");
        GuestOSHypervisorVO win10 = mapping("win10");
        when(mappings.findByOsIdAndHypervisor(42L, "Proxmox", "8.4.21")).thenReturn(win10);

        guru.configureVmOsDescription(vm, to, host);

        assertEquals("Windows Server 2019 (64-bit)", to.getOs());
        assertEquals("win10", to.getPlatformEmulator());
    }

    @Test
    public void aVmNotYetPlacedOnAHostUsesTheDefaultMapping() {
        GuestOSHypervisorVO win10 = mapping("win10");
        when(mappings.findByOsIdAndHypervisor(42L, "Proxmox", null)).thenReturn(win10);

        guru.configureVmOsDescription(vm, to, null);

        assertEquals("win10", to.getPlatformEmulator());
    }

    @Test
    public void aGuestOsWithoutAMappingIsOther() {
        guru.configureVmOsDescription(vm, to, null);

        assertEquals("other", to.getPlatformEmulator());
    }

    @Test
    public void theGuruServesProxmoxAndLeavesHostTrackingToThePowerReport() {
        assertEquals(Hypervisor.HypervisorType.Proxmox, guru.getHypervisorType());
        assertFalse(guru.trackVmHostChange());
    }

    private static DataTO dataOf(Hypervisor.HypervisorType type) {
        DataTO data = mock(DataTO.class);
        when(data.getHypervisorType()).thenReturn(type);
        return data;
    }

    @Test
    public void aCopyOfProxmoxIsNotQueuedPerHostAndTheOtherGurusAreNotAskedAfterwards() {
        CopyCommand copy = new CopyCommand(dataOf(Hypervisor.HypervisorType.Proxmox), dataOf(Hypervisor.HypervisorType.Proxmox), 0, true);

        Pair<Boolean, Long> result = guru.getCommandHostDelegation(15L, copy);

        assertFalse(copy.executeInSequence());
        assertTrue(result.first());
        assertEquals(Long.valueOf(15L), result.second());
    }

    @Test
    public void aCopyWhoseDestinationIsProxmoxIsNotQueuedEither() {
        CopyCommand copy = new CopyCommand(dataOf(null), dataOf(Hypervisor.HypervisorType.Proxmox), 0, true);

        assertTrue(guru.getCommandHostDelegation(15L, copy).first());
        assertFalse(copy.executeInSequence());
    }

    @Test
    public void aCopyOfAnotherHypervisorIsLeftAlone() {
        CopyCommand copy = new CopyCommand(dataOf(Hypervisor.HypervisorType.VMware), dataOf(Hypervisor.HypervisorType.VMware), 0, true);

        Pair<Boolean, Long> result = guru.getCommandHostDelegation(15L, copy);

        assertTrue(copy.executeInSequence());
        assertFalse(result.first());
        assertEquals(Long.valueOf(15L), result.second());
    }
}

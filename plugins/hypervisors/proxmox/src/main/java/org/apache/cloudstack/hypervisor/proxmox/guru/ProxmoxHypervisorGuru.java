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

import javax.inject.Inject;

import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxVmConfig;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruBase;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

public class ProxmoxHypervisorGuru extends HypervisorGuruBase implements HypervisorGuru {
    @Inject
    protected GuestOSDao guestOsDao;
    @Inject
    protected GuestOSHypervisorDao guestOsHypervisorDao;
    @Inject
    protected HostDao hostDao;

    protected ProxmoxHypervisorGuru() {
        super();
    }

    @Override
    public Hypervisor.HypervisorType getHypervisorType() {
        return Hypervisor.HypervisorType.Proxmox;
    }

    @Override
    public VirtualMachineTO implement(VirtualMachineProfile vm) {
        VirtualMachineTO to = toVirtualMachineTO(vm);
        VirtualMachine virtualMachine = vm.getVirtualMachine();
        Long hostId = virtualMachine.getHostId();
        configureVmOsDescription(virtualMachine, to, hostId == null ? null : hostDao.findById(hostId));
        return to;
    }

    /**
     * The guest OS as CloudStack shows it ({@code os}) and as Proxmox wants it ({@code platformEmulator}, the "ostype" of the VM), taken
     * from the guest OS mapping for Proxmox like the other hypervisors do. Without a mapping the VM is created as "other".
     */
    protected void configureVmOsDescription(VirtualMachine virtualMachine, VirtualMachineTO to, HostVO host) {
        GuestOSVO guestOs = guestOsDao.findByIdIncludingRemoved(virtualMachine.getGuestOSId());
        to.setOs(guestOs.getDisplayName());
        String version = host == null ? null : host.getHypervisorVersion();
        GuestOSHypervisorVO mapping = guestOsHypervisorDao.findByOsIdAndHypervisor(guestOs.getId(), getHypervisorType().toString(), version);
        to.setPlatformEmulator(mapping == null ? ProxmoxVmConfig.DEFAULT_OS_TYPE : mapping.getGuestOsName());
    }

    @Override
    public boolean trackVmHostChange() {
        // Proxmox HA can move a VM between nodes on its own; the power-state report fixes the host.
        return false;
    }
}

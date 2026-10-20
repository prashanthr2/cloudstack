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
import org.apache.cloudstack.storage.command.CopyCommand;

import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.DataTO;
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
import com.cloud.utils.Pair;
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

    /**
     * CloudStack asks every hypervisor guru about every command (HypervisorGuruManagerImpl.getGuruProcessedCommandTargetHost), and KVMGuru makes any
     * copy to a primary storage "execute in sequence" whatever the hypervisor is: the agent then sends such commands to a host one after the other,
     * so deploying several VMs at once queues their root volumes (lab, 2026-10-20: the third waited for one of the first two, although
     * execute.in.sequence.hypervisor.commands is false). Creating a root volume in Proxmox is an asynchronous task on the cluster and needs no such
     * queue, so this guru takes the flag off the copy commands of Proxmox. It answers "host changed" (with the same host) so that the other gurus
     * are not asked after it, which is what keeps KVMGuru from putting the flag back when it comes later in the list.
     */
    @Override
    public Pair<Boolean, Long> getCommandHostDelegation(long hostId, Command cmd) {
        if (cmd instanceof CopyCommand && (isProxmox(((CopyCommand) cmd).getSrcTO()) || isProxmox(((CopyCommand) cmd).getDestTO()))) {
            ((CopyCommand) cmd).setExecuteInSequence(false);
            return new Pair<>(true, hostId);
        }
        return super.getCommandHostDelegation(hostId, cmd);
    }

    private static boolean isProxmox(DataTO data) {
        return data != null && data.getHypervisorType() == Hypervisor.HypervisorType.Proxmox;
    }

    @Override
    public boolean trackVmHostChange() {
        // Proxmox HA can move a VM between nodes on its own; the power-state report fixes the host.
        return false;
    }
}

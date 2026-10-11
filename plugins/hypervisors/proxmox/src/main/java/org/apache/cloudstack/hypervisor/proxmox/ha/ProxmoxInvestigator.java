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
package org.apache.cloudstack.hypervisor.proxmox.ha;

import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VirtualMachine;

/**
 * Handles host failures the way the VMware investigator does, because Proxmox, like VMware, does its own HA.
 * <p>
 * A Proxmox host that stops answering is reported as Disconnected, never as Down (Down makes CloudStack restart
 * the host's VMs itself, which is Proxmox HA's job). CloudStack then moves it to Alert once alert.wait has passed.
 * VMs of Proxmox hosts are reported alive, so CloudStack's HA never competes with Proxmox HA.
 */
public class ProxmoxInvestigator extends AdapterBase implements Investigator {

    @Override
    public Status getHostAgentStatus(Host agent) {
        if (agent.getHypervisorType() == Hypervisor.HypervisorType.Proxmox) {
            return Status.Disconnected;
        }
        return null;
    }

    @Override
    public boolean isVmAlive(VirtualMachine vm, Host host) throws UnknownVM {
        if (vm.getHypervisorType() == Hypervisor.HypervisorType.Proxmox) {
            return true;
        }
        throw new UnknownVM();
    }
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.vm.VirtualMachine;

public class ProxmoxInvestigatorTest {
    private final ProxmoxInvestigator investigator = new ProxmoxInvestigator();

    private Host host(Hypervisor.HypervisorType type) {
        Host host = mock(Host.class);
        when(host.getHypervisorType()).thenReturn(type);
        return host;
    }

    private VirtualMachine vm(Hypervisor.HypervisorType type) {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHypervisorType()).thenReturn(type);
        return vm;
    }

    @Test
    public void aProxmoxHostThatStoppedAnsweringIsDisconnectedNeverDown() {
        assertEquals(Status.Disconnected, investigator.getHostAgentStatus(host(Hypervisor.HypervisorType.Proxmox)));
    }

    @Test
    public void otherHypervisorsAreLeftToTheirOwnInvestigators() {
        assertNull(investigator.getHostAgentStatus(host(Hypervisor.HypervisorType.KVM)));
        assertNull(investigator.getHostAgentStatus(host(Hypervisor.HypervisorType.VMware)));
    }

    @Test
    public void proxmoxVmsAreReportedAliveSoCloudStackHaStaysOutOfProxmoxHa() throws Investigator.UnknownVM {
        assertTrue(investigator.isVmAlive(vm(Hypervisor.HypervisorType.Proxmox), host(Hypervisor.HypervisorType.Proxmox)));
    }

    @Test(expected = Investigator.UnknownVM.class)
    public void theLivenessOfOtherVmsIsUnknown() throws Investigator.UnknownVM {
        investigator.isVmAlive(vm(Hypervisor.HypervisorType.KVM), host(Hypervisor.HypervisorType.KVM));
    }
}

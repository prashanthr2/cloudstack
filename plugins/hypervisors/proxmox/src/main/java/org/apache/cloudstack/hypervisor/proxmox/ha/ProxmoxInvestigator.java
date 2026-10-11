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

import java.util.List;

import javax.inject.Inject;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckOnHostAnswer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VirtualMachine;

/**
 * Decides what became of a Proxmox host that stopped answering, using the Proxmox cluster's own view of its members
 * as reported by a neighbouring node.
 * <p>
 * A node that the cluster reports offline is returned as Alert, never as Down: Down would make CloudStack restart
 * the host's VMs itself, while that is Proxmox HA's job.
 */
public class ProxmoxInvestigator extends AdapterBase implements Investigator {
    @Inject
    ResourceManager resourceManager;
    @Inject
    AgentManager agentManager;

    @Override
    public boolean isVmAlive(VirtualMachine vm, Host host) throws UnknownVM {
        // Whether a VM runs is for Proxmox (and its HA) to say, never to be guessed from the host state.
        throw new UnknownVM();
    }

    @Override
    public Status getHostAgentStatus(Host agent) {
        if (agent.getHypervisorType() != Hypervisor.HypervisorType.Proxmox) {
            return null;
        }
        List<HostVO> neighbours = resourceManager.listHostsInClusterByStatus(agent.getClusterId(), Status.Up);
        for (HostVO neighbour : neighbours) {
            if (neighbour.getId() == agent.getId() || neighbour.getHypervisorType() != Hypervisor.HypervisorType.Proxmox) {
                continue;
            }
            try {
                Answer answer = agentManager.easySend(neighbour.getId(), new CheckOnHostCommand(agent));
                if (answer instanceof CheckOnHostAnswer && ((CheckOnHostAnswer) answer).isDetermined()) {
                    if (((CheckOnHostAnswer) answer).isAlive()) {
                        // the node is up, only CloudStack's link to it is broken
                        return Status.Disconnected;
                    }
                    logger.warn("The Proxmox cluster reports {} offline: {}", agent, answer.getDetails());
                    return Status.Alert;
                }
            } catch (Exception e) {
                logger.debug("Unable to ask {} about {}", neighbour, agent, e);
            }
        }
        return null;
    }
}

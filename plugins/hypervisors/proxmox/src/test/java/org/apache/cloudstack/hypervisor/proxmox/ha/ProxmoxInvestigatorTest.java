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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckOnHostAnswer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.ha.Investigator;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.resource.ResourceManager;

public class ProxmoxInvestigatorTest {
    private ProxmoxInvestigator investigator;
    private ResourceManager resourceManager;
    private AgentManager agentManager;
    private HostVO failed;
    private HostVO neighbour;

    @Before
    public void setUp() {
        investigator = new ProxmoxInvestigator();
        resourceManager = mock(ResourceManager.class);
        agentManager = mock(AgentManager.class);
        ReflectionTestUtils.setField(investigator, "resourceManager", resourceManager);
        ReflectionTestUtils.setField(investigator, "agentManager", agentManager);

        failed = host(1L, Hypervisor.HypervisorType.Proxmox);
        neighbour = host(2L, Hypervisor.HypervisorType.Proxmox);
        when(resourceManager.listHostsInClusterByStatus(10L, Status.Up)).thenReturn(Arrays.asList(failed, neighbour));
    }

    private HostVO host(long id, Hypervisor.HypervisorType type) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getClusterId()).thenReturn(10L);
        when(host.getHypervisorType()).thenReturn(type);
        return host;
    }

    private void neighbourAnswers(Answer answer) throws Exception {
        when(agentManager.easySend(anyLong(), any(CheckOnHostCommand.class))).thenReturn(answer);
    }

    @Test
    public void aNodeTheClusterReportsOfflineIsAlertNeverDown() throws Exception {
        neighbourAnswers(new CheckOnHostAnswer(new CheckOnHostCommand(failed), false, "offline"));
        assertEquals(Status.Alert, investigator.getHostAgentStatus(failed));
    }

    @Test
    public void aLiveNodeWithABrokenLinkIsDisconnected() throws Exception {
        neighbourAnswers(new CheckOnHostAnswer(new CheckOnHostCommand(failed), true, "online"));
        assertEquals(Status.Disconnected, investigator.getHostAgentStatus(failed));
    }

    @Test
    public void anUndeterminedAnswerLeavesTheStateOpen() throws Exception {
        neighbourAnswers(new CheckOnHostAnswer(new CheckOnHostCommand(failed), "cannot tell"));
        assertNull(investigator.getHostAgentStatus(failed));
    }

    @Test
    public void noAnswerFromAnyNeighbourLeavesTheStateOpen() throws Exception {
        neighbourAnswers(null);
        assertNull(investigator.getHostAgentStatus(failed));
    }

    @Test
    public void theHostIsNeverAskedAboutItself() throws Exception {
        when(resourceManager.listHostsInClusterByStatus(10L, Status.Up)).thenReturn(Collections.singletonList(failed));
        assertNull(investigator.getHostAgentStatus(failed));
        verify(agentManager, never()).easySend(anyLong(), any(CheckOnHostCommand.class));
    }

    @Test
    public void otherHypervisorsAreNotInvestigated() {
        assertNull(investigator.getHostAgentStatus(host(5L, Hypervisor.HypervisorType.KVM)));
    }

    @Test(expected = Investigator.UnknownVM.class)
    public void vmLivenessIsLeftToProxmox() throws Investigator.UnknownVM {
        investigator.isVmAlive(null, failed);
    }
}

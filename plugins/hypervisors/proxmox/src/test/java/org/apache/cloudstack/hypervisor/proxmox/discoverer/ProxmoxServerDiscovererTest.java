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
package org.apache.cloudstack.hypervisor.proxmox.discoverer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.resource.ProxmoxResource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.DiscoveryException;
import com.cloud.resource.ServerResource;
import com.cloud.hypervisor.Hypervisor;

public class ProxmoxServerDiscovererTest {
    private ProxmoxApiClient client;
    private ProxmoxServerDiscoverer discoverer;

    @Before
    public void setUp() {
        client = mock(ProxmoxApiClient.class);
        discoverer = new ProxmoxServerDiscoverer() {
            @Override
            protected ProxmoxApiClient createClient(String endpoint, String tokenId, String tokenSecret, boolean verifyTls) {
                return ProxmoxServerDiscovererTest.this.client;
            }
        };
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.Proxmox);
        when(cluster.getGuid()).thenReturn("cluster-guid");
        ClusterDao clusterDao = mock(ClusterDao.class);
        when(clusterDao.findById(2L)).thenReturn(cluster);
        ReflectionTestUtils.setField(discoverer, "_clusterDao", clusterDao);
    }

    @Test
    public void everyOnlineNodeBecomesAHostAndKeepsTheCredentialsNeededToReconnect() throws Exception {
        when(client.getVersion()).thenReturn("8.4.21");
        when(client.getClusterName()).thenReturn("lab");
        when(client.getClusterNodes(anyString())).thenReturn(Arrays.asList(
                new ProxmoxApiClient.NodeInfo("pve1", "10.0.32.196", true),
                new ProxmoxApiClient.NodeInfo("pve2", "10.0.32.197", true),
                new ProxmoxApiClient.NodeInfo("pve3", "10.0.32.198", false)));

        Map<? extends ServerResource, Map<String, String>> found = discoverer.find(1L, 1L, 2L,
                URI.create("http://10.0.35.25:8006?verifyTls=false"), "root@pam!cloudstack", "secret", null);

        assertEquals("offline nodes are skipped", 2, found.size());
        for (Map.Entry<? extends ServerResource, Map<String, String>> entry : found.entrySet()) {
            assertNotNull(entry.getKey());
            Map<String, String> details = entry.getValue();
            // these are rebuilt into the resource after a management server restart
            assertEquals("root@pam!cloudstack", details.get("username"));
            assertEquals("secret", details.get("password"));
            assertEquals("https://10.0.35.25:8006", details.get(ProxmoxResource.DETAIL_ENDPOINT));
            assertEquals("false", details.get(ProxmoxResource.DETAIL_VERIFY_TLS));
            assertNotNull(details.get(ProxmoxResource.DETAIL_NODE));
            assertNotNull(details.get(ProxmoxResource.DETAIL_NODE_IP));
            assertNotNull(details.get("guid"));
            // the given address first, then every online node, so the API stays reachable if one node is lost
            assertEquals("https://10.0.35.25:8006,https://10.0.32.196:8006,https://10.0.32.197:8006",
                    details.get(ProxmoxResource.DETAIL_ENDPOINTS));
        }
    }

    @Test(expected = DiscoveryException.class)
    public void findFailsWhenNoNodeIsOnline() throws Exception {
        when(client.getClusterName()).thenReturn("lab");
        when(client.getClusterNodes(anyString())).thenReturn(Arrays.asList(new ProxmoxApiClient.NodeInfo("pve1", "10.0.32.196", false)));
        discoverer.find(1L, 1L, 2L, URI.create("http://10.0.35.25:8006"), "root@pam!cloudstack", "secret", null);
    }
}

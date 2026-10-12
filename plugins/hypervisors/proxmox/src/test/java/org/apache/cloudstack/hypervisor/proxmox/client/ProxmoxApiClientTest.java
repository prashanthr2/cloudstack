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
package org.apache.cloudstack.hypervisor.proxmox.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

public class ProxmoxApiClientTest {

    @Test
    public void endpointDefaultsToHttpsAndPort8006() {
        assertEquals("https://10.0.35.25:8006", ProxmoxApiClient.endpointOf(URI.create("https://10.0.35.25")));
        assertEquals("https://10.0.35.25:8006", ProxmoxApiClient.endpointOf(URI.create("http://10.0.35.25:8006")));
        assertEquals("https://pve.example.org:9000", ProxmoxApiClient.endpointOf(URI.create("https://pve.example.org:9000")));
    }

    @Test(expected = ProxmoxApiException.class)
    public void endpointWithoutHostIsRejected() {
        ProxmoxApiClient.endpointOf(URI.create("/just/a/path"));
    }

    @Test
    public void theGivenPortIsUsed() {
        assertEquals("https://10.0.35.25:8443", ProxmoxApiClient.endpointOf(URI.create("http://10.0.35.25:8443")));
    }

    @Test
    public void everythingButTheAddressAndPortIsIgnored() {
        assertEquals("https://10.0.35.25:8006", ProxmoxApiClient.endpointOf(URI.create("http://10.0.35.25:8006/some/path?x=1")));
    }

    @Test
    public void parseDataReturnsTheDataNode() {
        JsonNode data = ProxmoxApiClient.parseData("{\"data\":{\"version\":\"8.4.21\"}}");
        assertEquals("8.4.21", data.path("version").asText());
    }

    @Test
    public void parseDataToleratesEmptyBodies() {
        assertTrue(ProxmoxApiClient.parseData("").isNull());
        assertTrue(ProxmoxApiClient.parseData("{}").isNull());
    }

    @Test
    public void shortVersionKeepsOnlyTheVersionPart() {
        assertEquals("8.4.21", ProxmoxApiClient.shortVersion("pve-manager/8.4.21/2606ac850d46da29"));
        assertEquals("8.4", ProxmoxApiClient.shortVersion("8.4"));
        assertNull(ProxmoxApiClient.shortVersion(null));
        assertNull(ProxmoxApiClient.shortVersion(" "));
    }

    @Test
    public void shortVersionFitsTheHostTableColumn() {
        String version = ProxmoxApiClient.shortVersion("pve-manager/" + "9".repeat(60) + "/abc");
        assertEquals(32, version.length());
    }

    @Test
    public void storageEntriesAreParsedWithTheirMountPointAndOptions() throws Exception {
        JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"data\":[{\"storage\":\"local\",\"type\":\"dir\",\"path\":\"/var/lib/vz\","
                + "\"content\":\"iso,images\"},{\"storage\":\"cs-1\",\"type\":\"nfs\",\"server\":\"10.0.32.4\",\"export\":\"/acs/p\","
                + "\"path\":\"/mnt/1\",\"content\":\"images\",\"options\":\"vers=3\"}]}").get("data");

        java.util.List<ProxmoxApiClient.StorageConfig> storages = ProxmoxApiClient.parseStorages(data);

        assertEquals(2, storages.size());
        assertEquals("nfs", storages.get(1).getType());
        assertEquals("10.0.32.4", storages.get(1).getServer());
        assertEquals("/acs/p", storages.get(1).getExport());
        assertEquals("/mnt/1", storages.get(1).getPath());
        assertEquals("vers=3", storages.get(1).getOptions());
        assertNull(storages.get(0).getServer());
    }

    @Test
    public void storageStatusIsParsedFromTheNodesAnswer() throws Exception {
        JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"data\":{\"active\":1,\"enabled\":1,\"shared\":1,\"type\":\"nfs\","
                + "\"total\":4293893554176,\"used\":3483397783552,\"avail\":810495770624}}").get("data");

        ProxmoxApiClient.StorageStatus status = ProxmoxApiClient.parseStorageStatus(data);

        assertTrue(status.isActive());
        assertEquals(4293893554176L, status.getTotalBytes());
        assertEquals(3483397783552L, status.getUsedBytes());
        assertEquals(810495770624L, status.getAvailableBytes());
    }

    @Test
    public void aStorageThatIsNotMountedOnTheNodeIsNotActive() throws Exception {
        JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"data\":{\"active\":0,\"enabled\":1}}").get("data");
        assertTrue(!ProxmoxApiClient.parseStorageStatus(data).isActive());
    }
}

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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.junit.Test;

/** The VM, task and template-storage calls, against a fake Proxmox that answers by "METHOD path". */
public class ProxmoxApiClientVmTest {
    private static final String UPID = "UPID:pve1:00010B1A:00246999:6ACCFCCB:qmcreate:9202:pveaudit@pam!cloudstack:";
    /** An answer "@400" is an HTTP error with that status. */
    private static final String ERROR_PREFIX = "@";

    private final Map<String, List<String>> answers = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int pauses;
    private ProxmoxApiClient client;

    @Before
    public void setUp() throws Exception {
        CloseableHttpClient http = mock(CloseableHttpClient.class);
        when(http.execute(any(HttpUriRequest.class))).thenAnswer(invocation -> {
            HttpUriRequest request = invocation.getArgument(0);
            String key = request.getMethod() + " " + request.getURI().getRawPath()
                    + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery());
            requests.add(key);
            if (request instanceof HttpEntityEnclosingRequest && ((HttpEntityEnclosingRequest) request).getEntity() != null) {
                bodies.add(EntityUtils.toString(((HttpEntityEnclosingRequest) request).getEntity()));
            } else {
                bodies.add("");
            }
            List<String> queue = answers.get(key);
            if (queue == null) {
                return response(404, "");
            }
            String body = queue.size() > 1 ? queue.remove(0) : queue.get(0);
            if (body.startsWith(ERROR_PREFIX)) {
                return response(Integer.parseInt(body.substring(ERROR_PREFIX.length())), "");
            }
            return response(200, body);
        });
        client = new ProxmoxApiClient(Collections.singletonList("https://pve1:8006"), "pveaudit@pam!cloudstack", "secret", http) {
            @Override
            protected void pause(long millis) {
                pauses++;
            }
        };
    }

    private static CloseableHttpResponse response(int status, String body) throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(status);
        when(statusLine.getReasonPhrase()).thenReturn("reason");
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity()).thenReturn(new StringEntity(body));
        return response;
    }

    private void answer(String key, String... bodiesInOrder) {
        answers.put(key, new ArrayList<>(java.util.Arrays.asList(bodiesInOrder)));
    }

    private static String task(String upid) {
        return "{\"data\":\"" + upid + "\"}";
    }

    @Test
    public void nextVmIdIsReadFromTheClusterAsAString() {
        answer("GET /api2/json/cluster/nextid", "{\"data\":\"9205\"}");

        assertEquals(9205L, client.nextVmId());
    }

    @Test(expected = ProxmoxApiException.class)
    public void nextVmIdFailsWhenProxmoxReturnsNothing() {
        answer("GET /api2/json/cluster/nextid", "{\"data\":null}");

        client.nextVmId();
    }

    @Test
    public void createVmSendsTheIdAndEveryOptionUrlEncoded() {
        answer("POST /api2/json/nodes/pve1/qemu", task(UPID));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", "i-2-10-VM");
        params.put("scsi0", "cs-1:0,import-from=cst-2:import/f.qcow2,format=qcow2");

        String upid = client.createVm("pve1", 9202, params);

        assertEquals(UPID, upid);
        String body = bodies.get(0);
        assertTrue(body, body.contains("vmid=9202"));
        assertTrue(body, body.contains("name=i-2-10-VM"));
        // commas, colons, slashes and equals signs inside a value must be encoded, or Proxmox reads the option wrongly
        assertTrue(body, body.contains("scsi0=cs-1%3A0%2Cimport-from%3Dcst-2%3Aimport%2Ff.qcow2%2Cformat%3Dqcow2"));
    }

    @Test(expected = ProxmoxApiException.class)
    public void createVmFailsWhenNoTaskIdComesBack() {
        answer("POST /api2/json/nodes/pve1/qemu", "{\"data\":null}");

        client.createVm("pve1", 9202, Collections.emptyMap());
    }

    @Test
    public void destroyDeletesTheVmWithItsDisks() {
        answer("DELETE /api2/json/nodes/pve1/qemu/9202?purge=1", task(UPID));

        assertEquals(UPID, client.destroyVm("pve1", 9202));
    }

    @Test
    public void shutdownFallsBackToAForcedStopAfterTheTimeout() {
        answer("POST /api2/json/nodes/pve1/qemu/9202/status/shutdown", task(UPID));

        client.shutdownVm("pve1", 9202, 60);

        assertTrue(bodies.get(0), bodies.get(0).contains("timeout=60"));
        assertTrue(bodies.get(0), bodies.get(0).contains("forceStop=1"));
    }

    @Test
    public void migrateNamesTheTargetNode() {
        answer("POST /api2/json/nodes/pve1/qemu/9202/migrate", task(UPID));

        client.migrateVm("pve1", 9202, "pve2");

        assertEquals("target=pve2", bodies.get(0));
    }

    @Test
    public void waitForTaskReturnsWhenTheTaskStoppedWithOk() {
        String status = "GET /api2/json/nodes/pve1/tasks/" + UPID + "/status";
        answer(status, "{\"data\":{\"status\":\"running\"}}", "{\"data\":{\"status\":\"running\"}}",
                "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}");

        client.waitForTask(UPID, 60);

        assertEquals(2, pauses);
    }

    @Test
    public void waitForTaskAcceptsWarnings() {
        answer("GET /api2/json/nodes/pve1/tasks/" + UPID + "/status", "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: 1\"}}");

        client.waitForTask(UPID, 60);
    }

    @Test
    public void aFailedTaskReportsProxmoxsReasonAndTheEndOfItsLog() {
        answer("GET /api2/json/nodes/pve1/tasks/" + UPID + "/status",
                "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"unable to create VM 9202 - volume does not exist\"}}");
        answer("GET /api2/json/nodes/pve1/tasks/" + UPID + "/log?limit=500",
                "{\"data\":[{\"n\":1,\"t\":\"starting\"},{\"n\":2,\"t\":\"qemu-img: could not open the source\"}]}");

        try {
            client.waitForTask(UPID, 60);
            fail("expected the task to be reported as failed");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("qmcreate"));
            assertTrue(e.getMessage(), e.getMessage().contains("unable to create VM 9202 - volume does not exist"));
            assertTrue(e.getMessage(), e.getMessage().contains("qemu-img: could not open the source"));
        }
    }

    @Test
    public void aFailedTaskIsStillReportedWhenItsLogCannotBeRead() {
        answer("GET /api2/json/nodes/pve1/tasks/" + UPID + "/status", "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"boom\"}}");

        try {
            client.waitForTask(UPID, 60);
            fail("expected the task to be reported as failed");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().endsWith("boom"));
        }
    }

    @Test
    public void waitForTaskGivesUpWhenTheTaskIsStillRunning() {
        answer("GET /api2/json/nodes/pve1/tasks/" + UPID + "/status", "{\"data\":{\"status\":\"running\"}}");

        try {
            client.waitForTask(UPID, 0);
            fail("expected a timeout");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not finish within 0 seconds"));
        }
    }

    @Test
    public void taskIdsAreSplitIntoNodeAndType() {
        assertEquals("pve1", ProxmoxApiClient.taskNode(UPID));
        assertEquals("qmcreate", ProxmoxApiClient.taskType(UPID));
    }

    @Test(expected = ProxmoxApiException.class)
    public void aStringThatIsNotATaskIdIsRejected() {
        ProxmoxApiClient.taskNode("not-a-task");
    }

    @Test
    public void vmsAreFoundClusterWideByNameAndIdIgnoringTemplatesAndContainers() {
        answer("GET /api2/json/cluster/resources?type=vm", "{\"data\":["
                + "{\"type\":\"qemu\",\"vmid\":9202,\"name\":\"i-2-10-VM\",\"node\":\"pve2\",\"status\":\"running\"},"
                + "{\"type\":\"qemu\",\"vmid\":9000,\"name\":\"base\",\"node\":\"pve1\",\"status\":\"stopped\",\"template\":1},"
                + "{\"type\":\"lxc\",\"vmid\":9300,\"name\":\"ct\",\"node\":\"pve1\",\"status\":\"running\"}]}");

        ProxmoxApiClient.VmLocation byName = client.findVmByName("i-2-10-VM");
        assertEquals(9202L, byName.getVmid());
        assertEquals("pve2", byName.getNode());
        assertTrue(byName.isRunning());
        assertEquals("i-2-10-VM", client.findVm(9202).getName());
        assertNull(client.findVmByName("base"));
        assertNull(client.findVm(9300));
    }

    @Test
    public void aTemplateDirectoryEntryExposesOneFolderOfAnAlreadyMountedPathAsImagesContent() {
        answer("POST /api2/json/storage", "{\"data\":{\"type\":\"dir\",\"storage\":\"cst-2\"}}");

        client.createTemplateDirStorage("cst-2", "/mnt/css-1", "template/tmpl/1");

        String body = bodies.get(0);
        assertTrue(body, body.contains("storage=cst-2"));
        assertTrue(body, body.contains("type=dir"));
        assertTrue(body, body.contains("path=%2Fmnt%2Fcss-1"));
        assertTrue(body, body.contains("content=images"));
        assertTrue(body, body.contains("content-dirs=images%3Dtemplate%2Ftmpl%2F1"));
        // nothing is mounted by it, and it is offline where the NFS entry is not mounted
        assertTrue(body, body.contains("is_mountpoint=yes"));
        assertTrue(body, body.contains("shared=1"));
        // the share is read-only, so Proxmox must not try to create folders in it
        assertTrue(body, body.contains("create-subdirs=0"));
        assertTrue(body, body.contains("create-base-path=0"));
    }

    @Test
    public void theSecondaryStoreIsAddedAsAnNfsEntryThatDoesNotTryToCreateFoldersInTheReadOnlyShare() {
        answer("POST /api2/json/storage", "{\"data\":{\"type\":\"nfs\",\"storage\":\"css-1\"}}");

        client.createSecondaryNfsStorage("css-1", "10.0.32.4", "/acs/secondary", "/mnt/css-1", "vers=3,ro");

        String body = bodies.get(0);
        assertTrue(body, body.contains("storage=css-1"));
        assertTrue(body, body.contains("type=nfs"));
        assertTrue(body, body.contains("export=%2Facs%2Fsecondary"));
        assertTrue(body, body.contains("path=%2Fmnt%2Fcss-1"));
        assertTrue(body, body.contains("options=vers%3D3%2Cro"));
        assertTrue(body, body.contains("create-subdirs=0"));
    }

    @Test
    public void aConsoleIsOpenedForAWebsocketAndItsPortTicketAndPasswordAreRead() {
        answer("POST /api2/json/nodes/pve1/qemu/9204/vncproxy", "{\"data\":{\"port\":5900,\"ticket\":\"PVEVNC:68F1::abc\",\"password\":\"s3cr3t!!\","
                + "\"user\":\"pveaudit@pam!cloudstack\",\"upid\":\"UPID:x\"}}");

        ProxmoxApiClient.ConsoleSession session = client.openConsole("pve1", 9204);

        assertEquals("websocket=1", bodies.get(0));
        assertEquals(5900, session.getPort());
        assertEquals("PVEVNC:68F1::abc", session.getTicket());
        assertEquals("s3cr3t!!", session.getPassword());
        assertEquals("pveaudit@pam!cloudstack", session.getUser());
    }

    @Test(expected = ProxmoxApiException.class)
    public void aConsoleAnswerWithoutAPortIsRejected() {
        answer("POST /api2/json/nodes/pve1/qemu/9204/vncproxy", "{\"data\":{\"ticket\":\"t\"}}");

        client.openConsole("pve1", 9204);
    }

    @Test
    public void aVmsConfigurationIsReadAsOptionNamesAndValues() {
        answer("GET /api2/json/nodes/pve1/qemu/9204/config", "{\"data\":{\"name\":\"i-2-10-VM\",\"memory\":512,"
                + "\"scsi0\":\"cs-1:9204/vm-9204-disk-0.qcow2,size=8G\"}}");

        Map<String, String> config = client.getVmConfig("pve1", 9204);

        assertEquals("i-2-10-VM", config.get("name"));
        assertEquals("512", config.get("memory"));
        assertEquals("cs-1:9204/vm-9204-disk-0.qcow2,size=8G", config.get("scsi0"));
    }

    @Test
    public void deletingAStorageAcceptsAnEmptyAnswer() {
        answer("DELETE /api2/json/storage/cst-2", "");

        client.deleteStorage("cst-2");
    }

    // ---- migration, bridges and HA (Phase 5) ----

    @Test
    public void aRunningVmIsMovedWithItsMemory() {
        answer("POST /api2/json/nodes/pve1/qemu/9204/migrate", task("UPID:pve1:1:2:3:qmigrate:9204:u:"));

        assertEquals("UPID:pve1:1:2:3:qmigrate:9204:u:", client.migrateRunningVm("pve1", 9204, "pve2"));
        assertEquals("target=pve2&online=1", bodies.get(0));
    }

    @Test
    public void theStateOfAVmOnANodeIsNullWhenTheNodeDoesNotHaveIt() {
        answer("GET /api2/json/nodes/pve1/qemu/9204/status/current", "{\"data\":{\"status\":\"running\",\"vmid\":9204}}");

        assertEquals("running", client.getVmStatus("pve1", 9204));
        assertNull(client.getVmStatus("pve2", 9204));
    }

    @Test
    public void aNodeWithAnEmptyEntryInNotAllowedNodesIsAllowed() {
        answer("GET /api2/json/nodes/pve1/qemu/9204/migrate?target=pve2", "{\"data\":{\"allowed_nodes\":[\"pve2\"],"
                + "\"not_allowed_nodes\":{\"pve2\":{},\"pve3\":{}},\"local_disks\":[],\"local_resources\":[],\"running\":1}}");

        assertTrue(client.getMigrationProblems("pve1", 9204, "pve2").isEmpty());
    }

    @Test
    public void everyReasonAgainstAMoveIsReported() {
        answer("GET /api2/json/nodes/pve1/qemu/9204/migrate?target=pve2", "{\"data\":{\"allowed_nodes\":[],"
                + "\"not_allowed_nodes\":{\"pve2\":{\"unavailable_storages\":[\"local-lvm\"]}},\"local_resources\":[\"pci0\"],"
                + "\"local_disks\":[{\"volid\":\"local:iso/x.iso\",\"shared\":0,\"is_unused\":0},"
                + "{\"volid\":\"cs-1:9204/y\",\"shared\":1,\"is_unused\":0},{\"volid\":\"local:old\",\"shared\":0,\"is_unused\":1}]}}");

        List<String> problems = client.getMigrationProblems("pve1", 9204, "pve2");

        assertEquals(3, problems.size());
        assertTrue(problems.get(0), problems.get(0).contains("unavailable_storages"));
        assertTrue(problems.get(1), problems.get(1).contains("pci0"));
        assertTrue(problems.get(2), problems.get(2).contains("local:iso/x.iso"));
    }

    @Test
    public void aTargetThatIsNotListedAtAllIsAProblem() {
        answer("GET /api2/json/nodes/pve1/qemu/9204/migrate?target=pve2", "{\"data\":{\"allowed_nodes\":[\"pve3\"],\"not_allowed_nodes\":{},"
                + "\"local_disks\":[],\"local_resources\":[]}}");

        assertEquals(1, client.getMigrationProblems("pve1", 9204, "pve2").size());
    }

    @Test
    public void bridgesAreReadWithTheirStateAndVlanAwareness() {
        answer("GET /api2/json/nodes/pve1/network", "{\"data\":[{\"iface\":\"eth0\",\"type\":\"eth\",\"active\":1},"
                + "{\"iface\":\"vmbr0\",\"type\":\"bridge\",\"active\":1},"
                + "{\"iface\":\"vmbr1\",\"type\":\"bridge\",\"active\":1,\"bridge_vlan_aware\":1},"
                + "{\"iface\":\"vmbr2\",\"type\":\"bridge\",\"active\":0,\"bridge_vlan_aware\":1},"
                + "{\"iface\":\"ovs0\",\"type\":\"OVSBridge\",\"active\":1}]}");

        List<ProxmoxApiClient.NodeBridge> bridges = client.listBridges("pve1");

        assertEquals(4, bridges.size());
        assertTrue(!bridges.get(0).isVlanAware() && bridges.get(0).isActive());
        assertTrue(bridges.get(1).isVlanAware());
        assertTrue(!bridges.get(2).isActive());
        assertTrue(bridges.get(3).isVlanAware());
    }

    @Test
    public void anHaResourceIsFoundBySidAndCreatedAndDeletedByTheVmId() {
        answer("GET /api2/json/cluster/ha/resources", "{\"data\":[{\"sid\":\"vm:100\",\"state\":\"started\",\"comment\":\"CloudStack\"},"
                + "{\"sid\":\"vm:101\",\"state\":\"stopped\"}]}");
        answer("POST /api2/json/cluster/ha/resources", "{\"data\":null}");
        answer("DELETE /api2/json/cluster/ha/resources/vm:100", "{\"data\":null}");

        ProxmoxApiClient.HaResource found = client.findHaResource(100);
        assertEquals("CloudStack", found.getComment());
        assertEquals("started", found.getState());
        assertNull(client.findHaResource(102));
        assertNull(client.findHaResource(101).getComment());

        client.createHaResource(9204, "CloudStack");
        assertEquals("sid=vm%3A9204&state=started&comment=CloudStack", bodies.get(bodies.size() - 1));
        client.deleteHaResource(100);
        assertEquals("DELETE /api2/json/cluster/ha/resources/vm:100", requests.get(requests.size() - 1));
    }

    // ---- ISOs (Phase 6) ----

    @Test
    public void theEntryOfAnIsoFolderIsADirectoryOnTheSecondaryMountThatIsSharedAndReadOnlyLike() {
        answer("POST /api2/json/storage", "{\"data\":{\"storage\":\"csi-x\",\"type\":\"dir\"}}");

        client.createIsoDirStorage("csi-x", "/mnt/css-y", "template/tmpl/2/211");

        String body = bodies.get(0);
        assertTrue(body, body.contains("storage=csi-x") && body.contains("type=dir") && body.contains("path=%2Fmnt%2Fcss-y"));
        assertTrue(body, body.contains("content=iso") && body.contains("content-dirs=iso%3Dtemplate%2Ftmpl%2F2%2F211"));
        assertTrue(body, body.contains("is_mountpoint=yes") && body.contains("shared=1"));
        assertTrue(body, body.contains("create-subdirs=0") && body.contains("create-base-path=0"));
    }

    @Test
    public void theRunningConfigurationIsReadWithCurrentAndNotThePendingOne() {
        answer("GET /api2/json/nodes/pve1/qemu/9204/config?current=1", "{\"data\":{\"ide2\":\"none,media=cdrom\"}}");

        Map<String, String> config = client.getRunningVmConfig("pve1", 9204);

        assertEquals("none,media=cdrom", config.get("ide2"));
        assertNull(config.get("ide3"));
    }

    // ---- data volumes (Phase 7) ----

    private static final String VOLUME = "cs-7fd5:900000002/vm-900000002-abc.qcow2";
    private static final String VOLUME_PATH = "cs-7fd5%3A900000002%2Fvm-900000002-abc.qcow2";

    @Test
    public void anImageIsAllocatedForAnOwnerWithAFileNameAndASizeAndTheVolumeIdIsReturned() {
        answer("POST /api2/json/nodes/pve1/storage/cs-7fd5/content", "{\"data\":\"" + VOLUME + "\"}");

        assertEquals(VOLUME, client.allocateImage("pve1", "cs-7fd5", 900000002L, "vm-900000002-abc.qcow2", 5));
        String body = bodies.get(0);
        assertTrue(body, body.contains("vmid=900000002") && body.contains("filename=vm-900000002-abc.qcow2") && body.contains("size=5G") && body.contains("format=qcow2"));
    }

    @Test(expected = ProxmoxApiException.class)
    public void anAllocationThatAnswersWithoutAVolumeIdFails() {
        answer("POST /api2/json/nodes/pve1/storage/cs-7fd5/content", "{\"data\":null}");

        client.allocateImage("pve1", "cs-7fd5", 900000002L, "vm-900000002-abc.qcow2", 5);
    }

    @Test
    public void theAttributesOfAnImageAreReadWithTheVolumeIdEncodedInThePath() {
        answer("GET /api2/json/nodes/pve1/storage/cs-7fd5/content/" + VOLUME_PATH,
                "{\"data\":{\"format\":\"qcow2\",\"used\":524288,\"size\":1073741824,\"path\":\"/mnt/x/images/900000002/vm-900000002-abc.qcow2\"}}");

        ProxmoxApiClient.ImageInfo info = client.getImageInfo("pve1", VOLUME);

        assertEquals(1073741824L, info.getSize());
        assertEquals(524288L, info.getUsed());
        assertEquals("qcow2", info.getFormat());
    }

    @Test
    public void anImageThatDoesNotExistIsNullButARefusalIsAnError() {
        // the fake answers 404 for what it does not know
        assertNull(client.getImageInfo("pve1", VOLUME));
    }

    @Test
    public void anImageIsDeletedWithItsEncodedVolumeIdAndTheTaskIsReturned() {
        answer("DELETE /api2/json/nodes/pve1/storage/cs-7fd5/content/" + VOLUME_PATH, task("UPID:pve1:1:2:3:imgdel:900000002:u:"));

        assertEquals("UPID:pve1:1:2:3:imgdel:900000002:u:", client.deleteImage("pve1", VOLUME));
    }

    @Test
    public void theRunningConfigurationOfTheResizeAnswerIsATask() {
        answer("PUT /api2/json/nodes/pve1/qemu/9204/resize", task("UPID:pve1:1:2:3:resize:9204:u:"));

        assertEquals("UPID:pve1:1:2:3:resize:9204:u:", client.resizeDisk("pve1", 9204, "scsi1", "5G"));
        assertEquals("disk=scsi1&size=5G", bodies.get(0));
    }

    @Test
    public void aStopAbortsAShutdownThatIsStillRunning() {
        answer("POST /api2/json/nodes/pve1/qemu/9204/status/stop", task("UPID:pve1:1:2:3:qmstop:9204:u:"));

        assertEquals("UPID:pve1:1:2:3:qmstop:9204:u:", client.stopVm("pve1", 9204));
        assertEquals("overrule-shutdown=1", bodies.get(0));
    }

    @Test
    public void aStopIsRepeatedWithoutTheOptionWhenProxmoxRefusesItAsForAnHaResource() {
        answer("POST /api2/json/nodes/pve1/qemu/9204/status/stop", "@400", task("UPID:pve1:1:2:3:hastop:9204:u:"));

        assertEquals("UPID:pve1:1:2:3:hastop:9204:u:", client.stopVm("pve1", 9204));
        assertEquals(2, bodies.size());
        assertEquals("overrule-shutdown=1", bodies.get(0));
        assertEquals("", bodies.get(1));
    }

    @Test
    public void aStopThatFailsForAnotherReasonIsNotRepeated() {
        answer("POST /api2/json/nodes/pve1/qemu/9204/status/stop", "@403");

        try {
            client.stopVm("pve1", 9204);
            org.junit.Assert.fail("a refusal must be reported");
        } catch (ProxmoxApiException e) {
            assertEquals(403, e.getStatusCode());
        }
        assertEquals(1, bodies.size());
    }

    @Test
    public void theNetworkConfigurationListsTheInterfacesAndPendingChanges() {
        answer("GET /api2/json/nodes/pve1/network", "{\"data\":[{\"iface\":\"vmbr0\",\"type\":\"bridge\",\"cidr\":\"10.0.35.25/20\"},"
                + "{\"iface\":\"eth1\",\"type\":\"eth\"}],\"changes\":\"--- /etc/network/interfaces\\n+++ interfaces.new\"}");

        ProxmoxApiClient.NetworkConfig config = client.getNetworkConfig("pve1");

        assertEquals(2, config.getInterfaces().size());
        assertTrue(config.hasPendingChanges());
        assertTrue(config.find("vmbr0").isBridge());
        assertEquals("10.0.35.25/20", config.find("vmbr0").getCidr());
        assertTrue(!config.find("eth1").isBridge());
        assertNull(config.find("eth1").getCidr());
        assertNull(config.find("cloud0"));
    }

    @Test
    public void noChangesKeyMeansNothingIsPending() {
        answer("GET /api2/json/nodes/pve1/network", "{\"data\":[{\"iface\":\"vmbr0\",\"type\":\"bridge\"}]}");

        assertTrue(!client.getNetworkConfig("pve1").hasPendingChanges());
    }

    @Test
    public void aBridgeIsSavedWithoutPortsThenAppliedAsATaskThatIsWaitedFor() {
        answer("POST /api2/json/nodes/pve1/network", "{\"data\":null}");
        answer("PUT /api2/json/nodes/pve1/network", task("UPID:pve1:00010B1A:00246999:6ACCFCCB:srvreload:networking:pveaudit@pam!cloudstack:"));
        answer("GET /api2/json/nodes/pve1/tasks/UPID:pve1:00010B1A:00246999:6ACCFCCB:srvreload:networking:pveaudit@pam!cloudstack:/status",
                "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}");

        client.createBridge("pve1", "cloud0", "169.254.0.1/16");
        client.applyNetworkConfig("pve1", 30);

        assertEquals("iface=cloud0&type=bridge&cidr=169.254.0.1%2F16&autostart=1", bodies.get(0));
        assertTrue(requests.get(1).startsWith("PUT /api2/json/nodes/pve1/network"));
    }

    @Test
    public void revertingDeletesThePendingChanges() {
        answer("DELETE /api2/json/nodes/pve1/network", "{\"data\":null}");

        client.revertNetworkConfig("pve1");

        assertEquals("DELETE /api2/json/nodes/pve1/network", requests.get(0));
    }

    @Test
    public void theGuestAgentAnswersOrIsNotRunningYet() {
        answer("POST /api2/json/nodes/pve1/qemu/9300/agent/ping", "{\"data\":{}}");
        assertTrue(client.agentPing("pve1", 9300));

        answer("POST /api2/json/nodes/pve1/qemu/9300/agent/ping", "@500");
        assertTrue(!client.agentPing("pve1", 9300));
    }

    @Test
    public void aRefusedGuestAgentCallIsAnErrorNotJustNotYet() {
        answer("POST /api2/json/nodes/pve1/qemu/9300/agent/ping", "@403");
        try {
            client.agentPing("pve1", 9300);
            fail("a refused privilege must be reported");
        } catch (ProxmoxApiException e) {
            assertEquals(403, e.getStatusCode());
        }
    }

    @Test
    public void aFileIsWrittenInTheGuestWithItsPathAndContentEncodedInTheForm() {
        answer("POST /api2/json/nodes/pve1/qemu/9300/agent/file-write", "{\"data\":null}");

        client.agentFileWrite("pve1", 9300, "/var/cache/cloud/cmdline", "type=router name=r-1-VM");

        assertEquals("file=%2Fvar%2Fcache%2Fcloud%2Fcmdline&content=type%3Drouter+name%3Dr-1-VM", bodies.get(0));
    }

    @Test
    public void aCommandIsStartedWithEveryPartOfItAsARepeatedFormValueAndItsInput() {
        answer("POST /api2/json/nodes/pve1/qemu/9300/agent/exec", "{\"data\":{\"pid\":4711}}");

        long pid = client.agentExec("pve1", 9300, java.util.Arrays.asList("/bin/sh", "-c", "echo hi"), "aGk=");

        assertEquals(4711L, pid);
        assertEquals("command=%2Fbin%2Fsh&command=-c&command=echo+hi&input-data=aGk%3D", bodies.get(0));
    }

    @Test(expected = ProxmoxApiException.class)
    public void aCommandThatGetsNoProcessIdIsAnError() {
        answer("POST /api2/json/nodes/pve1/qemu/9300/agent/exec", "{\"data\":null}");

        client.agentExec("pve1", 9300, java.util.Arrays.asList("/bin/true"), null);
    }

    @Test
    public void theStatusOfACommandHasItsExitCodeAndOutput() {
        answer("GET /api2/json/nodes/pve1/qemu/9300/agent/exec-status?pid=4711",
                "{\"data\":{\"exited\":1,\"exitcode\":3,\"out-data\":\"hi\\n\",\"err-data\":\"err\\n\"}}");

        ProxmoxApiClient.AgentExecStatus status = client.agentExecStatus("pve1", 9300, 4711);

        assertTrue(status.isExited());
        assertEquals(3, status.getExitCode());
        assertEquals("hi\n", status.getOut());
        assertEquals("err\n", status.getErr());
    }

    @Test
    public void aCommandThatIsStillRunningHasNotExited() {
        answer("GET /api2/json/nodes/pve1/qemu/9300/agent/exec-status?pid=4711", "{\"data\":{\"exited\":0}}");

        ProxmoxApiClient.AgentExecStatus status = client.agentExecStatus("pve1", 9300, 4711);

        assertTrue(!status.isExited());
        assertEquals("", status.getOut());
    }

    @Test
    public void thePatchEntryIsADirectoryThatIsNotSharedForIsoFiles() {
        answer("POST /api2/json/storage", "{\"data\":{\"storage\":\"csp-1\",\"type\":\"dir\"}}");

        client.createPatchStorage("csp-1", "/var/lib/cloudstack-patch");

        assertEquals("storage=csp-1&type=dir&path=%2Fvar%2Flib%2Fcloudstack-patch&content=iso&shared=0", bodies.get(0));
    }

    @Test
    public void theFilesOfAStorageEntryAreListedWithTheirSize() {
        answer("GET /api2/json/nodes/pve1/storage/csp-1/content?content=iso",
                "{\"data\":[{\"volid\":\"csp-1:iso/a.iso\",\"size\":2048,\"format\":\"iso\"},{\"volid\":\"csp-1:iso/b.iso\",\"size\":4096}]}");

        List<ProxmoxApiClient.ContentItem> items = client.listContent("pve1", "csp-1", "iso");

        assertEquals(2, items.size());
        assertEquals("csp-1:iso/a.iso", items.get(0).getVolid());
        assertEquals(4096L, items.get(1).getSize());
    }

    @Test
    public void anIsoIsUploadedAsMultipartWithTheFileFollowedByZerosUpToWholeSectors() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("payload", ".bin");
        try {
            java.nio.file.Files.write(file, new byte[] {1, 2, 3, 4, 5});
            answer("POST /api2/json/nodes/pve1/storage/csp-1/upload", task("UPID:pve1:00010B1A:00246999:6ACCFCCB:imgcopy::pveaudit@pam!cloudstack:"));

            String upid = client.uploadIso("pve1", "csp-1", "cs-patch-abc.iso", file, 2048);

            assertTrue(upid.startsWith("UPID:pve1"));
            String body = bodies.get(0);
            assertTrue(body, body.contains("name=\"content\"\r\n\r\niso\r\n"));
            assertTrue(body, body.contains("name=\"filename\"; filename=\"cs-patch-abc.iso\""));
            // the file part is exactly the padded size: 5 bytes then zeros up to 2048
            int start = body.indexOf("Content-Type: application/octet-stream\r\n\r\n") + "Content-Type: application/octet-stream\r\n\r\n".length();
            int end = body.indexOf("\r\n--", start);
            assertEquals(2048, end - start);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test(expected = ProxmoxApiException.class)
    public void anUploadOfAFileThatCannotBeReadFailsWithAMessage() {
        client.uploadIso("pve1", "csp-1", "x.iso", java.nio.file.Paths.get("/nonexistent/agent.zip"), 2048);
    }
}

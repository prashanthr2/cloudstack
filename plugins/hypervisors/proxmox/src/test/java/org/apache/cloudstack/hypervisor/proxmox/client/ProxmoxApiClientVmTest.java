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
            return response(200, queue.size() > 1 ? queue.remove(0) : queue.get(0));
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
    public void aTemplateStorageExposesOneFolderAsImagesContentReadOnly() {
        answer("POST /api2/json/storage", "{\"data\":{\"type\":\"nfs\",\"storage\":\"cst-2\"}}");

        client.createTemplateStorage("cst-2", "10.0.32.4", "/acs/secondary", "template/tmpl/1", "vers=3,ro");

        String body = bodies.get(0);
        assertTrue(body, body.contains("storage=cst-2"));
        assertTrue(body, body.contains("type=nfs"));
        assertTrue(body, body.contains("content=images"));
        assertTrue(body, body.contains("content-dirs=images%3Dtemplate%2Ftmpl%2F1"));
        assertTrue(body, body.contains("options=vers%3D3%2Cro"));
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
}

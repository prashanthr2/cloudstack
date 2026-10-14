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
import java.util.List;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.junit.Test;

/** The root@pam login: the ticket and the CSRF token are used, kept, and renewed. */
public class ProxmoxApiClientLoginTest {
    private static final String TICKET = "{\"data\":{\"ticket\":\"PVE:root@pam:TICKET1\",\"CSRFPreventionToken\":\"CSRF1\"}}";

    private final List<String> requests = new ArrayList<>();
    private final List<String> cookies = new ArrayList<>();
    private final List<String> csrfTokens = new ArrayList<>();
    private final List<String> loginBodies = new ArrayList<>();
    private int loginStatus = 200;
    private String loginAnswer = TICKET;
    private int rejectNextCalls;
    private ProxmoxApiClient client;

    @Before
    public void setUp() throws Exception {
        CloseableHttpClient http = mock(CloseableHttpClient.class);
        when(http.execute(any(HttpUriRequest.class))).thenAnswer(invocation -> {
            HttpUriRequest request = invocation.getArgument(0);
            String path = request.getURI().getRawPath();
            requests.add(request.getMethod() + " " + path);
            if (path.endsWith("/access/ticket")) {
                loginBodies.add(EntityUtils.toString(((HttpEntityEnclosingRequest) request).getEntity()));
                return response(loginStatus, loginAnswer);
            }
            cookies.add(request.getFirstHeader("Cookie") == null ? null : request.getFirstHeader("Cookie").getValue());
            csrfTokens.add(request.getFirstHeader("CSRFPreventionToken") == null ? null : request.getFirstHeader("CSRFPreventionToken").getValue());
            if (rejectNextCalls > 0) {
                rejectNextCalls--;
                return response(401, "");
            }
            return response(200, "{\"data\":{\"version\":\"8.4.21\"}}");
        });
        client = ProxmoxApiClient.withRootLogin(Collections.singletonList("https://pve1:8006"), "s3cret", http);
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

    @Test
    public void theFirstCallLogsInAsRootWithThePassword() {
        client.get("/version");

        assertEquals(1, loginBodies.size());
        assertTrue(loginBodies.get(0), loginBodies.get(0).contains("username=root%40pam"));
        assertTrue(loginBodies.get(0), loginBodies.get(0).contains("password=s3cret"));
        assertEquals("PVEAuthCookie=PVE:root@pam:TICKET1", cookies.get(0));
    }

    @Test
    public void theTicketIsKeptForTheNextCalls() {
        client.get("/version");
        client.get("/version");
        client.post("/nodes/pve1/qemu", Collections.emptyMap());

        assertEquals(1, loginBodies.size());
    }

    @Test
    public void onlyChangesCarryTheCsrfToken() {
        client.get("/version");
        client.post("/nodes/pve1/qemu", Collections.emptyMap());

        assertNull(csrfTokens.get(0));
        assertEquals("CSRF1", csrfTokens.get(1));
    }

    @Test
    public void aRefusedTicketIsReplacedOnceAndTheCallRepeated() {
        client.get("/version");
        rejectNextCalls = 1;

        assertEquals("8.4.21", client.getVersion());

        assertEquals(2, loginBodies.size());
    }

    @Test
    public void aTicketThatKeepsBeingRefusedIsReportedAsFailed() {
        rejectNextCalls = 5;

        try {
            client.get("/version");
            fail("expected the call to fail");
        } catch (ProxmoxApiException e) {
            assertEquals(401, e.getStatusCode());
        }
        assertEquals(2, loginBodies.size());
    }

    @Test
    public void aWrongPasswordIsReportedAsARefusedLogin() {
        loginStatus = 401;
        loginAnswer = "";

        try {
            client.login();
            fail("expected the login to be refused");
        } catch (ProxmoxApiException e) {
            assertEquals(401, e.getStatusCode());
            assertTrue(e.getMessage(), e.getMessage().contains("refused the login of root@pam"));
        }
    }

    @Test
    public void aLoginAnsweredWithoutATicketIsReportedAsATwoFactorProblem() {
        loginAnswer = "{\"data\":{\"NeedTFA\":1}}";

        try {
            client.login();
            fail("expected the login to fail");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("two-factor"));
        }
    }

    @Test
    public void thePasswordIsNeverPartOfAMessageThatIsReported() {
        loginStatus = 401;
        loginAnswer = "";

        try {
            client.login();
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), !e.getMessage().contains("s3cret"));
        }
    }
}

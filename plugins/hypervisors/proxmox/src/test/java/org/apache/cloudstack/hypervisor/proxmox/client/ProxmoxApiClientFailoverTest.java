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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Before;
import org.junit.Test;

public class ProxmoxApiClientFailoverTest {
    private static final String DOWN = "10.0.0.1";
    private static final String UP = "10.0.0.2";
    private static final List<String> ADDRESSES = Arrays.asList("https://" + DOWN + ":8006", "https://" + UP + ":8006");

    private CloseableHttpClient http;
    private final Set<String> reached = ConcurrentHashMap.newKeySet();

    @Before
    public void setUp() {
        http = mock(CloseableHttpClient.class);
    }

    private CloseableHttpResponse answer(int status, String body) throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(status);
        when(statusLine.getReasonPhrase()).thenReturn("reason");
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity()).thenReturn(new StringEntity(body));
        return response;
    }

    private void firstAddressRefusesConnections(CloseableHttpResponse onSecond) throws IOException {
        when(http.execute(any(HttpUriRequest.class))).thenAnswer(invocation -> {
            HttpUriRequest request = invocation.getArgument(0);
            reached.add(request.getURI().getHost());
            if (DOWN.equals(request.getURI().getHost())) {
                throw new IOException("Connection refused");
            }
            return onSecond;
        });
    }

    @Test
    public void movesToTheNextAddressWhenOneCannotBeReached() throws Exception {
        firstAddressRefusesConnections(answer(200, "{\"data\":{\"version\":\"8.4.21\"}}"));
        ProxmoxApiClient client = new ProxmoxApiClient(ADDRESSES, "root@pam!t", "secret", http);

        assertEquals("8.4.21", client.getVersion());
        assertEquals(2, reached.size());
    }

    @Test
    public void remembersTheAddressThatWorkedAndDoesNotRetryTheDeadOne() throws Exception {
        firstAddressRefusesConnections(answer(200, "{\"data\":{\"version\":\"8.4.21\"}}"));
        ProxmoxApiClient client = new ProxmoxApiClient(ADDRESSES, "root@pam!t", "secret", http);

        client.getVersion();
        client.getVersion();
        client.getVersion();

        // 1 refused attempt + 3 successful calls
        verify(http, times(4)).execute(any(HttpUriRequest.class));
    }

    @Test
    public void failsWhenNoAddressCanBeReached() throws Exception {
        when(http.execute(any(HttpUriRequest.class))).thenThrow(new IOException("Connection refused"));
        ProxmoxApiClient client = new ProxmoxApiClient(ADDRESSES, "root@pam!t", "secret", http);

        try {
            client.getVersion();
            fail("expected the call to fail");
        } catch (ProxmoxApiException e) {
            assertEquals(-1, e.getStatusCode());
        }
        verify(http, atLeastOnce()).execute(any(HttpUriRequest.class));
    }

    @Test
    public void anHttpErrorAnswerIsNotMistakenForAnUnreachableAddress() throws Exception {
        CloseableHttpResponse unauthorized = answer(401, "");
        when(http.execute(any(HttpUriRequest.class))).thenReturn(unauthorized);
        ProxmoxApiClient client = new ProxmoxApiClient(ADDRESSES, "root@pam!t", "bad", http);

        try {
            client.getVersion();
            fail("expected the call to fail");
        } catch (ProxmoxApiException e) {
            assertEquals(401, e.getStatusCode());
        }
        // the first address answered, so the second must not have been tried
        verify(http, times(1)).execute(any(HttpUriRequest.class));
        verify(http, never()).close();
    }

    @Test(expected = ProxmoxApiException.class)
    public void atLeastOneAddressIsRequired() {
        new ProxmoxApiClient(Collections.<String>emptyList(), "root@pam!t", "secret", http);
    }
}

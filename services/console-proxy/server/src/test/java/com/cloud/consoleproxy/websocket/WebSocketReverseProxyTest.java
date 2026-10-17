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
package com.cloud.consoleproxy.websocket;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.websocket.api.Session;
import org.java_websocket.client.WebSocketClient;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class WebSocketReverseProxyTest {

    @SuppressWarnings("unchecked")
    private Map<String, String> headersOf(WebSocketReverseProxy proxy) throws Exception {
        Field field = WebSocketClient.class.getDeclaredField("headers");
        field.setAccessible(true);
        Map<String, String> headers = (Map<String, String>) field.get(proxy);
        // the library creates the map when the first header is added
        return headers == null ? Collections.emptyMap() : headers;
    }

    @Test
    public void theAuthorizationIsSentAsAHeaderOnTheWebsocketRequest() throws Exception {
        WebSocketReverseProxy proxy = new WebSocketReverseProxy(new URI("wss://10.0.32.74:8006/x"), Mockito.mock(Session.class),
                "PVEAPIToken=user@pam!token=secret");

        Assert.assertEquals("PVEAPIToken=user@pam!token=secret", headersOf(proxy).get("Authorization"));
    }

    @Test
    public void withoutAnAuthorizationNoHeaderIsAdded() throws Exception {
        Assert.assertNull(headersOf(new WebSocketReverseProxy(new URI("wss://10.0.0.1:443/ticket/t"), Mockito.mock(Session.class))).get("Authorization"));
        Assert.assertNull(headersOf(new WebSocketReverseProxy(new URI("wss://10.0.0.1:443/ticket/t"), Mockito.mock(Session.class), " ")).get("Authorization"));
        Assert.assertNull(headersOf(new WebSocketReverseProxy(new URI("wss://10.0.0.1:443/ticket/t"), Mockito.mock(Session.class), null)).get("Authorization"));
    }
}

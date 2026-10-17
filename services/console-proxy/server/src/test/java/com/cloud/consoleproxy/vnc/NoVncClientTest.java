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
package com.cloud.consoleproxy.vnc;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.consoleproxy.websocket.WebSocketReverseProxy;

public class NoVncClientTest {

    private WebSocketReverseProxy proxyThat(boolean opens) throws Exception {
        WebSocketReverseProxy proxy = Mockito.mock(WebSocketReverseProxy.class);
        Mockito.when(proxy.getURI()).thenReturn(new URI("wss://10.0.35.25:8006/x"));
        Mockito.when(proxy.connectBlocking(Mockito.anyLong(), Mockito.any(TimeUnit.class))).thenReturn(opens);
        return proxy;
    }

    @Test
    public void anOpenedWebsocketIsAccepted() throws Exception {
        WebSocketReverseProxy proxy = proxyThat(true);

        new NoVncClient().connectToWebSocket(proxy);

        Mockito.verify(proxy).connectBlocking(NoVncClient.WEBSOCKET_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    public void aWebsocketThatCannotBeOpenedFailsWithTheHostInTheMessage() throws Exception {
        try {
            new NoVncClient().connectToWebSocket(proxyThat(false));
            Assert.fail("expected the connection to fail");
        } catch (IOException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("10.0.35.25"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("15 seconds"));
        }
    }

    @Test
    public void anInterruptedWaitFailsAndKeepsTheInterruption() throws Exception {
        WebSocketReverseProxy proxy = Mockito.mock(WebSocketReverseProxy.class);
        Mockito.when(proxy.getURI()).thenReturn(new URI("wss://10.0.35.25:8006/x"));
        Mockito.when(proxy.connectBlocking(Mockito.anyLong(), Mockito.any(TimeUnit.class))).thenThrow(new InterruptedException());

        try {
            new NoVncClient().connectToWebSocket(proxy);
            Assert.fail("expected the connection to fail");
        } catch (IOException e) {
            Assert.assertTrue(Thread.interrupted());
        }
    }

    @Test(expected = IOException.class)
    public void aRefusedConnectionFailsAtOnce() throws Exception {
        // nothing listens on port 1 of the local host
        new NoVncClient().connectToWebSocket("ws://127.0.0.1:1/x", "PVEAPIToken=u@pam!t=s", Mockito.mock(Session.class));
    }
}

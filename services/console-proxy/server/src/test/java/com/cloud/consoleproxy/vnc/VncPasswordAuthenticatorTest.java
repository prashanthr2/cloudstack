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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class VncPasswordAuthenticatorTest {
    private static final byte[] BANNER = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHALLENGE = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final byte[] OK = {0, 0, 0, 0};
    private static final byte[] SERVER_INIT = {7, 8, 9, 10, 11};

    private final ByteArrayOutputStream toServer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream toViewer = new ByteArrayOutputStream();
    private final List<String> failures = new ArrayList<>();
    private VncPasswordAuthenticator authenticator;

    @Before
    public void setUp() {
        authenticator = new VncPasswordAuthenticator("pa&ss#1", bytes -> toServer.write(bytes, 0, bytes.length),
                bytes -> toViewer.write(bytes, 0, bytes.length), failures::add);
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private byte[] expectedResponse() throws Exception {
        return NoVncClient.encodePassword(CHALLENGE, "pa&ss#1");
    }

    @Test
    public void theViewerIsLoggedInAndTheRestIsRelayedInBothDirections() throws Exception {
        authenticator.fromServer(BANNER);
        Assert.assertArrayEquals(BANNER, toServer.toByteArray());
        Assert.assertArrayEquals(BANNER, toViewer.toByteArray());

        authenticator.fromViewer(BANNER);
        authenticator.fromServer(new byte[] {1, 2});
        // the server is told to use the password, the viewer is told that none is needed
        Assert.assertArrayEquals(join(BANNER, new byte[] {2}), toServer.toByteArray());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}), toViewer.toByteArray());

        authenticator.fromViewer(new byte[] {1});
        authenticator.fromServer(CHALLENGE);
        Assert.assertArrayEquals(join(BANNER, new byte[] {2}, expectedResponse()), toServer.toByteArray());
        Assert.assertFalse(authenticator.isRelaying());

        authenticator.fromServer(OK);
        Assert.assertTrue(authenticator.isRelaying());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}, OK), toViewer.toByteArray());

        // the viewer's ClientInit and the server's ServerInit pass through
        authenticator.fromViewer(new byte[] {1});
        authenticator.fromServer(SERVER_INIT);
        Assert.assertArrayEquals(join(BANNER, new byte[] {2}, expectedResponse(), new byte[] {1}), toServer.toByteArray());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}, OK, SERVER_INIT), toViewer.toByteArray());
        Assert.assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void theViewersVersionAndChoiceAreNotPassedToTheServer() throws Exception {
        authenticator.fromServer(BANNER);
        authenticator.fromViewer(join(BANNER, new byte[] {1}));

        // only what this class said itself has reached the server
        Assert.assertArrayEquals(BANNER, toServer.toByteArray());
    }

    @Test
    public void bytesThatArriveSplitIntoSingleBytesAreHandled() throws Exception {
        byte[] beforeTheViewerAnswers = join(BANNER, new byte[] {1, 2});
        byte[] afterTheViewerAnswers = join(CHALLENGE, OK, SERVER_INIT);
        for (byte b : beforeTheViewerAnswers) {
            authenticator.fromServer(new byte[] {b});
        }
        // the viewer answers the banner and the security types, a byte at a time
        for (byte b : join(BANNER, new byte[] {1})) {
            authenticator.fromViewer(new byte[] {b});
        }
        for (byte b : afterTheViewerAnswers) {
            authenticator.fromServer(new byte[] {b});
        }

        Assert.assertTrue(authenticator.isRelaying());
        Assert.assertArrayEquals(join(BANNER, new byte[] {2}, expectedResponse()), toServer.toByteArray());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}, OK, SERVER_INIT), toViewer.toByteArray());
        Assert.assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void bytesThatArriveCoalescedAreHandled() throws Exception {
        authenticator.fromServer(join(BANNER, new byte[] {1, 2}));
        authenticator.fromServer(join(CHALLENGE, OK, SERVER_INIT));

        Assert.assertTrue(authenticator.isRelaying());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}, OK, SERVER_INIT), toViewer.toByteArray());
        Assert.assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void aClientInitThatTheViewerSendsEarlyWaitsForTheEndOfTheLogin() throws Exception {
        authenticator.fromServer(join(BANNER, new byte[] {1, 2}));
        authenticator.fromViewer(join(BANNER, new byte[] {1}, new byte[] {1}));
        authenticator.fromServer(CHALLENGE);
        // not yet: the server has not accepted the password
        Assert.assertArrayEquals(join(BANNER, new byte[] {2}, expectedResponse()), toServer.toByteArray());

        authenticator.fromServer(OK);

        Assert.assertArrayEquals(join(BANNER, new byte[] {2}, expectedResponse(), new byte[] {1}), toServer.toByteArray());
    }

    @Test
    public void aServerThatNeedsNoPasswordIsUsedWithoutOne() throws Exception {
        authenticator.fromServer(join(BANNER, new byte[] {1, 1}));
        authenticator.fromServer(OK);

        Assert.assertTrue(authenticator.isRelaying());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1}), toServer.toByteArray());
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}, OK), toViewer.toByteArray());
    }

    @Test
    public void thePasswordIsPreferredWhenTheServerOffersBoth() throws Exception {
        authenticator.fromServer(join(BANNER, new byte[] {2, 1, 2}));

        Assert.assertArrayEquals(join(BANNER, new byte[] {2}), toServer.toByteArray());
    }

    @Test
    public void aRefusedPasswordIsReportedToTheViewerWithAReasonAndTheConnectionIsClosed() throws Exception {
        authenticator.fromServer(join(BANNER, new byte[] {1, 2}, CHALLENGE));
        authenticator.fromServer(new byte[] {0, 0, 0, 1, 0, 0, 0, 3, 'n', 'o', 'p'});

        Assert.assertFalse(authenticator.isRelaying());
        Assert.assertEquals(1, failures.size());
        Assert.assertTrue(failures.get(0), failures.get(0).contains("password"));
        // banner, security types, then a failed SecurityResult (1) with the reason as a string
        byte[] reason = "the VNC server did not accept the password".getBytes(StandardCharsets.UTF_8);
        byte[] expectedFailure = join(new byte[] {0, 0, 0, 1, 0, 0, 0, (byte) reason.length}, reason);
        Assert.assertArrayEquals(join(BANNER, new byte[] {1, 1}, expectedFailure), toViewer.toByteArray());
        // nothing more is passed after a failure
        authenticator.fromServer(SERVER_INIT);
        authenticator.fromViewer(new byte[] {1});
        Assert.assertEquals(1, failures.size());
    }

    @Test
    public void aServerThatSpeaksAnotherVersionIsRefused() {
        authenticator.fromServer("RFB 003.003\n".getBytes(StandardCharsets.US_ASCII));

        Assert.assertEquals(1, failures.size());
        Assert.assertTrue(failures.get(0), failures.get(0).contains("RFB 003.003"));
        Assert.assertEquals(0, toServer.size());
        Assert.assertEquals(0, toViewer.size());
    }

    @Test
    public void aServerThatOffersNoSecurityTypeIsReported() {
        authenticator.fromServer(join(BANNER, new byte[] {0}));

        Assert.assertEquals(1, failures.size());
    }

    @Test
    public void aServerThatOffersOnlyUnsupportedSecurityTypesIsReported() {
        authenticator.fromServer(join(BANNER, new byte[] {1, 16}));

        // one type, number 16: neither None nor password
        Assert.assertEquals(1, failures.size());
        Assert.assertTrue(failures.get(0), failures.get(0).contains("supported"));
    }
}

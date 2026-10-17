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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logs in to a VNC server on behalf of the viewer in the browser, for a console that is relayed as websocket frames.
 *
 * The browser's noVNC cannot answer a VNC password challenge on a page served over plain HTTP (the browser then gives it no crypto), and
 * the password should not be put in the URL anyway. KVM does it in the console proxy; this does the same for a websocket console: during the
 * first messages of the RFB protocol (3.8) it talks to the server itself, with the password, and tells the viewer that no authentication is
 * needed. From the server's SecurityResult on, bytes are passed through unchanged in both directions.
 *
 * <pre>
 *   viewer                         this class                          VNC server
 *     |<--- version ------------------|<--------------- version ---------|
 *     |---- version (swallowed) ----->|---- version -------------------->|
 *     |<--- [1 type: None] -----------|<--------------- security types --|
 *     |---- choice (swallowed) ------>|---- choice: VNC authentication ->|
 *     |                               |<--------------- challenge -------|
 *     |                               |---- DES(password, challenge) --->|
 *     |<--- SecurityResult ok --------|<--------------- SecurityResult --|
 *     |<===== everything else, passed through in both directions =======>|
 * </pre>
 *
 * The bytes of each side are buffered, so it does not matter how they are split into websocket frames.
 */
public class VncPasswordAuthenticator {
    private enum State {
        SERVER_VERSION, SERVER_SECURITY_TYPES, SERVER_CHALLENGE, SERVER_RESULT, RELAY, FAILED
    }

    static final String SUPPORTED_VERSION = "RFB 003.008\n";
    private static final int VERSION_LENGTH = 12;
    private static final int CHALLENGE_LENGTH = 16;
    private static final int RESULT_LENGTH = 4;
    private static final int SECURITY_NONE = 1;
    private static final int SECURITY_VNC_AUTH = 2;
    /** The viewer's version (12 bytes) and its choice of security type (1 byte) are not passed on: this class has answered for it. */
    private static final int VIEWER_NEGOTIATION_LENGTH = VERSION_LENGTH + 1;

    private final Logger logger = LogManager.getLogger(getClass());
    private final String password;
    private final Consumer<byte[]> toServer;
    private final Consumer<byte[]> toViewer;
    private final Consumer<String> onFailure;

    private State state = State.SERVER_VERSION;
    private byte[] fromServerBuffer = new byte[0];
    private byte[] fromViewerBuffer = new byte[0];
    private int viewerBytesToSwallow = VIEWER_NEGOTIATION_LENGTH;

    /**
     * @param password  the password of the VNC server
     * @param toServer  sends bytes to the VNC server
     * @param toViewer  sends bytes to the viewer in the browser
     * @param onFailure called once, with the reason, when the login cannot be completed; the connection should then be closed
     */
    public VncPasswordAuthenticator(String password, Consumer<byte[]> toServer, Consumer<byte[]> toViewer, Consumer<String> onFailure) {
        this.password = password;
        this.toServer = toServer;
        this.toViewer = toViewer;
        this.onFailure = onFailure;
    }

    public synchronized boolean isRelaying() {
        return state == State.RELAY;
    }

    /** Bytes that arrived from the VNC server. */
    public synchronized void fromServer(byte[] data) {
        if (state == State.FAILED) {
            return;
        }
        if (state == State.RELAY) {
            toViewer.accept(data);
            return;
        }
        fromServerBuffer = concat(fromServerBuffer, data);
        processServerBytes();
    }

    /** Bytes that arrived from the viewer. */
    public synchronized void fromViewer(byte[] data) {
        if (state == State.FAILED) {
            return;
        }
        if (state == State.RELAY) {
            toServer.accept(data);
            return;
        }
        // negotiation: the first 13 bytes are not passed on, whatever follows (the viewer's ClientInit) waits for the end of the login
        int swallowed = Math.min(data.length, viewerBytesToSwallow);
        viewerBytesToSwallow -= swallowed;
        if (swallowed < data.length) {
            fromViewerBuffer = concat(fromViewerBuffer, Arrays.copyOfRange(data, swallowed, data.length));
        }
    }

    private void processServerBytes() {
        while (true) {
            switch (state) {
                case SERVER_VERSION:
                    if (fromServerBuffer.length < VERSION_LENGTH) {
                        return;
                    }
                    String version = new String(take(VERSION_LENGTH), StandardCharsets.US_ASCII);
                    if (!SUPPORTED_VERSION.equals(version)) {
                        fail("the VNC server speaks " + version.trim() + ", only " + SUPPORTED_VERSION.trim() + " is supported");
                        return;
                    }
                    toServer.accept(SUPPORTED_VERSION.getBytes(StandardCharsets.US_ASCII));
                    toViewer.accept(SUPPORTED_VERSION.getBytes(StandardCharsets.US_ASCII));
                    state = State.SERVER_SECURITY_TYPES;
                    break;
                case SERVER_SECURITY_TYPES:
                    if (fromServerBuffer.length < 1) {
                        return;
                    }
                    int count = fromServerBuffer[0] & 0xff;
                    if (count == 0) {
                        fail("the VNC server refused the connection");
                        return;
                    }
                    if (fromServerBuffer.length < 1 + count) {
                        return;
                    }
                    take(1);
                    byte[] types = take(count);
                    int chosen = choose(types);
                    if (chosen < 0) {
                        fail("the VNC server offers no security type that is supported (offered: " + Arrays.toString(types) + ")");
                        return;
                    }
                    toServer.accept(new byte[] {(byte) chosen});
                    // the viewer is offered one type, "None": the login is done here
                    toViewer.accept(new byte[] {1, SECURITY_NONE});
                    state = chosen == SECURITY_VNC_AUTH ? State.SERVER_CHALLENGE : State.SERVER_RESULT;
                    break;
                case SERVER_CHALLENGE:
                    if (fromServerBuffer.length < CHALLENGE_LENGTH) {
                        return;
                    }
                    byte[] challenge = take(CHALLENGE_LENGTH);
                    byte[] response;
                    try {
                        response = NoVncClient.encodePassword(challenge, password);
                    } catch (Exception e) {
                        fail("the answer to the VNC password challenge could not be computed: " + e.getMessage());
                        return;
                    }
                    toServer.accept(response);
                    state = State.SERVER_RESULT;
                    break;
                case SERVER_RESULT:
                    if (fromServerBuffer.length < RESULT_LENGTH) {
                        return;
                    }
                    int result = ByteBuffer.wrap(take(RESULT_LENGTH)).getInt();
                    if (result != 0) {
                        failToViewer("the VNC server did not accept the password");
                        return;
                    }
                    toViewer.accept(new byte[RESULT_LENGTH]);
                    state = State.RELAY;
                    logger.info("The VNC login for the viewer is done, the console is relayed");
                    if (fromViewerBuffer.length > 0) {
                        toServer.accept(fromViewerBuffer);
                        fromViewerBuffer = new byte[0];
                    }
                    break;
                case RELAY:
                    if (fromServerBuffer.length > 0) {
                        toViewer.accept(take(fromServerBuffer.length));
                    }
                    return;
                default:
                    return;
            }
        }
    }

    private static int choose(byte[] types) {
        boolean none = false;
        for (byte type : types) {
            if (type == SECURITY_VNC_AUTH) {
                return SECURITY_VNC_AUTH;
            }
            none = none || type == SECURITY_NONE;
        }
        return none ? SECURITY_NONE : -1;
    }

    private byte[] take(int length) {
        byte[] taken = Arrays.copyOfRange(fromServerBuffer, 0, length);
        fromServerBuffer = Arrays.copyOfRange(fromServerBuffer, length, fromServerBuffer.length);
        return taken;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        if (first.length == 0) {
            return second;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(first.length + second.length);
        out.write(first, 0, first.length);
        out.write(second, 0, second.length);
        return out.toByteArray();
    }

    /** The viewer is already waiting for the SecurityResult: tell it, with a reason, so that it shows a message and does not hang. */
    private void failToViewer(String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer message = ByteBuffer.allocate(RESULT_LENGTH + 4 + text.length);
        message.putInt(1).putInt(text.length).put(text);
        toViewer.accept(message.array());
        fail(reason);
    }

    private void fail(String reason) {
        state = State.FAILED;
        fromServerBuffer = new byte[0];
        fromViewerBuffer = new byte[0];
        logger.error("The VNC login for the viewer failed: {}", reason);
        onFailure.accept(reason);
    }
}

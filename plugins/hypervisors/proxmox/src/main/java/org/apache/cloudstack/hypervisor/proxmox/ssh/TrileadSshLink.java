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
package org.apache.cloudstack.hypervisor.proxmox.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.StreamGobbler;

/** {@link SshLink} on the Trilead library that CloudStack uses for its other SSH connections. */
public class TrileadSshLink implements SshLink {
    private final String host;
    private final int port;
    private Connection connection;

    public TrileadSshLink(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** The fingerprint in the form of "ssh-keygen -l": the SHA-256 of the key blob in base64 without padding, after "SHA256:". */
    static String fingerprintOf(byte[] hostKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(hostKey);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void connect(int timeoutMillis, HostKeyCheck check) throws IOException {
        connection = new Connection(host, port);
        ServerHostKeyVerifier verifier = (hostname, p, algorithm, key) -> check.accept(fingerprintOf(key));
        connection.connect(verifier, timeoutMillis, 0, timeoutMillis);
    }

    @Override
    public boolean authenticateWithKey(String user, String privateKeyPem) throws IOException {
        try {
            return connection.authenticateWithPublicKey(user, privateKeyPem.toCharArray(), null);
        } catch (IOException e) {
            // a key that cannot be read or is not accepted is not fatal: the password is tried next
            return false;
        }
    }

    @Override
    public boolean authenticateWithPassword(String user, String password) throws IOException {
        return connection.authenticateWithPassword(user, password);
    }

    @Override
    public ExecResult exec(String command, byte[] stdin, int timeoutMillis) throws IOException {
        Session session = connection.openSession();
        try {
            InputStream out = new StreamGobbler(session.getStdout());
            InputStream err = new StreamGobbler(session.getStderr());
            session.execCommand(command);
            if (stdin != null) {
                OutputStream in = session.getStdin();
                in.write(stdin);
                in.flush();
            }
            session.getStdin().close();
            int conditions = session.waitForCondition(ChannelCondition.EXIT_STATUS | ChannelCondition.EOF | ChannelCondition.CLOSED, timeoutMillis);
            if ((conditions & ChannelCondition.TIMEOUT) != 0) {
                throw new IOException("the command did not finish within " + timeoutMillis / 1000 + " seconds");
            }
            // the exit status can arrive after the output ends
            session.waitForCondition(ChannelCondition.EXIT_STATUS, 5000);
            Integer exit = session.getExitStatus();
            return new ExecResult(exit == null ? -1 : exit, read(out), read(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the command was interrupted", e);
        } finally {
            session.close();
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) > 0) {
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
    }
}

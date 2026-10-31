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

import java.io.IOException;

/**
 * One SSH connection to a node, as little as {@link ProxmoxSsh} needs of it. The implementation on the Trilead library is
 * {@link TrileadSshLink}; the tests use a fake.
 */
public interface SshLink extends AutoCloseable {
    /** What the node's host key looks like: decides whether to go on. The argument is the fingerprint in the form of "ssh-keygen -l" ("SHA256:..."). */
    interface HostKeyCheck {
        boolean accept(String fingerprint);
    }

    /** Connects and runs the host key check; fails when the check refuses the key. */
    void connect(int timeoutMillis, HostKeyCheck check) throws IOException;

    /** Tries a login with a private key (PEM text); false when the node does not accept it. */
    boolean authenticateWithKey(String user, String privateKeyPem) throws IOException;

    boolean authenticateWithPassword(String user, String password) throws IOException;

    /** Runs a command with {@code stdin} as its standard input (may be null) and waits for it. */
    ExecResult exec(String command, byte[] stdin, int timeoutMillis) throws IOException;

    @Override
    void close();
}

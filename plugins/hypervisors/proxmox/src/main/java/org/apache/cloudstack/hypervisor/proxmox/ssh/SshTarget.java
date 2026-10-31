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

/**
 * Where and how to log in to a node. The private key is tried first and the password after it, as for a KVM host (LibvirtServerDiscoverer).
 * {@code pinnedFingerprint} is the host key fingerprint kept when the host was added ("SHA256:..."); null on the first connection, which accepts
 * and reports the key it saw.
 */
public final class SshTarget {
    private final String host;
    private final int port;
    private final String user;
    private final String privateKey;
    private final String password;
    private final String pinnedFingerprint;

    public SshTarget(String host, int port, String user, String privateKey, String password, String pinnedFingerprint) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.privateKey = privateKey;
        this.password = password;
        this.pinnedFingerprint = pinnedFingerprint;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public String getPassword() {
        return password;
    }

    public String getPinnedFingerprint() {
        return pinnedFingerprint;
    }

    @Override
    public String toString() {
        return user + "@" + host + ":" + port;
    }
}

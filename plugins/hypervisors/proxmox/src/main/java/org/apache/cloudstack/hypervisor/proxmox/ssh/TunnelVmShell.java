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
import java.nio.file.Path;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;

/**
 * A login in a system VM or router over SSH, as KVM does it from the host, through the Proxmox node that the VM runs on: the control address of the
 * VM (link-local) is only reachable from that node, so the management server opens a port on its own loopback address that the node forwards to
 * port 3922 of the VM, and logs in there with its own key, the one every system VM trusts. The private key never leaves the management server.
 * The VM's host key is not checked: it is made again at every boot and the only way to the VM is the node's own bridge, which the SSH connection to
 * the node (host key pinned) carries.
 */
public final class TunnelVmShell implements ProxmoxVmShell {
    public static final int VM_SSH_PORT = 3922;
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final long RETRY_MILLIS = 2000;

    private final ProxmoxSsh node;
    private final SshLink link;

    private TunnelVmShell(ProxmoxSsh node, SshLink link) {
        this.node = node;
        this.link = link;
    }

    /**
     * Opens the tunnel through {@code node} (which this closes) and logs in. The VM listens on port 3922 only a few seconds after it has been given its
     * boot arguments, so a refused connection is tried again until {@code waitSeconds} have passed.
     *
     * @param links makes the link to the local end of the tunnel, given its port
     */
    public static TunnelVmShell open(ProxmoxSsh node, String vmAddress, String privateKey, IntFunction<SshLink> links, int waitSeconds,
            LongConsumer pauser, LongSupplier clock) {
        if (StringUtils.isBlank(privateKey)) {
            node.close();
            throw new ProxmoxSshException("The management server has no private key (the setting ssh.privatekey), which a system VM trusts");
        }
        int localPort;
        try {
            localPort = node.forward(vmAddress, VM_SSH_PORT);
        } catch (RuntimeException e) {
            node.close();
            throw e;
        }
        long deadline = clock.getAsLong() + waitSeconds * 1000L;
        String last;
        while (true) {
            SshLink link = links.apply(localPort);
            try {
                link.connect(CONNECT_TIMEOUT_MS, fingerprint -> true);
                if (link.authenticateWithKey("root", privateKey)) {
                    return new TunnelVmShell(node, link);
                }
                last = "the VM did not accept the management server's key";
            } catch (IOException e) {
                last = e.getMessage();
            }
            link.close();
            if (clock.getAsLong() >= deadline) {
                node.close();
                throw new ProxmoxSshException(String.format("Unable to log in over SSH to the system VM at %s through Proxmox node %s within %d seconds: %s",
                        vmAddress, node, waitSeconds, last));
            }
            pauser.accept(RETRY_MILLIS);
        }
    }

    @Override
    public ExecResult exec(String command, int timeoutMillis) {
        try {
            return link.exec(command, null, timeoutMillis);
        } catch (IOException e) {
            throw new ProxmoxSshException("The command in the system VM could not be run: " + e.getMessage(), e);
        }
    }

    @Override
    public void upload(Path file, String remotePath, int timeoutMillis) {
        try {
            link.upload(file, remotePath, timeoutMillis);
        } catch (IOException e) {
            throw new ProxmoxSshException(String.format("The file %s could not be copied into the system VM: %s", file.getFileName(), e.getMessage()), e);
        }
    }

    @Override
    public void close() {
        link.close();
        node.close();
    }
}

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
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

/**
 * SSH access to one Proxmox node, for the commands the API cannot run (design-phase10-ssh-access.md). A login is made as the user of the
 * {@link SshTarget}: a private key first, then the password. The host key is compared with the fingerprint kept for the node, and a changed key
 * fails the connection. A user other than root runs its commands through {@code sudo}: {@code sudo -n} when that needs no password, otherwise
 * {@code sudo -S} with the login password on standard input.
 */
public class ProxmoxSsh implements AutoCloseable {
    static final int CONNECT_TIMEOUT_MS = 20_000;
    public static final int DEFAULT_PORT = 22;
    public static final int DEFAULT_COMMAND_TIMEOUT_MS = 120_000;

    private final SshTarget target;
    private final SshLink link;
    private String seenFingerprint;
    private Boolean sudoWithoutPassword;

    private ProxmoxSsh(SshTarget target, SshLink link) {
        this.target = target;
        this.link = link;
    }

    /** Connects and logs in; the caller closes it. */
    public static ProxmoxSsh open(SshTarget target, Supplier<SshLink> links) {
        if (StringUtils.isBlank(target.getHost()) || StringUtils.isBlank(target.getUser())) {
            throw new ProxmoxSshException("An SSH login needs an address and a user for the Proxmox node");
        }
        if (StringUtils.isAllBlank(target.getPrivateKey(), target.getPassword())) {
            throw new ProxmoxSshException("An SSH login to " + target + " needs a private key (the setting ssh.privatekey) or a password");
        }
        SshLink link = links.get();
        ProxmoxSsh ssh = new ProxmoxSsh(target, link);
        try {
            link.connect(CONNECT_TIMEOUT_MS, fingerprint -> {
                ssh.seenFingerprint = fingerprint;
                return target.getPinnedFingerprint() == null || target.getPinnedFingerprint().equals(fingerprint);
            });
        } catch (IOException e) {
            link.close();
            if (target.getPinnedFingerprint() != null && ssh.seenFingerprint != null && !target.getPinnedFingerprint().equals(ssh.seenFingerprint)) {
                throw new ProxmoxSshException(String.format("The SSH host key of %s changed: it was %s and is now %s. If the node was reinstalled, add the host "
                        + "again; otherwise something may be impersonating it.", target.getHost(), target.getPinnedFingerprint(), ssh.seenFingerprint), e);
            }
            throw new ProxmoxSshException("Unable to connect over SSH to " + target + ": " + e.getMessage(), e);
        }
        try {
            boolean loggedIn = false;
            if (StringUtils.isNotBlank(target.getPrivateKey())) {
                loggedIn = link.authenticateWithKey(target.getUser(), target.getPrivateKey());
            }
            if (!loggedIn && StringUtils.isNotEmpty(target.getPassword())) {
                loggedIn = link.authenticateWithPassword(target.getUser(), target.getPassword());
            }
            if (!loggedIn) {
                throw new ProxmoxSshException(String.format("The SSH login of %s was refused: %s", target, StringUtils.isBlank(target.getPrivateKey())
                        ? "the password is wrong, or the node does not allow password logins for this user"
                        : "the management server's key is not authorised on the node and " + (StringUtils.isEmpty(target.getPassword())
                        ? "no password was given" : "the password is wrong, or the node does not allow password logins for this user")));
            }
        } catch (IOException e) {
            link.close();
            throw new ProxmoxSshException("The SSH login to " + target + " failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            link.close();
            throw e;
        }
        return ssh;
    }

    /** The host key fingerprint the node showed ("SHA256:..."), to keep when the host is added. */
    public String getHostKeyFingerprint() {
        return seenFingerprint;
    }

    /** Runs a command as the login user, and returns the result whatever its exit code. */
    public ExecResult execute(String command, byte[] stdin, int timeoutMillis) {
        try {
            return link.exec(command, stdin, timeoutMillis);
        } catch (IOException e) {
            throw new ProxmoxSshException("The command on " + target.getHost() + " could not be run: " + e.getMessage(), e);
        }
    }

    /** Runs a command with the rights of root (the login user itself when it is root) and returns the result whatever its exit code. */
    public ExecResult sudo(String command, byte[] stdin, int timeoutMillis) {
        if ("root".equals(target.getUser())) {
            return execute(command, stdin, timeoutMillis);
        }
        if (sudoWithoutPassword == null) {
            sudoWithoutPassword = execute("sudo -n true", null, DEFAULT_COMMAND_TIMEOUT_MS).isSuccess();
            if (!sudoWithoutPassword && StringUtils.isEmpty(target.getPassword())) {
                throw new ProxmoxSshException(String.format("%s cannot use sudo without a password on %s: give the user NOPASSWD in /etc/sudoers.d, or give the "
                        + "password in Add Host", target.getUser(), target.getHost()));
            }
        }
        if (sudoWithoutPassword) {
            return execute("sudo -n sh -c " + quote(command), stdin, timeoutMillis);
        }
        // sudo reads the password from the first line of standard input, the command's own input follows
        byte[] password = (target.getPassword() + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[password.length + (stdin == null ? 0 : stdin.length)];
        System.arraycopy(password, 0, input, 0, password.length);
        if (stdin != null) {
            System.arraycopy(stdin, 0, input, password.length, stdin.length);
        }
        return execute("sudo -S -p '' sh -c " + quote(command), input, timeoutMillis);
    }

    /** As {@link #sudo(String, byte[], int)} but a failure is an exception that carries what the command printed. */
    public String sudoOrFail(String command) {
        ExecResult result = sudo(command, null, DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess()) {
            throw new ProxmoxSshException(String.format("The command \"%s\" failed on %s: %s", command, target.getHost(), result.describeFailure()));
        }
        return result.getStdout();
    }

    /** Writes a file as root, replacing it, with the given mode ("600"). The content travels on standard input, so it is not part of any command line. */
    public void writeFile(String path, byte[] content, String mode) {
        String command = "umask 077 && cat > " + quote(path) + " && chmod " + quote(mode) + " " + quote(path);
        ExecResult result = sudo(command, content, DEFAULT_COMMAND_TIMEOUT_MS);
        if (!result.isSuccess()) {
            throw new ProxmoxSshException(String.format("The file %s could not be written on %s: %s", path, target.getHost(), result.describeFailure()));
        }
    }

    /** A word for a shell: in single quotes, with each single quote written as '\''. */
    public static String quote(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() {
        link.close();
    }
}

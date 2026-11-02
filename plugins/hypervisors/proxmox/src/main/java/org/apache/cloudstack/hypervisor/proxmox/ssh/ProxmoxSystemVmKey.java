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

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * The key pair that lets an administrator log in to a system VM or router from the Proxmox node it runs on (requirements.md, "Logging in to a system
 * VM from a node"). The pair lives in {@value #PRIVATE_KEY}, in Proxmox's cluster-wide private directory, so the private key is on every node; the
 * public key goes into the setting proxmox.systemvm.ssh.key, which the plugin adds to every system VM it starts. Add Host makes the pair when it is
 * missing and adds the public key to the setting, so that the administrator does not have to.
 */
public final class ProxmoxSystemVmKey {
    public static final String PRIVATE_KEY = "/etc/pve/priv/cloudstack_systemvm";
    static final String COMMENT = "cloudstack-systemvm";
    private static final Pattern PUBLIC_KEY = Pattern.compile("(ssh|ecdsa|sk)-[A-Za-z0-9@.\\-]+ [A-Za-z0-9+/=]+( [A-Za-z0-9@._:+=/ \\-]*)?");

    private ProxmoxSystemVmKey() {
    }

    /**
     * The public key of the pair, which is made on the node when it does not exist. A private key without its public file (the file was removed) is
     * not replaced: its public key is derived from it.
     */
    public static String ensure(ProxmoxSsh ssh) {
        String key = read(ssh, "cat " + ProxmoxSsh.quote(PRIVATE_KEY + ".pub"));
        if (key == null) {
            if (ssh.sudo("test -f " + ProxmoxSsh.quote(PRIVATE_KEY), null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS).isSuccess()) {
                key = read(ssh, "ssh-keygen -y -f " + ProxmoxSsh.quote(PRIVATE_KEY));
            } else {
                ssh.sudoOrFail("ssh-keygen -q -t ed25519 -N '' -C " + COMMENT + " -f " + ProxmoxSsh.quote(PRIVATE_KEY));
                key = read(ssh, "cat " + ProxmoxSsh.quote(PRIVATE_KEY + ".pub"));
            }
        }
        if (key == null) {
            throw new ProxmoxSshException("The system VM login key in " + PRIVATE_KEY + " could not be read or made");
        }
        return key;
    }

    private static String read(ProxmoxSsh ssh, String command) {
        ExecResult result = ssh.sudo(command, null, ProxmoxSsh.DEFAULT_COMMAND_TIMEOUT_MS);
        String line = result.isSuccess() ? StringUtils.trimToEmpty(result.getStdout()) : "";
        return PUBLIC_KEY.matcher(line).matches() ? line : null;
    }

    /**
     * The value of the setting with the key added (keys are separated by |, as the setting documents), or null when the setting has the key already.
     * Keys the administrator put there stay.
     */
    public static String addTo(String setting, String key) {
        String wanted = identityOf(key);
        StringBuilder merged = new StringBuilder();
        if (StringUtils.isNotBlank(setting)) {
            for (String part : setting.split("[|\\r\\n]")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (identityOf(trimmed).equals(wanted)) {
                    return null;
                }
                merged.append(merged.length() == 0 ? "" : "|").append(trimmed);
            }
        }
        return merged.append(merged.length() == 0 ? "" : "|").append(key).toString();
    }

    /** The key type and the key itself: the comment after them does not make a different key. */
    private static String identityOf(String key) {
        String[] fields = key.trim().split("\\s+");
        return fields.length >= 2 ? fields[0] + " " + fields[1] : key.trim();
    }
}

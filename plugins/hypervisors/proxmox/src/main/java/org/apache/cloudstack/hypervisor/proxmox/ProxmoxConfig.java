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
package org.apache.cloudstack.hypervisor.proxmox;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

/**
 * Global settings of the Proxmox plugin. Like XenServer's "xapiwait" and VMware's "vmware.vcenter.session.timeout",
 * the timeouts are global settings, and so is whether the TLS certificate is verified. They are read when a host (re)connects, so a changed value reaches a host the next
 * time it connects.
 */
public class ProxmoxConfig implements Configurable {
    public static final ConfigKey<Integer> ApiConnectTimeout = new ConfigKey<>("Advanced", Integer.class,
            "proxmox.api.connect.timeout", "5",
            "Seconds to wait for a connection to a Proxmox node's API before trying the next node of the cluster", true);

    public static final ConfigKey<Integer> ApiReadTimeout = new ConfigKey<>("Advanced", Integer.class,
            "proxmox.api.read.timeout", "15",
            "Seconds to wait for a Proxmox API answer once connected", true);

    public static final ConfigKey<Boolean> ApiVerifyTls = new ConfigKey<>("Advanced", Boolean.class,
            "proxmox.api.verify.tls", "false",
            "Verify the TLS certificate of the Proxmox API. Off by default, like the VMware and XenServer plugins. When on, the "
                    + "CA that signed the Proxmox certificates (by default the Proxmox cluster CA, /etc/pve/pve-root-ca.pem) must "
                    + "be in the management server's Java truststore", true);

    @Override
    public String getConfigComponentName() {
        return ProxmoxConfig.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ApiConnectTimeout, ApiReadTimeout, ApiVerifyTls};
    }
}

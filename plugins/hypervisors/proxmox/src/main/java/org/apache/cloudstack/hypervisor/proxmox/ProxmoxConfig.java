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

    public static final ConfigKey<Integer> TaskTimeout = new ConfigKey<>("Advanced", Integer.class,
            "proxmox.task.timeout", "600",
            "Seconds to wait for a Proxmox task (creating a VM from a template, starting or stopping it ...) to finish. A task that is "
                    + "still running afterwards is reported as failed to CloudStack but is not cancelled in Proxmox", true);

    public static final ConfigKey<Integer> MigrationTimeout = new ConfigKey<>("Advanced", Integer.class,
            "proxmox.migration.timeout", "3600",
            "Seconds to wait for a Proxmox VM to move to another node. A large VM takes longer than a Proxmox task usually does. A move "
                    + "that is still running afterwards is reported as failed to CloudStack but is not cancelled in Proxmox", true);

    public static final ConfigKey<Boolean> HaManage = new ConfigKey<>("Advanced", Boolean.class,
            "proxmox.ha.manage", "false",
            "Let CloudStack put the VMs of compute offerings with HA enabled under Proxmox HA when they are started (and take them out "
                    + "again when the offering no longer has HA). Needs the privileges Sys.Audit and Sys.Console on / for the API token, see "
                    + "the Proxmox plugin documentation. When off, the HA flag has no effect on Proxmox and HA resources are managed in Proxmox", true);

    public static final ConfigKey<String> ControlBridge = new ConfigKey<>("Advanced", String.class,
            "proxmox.control.bridge", "cloud0",
            "Name of the host-only Linux bridge, without ports and with the link-local address 169.254.0.1/16, that the control NIC of system VMs and "
                    + "virtual routers is attached to. It must exist on every node: when a host is added CloudStack creates it if it is missing (the API "
                    + "token then needs the privilege Sys.Modify on the node), or fails the add and says how to create it by hand", true);

    public static final ConfigKey<String> IsoPath = new ConfigKey<>("Advanced", String.class,
            "proxmox.iso.path", "/var/lib/cloudstack-iso",
            "Directory on every Proxmox node where the ISOs of the secondary storage (and the config drive) are bound, one file each, for the time they are "
                    + "attached, as one shared Proxmox storage entry. Proxmox creates the directory; the API token needs the privileges to create a "
                    + "storage entry (PVEDatastoreAdmin on /storage). Every node needs the same path, and an SSH login with sudo", true);

    public static final ConfigKey<String> SystemVmSshKey = new ConfigKey<>("Advanced", String.class,
            "proxmox.systemvm.ssh.key", "",
            "Public SSH key(s), the contents of a .pub file, that CloudStack adds to /root/.ssh/authorized_keys of every system VM and router on "
                    + "Proxmox after it has started, so that an administrator can log in from a Proxmox node with ssh -p 3922 root@<control address of "
                    + "the VM>. Several keys are separated by | . Empty (the default): nothing is added and only the management server's key works. "
                    + "See the Proxmox plugin requirements, \"Logging in to a system VM from a node\"", true);

    public static final ConfigKey<String> SecondaryMountOptions = new ConfigKey<>("Advanced", String.class,
            "proxmox.secondary.mount.options", "soft,timeo=30,retrans=2,nosharecache",
            "NFS mount options that the plugin uses when it mounts a secondary store on a Proxmox node, for one operation (a template deploy, an ISO, a "
                    + "volume snapshot). A comma-separated list of: soft, hard, nosharecache, noatime, nolock, proto=tcp, and timeo=, retrans=, nconnect=, "
                    + "rsize=, wsize= with a number. The plugin adds ro or rw and the NFS version of the store itself. The default makes a dead NFS export "
                    + "give an I/O error after about 100 seconds instead of hanging the node (soft); nosharecache is needed when the secondary export lies "
                    + "below the export of a primary storage, as the kernel shares one mount between them and the options of the first would win. "
                    + "Empty: the system defaults, which is what KVM uses (hard). A change applies to mounts made after it", true);

    public static final ConfigKey<Boolean> CreateFullClone = new ConfigKey<>("Storage", Boolean.class,
            "proxmox.create.full.clone", "false",
            "If false (the default), the root volume of an instance is a linked clone of the template VM that caches the template on the primary storage: "
                    + "it is made in seconds and starts as a small file that holds only what the instance changes. If true, it is a full, independent copy of "
                    + "the template disk, which takes as long as the copy of the whole disk and uses its whole size, but does not depend on the template VM. "
                    + "Can be set per primary storage, and globally for all that have no value of their own. A compute or disk offering with sparse or fat "
                    + "provisioning always gets a full copy, as on KVM. Instances that exist keep what they were made with; a change applies to the next deploy",
            true, ConfigKey.Scope.StoragePool);

    @Override
    public String getConfigComponentName() {
        return ProxmoxConfig.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ApiConnectTimeout, ApiReadTimeout, ApiVerifyTls, TaskTimeout, MigrationTimeout, HaManage, ControlBridge, SystemVmSshKey, SecondaryMountOptions, IsoPath, CreateFullClone};
    }
}

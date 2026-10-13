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
package org.apache.cloudstack.hypervisor.proxmox.resource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;

/**
 * Turns what CloudStack knows about a VM into the options of a Proxmox VM, and reads back what Proxmox reports about its disks.
 * No Proxmox calls are made here.
 */
public final class ProxmoxVmConfig {
    /** The disk CloudStack's root volume is attached as. */
    public static final String ROOT_DISK = "scsi0";
    /** Used when the guest OS has no mapping: Proxmox's own default for "unspecified OS". */
    public static final String DEFAULT_OS_TYPE = "other";
    private static final long MIB = 1024L * 1024L;
    private static final int CPU_UNITS_MAX = 10000;

    private ProxmoxVmConfig() {
    }

    /**
     * The options set on a stopped VM just before it is started. The disks are not part of it: the root disk was attached when the VM
     * was created, together with the root volume.
     *
     * @param hostCpuCapacityMhz CPU cores times MHz of the node, to scale the CPU weight; 0 when unknown (the weight is then left at Proxmox's default)
     */
    public static Map<String, String> startOptions(VirtualMachineTO vm, long hostCpuCapacityMhz) {
        Map<String, String> options = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(vm.getName())) {
            options.put("name", vm.getName());
        }
        options.put("sockets", "1");
        options.put("cores", Integer.toString(vm.getCpus()));
        Integer cpuUnits = cpuUnits(vm, hostCpuCapacityMhz);
        if (cpuUnits != null) {
            options.put("cpuunits", Integer.toString(cpuUnits));
        }
        options.put("memory", Long.toString(Math.max(1, vm.getMaxRam() / MIB)));
        // CloudStack sizes the VM; Proxmox's memory ballooning would let it shrink below that
        options.put("balloon", "0");
        options.put("ostype", StringUtils.defaultIfBlank(vm.getPlatformEmulator(), DEFAULT_OS_TYPE));
        options.put("scsihw", "virtio-scsi-single");
        options.put("boot", "order=" + ROOT_DISK);
        if (vm.getNics() != null) {
            for (NicTO nic : vm.getNics()) {
                options.put("net" + nic.getDeviceId(), netOption(nic));
            }
        }
        return options;
    }

    /**
     * The VM's CPU weight, from the service offering's speed like the KVM plugin does: the CPU the VM asks for (cores times MHz) as a share of
     * what the node has, on Proxmox's scale of 1 to 10000 (cgroup v2). A VM asking for the whole node gets 10000. Null when the offering
     * has no speed or the node's capacity is unknown.
     */
    static Integer cpuUnits(VirtualMachineTO vm, long hostCpuCapacityMhz) {
        Integer speed = vm.getMinSpeed() != null ? vm.getMinSpeed() : vm.getSpeed();
        if (speed == null || speed <= 0 || hostCpuCapacityMhz <= 0) {
            return null;
        }
        double units = Math.ceil((double) vm.getCpus() * speed * CPU_UNITS_MAX / hostCpuCapacityMhz);
        return (int) Math.max(1, Math.min(CPU_UNITS_MAX, units));
    }

    /** {@code virtio=<mac>,bridge=<bridge>,tag=<vlan>,rate=<MB/s>,firewall=0} */
    static String netOption(NicTO nic) {
        if (StringUtils.isBlank(nic.getName())) {
            throw new ProxmoxApiException(String.format("NIC %d (MAC %s) has no bridge: set the KVM traffic label of the physical network to "
                    + "the Proxmox bridge", nic.getDeviceId(), nic.getMac()));
        }
        StringBuilder option = new StringBuilder("virtio=").append(nic.getMac()).append(",bridge=").append(nic.getName());
        String tag = vlanTag(nic.getBroadcastUri());
        if (tag != null) {
            option.append(",tag=").append(tag);
        }
        Integer rateMbps = nic.getNetworkRateMbps();
        if (rateMbps != null && rateMbps > 0) {
            // CloudStack's rate is in megabits per second, Proxmox's in megabytes per second
            option.append(",rate=").append(Math.max(1, rateMbps / 8));
        }
        return option.append(",firewall=0").toString();
    }

    /** The VLAN id of a network's broadcast URI ("vlan://100"), or null for an untagged network or any other kind of isolation. */
    static String vlanTag(URI broadcastUri) {
        if (broadcastUri == null || !"vlan".equalsIgnoreCase(broadcastUri.getScheme())) {
            return null;
        }
        String id = broadcastUri.getAuthority();
        if (id == null) {
            id = StringUtils.removeStart(broadcastUri.getSchemeSpecificPart(), "//");
        }
        return StringUtils.isNumeric(id) ? id : null;
    }

    /**
     * The VM id inside a Proxmox volume path ("9204/vm-9204-disk-0.qcow2": a disk lives in a folder named after the VM that owns it),
     * or null when the path does not look like one.
     */
    public static Long vmIdOfVolumePath(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        String folder = StringUtils.substringBefore(StringUtils.substringAfter(path, ":"), "/");
        if (folder.isEmpty() && !path.contains(":")) {
            folder = StringUtils.substringBefore(path, "/");
        }
        return StringUtils.isNumeric(folder) && !folder.isEmpty() ? Long.valueOf(folder) : null;
    }

    /**
     * The size in a disk option, "cs-1:9204/vm-9204-disk-0.qcow2,size=8G", in bytes; 0 when the option has none.
     */
    public static long diskSizeBytes(String diskOption) {
        for (String part : StringUtils.split(StringUtils.defaultString(diskOption), ',')) {
            if (part.startsWith("size=")) {
                return parseSize(part.substring("size=".length()));
            }
        }
        return 0;
    }

    /** The volume path in a disk option: the part after "&lt;storage&gt;:" and before the first comma. */
    public static String diskPath(String diskOption) {
        String volume = StringUtils.substringBefore(StringUtils.defaultString(diskOption), ",");
        return StringUtils.substringAfter(volume, ":");
    }

    static long parseSize(String size) {
        if (StringUtils.isBlank(size)) {
            return 0;
        }
        char unit = Character.toUpperCase(size.charAt(size.length() - 1));
        long factor = 1;
        String number = size;
        switch (unit) {
            case 'K':
                factor = 1024L;
                break;
            case 'M':
                factor = MIB;
                break;
            case 'G':
                factor = 1024L * MIB;
                break;
            case 'T':
                factor = 1024L * 1024L * MIB;
                break;
            default:
                break;
        }
        if (factor != 1) {
            number = size.substring(0, size.length() - 1);
        }
        try {
            return (long) (Double.parseDouble(number) * factor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

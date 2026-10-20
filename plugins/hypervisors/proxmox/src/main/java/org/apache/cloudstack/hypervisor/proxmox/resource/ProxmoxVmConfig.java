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
import java.util.Locale;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.template.VirtualMachineTemplate.BootloaderType;
import com.cloud.vm.VmDetailConstants;

/**
 * Turns what CloudStack knows about a VM into the options of a Proxmox VM, and reads back what Proxmox reports about its disks.
 * No Proxmox calls are made here.
 */
public final class ProxmoxVmConfig {
    /** The disk CloudStack's root volume is attached as. */
    public static final String ROOT_DISK = "scsi0";
    /** Used when the guest OS has no mapping: Proxmox's own default for "unspecified OS". */
    public static final String DEFAULT_OS_TYPE = "other";
    /** CloudStack's number for the first CD-ROM slot (TemplateManager.CDROM_PRIMARY_DEVICE_SEQ); the slots that follow are the next numbers. */
    public static final int FIRST_CDROM_SEQ = 3;
    /** The drive of slot 3, 4, 5, 6: IDE has four, and the first is ide2 as everywhere in Proxmox. */
    static final String[] CDROM_DRIVES = {"ide2", "ide3", "ide0", "ide1"};
    /** A CD-ROM drive without a disc. */
    public static final String EMPTY_CDROM = "none,media=cdrom";
    private static final String DEFAULT_NIC_MODEL = "virtio";
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
        return startOptions(vm, hostCpuCapacityMhz, new LinkedHashMap<>());
    }

    /**
     * As above, with the CD-ROM drives: drive name to its value ("&lt;volume&gt;,media=cdrom" or {@link #EMPTY_CDROM}), the drives that CloudStack
     * asked for with the ISO disks of the start command (filled or empty). When the first slot holds a bootable ISO (the boot loader is CD) the
     * VM boots from it first.
     */
    public static Map<String, String> startOptions(VirtualMachineTO vm, long hostCpuCapacityMhz, Map<String, String> cdroms) {
        return startOptions(vm, hostCpuCapacityMhz, cdroms, ROOT_DISK);
    }

    /** As above, with the drive the root disk is on ({@link #rootDiskDrive}), which the boot order names. */
    public static Map<String, String> startOptions(VirtualMachineTO vm, long hostCpuCapacityMhz, Map<String, String> cdroms, String rootDrive) {
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
        String firstDrive = CDROM_DRIVES[0];
        boolean bootFromCd = vm.getBootloader() == BootloaderType.CD && cdroms.containsKey(firstDrive);
        options.put("boot", "order=" + (bootFromCd ? firstDrive + ";" : "") + rootDrive);
        options.putAll(cdroms);
        if (vm.getNics() != null) {
            String model = nicModel(vm);
            for (NicTO nic : vm.getNics()) {
                options.put("net" + nic.getDeviceId(), netOption(nic, model));
            }
        }
        return options;
    }

    /** The drives a root disk can be on. */
    static final String[] ROOT_DRIVES = {ROOT_DISK, "sata0", "virtio0"};

    /**
     * The drive of the root disk from the VM detail "rootDiskController" (as on KVM): scsi (the default, also "osdefault" and "virtio-scsi") is
     * scsi0 on the virtio-scsi controller, virtio is a virtio block disk, sata is sata0. ide is refused, because the IDE drives are the CD-ROM drives.
     */
    static String rootDiskDrive(VirtualMachineTO vm) {
        String requested = vm.getDetails() == null ? null : vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER);
        if (StringUtils.isBlank(requested)) {
            return ROOT_DISK;
        }
        switch (requested.trim().toLowerCase(Locale.ROOT)) {
            case "osdefault":
            case "scsi":
            case "virtio-scsi":
                return ROOT_DISK;
            case "virtio":
                return "virtio0";
            case "sata":
                return "sata0";
            case "ide":
                throw new ProxmoxApiException("The root disk controller ide is not supported for Proxmox, because the IDE drives are the CD-ROM drives: use sata, "
                        + "scsi or virtio");
            default:
                throw new ProxmoxApiException("The root disk controller " + requested + " is not supported for Proxmox: use scsi, virtio or sata");
        }
    }

    /** The drive of a VM's configuration that holds the root volume ({@code volumePath}, "&lt;vmid&gt;/vm-&lt;vmid&gt;-disk-0.qcow2"), or null. */
    static String rootDriveOf(Map<String, String> config, String volumePath) {
        for (String drive : ROOT_DRIVES) {
            String value = config.get(drive);
            if (value != null && volumePath != null && value.contains(volumePath)) {
                return drive;
            }
        }
        return null;
    }

    /** The IDE drive of a CloudStack CD-ROM slot (a null slot is the first). */
    public static String cdromDrive(Long diskSeq) {
        int index = diskSeq == null ? 0 : (int) (diskSeq - FIRST_CDROM_SEQ);
        if (index < 0 || index >= CDROM_DRIVES.length) {
            throw new ProxmoxApiException(String.format("CD-ROM slot %d cannot be used: a Proxmox VM has %d CD-ROM drives (slots %d to %d)", diskSeq,
                    CDROM_DRIVES.length, FIRST_CDROM_SEQ, FIRST_CDROM_SEQ + CDROM_DRIVES.length - 1));
        }
        return CDROM_DRIVES[index];
    }

    /** True for the value of a CD-ROM drive in a VM's configuration ("&lt;volume&gt;,media=cdrom" or "none,media=cdrom"). */
    public static boolean isCdrom(String driveValue) {
        return driveValue != null && driveValue.contains("media=cdrom");
    }

    /** True when the drive holds no disc. */
    public static boolean isEmptyCdrom(String driveValue) {
        return isCdrom(driveValue) && driveValue.startsWith("none,");
    }

    public static String cdromValue(String volume) {
        return volume + ",media=cdrom";
    }

    /**
     * The network adapter model from the VM detail "nicAdapter" (as on KVM), for a guest that has no virtio driver; virtio when it is not set.
     * Refused when Proxmox has no such model, so that the VM does not start with something else than was asked for.
     */
    static String nicModel(VirtualMachineTO vm) {
        String requested = vm.getDetails() == null ? null : vm.getDetails().get(VmDetailConstants.NIC_ADAPTER);
        if (StringUtils.isBlank(requested)) {
            return DEFAULT_NIC_MODEL;
        }
        switch (requested.trim().toLowerCase(Locale.ROOT)) {
            case "virtio":
            case "virtio-net":
                return "virtio";
            case "e1000":
                return "e1000";
            case "e1000e":
                return "e1000e";
            case "rtl8139":
                return "rtl8139";
            case "vmxnet3":
                return "vmxnet3";
            default:
                throw new ProxmoxApiException("The network adapter " + requested + " is not supported for Proxmox: use virtio, e1000, e1000e, rtl8139 or vmxnet3");
        }
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
        return netOption(nic, DEFAULT_NIC_MODEL);
    }

    /** As above with the adapter model in place of virtio: {@code <model>=<mac>,bridge=...}. */
    static String netOption(NicTO nic, String model) {
        if (StringUtils.isBlank(nic.getName())) {
            throw new ProxmoxApiException(String.format("NIC %d (MAC %s) has no bridge: set the KVM traffic label of the physical network to "
                    + "the Proxmox bridge", nic.getDeviceId(), nic.getMac()));
        }
        StringBuilder option = new StringBuilder(model).append('=').append(nic.getMac()).append(",bridge=").append(nic.getName());
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

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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.network.Networks.TrafficType;
import com.cloud.offering.DiskOffering.DiskCacheMode;
import com.cloud.template.VirtualMachineTemplate.BootloaderType;
import com.cloud.vm.VirtualMachine;
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
        return startOptions(vm, hostCpuCapacityMhz, cdroms, rootDrive, null);
    }

    /**
     * As above, for a system VM or router also with the guest agent switched on (its boot arguments and its commands go through it) and with the
     * control NIC on {@code controlBridge}, the node's host-only bridge (core gives that NIC no bridge name; on KVM it is {@code cloud0}).
     */
    public static Map<String, String> startOptions(VirtualMachineTO vm, long hostCpuCapacityMhz, Map<String, String> cdroms, String rootDrive,
            String controlBridge) {
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
        String smbios = smbios(vm);
        if (smbios != null) {
            options.put("smbios1", smbios);
        }
        options.put("scsihw", "virtio-scsi-single");
        String firstDrive = CDROM_DRIVES[0];
        boolean bootFromCd = vm.getBootloader() == BootloaderType.CD && cdroms.containsKey(firstDrive);
        options.put("boot", "order=" + (bootFromCd ? firstDrive + ";" : "") + rootDrive);
        options.putAll(cdroms);
        if (isSystemVm(vm)) {
            options.put("agent", "1");
        }
        if (needsAgentPatch(vm) && !options.containsKey(PATCH_CDROM)) {
            // the drive that carries agent.zip into the VM: a drive cannot be added to a running VM, but its medium can be changed
            options.put(PATCH_CDROM, EMPTY_CDROM);
        }
        if (vm.getNics() != null) {
            String model = nicModel(vm);
            for (NicTO nic : vm.getNics()) {
                String bridge = controlBridge != null && nic.getType() == TrafficType.Control ? controlBridge : nic.getName();
                options.put("net" + nic.getDeviceId(), netOption(nic, model, bridge));
            }
        }
        return options;
    }

    /**
     * The SMBIOS system information of the VM, as the KVM plugin gives it to libvirt: the manufacturer and the product name that core sets
     * ("Apache Software Foundation", "CloudStack Proxmox Hypervisor") and the VM's uuid, also as its serial. cloud-init recognises a CloudStack
     * guest by a product name that starts with "CloudStack" and only then reads the metadata service of the router (host name, user data, SSH
     * key, password); without it cloud-init of an Ubuntu image disables itself. The strings are base64 encoded (Proxmox's {@code base64=1}), so
     * that no comma or space needs escaping. Null when core sent neither name.
     */
    static String smbios(VirtualMachineTO vm) {
        if (StringUtils.isAllBlank(vm.getMetadataManufacturer(), vm.getMetadataProductName())) {
            return null;
        }
        StringBuilder smbios = new StringBuilder();
        if (StringUtils.isNotBlank(vm.getUuid())) {
            smbios.append("uuid=").append(vm.getUuid()).append(',');
        }
        if (StringUtils.isNotBlank(vm.getMetadataManufacturer())) {
            smbios.append("manufacturer=").append(base64(vm.getMetadataManufacturer())).append(',');
        }
        if (StringUtils.isNotBlank(vm.getMetadataProductName())) {
            smbios.append("product=").append(base64(vm.getMetadataProductName())).append(',');
        }
        if (StringUtils.isNotBlank(vm.getUuid())) {
            smbios.append("serial=").append(base64(vm.getUuid())).append(',');
        }
        return smbios.append("base64=1").toString();
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The drives a root disk can be on. */
    static final String[] ROOT_DRIVES = {ROOT_DISK, "sata0", "virtio0"};

    /**
     * The drive of the root disk from the VM detail "rootDiskController" (as on KVM): scsi (the default, also "osdefault" and "virtio-scsi") is
     * scsi0 on the virtio-scsi controller, virtio is a virtio block disk, sata is sata0. ide is refused, because the IDE drives are the CD-ROM drives.
     */
    static String rootDiskDrive(VirtualMachineTO vm) {
        return rootDiskDrive(vm.getDetails());
    }

    /** As above, from the details of a VM (an attach command carries them as "controller info"). */
    static String rootDiskDrive(Map<String, String> details) {
        String requested = details == null ? null : details.get(VmDetailConstants.ROOT_DISK_CONTROLLER);
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

    // ---- data volumes (Phase 7): disk images owned by a per-account holder vmid, see design-phase7-data-volumes.md ----

    /** The holder of an account's data volumes is this plus the account id; 900000001 to 999999999 are reserved for CloudStack. */
    static final long HOLDER_BASE = 900_000_000L;
    static final long MAX_ACCOUNT_ID = 99_999_999L;
    private static final Pattern HOLDER_VOLUME = Pattern.compile(":9\\d{8}/vm-9\\d{8}-");
    private static final long MIB_BYTES = 1024L * 1024L;

    /** True for a VM id in the range reserved for the holders of data volumes. */
    public static boolean isReservedVmId(long vmid) {
        return vmid > HOLDER_BASE && vmid <= 999_999_999L;
    }

    /** The vmid that owns the data volumes of an account: 900000000 + the account id. */
    public static long holderVmId(long accountId) {
        if (accountId < 1 || accountId > MAX_ACCOUNT_ID) {
            throw new ProxmoxApiException("The account id " + accountId + " is outside 1 to " + MAX_ACCOUNT_ID + ", the accounts whose data volumes Proxmox can hold");
        }
        return HOLDER_BASE + accountId;
    }

    /** The path of a data volume in its storage entry: "&lt;holder&gt;/vm-&lt;holder&gt;-&lt;volume uuid&gt;.qcow2". */
    public static String dataVolumePath(long accountId, String volumeUuid) {
        long holder = holderVmId(accountId);
        return holder + "/vm-" + holder + "-" + volumeUuid + ".qcow2";
    }

    /** True when a drive's value (or a volume id) names a data volume, that is a disk owned by a holder vmid. */
    public static boolean isHolderVolume(String driveValue) {
        return driveValue != null && HOLDER_VOLUME.matcher(driveValue).find();
    }

    /** The controller of a drive name: "scsi", "virtio" or "sata". */
    static String controllerOf(String drive) {
        return drive.replaceAll("\\d+$", "");
    }

    /**
     * The drive of a data volume: its controller is the detail "dataDiskController" (scsi, virtio, sata), by default the one of the root disk so
     * that a guest that needed sata for its root disk does not get disks it has no driver for; the number is CloudStack's device id.
     */
    public static String dataDrive(Long deviceId, Map<String, String> details) {
        String requested = details == null ? null : details.get(VmDetailConstants.DATA_DISK_CONTROLLER);
        String controller;
        if (StringUtils.isBlank(requested) || "osdefault".equalsIgnoreCase(requested.trim())) {
            controller = controllerOf(rootDiskDrive(details));
        } else {
            switch (requested.trim().toLowerCase(Locale.ROOT)) {
                case "scsi":
                case "virtio-scsi":
                    controller = "scsi";
                    break;
                case "virtio":
                    controller = "virtio";
                    break;
                case "sata":
                    controller = "sata";
                    break;
                default:
                    throw new ProxmoxApiException("The data disk controller " + requested + " is not supported for Proxmox: use scsi, virtio or sata");
            }
        }
        int max = "scsi".equals(controller) ? 30 : "virtio".equals(controller) ? 15 : 5;
        if (deviceId == null || deviceId < 1 || deviceId > max) {
            throw new ProxmoxApiException(String.format("Device id %s cannot be used on the %s controller of a Proxmox VM, which has the drives %s1 to %s%d", deviceId,
                    controller, controller, controller, max));
        }
        return controller + deviceId;
    }

    /**
     * The options of a drive that come from the disk offering of the volume: cache mode, the limits (iops as they are, bandwidth in
     * megabytes per second) and the bursts (the "max" values, with their length in seconds) as ",iops_rd=100,mbps_wr=10,mbps_wr_max=15,mbps_wr_max_length=10";
     * empty when the volume has none. A burst is only passed with its base limit (Proxmox refuses it otherwise) and when it is not below it.
     */
    public static String driveOptions(VolumeObjectTO volume) {
        StringBuilder options = new StringBuilder();
        DiskCacheMode cache = volume.getCacheMode();
        if (cache == DiskCacheMode.WRITEBACK || cache == DiskCacheMode.WRITETHROUGH) {
            options.append(",cache=").append(cache.toString().toLowerCase(Locale.ROOT));
        }
        appendLimit(options, "iops_rd", volume.getIopsReadRate(), volume.getIopsReadRateMax(), volume.getIopsReadRateMaxLength(), false);
        appendLimit(options, "iops_wr", volume.getIopsWriteRate(), volume.getIopsWriteRateMax(), volume.getIopsWriteRateMaxLength(), false);
        appendLimit(options, "mbps_rd", volume.getBytesReadRate(), volume.getBytesReadRateMax(), volume.getBytesReadRateMaxLength(), true);
        appendLimit(options, "mbps_wr", volume.getBytesWriteRate(), volume.getBytesWriteRateMax(), volume.getBytesWriteRateMaxLength(), true);
        return options.toString();
    }

    private static void appendLimit(StringBuilder options, String name, Long rate, Long burst, Long burstSeconds, boolean bytesToMegabytes) {
        if (rate == null || rate <= 0) {
            return;
        }
        options.append(',').append(name).append('=').append(limitValue(rate, bytesToMegabytes));
        if (burst != null && burst >= rate) {
            // the unit of the base limit decides the name of the burst: "mbps_rd" has "mbps_rd_max", "iops_rd" has "iops_rd_max"
            options.append(',').append(name).append("_max=").append(limitValue(burst, bytesToMegabytes));
            if (burstSeconds != null && burstSeconds > 0) {
                options.append(',').append(name).append("_max_length=").append(burstSeconds);
            }
        }
    }

    /** An iops value as it is, a bandwidth in bytes per second as megabytes per second with six decimals (Proxmox's own byte options keep three, which turns a small limit into none). */
    private static String limitValue(long value, boolean bytesToMegabytes) {
        if (!bytesToMegabytes) {
            return Long.toString(value);
        }
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(MIB_BYTES), 6, RoundingMode.HALF_UP).max(new BigDecimal("0.000001")).stripTrailingZeros().toPlainString();
    }

    /** True when every option of {@code wantedOptions} (",a=b,c=d") is in the drive value; the size, which Proxmox adds itself, is not compared. */
    public static boolean driveHasOptions(String driveValue, String wantedOptions) {
        String have = "," + StringUtils.substringAfter(StringUtils.defaultString(driveValue), ",") + ",";
        for (String wanted : StringUtils.split(StringUtils.defaultString(wantedOptions), ',')) {
            if (!have.contains("," + wanted + ",")) {
                return false;
            }
        }
        return true;
    }

    /** The drive of a VM configuration that holds the volume at {@code volumePath}, or null. Looks at disk drives only (not CD-ROMs). */
    public static String driveHolding(Map<String, String> config, String volumePath) {
        if (StringUtils.isBlank(volumePath)) {
            return null;
        }
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            if (key.matches("(scsi|virtio|sata)\\d+") && entry.getValue() != null && entry.getValue().contains(":" + volumePath)) {
                return key;
            }
        }
        return null;
    }

    /**
     * The drive that holds exactly the volume {@code volumeId} ("&lt;pool entry&gt;:&lt;path&gt;"), or null. Proxmox numbers a VM's disks per storage, so two
     * volumes of one VM on different pools can have the same path (after a move); the pool entry tells them apart.
     */
    public static String driveHoldingVolume(Map<String, String> config, String volumeId) {
        if (StringUtils.isBlank(volumeId)) {
            return null;
        }
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (entry.getKey().matches("(scsi|virtio|sata)\\d+") && entry.getValue() != null && volumeId.equals(StringUtils.substringBefore(entry.getValue(), ","))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Every disk drive that holds a volume at {@code volumePath}, in the configuration's order (see {@link #driveHoldingVolume} for why there can be several). */
    public static List<String> drivesHolding(Map<String, String> config, String volumePath) {
        List<String> drives = new ArrayList<>();
        if (StringUtils.isNotBlank(volumePath)) {
            for (Map.Entry<String, String> entry : config.entrySet()) {
                if (entry.getKey().matches("(scsi|virtio|sata)\\d+") && entry.getValue() != null && entry.getValue().contains(":" + volumePath)) {
                    drives.add(entry.getKey());
                }
            }
        }
        return drives;
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
        return nicModel(vm.getDetails());
    }

    /** As above, from the details of a VM (a plug command carries them). */
    static String nicModel(Map<String, String> details) {
        String requested = details == null ? null : details.get(VmDetailConstants.NIC_ADAPTER);
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
        return netOption(nic, model, nic.getName());
    }

    /** As above on the given bridge (the control NIC's is not the traffic label). */
    static String netOption(NicTO nic, String model, String bridge) {
        if (StringUtils.isBlank(bridge)) {
            throw new ProxmoxApiException(String.format("NIC %d (MAC %s) has no bridge: set the KVM traffic label of the physical network to "
                    + "the Proxmox bridge", nic.getDeviceId(), nic.getMac()));
        }
        StringBuilder option = new StringBuilder(model).append('=').append(nic.getMac()).append(",bridge=").append(bridge);
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

    /** The CD drive that is reserved in the secondary storage VM and the console proxy VM for the file with the agent. */
    public static final String PATCH_CDROM = CDROM_DRIVES[0];

    /** The VMs whose agent (a Java program) is not in the template: the secondary storage VM and the console proxy VM. */
    public static boolean needsAgentPatch(VirtualMachineTO vm) {
        return vm.getType() == VirtualMachine.Type.SecondaryStorageVm || vm.getType() == VirtualMachine.Type.ConsoleProxy;
    }

    /** A system VM, a router or any other VM that CloudStack runs itself: everything but a user's instance. */
    public static boolean isSystemVm(VirtualMachineTO vm) {
        return vm.getType() != null && vm.getType() != VirtualMachine.Type.User;
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

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Test;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;

public class ProxmoxVmConfigTest {

    private static NicTO nic(int deviceId, String mac, String bridge, String broadcastUri, Integer rateMbps) {
        NicTO nic = mock(NicTO.class);
        when(nic.getDeviceId()).thenReturn(deviceId);
        when(nic.getMac()).thenReturn(mac);
        when(nic.getName()).thenReturn(bridge);
        when(nic.getBroadcastUri()).thenReturn(broadcastUri == null ? null : URI.create(broadcastUri));
        when(nic.getNetworkRateMbps()).thenReturn(rateMbps);
        return nic;
    }

    private static VirtualMachineTO vm(String platform, NicTO... nics) {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn("i-2-10-VM");
        when(vm.getCpus()).thenReturn(2);
        when(vm.getMaxRam()).thenReturn(2048L * 1024 * 1024);
        when(vm.getPlatformEmulator()).thenReturn(platform);
        when(vm.getNics()).thenReturn(nics);
        return vm;
    }

    @Test
    public void aVmIsSizedByCloudStackWithoutBallooning() {
        Map<String, String> options = ProxmoxVmConfig.startOptions(vm("win10", nic(0, "02:00:00:aa:bb:01", "vmbr1", "vlan://100", null)), 0);

        assertEquals("i-2-10-VM", options.get("name"));
        assertEquals("1", options.get("sockets"));
        assertEquals("2", options.get("cores"));
        assertEquals("2048", options.get("memory"));
        assertEquals("0", options.get("balloon"));
        assertEquals("win10", options.get("ostype"));
        assertEquals("virtio-scsi-single", options.get("scsihw"));
        assertEquals("order=scsi0", options.get("boot"));
    }

    private static VirtualMachineTO vmWithSpeed(int cpus, Integer minSpeed, Integer speed) {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getCpus()).thenReturn(cpus);
        when(vm.getMinSpeed()).thenReturn(minSpeed);
        when(vm.getSpeed()).thenReturn(speed);
        return vm;
    }

    @Test
    public void theCpuWeightIsTheShareOfTheNodeTheOfferingAsksFor() {
        // 2 cores of 1000 MHz on a node of 3 cores x 2400 MHz: 2000 / 7200 of the scale of 10000
        assertEquals(Integer.valueOf(2778), ProxmoxVmConfig.cpuUnits(vmWithSpeed(2, 1000, 1000), 7200));
        // a VM asking for the whole node, or more, gets the maximum
        assertEquals(Integer.valueOf(10000), ProxmoxVmConfig.cpuUnits(vmWithSpeed(3, 2400, 2400), 7200));
        assertEquals(Integer.valueOf(10000), ProxmoxVmConfig.cpuUnits(vmWithSpeed(8, 3000, 3000), 7200));
        // a tiny VM still gets a weight
        assertEquals(Integer.valueOf(2), ProxmoxVmConfig.cpuUnits(vmWithSpeed(1, 1, 1), 7200));
    }

    @Test
    public void theMinimumSpeedIsUsedWhenThereIsOneElseTheSpeed() {
        assertEquals(Integer.valueOf(1389), ProxmoxVmConfig.cpuUnits(vmWithSpeed(1, 1000, 2000), 7200));
        assertEquals(Integer.valueOf(2778), ProxmoxVmConfig.cpuUnits(vmWithSpeed(1, null, 2000), 7200));
    }

    @Test
    public void withoutASpeedOrANodeCapacityTheWeightIsLeftAtProxmoxsDefault() {
        assertNull(ProxmoxVmConfig.cpuUnits(vmWithSpeed(2, null, null), 7200));
        assertNull(ProxmoxVmConfig.cpuUnits(vmWithSpeed(2, 1000, 1000), 0));
        assertNull(ProxmoxVmConfig.cpuUnits(vmWithSpeed(2, 0, 0), 7200));
        assertNull(ProxmoxVmConfig.startOptions(vm("l26"), 0).get("cpuunits"));
    }

    @Test
    public void aGuestOsWithoutAMappingIsOther() {
        assertEquals("other", ProxmoxVmConfig.startOptions(vm(null), 0).get("ostype"));
        assertEquals("other", ProxmoxVmConfig.startOptions(vm(""), 0).get("ostype"));
    }

    @Test
    public void everyNicBecomesANumberedVirtioInterface() {
        Map<String, String> options = ProxmoxVmConfig.startOptions(vm("l26", nic(0, "02:00:00:aa:bb:01", "vmbr1", "vlan://100", null),
                nic(1, "02:00:00:aa:bb:02", "vmbr1", null, null)), 0);

        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,tag=100,firewall=0", options.get("net0"));
        assertEquals("virtio=02:00:00:aa:bb:02,bridge=vmbr1,firewall=0", options.get("net1"));
    }

    @Test
    public void theNetworkRateIsConvertedFromMegabitsToMegabytesPerSecond() {
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,tag=100,rate=12,firewall=0",
                ProxmoxVmConfig.netOption(nic(0, "02:00:00:aa:bb:01", "vmbr1", "vlan://100", 100)));
        // a slow network is not rounded down to "no limit"
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,rate=1,firewall=0",
                ProxmoxVmConfig.netOption(nic(0, "02:00:00:aa:bb:01", "vmbr1", "vlan://untagged", 4)));
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,firewall=0",
                ProxmoxVmConfig.netOption(nic(0, "02:00:00:aa:bb:01", "vmbr1", null, 0)));
    }

    @Test
    public void aNicWithoutABridgeIsRefusedWithAnInstruction() {
        try {
            ProxmoxVmConfig.netOption(nic(0, "02:00:00:aa:bb:01", null, "vlan://100", null));
        } catch (ProxmoxApiException e) {
            assertEquals(true, e.getMessage().contains("KVM traffic label"));
            return;
        }
        throw new AssertionError("expected the NIC to be refused");
    }

    @Test
    public void onlyVlanUrisGiveATag() {
        assertEquals("100", ProxmoxVmConfig.vlanTag(URI.create("vlan://100")));
        assertNull(ProxmoxVmConfig.vlanTag(URI.create("vlan://untagged")));
        assertNull(ProxmoxVmConfig.vlanTag(URI.create("vxlan://5000")));
        assertNull(ProxmoxVmConfig.vlanTag(null));
    }

    @Test
    public void theVmIdIsReadFromAVolumePath() {
        assertEquals(Long.valueOf(9204), ProxmoxVmConfig.vmIdOfVolumePath("9204/vm-9204-disk-0.qcow2"));
        assertEquals(Long.valueOf(9204), ProxmoxVmConfig.vmIdOfVolumePath("cs-1:9204/vm-9204-disk-0.qcow2"));
        assertNull(ProxmoxVmConfig.vmIdOfVolumePath("3f2a8c1e-uuid"));
        assertNull(ProxmoxVmConfig.vmIdOfVolumePath("cs-1:import/f.qcow2"));
        assertNull(ProxmoxVmConfig.vmIdOfVolumePath(""));
        assertNull(ProxmoxVmConfig.vmIdOfVolumePath(null));
    }

    @Test
    public void aDiskOptionGivesItsSizeAndItsPath() {
        String disk = "cs-7fd5:9204/vm-9204-disk-0.qcow2,iothread=1,size=8G";

        assertEquals(8L * 1024 * 1024 * 1024, ProxmoxVmConfig.diskSizeBytes(disk));
        assertEquals("9204/vm-9204-disk-0.qcow2", ProxmoxVmConfig.diskPath(disk));
    }

    @Test
    public void disksSizesInAllUnitsAreUnderstood() {
        assertEquals(512L * 1024, ProxmoxVmConfig.diskSizeBytes("a:b,size=512K"));
        assertEquals(100L * 1024 * 1024, ProxmoxVmConfig.diskSizeBytes("a:b,size=100M"));
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, ProxmoxVmConfig.diskSizeBytes("a:b,size=2T"));
        assertEquals(1536L * 1024 * 1024, ProxmoxVmConfig.diskSizeBytes("a:b,size=1.5G"));
        assertEquals(4096L, ProxmoxVmConfig.diskSizeBytes("a:b,size=4096"));
        assertEquals(0L, ProxmoxVmConfig.diskSizeBytes("a:b"));
        assertEquals(0L, ProxmoxVmConfig.diskSizeBytes(null));
        assertEquals(0L, ProxmoxVmConfig.diskSizeBytes("a:b,size=big"));
    }

    // ---- ISOs (Phase 6) ----

    @Test
    public void cdromSlotsMapToIdeDrivesStartingWithIde2() {
        assertEquals("ide2", ProxmoxVmConfig.cdromDrive(3L));
        assertEquals("ide2", ProxmoxVmConfig.cdromDrive(null));
        assertEquals("ide3", ProxmoxVmConfig.cdromDrive(4L));
        assertEquals("ide0", ProxmoxVmConfig.cdromDrive(5L));
        assertEquals("ide1", ProxmoxVmConfig.cdromDrive(6L));
    }

    @Test(expected = ProxmoxApiException.class)
    public void aSlotBeyondTheFourDrivesIsRefused() {
        ProxmoxVmConfig.cdromDrive(7L);
    }

    @Test(expected = ProxmoxApiException.class)
    public void aSlotBelowTheFirstCdromSlotIsRefused() {
        ProxmoxVmConfig.cdromDrive(2L);
    }

    @Test
    public void aCdromValueIsRecognisedWithAndWithoutADisc() {
        assertTrue(ProxmoxVmConfig.isCdrom("none,media=cdrom"));
        assertTrue(ProxmoxVmConfig.isCdrom("csi-x:iso/a.iso,media=cdrom,size=350K"));
        assertFalse(ProxmoxVmConfig.isCdrom("cs-x:9204/vm-9204-disk-0.qcow2,size=8G"));
        assertFalse(ProxmoxVmConfig.isCdrom(null));
        assertTrue(ProxmoxVmConfig.isEmptyCdrom("none,media=cdrom"));
        assertFalse(ProxmoxVmConfig.isEmptyCdrom("csi-x:iso/a.iso,media=cdrom"));
        assertEquals("csi-x:iso/a.iso,media=cdrom", ProxmoxVmConfig.cdromValue("csi-x:iso/a.iso"));
    }

    @Test
    public void theNetworkAdapterComesFromTheVmDetailAndDefaultsToVirtio() {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        assertEquals("virtio", ProxmoxVmConfig.nicModel(vm));

        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("nicAdapter", "E1000"));
        assertEquals("e1000", ProxmoxVmConfig.nicModel(vm));
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("nicAdapter", "Virtio"));
        assertEquals("virtio", ProxmoxVmConfig.nicModel(vm));
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("nicAdapter", " "));
        assertEquals("virtio", ProxmoxVmConfig.nicModel(vm));
    }

    @Test(expected = ProxmoxApiException.class)
    public void anAdapterProxmoxDoesNotHaveIsRefused() {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("nicAdapter", "pcnet"));

        ProxmoxVmConfig.nicModel(vm);
    }

    @Test
    public void theNicOptionUsesTheModelInPlaceOfVirtio() {
        NicTO nic = mock(NicTO.class);
        when(nic.getMac()).thenReturn("02:00:00:aa:bb:01");
        when(nic.getName()).thenReturn("vmbr1");

        assertEquals("e1000=02:00:00:aa:bb:01,bridge=vmbr1,firewall=0", ProxmoxVmConfig.netOption(nic, "e1000"));
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,firewall=0", ProxmoxVmConfig.netOption(nic));
    }

    @Test
    public void aBootableIsoInTheFirstSlotIsBootedBeforeTheDiskAndOtherwiseTheDiskIsFirst() {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getCpus()).thenReturn(1);
        when(vm.getMaxRam()).thenReturn(1024L * 1024 * 1024);
        Map<String, String> cdroms = new java.util.LinkedHashMap<>();
        cdroms.put("ide2", "csi-x:iso/a.iso,media=cdrom");
        cdroms.put("ide3", "none,media=cdrom");

        assertEquals("order=scsi0", ProxmoxVmConfig.startOptions(vm, 0, cdroms).get("boot"));

        when(vm.getBootloader()).thenReturn(com.cloud.template.VirtualMachineTemplate.BootloaderType.CD);
        Map<String, String> options = ProxmoxVmConfig.startOptions(vm, 0, cdroms);
        assertEquals("order=ide2;scsi0", options.get("boot"));
        assertEquals("csi-x:iso/a.iso,media=cdrom", options.get("ide2"));
        assertEquals("none,media=cdrom", options.get("ide3"));

        assertEquals("order=scsi0", ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>()).get("boot"));
    }

    @Test
    public void theRootDiskControllerDetailPicksTheDriveOfTheRootDisk() {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        assertEquals("scsi0", ProxmoxVmConfig.rootDiskDrive(vm));
        for (String scsi : new String[] {"scsi", "osdefault", "virtio-scsi", " SCSI ", ""}) {
            when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("rootDiskController", scsi));
            assertEquals(scsi, "scsi0", ProxmoxVmConfig.rootDiskDrive(vm));
        }
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("rootDiskController", "virtio"));
        assertEquals("virtio0", ProxmoxVmConfig.rootDiskDrive(vm));
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("rootDiskController", "sata"));
        assertEquals("sata0", ProxmoxVmConfig.rootDiskDrive(vm));
    }

    @Test
    public void ideAndUnknownControllersAreRefusedWithAReason() {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("rootDiskController", "ide"));
        try {
            ProxmoxVmConfig.rootDiskDrive(vm);
            org.junit.Assert.fail("ide must be refused");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("CD-ROM drives"));
        }
        when(vm.getDetails()).thenReturn(java.util.Collections.singletonMap("rootDiskController", "nvme"));
        try {
            ProxmoxVmConfig.rootDiskDrive(vm);
            org.junit.Assert.fail("nvme must be refused");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("nvme"));
        }
    }

    @Test
    public void theRootDriveIsFoundByTheVolumeItHolds() {
        Map<String, String> config = new java.util.HashMap<>();
        config.put("ide2", "none,media=cdrom");
        config.put("sata0", "cs-x:9204/vm-9204-disk-0.qcow2,size=8G");
        config.put("scsi1", "cs-x:9204/vm-9204-disk-1.qcow2,size=1G");

        assertEquals("sata0", ProxmoxVmConfig.rootDriveOf(config, "9204/vm-9204-disk-0.qcow2"));
        assertNull(ProxmoxVmConfig.rootDriveOf(config, "9204/vm-9204-disk-7.qcow2"));
        assertNull(ProxmoxVmConfig.rootDriveOf(config, null));
    }

    @Test
    public void theBootOrderNamesTheDriveOfTheRootDisk() {
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getCpus()).thenReturn(1);
        when(vm.getMaxRam()).thenReturn(1024L * 1024 * 1024);

        assertEquals("order=sata0", ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>(), "sata0").get("boot"));
        assertEquals("order=scsi0", ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>()).get("boot"));
    }

    // ---- data volumes (Phase 7) ----

    @Test
    public void theHolderOfAnAccountsDataVolumesIsTheBaseAndTheAccountId() {
        assertEquals(900000002L, ProxmoxVmConfig.holderVmId(2));
        assertEquals(999999999L, ProxmoxVmConfig.holderVmId(99999999));
        assertEquals("900000002/vm-900000002-abc.qcow2", ProxmoxVmConfig.dataVolumePath(2, "abc"));
    }

    @Test(expected = ProxmoxApiException.class)
    public void accountZeroHasNoHolder() {
        ProxmoxVmConfig.holderVmId(0);
    }

    @Test(expected = ProxmoxApiException.class)
    public void anAccountIdBeyondTheReservedRangeHasNoHolder() {
        ProxmoxVmConfig.holderVmId(100000000L);
    }

    @Test
    public void theReservedRangeIsFromTheFirstHolderToTheLastVmId() {
        assertFalse(ProxmoxVmConfig.isReservedVmId(100));
        assertFalse(ProxmoxVmConfig.isReservedVmId(899999999L));
        assertFalse(ProxmoxVmConfig.isReservedVmId(900000000L));
        assertTrue(ProxmoxVmConfig.isReservedVmId(900000001L));
        assertTrue(ProxmoxVmConfig.isReservedVmId(999999999L));
        assertFalse(ProxmoxVmConfig.isReservedVmId(1000000000L));
    }

    @Test
    public void aHolderVolumeIsRecognisedInADriveValueButAVmsOwnDiskIsNot() {
        assertTrue(ProxmoxVmConfig.isHolderVolume("cs-x:900000002/vm-900000002-abc.qcow2,size=1G"));
        assertFalse(ProxmoxVmConfig.isHolderVolume("cs-x:9204/vm-9204-disk-0.qcow2,size=8G"));
        assertFalse(ProxmoxVmConfig.isHolderVolume("none,media=cdrom"));
        assertFalse(ProxmoxVmConfig.isHolderVolume(null));
    }

    @Test
    public void aDataDiskIsOnTheControllerOfTheRootDiskUnlessTheDetailSaysOtherwise() {
        assertEquals("scsi1", ProxmoxVmConfig.dataDrive(1L, null));
        assertEquals("sata2", ProxmoxVmConfig.dataDrive(2L, java.util.Collections.singletonMap("rootDiskController", "sata")));
        assertEquals("virtio4", ProxmoxVmConfig.dataDrive(4L, java.util.Collections.singletonMap("rootDiskController", "virtio")));
        Map<String, String> details = new java.util.HashMap<>();
        details.put("rootDiskController", "sata");
        details.put("dataDiskController", "scsi");
        assertEquals("scsi3", ProxmoxVmConfig.dataDrive(3L, details));
        details.put("dataDiskController", "osdefault");
        assertEquals("sata3", ProxmoxVmConfig.dataDrive(3L, details));
    }

    @Test
    public void theDriveNumbersOfEachControllerAreChecked() {
        assertEquals("scsi30", ProxmoxVmConfig.dataDrive(30L, null));
        assertEquals("virtio15", ProxmoxVmConfig.dataDrive(15L, java.util.Collections.singletonMap("dataDiskController", "virtio")));
        assertEquals("sata5", ProxmoxVmConfig.dataDrive(5L, java.util.Collections.singletonMap("dataDiskController", "sata")));
        for (Object[] bad : new Object[][] {{31L, "scsi"}, {16L, "virtio"}, {6L, "sata"}, {0L, "scsi"}, {null, "scsi"}}) {
            try {
                ProxmoxVmConfig.dataDrive((Long) bad[0], java.util.Collections.singletonMap("dataDiskController", (String) bad[1]));
                org.junit.Assert.fail(bad[0] + " on " + bad[1]);
            } catch (ProxmoxApiException e) {
                assertTrue(e.getMessage(), e.getMessage().contains((String) bad[1]));
            }
        }
    }

    @Test(expected = ProxmoxApiException.class)
    public void ideIsNotADataDiskController() {
        ProxmoxVmConfig.dataDrive(1L, java.util.Collections.singletonMap("dataDiskController", "ide"));
    }

    @Test
    public void theOfferingsLimitsAndCacheModeBecomeDriveOptions() {
        org.apache.cloudstack.storage.to.VolumeObjectTO volume = mock(org.apache.cloudstack.storage.to.VolumeObjectTO.class);
        assertEquals("", ProxmoxVmConfig.driveOptions(volume));

        when(volume.getCacheMode()).thenReturn(com.cloud.offering.DiskOffering.DiskCacheMode.WRITETHROUGH);
        when(volume.getIopsReadRate()).thenReturn(500L);
        when(volume.getIopsWriteRate()).thenReturn(250L);
        when(volume.getBytesReadRate()).thenReturn(20L * 1024 * 1024);
        when(volume.getBytesWriteRate()).thenReturn(1536L * 1024);

        assertEquals(",cache=writethrough,iops_rd=500,iops_wr=250,mbps_rd=20,mbps_wr=1.5", ProxmoxVmConfig.driveOptions(volume));
        when(volume.getCacheMode()).thenReturn(com.cloud.offering.DiskOffering.DiskCacheMode.NONE);
        // a small limit is kept to six decimals (100 bytes per second is 0.000095 MB/s), not rounded up to a hundredth
        when(volume.getBytesWriteRate()).thenReturn(100L);
        assertTrue(ProxmoxVmConfig.driveOptions(volume), ProxmoxVmConfig.driveOptions(volume).endsWith(",mbps_wr=0.000095"));
    }

    @Test
    public void theBurstValuesOfTheOfferingAreDriveOptionsWhenTheyAreNotBelowTheirLimit() {
        org.apache.cloudstack.storage.to.VolumeObjectTO volume = mock(org.apache.cloudstack.storage.to.VolumeObjectTO.class);
        when(volume.getIopsReadRate()).thenReturn(50L);
        when(volume.getIopsReadRateMax()).thenReturn(100L);
        when(volume.getIopsReadRateMaxLength()).thenReturn(10L);
        when(volume.getBytesReadRate()).thenReturn(10L * 1024 * 1024);
        when(volume.getBytesReadRateMax()).thenReturn(20L * 1024 * 1024);
        when(volume.getBytesReadRateMaxLength()).thenReturn(30L);
        // the write limits are below their burst: the burst is left out
        when(volume.getIopsWriteRate()).thenReturn(80L);
        when(volume.getIopsWriteRateMax()).thenReturn(70L);
        when(volume.getBytesWriteRate()).thenReturn(5L * 1024 * 1024);
        when(volume.getBytesWriteRateMax()).thenReturn(5L * 1024 * 1024);

        assertEquals(",iops_rd=50,iops_rd_max=100,iops_rd_max_length=10,iops_wr=80,mbps_rd=10,mbps_rd_max=20,mbps_rd_max_length=30,mbps_wr=5,mbps_wr_max=5",
                ProxmoxVmConfig.driveOptions(volume));
    }

    @Test
    public void aBurstWithoutItsBaseLimitIsLeftOutBecauseProxmoxRefusesIt() {
        org.apache.cloudstack.storage.to.VolumeObjectTO volume = mock(org.apache.cloudstack.storage.to.VolumeObjectTO.class);
        when(volume.getIopsReadRateMax()).thenReturn(100L);
        when(volume.getBytesWriteRateMax()).thenReturn(5L * 1024 * 1024);
        when(volume.getBytesWriteRateMaxLength()).thenReturn(10L);

        assertEquals("", ProxmoxVmConfig.driveOptions(volume));
    }

    @Test
    public void aDriveHasTheWantedOptionsWhenEachIsThere() {
        assertTrue(ProxmoxVmConfig.driveHasOptions("cs-x:1/a.qcow2,iops_rd=200,size=8G", ",iops_rd=200"));
        assertFalse(ProxmoxVmConfig.driveHasOptions("cs-x:1/a.qcow2,iops_rd=2000,size=8G", ",iops_rd=200"));
        assertFalse(ProxmoxVmConfig.driveHasOptions("cs-x:1/a.qcow2,size=8G", ",iops_rd=200"));
        assertTrue(ProxmoxVmConfig.driveHasOptions("cs-x:1/a.qcow2,size=8G", ""));
    }

    @Test
    public void theDriveThatHoldsAVolumeIsFoundByItsPathAmongTheDiskDrivesOnly() {
        Map<String, String> config = new java.util.HashMap<>();
        config.put("ide2", "cs-x:iso/900000002/x.iso,media=cdrom");
        config.put("scsi0", "cs-x:9204/vm-9204-disk-0.qcow2,size=8G");
        config.put("virtio2", "cs-x:900000002/vm-900000002-abc.qcow2,size=1G");

        assertEquals("virtio2", ProxmoxVmConfig.driveHolding(config, "900000002/vm-900000002-abc.qcow2"));
        assertEquals("scsi0", ProxmoxVmConfig.driveHolding(config, "9204/vm-9204-disk-0.qcow2"));
        assertNull(ProxmoxVmConfig.driveHolding(config, "9204/vm-9204-disk-1.qcow2"));
        assertNull(ProxmoxVmConfig.driveHolding(config, null));
    }

    private static NicTO controlNic(int deviceId) {
        NicTO nic = nic(deviceId, "0e:00:a9:fe:03:07", null, null, null);
        when(nic.getType()).thenReturn(com.cloud.network.Networks.TrafficType.Control);
        return nic;
    }

    @Test
    public void aSystemVmHasTheGuestAgentOnAndItsControlNicOnTheControlBridge() {
        VirtualMachineTO vm = vm("l26", nic(0, "02:00:00:aa:bb:01", "vmbr1", "vlan://100", null), controlNic(1));
        when(vm.getType()).thenReturn(com.cloud.vm.VirtualMachine.Type.DomainRouter);

        Map<String, String> options = ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>(), "scsi0", "cloud0");

        assertEquals("1", options.get("agent"));
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,tag=100,firewall=0", options.get("net0"));
        assertEquals("virtio=0e:00:a9:fe:03:07,bridge=cloud0,firewall=0", options.get("net1"));
    }

    @Test
    public void everyKindOfSystemVmGetsTheAgent() {
        for (com.cloud.vm.VirtualMachine.Type type : new com.cloud.vm.VirtualMachine.Type[] {com.cloud.vm.VirtualMachine.Type.SecondaryStorageVm,
            com.cloud.vm.VirtualMachine.Type.ConsoleProxy, com.cloud.vm.VirtualMachine.Type.DomainRouter,
            com.cloud.vm.VirtualMachine.Type.InternalLoadBalancerVm}) {
            VirtualMachineTO vm = vm("l26", controlNic(0));
            when(vm.getType()).thenReturn(type);
            assertEquals(type.toString(), "1", ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>(), "scsi0", "cloud0").get("agent"));
        }
    }

    @Test
    public void aUserVmHasNoAgentOptionAndItsNicsKeepTheirTrafficLabelBridge() {
        VirtualMachineTO vm = vm("l26", nic(0, "02:00:00:aa:bb:01", "vmbr1", null, null));
        when(vm.getType()).thenReturn(com.cloud.vm.VirtualMachine.Type.User);

        Map<String, String> options = ProxmoxVmConfig.startOptions(vm, 0);

        assertTrue(!options.containsKey("agent"));
        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,firewall=0", options.get("net0"));
    }

    @Test
    public void theControlBridgeIsOnlyForTheControlNic() {
        VirtualMachineTO vm = vm("l26", nic(0, "02:00:00:aa:bb:01", "vmbr1", null, null));
        when(vm.getType()).thenReturn(com.cloud.vm.VirtualMachine.Type.DomainRouter);

        assertEquals("virtio=02:00:00:aa:bb:01,bridge=vmbr1,firewall=0",
                ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>(), "scsi0", "cloud0").get("net0"));
    }

    @Test
    public void anyOtherKindOfNicStillNeedsATrafficLabel() {
        VirtualMachineTO vm = vm("l26", nic(0, "02:00:00:aa:bb:01", null, null, null));
        when(vm.getType()).thenReturn(com.cloud.vm.VirtualMachine.Type.DomainRouter);
        try {
            ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>(), "scsi0", "cloud0");
            org.junit.Assert.fail("a NIC without a bridge must be refused");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("traffic label"));
        }
    }

    @Test
    public void theSecondaryStorageVmAndTheConsoleProxyVmReserveAnEmptyCdDriveForTheAgent() {
        for (com.cloud.vm.VirtualMachine.Type type : new com.cloud.vm.VirtualMachine.Type[] {com.cloud.vm.VirtualMachine.Type.SecondaryStorageVm,
            com.cloud.vm.VirtualMachine.Type.ConsoleProxy}) {
            VirtualMachineTO vm = vm("l26", controlNic(0));
            when(vm.getType()).thenReturn(type);
            assertEquals(type.toString(), "none,media=cdrom",
                    ProxmoxVmConfig.startOptions(vm, 0, new java.util.LinkedHashMap<>(), "scsi0", "cloud0").get("ide2"));
        }
    }

    @Test
    public void routersAndUserVmsGetNoReservedCdDrive() {
        for (com.cloud.vm.VirtualMachine.Type type : new com.cloud.vm.VirtualMachine.Type[] {com.cloud.vm.VirtualMachine.Type.DomainRouter,
            com.cloud.vm.VirtualMachine.Type.InternalLoadBalancerVm, com.cloud.vm.VirtualMachine.Type.User}) {
            VirtualMachineTO vm = vm("l26", nic(0, "02:00:00:aa:bb:01", "vmbr1", null, null));
            when(vm.getType()).thenReturn(type);
            assertTrue(type.toString(), !ProxmoxVmConfig.startOptions(vm, 0).containsKey("ide2"));
        }
    }

    @Test
    public void aCdDriveThatTheStartCommandAsksForIsNotReplacedByTheReservedOne() {
        VirtualMachineTO vm = vm("l26", controlNic(0));
        when(vm.getType()).thenReturn(com.cloud.vm.VirtualMachine.Type.SecondaryStorageVm);
        Map<String, String> cdroms = new java.util.LinkedHashMap<>();
        cdroms.put("ide2", "csi-1:iso/x.iso,media=cdrom");

        assertEquals("csi-1:iso/x.iso,media=cdrom", ProxmoxVmConfig.startOptions(vm, 0, cdroms, "scsi0", "cloud0").get("ide2"));
    }
}

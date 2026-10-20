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
}

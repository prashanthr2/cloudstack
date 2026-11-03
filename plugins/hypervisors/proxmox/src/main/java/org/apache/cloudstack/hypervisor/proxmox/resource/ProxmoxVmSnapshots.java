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

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CreateVMSnapshotAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.RevertToVMSnapshotAnswer;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotBaseCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.snapshot.VMSnapshot;

/**
 * Instance snapshots (design-phase10-snapshots.md): a CloudStack instance snapshot is a Proxmox snapshot of the VM, which covers every disk in the VM's
 * configuration (data volumes included) and, for a snapshot "with memory", the memory. Proxmox names it after the CloudStack snapshot; the state
 * lives in Proxmox (CloudStack keeps its own list), so nothing is kept in the plugin.
 */
class ProxmoxVmSnapshots {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxVmSnapshots.class);
    /** Proxmox accepts letters, digits, "-" and "_" in a snapshot name, starting with a letter, up to 40 characters. */
    private static final int MAX_NAME_LENGTH = 40;

    private final Supplier<ProxmoxApiClient> clients;
    private final IntSupplier taskTimeoutSeconds;

    ProxmoxVmSnapshots(Supplier<ProxmoxApiClient> clients, IntSupplier taskTimeoutSeconds) {
        this.clients = clients;
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    /** The Proxmox name of a CloudStack snapshot: other characters become "_", a name that does not start with a letter gets an "s", the end is kept when it is too long. */
    static String snapshotName(String cloudStackName) {
        String name = StringUtils.defaultString(cloudStackName).replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            name = "s" + name;
        }
        return name.length() <= MAX_NAME_LENGTH ? name : "s" + name.substring(name.length() - (MAX_NAME_LENGTH - 1));
    }

    /** A snapshot takes as long as writing the memory, so the command's own wait (1800 s from the snapshot strategy) counts when it is longer than the task timeout. */
    private int waitSeconds(VMSnapshotBaseCommand cmd) {
        return Math.max(taskTimeoutSeconds.getAsInt(), cmd.getWait());
    }

    Answer create(CreateVMSnapshotCommand cmd) {
        VMSnapshotTO target = cmd.getTarget();
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation vm = client.findVmByName(cmd.getVmName());
            if (vm == null) {
                return new CreateVMSnapshotAnswer(cmd, false, "Proxmox has no VM " + cmd.getVmName());
            }
            boolean withMemory = target.getType() == VMSnapshot.Type.DiskAndMemory;
            if (withMemory && !vm.isRunning()) {
                return new CreateVMSnapshotAnswer(cmd, false, "A snapshot with memory needs a running instance, and " + cmd.getVmName() + " is " + vm.getStatus());
            }
            String name = snapshotName(target.getSnapshotName());
            if (client.listSnapshotNames(vm.getNode(), vm.getVmid()).contains(name)) {
                return new CreateVMSnapshotAnswer(cmd, false, "Proxmox VM " + vm.getVmid() + " has a snapshot " + name + " already");
            }
            client.waitForTask(client.createSnapshot(vm.getNode(), vm.getVmid(), name, target.getDescription(), withMemory), waitSeconds(cmd));
            LOGGER.info("Took snapshot {} of Proxmox VM {} ({}){}", name, vm.getVmid(), cmd.getVmName(), withMemory ? " with its memory" : "");
            return new CreateVMSnapshotAnswer(cmd, target, cmd.getVolumeTOs());
        } catch (ProxmoxApiException e) {
            LOGGER.error("Snapshot {} of {} failed: {}", target.getSnapshotName(), cmd.getVmName(), e.getMessage());
            return new CreateVMSnapshotAnswer(cmd, false, e.getMessage());
        }
    }

    /** A snapshot, or a VM, that Proxmox does not have any more is already deleted: CloudStack can drop its own record. */
    Answer delete(DeleteVMSnapshotCommand cmd) {
        String name = snapshotName(cmd.getTarget().getSnapshotName());
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation vm = client.findVmByName(cmd.getVmName());
            if (vm == null || !client.listSnapshotNames(vm.getNode(), vm.getVmid()).contains(name)) {
                LOGGER.info("Proxmox has no snapshot {} of {}: nothing to delete", name, cmd.getVmName());
                return new DeleteVMSnapshotAnswer(cmd, cmd.getVolumeTOs());
            }
            client.waitForTask(client.deleteSnapshot(vm.getNode(), vm.getVmid(), name, false), waitSeconds(cmd));
            LOGGER.info("Deleted snapshot {} of Proxmox VM {} ({})", name, vm.getVmid(), cmd.getVmName());
            return new DeleteVMSnapshotAnswer(cmd, cmd.getVolumeTOs());
        } catch (ProxmoxApiException e) {
            LOGGER.error("Deleting snapshot {} of {} failed: {}", name, cmd.getVmName(), e.getMessage());
            return new DeleteVMSnapshotAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Rolls the VM back. A snapshot with memory resumes the VM where it was (Proxmox stops it, puts the disks back and loads the memory), one without
     * leaves it stopped; the answer tells CloudStack which of the two it got.
     */
    Answer revert(RevertToVMSnapshotCommand cmd) {
        String name = snapshotName(cmd.getTarget().getSnapshotName());
        try {
            ProxmoxApiClient client = clients.get();
            ProxmoxApiClient.VmLocation vm = client.findVmByName(cmd.getVmName());
            if (vm == null) {
                return new RevertToVMSnapshotAnswer(cmd, false, "Proxmox has no VM " + cmd.getVmName());
            }
            if (!client.listSnapshotNames(vm.getNode(), vm.getVmid()).contains(name)) {
                return new RevertToVMSnapshotAnswer(cmd, false, "Proxmox VM " + vm.getVmid() + " has no snapshot " + name);
            }
            client.waitForTask(client.rollbackSnapshot(vm.getNode(), vm.getVmid(), name), waitSeconds(cmd));
            String status = client.getVmStatus(vm.getNode(), vm.getVmid());
            VirtualMachine.PowerState state = "running".equals(status) ? VirtualMachine.PowerState.PowerOn : VirtualMachine.PowerState.PowerOff;
            LOGGER.info("Rolled Proxmox VM {} ({}) back to snapshot {}; it is {}", vm.getVmid(), cmd.getVmName(), name, status);
            return new RevertToVMSnapshotAnswer(cmd, cmd.getVolumeTOs(), state);
        } catch (ProxmoxApiException e) {
            LOGGER.error("Rolling {} back to snapshot {} failed: {}", cmd.getVmName(), name, e.getMessage());
            return new RevertToVMSnapshotAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Before an instance is destroyed: removes every snapshot of the VM. CloudStack drops its instance snapshots from its database when it expunges an
     * instance and sends nothing, and the snapshots of a data disk are inside the disk's own file, which is kept (the disk is detached and goes on
     * to live on), so they would stay in it for good. A snapshot that cannot be removed from a disk that is gone already is removed from the
     * configuration anyway. A failure is logged and does not stop the destroy: what is left is only space in a data volume.
     */
    void deleteAll(ProxmoxApiClient client, ProxmoxApiClient.VmLocation vm) {
        List<String> names;
        try {
            names = client.listSnapshotNames(vm.getNode(), vm.getVmid());
        } catch (ProxmoxApiException e) {
            LOGGER.warn("The snapshots of Proxmox VM {} could not be listed before it is destroyed, those in its data volumes stay: {}", vm.getVmid(), e.getMessage());
            return;
        }
        // children before parents: Proxmox lists parents first
        for (int i = names.size() - 1; i >= 0; i--) {
            try {
                client.waitForTask(client.deleteSnapshot(vm.getNode(), vm.getVmid(), names.get(i), true), taskTimeoutSeconds.getAsInt());
                LOGGER.info("Deleted snapshot {} of Proxmox VM {} before it is destroyed", names.get(i), vm.getVmid());
            } catch (ProxmoxApiException e) {
                LOGGER.warn("Snapshot {} of Proxmox VM {} could not be deleted before the VM is destroyed, it stays in the data volumes: {}", names.get(i), vm.getVmid(),
                        e.getMessage());
            }
        }
    }
}

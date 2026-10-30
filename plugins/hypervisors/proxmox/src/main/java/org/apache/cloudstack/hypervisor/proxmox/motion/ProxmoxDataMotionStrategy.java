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
package org.apache.cloudstack.hypervisor.proxmox.motion;

import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Moves a running instance together with its volumes ("migrate instance with volumes") between Proxmox hosts. Proxmox's own combined migration
 * leaves a disk on shared (NFS) storage where it is, so this does it in two steps with commands the plugin understands already: every volume that
 * changes primary storage is moved while the instance runs (the live move of {@link MigrateVolumeCommand}), then the instance is moved to the
 * destination node (PrepareForMigrationCommand and MigrateCommand, as for a migration without volumes). The volumes' rows are updated as the live
 * move of a single volume does. Design: docs/design-phase9-storage-migration.md, section 4.3.
 */
public class ProxmoxDataMotionStrategy implements DataMotionStrategy {
    private static final Logger LOGGER = LogManager.getLogger(ProxmoxDataMotionStrategy.class);
    private static final String MIGRATE_WAIT = "migratewait";
    private static final int DEFAULT_MIGRATE_WAIT_SECONDS = 3600;

    @Inject
    AgentManager agentMgr;
    @Inject
    VolumeDao volDao;
    @Inject
    ConfigurationDao configDao;

    @Override
    public StrategyPriority canHandle(DataObject srcData, DataObject destData) {
        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public StrategyPriority canHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        if (srcHost.getHypervisorType() == HypervisorType.Proxmox && destHost.getHypervisorType() == HypervisorType.Proxmox) {
            return StrategyPriority.HYPERVISOR;
        }
        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyAsync(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTo, Host srcHost, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        Answer answer = null;
        String error = null;
        try {
            moveVolumes(volumeMap, vmTo, srcHost);
            answer = migrateInstance(vmTo, srcHost, destHost);
        } catch (Exception e) {
            LOGGER.error("Migration of {} with its volumes from {} to {} failed: {}", vmTo.getName(), srcHost, destHost, e.getMessage(), e);
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        CopyCommandResult result = new CopyCommandResult(null, answer);
        result.setResult(error);
        callback.complete(result);
    }

    /** The live move of each volume that changes pool, on the host the instance runs on; the row follows the answer, as after a move of one volume. */
    private void moveVolumes(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTo, Host srcHost) throws AgentUnavailableException, OperationTimedoutException {
        int wait = DEFAULT_MIGRATE_WAIT_SECONDS;
        String configured = configDao.getValue(MIGRATE_WAIT);
        if (StringUtils.isNumeric(configured) && !configured.isEmpty()) {
            wait = Integer.parseInt(configured);
        }
        for (Map.Entry<VolumeInfo, DataStore> entry : volumeMap.entrySet()) {
            VolumeInfo volume = entry.getKey();
            StoragePool destPool = (StoragePool) entry.getValue();
            if (volume.getPoolId() != null && volume.getPoolId() == destPool.getId()) {
                continue;
            }
            MigrateVolumeCommand command = new MigrateVolumeCommand(volume.getId(), volume.getPath(), destPool, vmTo.getName(), volume.getVolumeType(), wait);
            Answer answer = agentMgr.send(srcHost.getId(), command);
            if (answer == null || !answer.getResult() || !(answer instanceof MigrateVolumeAnswer)) {
                throw new CloudRuntimeException(String.format("Proxmox could not move the volume %s of %s to storage pool %s%s", volume.getName(), vmTo.getName(),
                        destPool.getName(), answer == null || answer.getDetails() == null ? "" : ": " + answer.getDetails()));
            }
            VolumeVO row = volDao.findById(volume.getId());
            Long oldPoolId = row.getPoolId();
            row.setPath(((MigrateVolumeAnswer) answer).getVolumePath());
            row.setPodId(destPool.getPodId());
            row.setPoolId(destPool.getId());
            row.setPoolType(destPool.getPoolType());
            row.setLastPoolId(oldPoolId);
            volDao.update(row.getId(), row);
            LOGGER.info("Moved volume {} of {} to storage pool {}", volume.getName(), vmTo.getName(), destPool.getName());
        }
    }

    /** The instance goes to the destination node when it is not there already (the volumes may have moved on the same node). */
    private Answer migrateInstance(VirtualMachineTO vmTo, Host srcHost, Host destHost) throws AgentUnavailableException, OperationTimedoutException {
        if (srcHost.getId() == destHost.getId()) {
            return new Answer(null, true, vmTo.getName() + " stays on " + srcHost.getName());
        }
        PrepareForMigrationCommand prepare = new PrepareForMigrationCommand(vmTo);
        Answer prepared = agentMgr.send(destHost.getId(), prepare);
        if (prepared == null || !prepared.getResult()) {
            throw new CloudRuntimeException(String.format("Host %s cannot receive %s%s", destHost.getName(), vmTo.getName(),
                    prepared == null || prepared.getDetails() == null ? "" : ": " + prepared.getDetails()));
        }
        MigrateCommand migrate = new MigrateCommand(vmTo.getName(), destHost.getPrivateIpAddress(), false, vmTo, false);
        migrate.setHostGuid(destHost.getGuid());
        Answer migrated = agentMgr.send(srcHost.getId(), migrate);
        if (migrated == null || !migrated.getResult()) {
            PrepareForMigrationCommand rollback = new PrepareForMigrationCommand(vmTo);
            rollback.setRollback(true);
            agentMgr.send(destHost.getId(), rollback);
            throw new CloudRuntimeException(String.format("Proxmox could not move %s to host %s%s", vmTo.getName(), destHost.getName(),
                    migrated == null || migrated.getDetails() == null ? "" : ": " + migrated.getDetails()));
        }
        return migrated;
    }
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

public class ProxmoxDataMotionStrategyTest {
    /** The destination as core hands it over: a data store that is also a storage pool. */
    interface PoolStore extends DataStore, StoragePool {
    }

    private AgentManager agentMgr;
    private VolumeDao volDao;
    private ProxmoxDataMotionStrategy strategy;
    private Host src;
    private Host dest;
    private VirtualMachineTO vm;
    private AsyncCompletionCallback<CopyCommandResult> callback;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        agentMgr = mock(AgentManager.class);
        volDao = mock(VolumeDao.class);
        strategy = new ProxmoxDataMotionStrategy();
        strategy.agentMgr = agentMgr;
        strategy.volDao = volDao;
        strategy.configDao = mock(ConfigurationDao.class);
        src = host(1L, "pve1", HypervisorType.Proxmox);
        dest = host(2L, "pve2", HypervisorType.Proxmox);
        vm = mock(VirtualMachineTO.class);
        when(vm.getName()).thenReturn("i-2-10-VM");
        callback = mock(AsyncCompletionCallback.class);
    }

    private static Host host(long id, String name, HypervisorType type) {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(id);
        when(host.getName()).thenReturn(name);
        when(host.getHypervisorType()).thenReturn(type);
        when(host.getPrivateIpAddress()).thenReturn("10.0.0." + id);
        return host;
    }

    private static VolumeInfo volume(long id, Long poolId, String path, Volume.Type type) {
        VolumeInfo volume = mock(VolumeInfo.class);
        when(volume.getId()).thenReturn(id);
        when(volume.getName()).thenReturn("vol" + id);
        when(volume.getPoolId()).thenReturn(poolId);
        when(volume.getPath()).thenReturn(path);
        when(volume.getVolumeType()).thenReturn(type);
        return volume;
    }

    private static PoolStore pool(long id) {
        PoolStore pool = mock(PoolStore.class);
        when(pool.getId()).thenReturn(id);
        when(pool.getName()).thenReturn("pool" + id);
        when(pool.getUuid()).thenReturn("uuid-" + id);
        when(pool.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);
        return pool;
    }

    private VolumeVO row(long id) {
        VolumeVO row = mock(VolumeVO.class);
        when(row.getId()).thenReturn(id);
        when(row.getPoolId()).thenReturn(1L);
        when(volDao.findById(id)).thenReturn(row);
        return row;
    }

    private CopyCommandResult result() {
        ArgumentCaptor<CopyCommandResult> result = ArgumentCaptor.forClass(CopyCommandResult.class);
        verify(callback).complete(result.capture());
        return result.getValue();
    }

    @Test
    public void onlyAMoveBetweenTwoProxmoxHostsIsHandled() {
        Map<VolumeInfo, DataStore> none = new LinkedHashMap<>();

        assertEquals(StrategyPriority.HYPERVISOR, strategy.canHandle(none, src, dest));
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(none, src, host(3L, "kvm1", HypervisorType.KVM)));
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(mock(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class),
                mock(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class)));
    }

    @Test
    public void everyVolumeThatChangesPoolIsMovedLiveThenTheInstanceIsMovedToTheOtherHost() throws Exception {
        VolumeInfo root = volume(10L, 1L, "106/vm-106-disk-0.qcow2", Volume.Type.ROOT);
        VolumeInfo data = volume(11L, 1L, "900000002/vm-900000002-abc.qcow2", Volume.Type.DATADISK);
        VolumeInfo same = volume(12L, 2L, "106/other.qcow2", Volume.Type.DATADISK);
        Map<VolumeInfo, DataStore> map = new LinkedHashMap<>();
        map.put(root, pool(2L));
        map.put(data, pool(2L));
        map.put(same, pool(2L));
        VolumeVO rootRow = row(10L);
        row(11L);
        when(agentMgr.send(eq(1L), any(MigrateVolumeCommand.class))).thenReturn(
                new MigrateVolumeAnswer(null, true, "ok", "106/vm-106-disk-1.qcow2"), new MigrateVolumeAnswer(null, true, "ok", "900000002/vm-900000002-abc.qcow2"));
        when(agentMgr.send(eq(2L), any(PrepareForMigrationCommand.class))).thenReturn(new PrepareForMigrationAnswer(mock(PrepareForMigrationCommand.class)));
        when(agentMgr.send(eq(1L), any(MigrateCommand.class))).thenReturn(new MigrateAnswer(mock(MigrateCommand.class), true, "moved", null));

        strategy.copyAsync(map, vm, src, dest, callback);

        CopyCommandResult done = result();
        assertFalse(done.getResult(), done.isFailed());
        InOrder order = inOrder(agentMgr);
        order.verify(agentMgr, org.mockito.Mockito.times(2)).send(eq(1L), any(MigrateVolumeCommand.class));
        order.verify(agentMgr).send(eq(2L), any(PrepareForMigrationCommand.class));
        order.verify(agentMgr).send(eq(1L), any(MigrateCommand.class));
        verify(rootRow).setPath("106/vm-106-disk-1.qcow2");
        verify(rootRow).setPoolId(2L);
        verify(rootRow).setLastPoolId(1L);
        verify(volDao, never()).findById(12L);
    }

    @Test
    public void aFailedVolumeMoveStopsBeforeTheInstanceIsMoved() throws Exception {
        VolumeInfo root = volume(10L, 1L, "106/vm-106-disk-0.qcow2", Volume.Type.ROOT);
        Map<VolumeInfo, DataStore> map = new LinkedHashMap<>();
        map.put(root, pool(2L));
        when(agentMgr.send(eq(1L), any(MigrateVolumeCommand.class))).thenReturn(new Answer(null, false, "no space"));

        strategy.copyAsync(map, vm, src, dest, callback);

        CopyCommandResult result = result();
        assertTrue(result.isFailed());
        assertTrue(result.getResult(), result.getResult().contains("no space"));
        verify(agentMgr, never()).send(eq(1L), any(MigrateCommand.class));
        verify(volDao, never()).update(any(Long.class), any(VolumeVO.class));
    }

    @Test
    public void whenTheInstanceIsAlreadyOnTheDestinationHostOnlyTheVolumesMove() throws Exception {
        VolumeInfo root = volume(10L, 1L, "106/vm-106-disk-0.qcow2", Volume.Type.ROOT);
        Map<VolumeInfo, DataStore> map = new LinkedHashMap<>();
        map.put(root, pool(2L));
        row(10L);
        when(agentMgr.send(eq(1L), any(MigrateVolumeCommand.class))).thenReturn(new MigrateVolumeAnswer(null, true, "ok", "106/vm-106-disk-1.qcow2"));

        strategy.copyAsync(map, vm, src, src, callback);

        assertFalse(result().isFailed());
        verify(agentMgr, never()).send(eq(1L), any(MigrateCommand.class));
    }

    @Test
    public void aDestinationHostThatCannotReceiveTheInstanceFailsBeforeTheMove() throws Exception {
        VolumeInfo data = volume(11L, 2L, "x", Volume.Type.DATADISK);
        Map<VolumeInfo, DataStore> map = new LinkedHashMap<>();
        map.put(data, pool(2L));
        when(agentMgr.send(eq(2L), any(PrepareForMigrationCommand.class))).thenReturn(new Answer(null, false, "bridge missing"));

        strategy.copyAsync(map, vm, src, dest, callback);

        assertTrue(result().isFailed());
        verify(agentMgr, never()).send(eq(1L), any(MigrateCommand.class));
    }

    @Test
    public void aFailedInstanceMoveIsReportedAndTheDestinationIsToldToForgetIt() throws Exception {
        VolumeInfo data = volume(11L, 2L, "x", Volume.Type.DATADISK);
        Map<VolumeInfo, DataStore> map = new LinkedHashMap<>();
        map.put(data, pool(2L));
        when(agentMgr.send(eq(2L), any(PrepareForMigrationCommand.class))).thenReturn(new PrepareForMigrationAnswer(mock(PrepareForMigrationCommand.class)));
        when(agentMgr.send(eq(1L), any(MigrateCommand.class))).thenReturn(new Answer(null, false, "timeout"));

        strategy.copyAsync(map, vm, src, dest, callback);

        assertTrue(result().isFailed());
        ArgumentCaptor<PrepareForMigrationCommand> prepares = ArgumentCaptor.forClass(PrepareForMigrationCommand.class);
        verify(agentMgr, org.mockito.Mockito.times(2)).send(eq(2L), prepares.capture());
        assertTrue(prepares.getAllValues().get(1).isRollback());
    }
}

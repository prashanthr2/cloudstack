# Phase 5 design: migration, host maintenance and HA

Status: **proposal, for review.** Nothing described here is implemented. "V" means read in code (CloudStack in this repository; Proxmox in the
8.x mirrors under `promox-src`, branch `stable-bookworm`, qemu-server 8.4.10). "U" means **unverified**: it depends on `pve-manager`,
`pve-ha-manager` or `pve-cluster`, which are not mirrored, or has not been run. Each U has a lab check in section 8.

## 1. Goal and scope

1. **Migration:** an administrator migrates a running Proxmox VM to another node of the same cluster (`migrateVirtualMachine`), and CloudStack
   migrates VMs away when a host goes into maintenance.
2. **HA:** the same stance as for VMware: **Proxmox's HA manager restarts VMs, CloudStack stays out of it**, and CloudStack's view (host, power
   state) follows what Proxmox does. CloudStack must also behave correctly for a VM that Proxmox HA manages (start, stop, migrate, delete).

Not in scope: storage migration and storage motion (the cluster-scoped NFS pool needs none), migration between clusters, and letting CloudStack
create or manage Proxmox HA resources (a decision in section 9).

## 2. What CloudStack does today for a Proxmox VM

**Migration is blocked at the API (V).** `ManagementServerImpl.LIVE_MIGRATION_SUPPORTING_HYPERVISORS` (:901) and
`UserVmManagerImpl.isOnSupportedHypevisorForMigration` (:7703) do not list Proxmox, so `listHostsForMigrationOfVM` and `migrateVirtualMachine`
refuse. Storage motion is off (`hypervisor_capabilities.storage_motion_supported = 0`), which is right: candidates are then the hosts of the
same cluster.

**The migration command sequence (V, `VirtualMachineManagerImpl.migrate`, :3133 and following):**
1. `PrepareForMigrationCommand` to the **destination** host; the answer must be a `PrepareForMigrationAnswer` (it is cast without a check, :3402).
2. The VM goes to `Migrating`, `host_id` = destination, `last_host_id` = source.
3. `MigrateCommand` to the **source** host (fields: VM instance name, `destinationIp` = the destination's private address, the destination
   guid, the VM TO). Only `getResult()` is read.
4. `CheckVirtualMachineCommand` to the destination (`checkVmOnHost`): it fails unless the answer is a `CheckVirtualMachineAnswer` that is not
   `PowerOff`. The plugin answers "unsupported" today, so even a successful migration would be rolled back.
5. On any failure a **plain `StopCommand` (`checkBeforeCleanup=false`) is sent to the destination host** (:3332) and the VM goes back to the source.
   It looks exactly like a user's stop.

**Host maintenance (V, `ResourceManagerImpl.doMaintain`):** requires the global setting `vm.ha.enabled` when the host has VMs, sends
`MaintainCommand` (the plugin answers with a plain `MaintainAnswer`, which says "migrate"), then schedules a migration away for each VM
(`HighAvailabilityManagerImpl.scheduleMigration` -> `migrateAway`). A host whose VMs cannot all be moved ends in `ErrorInPrepareForMaintenance`.

**HA in core (V):**
- Only VMware and Hyperv are skipped: `HighAvailabilityManagerImpl` :334 (host down), :510 and :555 (restart), and
  `VirtualMachineManagerImpl` :5551 (restart after an out-of-band power off). XenServer is not skipped; Proxmox is not either.
- `ProxmoxInvestigator` returns `Disconnected` for a Proxmox host, so a dead node never becomes `Down`, `scheduleRestartForVmsOnHost` is never
  called, and CloudStack does not restart anything when a node dies (same outcome as VMware, by a different route, Phase 1).
- **A VM's host follows Proxmox (V):** the power-state report of the node that lists the VM sets `power_host`, and
  `handlePowerOnReportWithNoPendingJobsOnVM` then moves `vm_instance.host_id` to it (`FollowAgentPowerOnReport`). `trackVmHostChange()` is never
  called by core. So when Proxmox HA restarts a VM on node B, CloudStack moves it to B within a ping, as in a VMware vMotion.

**Three scenarios today:**
- **(a) A node dies and Proxmox HA starts the VM elsewhere:** CloudStack does nothing, then follows the VM to node B (above). Fine. A small race
  (V mechanics, I outcome): if node A comes back before B's report is processed, A's report omits the VM, and after one round it is treated as
  missing and set to Stopped, until B's next report sets it Running again.
- **(b) A user stops a VM in CloudStack that Proxmox HA manages (I, strong):** the Proxmox API does not stop an HA-managed VM itself: it queues
  `ha-manager crm-command stop` and returns (V in qemu-server). The plugin's `waitFor` therefore ends at once, CloudStack marks the VM Stopped,
  and the VM is still running or shutting down; the next report says PowerOn for a Stopped VM, CloudStack sets it Running again ("out of band power
  on"). A flap.
- **(c) CloudStack HA is on for the offering (I, strong):** an out-of-band stop of the VM makes core call `scheduleRestart`; the investigators then send
  `CheckVirtualMachineCommand`, the plugin answers with an `UnsupportedAnswer`, and `CheckOnAgentInvestigator` casts it to
  `CheckVirtualMachineAnswer` without checking (V, :49): a `ClassCastException`, a retry loop and a VM that stays Running in CloudStack while it is
  stopped in Proxmox.

## 3. What Proxmox does (V in qemu-server 8.4.10 unless marked)

**Migration:** `POST /nodes/{node}/qemu/{vmid}/migrate`, called on the node that holds the VM.
- Parameters: `target` (required), `online`, `bwlimit`, `targetstorage`, `with-local-disks`. **`force`, `migration_type` and `migration_network` are
  root-only** (the check is for the exact user `root@pam`, so no token passes): the plugin must not send them.
- Permission `VM.Migrate` on `/vms/{vmid}` (in `PVEVMAdmin`: the token already has it). No storage permission is needed on shared storage, and
  nothing is checked for the target node or `SDN.Use`.
- Returns a task id (`...:qmigrate:<vmid>:<user>!<token>:`), registered on the source node. Success is exit status `OK` (or `WARNINGS: n`, also success).
- A stopped VM on shared storage only moves its configuration (seconds). A running VM on shared NFS transfers its memory only; the call is a task that
  runs until the end. `online=1` for a stopped VM is accepted (a warning, then an offline migration), so always sending it is safe. A running VM
  without `online=1` is refused.
- The VM is locked (`lock: migrate`) for the duration, for stopped VMs too. On success only the node changes (the configuration file moves between
  nodes); id and name stay. On failure the VM stays where it was, running.
- `GET .../migrate?target=<node>` is a precondition check: `allowed_nodes`, `not_allowed_nodes`, `local_disks`, `local_resources`. It does **not**
  check CPU compatibility, bridges or VLANs on the target.
- Failures to expect: a local CD-ROM image or disk ("can't migrate local disk ... local cdrom image", also offline), local devices (USB, PCI, serial),
  a bridge that does not exist on the target (only found when the VM starts on the target: "bridge '<b>' does not exist"), a CPU or machine-type
  mismatch (a QEMU error in the task log), and a lock held by another task ("VM is locked (...)").
- The task runs SSH between the nodes as root: it needs the cluster's normal trust, not anything from the API token.

**HA (qemu-server redirects, V; the HA manager itself is U):** for a VM that is an HA resource (state not `ignored`):
- `status/start` -> task `hastart`, `ha-manager set vm:<id> --state started`; `status/stop` -> `hastop`, `ha-manager crm-command stop vm:<id> 0`;
  `status/shutdown` -> `hastop` with the timeout (`forceStop` is ignored on this path); `migrate` -> `hamigrate`, `ha-manager migrate vm:<id> <node>`
  (`online`, `bwlimit`, `targetstorage` are dropped). **These tasks only queue a request and end at once**; the real work follows later (a `qmigrate`
  task started by the HA agent, a shutdown, a start). `reboot` and `reset` are not redirected.
- `DELETE` of an HA VM fails ("used in HA resources and purge parameter not set") unless `purge=1`, which also removes the HA resource. The plugin
  already sends `purge=1`.
- HA API (`/cluster/ha/...`) privileges are U (standard: `Sys.Console` on `/` to write, `Sys.Audit` to read). **`PVEVMAdmin` does not contain
  `Sys.Console` (V)**, so the current token cannot manage HA resources. It does not need to for anything above: the redirect is done by Proxmox.
- In qemu-server 8.4 the VM configuration has no HA field; HA state lives in `/etc/pve/ha/resources.cfg`.

**Node failure and maintenance (U, standard behaviour):** a node that loses quorum is fenced by the watchdog (about a minute) and its HA VMs are
restarted on other nodes (about two minutes in all); non-HA VMs stay down. `ha-manager crm-command node-maintenance enable <node>` makes the HA
manager migrate HA VMs away and avoid the node; it is a command-line tool and **probably has no REST endpoint**, so a token cannot use it.

**State during a migration:** the VM stays on the source with `status=running` and `lock=migrate` until the configuration moves (then it is on the
target within seconds); a stopped VM shows `stopped` with `lock=migrate`. `/cluster/resources` shows the VM's `node` and `lock` (U).

## 4. Design

### 4.1 Migration (plugin)

All three commands are answered by `ProxmoxResource`, with a new `ProxmoxMigrationHandler` (or the existing `ProxmoxVmHandler`).

- **`PrepareForMigrationCommand`** (destination host): checks that the destination node is online, that the VM exists and is **not** already on this
  node, and asks Proxmox for the precondition (`GET .../migrate?target=<this node>`): the node must be in `allowed_nodes`, with no `local_disks` or
  `local_resources`. A problem fails the command with Proxmox's reason **before** CloudStack changes the VM's state. Answers `PrepareForMigrationAnswer`.
- **`MigrateCommand`** (source host): maps `destinationIp` to the destination node name (the same lookup `PingTestCommand` uses, from the cluster's
  node list; the guid cannot be used), finds the VM's current node, and
  - if the VM is already on the destination: success (idempotent);
  - otherwise `POST .../migrate` with `target` and `online=1` (always, see section 3) and waits for the task for as long as
    `proxmox.migration.timeout` (new setting, default 3600 s, because a large VM takes longer than `proxmox.task.timeout`), then **checks the VM's node**,
    since for an HA VM the call only queues the move;
  - on failure answers false with the task's reason; the VM is still running on the source, which is what CloudStack then assumes.
- **`CheckVirtualMachineCommand`** (destination): answers a `CheckVirtualMachineAnswer` with `PowerOn` only when the VM is running **on this node**,
  else `PowerOff`.
- **Stop and reboot act only on the node that holds the VM** (the cleanup `StopCommand` after a failed migration goes to the *destination*, where the
  VM is not): if the VM is running on another node, `StopCommand` and `RebootCommand` answer a failure ("running on node X, not on this node") and
  touch nothing; a VM that is missing or already stopped is still a success. Today the handler looks the VM up cluster-wide and would stop the VM on
  the source. The trade-off: if Proxmox HA has just moved the VM and CloudStack's host is not updated yet, a user's stop is refused with that
  message until the next power-state report (seconds to a minute), instead of being sent to the wrong node.
- **Start** already moves a stopped VM to the deployment node first; it now waits until the VM is *on* that node (an HA move is only queued) before
  configuring and starting it.

### 4.2 Core changes (four places, all small)

1. `ManagementServerImpl.LIVE_MIGRATION_SUPPORTING_HYPERVISORS` and its error text: add Proxmox.
2. `UserVmManagerImpl.isOnSupportedHypevisorForMigration` and the messages around it: add Proxmox.
3. `HighAvailabilityManagerImpl` :334, :510, :555: skip Proxmox like VMware and Hyperv (CloudStack does not restart or fence VMs of a hypervisor that has its
   own HA).
4. `VirtualMachineManagerImpl` :5551: the same skip for the restart after an out-of-band power off. With it an out-of-band stop of a Proxmox VM is
   synced to Stopped, and scenario (c) is gone.

Not needed: `trackVmHostChange` (never called), storage motion, `MigrateWithStorageCommand`, any fencer, a change to `CheckOnAgentInvestigator` (once the
plugin answers `CheckVirtualMachineCommand` correctly the cast no longer fails, and the skip in 3 and 4 keeps the HA path away anyway),
`ProxmoxInvestigator` (stays as it is: `Disconnected`, which keeps a dead node from becoming `Down`).

### 4.3 HA stance and the behaviour for HA-managed VMs

- **Proxmox HA decides; CloudStack follows.** The offering's HA flag has no effect for Proxmox VMs, as for VMware (documented). A VM that Proxmox HA
  restarts after a node failure is followed by the power-state report (section 2). Nothing is added for node failure.
- **Stop, start and migrate of an HA-managed VM are only requests to Proxmox.** The plugin therefore **waits for the VM's real state**, not only the
  task: after `shutdown` or `stop` it polls `status/current` until the VM is stopped (up to the timeout, then reports failure, so scenario (b) cannot
  mark a running VM Stopped); after `start` until it is running; after a migrate until its node is the target. This is also correct for VMs that are
  not HA-managed, where the task and the state agree, so there is no special case.
- **Deleting a VM stops it first and then destroys it with `purge=1`** (already the case), which also removes its HA resource; with the wait above the
  destroy no longer races the stop.
- **A VM stopped by CloudStack is stopped for HA too** (`crm-command stop` leaves the resource requested stopped, U), so Proxmox HA does not restart it;
  starting it from CloudStack sets it to started again.

### 4.4 Host maintenance

- `MaintainCommand` keeps answering with a plain `MaintainAnswer` (migrate): CloudStack then migrates each VM away itself (`migrateAway`), which works
  once 4.1 exists, also for HA-managed VMs (the migrate becomes an HA request, 4.3). `vm.ha.enabled` must be true globally, as for any hypervisor
  (core requirement, V).
- **Proxmox's HA manager is not told that the node is in maintenance**: `ha-manager crm-command node-maintenance` has no REST endpoint (U), so
  Proxmox HA could place or restart VMs on that node while CloudStack empties it. Documented as a limit; the administrator can set maintenance in
  Proxmox by hand on the node (CLI) for the duration. A REST way, if the lab check finds one, can be added later.

### 4.5 Power-state report during moves

No change is planned: during a CloudStack migration core ignores reports for the VM (a job is pending). During a Proxmox-initiated move the VM
leaves the source's list and appears on the target's; core's "missing report" grace of one round covers the gap. The lab check L7 verifies this.

## 5. Privileges

No new privileges for the design above: `VM.Migrate` (in `PVEVMAdmin`) covers migrate. The HA API and node maintenance would need `Sys.Console`
(and `Sys.Audit`) and are not used.

## 6. Risks and limits

- A VM with a local ISO, local disk or local device cannot be migrated; the precondition shows it, and the error is clear.
- A bridge or VLAN missing on the target is found only at the end (the migration fails and the VM stays); an administrator must keep the bridges
  identical on all nodes (as for KVM).
- CPU incompatibility between nodes appears as a task error from QEMU.
- Proxmox HA can move VMs on its own at any time (failure, its own rules), and CloudStack only follows; a user's stop during such a move is refused with a
  message (4.1).
- Node maintenance is not coordinated with Proxmox HA (4.4).
- Live migration of a running VM with a lot of memory takes minutes; the command waits (`proxmox.migration.timeout`).

## 7. Test plan once built

1. Migrate a running VM to each other node and back: it keeps running, `host_id` is the target, the console still opens (new one).
2. Migrate with a local ISO attached, and to a node without the bridge: a clear failure, the VM keeps running on the source, CloudStack's state is
   unchanged.
3. Force a failure after `Prepare`: no VM is stopped by the cleanup (check the VM is still running on the source).
4. Put a host into maintenance: its VMs move away; cancel maintenance.
5. With an HA resource (added by hand in Proxmox): stop and start from CloudStack, migrate, destroy; the Proxmox state and CloudStack's agree after each.
6. Kill a node that holds an HA VM: CloudStack does not restart it; after Proxmox restarts it elsewhere the VM follows to the new host.
7. Stop a Proxmox VM in Proxmox's own UI: CloudStack syncs it to Stopped, with and without the offering's HA flag.

## 8. Lab checks before building

Use the token of the host (`source ~/tp.sh`, `$TP`; as `root@pam!cloudstack` or `pveaudit@pam!cloudstack`) on a node, `H=127.0.0.1`. `$N` is the node
that holds the VM, `$ID` its vmid, `$T` another node.

- **L1, migrate by token:** `curl -ks -X POST -H "Authorization: PVEAPIToken=$TP" https://$H:8006/api2/json/nodes/$N/qemu/$ID/migrate --data-urlencode target=$T --data-urlencode online=1`;
  then poll the task (`GET /nodes/$N/tasks/<upid>/status`), and `GET /api2/json/cluster/resources?type=vm` to see the node change. Repeat with
  `--data-urlencode migration_type=insecure`: expect "Only root may use this option." Record the task's `exitstatus` and the time for a running VM.
- **L2, precondition:** `GET /nodes/$N/qemu/$ID/migrate?target=$T`: record `allowed_nodes`, `local_disks`, `local_resources`. Repeat with a local ISO
  attached (`qm set $ID --ide2 local:iso/<file>`), and with a bridge that does not exist on `$T`.
- **L3, HA permissions and semantics:** `curl .../api2/json/cluster/ha/resources` with the token (expect 403 or an empty list), then as root on a node
  `ha-manager add vm:$ID --state started --max_restart 1 --max_relocate 1`.
- **L4, redirects:** with the VM an HA resource, `POST status/shutdown` and `status/start` and `migrate` by token: record the task types (`hastop`,
  `hastart`, `hamigrate`), that each task ends at once, and how long until `status/current` and the node really change; and `ha-manager config` after a
  stop (does the resource stay `started` or become `stopped`?).
- **L5, delete an HA VM:** stop it, `DELETE .../qemu/$ID` (expect the "used in HA resources" refusal), then `?purge=1`.
- **L6, node failure (optional but important):** with an HA VM on node `$T`, power the node off; time `ha-manager status` until the VM runs elsewhere;
  note what `/cluster/resources` shows meanwhile, and what a non-HA VM on that node shows.
- **L7, in-flight state:** during a long migration poll `status/current` and `/cluster/resources?type=vm` every second: `status`, `qmpstatus`, `lock`,
  `node`.
- **L8, maintenance:** `pvesh ls /cluster/ha` and `pvesh usage /cluster/ha -v` (is there any node-maintenance endpoint?), and as root
  `ha-manager crm-command node-maintenance enable $T`, `ha-manager status`, then `disable`.

## 9. Decisions for the reviewer

1. **HA stance:** CloudStack stays out of Proxmox HA, as for VMware (the offering's HA flag has no effect for Proxmox). Confirm.
2. **Should CloudStack create and remove Proxmox HA resources** (for example when the offering's HA flag is set)? It would need `Sys.Console` and
   `Sys.Audit` for the token (a new privilege beyond `PVEVMAdmin`) and the group and restart settings to choose. Recommendation: not in this phase;
   HA resources are managed in Proxmox.
3. **Core changes:** the four places in 4.2 (two migration lists, three HA skips and one in the power-report restart), all adding Proxmox next to VMware.
4. **A stop that finds the VM on another node is refused** (4.1) instead of being sent to the actual node. Confirm.
5. **New setting `proxmox.migration.timeout`** (default 3600 s).

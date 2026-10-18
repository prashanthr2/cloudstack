# Phase 5 design: migration, host maintenance and HA

Status: **proposal, for review.** Nothing described here is implemented. "V" means read in code (CloudStack in this repository; Proxmox in the
8.x mirrors under `promox-src`, branch `stable-bookworm`, qemu-server 8.4.10). "U" means **unverified**: it depends on `pve-manager`,
`pve-ha-manager` or `pve-cluster`, which are not mirrored, or has not been run. Each U has a lab check in section 8.

## 1. Goal and scope

1. **Migration:** an administrator migrates a running Proxmox VM to another node of the same cluster (`migrateVirtualMachine`), and CloudStack
   migrates VMs away when a host goes into maintenance.
2. **HA:** **Proxmox's HA manager restarts VMs, CloudStack's own HA stays out of it.** Unlike VMware, where HA is a cluster setting, Proxmox HA is
   *per VM*: only VMs that are HA resources are protected. So **the compute offering's HA flag is mapped to a Proxmox HA resource** for the VM
   (4.3), and CloudStack's view (host, power state) follows what Proxmox does. CloudStack must also behave correctly for a VM that Proxmox HA
   manages (start, stop, migrate, delete).

Not in scope: storage migration and storage motion (the cluster-scoped NFS pool needs none), and migration between clusters.

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

- **`PrepareForMigrationCommand`** (destination host). KVM prepares the networks of the VM on the destination here: its handler plugs every NIC
  through the VIF driver, which creates the VLAN bridge (V, `LibvirtPrepareForMigrationCommandWrapper`); the management-side `prepareNicForMigration` only
  runs network element hooks, with nothing to do for L2 and shared networks. For Proxmox a guest network is `bridge=<traffic label>,tag=<VLAN>` on a
  **VLAN-aware bridge**, so there is no per-VLAN object to create: Proxmox adds the VLAN when the VM starts. What must exist on the destination is the
  bridge itself. So the command checks, for each NIC of the VM (its bridge name and VLAN are in the VM TO), that the destination node has that bridge,
  active and VLAN-aware when a tag is used (`GET /nodes/{node}/network`, U), and also that the destination is online, that the VM exists and is **not**
  already on this node, and the precondition `GET .../migrate?target=<this node>`: the node must be in `allowed_nodes`, with no `local_disks` or
  `local_resources`. Any problem fails the command with a clear reason **before** CloudStack changes the VM's state. Answers `PrepareForMigrationAnswer`.
  Creating the bridge on the destination is not attempted (it would need `Sys.Modify` on the node and a network reload); the administrator keeps the
  bridges identical on all nodes, which a deployment on any node already requires.
- **`MigrateCommand`** (source host): maps `destinationIp` to the destination node name (the same lookup `PingTestCommand` uses, from the cluster's
  node list; the guid cannot be used), finds the VM's current node, and
  - if the VM is already on the destination: success (idempotent);
  - otherwise `POST .../migrate` with `target` and `online=1` (always, see section 3) and waits for the task for as long as
    `proxmox.migration.timeout` (new setting, default 3600 s, because a large VM takes longer than `proxmox.task.timeout`), then **checks the VM's node**,
    since for an HA VM the call only queues the move;
  - on failure answers false with the task's reason; the VM is still running on the source, which is what CloudStack then assumes.
- **`CheckVirtualMachineCommand`** (destination): answers a `CheckVirtualMachineAnswer` with `PowerOn` only when the VM is running **on this node**,
  else `PowerOff`. A successful verification also clears the migration mark below.
- **Stop and reboot work on the VM wherever it is** (cluster-wide), so a user's stop always succeeds, even right after Proxmox HA has moved the VM and
  before CloudStack's host is updated (that takes up to one `ping.interval`, 60 s by default: the power-state report rides on the host ping). One case
  must be left alone: after a **failed migration** core sends a plain `StopCommand` (`checkBeforeCleanup=false`, V, indistinguishable from a user's stop)
  to the *destination* host, while the VM is still running on the *source*. To tell the two apart, `PrepareForMigrationCommand` leaves a **mark** on the
  destination's resource (VM name and time, kept in memory); a `StopCommand` or `RebootCommand` that arrives on that host for a VM that is running on
  *another* node while a mark exists is the cleanup of a failed migration, and is answered with success **without touching the VM**. The mark is cleared by
  `CheckVirtualMachineCommand` (the migration succeeded) or expires after `proxmox.migration.timeout` plus a margin. A stop without a mark acts on the
  VM's real node. (If a stop of the VM running on the source arrives while the mark of an unrelated, failed attempt is still alive, it goes to the
  source host, where the VM *is*, and works; only a command for the destination host is skipped.)
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

### 4.3 HA: Proxmox restarts VMs, per VM, driven by the offering's HA flag

CloudStack's own HA never restarts a Proxmox VM (the skips in 4.2), so there is exactly one HA authority, Proxmox. In Proxmox a VM is protected only if it
is an **HA resource** (`vm:<vmid>`, state `started`); the flag of the compute offering (`vm.isHaEnabled()`, which core also lets the user change with
`updateVirtualMachine haenable`) is what says whether the VM should be one. The plugin sees it at start: `VirtualMachineTO.isEnableHA()` (V).

- **At start**, after the VM is running: if the flag is set, the plugin makes sure the HA resource exists (`POST /cluster/ha/resources`, `sid=vm:<id>`,
  `state=started`, `comment=CloudStack`; no group, Proxmox's default restart and relocate limits). If the flag is **not** set and a resource with the
  comment `CloudStack` exists (the flag was turned off), it removes it. A resource without that comment, which an administrator made by hand, is never
  touched. The change of the flag therefore takes effect at the next start of the VM (documented).
- **Privileges:** the HA API needs more than `PVEVMAdmin` (standard: `Sys.Console` on `/` to write, `Sys.Audit` to read; U, lab check L3). If the token lacks
  them and the flag is set, the **start fails with a clear message** ("Proxmox HA could not be enabled for <vm>: the token needs ... ") rather than
  starting a VM that its owner believes is protected. (Decision 2.) A deployment that does not use HA needs no new privilege.
- **Node failure:** nothing more is added. Proxmox HA restarts the VM, and CloudStack follows it (section 2).
- **Stop, start and migrate of an HA resource are only requests to Proxmox.** The plugin therefore **waits for the VM's real state**, not only the task:
  after `shutdown` or `stop` it polls `status/current` until the VM is stopped (up to the timeout, then reports failure, so scenario (b) cannot mark a
  running VM Stopped); after `start` until it is running; after a migrate until its node is the target. This is also correct for VMs that are not HA
  resources, where the task and the state agree, so there is no special case.
- **Deleting a VM stops it first and then destroys it with `purge=1`** (already the case), which also removes its HA resource; with the wait above the
  destroy no longer races the stop.
- **A VM stopped by CloudStack is stopped for HA too** (`crm-command stop` leaves the resource requested stopped, U), so Proxmox HA does not restart it;
  starting it from CloudStack sets it to started again.
- Shared storage is required for HA (it is already, for the NFS pool). Groups and node priorities are left to Proxmox for now.

### 4.4 Host maintenance

CloudStack migrates **every** VM of the host away when an administrator starts maintenance, HA or not (core, V: `scheduleMigration` for each VM, which works
once 4.1 exists; `vm.ha.enabled` must be true globally, as for any hypervisor). Proxmox's HA manager only moves its own HA VMs and keeps using the node
as a target, so to keep Proxmox HA from placing VMs on a node that CloudStack is emptying, the node should be put in **Proxmox's maintenance mode once it is
empty**, and taken out of it when maintenance is cancelled:

1. `MaintainCommand` (start): the plugin does **not** enable Proxmox maintenance yet, so that Proxmox HA does not migrate the HA VMs by itself at the same
   time as CloudStack (two movers, two destinations). It answers as today (migrate), and starts a **watcher** for the node: every few seconds it lists the
   VMs on the node, and when the node is empty (or after a long timeout) it enables Proxmox's node maintenance.
2. `cancel maintenance`: the plugin registers a `ResourceListener` (core has `EVENT_CANCEL_MAINTENANCE_AFTER`, V) and disables Proxmox's node maintenance
   when CloudStack takes the host out of maintenance. A restart of the management server in between is covered by also disabling it when a host
   reconnects while it is not in maintenance in CloudStack.
3. **This depends on Proxmox offering a way to do it through the API with a token.** `ha-manager crm-command node-maintenance enable|disable <node>` is a
   command-line tool, and I could not find a REST endpoint (U, lab check L8). If there is none, the options are: leave it to the administrator (CLI, or
   a note in the maintenance procedure), or give the plugin SSH access to the nodes (not proposed: it breaks the no-agent, token-only model). If an
   endpoint exists (for the root user only, for example), the plugin cannot use it with a token either.

### 4.5 Power-state report during moves

No change is planned: during a CloudStack migration core ignores reports for the VM (a job is pending). During a Proxmox-initiated move the VM
leaves the source's list and appears on the target's; core's "missing report" grace of one round covers the gap. The lab check L7 verifies this.

## 5. Privileges

- Migration: `VM.Migrate` (in `PVEVMAdmin`, already granted); nothing on the target node.
- HA flag: the HA API (`/cluster/ha/resources`), standard `Sys.Console` on `/` (write) and `Sys.Audit` (read): **not in `PVEVMAdmin`**, so a deployment that
  wants HA must grant a role with them (for example a custom role) to the token and its user. Verified in lab check L3.
- Node maintenance: unknown (L8).

## 6. Risks and limits

- A VM with a local ISO, local disk or local device cannot be migrated; the precondition shows it, and the error is clear.
- A bridge missing on the target (or not VLAN-aware) is now found by the `PrepareForMigrationCommand` check, before anything changes; the administrator must
  keep the bridges identical on all nodes (as a deployment on any node already requires). A VLAN needs no action: it is a tag on the bridge.
- CPU incompatibility between nodes appears as a task error from QEMU.
- Proxmox HA can move VMs on its own at any time (failure, its own rules), and CloudStack only follows, within one `ping.interval` (60 s by default).
- The HA flag of a running VM changes at its next start, not at once (4.3).
- Coordinating node maintenance with Proxmox HA depends on an API that may not exist (4.4, L8).
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
- **L3, HA permissions and semantics:** with the token: `curl -ks -H "Authorization: PVEAPIToken=$TP" https://$H:8006/api2/json/cluster/ha/resources` (read),
  then `curl -ks -X POST -H "Authorization: PVEAPIToken=$TP" https://$H:8006/api2/json/cluster/ha/resources --data-urlencode sid=vm:$ID --data-urlencode state=started --data-urlencode comment=CloudStack`
  (create), and `curl -ks -X DELETE ... /cluster/ha/resources/vm:$ID` (remove): record which of the three work and the exact error texts (so the privilege the
  token needs is known). Then as root on a node `ha-manager config` to see the resource and its comment.
- **L4, redirects:** with the VM an HA resource, `POST status/shutdown` and `status/start` and `migrate` by token: record the task types (`hastop`,
  `hastart`, `hamigrate`), that each task ends at once, and how long until `status/current` and the node really change; and `ha-manager config` after a
  stop (does the resource stay `started` or become `stopped`?).
- **L5, delete an HA VM:** stop it, `DELETE .../qemu/$ID` (expect the "used in HA resources" refusal), then `?purge=1`.
- **L6, node failure (optional but important):** with an HA VM on node `$T`, power the node off; time `ha-manager status` until the VM runs elsewhere;
  note what `/cluster/resources` shows meanwhile, and what a non-HA VM on that node shows.
- **L7, in-flight state:** during a long migration poll `status/current` and `/cluster/resources?type=vm` every second: `status`, `qmpstatus`, `lock`,
  `node`.
- **L9, networks of the destination:** `curl -ks -H "Authorization: PVEAPIToken=$TP" https://$H:8006/api2/json/nodes/$T/network`: record, for the bridge used by the
  guest network (`vmbr1`), the fields `type`, `active` and `bridge_vlan_aware`, so the preparation check can rely on them.
- **L8, maintenance:** `pvesh ls /cluster/ha` and `pvesh usage /cluster/ha -v` (is there any node-maintenance endpoint?), and as root
  `ha-manager crm-command node-maintenance enable $T`, `ha-manager status`, then `disable`.

## 9. Decisions for the reviewer

1. **HA:** CloudStack's own HA never restarts Proxmox VMs, and **the offering's HA flag creates a Proxmox HA resource for the VM** (4.3); a change of
   the flag takes effect at the next start. Confirm.
2. **If the token cannot create the HA resource** (it lacks the privileges) and the flag is set: **fail the start with a clear message**, or start the VM
   without HA and log it? Recommendation: fail the start (the owner paid for HA).
3. **Core changes:** the four places in 4.2 (two migration lists, three HA skips and one in the power-report restart), all adding Proxmox next to VMware.
4. **Stop and reboot work on the VM's real node**, except for the cleanup of a failed migration (4.1, the mark). Confirm.
5. **Maintenance:** CloudStack empties the node first, then enables Proxmox maintenance (if the API allows it, L8); otherwise leave it to the
   administrator. Confirm, and say whether SSH access to the nodes is acceptable for this one purpose (not proposed).
6. **New setting `proxmox.migration.timeout`** (default 3600 s).

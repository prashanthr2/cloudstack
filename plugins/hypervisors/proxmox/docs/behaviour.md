# Proxmox plugin: behaviour and design decisions

One entry per behaviour: what it does, why, and where it lives. Update this file whenever behaviour changes.
Open questions are collected at the end.

## 1. Discovery (adding a host)

**Code:** `discoverer/ProxmoxServerDiscoverer`, `client/ProxmoxApiClient`

- One add-host call connects to the given address, reads `/cluster/status`, and creates a host for **every online node**.
  This matches how XenServer adds a whole pool from one host. Offline nodes are skipped. There is no automatic refresh;
  a node that joins later is added by adding the same address again (see below).
- Credentials: the username field carries the API token id and the password field the token secret. CloudStack requires
  both fields, and tokens are revocable and can be scoped, which a stored account password cannot (see "Why API tokens"
  and [requirements.md](requirements.md)).
- The "host name" field is an address with an optional port (`10.0.35.25` or `10.0.35.25:8443`); the port defaults to 8006
  and is used for every node. The scheme the UI adds (`http://`) and anything after the address are ignored: the API is
  always reached over HTTPS.
- TLS verification is the global setting `proxmox.api.verify.tls` (default `false`, like the VMware and XenServer plugins,
  which accept any server certificate), not part of the host URL. How to turn it on is in [requirements.md](requirements.md).
- A CloudStack cluster holds one Proxmox cluster. The first node added records the Proxmox cluster in the CloudStack
  cluster's GUID (`proxmox-cluster:<name>`; a standalone node uses its own name), as XenServer does with the pool UUID.
  Adding nodes of a different Proxmox cluster is refused. A GUID that was not set by the plugin is replaced.
- Adding the same address again skips the nodes that already exist and adds only new ones, which picks up a node that joined
  the Proxmox cluster later. If every online node is already added the call says so.
- The host GUID is derived from the Proxmox cluster name and the node name, so nodes of different Proxmox clusters
  cannot collide.
- Host details stored per host (all that CloudStack keeps for a host, and what the resource is rebuilt from after a
  management server restart): `username`, `password` (encrypted by the host details DAO when database encryption is on),
  `node`, `nodeip`, `endpoint`, `endpoints`. (Hosts added by earlier builds also carry `verifytls`, which is now ignored.)

**Why API tokens.** The management server is an unattended service. A token can be revoked without touching a user, can be
limited with privilege separation, does not expire after two hours like a login ticket, and works when the account has
two-factor authentication. Username/password login is possible later; the two are told apart by the `!` in a token id.

## 2. Host startup data

**Code:** `resource/ProxmoxResource.initialize()`

- CPU count and sockets, CPU speed (MHz) and total memory come from `/nodes/{node}/status`. The `External` hypervisor
  reports zeros, which breaks capacity planning; real values are needed for deployment.
- The host's private and storage address is the node's own cluster address (from `/cluster/status`), not the address used
  for the API call.
- The hypervisor version is stored as the short version (`8.4.21`). Proxmox reports `pve-manager/8.4.21/<hash>`, which
  does not fit the `host.hypervisor_version` column (varchar(32)).

## 3. Ping and VM state

**Code:** `ProxmoxResource.getCurrentStatus()`

- Each ping lists the VMs on that node only (`/nodes/{node}/qemu`), keyed by VM name (the CloudStack instance name), and
  reports only the **running** ones (PowerOn), like KVM; a VM that is not running is simply not in the report. Reporting cluster-wide from
  every host would make the recorded power host flap. A stopped VM is deliberately *not* reported as PowerOff: CloudStack takes a PowerOff report
  for a VM it thinks is Running as an out-of-band stop and releases its network and storage resources, and a VM that Proxmox HA has just moved to a
  node shows up there as stopped for a few seconds before it is started (lab P5-6, 2026-10-20: Running, Stopped with resources released, Running again).
  An out-of-band stop (the guest shut itself down) is therefore found by CloudStack's missing-report rule after about two ping intervals instead
  of one, and a normal CloudStack stop no longer causes a spurious \"Out of band VM power off\" event.
- Templates are skipped. VMs whose name is not a CloudStack instance name are ignored by the server.
- If the API cannot be reached the ping returns `null`, which CloudStack treats as a disconnect.
- A VM that Proxmox HA moves to another node is picked up through this report: the server notices the VM on a new host
  (out-of-band move) and corrects its host id. No Proxmox-specific code is needed for that.

## 4. API failover

**Code:** `ProxmoxApiClient.execute()`, discoverer (`endpoints` detail)

- Any Proxmox node answers for the whole cluster, so the discoverer stores the address of every online node, the one
  given first. If a connection to the address in use fails, the client tries the next and remembers the one that worked.
- Only connection failures cause a switch. An HTTP answer (401, 500 ...) means the address was reachable and is returned
  as an error. The connect timeout (`proxmox.api.connect.timeout`, 5 s by default) keeps a dead address from stalling a ping.
- Why: without it every host talks through one node, so losing that node put the healthy nodes into Alert too.
- Hosts added before this existed hold only one address and must be re-added to get the list.

## 5. Failure detection

**Code:** `resource/ProxmoxResource` (`PingTestCommand`), `ha/ProxmoxInvestigator`; the resulting host states come from
CloudStack's host state machine (`api/.../host/Status.java`), which the plugin does not change.

When a connected host stops answering, CloudStack asks its investigators what happened. Proxmox hosts are treated the way
VMware hosts are, because both rely on the hypervisor's own HA.

- `ProxmoxInvestigator` reports a Proxmox host that stopped answering as **`Disconnected`** ("agent link broken, host may
  be up"), never `Down`. `Down` is avoided because it makes CloudStack restart the host's VMs itself.
- `isVmAlive` answers **true** for Proxmox VMs (as VMware does), so CloudStack's HA never decides a Proxmox VM is dead.
- `PingTestCommand` (used by CloudStack's built-in ping investigator) is answered truthfully: yes only if the address
  belongs to a node the Proxmox cluster currently reports online. It used to answer yes to everything, so a powered-off
  node was vouched for by the others and stayed `Up`.

### Resulting host states (CloudStack's state machine, same as for VMware)

| What happens | Host becomes | Why |
|---|---|---|
| A host that was `Up` stops answering (ping timeout) | **`Alert`** | `Up` + `AgentDisconnected` -> `Alert` |
| The management server stops or restarts while the host is `Up` | `Disconnected` | `Up` + `ManagementServerDown` -> `Disconnected` |
| A disabled / maintenance host stops answering | `Disconnected` | `ShutdownRequested` |
| A `Disconnected` host stays unreachable | `Alert` once `alert.wait` (1800 s) has passed since its last good ping | `WaitedTooLong` |
| A host in `Alert` / `Disconnected` answers again | `Up` | reconnect scan (section 5a) |
| No investigator gives a verdict at all | stays `Up` until `alert.wait` has passed, then `Alert` | |

So the same dead node shows `Alert` when it fails while CloudStack is running and `Disconnected` after a management server
restart. That is standard CloudStack behaviour for every hypervisor, not something specific to Proxmox.
- To see `Alert` sooner for hosts that are already `Disconnected`, lower the global setting `alert.wait`.

**Decision history.** A first version asked a neighbouring node whether the Proxmox cluster still listed the node online
and reported `Alert` for an offline node. It was replaced by the VMware-style `Disconnected` verdict to behave like the
other hypervisors. For a host that was `Up` the visible state is `Alert` either way, because of the state machine above;
the verdict only changes the alert text and how hosts that are not `Up` are handled. (An earlier version of this document
claimed a lost host would show `Disconnected` until `alert.wait`; that was wrong for hosts that were `Up`.)

### Same path as a VMware host (checked by reading the code)

A VMware host and a Proxmox host go through identical steps when they stop answering. This comparison is from the source,
not from running a VMware host.

| Step | VMware | Proxmox |
|---|---|---|
| Ping (`getCurrentStatus`) | `null` if the hypervisor host is not connected | `null` if the Proxmox API cannot be reached |
| Ping timeout / investigation | `MonitorTask` -> `disconnectWithInvestigation(PingTimeout)` (shared code, no hypervisor-specific branches) | same |
| First investigation step: `CheckHealthCommand` | answers yes only if the hypervisor host is connected | answers yes only if the node's status can be read |
| Investigators | `PingInvestigator`, then `VmwareInvestigator` -> `Disconnected` | `PingInvestigator`, then `ProxmoxInvestigator` -> `Disconnected` |
| State machine (`Status.java`, hypervisor independent) | `Up` + `AgentDisconnected` -> `Alert` | same |
| CloudStack HA | skipped explicitly in `HighAvailabilityManagerImpl` | to be added (see section 6) |

## 5a. Ping cadence, timeouts and reconnection

**Code:** none of it is in the plugin: CloudStack's agent manager (`DirectAgentAttache`, `AgentManagerImpl.MonitorTask`,
`ClusteredAgentManagerImpl.scanDirectAgentToLoad`) schedules every direct-connect host the same way, including VMware.
The plugin only supplies `getCurrentStatus()` (one API call per ping) and the resource rebuild.

| Setting | Default | Effect |
|---|---|---|
| `ping.interval` | 60 s | How often each host is pinged (one Proxmox API call: list the VMs on the node) |
| `ping.timeout` | 2.5 | Multiplier: no good ping for `ping.interval` x `ping.timeout` marks the host lost |
| `host.ping.retry.count` | 0 | Retries inside one ping when the status call fails |
| `direct.agent.scan.interval` | 90 s | How often lost hosts are reloaded and reconnected (needs a restart to change) |
| `alert.wait` | 1800 s | Time since the last good ping after which a `Disconnected` host, or one with no investigator verdict, becomes `Alert` |

- A failed ping does not disconnect the host itself. The monitor task (every `ping.interval`) finds hosts without a good
  ping inside the timeout window and starts the investigation (section 5); a host that was `Up` ends in `Alert`.
- Reconnection is done by a timer thread ("ClusteredAgentManager Timer") that runs `scanDirectAgentToLoad()` every
  `direct.agent.scan.interval` (the first run 5 s after startup). It first releases hosts whose last ping is older than the
  ping timeout, then picks direct-connect hosts that have no owner and a stale last ping (at most `direct.agent.load.size`
  = 16 per round), rebuilds each resource from the stored host details (`DiscovererBase.reloadResource`), runs
  `initialize()` and starts a new agent link. A node that is still down fails `initialize()` and is retried at the next
  scan. A recovered node is `Up` again within about one scan interval plus the time the node needs to answer API calls
  (random phase, so 0-90 s plus boot; 45 s was measured in the lab).
- Caveat: an API call waits up to `proxmox.api.connect.timeout` per address, so if every node address times out (instead
  of refusing) one ping can take about that many seconds x the number of addresses. With a short `ping.interval` (10 s in
  the lab) pings then run late; lower the connect timeout if that happens.

### Plugin settings

Global settings, like XenServer's `xapiwait` and VMware's `vmware.vcenter.session.timeout` (those plugins have no TLS
setting; they accept any server certificate). They are dynamic, and are read
when a host (re)connects, so a new value reaches a host the next time it connects.

| Setting | Default | Effect |
|---|---|---|
| `proxmox.api.connect.timeout` | 5 s | Wait for a connection to one node's API before trying the next node |
| `proxmox.api.read.timeout` | 15 s | Wait for an API answer once connected |
| `proxmox.api.verify.tls` | false | Verify the Proxmox API certificate; when on, the signing CA must be in the management server's Java truststore |

## 6. HA stance

- Proxmox HA restarts VMs after a node failure. CloudStack must not compete.
- The investigator never reports `Down`, and `Down` is what makes CloudStack schedule VM restarts. It also reports
  Proxmox VMs as alive.
- **Done (Phase 5):** the explicit skips VMware and Hyper-V have in `HighAvailabilityManagerImpl` (host `Down` handling, `scheduleRestart`,
  the second skip further down) and in the out-of-band power-off restart in `VirtualMachineManagerImpl` now include Proxmox, so there is
  exactly one HA authority, Proxmox.

For comparison, XenServer uses CloudStack-driven HA (storage heartbeat, investigator, fencing) and VMware relies on the
hypervisor's HA with explicit skips in CloudStack. Proxmox follows the VMware model.

**HA is per VM in Proxmox**, not a cluster setting as in VMware: a VM is protected only if it is an HA resource. Two ways to get there (design:
design-phase5-migration-ha.md, 4.3; implemented in Phase 5, not yet run on the lab):
- **Default (global setting `proxmox.ha.manage` off):** the HA flag of the compute offering has **no effect** on Proxmox VMs. The Proxmox administrator
  makes VMs HA resources in Proxmox. CloudStack never restarts them itself and follows them when Proxmox moves or restarts them. No extra privilege.
- **`proxmox.ha.manage` on:** CloudStack creates the Proxmox HA resource for a VM whose offering has HA enabled when it starts (and removes the one it
  created when the flag is off). This needs **extra permissions for the token** (`Sys.Audit` and `Sys.Console` on `/`; `Sys.Console` is node console
  access): see requirements.md, "Permissions for HA management from CloudStack".

## 7. Commands the resource answers

Handled: `ReadyCommand`, `CheckHealthCommand`, `PingTestCommand`, `CheckNetworkCommand`, `MaintainCommand`,
`GetHostStatsCommand`, `UpdateHostPasswordCommand`, and for primary storage `CreateStoragePoolCommand`,
`ModifyStoragePoolCommand`, `DeleteStoragePoolCommand`, `GetStorageStatsCommand`, and for VMs (section 10) `CopyCommand`,
`DeleteCommand`, `StartCommand`, `StopCommand`, `RebootCommand`, for ISOs and blank volumes (section 13) `AttachCommand` and `DettachCommand` (ISO disks only) and
`CreateObjectCommand` (root volumes only), and for migration (section 12) `PrepareForMigrationCommand`,
`MigrateCommand`, `CheckVirtualMachineCommand`. Every other command is answered with an "unsupported" result, so missing
functionality fails loudly instead of being accepted silently.

## 8. Changing the token of added hosts

**Code:** `ManagementServerImpl` (`updateHostPassword`), `ResourceManagerImpl` (`updateClusterPassword`, failure message),
`ProxmoxResource` (`UpdateHostPasswordCommand`, `describeTokenFailure`)

- CloudStack's `updateHostPassword` stores a new username/password for a host, or for every host of a cluster when given a
  cluster id. It only accepted KVM and XenServer; Proxmox is added to that list.
- **A Proxmox token belongs to the whole Proxmox cluster**, but CloudStack keeps one copy per host, so a single-host update
  would leave the other hosts holding a revoked secret. A single-host update is therefore refused for Proxmox, with a message
  asking for the cluster id, as `ManagementServerImpl` already does for XenServer pools.
- The operation requires the username to equal the stored one, so it rotates the **secret** of a token id. A different token id
  or user needs the hosts to be removed and added again.
- With `update_passwd_on_host=true` CloudStack also sends `UpdateHostPasswordCommand` to each host's resource. For KVM that
  changes the root password on the host. For Proxmox nothing can be changed on the node, so the resource instead checks the
  new token with Proxmox and, if accepted, switches the running connection to it (a rejected token is reported and the old
  one stays in use). Without the flag the new token is used from the next reconnect.
- CloudStack stores the new value for all hosts **before** the per-host step, so a failed call leaves the new (possibly wrong)
  value stored; the call has to be repeated with the correct one.
- In the one supported rotation (same token id) Proxmox forces the old token to be deleted before the new secret exists, so the
  hosts are already disconnected when CloudStack is updated. The normal call is therefore the one **without**
  `update_passwd_on_host` (store, then the hosts reconnect with it); the flag only helps while the old token is still valid.
- The stored value is not validated. **Decision:** this is how CloudStack core already behaves for every hypervisor, so it is left
  as it is (changing core behaviour would widen the scope of the pull request) and documented in requirements.md. It is listed
  in [upstream-notes.md](upstream-notes.md) as something to raise with the project separately.
- **Failure messages carry the reason the host gave.** `ResourceManagerImpl` used to reply with a fixed sentence whatever
  went wrong; it now appends the host's reason, says when the host is not connected, and says that the new credentials were
  already saved. The Proxmox resource turns a 401 into "Proxmox rejected the API token ..." and a 403 into "does not have the
  permissions CloudStack needs". This applies to every hypervisor that answers `UpdateHostPasswordCommand` with details.
- The password is stored encrypted when database encryption is on, like at add time.

## 9. Primary storage (NFS, cluster scope)

**Code:** `ProxmoxResource` (storage handlers), `ProxmoxStorageNames`, `ProxmoxApiClient` (storage calls),
`StoragePoolMonitor` (core, one line). The design and the reasoning are in [design-phase2-storage.md](design-phase2-storage.md).

- A CloudStack NFS pool becomes **one cluster-wide Proxmox storage entry** named `cs-<pool uuid>` (hyphens kept; Proxmox refuses an id that starts with a digit), mounted by
  every node at `/mnt/<pool uuid>` (the place the KVM agent mounts it), with `content=images`.
- `CreateStoragePoolCommand` does nothing, as on KVM. The entry is created by the first `ModifyStoragePoolCommand` (it is the one
  that carries the pool's mount options, `nfsmountopts`); the commands for the other hosts find the entry, and a creation race
  between two hosts is tolerated.
- An existing entry for the same share is reused (a trailing `/` on the export is ignored). One that points at a different
  server or export is **refused** with a message and left untouched.
- Each host's command waits (up to 15 x 2 s) until its node reports the storage active, then answers with the node's capacity and
  available bytes and the mount point. A node that never mounts the share answers with an explanation, so one host failing does
  not hide the others.
- Taking one host out of a pool (`ModifyStoragePoolCommand` with add=false) leaves the entry alone: it is cluster-wide.
  `DeleteStoragePoolCommand` removes the entry and can be repeated by every host without error. **Proxmox does not unmount the
  share when an entry is removed** (nor does removing a storage in Proxmox itself), so `/mnt/<pool uuid>` stays mounted on the
  nodes, unlike KVM where removing the pool unmounts it, until `umount /mnt/<pool uuid>` is run on each node. This is left as it
  is on purpose: Proxmox has no API to unmount a storage, so the plugin would need root access to the nodes, and it would do
  more than Proxmox does itself. Re-adding the same pool reuses the mount.
- **Adding the same share again.** CloudStack derives an NFS pool's uuid from the server and path as typed
  (`nameUUIDFromBytes(host + path)`), so the same share always gets the same uuid, hence the same entry id and mount point. Proxmox
  mounts a storage only if nothing is mounted from that `server:export` at that mount point (`nfs_is_mounted`), so a mount left
  from an earlier pool is reused. A different spelling (a hostname instead of an IP, a trailing slash) gives a new uuid and a second
  mount of the same share. Because the leftover mount is reused as it is, **mount options given later do not apply until the
  share is unmounted** from the nodes.
- Capacity statistics come from the node's view of the entry. Pool types other than NFS are refused.
- A host that (re)connects gets its cluster's pools re-checked, because Proxmox is on `StoragePoolMonitor`'s hypervisor list.
- **NFS mount options cannot be set on a Proxmox pool**: core allows `nfsmountopts` for KVM and the simulator only, so the add or update
  is refused for Proxmox (the resource would pass them on if they were ever present). Left out on purpose; see the design.
- Not covered yet: volumes, templates, zone-wide pools (design-phase2-storage.md, sections 1 and 7).

## 10. Deploying and running a VM (Phase 3a, implemented, not yet tested in the lab)

The design and the lab results behind it are in design-phase3-vm-deployment.md and test-log.md (spikes S9-0 to S9-10). What the
resource does for each command:

| CloudStack sends | What the plugin does | Answer |
|---|---|---|
| `CopyCommand`, template on secondary to primary | Creates (once) a read-only `nfs` entry `css-<uuid>` for the whole secondary store (`options=vers=<n>,ro`, mounted at `/mnt/css-<uuid>`: the only mount), then (once per account) a `dir` entry `cst-<uuid>` on that mount with `content-dirs images=template/tmpl/<account>`, and waits until both are active on the node. **Nothing is copied.** Names the template `cst-<uuid>:<template id>/<file>`. Both ids are derived from server, export and folder, so every command finds the same entries, and every template of an account shares one | `CopyCmdAnswer` with the template path, size and format `QCOW2` |
| `CopyCommand`, template on primary to root volume | Creates the VM (`POST /nodes/<node>/qemu`, `name` = the instance name, `scsi0=<pool entry>:0,import-from=<template volume>,format=qcow2`), waits for the task, grows the disk if the root disk is bigger than the template, reads the disk path back | `CopyCmdAnswer` with path `<vmid>/vm-<vmid>-disk-0.qcow2`, size and format. The VM id in the path is how later commands find the VM |
| `StartCommand` | Finds the VM by the VM id in the root volume path. If it is on another node, moves it to this host's node first (offline move, config only). Sets name, CPU, memory (ballooning off), `ostype`, `scsihw`, boot order and the NICs, then starts it and waits | `StartAnswer` |
| `StopCommand` | Finds the VM by name anywhere in the cluster. Not found or already stopped is success. Otherwise a guest shutdown with a 60 s timeout and forced stop afterwards, or a hard stop when CloudStack asks for a forced stop | `StopAnswer` |
| `RebootCommand` | Reboots the VM wherever it is | `RebootAnswer` |
| `DeleteCommand`, root volume | Stops the VM if it is running and destroys it with `purge`; the disk it owns goes with it. A VM that is already gone is success | `Answer` |
| `DeleteCommand`, template on primary | Nothing: the entries are shared by the templates of the account and the store and are kept | `Answer` |

Details that are easy to get wrong:

- **The VM is created at root-volume time, on whichever node got the command.** CloudStack picks a random host of the pool for the volume
  commands, not the host the VM will run on, hence the offline move at start.
- **CPU:** `sockets=1`, `cores` = the offering's CPU count, and `cpuunits` (Proxmox's CPU weight) = cores x speed as a share of the node's
  cores x MHz, on a scale of 1 to 10000, the same idea as the shares the KVM plugin sets. Left at Proxmox's default (100) when the offering has no
  speed. A CPU limit ("limit CPU use") is not mapped yet. The weight is relative, so Proxmox VMs not created by CloudStack, which keep 100, get
  very little CPU next to CloudStack's VMs when the node is busy.
- **A NIC's bridge is the zone's KVM traffic label** (the lab: `vmbr1`); the VLAN is the number in the network's broadcast URI. A NIC
  without a label fails the start with a message that says so.
- **The guest OS mapping** is read from `guest_os_hypervisor` for `Proxmox` (`ostype`), `other` without a row (see the design, 4.8).
- **`proxmox.task.timeout`** (default 600 s) is how long a task (creating from a template, starting, stopping) is waited for. After it
  the command fails but the Proxmox task is not cancelled.
- **A failed creation removes what it left behind**, except a VM id that turned out to belong to someone else's VM; the id is not
  reserved by Proxmox, so a create that fails with "already exists" is retried with a new id (3 attempts).
- Token privileges needed on top of storage: see design-phase3-vm-deployment.md, 4.9 (`PVEVMAdmin`, `PVESDNUser` on
  `/sdn/zones/localnetwork`).
- **UI:** the register and upload template form lists the formats per hypervisor in `RegisterOrUploadTemplate.vue`; Proxmox offers `QCOW2`
  only (found on the lab: the list was empty). The UI needs a rebuild or the dev server to show it.
- Not yet handled (answered as unsupported): data volumes, attach and detach, resize, ISO, snapshots, console, live migration, VM stats.

## 11. Console (Phase 4, works on the lab)

Design: design-phase4-console.md. For a running Proxmox VM CloudStack asks the VM's host (`GetVmWebsocketConsoleCommand`). The plugin finds the VM on its
current node, calls `vncproxy` with `websocket=1` as the API token, and answers with the node's `wss://` URL, the `Authorization` header of the same token
(the ticket is bound to it) and the VNC password. The console proxy VM opens the websocket with that header, **logs in to the VM's VNC server itself with the
password** (the browser's noVNC cannot on a page served over plain HTTP, and the password stays out of the URL), and relays the rest to the browser's noVNC.

- A console is single use: a reload of the console page, or a dropped connection, needs "View console" again (the ticket and the password are good for one
  connection and seconds).
- The console proxy VM must reach port 8006 of the node and runs the patched code (see the design, section 11; build the `client` module too).
- A VM that is not running, or that Proxmox does not know, has no console; the reason is in the management server log.
- The token needs `VM.Console` (in `PVEVMAdmin`, so already granted by the roles of requirements.md).
- The API token secret is passed to the console proxy VM inside the encrypted console token.
- Only the first ten frames of each direction are logged by the console proxy (their sizes), to make a stuck handshake visible.

## 12. Migration, host maintenance and HA (Phase 5, implemented; unit tested, not yet run on the lab)

Design and the reasons: design-phase5-migration-ha.md. What happens:

- **Live migration** (`migrateVirtualMachine`, and the migration CloudStack does for host maintenance) is allowed for Proxmox (core: the two lists in
  `ManagementServerImpl` and `UserVmManagerImpl`). Only the destination host is chosen; the storage is shared (NFS), so no storage motion.
  - `PrepareForMigrationCommand` (destination): fails, before CloudStack changes anything, when the destination node is not online, the VM is unknown or already
    on that node, a NIC's bridge is missing or not active on the destination (and VLAN-aware, when the NIC has a VLAN tag), or Proxmox's own check
    (`GET .../migrate`) reports a reason: the node is not allowed (a non-empty reason in `not_allowed_nodes`; an empty entry means nothing), a local resource, or an
    attached disk that is not on shared storage. Bridges are not created on the destination: keep them identical on all nodes.
  - `MigrateCommand` (source): finds the node from the destination address, calls Proxmox with `online=1`, waits for the task for `proxmox.migration.timeout`
    (default 3600 s) and then until the VM is really on the destination and running, because for an HA VM the call only queues the move. A VM already on
    the destination is a success.
  - `CheckVirtualMachineCommand` (destination): `PowerOn` only when the VM is running on this node.
- **Stop, reboot, start, delete wait for the real state of the VM.** For a VM under Proxmox HA the stop, start and migrate calls end their task at once and
  Proxmox does the work later (lab L4: up to about 75 s for a stop). The plugin polls the VM's state (every 2 s, up to `proxmox.task.timeout`, for a move
  `proxmox.migration.timeout`) and fails with a clear message if it never gets there. Starting a VM that is on another node first moves it to the deployment
  node and waits until it is there.
- **A forced stop aborts a shutdown that is still running.** The stop is sent with `overrule-shutdown`, because a graceful shutdown that was started outside CloudStack (the Proxmox UI, `qm shutdown`, HA) holds the VM's lock and a plain stop fails with "can't lock file ... got timeout" (lab 2026-10-21). Proxmox refuses the option for a VM under HA, so on an HTTP 400 the stop is repeated without it.
- **A stop always acts on the node the VM really is on**, even right after Proxmox HA moved it (CloudStack learns of the new host within one `ping.interval`).
  One exception: after a *failed* migration CloudStack sends a plain stop to the destination, while the VM is still running on the source. The destination
  remembers (in memory) that it was prepared for that VM; a stop or reboot that arrives there for a VM running on another node is answered with success and
  not carried out, once. The memory ends when `CheckVirtualMachineCommand` confirms the VM, when the preparation is rolled back, or after
  `proxmox.migration.timeout` plus 5 minutes.
- **HA from the offering (only with the global setting `proxmox.ha.manage`, default off).** When on: after a VM starts, if its compute offering has HA enabled
  the plugin makes it a Proxmox HA resource (`vm:<id>`, state `started`, comment `CloudStack`); if the offering has no HA and the VM has a resource with
  the comment `CloudStack`, the resource is removed. A resource without that comment (made by an administrator) is never changed. A change of the offering's
  flag therefore takes effect at the next start. If the token lacks `Sys.Audit` and `Sys.Console` on `/`, the start of a VM that should be protected fails with
  a message naming them (a VM without the flag starts anyway). When off, the flag has no effect on Proxmox. Deleting a VM uses `purge=1`, which also removes its
  HA resource. A stop from CloudStack leaves the HA resource requesting `stopped`, so Proxmox does not restart a VM that CloudStack stopped.
- **Host maintenance**: CloudStack migrates every VM away (needs the migration above). Proxmox's own HA maintenance mode has no REST endpoint (lab L8), so the
  plugin does not set it; if Proxmox HA must also avoid the node, run `ha-manager crm-command node-maintenance enable <node>` on a node, and `disable` after.
- **A node failure** is handled by Proxmox HA for the VMs that are HA resources; CloudStack does not restart anything and follows the VM to its new node.

## 13. ISOs (Phase 6, implemented; unit tested, not yet run on the lab)

Design and the lab results behind it: design-phase6-iso.md.

- **An ISO is a Proxmox volume `csi-<hash>:iso/<file>.iso`.** Proxmox allows no folder below `iso/` and CloudStack keeps each ISO in its own folder
  (`template/tmpl/<account>/<id>/<file>`), so the first time an ISO is used the plugin adds a read-only, shared directory entry for **that ISO's folder** on the mount of the
  secondary store (the `css-` NFS entry made for templates). The entry is kept after use and shared by every VM that uses the ISO; there is one per different ISO ever attached,
  not per VM or account. Only `.iso` and `.img` files are accepted, and only ISOs on an NFS secondary store.
- **CD-ROM drives.** A VM has up to four, `ide2`, `ide3`, `ide0`, `ide1` for CloudStack's slots 3, 4, 5, 6 (slot 3 is `ide2`, as everywhere in Proxmox). The host tells core it has four
  (host detail `host.cdrom.max.count`, saved when the host connects), so `vm.iso.max.count` can be raised up to 4 per cluster (default 1).
- **At start**, CloudStack sends every slot, filled or empty; the plugin makes a drive for each (`<volume>,media=cdrom`, or `none,media=cdrom`) and empties a drive that holds an ISO
  detached since the last start. When the ISO in the first slot is bootable (core sets the boot loader to CD) the boot order is `ide2;scsi0`, otherwise `scsi0`.
- **Attach and detach** work on a running VM only for a slot whose drive exists in the **running** configuration (`config?current=1`), because Proxmox keeps a drive that is added to a
  running VM pending until the next restart (and still answers 200). Otherwise the command fails with "stop and start the instance". Attach sets the drive to the ISO; detach ejects it
  and keeps the drive; detaching from an empty drive or one that holds another ISO is a success without a change. The disc changes at once (lab: QEMU opens and closes the file).
- **Deploy from an ISO.** The root volume is created blank by `CreateObjectCommand`: the same VM as for a template, with an empty `scsi0` of the volume's size rounded up to whole GiB, and the
  volume path in the usual form (`<vmid>/vm-<vmid>-disk-0.qcow2`). Only root volumes; a data volume is "unsupported" until data volumes exist.
- **Guests without virtio drivers.** The VM detail `nicAdapter` (`virtio`, `e1000`, `e1000e`, `rtl8139`, `vmxnet3`) sets the NIC model; an unknown value fails the start. The detail
  `rootDiskController` sets the drive of the root disk: `scsi` (the default, also `osdefault` and `virtio-scsi`) is `scsi0` on the virtio-scsi controller, `virtio` is `virtio0`,
  `sata` is `sata0`; `ide` and anything else fail the start with a message (the IDE drives are the CD-ROM drives). The disk is created on `scsi0`; at start it is moved in one call that
  deletes the old drive and sets the new one to the same volume (lab I9), and the boot order names the new drive. A later change of the detail moves it back or elsewhere at the next start.
  The details are set on the instance (Settings tab, while it is stopped) or on the template (then every instance from it gets them; the right place for an old guest such as CentOS 5,
  which has no virtio-scsi driver and panics with "Volume group VolGroup00 not found"). The names are case sensitive: `rootDiskController`, `nicAdapter`. The Settings dropdown offers
  them for Proxmox since the list of suggestions was extended in core (it is per hypervisor); a name can also be typed in.
- **Migration** of a VM with an ISO attached is not blocked (the entry is shared; lab I6).

## 14. Parallel deployments (2026-10-20)

CloudStack's agent sends commands that are marked "execute in sequence" to a host one after the other. `KVMGuru` marks every copy to a primary storage that way, for every hypervisor, because
CloudStack asks all hypervisor gurus about every command. For Proxmox that queued the root volumes of VMs deployed at the same time (a root volume is a `qmcreate` task, which Proxmox runs
asynchronously and in parallel). `ProxmoxHypervisorGuru` therefore takes the flag off the copy commands of Proxmox, so deployments run in parallel. Because VMs created at once can be
given the same free VM id (Proxmox does not reserve it), the creation retries a taken id up to 10 times with a growing pause (it was 3 times without a pause).

## 15. Data volumes (Phase 7, implemented; unit tested, not yet run on the lab)

Design and the lab results behind it: design-phase7-data-volumes.md.

- **A data volume is a disk image owned by the account's "holder" vmid, `900000000 + account id`** (account 2 is `900000002`), named after the CloudStack volume:
  `<pool entry>:900000002/vm-900000002-<volume uuid>.qcow2`. The files of an account are together in `images/<holder>/` on the primary storage. The image belongs to no VM, so it can
  be attached to any, **deleting or expunging an instance never removes its data volumes** (a VM destroy removes only the disks it owns), and a detach leaves no `unusedN` entry (Proxmox only
  registers one for a disk the VM owns; removing one would delete the file).
- **The vmids 900000001 to 999999999 are reserved for CloudStack.** Never create a VM with such an id and never set the cluster's `next-id` range there: destroying a VM with an account's holder id
  (with the "destroy unreferenced disks" option of the Proxmox UI) would remove that account's volumes. The plugin refuses to create a VM if Proxmox offers an id in that range, and an account id
  above 99999999 cannot have data volumes.
- **Create** (`CreateObjectCommand`): CloudStack creates the disk at the first attach. The plugin allocates the image with the size rounded up to whole GiB (thin qcow2) and answers with its path; a repeated
  command answers with the image that exists.
- **Attach** (`AttachCommand`, DATADISK): the drive is `<controller><device id>`. The controller is the instance detail `dataDiskController` (`scsi`, `virtio`, `sata`), by default the family of the root disk
  (so a guest that needed `sata` for its root disk gets `sata` data disks); device ids run 1 to 30 on `scsi`, 1 to 15 on `virtio`, 1 to 5 on `sata`. The drive gets the disk offering's cache mode and
  iops and bandwidth limits (`iops_rd`, `iops_wr`, `mbps_rd`, `mbps_wr`) and bursts (the offering's "max" values and their length in seconds: `iops_rd_max`, `mbps_wr_max`, `mbps_rd_max_length`, ...; a burst is passed only with its base limit and when it is not below it). CloudStack's bandwidth is in bytes per second and is passed to Proxmox as megabytes per second with six decimals. SCSI and VirtIO drives are hot-plugged into a running instance; **a SATA drive cannot be**: Proxmox would keep it pending, so the plugin takes the
  change back and fails with a message that says to **stop the instance, attach the volume while it is stopped and start it again** (restarting alone does not help: the attach is refused again while it runs). A drive that holds another disk fails; the same volume on the drive is a success.
- **Detach** (`DettachCommand`): removes the drive that holds the volume (live on SCSI and VirtIO; a SATA drive cannot be unplugged from a running instance, the answer says to stop it first). The image stays where it is.
- **At start** every data volume of the start command becomes a drive, and a drive that holds a volume that is no longer in the command (detached since the last start) is deleted (in one `delete` list with a moved root disk).
  The disk offering limits and cache mode are also applied to the **root disk** when it does not have them yet.
- **Resize** (`ResizeVolumeCommand`): grows the disk that holds the volume in the instance's configuration, running or stopped, to whole GiB. **A volume that no instance has attached cannot be resized** (Proxmox has no
  such operation: attach it first), and a **disk never shrinks**, whatever `shrinkok` says.
- **Delete** (`DeleteCommand`): a data volume (by its type, or a path in the reserved range) deletes its disk image and never a VM; an image that is gone is a success. CloudStack only deletes detached volumes; Proxmox itself
  would delete an image that a VM still refers to, so nothing relies on it.
- **Answer types:** core casts the reply of a volume attach to an `AttachAnswer` (a failure too, which carries the message) and expects a `CreateObjectAnswer`, `ResizeVolumeAnswer` and `GetVolumeStatsAnswer` for
  the other commands; a plain answer fails the attach with a `ClassCastException` (lab 2026-10-21). Attaching an ISO is different: core accepts a plain answer there.
- **Statistics** (`GetVolumeStatsCommand`): size and used space of each image.
- **Not supported yet** (answered with a reason): copying a volume to or from the secondary store (upload, download, volume snapshots, create from snapshot or from a data disk template), moving a volume to another
  primary storage, `CheckVolumeCommand`.

## 16. The control bridge at host add (Phase 8a, first piece; implemented, unit tested, not yet run on the lab)

When "add host" finds a node that is not added yet, the plugin looks at the node's network configuration (`GET /nodes/<node>/network`, read only) for the interface named by the setting `proxmox.control.bridge`
(default `cloud0`). What happens next:

- **It exists and is a bridge (Linux or Open vSwitch):** nothing is changed and no privilege beyond reading is used, whoever made it. If its address is not the link-local gateway with the prefix of the
  link-local network (`169.254.0.1/16`, from `NetUtils`), the host is still added and a warning says that system VMs start but the troubleshooting login from the node will not work.
- **It exists but is not a bridge:** the host is not added: "Proxmox node <node> has an interface named '<name>' that is not a bridge. Choose another name in the setting proxmox.control.bridge, or remove or rename that interface."
- **It is missing and the node has pending network changes** (Proxmox reports `changes` next to the interface list, `/etc/network/interfaces.new`): the host is not added, and nothing is applied, so that somebody else's
  half-made change is never put live: "... has pending network changes ..., apply or discard them in the Proxmox web interface, or create the bridge by hand ..., and add the host again."
- **It is missing:** the plugin saves a Linux bridge without ports and with that address (`POST /nodes/<node>/network`: `iface`, `type=bridge`, `cidr`, `autostart=1`), then applies the configuration (`PUT`, a
  `srvreload` task that is waited for, `proxmox.task.timeout`). Both need `Sys.Modify` on `/nodes/<node>`. A refusal (HTTP 403) fails the add with the message that names the node and the bridge, and gives the
  `pveum role add CloudStackNet --privs Sys.Modify` and `pveum acl modify /nodes/<node> --users <user> --tokens '<token id>' --roles CloudStackNet --propagate 0` commands (the user and the token both need it), or says to
  create the bridge by hand on every node (requirements.md, "The link-local control bridge") and add the host again. Another Proxmox error fails the add with Proxmox's text and the same manual way.
  If the apply fails, the saved change is discarded again (`DELETE /nodes/<node>/network`), and the message says whether that worked.
- **A token that cannot even read the network configuration** (no `Sys.Audit`) fails the add and the message says so.
- **The name is not usable** (a Linux interface name: a letter, then up to 14 letters, digits or underscores): refused before any call, naming the setting.
- Only nodes that are added are looked at (nodes already added are skipped as before), and one failing node fails the whole add, naming that node; bridges already created on earlier nodes stay and are found
  on the next try. The plugin never changes the node's network again after the host is added.
- **Proxmox rewrites the whole `/etc/network/interfaces`** when it applies a change made through the API (comments and layout are lost, the settings are kept; lab 2026-10-21). Creating the bridge by hand
  only appends lines.

Not done yet (it comes with the system VM start, 8a): a check at a later connect with a warning, and the refusal to start a system VM or router on a node that has lost the bridge.

## 17. System VMs and routers: start, and the commands through the guest agent (Phase 8a, implemented; unit tested, not yet run on the lab)

**Start** (`StartCommand` of a VM whose type is not a user instance: secondary storage VM, console proxy, router, internal load balancer VM ...):

- The VM is created and started as any VM, with **`agent=1`** added to its options, and its *control* NIC (the NIC core marks as type Control, which has no traffic label) on the node's control bridge (`proxmox.control.bridge`, section 16);
  the other NICs use their traffic labels as for user VMs. The control NIC is found by its type, not its number (it is `eth0` on an SSVM, CPVM or VPC router and `eth1` on a router of an isolated network).
- Before anything is changed the node must have that bridge: otherwise the start fails with "Proxmox node <node> has no control bridge '<name>' ... Create it as described in the Proxmox plugin requirements".
- After Proxmox reports the VM running, the plugin (class `ProxmoxSystemVms`) does what the KVM host does over SSH, through the guest agent API:
  1. polls `agent/ping` (every 2 s, up to 300 s) until the agent answers. The system VM's own init waits only 60 s for its boot arguments once its agent runs, so the write follows the first answer at once;
  2. writes the VM's boot arguments (`StartCommand`'s `bootArgs`) to `/var/cache/cloud/cmdline` with `agent/file-write` (up to 10 tries, 3 s apart);
  3. waits (up to 300 s) until `cloud-early-config` of the VM has finished, by running `[ "$(systemctl is-active cloud-early-config)" = active ] && ss -ltn | grep -q ':3922 '` in it (its unit is a oneshot that stays "active" when it is done, and it is what writes `authorized_keys` and sets the network up). Port 3922 alone is not a sign: the template's sshd listens on it from the start (lab 2026-10-22, see the test log);
  4. runs core's `isSystemVMSetup` (for an SSVM or CPVM: the script `patched.sh`, retried for up to 10 minutes).
  A failure at any step fails the start with its reason (the guest agent that never answers names `qemu-guest-agent` and the agent option).
- **Patch files** (what the KVM host copies over SSH at every start). The template has the scripts but **not the Java agent** (`/usr/local/cloud` is empty in it; read in `tools/appliance/systemvmtemplate`), `cloud-early-config` waits only 2 minutes
  for `cloud-scripts.tgz`, and on a router it removes the agent directory at every boot, so the files are needed again each time. Sizes on an installed management server: `agent.zip` 115,550,932 bytes, `cloud-scripts.tgz` 149,002, `patch-sysvms.sh` 5,135.
  They are read from `vms/` on the class path or `/usr/share/cloudstack-common/vms/` on the management server (`ProxmoxPatchFiles`), and delivered as follows:
  - **Before the start of an SSVM or CPVM** (`ProxmoxSystemVms.beforeStart`): one storage entry of type `dir`, named `csp-<uuid of the path>`, path from the setting `proxmox.patch.path` (default `/var/lib/cloudstack-patch`), content `iso`, not shared, is created when it is
    missing (Proxmox makes the directory and its `template/iso` folder on each node when it activates the entry, lab P1); `agent.zip` is uploaded to it with the storage upload API as `cs-patch-<first 16 characters of its SHA-512>.iso`, **padded with zeros to a
    multiple of 2048 bytes** (a CD shows only whole sectors, lab P3), unless the node already has a file of that name and size. Files `cs-patch-*` of other versions are removed; other files in the entry are never touched. A node without room, a token that may not
    upload, or a missing `agent.zip` fails the start with a message that names the node, the privileges (`PVEDatastoreAdmin` on `/storage`) and the path.
  - **The VM is created with an empty CD drive (`ide2`) reserved** (SSVM and CPVM only), because a drive cannot be added to a running VM while its medium can be changed live.
  - **After the boot arguments are written** (the VM only waits 60 s for them, so they come first), the plugin runs in the VM a check of its `cloud-scripts-signature` and of whether `/usr/local/cloud/systemvm` has files. An SSVM or CPVM that has the agent and a signature
    equal to the SHA-512 of the management server's `cloud-scripts.tgz` (a restart) gets nothing. Otherwise: the payload file is put into the CD drive, the guest waits for the whole padded size to show in `/sys/block/sr0/size`, copies exactly the real number of bytes
    with `head -c <size> /dev/sr0` to `/var/cache/cloud/agent.zip.part`, and the plugin compares its SHA-512 with the expected one (a mismatch fails the start: "the copy of agent.zip ... is damaged") before it is moved into place; the CD is emptied again in every case.
  - **A router, and every VM that needs patching, then gets `cloud-scripts.tgz` and `patch-sysvms.sh`** through the API (base64 pieces appended to `<file>.part` and moved into place when complete), `cloud-scripts.tgz` last, because that is the file `cloud-early-config` waits for and
    `agent.zip` has to be in place by then.
  - The copy of 110 MiB takes seconds (upload 1.7 s, guest read 3 s in the lab), not the 12 to 34 minutes of copying through the API in pieces.
- **Login keys for troubleshooting** (setting `proxmox.systemvm.ssh.key`, empty by default; requirements.md, "Logging in to a system VM from a node"): after the VM has finished configuring itself (`cloud-early-config` has finished, so it has already written `authorized_keys` from the boot arguments)
  and before the setup check, the plugin adds the public key(s) of the setting to the VM's `/root/.ssh/authorized_keys` through the guest agent (the directory and file are created with modes 700 and 600; a key already in the file is not added again). Entries are separated by `|` or a line break;
  an entry that is not a public key line is skipped with a warning, and a failure to add the keys is logged: neither fails the start. The keys are added at every start by CloudStack, since the VM rewrites the file at its own boot.

**Router and system VM commands** (`NetworkElementCommand`s, and core's `VirtualRoutingResource` behind them) are run by `ProxmoxRouterDeployer`, which core calls instead of SSH:

- `executeInVR` runs `/opt/cloud/bin/<script> <args>` in the VM with `agent/exec` (`/bin/sh -c`), polls `agent/exec-status` every 0.3 s until it exits (or the caller's timeout: 10 minutes by default), and answers success when the exit code is 0, with the output (the error output when it printed nothing and failed).
- `createFileInVR` writes the file with `agent/file-write` when it is at most 48,000 bytes, and above that appends it piece by piece (`base64 -d >>` with 48,000 bytes of the file per call, as one call carries at most 64 KiB of input).
- The address core passes (the control address, link-local) is **never connected to**. It is only a key: it maps to the VM's name, learned when the VM is started, from the name every network command carries, and from `CheckSshCommand`; the VM is then found by name in the cluster for every call, so it works on whichever
  node the VM is on after a migration or an HA restart. An address with no VM known fails the call with a message naming the address; a VM Proxmox no longer has fails it naming the VM.
- `CheckSshCommand` (sent after a start, in the same batch as the start) is answered with a guest agent ping, not a connection to port 3922; the ping is repeated every 2 s for up to 120 s (KVM tries to connect for minutes), because a router can leave its agent unanswered for a short while right after it has been configured. A failure after the whole wait is logged with its reason and fails the start of the router (core then stops the rest of the start commands).
- `prepareCommand` only records the address and the VM name for now; the NIC hot-plug that IP association, guest network setup and source NAT need on a running router is 8b and 8c, so those commands are not usable yet.

## 18. Virtual routers: interfaces, traffic counters and reboot (Phase 8b, implemented; unit tested, not yet run on the lab)

A router starts like any other system VM (section 17: guest agent, control NIC on the control bridge, boot arguments, `cloud-scripts.tgz` written at every start, login keys), and core's commands for it run through the guest agent. What 8b adds:

- **Which interface is which.** Core names the interface of a public IP, a guest network or the source NAT by MAC address; the router's scripts need its number inside the guest (`eth0`, `eth1` ...). The plugin reads the VM's configuration (`GET .../config`, the entries `net0`, `net1` ...) and takes the **position of the MAC address** in the order of the entries, as the KVM host does with the position in the domain
  (lab S5 and S6: the guest names the interfaces in the order of the configuration). For a router of an isolated network that is guest `eth0`, control `eth1`, public `eth2`. Done in `ProxmoxRouterDeployer.prepareCommand` (class `ProxmoxRouterNics` parses the configuration):
  - `IpAssocCommand`: every IP gets its interface number; **an IP whose MAC address the router does not have gets an interface hot-plugged** (`PUT config net<next>=virtio=<mac>,bridge=<traffic label of the public network>,tag=<VLAN of the IP>,firewall=0`; the bridge is `IpAddressTO.networkName`, the VLAN comes from its broadcast URI, `untagged` has none), and the plugin waits up to 30 s until the guest shows the interface (`ip -o link`). For every interface it also runs `netusage.sh -a eth<n>` in the router.
    A public IP on the interface the router already has needs no hot-plug; one on another VLAN does (not yet run on the lab; planned for 8c).
  - `IpAssocVpcCommand`: only the interface numbers.
  - `SetupGuestNetworkCommand`: the guest interface must be in the configuration, otherwise the command fails with "Can not find nic with mac ... for VM ...".
  - `SetSourceNatCommand`: the number of the interface of the source NAT IP.
  - After an `IpAssocCommand` (`cleanupCommand`): when the last public IP of an interface is released, **the interface is removed again** (`PUT config delete=net<n>` and `netusage.sh -d eth<n>`), except `eth2`, the default public interface (the same rule as KVM).
- **Traffic counters** (`NetworkUsageCommand`, class `ProxmoxNetworkUsage`): `netusage.sh` (`-g` to read, `-c` to create, `-r` reset, `-a` and `-d` for an interface) in the router, and for a VPC router `vpc_netusage.sh -l <public gateway>`, run in the router through the guest agent; the answer holds the summed sent and received bytes. A script that fails gives zeros, as on KVM.
- **Reboot of a router** (`RebootRouterCommand`): the VM is rebooted through Proxmox, the plugin waits until the router has configured itself again (it uses its saved boot arguments, nothing is copied), then creates the traffic counters. It is not a restart of the VM by CloudStack (stop and start), which does the whole start sequence.
- **Router template.** Core finds the template of a router by the name in a zone-scoped global setting per hypervisor (`router.template.kvm`, `.vmware` ...). Proxmox has its own, **`router.template.proxmox`** (default `SystemVM Template (Proxmox)`; a new key in `VirtualNetworkApplianceManager` and a line in `NetworkHelperImpl`), so a zone with KVM and Proxmox routers keeps one template name for each. A template with that exact name must be registered for the hypervisor Proxmox (requirements.md, "Virtual routers on Proxmox"), for example by setting the value to the name of the template already registered for the secondary storage VM.
- **Router migration.** `NetworkOrchestrator.prepareNicForMigration` treats a Proxmox router like a KVM or VMware one (its hot-plugged interfaces are not in the database), one added condition.

Not done: VPC (tiers, ACLs, private gateways, VPN) and a public IP on another VLAN were not run on the lab (8c); redundant routers and security groups are out of scope.

## 19. VPC and plugging interfaces (Phase 8c, implemented; unit tested, not yet run on the lab)

- **`PlugNicCommand`** (a VPC tier or a private gateway added to a running VPC router, "add network" on an instance, and any other interface added to a VM): `ProxmoxVmHandler.plugNic` finds the VM by name in the cluster, and sets `net<device id>` (the next free number when that key is taken) to `<model>=<mac>,bridge=<bridge of the traffic label>,tag=<VLAN of the network>,firewall=0`, through the same `netOption` as a start. A running VM gets the interface at once when its `hotplug` option includes the network (the Proxmox default,
  lab S6); a stopped VM has it at its next start. The adapter model is the `nicAdapter` detail for an instance and always virtio for a router. An interface with the MAC address the VM already has is a success and nothing is changed, as on KVM. A network without a bridge fails the command with the message about the KVM traffic label; a refusal by Proxmox fails it with Proxmox's text and a hint about the `hotplug` option.
  Not done, unlike KVM: the config drive ISO is not detached and attached again for an instance, and no security group rules (the capability is off).
- **`UnPlugNicCommand`**: the interface with that MAC address is removed (`PUT config delete=net<n>`); a VM that is gone or does not have it is a success. The bridge and its VLAN are never touched (Proxmox makes and removes the VLAN sub-bridge of a node itself).
- **VPC router.** Core lists Proxmox among the hypervisors that support a VPC (`VpcManagerImpl`, one line, upstream note 25). The router is the same system VM as in section 18; its control NIC is `eth0` (type `vpcrouter`), the public NIC `eth1` and the tiers `eth2` and up, and the commands that are specific to it need nothing more than section 18 already does: `IpAssocVpcCommand` (interface numbers by MAC), `SetupGuestNetworkCommand` (the tier's interface must be in the configuration and, since a tier is plugged a moment before, **the plugin now also waits up to 30 s until the guest shows it**), `NetworkUsageCommand` with `vpc_netusage.sh`, and the ACL, route and VPN commands, which are scripts run in the router.
- **A public IP on another VLAN** needs a new public interface: the hot-plug of section 18 (`IpAssocCommand`) does it, on the bridge of the public traffic label with the VLAN of the IP.

Not done: site-to-site VPN and remote access VPN were not run on the lab; redundant VPC routers are out of scope.

## Known gaps and open questions

- A Proxmox node that joins later is not detected automatically; add the same address again to add it.
- HA skips in `HighAvailabilityManagerImpl` / `VirtualMachineManagerImpl` (section 6).
- TLS: only "verify against the Java truststore" or "do not verify" (the default, as for VMware and XenServer). Pinning a
  certificate fingerprint is not supported.
- The token of added hosts can be rotated in place for the whole cluster (same token id, new secret); a single-host update is
  refused, and a different token id needs the hosts to be removed and added again (section 8).
- The API port is one value for the whole cluster; nodes listening on different ports are not supported.
- Token permissions: the lab uses a `root@pam` token. The read-only set for the current features (`PVEAuditor`) is
  documented in requirements.md but still to be verified; the set for VM, storage and network features comes with them.
- Some Proxmox operations (setting raw QEMU `args`) are root-only; whether a root token passes that check is unverified.
- Not yet implemented: system VMs and virtual routers, snapshots, uploading and downloading volumes (see section 10 for what a VM can do now).
- Proxmox's own maintenance state is not set by CloudStack (section 12).

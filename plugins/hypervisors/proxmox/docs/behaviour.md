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

- Each ping lists the VMs on that node only (`/nodes/{node}/qemu`), keyed by VM name (the CloudStack instance name),
  PowerOn or PowerOff. Reporting cluster-wide from every host would make the recorded power host flap.
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
- **Not done yet:** the explicit skips VMware and Hyper-V have in `HighAvailabilityManagerImpl` (host `Down` handling,
  `scheduleRestart`) and in the out-of-band power-off restart in `VirtualMachineManagerImpl`. These must be added before
  VMs are deployed on Proxmox.

For comparison, XenServer uses CloudStack-driven HA (storage heartbeat, investigator, fencing) and VMware relies on the
hypervisor's HA with explicit skips in CloudStack. Proxmox follows the VMware model.

## 7. Commands the resource answers

Handled: `ReadyCommand`, `CheckHealthCommand`, `PingTestCommand`, `CheckNetworkCommand`, `MaintainCommand`,
`GetHostStatsCommand`, `UpdateHostPasswordCommand`, and for primary storage `CreateStoragePoolCommand`,
`ModifyStoragePoolCommand`, `DeleteStoragePoolCommand`, `GetStorageStatsCommand`, and for VMs (section 10) `CopyCommand`,
`DeleteCommand`, `StartCommand`, `StopCommand`, `RebootCommand`. Every other command is answered with an "unsupported" result, so missing
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
- Not yet implemented: data volumes, console, live migration, HA skips, system VMs and virtual routers, snapshots (see section 10 for what a VM can do now).

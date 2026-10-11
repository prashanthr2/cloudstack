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
`GetHostStatsCommand`. Every other command is answered with an "unsupported" result, so missing
functionality fails loudly instead of being accepted silently.

## Known gaps and open questions

- A Proxmox node that joins later is not detected automatically; add the same address again to add it.
- HA skips in `HighAvailabilityManagerImpl` / `VirtualMachineManagerImpl` (section 6).
- TLS: only "verify against the Java truststore" or "do not verify" (the default, as for VMware and XenServer). Pinning a
  certificate fingerprint is not supported.
- The token of added hosts cannot be changed (for example after rotating it); the hosts have to be removed and added again.
- The API port is one value for the whole cluster; nodes listening on different ports are not supported.
- Token permissions: the lab uses a `root@pam` token. The read-only set for the current features (`PVEAuditor`) is
  documented in requirements.md but still to be verified; the set for VM, storage and network features comes with them.
- Some Proxmox operations (setting raw QEMU `args`) are root-only; whether a root token passes that check is unverified.
- Not yet implemented: VM lifecycle, storage (NFS), networking, migration, system VMs and virtual routers, snapshots.

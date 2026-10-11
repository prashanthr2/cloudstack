# Proxmox plugin: behaviour and design decisions

One entry per behaviour: what it does, why, and where it lives. Update this file whenever behaviour changes.
Open questions are collected at the end.

## 1. Discovery (adding a host)

**Code:** `discoverer/ProxmoxServerDiscoverer`, `client/ProxmoxApiClient`

- One add-host call connects to the given address, reads `/cluster/status`, and creates a host for **every online node**.
  This matches how XenServer adds a whole pool from one host. Offline nodes are skipped; there is no automatic refresh,
  so a node added to Proxmox later has to be added to CloudStack separately (known gap, see below).
- Credentials: the username field carries the API token id and the password field the token secret. CloudStack requires
  both fields, and tokens are revocable and can be scoped, which a stored account password cannot (see "Why API tokens").
- The URL is only used to find the API address. The scheme is ignored (the UI forces `http://` on anything typed
  without it) and HTTPS is always used. `verifyTls=false` in the query disables certificate checks for lab use.
- The host GUID is derived from the Proxmox cluster name and the node name, so nodes of different Proxmox clusters
  cannot collide.
- Host details stored per host (all that CloudStack keeps for a host, and what the resource is rebuilt from after a
  management server restart): `username`, `password` (encrypted by the host details DAO when database encryption is on),
  `node`, `nodeip`, `endpoint`, `endpoints`, `verifytls`.

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
- Reconnection: the scan reloads lost hosts from their stored details and starts the resource again. A recovered node is
  `Up` again within roughly one scan interval; a node that is still down is retried at the next scan.
- Caveat: an API call waits up to `proxmox.api.connect.timeout` per address, so if every node address times out (instead
  of refusing) one ping can take about that many seconds x the number of addresses. With a short `ping.interval` (10 s in
  the lab) pings then run late; lower the connect timeout if that happens.

### Plugin settings

Global settings, like XenServer's `xapiwait` and VMware's `vmware.vcenter.session.timeout`. They are dynamic, and are read
when a host (re)connects, so a new value reaches a host the next time it connects.

| Setting | Default | Effect |
|---|---|---|
| `proxmox.api.connect.timeout` | 5 s | Wait for a connection to one node's API before trying the next node |
| `proxmox.api.read.timeout` | 15 s | Wait for an API answer once connected |

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

- A Proxmox node added after the cluster was registered is not picked up automatically.
- HA skips in `HighAvailabilityManagerImpl` / `VirtualMachineManagerImpl` (section 6).
- TLS: only "verify" or "do not verify". Pinning a certificate fingerprint or trusting a custom CA is not supported.
- Token permissions: the lab uses a `root@pam` token. The minimum privilege set is to be worked out once the VM, storage
  and network features exist.
- Some Proxmox operations (setting raw QEMU `args`) are root-only; whether a root token passes that check is unverified.
- Not yet implemented: VM lifecycle, storage (NFS), networking, migration, system VMs and virtual routers, snapshots.

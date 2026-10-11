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
  as an error. The connect timeout is capped at 5 seconds so a dead address does not stall a ping.
- Why: without it every host talks through one node, so losing that node put the healthy nodes into Alert too.
- Hosts added before this existed hold only one address and must be re-added to get the list.

## 5. Failure detection

**Code:** `resource/ProxmoxResource` (`PingTestCommand`, `CheckOnHostCommand`), `ha/ProxmoxInvestigator`

When a host stops answering, CloudStack asks its investigators what happened.

- `PingTestCommand` (used by the built-in ping investigator) is answered truthfully: yes only if the address belongs to a
  node the Proxmox cluster currently reports online. It used to answer yes to everything, so a powered-off node was vouched
  for by the others and stayed `Up`.
- `ProxmoxInvestigator` asks a neighbouring node (`CheckOnHostCommand`) whether the cluster still reports the node online:

  | Cluster says | Host becomes | Why |
  |---|---|---|
  | node offline | `Alert` | Visible at once; never `Down`, see HA stance |
  | node online | `Disconnected` | Node is alive, only CloudStack's link to it is broken |
  | cannot tell | no verdict | CloudStack waits `alert.wait` (30 min default), then `Alert` |

- Without an investigator of its own a dead host stays `Up` for `alert.wait` (1800 s by default), which is the normal
  behaviour for any hypervisor that has none.
- `isVmAlive` always answers "unknown": whether a VM runs is for Proxmox to say, never guessed from the host state.

## 6. HA stance

- Proxmox HA restarts VMs after a node failure. CloudStack must not compete.
- The investigator never reports `Down`, and `Down` is what makes CloudStack schedule VM restarts.
- **Not done yet:** the explicit skips VMware and Hyper-V have in `HighAvailabilityManagerImpl` (host `Down` handling,
  `scheduleRestart`) and in the out-of-band power-off restart in `VirtualMachineManagerImpl`. These must be added before
  VMs are deployed on Proxmox.

For comparison, XenServer uses CloudStack-driven HA (storage heartbeat, investigator, fencing) and VMware relies on the
hypervisor's HA with explicit skips in CloudStack. Proxmox follows the VMware model.

## 7. Commands the resource answers

Handled: `ReadyCommand`, `CheckHealthCommand`, `PingTestCommand`, `CheckNetworkCommand`, `MaintainCommand`,
`GetHostStatsCommand`, `CheckOnHostCommand`. Every other command is answered with an "unsupported" result, so missing
functionality fails loudly instead of being accepted silently.

## Known gaps and open questions

- A Proxmox node added after the cluster was registered is not picked up automatically.
- HA skips in `HighAvailabilityManagerImpl` / `VirtualMachineManagerImpl` (section 6).
- Whether an offline node should be `Alert` straight away (current) or `Disconnected` first, as other hypervisors show it.
- TLS: only "verify" or "do not verify". Pinning a certificate fingerprint or trusting a custom CA is not supported.
- Token permissions: the lab uses a `root@pam` token. The minimum privilege set is to be worked out once the VM, storage
  and network features exist.
- Some Proxmox operations (setting raw QEMU `args`) are root-only; whether a root token passes that check is unverified.
- Not yet implemented: VM lifecycle, storage (NFS), networking, migration, system VMs and virtual routers, snapshots.

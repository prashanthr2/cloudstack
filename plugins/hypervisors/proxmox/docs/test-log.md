# Proxmox plugin: lab test log

What was tested, how, and what the tests found. One section per round; newest last. Dates are when the test ran.

## Lab

- Proxmox VE 8.4.21, three nodes (3 vCPU, ~8 GB each), one cluster, local storage only, no VMs.
  `vmbr0` management (untagged), `vmbr1` VLAN-aware trunk for guest and public traffic.
- CloudStack management server 24.0.0-SNAPSHOT run from source (`jetty:run`) on Ubuntu 24.04, MySQL 8.
- Zone with a KVM host (nightly 24.0.0 agent) for system VMs, Proxmox added as a second cluster.

## Phase 1: registration, discovery, host lifecycle (2026-10-10 / 2026-10-11)

| # | Check | Result |
|---|---|---|
| 1 | `Proxmox` offered when adding a cluster | Pass |
| 2 | One add-host creates one host per node | Pass (3 hosts) |
| 3 | Hosts reach `Up` / `Enabled` | Pass |
| 4 | Real capacity (CPU, speed, memory), node IP, short version | Pass (3 CPU x 2.10 GHz, 7.76 GB) |
| 5 | Node IP is the node's own address | Pass |
| 6 | Stays `Up` over time (ping) | Pass |
| 7 | Host CPU / memory statistics shown | Pass |
| 8 | Hosts reconnect after a management server restart | Pass, after fix 2 below |
| 9a | One node powered off (`pve2`) | Host goes to `Alert` shortly (standard: a host that was `Up` goes to `Alert`) |
| 9b | The node used for the API powered off (`pve1`) | Failed first (all hosts `Alert`, dead node `Up`). After fixes 3-6: `pve1` goes to `Alert` after power-off on the final code (`31b9cef2d1`), other hosts unaffected; hosts show `Disconnected` after a management server restart (standard, see behaviour.md section 5). Investigator verdict `Disconnected` confirmed in the log. |
| 9c | A **disabled** host's node powered off | Host goes to `Disconnected` (no `Alert`), as the state table predicts (`ShutdownRequested`, no investigation) |

### Bugs found by testing, and the fixes

1. **Host row rejected: "Data too long for column 'hypervisor_version'"** - the full `pve-manager/8.4.21/<hash>`
   string is longer than the varchar(32) column. Now the short version is stored.
2. **Hosts stayed `Disconnected` after a restart** - the token was not part of the stored host details, so the resource
   could not be rebuilt. Username and password are now stored with the host.
3. **All hosts went to `Alert` when one node died** - every host used a single API address. Now every node address is
   stored and the client fails over (test: `ProxmoxApiClientFailoverTest`).
4. **A powered-off node stayed `Up`** - `PingTestCommand` was answered "success" for any address, so the other hosts
   vouched for the dead one. Now it checks the cluster's view of node membership.
5. **Dead node still `Up` for 30 minutes** - with no verdict, CloudStack waits `alert.wait` (1800 s). Added
   `ProxmoxInvestigator`. Registration confirmed in the management server log ("Ha Investigators Registry").
6. **`Alert` while running but `Disconnected` after a management server restart** - looked inconsistent. It is how
   CloudStack's host state machine works for every hypervisor (`Up` + `AgentDisconnected` -> `Alert`;
   `Up` + `ManagementServerDown` -> `Disconnected`). The investigator was changed to the VMware-style `Disconnected`
   verdict (never `Down`); the visible state for a host that was `Up` is `Alert` either way. An earlier version of the
   docs wrongly said a lost host would stay `Disconnected` until `alert.wait`; corrected in behaviour.md section 5.

### Lessons that are not code

- The UI prepends `http://` to the host field unless it already contains `http://`, so a full `https://` URL cannot be
  typed; type `host:8006?verifyTls=false` without a scheme.
- A host in `Disconnected` status with resource state `Enabled` has no Remove button; disable it first.
- The upstream project version moved to `24.0.0-SNAPSHOT` while this was in development; the plugin POM and the
  upgrade SQL had to follow (`schema-42300to2400.sql`).
- A build that "passes" can silently use stale artifacts from the local Maven repository if the project version changed;
  verify that the artifacts built carry the expected version.

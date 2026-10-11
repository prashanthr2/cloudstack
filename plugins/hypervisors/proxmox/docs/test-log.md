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
| 10 | Powered-off nodes (enabled, and the disabled one after re-enabling) return by themselves | Pass: back to `Up` about 45 s after the node was started, no manual action |

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
  typed; type the plain address (with `:port` if needed). The scheme is ignored by the plugin. (Early builds also read
  `?verifyTls=false` from the host field; that was replaced by the global setting `proxmox.api.verify.tls`.)
- A host in `Disconnected` status with resource state `Enabled` has no Remove button; disable it first.
- The upstream project version moved to `24.0.0-SNAPSHOT` while this was in development; the plugin POM and the
  upgrade SQL had to follow (`schema-42300to2400.sql`).
- A build that "passes" can silently use stale artifacts from the local Maven repository if the project version changed;
  verify that the artifacts built carry the expected version.

## Changes after Phase 1 that still need a lab check (2026-10-12)

Unit tests pass for all of these; they have not run against the lab yet.

| # | Change | Check on the lab |
|---|---|---|
| a | TLS verification is now the global setting `proxmox.api.verify.tls` (default `false`, as for VMware and XenServer) instead of `?verifyTls=false` in the URL | Add works with the default; set `true` without the CA: PKIX error; import `/etc/pve/pve-root-ca.pem` (path confirmed), restart, set `true`: works |
| b | Host name accepts `address` or `address:port` | Add with the plain address; add with an explicit `:8006` |
| c | The CloudStack cluster records the Proxmox cluster (`proxmox-cluster:<name>`); a node of another Proxmox cluster is refused | Check `cluster.guid` after an add; try a second Proxmox cluster if one exists |
| d | Adding the same address again adds only new nodes | Add again: "already added" message; after a new node joins, add again: only that node appears |
| e | Minimal token permissions (`PVEAuditor`) | **Pass** (2026-10-12): with the role on the token, `cluster/status` 200, node status shows CPU and memory, and the VM list shows a test VM; without the role on the token the same token got 403 / empty results. ACLs of the working setup: role `PVEAuditor` on `/` (propagate) for the user `pveaudit@pam` and for the token `pveaudit@pam!cloudstack` |
| f | Token rotation: `updateHostPassword` accepts Proxmox hosts; with `update_passwd_on_host=true` the running connection switches to the new token | Recreate the token under the same id, run the `cmk` command from requirements.md for the cluster: hosts stay `Up`; with a wrong secret the call fails and hosts stay `Up` on the old token. Also the UI "Change password" on one host, then revoke the old token: the host reconnects within about one scan interval |

### Notes on check a (TLS)

- Importing the Proxmox CA on Ubuntu 24.04 (2026-10-12): copying `/etc/pve/pve-root-ca.pem` to
  `/usr/local/share/ca-certificates/pve-root-ca.crt` and running `update-ca-certificates` printed `1 added, 0 removed` and
  the `ca-certificates-java` hook printed `Adding debian:pve-root-ca.pem`, so the CA reached Java's truststore. The warning
  `rehash: skipping ca-certificates.crt, it does not contain exactly one certificate or CRL` is harmless. The first attempt
  with `keytool ... "$JAVA_HOME/lib/security/cacerts"` failed because `JAVA_HOME` was not set; the documentation was
  changed to not depend on it.


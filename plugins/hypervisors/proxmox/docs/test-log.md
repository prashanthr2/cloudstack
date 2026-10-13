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
| a | TLS verification is now the global setting `proxmox.api.verify.tls` (default `false`, as for VMware and XenServer) instead of `?verifyTls=false` in the URL | Add works with the default; set `true` without the CA: PKIX error; import `/etc/pve/pve-root-ca.pem` (path confirmed), restart, set `true`: works **Pass (2026-10-12): default false works; CA imported with update-ca-certificates, verify true works** |
| b | Host name accepts `address` or `address:port` | Add with the plain address; add with an explicit `:8006` **Pass (2026-10-12)** |
| c | The CloudStack cluster records the Proxmox cluster (`proxmox-cluster:<name>`); a node of another Proxmox cluster is refused | Check `cluster.guid` after an add; try a second Proxmox cluster if one exists **Pass (2026-10-12)** |
| d | Adding the same address again adds only new nodes | Add again: "already added" message; after a new node joins, add again: only that node appears **Pass (2026-10-12)** |
| e | Minimal token permissions (`PVEAuditor`) | **Pass** (2026-10-12): with the role on the token, `cluster/status` 200, node status shows CPU and memory, and the VM list shows a test VM; without the role on the token the same token got 403 / empty results. ACLs of the working setup: role `PVEAuditor` on `/` (propagate) for the user `pveaudit@pam` and for the token `pveaudit@pam!cloudstack` |
| f | Token rotation: `updateHostPassword` accepts Proxmox hosts; with `update_passwd_on_host=true` the running connection switches to the new token | Recreate the token under the same id, run the `cmk` command from requirements.md for the cluster: hosts stay `Up`; with a wrong secret the call fails and hosts stay `Up` on the old token. Also the UI "Change password" on one host, then revoke the old token: the host reconnects within about one scan interval **Pass (2026-10-12)**: rotation works; the single-host and message follow-ups are checks g to k |

### Notes on check a (TLS)

- Importing the Proxmox CA on Ubuntu 24.04 (2026-10-12): copying `/etc/pve/pve-root-ca.pem` to
  `/usr/local/share/ca-certificates/pve-root-ca.crt` and running `update-ca-certificates` printed `1 added, 0 removed` and
  the `ca-certificates-java` hook printed `Adding debian:pve-root-ca.pem`, so the CA reached Java's truststore. The warning
  `rehash: skipping ca-certificates.crt, it does not contain exactly one certificate or CRL` is harmless. The first attempt
  with `keytool ... "$JAVA_HOME/lib/security/cacerts"` failed because `JAVA_HOME` was not set; the documentation was
  changed to not depend on it.

### Notes on check f (token rotation) and the check 0 (settings)

- Check 0 (the three `proxmox.api.*` settings exist with the expected defaults): pass.
- The token was deleted and recreated in Proxmox (same id, new secret). The UI "Change password" on one host updated only that
  host; the other two hosts, still holding the old secret, went to `Alert`. The token belongs to the Proxmox cluster, so the old
  secret is invalid for every node, while CloudStack keeps one copy per host.
- `cmk update hostpassword clusterid=... update_passwd_on_host=true`: the first attempt failed with "CloudStack failed to
  update the password of Host ..." because one host was still in `Alert` (CloudStack's `doUpdateHostPassword` returns false
  for any host that is not attached); the second attempt succeeded once all hosts were `Up`. The new secret is stored for every
  host of the cluster before the per-host step, which is why the other hosts recovered at the next reconnect scan.
- A wrong secret is rejected (the call fails and the connected hosts stay on the old token), but CloudStack has already stored
  the wrong secret for the hosts, so a reconnect would then fail. After a failed update, repeat it with the correct secret. The
  error shown is generic; the reason ("The new API token was not accepted by Proxmox: ...") is in the management server log.
- Follow-up done (not yet checked on the lab): a single-host update is refused for Proxmox, as XenServer does, and the failure
  message now carries the reason the host gave (see the checks below).

## Checks for the token update follow-up (to run on the lab)

| # | Check | Expected |
|---|---|---|
| g | UI "Change password" on one Proxmox host | refused: "Single host update is not supported by Proxmox hypervisors ... informing the Cluster ID" **Pass (2026-10-12)** |
| h | `cmk update hostpassword clusterid=... update_passwd_on_host=true` with a **wrong secret**, hosts `Up` | fails with "Reason given by the host: Proxmox rejected the API token (HTTP 401) ..." and the note that the new credentials were already saved; repeat with the right secret **Pass (2026-10-12): rejected with the Proxmox reason when done with the flag and hosts `Up`; without the flag a wrong secret is stored and reported as success (documented)** |
| i | the same call while one host is `Alert` | fails with "The host is not connected; wait until it is Up and try again" **Pass (2026-10-12)** |
| j | the same call with the correct secret and all hosts `Up` | succeeds, hosts stay `Up` **Pass (2026-10-12)** |
| k | the normal rotation: recreate the token in Proxmox, then `cmk update hostpassword clusterid=... username=... password=<new>` **without** the flag | hosts leave `Alert`/`Disconnected` and are `Up` again within about one reconnect scan **Pass (2026-10-12)** |

Observed on 2026-10-12 after a restart: a wrong secret stored by an earlier failed call left every host `Disconnected` until the
correct secret was stored again (the value is saved before it is checked).

Observed (2026-10-12): a cluster-wide update without `update_passwd_on_host` stored the value and returned success while the
hosts stayed `Up`: a connected host keeps the token it started with in memory and uses the stored one only at the next
reconnect. Check h (wrong secret **with** the flag, hosts `Up`) passes: the call is rejected with the Proxmox reason.

## Phase 1 status (2026-10-12)

Every planned check has passed on the lab: registration, discovery, capacity and statistics, restart, failure and recovery,
failover between node addresses, TLS setting, host name forms, cluster identity, adding again, minimal token permissions, and
the token update. Known limits are in behaviour.md ("Known gaps") and upstream-notes.md.

## Phase 2 (NFS primary storage): spikes and status (2026-10-12)

Spike results on the lab (details in design-phase2-storage.md, section 8):

| # | Result |
|---|---|
| S1 | A pool uuid starting with a letter is accepted as a storage id; one starting with a digit is **refused** ("contains illegal characters"), so the id is `cs-<uuid>`; that 39-character form is still to be checked (S1b) |
| S3 | `--options` passes through to the mount |
| S4 | the status call returns `active`, `avail`, `content`, `enabled`, `shared`, `total`, `type`, `used`, matching the NFS server |
| S6 | `import` is accepted as a content type on an NFS entry |
| S7 | confirmed on the lab: a disk can be allocated for a VM id that has no VM, a VM created afterwards with that id accepts it, and a disk of another id can be attached to a VM too (an earlier "no" was probably an entry without `images` content) |
| S2 | confirmed on the lab: `PVEAuditor` alone cannot create storage (403); `PVEDatastoreAdmin` on `/storage` for the token alone is still refused; for the token **and** its user it works |
| S5 | confirmed on the lab: creating an existing id, also with a different export, fails with "storage ID ... already defined" |
| S1b | confirmed by p1 (`cs-<uuid>` created through the API) |
| S8 | confirmed: the CloudStack pool is mounted at `/mnt/<pool uuid>` (`findmnt -t nfs,nfs4` on a node) |

Implementation status: the storage handlers, the API client calls and the `StoragePoolMonitor` change are written and unit tested
(plugin: 64 tests; `StoragePoolMonitorTest` has a test that fails without the core change). **Not yet run on the lab.** Lab checks:

| # | Check | Expected |
|---|---|---|
| p1 | add a cluster-scope NFS pool from CloudStack | pool `Up` with capacity; an entry named `cs-<pool uuid>` visible on all three nodes in Proxmox, mounted at `/mnt/<uuid>` **Pass (2026-10-12)** |
| p2 | restart the management server | the pool stays attached; hosts keep their storage **Pass (2026-10-12): the pools were attached again after a management server restart** |
| p3 | power a node off and on | the node reconnects and its storage is checked again **Pass (2026-10-12)** |
| p4 | add a pool with a wrong server or an export the nodes may not use | a clear message with the Proxmox reason, no half-created pool **Pass (2026-10-12): the add fails with an error (the message was not recorded)** |
| p5 | remove the pool | the Proxmox entry is gone ****Pass** (2026-10-12): the pool is removed from CloudStack and its entry is gone from Proxmox (confirmed on a node: not in `pvesm status`, not in `/etc/pve/storage.cfg`). The share stays mounted, which is also what happens when a storage is removed in Proxmox itself (see below) |
| p6 | compare the capacity with the NFS server | it matches **Pass (2026-10-12)** |

### Notes on p5 (removing the pool)

- Removing a pool from CloudStack removes the Proxmox entry. The NFS share is **left mounted** on the nodes. This is not specific to
  CloudStack: Proxmox's own removal of a storage leaves the mount too (observed on the lab; and in the source, the
  `DELETE /storage/{id}` handler of pve-storage 8.3.6 removes the entry from the configuration and never deactivates the storage).
  KVM differs, because CloudStack removes the libvirt pool and the mount goes with it.
- **Why it is left alone:** Proxmox has no API call to unmount a storage, so doing it would need root access to the nodes (for
  example over SSH), which this phase does not require; and a plugin that unmounts what Proxmox itself leaves mounted would be
  doing more than Proxmox. Re-adding the same pool reuses the mount.
- To clean up by hand, on each node, once the share is not in use: `umount /mnt/<pool uuid>` and `rmdir /mnt/<pool uuid>`.

### Phase 2 status (2026-10-12)

**Complete.** The implementation, the unit tests, and every lab check (p1 to p8, S1 to S8) are done. Left on purpose: the NFS share stays
mounted after a pool is removed (p5), and mount options cannot be set on a Proxmox pool (p8); both are documented in behaviour.md.
Phase 3 (VM deployment) starts from the volume design in design-phase2-storage.md, section 7.


Observed with the `findmnt` check (2026-10-12): besides the pool's own mount at `/mnt/<pool uuid>`, the nodes still had mounts left by the
spike entries that were added and removed by hand, some under `/mnt/pve/<id>` and one under `/mnt/<uuid>`. This is the same effect as p5
(Proxmox does not unmount a share when an entry is removed), seen with entries created without CloudStack. One of those ids was about
80 characters long and was accepted, which agrees with the Proxmox source having no length limit for a storage id.

S7 clean-up (2026-10-12): `qm destroy 9100 --purge` printed only "purging VM 9100 from related configurations", and the disk of another
id (9101) that had been attached to it was still there afterwards: `pvesm free spike-s7:9101/vm-9101-disk-0.qcow2` removed it. Deleting a
VM therefore removes the disks it owns but not an attached disk owned by another id.


## Phase 3 spikes (VM deployment), 2026-10-12

Lab: Proxmox 8.4.21, secondary NFS mounted by hand at `/mnt/secstorage` on one node (`vers=3,ro`), primary entry `cs-<uuid>`.

| # | What | Result |
|---|---|---|
| S9-0 | layout of the CloudStack secondary store | `template/tmpl/<account id>/<template id>/<uuid>.qcow2` next to `template.properties` |
| S9-1 | `qm create ... scsi0=<pool>:0,import-from=/mnt/secstorage/<path>.qcow2,format=qcow2` as **root** | **Pass.** The disk was created in `images/<vmid>/` and written in full (8 GiB written for an 8 GiB template, `preallocation=metadata`), so each deploy is a full copy |
| S9-2 | the same by **API token** with `import-from=<storage>:import/<file>` from a `dir` entry on `/mnt/secstorage` with `content=import` and `content-dirs import=template/tmpl/1/4` | **Pass.** Task exit status `OK`; the VM and its disk were created. Token roles: `PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage` (user and token) |
| S9-3 | `PUT .../config` with `net0=virtio=<mac>,bridge=vmbr1,tag=100,firewall=0` by token | Refused first: `Permission check failed (/sdn/zones/localnetwork/vmbr1/100, SDN.Use)`. **Pass** after `pveum acl modify /sdn/zones/localnetwork --users <user> --tokens <token> --roles PVESDNUser`. So a plain bridge needs `SDN.Use`, checked per bridge and VLAN tag |
| S9-4 | start, stop, destroy (`?purge=1`) by token | **Pass.** Each returns a UPID (`UPID:<node>:<pid>:<pstart>:<starttime>:<type>:<vmid>:<user>!<token>:`); `qm status` showed `running`; after destroy the VM configuration was gone |

| S9-5 | one `dir` entry for the whole store with `content-dirs import=template` and a nested volume id `import/tmpl/1/4/<file>` | **Refused:** `unable to parse directory volume name`. The volume name must be a plain file name directly in the entry's `import` folder, so one entry per template folder is needed |
| S9-6 | `nfs` entry `cst-test` (`--server`, `--export` = the secondary export, `--content import --content-dirs import=template/tmpl/1/4 --options vers=3,ro`), created as root; then the **token** creates a VM with `import-from=cst-test:import/<uuid>.qcow2` | **Pass.** The listing shows the template (`qcow2`, `import`, 1769537536 bytes); the VM was created; after `DELETE ...?purge=1` and `pvesm remove cst-test` the primary pool was empty (no disk left behind). Not yet tested: creating the entry by token through the API, and the mount on a second node |

| S9-7 | the **token** creates the per-template `nfs` entry through `POST /storage` (`type=nfs`, `server`, `export`, `content=import`, `content-dirs`, `options=vers=3,ro`) and deletes it with `DELETE /storage/<id>` | **Pass.** Create returned `{"data":{"type":"nfs","storage":"cst-test"}}`. The first delete printed an empty body and the entry was gone (a second delete answered HTTP 500 `storage 'cst-test' does not exist`). Needed `Datastore.Allocate` on `/storage` (part of `PVEDatastoreAdmin`); without it: `Permission check failed (/storage, Datastore.Allocate)` |
| S9-8 | with that entry, the token creates the VM through **another node** (`POST /nodes/<node2>/qemu`, `import-from=cst-test:import/<uuid>.qcow2`) | **Pass.** The VM exists on node 2, stopped, with the 8 GB boot disk, and node 2 has the share mounted (`/mnt/pve/cst-test`, `ro,vers=3`). Both nodes keep the mount after the entry is removed (as in p5). Destroy by token on node 2 returned a task id |

| S9-9 (P4) | the token moves a **stopped** VM to another node: `POST /nodes/<node1>/qemu/<vmid>/migrate target=<node2>` (disk on the shared NFS pool) | **Pass.** Returns a task id; the VM is then listed on node 2, stopped, with its disk. No extra privilege beyond `PVEVMAdmin` |
| S9-10 | import time for the lab's template | `time qm create ... import-from=...` took **7.6 s** for an 8 GiB disk (1.7 GB template) on the lab's NFS. Dependent on the NFS server and the template's real size |

Notes:

- A config value sent as `-d net0=virtio=...` without URL encoding gave `no sdn vnet ID specified`; the same value URL-encoded
  (`--data-urlencode`) worked. The client must URL-encode every value.
- Absolute-path `import-from` is refused for tokens (source reading); it worked here only as root on the node's shell.
- Successful `DELETE /storage/<id>` has an empty body; the client must not require JSON there.
- The task status call (`GET /nodes/<node>/tasks/<upid>/status`) returns `status` (`running` or `stopped`) and `exitstatus` (`OK` or an error text).

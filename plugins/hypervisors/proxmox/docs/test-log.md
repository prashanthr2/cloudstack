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

### Phase 3 core changes and SQL (2026-10-13)

- Unit tests: `VolumeDaoImplTest` (a Proxmox volume's format is QCOW2) and `NetworkModelImplTest` (Proxmox reads the KVM traffic label);
  the engine/schema and server suites touching these classes pass.
- The upgrade SQL (hypervisor capabilities row, guest OS to `ostype` mapping) was run against a scratch MySQL database with sample
  guest OS names, twice (the second run adds nothing). It found one wrong mapping, "Other Linux (64-bit)" -> `other`, which was fixed so
  Linux entries named "Other ..." map to `l26`. BSD, Apple, Novell and DOS guests map to `other`, as `l26` is documented as a Linux kernel.
- Template space reservation (`getBytesRequiredForTemplate`) needs no change: it only applies to managed storage, and the NFS pool is not.

### Phase 3a implementation (2026-10-13)

Unit tests: the plugin has 132 tests, all passing (JDK 17, checkstyle clean). New: client calls for VMs, tasks and import storage
(`ProxmoxApiClientVmTest`, against a fake Proxmox that answers by "METHOD path", including URL encoding of every option), the VM option
mapping (`ProxmoxVmConfigTest`), the command handlers (`ProxmoxVmHandlerTest`: template entry, root volume, id clash and cleanup, start with
and without an offline move, stop, reboot, delete), the guest OS lookup (`ProxmoxHypervisorGuruTest`), and the new `proxmox.task.timeout` setting.

Lab checks for Phase 3a are listed in design-phase3-vm-deployment.md, section 9, and still to be run.

### Phase 3a on the lab (2026-10-13)

| # | Check | Result |
|---|---|---|
| 1 | register a QCOW2 template for Proxmox | **Pass.** Ready. Found: the Format list was empty for Proxmox in the UI (`RegisterOrUploadTemplate.vue`); fixed |
| 2 | deploy a VM on an L2 network | **Pass.** Found: (a) CPU weight showed Proxmox's default 100 instead of following the offering's speed; fixed by setting `cpuunits` (cores x MHz as a share of the node, scale 1 to 10000, like KVM's shares). (b) Each template used leaves one `cst-` storage entry on the cluster (and a mount on every node): not practical for many templates, see the follow-up below |
| 3 | start on a different node than the one that created the VM | **Pass.** The first deploy already landed on another node and was moved |
| 4 | reboot, destroy and expunge | **Pass.** The VM and its disk are gone |
| 5 | second deploy of the same template; stop of an already stopped VM | **Pass.** The entry is reused; the stop job succeeds |

Check 6 (zone without a KVM traffic label), run 2026-10-16: **Pass.** The start fails at once with `NIC 0 (MAC ...) has no bridge: set the KVM traffic label of the physical network to the Proxmox bridge`. Not run: a template folder the nodes cannot read.

### Spike S10: fewer storage entries for templates (2026-10-14)

Question: can one entry per account folder serve all templates of that account?

| # | What | Result |
|---|---|---|
| S10-1 | `nfs` entry `acct-test` (`content=images`, `content-dirs images=template/tmpl/1`, `options=vers=3,ro`), then `pvesm list acct-test` | **Empty list.** The source explains it: `list_images` only scans folders matching `[0-9][0-9]*` (two or more digits), and the template folders are `3` and `4`. So the file cannot be checked through the listing |
| S10-2 | the token creates a VM with `import-from=acct-test:4/<uuid>.qcow2` | **Pass.** A task id was returned, `qm config` showed `lock: create` while the import ran, then the VM with `scsi0` 8G. The volume need not be listed to be used |

Result: the plugin uses one entry per account folder (design 4.1). The template check is "the entry is mounted on the node", not a file
lookup; a missing file fails the VM creation with Proxmox's message.

### Spike S11: one entry per secondary store with an absolute path (2026-10-14)

Why: S10 works but needs one entry (and one permanent mount on every node) per account folder, which does not scale to thousands of accounts.
Setup: one `nfs` entry `sec-all` on the whole secondary export (`content=import`, `options=vers=3,ro`), mounted by Proxmox at `/mnt/pve/sec-all`.

| # | What | Result |
|---|---|---|
| S11-1 | the **token** creates a VM with `import-from=/mnt/pve/sec-all/template/tmpl/1/4/<uuid>.qcow2` | **Refused:** `Only root can pass arbitrary filesystem paths.` The Proxmox check is `$user ne 'root@pam'`, and a token's user string is `root@pam!<name>`, so even a token owned by root is refused |
| S11-2 | the same through a **root@pam login** (`POST /access/ticket`, then the `PVEAuthCookie` and `CSRFPreventionToken` headers) | **Pass.** `ticket ok`; the create returned a task (`...:qmcreate:9221:root@pam:`) and the VM has `scsi0` 8G |

Result: one entry per secondary store (any number of accounts and templates) is possible, but the template import needs a root@pam login
(a password, not a token). Whether to take that is a decision for the project; see design-phase3-vm-deployment.md, 4.1.

### Decision: back to one entry per account folder (2026-10-14)

The root@pam variant (S11) was implemented and then reverted: no root password for now, to be revisited if another feature needs root. The
plugin is back to the S10 design (one read-only entry per account folder, imported with the API token). The global setting
`proxmox.root.password` and the root login in the client are gone again (they remain in the git history, commit 93e1ed96ed). Known limitation: the
number of entries grows with the accounts that use templates on Proxmox (design 4.1).

### Spike S12: one NFS mount per store, one `dir` entry per account (2026-10-14)

Question: can several storage entries share one mount, so that the number of mounts does not grow with the accounts? Setup: the existing
read-only `nfs` entry of the secondary store (mounted at `/mnt/pve/cst-aba3b55d-...`), plus a `dir` entry on that same path:
`pvesm add dir acct1-dir --path <that mount> --content images --content-dirs images=template/tmpl/1 --is_mountpoint yes --shared 1` (run as root).

| # | What | Result |
|---|---|---|
| S12-1 | add the `dir` entry, then `pvesm status` and `findmnt` | **Pass.** `acct1-dir` is `active` with the share's capacity; the list of mounts is **unchanged** (no new mount) |
| S12-2 | the token creates a VM with `import-from=acct1-dir:4/<uuid>.qcow2` | **Pass.** `lock: create` while the import ran, then the VM with `scsi0` 8G |
| S12-3 | remove the VM and the `dir` entry | **Pass.** The mounts are unchanged, nothing is left behind (a `dir` entry mounts nothing) |

Observation from the same output: seven mounts of the same export were on the node, all left by removed test entries (Proxmox never
unmounts), which is what per-account `nfs` entries would produce at scale.

| S12b | the **token** creates the `dir` entry through `POST /storage` (`type=dir`, `path`, `content=images`, `content-dirs`, `is_mountpoint=yes`, `shared=1`) and removes it with `DELETE /storage/<id>` | **Pass.** Create returned `{"data":{"type":"dir","storage":"acct1-dir"}}`, the entry was `active`, delete returned `{"data":null}` and the entry was gone |

Result: the plugin uses one NFS mount per secondary store and a directory entry per account folder on it (design 4.1), with the token only.

### Phase 3a: store mount plus account directory entries implemented (2026-10-14)

Unit tests: 139 pass. The template copy creates the store's NFS entry (`css-`), then the account's `dir` entry (`cst-`) and waits for both to
be active; another template of the same account creates nothing, another account adds only a `dir` entry, no second mount. To retest on the lab:
pull and rebuild, remove the old `cst-` entries (`pvesm status | grep cst-`, `pvesm remove`), remove the template from the primary pool or register
a new one, deploy, then `findmnt | grep secondary` should show one mount for the store (plus leftovers from earlier entries until unmounted) and
`pvesm status` the `css-` and `cst-` entries.

### Phase 3a on the lab, store mount version (2026-10-14)

| Check | Result |
|---|---|
| deploy a VM | **Failed first:** `create storage failed: mkdir /mnt/css-.../import: Read-only file system`. Proxmox creates the folders of the declared content types in the mount of a new entry unless `create-subdirs` is 0, and the share is read-only. Fixed: `create-subdirs=0` on the store's NFS entry, and `create-subdirs=0` and `create-base-path=0` on the directory entries (140 unit tests pass). To retest |
| delete a template | **Failed first, then passed after patching the SSVM (2026-10-16):** `IllegalArgumentException: HypervisorType 'Proxmox' not found` in `HypervisorTypeAdaptor.deserialize`, logged by an `AgentRequest-Handler` thread. Not the plugin: a template on secondary storage is deleted by the secondary storage VM, which got the command with `"hypervisorType":"Proxmox"` and runs an older copy of the API classes baked into its system VM image. To be confirmed on the SSVM, then fixed by rebuilding the system VM image and recreating the SSVM (see upstream-notes.md, 10) |

### Template delete on the lab, resolved (2026-10-16)

The delete worked after the secondary storage VM was given the current code. In this CloudStack version there is no `systemvm.iso`: the build
(`mvn -DskipTests -Dsystemvm install -pl systemvm -am`) produces `systemvm/dist/agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh`, the KVM agent
keeps copies in `/usr/share/cloudstack-common/vms/` on each KVM host and copies them into the system VM over SSH, and `patchSystemVm` with
`forced=true` is needed because only jars changed and the check compares the scripts' checksum. Steps: check the new `agent.zip` holds the type
(`unzip -p agent.zip cloud-api-*.jar > /tmp/a.jar; unzip -p /tmp/a.jar 'com/cloud/hypervisor/Hypervisor$HypervisorType.class' | grep -c Proxmox`),
copy the three files to each KVM host, then `cmk patch systemvm id=<ssvm> forced=true`.

### Phase 3a status (2026-10-17)

All lab checks of the Phase 3a plan have passed: register, deploy on L2, start on another node, reboot, destroy and expunge, repeat deploy, stop of
a stopped VM, template delete (after patching the SSVM), and the missing traffic label (clear error). Remaining from the plan: a template folder the nodes
cannot read. Not in 3a: console, data volumes, live migration, HA, system VMs and virtual routers.

### Phase 4 console checks (2026-10-17)

Run with `docs/scripts/console-check.py` on a node, token `pveaudit@pam!cloudstack` (roles from Phase 3), VM 100 running.

| # | What | Result |
|---|---|---|
| C1 | `POST .../qemu/100/vncproxy` with `websocket=1` by token | **Pass.** HTTP 200; `port` 5900, `user` `pveaudit@pam!cloudstack` (the full token string), a `password` is returned, ticket length 370. `VM.Console` is covered by the existing roles |
| C2 | websocket upgrade (`vncwebsocket`) with the **same token as an `Authorization: PVEAPIToken=...` header** | **Pass.** `HTTP/1.1 101 Switching Protocols`: pveproxy accepts the token header on the websocket request. No login cookie is needed |
| C3 | first bytes through the tunnel | **Pass.** `RFB 003.008` (the VM's VNC server answers through the websocket) |
| C3 window | the delay between `vncproxy` and the websocket: 0 s, 8 s, 30 s | **0 s and 8 s worked; 30 s failed** (no upgrade answer, the read timed out). So the window is somewhere between 8 and 30 s (the listener's timeout and the ticket's age); not narrowed further |

### Phase 4 console implemented (2026-10-17)

Unit tests: plugin 150 (console handler: this node and another node, VM unknown, not running, no password, refusal by Proxmox, the URL encoding, the token
header; `openConsole`; the resource command), server `ConsoleAccessManagerImplTest` 46 (Proxmox branch, refusals, param, noVNC URL with the encoded password),
console proxy (header carried from the token, a header or URL in the query ignored, header sent on the websocket request). Lab test: to be run, see
design-phase4-console.md, section 11.

### Console on the lab: first attempt (2026-10-17)

`createConsoleEndpoint` failed with `UnsupportedAnswer cannot be cast to GetVncPortAnswer`: the stack trace showed `ConsoleAccessManagerImpl.java:495`, the
call of the old class. The new jars were in `~/.m2` but jetty loads the shaded `client/target/cloud-client-ui-*.jar` (built Oct 16), which still held the old
classes. Fix: rebuild the `client` module (`mvn -DskipTests install -pl client`) and restart. Build steps corrected in design-phase4-console.md, section 11.

### Console on the lab: second attempt (2026-10-17)

After the `client` rebuild `createConsoleEndpoint` worked and the console proxy VM received the request (log: viewer created, "Connect to VNC over websocket on
host 10.0.35.25", "Connection to VNC server has been established successfully"), but the browser stayed in "connecting". That log line was misleading: the
websocket client connects asynchronously, so it only means the attempt started; nothing followed, which fits a connection that hangs (the console proxy VM
may not reach port 8006 of the node). Changes: the connect now waits for the handshake (15 s) and fails with the host in the message, and the opening of the
websocket is logged. To check on the VM: `timeout 6 bash -c '</dev/tcp/10.0.35.25/8006'`, `ip route get 10.0.35.25`.

### Console on the lab: third attempt, cause found (2026-10-17)

Frames logged by the CPVM: banner (12) both ways, security types (2: `01 02`), choice (1), challenge (16), and then no answer from the viewer. The browser
console: "noVNC requires a secure context (TLS)" (the page is served over HTTP). Change: the console proxy now logs in for the viewer
(`VncPasswordAuthenticator`, 11 unit tests), the noVNC URL no longer carries a password (46 `ConsoleAccessManagerImplTest` tests). To retest: rebuild `server`
and `client`, rebuild the system VM files, patch the CPVM, open a fresh console.

### Phase 4 console: works on the lab (2026-10-17)

With the console proxy logging in for the viewer, "View console" on a running Proxmox VM opens the guest's screen. Findings on the way, all recorded above: the
client module must be rebuilt for jetty to load new server classes; the console proxy VM needs the patched files (agent.zip and the others on each KVM host,
then `patchSystemVm forced=true`); a websocket connect that is not waited for hides failures; noVNC cannot answer a VNC password challenge on a page served
over plain HTTP. Known limits: a console session is single use (a reload or a dropped connection needs "View console" again), and the API token secret is
inside the console proxy token.

### Phase 5 migration and HA: lab checks (2026-10-19)

Design: design-phase5-migration-ha.md, section 8. Token `pveaudit@pam!cloudstack` (roles `PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`,
`PVESDNUser` on the zone).

| # | What | Result |
|---|---|---|
| L1 | the token migrates a running VM: `POST /nodes/<node>/qemu/<vmid>/migrate` with `target=<node name>` and `online=1` | **Pass.** The node in the path and the target are node **names** (a node id such as `1` or `3` is refused: "does not look like a valid node name"). Task type `qmigrate`, registered on the source node; final status `stopped`, `exitstatus` **`OK`**. A running VM of 512 MB took **12 s** (start 1792420768, end 1792420780); an earlier offline move of a stopped VM (9204) took **2 s**. The VM was still `running` afterwards. Only `VM.Migrate` (in `PVEVMAdmin`) was needed |
| L1 (root-only) | the same with `migration_type=insecure` | **Refused:** HTTP 400, `{"errors":{"migration_type":"Only root may use this option."}}`, no migration. The plugin must not send `migration_type`, `migration_network` or `force` |
| L2 | the precondition `GET /nodes/<node>/qemu/<vmid>/migrate?target=<node>` as the token, VM running, with and without a local ISO attached | **Pass, with a finding.** Without the ISO: `running` 1, `local_disks` `[]`, `local_resources` `[]`, both other nodes in `allowed_nodes`. With `local:iso/dummy.iso` as `ide2`: `allowed_nodes` unchanged, and `local_disks` lists it (`volid`, `cdrom` 1, `shared` 0, `is_unused` 0, `referenced_in_pending` 1). **`not_allowed_nodes` contained every node with an empty entry `{}` in both runs, while the same nodes were in `allowed_nodes`**: an entry means nothing unless it has a reason (`unavailable_storages` / `unavailable-resources`), and a local blocker shows only in `local_disks`. The plugin's check reads those fields, not just the list membership |
| L9 | `GET /nodes/<target>/network` as the token | **Pass.** `vmbr1` on the target: `type` `bridge`, `active` 1, `bridge_vlan_aware` 1, `bridge_vids` `2-4094`, `bridge_ports` `eth1`. The preparation check can rely on these fields |
| L3 | the HA API as the token: read, create and delete `/cluster/ha/resources` | **All refused, HTTP 403:** read `Permission check failed (/, Sys.Audit)`, create and delete `Permission check failed (/, Sys.Console)`. So managing HA resources needs `Sys.Audit` and `Sys.Console` on `/`, neither in `PVEVMAdmin`; `Sys.Console` is node console access (standard Proxmox docs), a notable widening of the token. HA management is therefore opt-in (design 4.3). `ha-manager config` and `status` as root printed only `quorum OK` (no HA resources exist) |
| L10 | the narrow HA grant: role `CloudStackHA` (`Sys.Audit`, `Sys.Console`) on `/` for the user and the token, **propagate 0** (`pveum acl list` shows both entries with propagate 0); then the HA calls and a node console call as the token | **Pass.** Read `/cluster/ha/resources`: HTTP 200 (`[]`). Create `sid=vm:100`, `state=started`, `comment=CloudStack`: HTTP 200; `ha-manager config` shows `vm:100`, `comment CloudStack`, `state started`, and `ha-manager status` shows `service vm:100 (<node>, starting)` for the already running VM (it is adopted). Delete: HTTP 200. **`POST /nodes/<node>/termproxy` is still refused:** `Permission check failed (/nodes/<node>, Sys.Console)`, HTTP 403: the non-propagating grant at `/` does not reach the node's own path, so the token gets HA management without node console access. The grant was left in place on the lab for the later HA test with a compute offering |
| L4 | with `vm:100` an HA resource created by the token (`state started`, comment `CloudStack`): `status/shutdown` (`timeout=30`, `forceStop=1`), `status/start`, `migrate` (`online=1`) by token | **Pass; confirms the redirects.** Each returned **at once, HTTP 200, with an HA task: `hastop`, `hastart`, `hamigrate`**. Shutdown: the VM was still `running` for the whole 33 s that was watched (`ha-manager status`: `started`, then `request_stop`) and was `stopped` within about 75 s of the call (so the shutdown timeout plus the HA cycle; `forceStop` makes no difference on this path). Start: the VM was `stopped` for about 18 s and `running` about 20 s after the call. Migrate: the HA state stayed `started` for about 12 s, then `migrate`, and was still `migrate` 30 s later (a direct, non-HA migration of the same VM took 12 s in all); the move finished: later `ha-manager status` showed `vm:100` `started` on pve3 and `qm list` there showed it `running` (pve1 and pve2 listed nothing), so an HA migration completes and the VM stays up. After the start, `ha-manager config` showed `vm:100` with `state started` |
| L5 | delete an HA VM: a throwaway VM 9300 with an HA resource (`state stopped`, comment `CloudStack`, created by the token); `DELETE .../qemu/9300`, then `?purge=1` | **Pass.** Without `purge`: HTTP 500, `unable to remove VM 9300 - used in HA resources and purge parameter not set.` With `purge=1`: a `qmdestroy` task; 5 s later the VM was gone and `ha-manager config` listed only `vm:100`: purge removes the VM's HA resource too. The plugin already sends `purge=1` |
| L8 | is there a REST endpoint for HA node maintenance: `pvesh ls /cluster/ha`, `pvesh usage /cluster/ha -v`, `ha-manager help` | **No endpoint.** `/cluster/ha` lists only `groups`, `resources` and `status`. Node maintenance exists only as the CLI command `ha-manager crm-command node-maintenance enable\|disable <node>`, which runs on a node. A token-only plugin cannot set it. Decision: CloudStack host maintenance migrates the VMs off (Phase 5 migration); the plugin does not set Proxmox's maintenance state, and the docs tell the admin to do it with `ha-manager` if they want Proxmox HA to stay away from the node |

### Phase 5 implementation, unit tests (2026-10-19)

Built on the decisions in design-phase5-migration-ha.md. New: `ProxmoxApiClient` calls (`migrateRunningVm` with `online=1`, `getVmStatus`, `getMigrationProblems`,
`listBridges`, `findHaResource`, `createHaResource`, `deleteHaResource`), `ProxmoxVmHandler` (prepare, migrate, check, the mark, waits for the real state, HA flag),
settings `proxmox.migration.timeout` and `proxmox.ha.manage`, and the core changes (Proxmox added to the two live-migration lists, and skipped like VMware in
`HighAvailabilityManagerImpl` three times and in `VirtualMachineManagerImpl` once).

Plugin: **182 tests pass** (was 150): `ProxmoxVmHandlerMigrationTest` (24: preparation refusals for bridge, VLAN, state, reason and offline node; the move waits until the VM
is really on the target and running; already-there and unknown-address cases; failed and timed-out move; check answers; the mark is used up by one stop, covers
reboot, is ignored when the VM is on this node, ends with a verified migration, a rollback or expiry; stop waits for the real state and times out; HA resource created,
kept, removed only when made by the plugin, 403 fails a protected VM's start and not an unprotected one's; setting off changes nothing; start waits for running),
`ProxmoxApiClientVmTest` (+7: the HA and migration calls against a fake Proxmox, including the empty-entry reading rule) and `ProxmoxConfigTest`. Core:
`VirtualMachineManagerImplTest` (103), `HighAvailabilityManagerImplTest` (22), `ManagementServerImplTest` (71) and `UserVmManagerImplTest` (253) pass. The last needed
a one-line change of a hard-coded count (`verifyIfHypervisorSupportRootdiskSizeOverrideTest`, expected exceptions = number of hypervisors minus 6, now 7:
adding `Proxmox` to the enum broke it already in Phase 1, see upstream-notes.md 12).

Lab runs of the Phase 5 behaviour (fill in the Result column as they are run):

| # | Steps | Expected | Result |
|---|---|---|---|
| P5-1 | Live-migrate a running instance to another host from the UI | The instance keeps running; CloudStack shows it on the new host | **Pass** (2026-10-19; the console works after it) |
| P5-2 | Try to migrate an instance to a host that lacks its bridge, or an instance with a local CD-ROM | The migration is refused with a clear reason and the instance is untouched on its host | **Pass** (2026-10-20) |
| P5-3 | Start a migration and take the destination away mid-move (for example stop its Proxmox networking) | The VM keeps running on the source; CloudStack shows it Running on the source | **Pass** (2026-10-20): with SSH from the source node to the destination blocked, the migration failed with Proxmox's reason, the VM stayed Running on the source, the clean-up stop sent to the destination was left alone (the mark), and a later migration and stop worked |
| P5-4 | Put a host that has an instance on it into maintenance (`vm.ha.enabled` = true), then cancel maintenance | The instance moves away and the host reaches Maintenance; cancelling brings it back to Up | **Pass** (2026-10-20) |
| P5-5 | Set `proxmox.ha.manage` = true. Start an instance from an HA-enabled offering; then stop, start and delete it from CloudStack | `ha-manager config` shows `vm:<id>` with comment `CloudStack`; stop and start work through HA; the delete removes the resource. Without the privileges the start fails naming `Sys.Audit` and `Sys.Console` | **Pass** (2026-10-20): with `proxmox.ha.manage` = true and an HA offering, `ha-manager config` listed `vm:105`, `vm:106`, `vm:107` (comment `CloudStack`, state `started`) for the three instances, each on the node it runs on; after a CloudStack stop `vm:105` was `stopped` (Proxmox does not restart it), after the start `started` again; after the delete the resource was gone. (A first attempt with an offering that did not have HA enabled created no resource, as designed) |
| P5-6 | With an HA instance, power off its node (lab check L6) | Proxmox restarts the VM on another node and CloudStack follows it | **Pass with a finding** (2026-10-20): HA instances restarted on another node in 3 to 4 minutes and CloudStack followed them; non-HA instances stayed Running (offline banner) while the node was down and went to Stopped after the node came back, and the host returned to Up. The finding (a stopped VM reported as PowerOff during recovery) is fixed by reporting only running VMs; the repeat that verifies the fix is open |
| P5-7 | Move an HA instance with `ha-manager migrate` while watching CloudStack (lab check L7) | CloudStack's report of the VM during the move does not mark it Stopped | **Pass** (2026-10-20, with the report-only-running-VMs build): `ha-manager migrate` moved an HA instance from pve2 to pve1; the log shows only `PowerOn` reports, the VM's host changing from 15 to 14, no `PowerOff` and no Stopped state |

### Console after Phase 5: the VNC login raced with the browser (2026-10-19)

Symptom on the lab: after Phase 5 was deployed, the console of a migrated VM and of a new VM closed at once (websocket code 1006, "frames from the host: 5, to the host: 3");
a run of `console-check.py` against VM 100 on pve2 gave `101` and `RFB 003.008`, so Proxmox was fine. Cause, from the console proxy log timestamps: the login with Proxmox
was done at 21:42:59,304 and the browser's own version and security choice (13 bytes, which `VncPasswordAuthenticator` answers itself and must not pass on) arrived at
21:42:59,330, after it. The class only swallowed them while the login was still running, so they went to QEMU, which took them for the ClientInit and closed the connection
right after its ServerInit. It had worked before because the login was slower (the race is between Proxmox's reply and the browser's reply). Fix: the first 13 bytes from
the viewer are swallowed in every state. Two new tests (`aViewerThatAnswersAfterTheLoginIsDoneIsStillSwallowed`, and the same with ClientInit in the same write); console
proxy tests 25 pass. Needs the system VM files rebuilt and the console proxy VM patched again (design-phase4-console.md, deployment steps).


Lab result (2026-10-19): after the fix and the system VM files patched again, the console works on a VM that was live-migrated and on a newly deployed VM. P5-1 (live migration
from the UI) works; the migration error before it was a management server still running the old `server` jar (only the plugin and `client` had been rebuilt).

## Phase 6 (ISOs): lab checks I1 to I8 (2026-10-20)

Design: design-phase6-iso.md, sections 7 and 9 (commands and results). All eight pass with the token and the documented roles: the per-ISO-folder directory entry is created and lists
the ISO; attach, swap and eject work on a stopped and a running VM (checked by whether QEMU holds the file open); a new drive added to a running VM is only pending, so the plugin
checks the running configuration first; a blank VM with `scsi0=<pool>:2,format=qcow2` boots from the CD-ROM first; the migration precondition has no local disk with an ISO from a shared
entry; a concurrent create of the same entry gives one 200 and one 500 (handled like the template entries); a non-ISO file is refused by Proxmox.

### Phase 6 implementation, unit tests (2026-10-20)

New: `ProxmoxStorageNames.isoStorageId`, `ProxmoxApiClient.createIsoDirStorage` and `getRunningVmConfig`, `ProxmoxVmConfig` (CD-ROM drive mapping, `nicAdapter`, boot order), `ProxmoxVmHandler`
(`attachIso`, `detachIso`, `createObject`, the drives at start, `ensureSecondaryMount` shared with the template copy), host detail `host.cdrom.max.count` = 4, and the three commands in
`ProxmoxResource`. Plugin: **209 tests pass** (was 182): `ProxmoxVmHandlerIsoTest` (15: entries and volume, second slot, a running VM without the drive is refused without a change, a
stopped VM gets it, bad slot / name / path / non-NFS store, unknown VM, data volume unsupported, detach and its no-op cases, every slot of the start command becomes a drive, boot order,
a drive emptied after a detach, a bad ISO fails the start, blank root volume rounded to whole GiB with the usual path, only root volumes), `ProxmoxVmConfigTest` (+8),
`ProxmoxApiClientVmTest` (+2), `ProxmoxStorageNamesTest` (+1), `ProxmoxResourceTest` (+1).

Lab runs to record (fill in the Result column as they are run):

| # | Steps | Expected | Result |
|---|---|---|---|
| P6-1 | Set `vm.iso.max.count` to 2 (zone or cluster scope). Restart the management server so the hosts reconnect (they then report 4 CD-ROM drives). Stop and start a test instance. Attach an ISO to the running instance. | The attach succeeds; Proxmox shows `ide2: csi-...:iso/<file>.iso,media=cdrom`; a `csi-...` entry exists for the ISO's folder | **Pass** (2026-10-20) |
| P6-2 | On the same instance, attach a second ISO (second drive, `ide3`), then detach the first ISO | The second ISO is in `ide3`; after the detach `ide2` is `none,media=cdrom` and the drive is kept | **Pass** (2026-10-20) |
| P6-3 | Stop the instance and start it again with an ISO attached | The drives are in place at start (`ide2` holds the ISO, a second slot is empty); the ISO is still attached after the start | **Pass** (2026-10-20); the CentOS 5 instance panicked at boot for a guest-driver reason, see the finding above |
| P6-4 | On an instance that was started before step P6-1 (no CD-ROM drive in its running configuration), attach an ISO while it runs | The attach fails with "stop and start the instance"; Proxmox has no pending change (`qm pending` shows no `ide`) | **Pass** (2026-10-20): the UI shows "failed to attach ISO" |
| P6-5 | Deploy a new instance from an ISO (a bootable installer, if available) | A blank 'scsi0' disk of the chosen size; the console shows the ISO booting first (with the 350 KB lab ISO: "Booting from DVD/CD... Boot failed") | **Pass on the second attempt** (2026-10-20); on the first the console failed because the VM was found stopped in Proxmox, cause open (below) |
| P6-6 | On a stopped instance set the details `rootDiskController` = `sata` and `nicAdapter` = `e1000`, then start it | In Proxmox the root disk is `sata0` (no `scsi0`, no `unused0`) and the NIC is `e1000=...`; the boot order names `sata0` | **Pass** (2026-10-20) |
| P6-7 | Migrate an instance that has an ISO attached to another host | The migration succeeds; the ISO is still attached on the new host | **Pass** (2026-10-20) |
| P6-8 | Delete an instance that has an ISO attached, then delete the ISO in CloudStack | Both deletes succeed; the `csi-...` entry stays (it is kept by design) | **Pass** (2026-10-20) |

Phase 6, lab check I9 and `rootDiskController` (2026-10-20): the one-call move of the root disk from `scsi0` to `sata0` works with the token (see design-phase6-iso.md, section 9). Implemented as
`ProxmoxVmHandler.moveRootDisk` with `ProxmoxVmConfig.rootDiskDrive` / `rootDriveOf`; the boot order names the drive. Plugin: **219 tests pass** (was 209): the mapping (scsi, osdefault, virtio-scsi, virtio,
sata; ide and unknown refused), the drive found by the volume it holds, the move to sata, the default leaving the disk alone, a move back, a bootable ISO before the moved disk, ide failing the start
before anything changes, and a root disk that is on no drive.

Phase 6 lab finding (2026-10-20): P6-1 passed (attach to a running instance). P6-3 showed a guest kernel panic ("Volume group VolGroup00 not found ... Kernel panic"): the instance is CentOS 5, whose boot ramdisk has no
virtio-scsi driver, so its root disk on `scsi0` is invisible; the ISO is not the cause (the boot order was `scsi0` only). The setting `rootDiskController` was not in the Settings dropdown for a Proxmox
instance because the suggestions are a per-hypervisor list in core; Proxmox is added (`QueryManagerImplTest`, 37 pass). To verify: set `rootDiskController` = `sata` on the stopped instance and start it.

Phase 6 console observations (2026-10-20): (1) The console of an instance that CloudStack still showed Running failed with "VM ... must be running to connect console" while the VM was **stopped in Proxmox**
(VM 103, `i-2-26-VM`): CloudStack learns of a power-off through the host ping (`ping.interval`, 60 s), so the state can be wrong for up to a minute, and the error was raised after it had caught up. Why
the VM stopped is still open (guest shutdown, crash, or a stop issued by someone; the Proxmox task history of VM 103 and the management server log decide). (2) On the instance deployed from the ISO the console
worked and then reported "disconnected" once; connecting again worked (a console session is single use, see behaviour.md, section 11).

Parallel deployments (2026-10-20): the log of three simultaneous deployments showed `"executeInSequence":"true"` on every root-volume `CopyCommand` while `execute.in.sequence.hypervisor.commands` is false (the StopCommand
of the same VM had `false`), and the agent queue holds such requests back per host. Cause: `KVMGuru` (upstream-notes.md, 17). Fix in `ProxmoxHypervisorGuru` and a more patient VM id retry
(`ProxmoxHypervisorGuruTest` +3, plugin 222 tests pass). Lab run to record: P-par-1, deploy three instances at once: all three `qmcreate` tasks start together; no new "Waiting for Seq" line in the management server log.

Lab result (2026-10-20): P-par-1 **pass**: with the guru change deployed, instances deployed at once are created in parallel (the `qmcreate` tasks start together). Open issue 13 is closed.

P5-6 node failure (2026-10-20, `echo o > /proc/sysrq-trigger` on the node of an HA instance): Proxmox restarted the HA instances on another node after 3 to 4 minutes, the host of the failed node went to Alert (not Down), CloudStack
did not restart anything, and the non-HA instances stayed Running in CloudStack (with the "Compute Resource Status is Offline" banner), as designed. **Finding:** during the recovery the new node reported the HA VM as PowerOff
for ten seconds (Proxmox had moved its configuration there and not yet started it); CloudStack took that as an out-of-band stop, failed to stop it on the unreachable host, released its network and storage resources, set it
Stopped, and then Running again on the new host when the VM started. Fix: the plugin reports only running VMs, like KVM (behaviour.md, section 3; open issue 1 is closed). Plugin: 222 tests pass. To verify on the lab: repeat
P5-6 and check that the log shows no `PowerOff` report and no Stopped state for the HA VM during the recovery.

### Phase 7 implementation, unit tests (2026-10-21)

New: `ProxmoxApiClient` (`allocateImage`, `getImageInfo`, `deleteImage`, `resizeDisk` now returns the task), `ProxmoxVmConfig` (holder ids and the reserved range, the data drive and its controller, the drive options from the disk
offering, finding the drive that holds a volume), and in `ProxmoxVmHandler`: create (data volume), attach, detach, the data drives and stale-drive removal at start, the offering limits on the root drive, delete of a data
volume, resize, volume statistics, and a guard against a VM id in the reserved range; `dataDiskController` is offered for Proxmox in the settings suggestions (`QueryManagerImpl`). Plugin: **265 tests pass** (was 222):
`ProxmoxVmHandlerDataVolumeTest` (26), `ProxmoxVmConfigTest` (+10), `ProxmoxApiClientVmTest` (+6); core `QueryManagerImplTest` (37) passes.

Lab runs to record (fill in the Result column as they are run; use an account other than admin for one of them):

| # | Steps | Expected | Result |
|---|---|---|---|
| P7-1 | Create a data volume and attach it to a running instance (scsi controller) | The attach succeeds; Proxmox shows a `scsi1` drive `...:900000002/vm-900000002-<uuid>.qcow2`; the file is in `images/900000002/` | **Pass** (2026-10-21, after the AttachAnswer fix): a data volume created and attached to a running instance |
| P7-2 | Format and mount it in the guest, write a file, detach the volume, attach it to another instance | The data is there on the second instance; no `unusedN` line in the first VM's configuration | **Pass** (2026-10-21, account 4, holder `900000004`): the data followed the volume to the second instance; after the detach the first VM's configuration had no `scsi1` and no `unusedN`. Note: Proxmox appends the empty CD drives to the boot order after the disk (`order=scsi0;ide1;ide0;ide3;ide2`); the disk is still first |
| P7-3 | Grow the attached volume (resize to a larger size) | The size grows in CloudStack and in Proxmox; the guest sees it after a rescan | |
| P7-4 | Try to resize a detached volume, and to shrink an attached one | Both fail with a clear message | |
| P7-5 | Stop and start the instance with the volume attached; then detach while stopped and start again | The volume is a drive after the start; after the detach and start it is gone from the configuration | |
| P7-6 | Delete the instance (expunge) with a volume attached, then check the volume | The volume is `Ready` and detached in CloudStack, its file is still in `images/<holder>/` | |
| P7-7 | Delete a detached volume | The file is gone from `images/<holder>/` | |
| P7-8 | Set `dataDiskController` = `sata` on a stopped instance, start it, attach a volume while running | The attach fails with "stop and start" and no pending change is left (`qm pending`); after a restart the drive is `sata1` | |
| P7-9 | Migrate an instance that has a data volume | It migrates with the volume attached | |
| P7-10 | Use a disk offering with iops and bandwidth limits for the root disk and a data disk | Proxmox shows `iops_rd` / `mbps_wr` on the drives | |

Lab finding (2026-10-21, first P7-1 attempt): attaching a data volume failed with `class com.cloud.agent.api.Answer cannot be cast to class org.apache.cloudstack.storage.command.AttachAnswer`: the plugin answered the volume
`AttachCommand` with a plain `Answer`, and `VolumeApiServiceImpl` casts the reply to `AttachAnswer` (a failure too). Fixed: the volume attach answers with `AttachAnswer` (the disk, or the message), the volume detach with
`DettachAnswer`; the tests now assert the answer types. Plugin: 265 tests pass.

Lab finding (2026-10-21, P7-3): resizing an attached data volume was refused by core with "Hypervisor Proxmox does not support volume resize" before any command reached the plugin: `VolumeApiServiceImpl` allows a resize only for
the hypervisors in `SupportedHypervisorsForVolResize`. Proxmox added to that list (upstream-notes.md, 18); the other KVM-specific checks near the resize, attach and detach paths do not affect Proxmox (read in the code). `VolumeApiServiceImplTest` (159) passes.


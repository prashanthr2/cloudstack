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
| P7-3 | Grow the attached volume (resize to a larger size) | The size grows in CloudStack and in Proxmox; the guest sees it after a rescan | **Pass** (2026-10-21, after the resize allow-list fix): a volume attached to a running instance grew from 20 GiB to 50 GiB; Proxmox shows `scsi1 ... size=50G`, the guest sees the new size and the filesystem grew online (`growpart`, `resize2fs`) with the data intact |
| P7-4 | Try to resize a detached volume, and to shrink an attached one | Both fail with a clear message | **Pass** (2026-10-21): the resize of a detached volume is refused; a shrink is refused by CloudStack itself for QCOW2 ("Unable to shrink volumes of type QCOW2", the same rule as for KVM), so the plugin's own shrink refusal is never reached through the UI |
| P7-5 | Stop and start the instance with the volume attached; then detach while stopped and start again | The volume is a drive after the start; after the detach and start it is gone from the configuration | **Pass** (2026-10-21): after a stop and start the data volume is a `scsi1` drive again; after a detach while stopped and the next start the drive is gone, with no `unusedN` entry |
| P7-6 | Delete the instance (expunge) with a volume attached, then check the volume | The volume is `Ready` and detached in CloudStack, its file is still in `images/<holder>/` | **Pass** (2026-10-21): after the instance was expunged, its VM and root disk were gone and the data volume stayed `Ready` and detached, with its image in the holder folder |
| P7-7 | Delete a detached volume | The file is gone from `images/<holder>/` | **Pass** (2026-10-21): the image of a deleted detached volume is gone from the holder folder |
| P7-8 | Set `dataDiskController` = `sata` on a stopped instance, start it, attach a volume while running | The attach fails with "stop and start" and no pending change is left (`qm pending`); after a restart the drive is `sata1` | **Pass** (2026-10-21, as admin): the attach to a running instance with `dataDiskController` = `sata` is refused with nothing left pending; after stopping the instance, attaching while it is stopped and starting it the drive is `sata1`. The refusal's wording was corrected (restarting alone does not help) |
| P7-9 | Migrate an instance that has a data volume | It migrates with the volume attached | **Pass** (2026-10-21): an instance with a data volume migrates and keeps the volume attached |
| P7-10 | Use a disk offering with iops and bandwidth limits for the root disk and a data disk | Proxmox shows `iops_rd` / `mbps_wr` on the drives | **Pass** (2026-10-21, after the fixes): the offering's limits, bursts and lengths are on the drives in Proxmox, with the small bandwidth kept exact |

Lab finding (2026-10-21, first P7-1 attempt): attaching a data volume failed with `class com.cloud.agent.api.Answer cannot be cast to class org.apache.cloudstack.storage.command.AttachAnswer`: the plugin answered the volume
`AttachCommand` with a plain `Answer`, and `VolumeApiServiceImpl` casts the reply to `AttachAnswer` (a failure too). Fixed: the volume attach answers with `AttachAnswer` (the disk, or the message), the volume detach with
`DettachAnswer`; the tests now assert the answer types. Plugin: 265 tests pass.

Lab finding (2026-10-21, P7-3): resizing an attached data volume was refused by core with "Hypervisor Proxmox does not support volume resize" before any command reached the plugin: `VolumeApiServiceImpl` allows a resize only for
the hypervisors in `SupportedHypervisorsForVolResize`. Proxmox added to that list (upstream-notes.md, 18); the other KVM-specific checks near the resize, attach and detach paths do not affect Proxmox (read in the code). `VolumeApiServiceImplTest` (159) passes.

Lab note (2026-10-21, P7-8): as a regular user (account 4) adding the instance setting `dataDiskController` failed with the UI's generic "Unable to add or edit setting". Cause (read in the code, not the plugin): the global setting
`user.vm.readonly.details` (default `dataDiskController, rootDiskController, ...`) makes those settings read-only for non-admin callers (`UserVmManagerImpl.updateVirtualMachine`), so they must be set by an admin or the
setting must be changed. CloudStack itself writes both controller details as `osdefault` at deploy, which the plugin reads as the root disk's controller family.

Lab finding (2026-10-21, P7-8): attaching a volume to a running instance with `dataDiskController` = `sata` was refused as designed (nothing left pending), but the message said "stop and start the instance", which does not help: the attach is refused
again when the instance is running. The message now says to stop the instance, attach the volume while it is stopped and start it again (the drive is made at the start). Plugin: 265 tests pass.

Lab finding (2026-10-21, P7-10): the disk offering had read and write iops 50, read and write bandwidth 100 (bytes per second) and the two "max" (burst) bandwidth values 150. Proxmox showed read/write limits of 0.01 MB/s and 50 ops/s on the drive and no burst:
`ProxmoxVmConfig.driveOptions` mapped only the base limits, and converted bytes to megabytes with two decimals and a floor of 0.01 (about a hundred times too high for 100 bytes per second; Proxmox's own byte options would be worse, it
keeps three decimals and turns that into no limit). Now the burst values map to `mbps_rd_max`, `mbps_wr_max`, `iops_rd_max`, `iops_wr_max` with `*_max_length` (seconds), only when the base limit is set and the burst is not below it (Proxmox
refuses a burst without its base), and bandwidth keeps six decimals. Plugin: 267 tests pass. To verify: start an instance whose offering has bursts and check the drive in `qm config`.

Lab finding (2026-10-21, open issue 2): with a graceful shutdown started in Proxmox (`qm shutdown <vmid> --timeout 120`) on a guest that ignores it, a force stop from CloudStack failed with
`Proxmox task qmstop failed: can't lock file '/var/lock/qemu-server/lock-<vmid>.conf' - got timeout`. CloudStack serialises the jobs of one VM, so this only happens for a shutdown started outside CloudStack. Fixed in
`ProxmoxApiClient.stopVm` (`overrule-shutdown=1`, repeated without the option on an HTTP 400 for HA VMs; `ProxmoxApiClientVmTest` +3, plugin 270 tests pass). To verify: repeat the same test, the force stop should succeed within a few seconds.

Lab result (2026-10-21): the repeat of the forced-stop test passes: with a graceful shutdown started in Proxmox on a guest that ignores it, a force stop from CloudStack now succeeds (`overrule-shutdown`). Open issue 2 is closed.

## Phase 8 pre-build lab checks (2026-10-21)

Node `ref-trl-12501-k-mr8-prashanth-reddy-pve1`, token `pveaudit@pam!cloudstack` (role `PVEVMAdmin`), throwaway VM 9600 from the KVM system VM template (4.88 GiB, `template/tmpl/1/3/...qcow2`) with `agent=1`, `net0` on a test bridge `cstest0` (169.254.250.1/24), `net1` and `net2` on `vmbr0`. Commands were run on the node; the VM and the guest agent exec calls went through the REST API with the token.

| # | Check | Result |
|---|---|---|
| T1 | `POST /nodes/<n>/network` (bridge, address) and `PUT` (apply) with the token | **Refused**, HTTP 403 `Permission check failed (/nodes/<n>, Sys.Modify)` for both; `DELETE` too. Nothing changed on the node. The token cannot create the control bridge; the bridge was made by hand with `ifupdown2` (`ifreload -a`, no other interface touched) |
| S1 | `agent/ping` and `agent/file-write` by the token | Pass. The ping answered 200 at the 4th try (about 8 s after `qm start`), the written file was read back in the guest |
| S2 | `agent/exec` and `agent/exec-status` | Pass. Exit code 3 and stderr returned; a 5 s command took 6.4 s including the 1 s polling; **1.4 MB of output came back whole, not truncated** |
| S3 | 20 chunks of 48,000 bytes as base64 on stdin into `base64 -d >> /tmp/big` | Pass. `sha256sum` equal, **17 s for 960 kB (about 56 kB/s, about 0.85 s per call with a 1 s poll)**. A 10 MB patch file would take about 3 minutes at this rate |
| S4 | KVM system VM template booted with `agent=1` | Pass. `virt-what` says `kvm`, `qemu-guest-agent` active, the boot arguments written by the API at about 8 s were accepted: `cloud.log` says "Received a new non-empty cmdline file from qemu-guest-agent", `cmd_line.json` was made, `eth0` got the address from `eth0ip` and sshd listened on `169.254.250.10:3922` |
| S5 | NIC order | Pass. `eth0`, `eth1`, `eth2` carry the MACs of `net0`, `net1`, `net2` in that order |
| S6 | `PUT config net3=...,tag=100` and `delete=net3` on the running VM, by the token | Pass. HTTP 200 both; `eth3` appeared with the MAC of the configuration and went away on delete |
| T2 | From the node: `ping` and `ssh -p 3922` to the VM on `cstest0` | The VM answers ping (0.4 to 2 ms) and sshd answers on 3922. The key login was refused (`Permission denied (publickey)`): the test boot arguments had no `authorized_key=`, and the system VM only trusts the key it is given there (the management server's `ssh.publickey`), not the repository's `id_rsa.cloud`. The key login is to be shown with the real boot arguments in the 8a lab runs |
| S8, S9 | not run | S9: only `patch-sysvms.sh` (6,104 bytes) exists in this checkout; `agent.zip` and `cloud-scripts.tgz` need an installed management server |

Cleanup: VM 9600 destroyed. The bridge `cstest0` stays until it is removed by hand (the token cannot delete it): delete its stanza from `/etc/network/interfaces` and run `ifreload -a`. Unexplained: one SSH session of the tester dropped once after the first `ifreload -a` ("Can't assign requested address"); the node had not restarted and did not repeat it.

Lab result (2026-10-21, T1 second run): after `pveum role add CloudStackNet --privs "Sys.Modify"` and `pveum acl modify /nodes/<node> --users pveaudit@pam --tokens 'pveaudit@pam!cloudstack' --roles CloudStackNet --propagate 0`
(both entries listed by `pveum acl list`, one for the user and one for the token), the token did all of it with no other privilege, all HTTP 200: `DELETE /nodes/<n>/network/cstest0` (removes the hand-made stanza), `POST` of `cloud0`
(bridge, `cidr=169.254.0.1/16`, autostart, no ports) and one `PUT` that applied both changes as a task (`srvreload:networking`). Afterwards `cloud0` is up at 169.254.0.1/16, `cstest0` is gone, no `interfaces.new`
is left, and no SSH session dropped. **Side effect:** `diff /etc/network/interfaces /etc/network/interfaces.new` showed that Proxmox rewrote the whole file: the header comment and the comments on `vmbr0` and `vmbr1` were removed,
the stanzas were reordered and re-indented; the settings of every interface were kept. `cloud0` stays on the lab node (the Phase 8 build needs it).

### Control bridge at host add: implementation, unit tests (2026-10-21)

New: `ProxmoxControlBridge` (look for the bridge; create and apply it when missing; the messages), `ProxmoxApiClient` (`getNetworkConfig` with the pending `changes`, `createBridge`, `applyNetworkConfig`, `revertNetworkConfig`),
the setting `proxmox.control.bridge`, and the call in `ProxmoxServerDiscoverer.find` for every node that is added. Plugin: **300 tests pass** (`ProxmoxControlBridgeTest` 15, `ProxmoxServerDiscovererTest` +4, `ProxmoxApiClientVmTest` +4,
`ProxmoxConfigTest` updated). Not verified against a real node: that Proxmox puts `changes` next to `data` in the answer of `GET /nodes/<node>/network` when `interfaces.new` exists (the web interface does this, but the
pending-changes refusal depends on it): lab check N3 below.

### Phase 8a, first pieces: system VM start and the router deployer, unit tests (2026-10-21)

New: `ProxmoxApiClient` (`agentPing`, `agentFileWrite`, `agentExec` with a repeated `command` form value, `agentExecStatus`), `ProxmoxRouterDeployer` (core's `VirtualRouterDeployer` through the guest agent), `ProxmoxSystemVms`
(agent wait, boot arguments, readiness, `isSystemVMSetup`), `ProxmoxVmConfig` (`agent=1` and the control NIC on the control bridge for every VM that is not a user instance), `ProxmoxVmHandler.start` (control bridge check before, system VM set-up
after), and in `ProxmoxResource` the `VirtualRoutingResource`, `NetworkElementCommand` and `CheckSshCommand` handling. Plugin: **339 tests pass** (`ProxmoxRouterDeployerTest` 14, `ProxmoxSystemVmsTest` 9, `ProxmoxVmHandlerSystemVmTest` 5,
`ProxmoxVmConfigTest` +5, `ProxmoxApiClientVmTest` +7). Core is unchanged so far (it already treats Proxmox like KVM for traffic labels). Not run on a lab. Assumptions to check there: `ss` and `/opt/cloud/bin/patched.sh` exist in the template, a router's
sshd listens on 3922 when it is configured (the readiness check), and what the SSVM and CPVM do without the patch files.

### Phase 8 patch CD: lab checks P1 to P3 (2026-10-21)

Same node and token as the earlier Phase 8 checks (`PVEVMAdmin`, `PVEDatastoreAdmin` and `PVESDNUser`; no `Sys.Modify` needed).

| # | Check | Result |
|---|---|---|
| P1 | `POST /storage` (`type=dir`, `path=/var/lib/cloudstack-patch`, `content=iso`, `shared=0`) with the token | Pass. HTTP 200; the node made `/var/lib/cloudstack-patch` and `template/iso` as soon as the entry was active (`pvesm status`). No SSH, no manual directory. Not tried: a second node |
| P2 | Upload a 115,550,932 byte file named `*.iso` to `/nodes/<n>/storage/<entry>/upload` (`content=iso`) | Pass. HTTP 200 (a task, `imgcopy`) in 1.7 s; listed as `csp-lab:iso/cs-patch-test.iso`, `format iso`, the right size; SHA-256 of the stored file equal to the source. Proxmox accepts raw bytes under an `.iso` name |
| P3 | Put that file into the empty CD drive `ide3` of a running VM (created with `ide3=none,media=cdrom`) with `PUT config`, read it in the guest | **Medium change: pass.** HTTP 200, nothing pending (`qm pending` shows it as current), and the eject (`ide3=none,media=cdrom`) works the same. **Reading: the first six 16 MiB segments are identical to the source, stable on a second read; the whole-file hash differed because the guest sees whole 2048-byte sectors only** (`blockdev --getsize64` 115,550,208, the file is 115,550,932, so the last 724 bytes are cut). Fix: pad the payload to a multiple of 2048 bytes. The padded read is to be confirmed in the end-to-end test |

Cleanup done (VM, uploaded file, entry; the directory was removed by hand because Proxmox leaves it behind).

### Phase 8a, patch delivery: implementation, unit tests (2026-10-21)

New: `ProxmoxPatchFiles` (finds `agent.zip`, `cloud-scripts.tgz`, `patch-sysvms.sh`; SHA-512 kept until the file changes), `ProxmoxApiClient` (`createPatchStorage`, `listContent`, `uploadIso` with a multipart body streamed from the file and padded with zeros to whole
sectors), `ProxmoxStorageNames` (`csp-` entry, `cs-patch-<sha>.iso`), the setting `proxmox.patch.path`, `ProxmoxVmConfig` (an empty CD drive reserved for SSVM and CPVM), `ProxmoxRouterDeployer.writeBytes` (pieces to `<file>.part`, then `mv`), and in `ProxmoxSystemVms`
`beforeStart` (entry, upload, removal of old files) and the delivery after the boot arguments (state check, payload CD and checked copy of `agent.zip`, then the scripts last). Plugin: **371 tests pass** (`ProxmoxSystemVmsTest` rewritten, 27; `ProxmoxPatchFilesTest` 4;
`ProxmoxRouterDeployerTest` +1; `ProxmoxVmConfigTest` +3; `ProxmoxVmHandlerSystemVmTest` +2; `ProxmoxApiClientVmTest` +5; `ProxmoxStorageNamesTest` +2; `ProxmoxConfigTest`). Not run on a lab. To check there: that the padded read gives the exact `agent.zip`
(lab P3 stopped at the unpadded size), that `/dev/sr0` is the reserved drive in the template, a second node getting its own directory, and that an SSVM and a CPVM really come up and connect.

### Phase 8a, login keys for troubleshooting: implementation, unit tests (2026-10-22)

Lab finding (2026-10-22): a console proxy VM created on Proxmox (after `system.vm.default.hypervisor` was set to Proxmox and the CPVM destroyed) came up and the console works: the first system VM run on Proxmox. Login with the node's own key (`/root/.ssh/id_rsa` of pve2) to `169.254.56.178:3922`
was refused: the VM trusts only the management server's public key, which is not on the node.

Done: the global setting `proxmox.systemvm.ssh.key` (empty by default) and `ProxmoxSystemVms.installLoginKeys`, which appends the public key(s) to `/root/.ssh/authorized_keys` through the guest agent after the VM is configured and before the setup check (entries split on `|` or line breaks; an entry that is not
a public key line is skipped, nothing is run for a setting without a usable key, and a failure is logged without failing the start). Plugin: **378 tests pass** (`ProxmoxSystemVmsTest` +7, `ProxmoxConfigTest`). To see on the lab: the key is in `authorized_keys` of a restarted system VM and the login from the node works.

Lab finding (2026-10-22, SSVM on Proxmox): the secondary storage VM `s-49-VM` started on the Proxmox node, the log shows the agent file uploaded (94,543,872 bytes padded), the boot arguments, the patch files and "Added 1 login key(s)" (20:42:32, 20:42:42, 20:42:50), `StartAnswer` and `CheckSshAnswer` true, and the SSVM's own agent connected to the management server at 20:43:45 (about 55 s after the start answer);
template sync and `ReadyCommand` followed. So the patch CD path (agent.zip through the payload CD) works. **But the login from the node was refused** for this VM although the key was reported as added: the readiness wait looked for port 3922 to listen, which the template's sshd does from the start, so the key was
added before `cloud-early-config` wrote `authorized_keys`, which replaces the file with the management server's key only. Fixed: the plugin now waits until the unit `cloud-early-config` is "active" (finished) before adding keys. Also seen in the SSVM console: `I/O error, dev sr0, sector 0` once before the login prompt: the guest kernel probing the empty CD drive that is reserved for the agent file; cosmetic.
The start took about 57 s from the upload to the boot arguments (kernel time 42 s at the sr0 message), most of it the VM booting; a timing line per phase is now logged ("<vm> is ready N s after Proxmox started it: guest agent after ..., boot arguments ..., patch files ..., configured ..., login keys ..., setup check ...", and "The agent file for <vm> is on Proxmox node ... after N s") to see where the time goes. Plugin: 378 tests pass.

Lab finding (2026-10-22, SSVM restarts): (1) After setting the CPU type by hand (`x86-64-v2-AES`) and rebooting the SSVM, its agent connected as `s-49-VM` and no `hostname` timeout was logged; after deleting the setting again (default `kvm64`) and a **proper CloudStack stop and start**, it also connected and `agentstate` is Up. So the CPU type is not shown to matter; the restart is much lighter than a first boot (no agent copy and unpack), and the `hostname` race is still expected on a first boot (open issue 23: repeat the first boot three times to see how often).
(2) The attempt in between, `qm stop` followed by `cmk reboot systemvm` (CloudStack sends a reboot for a VM that Proxmox has stopped), left the agent unable to connect: the SSVM log shows `Connection reset` in the TLS handshake with the management server, repeatedly. A proper stop and start does the whole sequence, including `SetupCertificateCommand` (a new certificate imported into the VM, 21:16:53 to 21:17:27, 34 s), and then works. Do not use `qm stop` plus a CloudStack reboot to restart a system VM; use `cmk stop systemvm` and `cmk start systemvm`. The cause of the reset for the reboot path is not proven.
(3) Timeline of the proper restart (management server log, 21:15:57 start): boot arguments given 21:16:44 (47 s: Proxmox start and the VM booting up to its guest agent), "has the agent and the current scripts, nothing to patch" 21:16:45, login key added 21:16:52, `StartAnswer` 21:16:52, certificate set-up 34 s, agent Up about 3 minutes after the start. Most of it is the VM and the certificate import running slowly (single vCPU, nested lab), not the plugin's own steps; the per-phase timing line in the next build will show the split. The guest clock matched the management server's (21:19:15 and 21:19:39).

Lab result (2026-10-22, first boot of a new SSVM with commit 68b84a2d47, run 1 of 3): `s-50-VM` on pve1 came up, registered as `s-50-VM` (host row 19, no `hostname` timeout in its log, no "does not exist" warning), `agentstate` Up, and **the login from the node worked** (`ssh -i /etc/pve/priv/cloudstack_systemvm -p 3922 root@169.254.239.91` printed `s-50-VM`); `authorized_keys` has the management key and the troubleshooting key. Timeline from the management server log: agent file already on the node (0 s), the plugin's
timing line "s-50-VM is ready 68 s after Proxmox started it: guest agent after 40 s, boot arguments 0 s, patch files 9 s, configured 17 s, login keys 0 s, setup check 0 s" (so the VM booting up to its guest agent is 40 of the 68 s; the 94 MB agent file from the payload CD, its checksum and the scripts took 9 s), and the SSVM's own agent registered at 21:30:24, about 96 s after the start began (21:28:47). Runs 2 and 3, to see whether the agent-name race of open issue 23 shows on a first boot, are pending.

Lab result (2026-10-22, first boots of a new SSVM, runs 2 and 3 of 3, commit 68b84a2d47): both came up, registered under their own names (`s-51-VM` host row 20 on pve2, `s-52-VM` host row 21 on pve1), `agentstate` Up, no `hostname` timeout in the VM and no "does not exist" warning, `authorized_keys` with both keys, and the login from the node worked (`s-51-VM`, `s-52-VM`). With run 1 that is **three first boots out of three without the agent-name race** (open issue 23), after the earlier failure; not proof it cannot happen.
Timing lines: run 2 "ready 71 s after Proxmox started it: guest agent after 20 s, boot arguments 0 s, patch files 9 s, configured 39 s, login keys 0 s, setup check 0 s"; run 3 "ready 37 s ...: guest agent after 20 s, patch files 9 s, configured 6 s". So the time to the guest agent varies from 20 to 40 s, the patch step is a steady 9 s, and waiting for `cloud-early-config` varies from 6 to 39 s; from the start to the SSVM's own agent registering: run 1 about 96 s, run 3 about 66 s.
Seen in run 2: the agent file was checked on **pve1 at 21:37:01 and then on pve2 at 21:37:06**, so the first start attempt on pve1 did not complete and CloudStack tried pve2 (the SSVM ended on pve2, 143 s to registered). The reason for the first attempt's failure is not yet known (asked for the log lines).

Lab finding (2026-10-22, run 2, the first attempt on pve1): the start of `s-51-VM` on pve1 failed with `Proxmox task qmstart failed: volume 'cs-7fd5565c-...:115/vm-115-disk-0.qcow2' does not exist`; core cleaned up (`StopCommand`: "already stopped", nics and storage released), planned again and started it on pve2 five seconds later, where it worked. The root disk of a new VM is created on the node that received the volume command (here pve2) and the start was planned on another node (pve1); the new file was not visible there yet, most likely NFS directory caching of the primary storage on pve1. Nothing was left behind (`qm list` on pve1 shows no stray VM; the VM 115 there is the next SSVM). A start on a different node than the one that created the disk can therefore fail once and succeed on the retry on another host; with no other eligible host it would fail. Console proxy `v-48-VM` on pve2: the console works from the UI (reported).

### Phase 8b, virtual routers: implementation, unit tests (2026-10-22)

New: `ProxmoxRouterNics` (the `netN` entries of a router's configuration, the position of a MAC address), `ProxmoxRouterDeployer.prepareCommand` and `cleanupCommand` (interface numbers for `IpAssocCommand`, `IpAssocVpcCommand`, `SetupGuestNetworkCommand`, `SetSourceNatCommand`; hot-plug of a missing public interface and its removal when the last IP goes; `netusage.sh -a` and `-d`),
`ProxmoxNetworkUsage` (`NetworkUsageCommand` through `netusage.sh` and `vpc_netusage.sh`), `RebootRouterCommand` in `ProxmoxResource`, and in core a new setting `router.template.proxmox` (`VirtualNetworkApplianceManager`, registered in `VirtualNetworkApplianceManagerImpl`, mapped in `NetworkHelperImpl`; `NetworkHelperImplTest` +1, 17 pass) and `NetworkOrchestrator.prepareNicForMigration` (Proxmox routers). Plugin: **400 tests pass**
(`ProxmoxRouterNicsTest` 4, `ProxmoxNetworkUsageTest` 6, `ProxmoxRouterDeployerTest` +12). Not run on a lab. To see there: a router for an isolated network starts on Proxmox with guest `eth0`, control `eth1` and public `eth2`, DHCP, DNS and user data for a VM in the network, source NAT and static NAT, port forwarding, firewall rules, the traffic statistics, `reboot router` and a restart of the network; a public IP on another VLAN (hot-plug) and VPC are 8c.

Lab finding (2026-10-22, first router on Proxmox, isolated network): the router `r-54-VM` started on pve2 and its first commands ran (the file for the DHCP entry was created through the guest agent), but **every command that runs `update_config.py` failed** with `OSError: [Errno 75] Value too large for defined data type` in `CsAddress.py` `CsRpsrfs.enable` (`updatefile` on `/sys/class/net/<dev>/queues/rx-0/rps_cpus`), so "Unable to apply dhcp entry on router". Cause, read in the system VM scripts: `CsRpsrfs.cpus()` counts the lines of `/proc/cpuinfo` that contain the word "processor", and the model name of Proxmox's default CPU type `kvm64` is "Common KVM processor", so every CPU is counted twice;
a 1-vCPU router believes it has 2 and writes the mask `3` to `rps_cpus`, which the kernel refuses. On KVM (CPU model names without that word) it never shows. The same wrong count is in `ipassoc.sh` and twice in `setup/common.sh` (irqbalance). Fixed in the scripts (count the lines that start with "processor": `systemvm/debian/opt/cloud/bin/cs/CsAddress.py`, `ipassoc.sh`, `setup/common.sh`); the fix reaches a router through `cloud-scripts.tgz`, which is written at every router start, so the management server's copy in `/usr/share/cloudstack-common/vms/` must be rebuilt (`mvn -q package -DskipTests -Dsystemvm -pl systemvm`) and copied. A guest CPU type whose model name lacks the word (for example `x86-64-v2-AES`) also avoids it.

Lab result (2026-10-22, 8b, router with the fixed scripts): the router `r-54-VM` started and the DHCP entry command succeeded (no more `Errno 75`); an Ubuntu VM in the isolated network got no address at boot, but did when its DHCP client was run by hand, so the router's DHCP server and the path to it work. Cause: the template was made from a VM, and its `/etc/netplan/50-cloud-init.yaml` matches the NIC by the MAC address of that first VM (`match: macaddress: 02:01:00:d1:00:0b`, `set-name: ens8`); the new VM's NIC has another MAC (`02:01:00:ce:00:03`) and Proxmox names it `ens18`, so nothing configures it. A template problem, not the plugin's; fixed in the template.

Lab finding (2026-10-23, restart of the network with a new router `r-58-VM` on pve3): the router started and `StartAnswer` was fine, but the `CheckSshCommand` in the same batch was answered "The guest agent of r-58-VM does not answer" (the plugin pinged the agent once, at once after the start answer); core then did not send the other 12 commands ("Incorrect number of commands (14) and answers (2)") and failed with "error in finalizeStart, not retrying". The first router start had passed, so the agent of a freshly configured router can be unanswered for a short while. Changed: the check now pings every 2 s for up to 120 s, and logs the reason when it still fails (`ProxmoxResource`, `ProxmoxResourceTest` +4, plugin **404 tests pass**). To see on the lab: the same restart succeeds; if it still fails after 120 s the log line names the cause.

Lab result (2026-10-23, 8b, commits 977cc48e02 and 7062c0eda0): with the fixed scripts and the longer wait in `CheckSshCommand`, the virtual router of an isolated network works on Proxmox: restart of the network with cleanup (a new router on pve3), DHCP and DNS for a VM, outbound access through source NAT, static NAT, port forwarding, a firewall rule, `reboot router`, and the traffic statistics (`netusage.sh -g` in the router showed the guest-to-public and public-to-guest bytes, e.g. `33108:39776:0:0:`; the plugin sums them per direction, the polling and the `user_statistics` table were checked by the tester). 8b is done on the lab; a public IP on another VLAN (the hot-plug) is part of the 8c runs.

### Phase 8c, plug and unplug of interfaces, VPC: implementation, unit tests (2026-10-23)

New: `ProxmoxVmHandler.plugNic` and `unplugNic` (`PlugNicCommand`, `UnPlugNicCommand`: `net<device id>` on the bridge of the traffic label with the VLAN tag, adapter model from the VM details for an instance and virtio for a router, the next free number when the key is taken, repeated commands are a success, removal by MAC address), the wait for a newly plugged guest interface in `SetupGuestNetworkCommand`'s preparation, the dispatch in `ProxmoxResource`, and in core Proxmox in the list of hypervisors that support VPC (`VpcManagerImpl`, `VpcManagerImplTest` +1, 22 pass). Plugin: **417 tests pass** (`ProxmoxVmHandlerNicTest` 11, `ProxmoxRouterDeployerTest` +1). Not run on a lab.
To see there: a VPC with two tiers (the router starts, a VM in each tier gets DHCP, routing between the tiers, a network ACL), a public IP on another VLAN (an interface hot-plugged into the router), "add network" and "remove network" on a running instance, restart of the VPC with cleanup.

Lab finding (2026-10-24, 8c, VPC with two tiers): the VPC router started and DHCP works for a VM in a tier, but the VM's host name was not set and `cloud-init status` says "disabled". Cause (read in the KVM plugin and in cloud-init's detection, not yet seen on the lab): the KVM plugin gives each VM SMBIOS system information (manufacturer, product "CloudStack KVM Hypervisor", uuid, serial) from the `metadataManufacturer` and `metadataProductName` that core puts in the start command, and cloud-init's `ds-identify` finds the CloudStack data source by a product name that starts with "CloudStack"; the Proxmox plugin ignored both fields, so the VM's product name is Proxmox's default and cloud-init finds no data source and turns itself off. Changed: `ProxmoxVmConfig.smbios` sets `smbios1` (`uuid`, base64 `manufacturer`, `product`, `serial`, `base64=1`) at every start (`ProxmoxVmConfigTest` +3, plugin **419 tests pass**). To see on the lab: after a stop and start of the VM, `cat /sys/class/dmi/id/product_name` says "CloudStack Proxmox Hypervisor", `cloud-init status` is "done" and the host name is the instance's.

Lab result (2026-10-24 and 25, 8c, VPC): the VPC tests pass: two tiers and their VMs (once the network garbage collector settings were back to the defaults; with `network.gc.wait` and `network.gc.interval` at 20 s core shut a tier down while its VM was still being deployed and unplugged its interface from the router, which left both tiers `Allocated`), the router's interfaces, DHCP, DNS and cloud-init in each tier (after the SMBIOS fix), routing between the tiers, network ACLs, outbound access, public IP with static NAT, port forwarding and an ingress ACL, a public IP on another VLAN with its interface added to and removed from the router, "add network" and "remove network" on a running instance, user data and SSH key, `reboot router`, stop and start of the router, restart of the VPC with cleanup, and deleting a tier.
**Not passing: live migration of the router** (`migrateSystemVm` with a host): "Unsupported hypervisor: Proxmox for VM migration, we support [XenServer, KVM, VMware, Simulator] only". `MigrateSystemVMCmd` with a host calls `UserVmManagerImpl.migrateVirtualMachineWithVolume` (with an empty volume map), which first checks the hypervisor's functionality `VmStorageMigration`; Proxmox did not have it, though the call goes on to `migrateVirtualMachine` when no volume has to move (shared primary storage), the path that Phase 5 already runs for instances. A first attempt (commit 5c6ac50281) gave Proxmox the functionality `VmStorageMigration`; that only moved the failure to the next check, in `getHostsForMigrateVmWithStorage`: the storage motion capability of the hypervisor and its exact version (`hypervisor_capabilities.storage_motion_supported`, looked up by the exact version for every hypervisor except KVM, which falls back to any row): "Migration with storage isn't supported for source host ... on hypervisor Proxmox with version 8.4.21". Proxmox has only the `default` row, with `storage_motion_supported` 0 (the plugin does not implement the moving of volumes between storages yet, open issue 17; Proxmox itself can: `move_disk`), and the lookup for version 8.4.21 does not use the default row.
**Decision (2026-10-25): no change to core for this.** The flag was taken out again, and the live migration of a router (and of the SSVM and CPVM, which use the same API) is a known limit until storage migration is implemented in the plugin (open issue 37), when the capability row and `VmStorageMigration` can be set for Proxmox as for KVM.
**`vpc_netusage.sh` printed nothing**: expected, read in the scripts: for a VPC router the script's create option exits without making any rule (the function `create_usage_rules` is never called) and its `-g` reads a chain `NETWORK_STATS_<dev>` that nothing creates (the `NETWORK_STATS_<dev>` chains are only made for the public interface of the router of an isolated network, in `CsAddress.py`), so the answer is empty and the plugin reports zero bytes. This is how the system VM scripts are; the plugin runs the script as the KVM host does. VPC statistics from the router would need a fix in the scripts, not in the plugin (open issue 36).

### Phase 9 lab checks M1 to M8 (2026-10-28)

`move_disk` of a root disk and of a holder-owned data disk (stopped and live), the hand-over of a disk to and from an empty holder VM, the move of a holder VM to another node with an `unusedN` entry, adopting an existing holder volume, and `protection=1`: all pass with the token. Details and the commands: design-phase9-storage-migration.md section 8. Result for the design: a live move leaves the volume owned by the VM; a hand-over to the holder VM (the account number) works as a rename.

### Phase 9, the alias map (2026-10-29): unit tests

`ProxmoxVolumeAliasesTest` (7: parse/render, one-step lookup after a second rename, forget, capacity at 8000 characters), `ProxmoxHolderVmsTest` (+6: resolve, record keeps the text, forget, hand-over with alias, refusal before the rename when the description is full), `ProxmoxVmHandlerDataVolumeTest` (+4: attach, detach, delete and resize of a renamed volume). Plugin: **451 tests pass**. Not run on a lab (nothing renames a volume until the live move is built).

### Phase 9, step 2: offline copy of a detached data volume (2026-10-29): unit tests

`ProxmoxVmHandlerDataVolumeTest` (+3: copy via the holder entry with `delete=0` and the new path in the answer, refusal when the destination is the current pool, refusal without an image and nothing changed). Plugin total 454 pass, 0 fail. Lab check M9 (design section 12) not run yet.

### Phase 9, step 3: live move and hand-overs (2026-10-29): unit tests

`ProxmoxVmHandlerDataVolumeTest` (+4: live move answers the new path, live move to the same storage refused, detach of an instance-owned volume hands it to the holder and sets `volumePath`, delete of a volume a stopped instance still lists). Plugin total 458 pass, 0 fail. A bug found while writing: the start hook took the old root drive of a moved root disk for a stale data disk; the root volume is now told by path. Not run in the lab yet.

### Phase 9, lab M9 (2026-10-29): protected holder, run on pve1 by hand

Created `900000099` with `protection=1`, listed a volume as `unused0`, `move-disk` of `unused0` to the other NFS entry with `--delete 0` worked (copy as `unused0`, source as `unused1`); `--delete unused0` was refused: "protection mode enabled". Fix: `deleteListed` lifts protection around the removal (unit test `protectionIsOffOnlyWhileTheEntryIsRemoved`, plugin total 459 pass). The test VM `900000099` and its two volumes are still on the lab: `qm set 900000099 --protection 0 && qm destroy 900000099 --purge`, then remove `/mnt/<pool>/images/900000099` leftovers if any.

### Phase 9, lab T1 to T3 (2026-10-29)

- **T1 (M9, detached data volume to the other pool): pass** (after pulling the rebuilt jar; the first try ran the old build).
- **T2 (live move of a data volume): needed a capability row for the exact host version** `8.4.21`: core looks it up without the `default` fallback. With the row inserted, the live move worked. Step 5 must cover every version (plan: the discoverer inserts the row).
- **T3 (live move of the root volume): worked; Proxmox renamed the root disk to `106/vm-106-disk-1.qcow2`** because the target already had `disk-0` of that VM (the data volume). The data and root volume both had the path `106/vm-106-disk-0.qcow2` on different pools, which exposed lookups by path alone: fixed (design section 15), +3 unit tests (two volumes with one path: live move of each, detach), plugin total 462 pass.

### Phase 9, lab T4 (2026-10-29): first try failed

Detach of a live-moved data volume: `IllegalArgumentException: Illegal character in path` (the task id `...qmmove:106-unused0>900000002-unused0...`). Proxmox had already moved the disk, so core kept the old path. Fixed: task ids are encoded in the status URL (`pathSafe`, +1 test), `handBack` goes on when the holder lists the volume although the task could not be followed (+2 tests), and the running detach records an alias until the answer carries the new path. Plugin total 465 pass. T4 to be rerun with a fresh volume.

### Phase 9, lab T4 to T6 (2026-10-29)

- **T4 (detach of a live-moved data volume from a running instance): pass** on a healthy instance (after the task-id encoding fix and the safety net). The path core stores is the holder's.
- **T5 (live move, stop the instance, detach while stopped, start): pass.**
- **T6 (destroy an instance that owns a moved data volume, keep the volume): pass**, seen on instance 101: the volume went to the holder as `900000002/vm-900000002-disk-0.qcow2`, core kept the old path `101/vm-101-disk-1.qcow2`, and an attach to instance 122 resolved the alias (`scsi1` on the holder path). Core keeps showing the old path until the volume is deleted (known cost, design section 11).
- **First T4 attempt, not explained:** on an instance whose guest was in a kernel panic, the hot-unplug stayed pending (`del scsi1` in `qm pending`) and the plugin's undo got `HTTP 400 Parameter verification failed`, while `qm set <vmid> --revert scsi1` by hand worked. The error text now names the rejected parameter (change f86cacd645); the cause of the 400 is still open. Not reproduced on a healthy instance.
- Not run yet: T7 (attach, detach, delete of a volume that is still listed).

### Phase 9, vmid reuse (2026-10-29): unit test

Seen in lab T7 preparation: the path of a volume handed to the holder still names the destroyed instance's vmid. The alias map is now read before the image (design section 17); `anAliasWinsOverAnImageThatNowHasTheOldPathBecauseAnotherVmGotTheOldVmId`. Plugin total 467 pass. Not lab tested.

### Phase 9, stable path of a live-moved data volume (2026-10-29): unit test

`aLiveMoveOfADataVolumeKeepsItsHolderPathAndRecordsWhereTheDiskIs` (the answer is the holder path, the holder's description gets the alias). Plugin total 468 pass. To run in the lab: live move a data volume, check `listVolumes` path stays `9000000xx/...`, then T4, T5, T6 and the attach to another instance again.

### Phase 9, stale alias keys (2026-10-30): unit tests

`ProxmoxVolumeAliasesTest` (+1, one changed: a second rename drops the stop on the way, an alias starting at an id that is in use again is dropped). Plugin total 469 pass. Lab: the holder of account 2 showed two lines for one volume before the fix; old volumes are being deleted, to re-check with a fresh volume (one line per volume expected).

### Phase 9, lab rerun with the stable path (2026-10-30)

On the build with the stable data volume path (design section 18), alias-first lookup (17) and the stale key fix (19):
- **T2 (live move of a data volume): pass.** CloudStack still shows the holder path `900000002/vm-900000002-<uuid>.qcow2`; instance 122 has the root disk on pool A (`122/vm-122-disk-0.qcow2`) and the data disk on pool B (`122/vm-122-disk-0.qcow2`, the same path on another pool, which Proxmox's per-storage numbering allows); the holder's description has exactly one alias line, `<pool B>|<holder path>|122/vm-122-disk-0.qcow2`.
- **T6 (destroy the instance, keep the volume): pass.**
- **T7 (attach, detach, delete): pass.**

### Phase 9, step 4 (2026-10-30): unit tests

`ProxmoxDataMotionStrategyTest` (6: only two Proxmox hosts are handled; every volume that changes pool is moved, then the instance moves, volumes already on the destination pool are skipped and rows follow the answers; a failed volume move stops before the instance move; same host means volumes only; a destination that cannot receive fails before the move; a failed instance move is reported and rolled back). Plugin total 475 pass. Not run in the lab: it needs the `VmStorageMigration` flag (step 5).

### Phase 9, step 5 (2026-10-30): unit tests

`VmStorageMigration` claimed for Proxmox again (`HypervisorTypeFunctionalityTest`, 2), `ProxmoxServerDiscovererTest` (+3: a version without a row gets one with storage motion on, a version with a row is left alone, a failure does not stop the host). Plugin total 478 pass. Lab to run: T8 migrate an instance with a data volume to the other pool and another host; T9 migrate a router to another host.

### Phase 9, lab T8 and T9 (2026-10-30)

- **First try of T8** failed with "Unsupported hypervisor: Proxmox for VM migration": the lab was running a stale `cloud-api` jar (the commit with the `VmStorageMigration` flag was pulled but the `api` module was not rebuilt; a `javap` of `api/target/cloud-api-*.jar` showed `RootDiskSizeOverride` only). Rule for the lab: rebuild `api` when a commit touches it.
- **T8 (migrate an instance with its volumes to the other pool and another host): pass.**
- **T9 (migrate a router to another host, `migrateSystemVm`): pass** (open issue 37 closed).

### Phase 10, step 1: the SSH layer (2026-10-31): unit tests

`ProxmoxSshTest` (15: key then password, accepted key needs no password, refused login message, key or password needed, first connection reports the host key, a changed host key fails, root commands as they are, `sudo -n`, `sudo -S` with the password on standard input, the NOPASSWD message, a failed command carries its output, file written from standard input, quoting, fingerprint form). Plugin total 494 pass. The real connection class is not run against a node yet (Add Host, step 2). Lab checks X1 to X8 of design-phase10-ssh-access.md section 6: X1, X2, X4, X5, X6, X8 pass; X3 and X7 to repeat.

### Phase 10, step 2: Add Host with a login (2026-10-31): unit tests

One API token for the Proxmox cluster, kept in the CloudStack cluster's details. `ProxmoxApiAccessTest` (9: token made and read, old token replaced first, user and roles kept, every role given to user and token with its path, the node right on the node's own path, HA role only when HA is managed, a failure stops the setup, output that is not a token, revoke by id forgives a missing token) and `ProxmoxServerDiscovererTest` (one token serves the cluster and is kept with it, a later add reuses it, a token Proxmox no longer accepts is replaced, the login and host key are kept per host, no password kept when the key did the login, a node that refuses is skipped, a refused login on the given address fails the add, the last host deleted removes the token and the cluster's details, other hosts left keep it, a token that cannot be removed does not stop the delete, a host is rebuilt with the cluster's token or keeps its own). Plugin total 515 pass. Not run against a node yet.

## Proxmox 9 compatibility (2026-11-02)

Source comparison only, no lab run yet: see `design-phase10-ssh-access.md` section 9 (result: no change needed; the cluster and node calls, HA and the bridge ACL path are not in the mirrors and are the first things to check on a Proxmox 9 node).

## Console of a system VM, 2026-11-02

Console proxy VM 101 on pve3: `vncproxy` task ran on pve2 and ended `connection timed out`; pve3's `pveproxy` logged `connect to 'localhost:5900' failed: Connection refused`. User VM 103 on pve2 worked. Cause and fix: `design-phase10-ssh-access.md` section 10. Re-tested after commit 23d6f61d22 (2026-11-02): the console of the console proxy VM, the secondary storage VM and user VMs all open. **Pass.**

## Phase 10 Add Host and token, lab round (2026-11-02, Proxmox 8.4.21, three nodes)

After commits 2a1880f95d (audit role kept on the node path) and 1532997c74 (DELETE_HOST guards):

| Check | Result |
|---|---|
| Add Host with root and password finds every online node; one token `cs-<cluster>` in `pveum user token list cloudstack@pve` | Pass |
| `cluster_details` holds `username` (token id) and `password` (secret) | Pass |
| Start a VM with the token | Pass |
| Restart the management server: hosts reconnect (cluster token merged into the host's resource parameters) | Pass |
| Delete all hosts of the cluster: token removed on Proxmox, cluster details gone | Pass (after the guards) |
| Add the hosts again after the delete | Pass |
| Consoles of system VMs and user VMs | Pass (after 23d6f61d22) |

Run later the same day (commits 010df3f826, 6a8e038f43, 7642ac48d5; the management server's key from `ssh.publickey`, hidden from the UI, was added to `/root/.ssh/authorized_keys`; the System SSH Key choice and the empty password needed the UI and `verifyHostCredentials` changes): the system VM login key made by Add Host, key-only login, and the wrong password and non-root `csadmin` with sudo checks all **passed** (reported by the user as "all 3 worked").

Not run yet: Proxmox 9.

## Update Host password as the SSH login, the HA role always given, and the user cleanup (2026-11-03, Proxmox 8.4.21, three nodes)

Lab checks are in `design-phase10-ssh-access.md` sections 13 and 14, run after commits 12de235c17 (the resource gets the SSH host key) and 2d8d59b27b (user and roles removed with the last host). The token rotation rows (f, h, k) of the Phase 1 lab round above describe the earlier model, in which the administrator made the token by hand; they are history.

| Check | Result |
|---|---|
| 1. `updateHostPassword` without `update_passwd_on_host`: the login is stored, the hosts stay Up after a reconnect | **Pass** (2026-11-02) |
| 2. `updateHostPassword` with `update_passwd_on_host=true`, right and wrong password, on hosts added with a key | **Pass** (the first run failed with "no stored SSH host key"; fixed by 12de235c17, hosts re-added) |
| 3. Delete all hosts: the token, the user `cloudstack@pve` and the roles `CloudStackNet` and `CloudStackHA` are gone | **Pass** |
| 4. A single-host `updateHostPassword` is refused with the new text (the same SSH login is used on every node) | **Pass** (2026-11-02) |
| 5. `proxmox.ha.manage=true` with an HA offering: the VM is added to HA (`ha-manager`/`/cluster/ha/resources` shows it), no 403 | **Pass** |
| 6. `POST /nodes/<node>/termproxy` as the token is refused | **Pass**: HTTP 403 `Permission check failed (/nodes/<node>, Sys.Console)`. A first try showed HTTP 401 only because bash history expansion turned `\!` into a literal backslash in the token id (`set +H` fixes it); it proved nothing and was repeated |

## Instance snapshots (2026-11-03, Proxmox 8.4.21, NFS qcow2, commit 6a4481a6bc)

Needs `UPDATE cloud.hypervisor_capabilities SET vm_snapshot_enabled=1 WHERE hypervisor_type='Proxmox' AND hypervisor_version='default';` on an existing database, then a restart of the management server. "Disk-only" below means an **instance snapshot without memory** (`snapshotmemory=false`, taken on the instance's Snapshots tab); the Snapshot button of a volume is a different feature, not supported (see the note after the table).

| # | Check | Result |
|---|---|---|
| IS1 | Stopped instance with a data volume: create an instance snapshot | **Pass.** Ready; `qm listsnapshot 103` lists `i-2-94-VM_VS_20261103143733` with the description; `qm config 103 --snapshot ...` lists `scsi0` and the data volume `scsi1` |
| IS2 | Instance snapshot **without memory on a running instance**, after writing a file, then another file | **Pass.** Ready; the snapshot config has no `vmstate`; the instance kept running |
| IS3 | Running instance: snapshot with memory | **Pass.** Ready; `images/103/vm-103-state-i-2-94-VM_VS_20261103145019.raw` (481,862,656 bytes for 512 MB of RAM) beside `vm-103-disk-0.qcow2` |
| IS4 | Revert to the memory snapshot while running | **Pass** (reported as working; no output kept) |
| IS5 | Stop the instance, revert to a snapshot without memory | **Pass.** The instance stayed stopped and started fine. (A first attempt looked like a lost partition because the revert had gone to the first snapshot, taken before the partition existed; the revert was right) |
| IS6 | Delete the snapshots one by one | **Pass.** `qm listsnapshot` went from three snapshots to two to none, and the state file of IS3 was removed (only `vm-103-disk-0.qcow2` is left in `images/103/`) |
| IS7 | Two snapshots, expunge the instance and keep the data volume | **Pass.** `qemu-img snapshot -l` of the data volume file afterwards lists only `s2` and `s3` (11:11, from the manual `qm snapshot` tests before this build); none of the `i-2-94-VM_VS_...` snapshots the instance had on that disk is left. The two old ones are not ours to remove: no instance owned them any more |
| IS8 | Same, expunge the instance with the data volume | **Pass** (reported) |
| IS9 | Storage migration while the instance has snapshots | **Refused by core** with `Instance with Instance Snapshots cannot be migrated with storage, please remove all Instance Snapshots`, as expected. Attach, detach and resize were not tried (core refuses them with its own texts) |

Notes:
- **Volume snapshots were not supported at the time of this round (built on 2026-11-04, see the next section).** The Snapshot button of a data volume ends in `Unsupported command issued: org.apache.cloudstack.storage.command.CreateObjectCommand` (`DefaultSnapshotStrategy.takeSnapshot`), core's generic text. That was the "failure" of a first IS2 attempt; it was not an instance snapshot.
- Core refuses to resize a volume of an instance that has instance snapshots (`VolumeApiServiceImpl.validateNoVmSnapshots`), even though Proxmox itself allows it.
- Not measured: the growth of a data volume that carries snapshots, and a snapshot with memory of a larger instance (time).

## Volume snapshots (built and lab-verified 2026-11-04; checks before building V1 to V11 are in design-phase10-volume-snapshots.md section 7)

Unit tests: `ProxmoxVolumeSnapshotsTest` (34), two in `ProxmoxResourceTest`, one in `ProxmoxServerDiscovererTest`; plugin total 590 pass. **Lab checks, not run yet** (rebuild with `mvn install -DskipTests -pl plugins/hypervisors/proxmox,client`, restart the management server):

| # | Check | Expected |
|---|---|---|
| VS1 | Detached data volume with a file in it: Take Snapshot in the UI | Backed Up; a file under `snapshots/<account>/<volume id>/` on secondary; `qemu-img snapshot -l <volume file>` lists nothing; one `/mnt/css-rw-*` mount |
| VS2 | Data volume of a **running** instance: write a file, take the snapshot, write another file | Backed Up; `qm listsnapshot <vmid>` has no `cs-...` entry afterwards; the instance kept running; `qemu-img convert` of the backup shows only the first file |
| VS3 | Volume snapshot of the root volume of a stopped instance | Backed Up, nothing left in the root file |
| VS4 | Create Volume from the snapshot of VS2, attach it to an instance | The volume file is `images/<holder>/vm-<holder>-<uuid>.qcow2`; the disk holds only the first file; `pvesm list` shows it |
| VS5 | Stop the instance, Revert the data volume to the snapshot | The first file only; the instance starts; `qemu-img snapshot -l` of the volume is empty |
| VS6 | Grow the volume (resize in the UI), then Revert | The data of the snapshot, and the disk is the **size of the snapshot again**: `qemu-img info` of the file and `volumes.size` in the database both show the old size (corrected 2026-11-05: the first version expected the grown size) |
| VS7 | Delete the snapshot in the UI | The record goes; the file on secondary goes (secondary storage VM) |
| VS8 | `sudo mount -o remount,ro /mnt/css-rw-<...>` on the node, take a snapshot, then remount `rw` | A clear error in the UI; no snapshot is left in the volume file (`qemu-img snapshot -l`) |
| VS9 | Take a second snapshot | No second mount (`findmnt \| grep css-rw`) |
| VS10 | An instance snapshot exists: take a volume snapshot of its volume, then revert | The refusals of core, with its text |
| VS11 | Recurring snapshot policy with a short interval, and updateHostPassword to a new SSH password, then a snapshot | A scheduled run succeeds; the snapshot after the password change works without a restart |
| VS12 | **Root volume of a running instance** (write a file in the guest, take the snapshot, write another), then Create Volume from it | Backed Up; `qm listsnapshot <vmid>` has no `cs-...` entry afterwards; the backup is a bootable disk holding only the first file; the instance kept running |

**Results (lab, 3 nodes, 2026-11-04): VS1 to VS12 pass.**

| # | Result |
|---|---|
| VS1 | Backed Up; file under `snapshots/2/<volume id>/` on secondary; no internal snapshot left in the volume file |
| VS2 | The backup (read after a read-write loop mount, so the journal of the crash-consistent copy can replay) holds the files from before the snapshot and not the one written after; `file.txt` was already on the disk. A read-only loop mount of the same copy fails with "wrong fs type", which is the unreplayed journal and not a bad backup |
| VS3 | Root volume of a stopped instance: nothing left in the root file (`qemu-img snapshot -l` empty) |
| VS4 | Volume file `images/900000002/vm-900000002-<uuid>.qcow2`, listed by `pvesm list`; the guest sees only the files from before the snapshot |
| VS5 | Revert works; only `first.txt`; no `.tmp` and no internal snapshot left |
| VS6 | **Passed wrongly, then corrected (2026-11-05).** After growing to 10 GiB and reverting, the file was 10 GiB and the data the snapshot's, but core had set `volumes.size` back to 5 GiB (5368709120), so file and database disagreed; found by SM2, when "create volume from snapshot" refused to cut a 10 GiB backup to the 5 GiB that core records. The revert no longer resizes (design-phase10-volume-snapshots.md, decision 5). To repeat VS6 on a fresh volume after the fix |
| VS7 | Delete removes the record and the file on secondary |
| VS8 | With the secondary mounted read-only separately (`mount -o ro,nosharecache`; a plain `remount,ro` is refused on the lab because the secondary export is a subdirectory of the primary export and the two share one kernel mount): the snapshot fails (UI text is core's generic "internal error creating Snapshot"), nothing is left in the volume file; the next snapshot after `umount` works (the plugin mounts read-write again) |
| VS9 | A second snapshot makes no second mount |
| VS10 | Running instance: "The Instance the specified disk is attached to is not in the shutdown state"; stopped with an instance snapshot: "Unable to revert Snapshot for Instance, please remove Instance Snapshots before reverting Instance from Snapshot" (both core's text) |
| VS11 | After `passwd` on the nodes, snapshots failed until `updateHostPassword` was called with the cluster id **and `update_passwd_on_host=true`**: each node logged "The SSH login csadmin works on Proxmox node ..." and the next snapshot was Backed Up without a restart. Without that parameter core only writes the database and the running plugin keeps the old password (open-issues.md). The recurring-policy half was not run |
| VS12 | Root volume of a running instance: Backed Up; `qm listsnapshot 103` has no `cs-` entry afterwards; the volume made from it holds only the first file |

## Secondary mounts without storage entries (design-phase11-secondary-mounts.md; lab checks M1 to M6, 2026-11-05, not run yet)

Scratch VMs 9001 (ISO) and 9002 (template disk) are made by hand with `qm` and removed afterwards; CloudStack is not involved.

| # | Check | Expected |
|---|---|---|
| M1 | Plain mount of the secondary export with `ro,soft,timeo=30,retrans=2,nosharecache` as the SSH user with sudo | `findmnt` shows `ro` and `soft`; an ISO and a template file are readable |
| M2 | Scratch VM with `ide2=<path on the mount>,media=cdrom`: start, eject, attach again while running | All succeed; QEMU has the file open (`info block`) |
| M3 | With that ISO: online migration to a node without the mount, then with it; instance snapshot without and with memory | Records what Proxmox does (a refusal without the mount is expected) |
| M4 | A separate NFS server (pve1) holds a copy of the ISO, mounted `soft` on pve3 and attached; block the server | `ls`, `pvesm status`, `qm status` and the VM stay responsive; the mount gives an error within about 30 s |
| M5 | `qemu-img convert` a template from the mount into `images/9002/vm-9002-disk-0.qcow2`, create the VM with that disk, start it | Starts; time compared with `import-from` (7.6 s for 8 GiB) |
| M6 | `qemu-img info` of an image with a backing file and of one with a data file | Both show up in the JSON, so the plugin can refuse them |

**Results so far (2026-11-05):** M1 pass. M2 **fail**: `qm create ... --ide2 /mnt/mtest/...iso,media=cdrom` gives "unable to associate path ... to any storage"; Proxmox accepts a path for a CD drive only inside the `iso` directory of a storage entry (design-phase11, section 6). M2b (bind mount of the ISO file into the `iso` directory of a local `dir` storage, then `<storage>:iso/<name>.iso`) replaces M2 to M4 for the ISO; M5 and M6 (template) are not affected.
M2b **pass**, M3 **pass** (2026-11-05): ISO bound into `local`'s `iso` directory attaches, boots, ejects and re-attaches; online migration needs a `shared 1` directory entry (`csiso`) because `local` makes the ISO a local disk; with `csiso` and the same bind on both nodes: migration both ways (9 s, 4 to 5 ms downtime) and snapshots with and without memory work. Details in design-phase11 section 6.
M4 **pass** (2026-11-05): with the NFS server blocked (DROP) the node's Proxmox commands and services stayed responsive and the VM kept running; a read of the ISO failed with an I/O error after 99 s (`soft,timeo=30,retrans=2`). Details in design-phase11 section 6. M5 and M6 still to run.
M5 **pass**, M6 **pass** (2026-11-05): a 5 GB (1.67 GiB on disk) template converted from the soft read-only mount into `images/9002/` in 29.9 s and the VM created with that disk starts; backing file and data file both show in `qemu-img info --output=json`. Lab checks M1 to M6 are done; design-phase11 section 6 has the summary and what changed.
M7 **pass** (2026-11-05): mount in a unique directory, bind one ISO file, unmount the directory: no "busy", the bind keeps reading (4,194,304 bytes), and unmounting the bind releases the NFS mount (7 NFS mounts of the store before, 6 after). Six other NFS mounts of the store were still there on pve3 (what they are was not looked at).

## Secondary mounts, step 1 (built 2026-11-05)

Unit tests: `ProxmoxSecondaryMountsTest` (31: the mount per operation and its options, the setting's allowed list and refusals, a failed mount, a failed action, a failed release, the one-time cleanup, the image check, paths), the volume snapshot tests follow the new mount commands (36), and the setting is in `ProxmoxConfigTest`; plugin total 621 pass. **Lab checks, not run yet** (volume snapshots must still work, now with a mount per operation):

| # | Check | Expected |
|---|---|---|
| SM1 | Take a volume snapshot of a detached data volume (VS1 again) and watch the node during it: `findmnt -t nfs4 \| grep css-tmp` | A `/mnt/css-tmp-<uuid>` mount with `rw,...,soft,timeo=30,retrans=2,nosharecache` while it runs; nothing after it and no `/mnt/css-tmp-*` directory left |
| SM2 | Create a volume from the snapshot and revert (VS4, VS5 again) | The same with `ro`; both work |
| SM3 | Change `proxmox.secondary.mount.options` to `soft,timeo=10,retrans=1`, take a snapshot | The mount shows the new options |
| SM4 | Set it to `soft;reboot` | The snapshot fails with the text that names the option; nothing was run on the node |
| SM5 | Make a directory `/mnt/css-tmp-old` older than 10 minutes on a node (`touch -d '20 min ago'`), restart the host connection (disconnect and reconnect the host in the UI), take a snapshot | The directory is gone |
| SM6 | A snapshot with the NFS server blocked (separate server, as M4), then released | The snapshot fails with an error within about 2 minutes; no mount and no directory left on the node; the next snapshot works |
| SM7 | VS6 again, on a fresh 5 GiB data volume, after the revert fix: take a snapshot, grow the volume to 10 GiB in the UI, stop the instance (or detach), revert | After the revert `qemu-img info` of the file shows 5 GiB **and** `select size from volumes where uuid='<volume>'` shows 5368709120 (both, not the file alone); then take a snapshot and create a volume from it: it works without any cutting |

**Results (lab, 3 nodes, 2026-11-05):** SM1 to SM5 and SM7 pass; SM6 skipped (the lab has one NFS server for primary and secondary, so a dead secondary cannot be simulated without a second store; M4 measured the node side and the unit tests cover the failure paths).

| # | Result |
|---|---|
| SM1 | Seen through the sudo journal, not by polling (the operation takes under a second, and ran on pve1, not on the node being watched): cleanup of old directories, `mkdir` and `mount -t nfs -o rw,soft,timeo=30,retrans=2,nosharecache` (no `vers=`: this store has no NFS version), `mkdir` of the snapshot folder, `qemu-img convert -U` of the running instance's `cs-` snapshot into the unique directory, `stat`, then `umount` and `rmdir` of the same directory; plugin log "Copied snapshot ... (7733248 bytes)"; afterwards no `css-tmp` mount and no directory |
| SM2 | Create volume from a snapshot (pve2) and revert (pve1) mount `ro,soft,timeo=30,retrans=2,nosharecache` and release the same directory; the file is 5 GiB and `volumes.size` and `snapshots.size` are 5368709120. **It first failed** on the volume that VS6 had left with a 10 GiB file and a 5 GiB record ("holds data beyond that size: it is not cut"), which led to the revert fix (design-phase10-volume-snapshots.md, decision 5); the pass is on a fresh volume after the fix |
| SM3 | With the setting `soft,timeo=10,retrans=1` the mount is `rw,soft,timeo=10,retrans=1`, applied to the next mount without a restart |
| SM4 | With `soft;reboot` the snapshot fails and the plugin log names the option (`The setting proxmox.secondary.mount.options has the option 'soft;reboot', which ...`). Not pasted: that no node ran a `css-tmp` command and that no `cs-` snapshot is left on the instance (`qm listsnapshot`) |
| SM5 | A directory `/mnt/css-tmp-old` (20 minutes old) on each node: after the hosts were reconnected and a snapshot taken, pve1 (which took it) had lost its directory; pve2 and pve3 still had theirs, as designed (the cleanup runs on the first use of each node). The count of the cleanup command in the journal printed 0 only because the journal escapes the quotes of the command (the check was wrong, not the plugin) |
| SM7 | A 5 GiB volume grown to 10 GiB (file 10 GiB), reverted to the snapshot from before: file 5 GiB; a new snapshot of it and a volume created from that: 5 GiB, no cutting. The query of `volumes.size` for the grown volume was not pasted; the 5 GiB of the later snapshot and volume show that the database had 5 GiB |

## Templates as Proxmox template VMs, step 2 (built 2026-11-05)

Unit tests: `ProxmoxTemplatesTest`, `ProxmoxVmHandlerTemplateTest`, the new client calls in `ProxmoxApiClientVmTest`, two dispatch tests in `ProxmoxResourceTest`; plugin total 658 pass. Lab checks TV1 to TV10 were run on 2026-11-05 (results below; the first run found that the path was wrong, see the finding). Check the node with the sudo journal (`sj` of the secondary mount checks) and the Proxmox UI; check CloudStack with SQL as well as the node. The expectations below are the ones after the fix of the path (the first run expected `secondary:...` in `install_path` and a re-made template VM in TV6):

| # | Check | Expected |
|---|---|---|
| TV1 | Register a user template (qcow2) and deploy an instance from it, in a zone with one pool | In Proxmox a template VM `cs-tmpl-<uuid>` (template icon) appears on a node; `qm config <vmid>` shows `template: 1` and a `base-<vmid>-disk-0.qcow2` disk; the instance is a normal VM with its own disk; `install_path` and `local_path` of the `template_spool_ref` row are the base disk in the pool, `<vmid>/base-<vmid>-disk-0.qcow2` (the vmid of the template VM), and the `scsi0` of the template VM is `cs-<pool uuid>:<that path>` |
| TV2 | Deploy a second instance from the same template | No `css-tmp` mount and no `qemu-img convert` in the sudo journal of any node (no SSH at all), the clone takes seconds to a minute; `volumes.size` equals the disk's virtual size |
| TV3 | Deploy with a root disk bigger than the template (resize at deploy) | The disk image is the bigger size (`qemu-img info`) and `volumes.size` is the same |
| TV4 | Deploy from the system VM template (the SSVM template of Phase 8) and start the instance | Works as before; the console proxy / SSVM still come up |
| TV5 | A template whose image has a backing file (`qemu-img create -b ...`) registered | The copy fails with the message "has a backing file"; no VM is made |
| TV6 | `qm destroy` the template VM by hand, then deploy again | The deploy fails with "Proxmox has no template VM with the disk ... removed outside CloudStack ... remove the template from the primary storage (its row in template_spool_ref)"; nothing is mounted or converted. After the row is deleted, the next deploy copies the template again and works. The eviction of such a template (storage cleanup) is a success and clears the row |
| TV7 | Eviction of an unused template: with `storage.cleanup.enabled` and `storage.template.cleanup.enabled` on, delete all instances from a template and wait for two cleanup cycles (or set `storage.cleanup.interval` low) | The template VM is removed from Proxmox and the `template_spool_ref` row is gone; a template with a live instance is not touched |
| TV8 | An existing instance from before this change starts, stops, and is deleted | Works; its template path is of the old `cst-` form and nothing is made for it |
| TV9 | Deploy from an old template (old `cst-` path) while its entry exists | Works with the old `import-from` |
| TV10 | Ten deploys from a template that is not yet on the pool, in parallel | One template VM (core's lock), ten clones, no failures; name check with `qm list \| grep cs-tmpl` |

### Results of the first run (2026-11-05, with the secondary location in `install_path`)

| # | Result |
|---|---|
| TV1 | **Works, but the path in the database was wrong.** Template VM `cs-tmpl-a40a72c1-...` (104, template 1, `scsi0 ...:104/base-104-disk-0.qcow2,size=100G`) made on pool `f7c5790d`; the root volume `105/vm-105-disk-0.qcow2` is 100 GiB with no backing file (full clone). But `template_spool_ref.install_path` held `secondary:|10.0.32.4|/acs/...|template/tmpl/2/219/....qcow2`: the location on secondary, not on primary. **Finding:** on KVM `install_path` and `local_path` hold the template's place in the pool (for full and linked clones alike). Fixed: both now hold `<vmid>/base-<vmid>-disk-0.qcow2` (behaviour.md section 25, design-phase11). To check again after the fix. Two `Made the template VM ... (104)` lines (14:40:58 and 14:41:32, two different mount directories) appeared under one log id for the same VM id: to explain (see the open question below) |
| TV2 | Pass. A second pool made a second template VM (`cs-tmpl-6a065a14-...`, 106); a third deploy on the first pool made nothing more. The sudo journal showed the read-only soft mount, `test -f`, `qemu-img info`, `qemu-img convert`, `umount` |
| TV3 | Pass. Template 100 GiB, root disk 120 GiB: the image is 120 GiB |
| TV4 | Pass for the copy: after the rows of the system VM template were deleted and the console proxy VM destroyed, a new row (template 214, pool 7) was `Ready` with a `secondary:` path (the old path form; to check again with the new form). That the new console proxy VM came up was not pasted |
| TV5 | Not reached: the registration itself failed ("Failed post download script: The downloaded file is not a valid QCOW2"), because core's check on the secondary storage VM refuses an image with a backing file before the plugin sees it. The plugin's own check (`has a backing file`) is a second guard and is covered by unit tests; no VM was made |
| TV6 | The template VM was found missing and made again from the store in the path (log "Proxmox has no template VM ... making it again", sudo journal: mount, `test -f`, `qemu-img info`, `qemu-img convert`, `umount`), for the system VM template and for a user template. **This behaviour is gone** with the new path (the deploy now fails with a message, see TV6 above). Note: `qm destroy 101 --purge` printed `chattr: Operation not supported` on the NFS file; Proxmox goes on ("purging VM 101") and the template VM was gone. To check: that `images/101` is empty on the pool after it |
| TV7 | Pass. Rows with `marked_for_gc=1` were evicted at the cleanup: log "Removed the template VM cs-tmpl-6a065a14-... (101) from the pool" and "Successfully evicted Template"; the row and the template VM were gone; the unmarked rows and their VMs stayed |
| TV8 | Pass. An instance from before the change starts, stops and is deleted; no new template entries |
| TV9 | Pass. A deploy from an old template with its `cst-` entry worked |
| TV10 | Partly shown: after the parallel deploys the pools have exactly one template VM per template and pool (three VMs on pve1, pve2 and pve3 for template 219 on pools 7 and 8 and template 220 on pool 7; `template_spool_ref` rows 29 and 30 for template 219), no duplicates. The number of failures among the deploys was not pasted |

**Open question from TV1:** the two `Made the template VM` lines for VM 104 under one log id. Asked for the lines of that log id (`grep c817038d jetty.log | grep -E "ProxmoxTemplates|ProxmoxVmHandler|ERROR|WARN"`) to see whether the first template VM was destroyed between them (a failed first attempt) or the copy ran twice.

### Results of the second run (2026-11-05, with the path on primary)

| # | Result |
|---|---|
| TV1 / TV2 | **The path is right** (`template_spool_ref`: `install_path` = `local_path` = `101/base-101-disk-0.qcow2`, Ready). **Bug found:** the first deploy on a pool failed twice right after the copy (`Proxmox has no template VM with the disk 101/base-101-disk-0.qcow2 ...`, 0.4 s after `Made the template VM`), on pool A at 22:43:51 and on pool B at 22:44:56, and the third attempt worked: the deploy took 3 minutes and copied the template to two pools. Cause: the template flag and name were read from the cluster list (`/cluster/resources`), which shows them only after the status daemon of Proxmox has reported the VM. **This is also the double `Made the template VM (104)` of the first run:** the VM was taken for "not a template", destroyed as a leftover and made again with the same id. Fixed: the template VM is checked from its configuration (behaviour.md section 25); unit tests added (`aTemplateVmIsFoundFromItsConfigurationWhenTheClusterList...`). To check again: one `Made the template VM` per deploy and a first deploy that works |
| TV4 | Console proxy VM is Running. Two template VMs for the system VM template (pools 7 and 8, rows 34 and 35, each `...base-<vmid>-disk-0.qcow2`, 5G): a copy per pool is by design, but one deploy making both is the symptom of the bug above (a failed first attempt, the planner then tries the other pool). To check again: one copy only |
| TV6 | Pass. `qm destroy` of the template VM left no `images/101`; the deploy failed with the message ("removed outside CloudStack ... template_spool_ref"), the journal shows a copy for the other template that the planner tried, none for this one; no `tv-e` VM was left; after deleting the row the deploy copied again (mount, convert, `mv`, `umount`) and worked, new row `101/base-101-disk-0.qcow2` |
| TV7 | Pass: with `tv-f` alive the template VM and row stayed; after the expunge and two cycles the log shows `Removed the template VM ... (109) from the pool` and `Successfully evicted`, and the VM and row were gone. The eviction of a template whose VM was removed by hand answered `nothing to remove` and cleared the row. The failure of `tv-g` at 23:28:15 is the bug of TV1 (a copy to a pool that had no row, then no template VM found); its second attempt worked |
| TV10 | One row per pool (`109/...` on pool 7, `110/...` on pool 8) and one `Made the template VM` per pool, but **2 of 10 deploys failed**: `Proxmox task qmclone failed: clone failed: cfs-lock 'storage-cs-7fd5565c-...' error: got lock request timeout`. The plugin now tries a clone that fails with that error again with a new VM id (unit test added); to check again with the ten parallel deploys, and if some still fail, limit the clones that run at once per pool (open-issues.md) |


### Results of the third run (2026-11-06, with the template VM read from its configuration and the clone retry)

| # | Result |
|---|---|
| TV1 | Pass. One `Made the template VM cs-tmpl-e0ae7df7-... (101) on cs-f7c5790d-...: 101/base-101-disk-0.qcow2` for the deploy, and no failed first attempt (before the fix: two failures and a copy to two pools). The path in the log is the one core keeps |
| TV4 | Pass: after the system VM template rows were deleted and the console proxy VM destroyed, one copy only (before the fix: two); the row is `103/base-103-disk-0.qcow2` in both columns, pool 7, Ready. The state of the new console proxy VM was not pasted |
| TV10 | Pass: 12 parallel deploys, all succeeded (before the fix: 2 of 10 failed on the pool lock). The log has 0 lines with `lock request timeout` or `locked command timed out`, so the pool lock was not hit this time and **the retry was not exercised on the lab** (it is covered by unit tests only); not pasted: how long the deploys took |

## ISOs as bind mounts, step 3 (built 2026-11-06)

Unit tests: `ProxmoxIsosTest` (14), `ProxmoxVmHandlerIsoTest` (29), the client, names and config tests; plugin total 680 pass. **Lab checks, not run yet** (behaviour.md section 13). Set `proxmox.iso.path` is left at its default; check the node with `sj`, `mount | grep cloudstack-iso`, `pvesm status` and the VM configurations, and CloudStack with the instance's state:

| # | Check | Expected |
|---|---|---|
| IS1 | Register an ISO, attach it to a **stopped** instance and start it | `pvesm status` shows one new entry `csiso-<uuid>` (type dir, shared), `mount \| grep cloudstack-iso` shows one bind `.../template/iso/cs-<16 hex>.iso` on the node that ran it, no `css-tmp` directory and no NFS mount of the secondary store is left (`findmnt -t nfs4,nfs`), `qm config <vmid>` shows `ide2: csiso-...:iso/cs-....iso,media=cdrom`, and the guest sees the CD (`blkid /dev/sr0`) |
| IS2 | Attach a second, different ISO to a second CD slot (`vm.iso.max.count` raised) of the same instance | A second bind and drive `ide3`; the first is untouched |
| IS3 | Attach the same ISO to a **running** instance that has an empty CD drive; detach it | Attach: the guest sees the disc at once. Detach: the drive is `none,media=cdrom`, the bind is gone from `mount` (no other VM has it), and the file is gone from the directory |
| IS4 | The same ISO attached to two instances; detach it from one | The bind stays (the other VM's configuration names it); after the second detach it is gone |
| IS5 | Deploy an instance from an ISO (boot from the ISO) and finish a boot | The blank root volume is made as before, the VM boots from the CD |
| IS6 | Online migration of an instance with an ISO attached, to another node and back | `PrepareForMigrationCommand` makes the bind on the destination first (`mount` there), the migration works, the guest still reads the CD; the instance of a node without the ISO bound is refused with the reason when the NFS export is not reachable from the destination (stop the export route for that node) |
| IS7 | Reboot the node that runs an instance with an ISO, start the instance again from CloudStack | The start makes the bind again and works |
| IS8 | Attach an ISO whose store cannot be mounted from the node (block the NFS server with `iptables` on that node, or use an ISO of a store the node cannot reach) | The attach and the start fail within about a minute with the reason and "Detach the ISO ..."; nothing is changed in the VM; the node and `pvesm status` stay responsive |
| IS9 | Remove the ISO file from the store behind the ISO, then attach it | Fails with "does not exist on the secondary store" |
| IS10 | Leave an ISO attached, expunge the instance, restart the agent (restart the management server or reconnect the host) and attach another ISO | The first attach after the restart removes the bind that no VM names (sweep: `mount` shows only the binds in use) |

### Results of the first run (2026-11-06)

| Test | Result |
|---|---|
| IS1 | **Pass.** One `csiso-6cbdeba0-...` entry (dir), one bind `cs-f931e8389c2189dc.iso` with `ro,soft,nosharecache`, `ide2: csiso-...:iso/cs-....iso,media=cdrom`, no `css-tmp` directory; the guest saw the CD (as `/dev/sr2`, the first two slots are the empty CD drives) |
| IS2 | **Pass.** Two binds, `ide2` and `ide3` hold the two ISOs |
| IS3 | **Pass.** Attach to a running instance: bind made, disc seen at once; detach: `ide2: none,media=cdrom`, that bind gone, the other two stay |
| IS4 | **Pass** for the shared bind (both instances on one node: one bind, it stays after the first detach). **Finding:** with the instance *stopped* both ISOs were detached and the binds stayed, also after 10 minutes. Cause: CloudStack sends no command to the host to detach an ISO from a stopped instance (the DB changes only), so the plugin never knew. Fixed: a start that no longer lists an ISO removes its bind, and the sweep now runs before every bind (see below) |
| IS5 | **Pass** for the boot from the ISO (blank root disk 5G, `ide2` holds the ISO, one bind). **Finding:** the bind stayed after the instance was expunged. Fixed: the delete of the VM removes the binds of the VM's configuration when the delete reaches the VM's node, and the sweep removes the others |
| IS6 | **Pass** for the move: no bind on the destination before, the bind there during the migration, the move back worked. **Finding:** the bind stayed on the source node. Fixed: after a successful `MigrateCommand` the source removes the binds the VM's configuration named (the check is now per node: a bind is only used by the VMs of its own node). The blocked-NFS step did not fail at once: `PrepareForMigrationCommand` waits for the temporary mount, which `timeout 60` ends after a minute (the mount client retries for up to two minutes by default); not run to its end |
| IS7 | **Pass.** After `umount` of the bind and a start from CloudStack the bind is back (`sj` shows the temporary mount, `mount --bind`, the check, the unmount; the management log has no line for it because the plugin logs only the entry creation and the attach) |
| IS8 | **Not valid.** The instances were *stopped*: an attach to a stopped instance sends no command to the host either, so nothing ran. Repeat with a **running** instance on the node whose NFS access is blocked |
| IS9 | **Pass.** "Failed to attach ISO" with the file missing from the store |
| IS10 | **Pass** for the sweep (after the host reconnect and an attach of another ISO the old bind was gone). **Finding:** the bind stayed after the expunge, as IS5 |

Changes after this run: the names carry CloudStack's UUIDs (`cs-<ISO uuid>.iso` for the bind, `cs-tmpl-<template uuid>-<8 characters of the pool uuid>` for the template VM); a bind is removed after a migration, a delete and a start that does not list it; the sweep runs before every bind (not only the first) and skips binds made in the last 10 minutes; a move before a start that Proxmox refuses fails with the VM, both nodes and the reason.

### Results of the second run (2026-11-06, after the uuid names and the bind clean-up)

| Test | Result |
|---|---|
| Names | **Pass.** Template VM `cs-tmpl-3f8302d1-fa8e-4606-8aae-0e225e2e81cd-7fd5565c` (T1 uuid and pool start), bind `cs-b43b3621-56ab-4628-858d-a5c1b0a0adcb.iso` (ISO1 uuid). The first attempt showed hash names because an old jar was still deployed |
| IS4 | **Pass.** After the detach on the stopped instance the binds stayed; the next start removed both and the four drives are `none,media=cdrom` |
| IS5 | **Pass** (repeated). With `expunge=true` the bind was gone once the instance was expunged. The first attempt left it because the instance was only destroyed (its configuration still named the bind) and the ISO would not boot, so the VM had to be force-stopped in Proxmox |
| IS6 | **Pass.** The bind moved with the instance |
| IS8 | **Pass.** The attach on a running instance failed with the mount error (`exit code 124` is the 60 s timeout) and "Detach the ISO ..."; `pvesm status` and `qm list` answered, no bind, no ISO on the drives |

## Template from a volume, step 4 (built 2026-11-08)

Unit tests: `ProxmoxTemplatesFromVolumeTest` (12), 700 in the plugin. Lab checks to run (design-phase11-step4-template-from-volume.md, section 7):

| Test | What | Expected |
|---|---|---|
| T1 | `cat template.properties` of a template that the secondary storage VM registered, and of the new one (T2) | The same keys (`id`, `public`, `uniquename`, `qcow2`, `qcow2.filename`, `qcow2.size`, `qcow2.virtualsize`, `virtualsize`, `size`; the registered one may also have `filename`, which the plugin does not write: note it). The new template is in the store's template list after "Sync templates" (`listTemplates`, state Ready, size and physical size) |
| T2 | Stopped instance from template T1: Create template from its root volume | Ready; folder `template/tmpl/<account>/<id>` on the store with `<template uuid>.qcow2` and `template.properties`; sizes in the UI equal `qemu-img info` of the file; no `css-tmp` mount left (`findmnt -t nfs,nfs4`) |
| T3 | Deploy an instance from the new template | A template VM `cs-tmpl-<new uuid>-<pool>` is made on the pool; the clone boots with the data of the source instance |
| T4 | Create a template from a data volume | Works, the template deploys |
| T5 | Volume snapshot, create a volume from it, create a template from that volume (the snapshot workaround) | Works |
| T6 | Instance running: Create template from its root volume | Core refuses it ("attached to a non-stopped Instance") |
| T7 | Block NFS from the node, then Create template from a volume | Fails with the mount reason within about a minute; nothing left on the store or the node |
| T8 | Remove the volume's file by hand (on a detached volume), then Create template | Fails with "does not exist on the Proxmox node"; nothing left on the store |
| T9 | Create template from a snapshot | Fails (core/SSVM, open-issues.md) |

### Results of the first run (2026-11-09)

| Check | Result |
|---|---|
| T1 | Pass. The template the secondary storage VM registered has the keys `id`, `public`, `uniquename`, `qcow2*`, `size`, `virtualsize` and also `filename`, `hvm`, `description`. The plugin's file has the same keys except `filename`, `hvm` and `description` (see note 1) |
| T2 | Pass. Ready, `template/tmpl/2/226/<template uuid>.qcow2` + `template.properties`; `physicalsize` 3413114880 equals the file length, `virtualsize` 100 GiB equals `qemu-img info`. About 31 s for a 3.2 GiB image |
| T3 | Pass. Template VM `cs-tmpl-<uuid of tv-root2>-7fd5565c` (101) on the pool, 100G; the clone has the data of the source instance. (The second deploy, that must not make another template VM, was not shown) |
| T4 | Pass. A detached data volume gives a Ready template (5 GiB virtual, 4.2 MiB on disk); an instance deploys from it and cannot boot because it has no OS, as expected |
| T5 | Pass. Snapshot, volume from the snapshot (a data volume), template from that volume: Ready, 3428122624 bytes. (Deploying from `tv-snap` and booting was not shown) |
| T6 | Pass. Core refuses: "attached to a non-stopped Instance" |
| T7 | Pass. Fails after exactly 60 s with "Could not mount the secondary store ...: exit code 124"; no `css-tmp` mount on any node and no `.tmp` file on the store. The text of the reason was improved afterwards (note 2) |
| T8 | Pass, but the text was wrong: "does not exist on the secondary store" for a file on the primary storage. Fixed to "does not exist on the primary storage" |
| T9 | Pass (the expected failure). Core answers only "Failed to create Template", the secondary storage VM gives no reason (open-issues.md, workaround T5) |

Notes:

1. The plugin's `template.properties` lacks `filename`, `hvm` and `description`. Core showed the template as Ready and deployable, so nothing in the flows tested needs them; whether the secondary storage VM keeps the template Ready after its template sync (restart of the SSVM) is not yet checked.
2. A mount that times out (exit code 124) now says "the mount did not finish within 60 seconds (is the NFS server reachable from the node?)".

### Finding after the restart of the management server (2026-11-09)

All templates made from volumes went back to "processing" after the management server was restarted. Cause (V, `TemplateLocation.getTemplateInfo`): the secondary storage VM builds the install path from the plain `filename` property, which `TemplateLocation.save()` does not write, so the path it reported ended in `null` and core took the template out of the Ready state. This confirms note 1 above. Fixed: the plugin now appends `filename=<file>` and `hvm=true` to `template.properties` (unit test checks both). Templates made by the earlier build need the line added by hand on the store.

**Correction (same day, from the SSVM's template list after adding `filename`):** adding `filename` was not the cause. The list the SSVM returned had no entry for the templates 226 to 229 at all. Core stores the name of the copy answer as the unique name of the template (`TemplateServiceImpl`, `setUniqueName(newTemplate.getName())`), and the SSVM lists templates by the `uniquename` of `template.properties`. The plugin answered the template uuid but wrote `uniquename=<id>-<account>-<hash>` (the name of the destination object), so the sync did not find the template. Fixed: `uniquename` is the uuid, the same as the answer (unit test). The `filename` and `hvm` lines stay, as KVM writes `filename` too. Templates made by the earlier build need `uniquename` set to their uuid on the store.

### Results of the second run of step 4 (2026-11-09, after the uniquename fix, commit c368513b80)

- Templates made from volumes (`t-a_template`, `t-b`) are Ready after creation, and still Ready and on the store after the SSVM was recreated (template sync): **pass**. The `Template Sync` lines show only "did not find" for the templates of other hypervisors, and no "Deleted template" for them.
- `tv-bf` (221), an old failed test, was stuck: its `template_store_ref` row was in `Creating`, so delete failed ("Failed to update state"). Removed by deleting that row, then deleting the template. Lab leftover only; the failed creates of T7 and T8 left no such row.
- Not yet run again with the fixed jar: a second deploy from a volume template (no new template VM), a boot from a template made from a snapshot-volume, and a template from a data volume after the sync.

Follow-up checks on the fixed jar (2026-11-09), all as expected: R1 (a second deploy from a volume template makes no new template VM), R2 (deploy and boot from a template made through the snapshot workaround), R3 (a template from a data volume stays Ready after the SSVM was recreated). **Step 4 is closed.**

## Linked clones, lab spike (2026-11-09, design-phase12 section 8)

Run by hand with `qm`, `pvesm` and the REST API (token of a throw-away user) on pve3 against a spare base VM 8100 (a full clone of the template VM 107, 100 GiB, on pool `cs-f7c5790d-...`); nothing through CloudStack or the plugin yet.

| Check | Result |
|---|---|
| LC1 | **Pass.** `qm clone --full 0` took 1.6 s (a full clone of the same 100 GiB took 50 s); the overlay is 196 KiB with `backing file: ../8100/base-8100-disk-0.qcow2`; the VM config holds the long id `<pool>:8100/base-8100-disk-0.qcow2/8001/vm-8001-disk-0.qcow2`; the file is `images/8001/vm-8001-disk-0.qcow2` |
| LC2 | **Pass for the calls the plugin makes.** `GET .../storage/<pool>/content/<volid>` answers the **short** id (`<pool>:8001/vm-8001-disk-0.qcow2`) and the **long** id with the same size, format and path. `qm resize` works on a clone (the config keeps the long id). `pvesm list` shows only the long id for a clone. The plugin lists storage content only for ISOs, so nothing compares root-volume ids. D5 (short path) is confirmed |
| LC3 | **Pass.** `qm resize` to 110G on a clone: virtual size 110 GiB, overlay 53 MiB after a boot, base unchanged (100 GiB), the VM starts |
| LC4 | **Pass.** `qm destroy` of a base with clones, and the same through the API with a token: both refused, `base volume '<pool>:8100/base-8100-disk-0.qcow2' is still in use by linked cloned` (Proxmox's own text, with the typo); through the API it is a task that ends with that status |
| LC5 | **Backup pass; the revert question is still open.** A snapshot of a stopped clone read with `qemu-img convert -l snapshot.name=...` is `Images are identical.` to the base and has no backing file (the backup reads through the base; 1 min 5 s for 100 GiB with 3.2 GiB of data); the same with `-U` on a running clone (VM snapshot) works. Replacing the overlay with the flat copy gives a file with no backing file and the VM starts. **The probe of the base protection after the flatten was badly made** (other clones existed, so the refusal proves nothing): repeat with one clone only (see below) |
| LC6 | **Pass.** Instance snapshot without and with memory (`--vmstate 1`) on a running clone, rollback, start, delete: all work |
| LC7 | **Offline migration and online migration pass:** a stopped clone moved pve3 to pve1 in 2 s (this is the move the plugin does before a start), and the running clone moved pve1 to pve2 online in 9 s; the disk stays on the shared pool. **`move_disk` is not tested:** the command named the same pool (`you can't move to the same storage with same format`); repeat to the other pool |
| LC8 | **Pass.** On this Proxmox the overlay is `extended l2: false` (not on as the design assumed). `qemu-img snapshot -c/-l/-d`, `map`, `check`, `convert` and `compare` all work on an overlay |
| LC9 | **Pass.** `qm clone --full 0 --target <other node>` made the VM on the other node (`/etc/pve/nodes/<node>/qemu-server/8006.conf`), and it started there. This is the plugin's deploy path |
| LC10 | **Pass.** Ten linked clones one after another: 16 s (1.6 s each); ten at once: 7 s, no failure, no error (full clones: 205 to 243 s each with two failures) |

Other things seen:
- `qm template` of the base printed `chattr +i ... Operation not supported`: Proxmox cannot make the base file immutable on NFS (a KVM template file there is as unprotected). It is a warning, not an error.
- All clones of the spike and the base 8100, the user and the token of the spike must be removed (cleanup block); not confirmed yet.

## Linked clones, built 2026-11-09 (design-phase12-linked-clones.md; 710 unit tests pass; not yet run through CloudStack)

What was built: the setting `proxmox.create.full.clone` (scope StoragePool, then global, default false), `ProxmoxCloneMode` (thin follows the setting; sparse and fat are full copies), `full=0` in `createRootVolume`, the short volume path (`ProxmoxVmConfig.diskPath`/`shortPath`/`shortVolumeId`) and the drive lookups in the short form. Unit tests: `ProxmoxCloneModeTest` (3), `ProxmoxVmConfigTest` (3 new), `ProxmoxVmHandlerTemplateTest` (4 new), `ProxmoxConfigTest`.

| Check | What | Expected |
|---|---|---|
| LK1 | Setting not set (default). Deploy an instance from a template | Log "Making the root volume of <vm> as a linked clone"; the clone takes seconds; `volumes.path` in CloudStack is `<vmid>/vm-<vmid>-disk-0.qcow2`; `qm config <vmid>` shows the long id `<pool>:<base>/base-<base>-disk-0.qcow2/<vmid>/vm-<vmid>-disk-0.qcow2`; `qemu-img info -U` of the file has a `backing file`; the instance boots |
| LK2 | `update configuration name=proxmox.create.full.clone storageid=<pool uuid> value=true`, deploy | Log "as a full clone"; no `backing file`; the path is the same short form |
| LK3 | Global value true, pool value false; then the pool without a value | Pool value false: linked. Pool without a value: full (the global value). Set both back to false afterwards |
| LK4 | A compute offering with provisioning type sparse (or fat), setting false, deploy | A full copy, whatever the setting |
| LK5 | On a linked instance: stop, start (on another host: the configuration move), reboot, migrate to another host, expunge | All work; after the expunge the overlay is gone and the base stays |
| LK6 | Deploy with a root disk bigger than the template (`rootdisksize`), and resize the root volume later | The overlay is grown to the size asked; the guest sees it |
| LK7 | On a linked instance: volume snapshot (running), volume from the snapshot, attach it; instance snapshot with and without memory, revert; stop it and revert the volume snapshot | All work; after the volume revert the file has no backing file |
| LK8 | Stop a linked instance: create a template from its root volume, deploy from it; move its root volume to the other pool | The template has no backing file and deploys; the moved volume is a flat file on the other pool, the old overlay is gone, the base stays |
| LK9 | Ten instances at once, then expunge them all and delete the template | All ten succeed in well under a minute with no lock timeout in the log (full copies took 205 to 243 s with failures); deleting the template while instances exist is refused by core; after they are expunged the template VM is destroyed |

### Linked clones, first lab run 2026-11-09 (LK1 to LK8; LK9 open)

| Check | Result | Evidence |
|---|---|---|
| LK1 | Passed | Log "as a linked clone of the template VM 108"; created in 1.3 s, deploy took 21 s in all. DB path `115/vm-115-disk-0.qcow2`; `qm config` has `108/base-108-disk-0.qcow2/115/vm-115-disk-0.qcow2`; file 14.4 MiB with `backing file: ../108/base-108-disk-0.qcow2` |
| LK2 | Passed | Pool values true: log "as a full clone", 118 s; short path in `qm config`, no backing file, 3.21 GiB |
| LK3 | Passed | Global true, pools without value: full clone. Global true, pools false: linked clone (pool wins). Note: the response of `update configuration` for a pool shows the previous value, the stored value is the new one |
| LK4 | Passed | Offering with provisioning type sparse: full clone, no backing file, 3.2 GiB |
| LK5 | Passed, reboot to repeat | Stop and start worked, migrate to another host worked and the disk stayed a linked clone; expunge removed `images/115`, the base `images/108` stayed. The reboot failed with "VM quit/powerdown failed - got timeout" because the guest was still booting after the start (ACPI is not answered yet): timing, not the plugin. To repeat on a VM that has been up for a few minutes |
| LK6 | Passed | `rootdisksize=120`: linked clone with virtual size 120 GiB; resize to 130 GiB while stopped: 130 GiB and the backing file still there |
| LK7 | Passed | Volume snapshot BackedUp; volume from snapshot created and attached; instance snapshots with and without memory created and reverted; after the volume revert the file is 3.2 GiB with no backing file. `qm config` still names the volume with the long id (`108/base-108-disk-0.qcow2/120/...`) although the file is flat; Proxmox decides by the file, as in the spike. To check: start after the revert, the CloudStack path of the volume, and that the data disk is in `qm config` |
| LK8 | Passed, two checks open | Template from the root volume of the stopped linked instance: Ready, no backing file on the secondary store. Deploy from it: log "as a linked clone of the template VM 122" (the first deploy waited about 5 minutes while the new template VM was made on the pool). Move of the root volume to the other pool: flat file there, no backing file, base 108 still on the first pool. Open: that the old overlay directory on the first pool is gone |
| LK9 | Passed for the plugin | Ten at once: 0 lock timeouts. Each clone took 1.2 to 2.6 s ("Making the root volume" to "Created Proxmox VM"), seven on the first pool and three on the second. The whole run took 2 min 53 s because the ten deploys reached the clone step 5 to 30 s apart (23:33:00 to 23:35:20): the wait is before the plugin is called, not in the clone. The log of one deploy (job 2001, i-2-186-VM) shows it: 23:32:39 the VPC router is asked to prepare the NIC, the router scripts (DHCP entry, password, metadata) each take 0.5 to 2 s but with a 14 s gap between the DHCP entry and the password command (23:32:43 to 23:32:57), most likely because the router handles one command at a time and the other nine deploys are queued on it; the clone follows at 23:33:00 and takes 1.3 s. A disk write test on the second primary pool gave 34.5 MB/s (`dd`, 2 GiB, direct), which explains the full copies (about 118 s for 3.2 GB) and the 5 minute first copy of a template to a pool: the lab storage is the limit, not the plugin. Expunge of the ten, the refused delete of a template that has instances, and the clean-up were run by the tester without output kept |

## System VM patching, SSH-tunnel spike, 2026-11-11 (open-issues.md, "System VM patching"; no code change)

Question: can the management server patch a system VM the way KVM does (SSH to the VM's control address on port 3922), through a tunnel via the node, instead of the `csp-` storage entry, the `ide2` CD and the base64 pieces? Lab: SSVM `s-169-VM` on pve2, management server run from Maven as root.

| Check | Result | Evidence |
|---|---|---|
| Key | Found | The management server's key pair is `/root/.ssh/id_rsa.cloud` (ECDSA); its public half equals `ssh.publickey` in the database. The setting is in a hidden category: `cmk list configurations` does not show it. Not at the packaged-install path `/var/lib/cloudstack/management/.ssh/` |
| 1 and 2: reach and login | Pass | `ssh -f -N -L 13922:<control address>:3922 root@<node>` then `ssh -i <key> -p 13922 root@127.0.0.1` logs in as root on the VM with the management server's own key; the agent files are listed. The key never leaves the management server |
| 3: copy speed | Pass | `agent.zip` (94.5 MB in this build) through the tunnel into `/var/cache/cloud`: 2.9 s, SHA-512 equal. Current method: 1.7 s upload plus 3 s guest read |
| 2b: how early the port answers | Pass, with a note | Restart of the SSVM: Proxmox started it about 15:52:13, the guest agent answered after 45 s, the plugin gave the boot arguments at 15:52:59, `sshd` listened on the control address at 15:53:03 (4 s later; it can only bind that address once the boot arguments are in), the VM was ready at 15:53:12 (59 s in all). The wait for the patch files in `cloud-early-config` starts after the boot arguments, so a copy of about 3 s fits easily |
| Not covered | Open | This was a restart with the same version: the plugin found the agent and current scripts and patched nothing. A first start of a fresh template (no agent in it) and a router were not exercised |

Also noted: the control (link-local) address of a system VM changes on every stop and start (core gives it back and allocates a new one), so a tunnel has to be opened from the address of the start command each time; the VM may also start on another node. The 45 s of the start before the guest agent answers is independent of how patching is done.

## System VM patching over an SSH tunnel, built 2026-11-11 (703 unit tests pass; not yet run through CloudStack)

What was built: `TunnelVmShell` (the node forwards a loopback port to port 3922 of the VM; login as root with `ssh.privatekey`; retried every 2 s for up to 120 s), `SshLink.forwardLocalPort` and `upload` (streamed `cat >`), `ProxmoxSystemVms.deliverPatchFiles` (state check, `.part` files with a SHA-512 check each, one move with `cloud-scripts.tgz` last). Removed: `beforeStart`, the `csp-` storage entry, the padding upload, the reserved `ide2` drive, the setting `proxmox.patch.path` and the base64 pieces. Tests: `TunnelVmShellTest` (6), `ProxmoxSystemVmsTest` rewritten (25), the removed tests of the old method deleted.

| Check | What | Expected |
|---|---|---|
| SP1 | Stop and start the running SSVM | Log `has the agent and the current scripts, nothing to patch`; the start completes; no `csp-` or upload lines |
| SP2 | Destroy the SSVM and the CPVM so that new ones are made | Log `Gave [agent.zip, patch-sysvms.sh, cloud-scripts.tgz] to s-NN-VM over SSH in N s` (a few seconds); both reach Up and connect; `ls /usr/local/cloud/systemvm` in the VM has the agent |
| SP3 | Restart a router (restart the network with clean up) | Log `Gave [patch-sysvms.sh, cloud-scripts.tgz]`; the router works |
| SP4 | In a running SSVM write a wrong signature (`echo old > /var/cache/cloud/cloud-scripts-signature`), then stop and start it | Patched again (the three files) |
| SP5 | On the node that holds the SSVM add `AllowTcpForwarding no` (file in `/etc/ssh/sshd_config.d/`, `systemctl reload ssh`), stop and start the SSVM, then remove the file and reload | The start fails after about 2 minutes (the retry time) with `Unable to log in over SSH to the system VM at <address> through Proxmox node ... within 120 seconds: <error>`; after the file is removed the start works. Set it on all nodes, or the VM may start on one that allows forwarding |
| SP6 | Remove the old `csp-<uuid>` entry (`pvesm remove`) and the directory `/var/lib/cloudstack-patch` on the nodes, then start an SSVM | No effect on the start; `qm config` of an SSVM made by an older version shows an empty `ide2` that the start leaves empty |

### System VM patching over SSH, first lab run 2026-11-11 (SP1 to SP6)

| Check | Result | Evidence |
|---|---|---|
| SP1 | Not conclusive | The stop of the SSVM was followed by a new one (s-169-VM became s-189-VM: core replaced it), so the start was a first start and the log says `Gave [...]`, not `nothing to patch`. The restart path with the same VM is seen in SP4 and SP6 |
| SP2 | Passed | New SSVM and CPVM: `Gave [agent.zip, patch-sysvms.sh, cloud-scripts.tgz] to s-189-VM over SSH in 13 s` and `... v-190-VM ... in 10 s` (that time includes waiting for the VM's sshd); ready 48 s and 40 s after Proxmox started them (guest agent after 21 s and 16 s); both Up. In the VM: 246 agent files and the script signature equals the SHA-512 of the management server's `cloud-scripts.tgz` |
| SP3 | Passed | VPC restart with clean up: `Gave [patch-sysvms.sh, cloud-scripts.tgz] to r-191-VM over SSH in 8 s` (no agent.zip), router ready in 35 s |
| SP4 | Passed | With a wrong signature written into the SSVM, the next start patched again (`patch files 11 s`) |
| SP5 | Passed | `AllowTcpForwarding no` on all three nodes from 21:41:34 to 21:50:14. The first start of the SSVM failed after 121 s, the next start on pve1 failed after exactly 120 s (`Unable to log in over SSH to the system VM at 169.254.108.30 through Proxmox node csadmin@10.0.35.25:22 within 120 seconds: There was a problem while connecting to 127.0.0.1:33667`), and core then moved the VM to pve2 where it was blocked as well. After the files were removed the start worked (the next SSVM, s-192-VM, was made and started). The plugin's login to the node is the user `csadmin`, not root |
| SP6 | Passed in effect | The `csp-` entry was removed (and the directory on pve2 only); the SSVM s-192-VM that was made and started afterwards came up with its files, so nothing uses the entry any more. A separate start of the same VM after the removal was not logged |

The 120 s in the SP5 message are a constant (`ProxmoxSystemVms.SHELL_WAIT_SECONDS`), equal to the 2 minutes the VM itself waits for the patch files. The message hides the reason (Trilead wraps every error while connecting, banner and key exchange included, into "There was a problem while connecting to ..."); after this run the message carries the cause (`TunnelVmShell.describe`), the first failed attempt is logged, and when the login fails the plugin asks the VM through the guest agent for its addresses, listening ports, the state of `ssh`, `cloud-early-config` and `cloud`, and the end of `cloud.log`, and logs it (`ProxmoxSystemVms.DIAGNOSE`).

Checked on pve1 afterwards: with the block removed, a manual tunnel through `csadmin@pve1` to the console proxy VM (169.254.139.54) logs in as root with the management server's key; `cloud0` is up with `169.254.0.1/16` and one link-local route. SP1 (a restart of the same VM) is still open: stopping an SSVM makes core replace it.


### Start of a VM just after its stop answered "already running" (found 2026-11-11, fixed)

Stop and start of the console proxy v-190-VM (Proxmox VM 105 on pve1): `qmshutdown` ran 22:20:02 to 22:20:12 and core sent the start at 22:20:15. No `qmstart` ran on any node after that; the plugin logged nothing for the start, and the `CheckSshCommand` that follows pinged the (stopped) VM's guest agent for 120 s (`v-190-VM is not reachable: The guest agent of v-190-VM does not answer (tried for 120 seconds)`), so core destroyed the proxy and made v-193-VM. Cause: `ProxmoxVmHandler.start` returned success when the cluster list (`/cluster/resources`, refreshed by Proxmox only every ten seconds or so) still showed the VM as running; the stop had waited on the node's own status and returned correctly. Fix: `withLiveStatus` takes the state from `status/current` of the VM's node before the start and the stop decide that there is nothing to do (behaviour.md section 7). Tests: 4 new in `ProxmoxVmHandlerTest`; the fixtures of the other handler tests now answer "stopped" and then "running" for a start. 710 tests pass. To see on the lab: stop and start the console proxy at once, several times.

This may be the same cause as the intermittent start failure on one node noted earlier in open-issues.md (never explained): to look again after this fix.


### Restart of a system VM with the boot arguments of the last boot (found 2026-11-11, fixed)

Stop and start of the console proxy v-193-VM, three times at once. Runs 1 and 2: `has the agent and the current scripts, nothing to patch` (SP1 passes), ready in 35 s and 53 s. Run 3: boot arguments given at 22:47:09, then no SSH login for 120 s. The report that the plugin now takes from the VM through the guest agent showed `eth0 169.254.236.134` and `sshd` listening on `169.254.236.134:3922`, while the start command (and the `eth0ip` property in the VM's cloud.log) said `169.254.135.67`; also `eth1ip` 10.0.39.136 in the properties against 10.0.39.125 on the NIC. Cause: init.sh waits for a non-empty `/var/cache/cloud/cmdline`; the file of the last boot is still there, so the VM set up its network from the old arguments at once, and core had given a new link-local address for this start. The tunnel went to the new address. Runs 1 and 2 (the "configured 0 s" in their timing) probably got the same addresses again. Fix: a normal stop of a system VM empties the file through the guest agent first (behaviour.md section 7); 713 tests pass (3 new). To see on the lab: stop and start the console proxy and the SSVM several times (`cmk stop systemvm` then `start`); each start should log `configured` seconds like a new VM (about 11 s, not 0 s), and `ip -br addr` in the VM should show the address of the start command.

Comparison with KVM (2026-11-11), from `cloud.log` and the modification time of `/var/cache/cloud/cmdline` inside the VMs. Proxmox, three boots of v-193-VM: run 3 found a non-empty file at 22:47:05 (the old one), set up the interfaces at 22:47:07, and the plugin wrote the new arguments at 22:47:09; run 4 had the file written at 22:49:49.5 and found it at 22:49:50. KVM (v-359-VM, one boot, a new disk): file written 22:15:56.3, found at :57, interfaces at :58. So KVM has the same ordering and wins the race because its host writes within about a second of the agent starting, while the plugin, polling every 2 s and then calling the API, wrote 2 to 4 s later. Both fixes were made: the boot arguments are emptied at a normal stop, and the agent is polled every 0.5 s. The KVM VM had not been restarted, so its behaviour on a restart is not in these logs. 713 tests pass.

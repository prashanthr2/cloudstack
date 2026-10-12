# Phase 2 design: NFS primary storage

Status: **decisions taken (2026-10-12), Phase 2 implemented and unit tested; lab checks pending.** The first spike results are in section 8.

"Verified in code" means read in this repository. "Expected" means taken from the Proxmox documentation or experience and
**not yet confirmed on the lab**; each is covered by a check in section 8.

## 1. Scope

**In Phase 2:** add and remove an NFS primary storage pool for a Proxmox cluster, connect every node to it, report its capacity,
and keep it connected across management server restarts and node restarts.

**Not in Phase 2 (Phase 3, with VM deployment):** creating volumes, copying templates to the pool, attaching disks. A Proxmox
disk image belongs to a VM id, so volumes only make sense once a VM exists. Section 7 sketches how that will work.

Only **cluster-wide** pools are covered. A Proxmox storage entry is cluster-wide by nature, which matches a CloudStack
cluster-scope pool. Zone-wide pools are not supported yet.

## 2. How CloudStack adds an NFS pool (verified in code)

`CloudStackPrimaryDataStoreLifeCycleImpl.attachCluster`:

1. Lists the `Up` hosts of the cluster; fails if there are none.
2. For each host in turn, sends **`CreateStoragePoolCommand(add=true, pool)`** and **stops at the first success**
   (`createStoragePool`). Only these pool types are accepted: NFS, filesystem, iSCSI, VMFS, shared mount point, PreSetup,
   datastore cluster, OCFS2, RBD, CLVM, SMB, Gluster. If every attempt fails the pool record is removed.
3. Calls `storageMgr.connectHostsToPool`, which sends **`ModifyStoragePoolCommand(add=true, pool, details)`** to **every** host
   (through `DefaultHostListener.hostConnect`). The answer must be a **`ModifyStoragePoolAnswer`** carrying capacity and
   available bytes; CloudStack records them and the `localPath` in `StoragePoolHostVO`.

Afterwards:
- `StoragePoolMonitor.processConnect` repeats the `ModifyStoragePoolCommand` for the cluster's pools whenever a host
  (re)connects. **It does this only for a fixed list of hypervisors** (XenServer, KVM, VMware, Simulator, Ovm, Hyperv, LXC,
  Ovm3); Proxmox is not on it.
- The statistics collector asks for pool capacity with **`GetStorageStatsCommand(poolUuid, poolType)`**.
- Removing a pool sends **`DeleteStoragePoolCommand`** to the hosts: to every host for KVM, and until the first success for
  the others.

## 3. How a pool maps to Proxmox

The Proxmox storage entry is cluster-wide: one entry is visible on every node and each node mounts the share itself. That
fits the CloudStack sequence directly:

| CloudStack step | Proxmox action |
|---|---|
| `CreateStoragePoolCommand` to the first host | **Nothing** (as on KVM, where this command is also a no-op). The entry is created by the next step, because only that command carries the pool's mount options. |
| `ModifyStoragePoolCommand(add=true)` to each host | `GET /storage` to look for the entry. If missing: `POST /storage` with `type=nfs`, `storage=<id>`, `server`, `export`, `content=images`, `path=/mnt/<uuid>` (the mount point) and `options=<nfsmountopts>`. If it exists with the **same** server and export (a trailing `/` is ignored) it is reused; if it points elsewhere the command **fails** with a message, and nothing is changed. If another host's command creates it at the same moment, the second finds it and carries on. Then the node's status (`GET /nodes/{node}/storage/{id}/status`) is polled (up to 15 x 2 s) until it is `active`; the answer carries `total` and `avail` as capacity and available bytes, and `localPath` = `/mnt/<uuid>`. A node that never mounts the share answers with an explanation. |
| `ModifyStoragePoolCommand(add=false)` | Left alone: the entry is cluster-wide and is removed with the pool. |
| `GetStorageStatsCommand` | The status call for this node; returns total and used. |
| `DeleteStoragePoolCommand` | `DELETE /storage/{id}`; "not found" counts as success so the call can be repeated for each host. **This removes the entry only: Proxmox does not unmount the share** (its delete handler never deactivates the storage), so the mount at `/mnt/<uuid>` stays on every node until it is unmounted by an administrator or the node reboots. |

Only NFS pools are accepted; other pool types are refused with a message.

**Storage id.** `cs-<pool uuid>`, keeping the uuid's hyphens so an administrator can match the entry with the pool at a glance.
The prefix is needed: **Proxmox refuses a storage id that starts with a digit** ("storage ID ... contains illegal characters",
checked on the lab with `1a577022-...`), and a CloudStack pool uuid is a name-based UUID that starts with a digit in about 60 % of
the cases (KVM does not mind, since a libvirt pool name has no such rule). One function computes the id, so the same entry is
found again after a restart. The 39-character form itself is checked in S1b.

**Mount point.** KVM mounts a pool at `/mnt/<pool uuid>`; the Proxmox entry is given the same mount point through its `path`
option (S8 checks that Proxmox accepts it). Without it Proxmox would use `/mnt/pve/<id>`.

**NFS parameters** come from the pool: host address and path from `StorageFilerTO` (`getHost()`, `getPath()`). Proxmox negotiates
the NFS version when none is given. **Mount options are not supported for Proxmox pools:** CloudStack core refuses `nfsmountopts`
on create and on update for every hypervisor except KVM and the simulator (`StorageManagerImpl`), and the UI shows the field for
KVM only. The resource would pass `nfsmountopts` to Proxmox's `options` if it were ever present (unit tested), but it never is.
Supporting it would need a core change for both checks and, because Proxmox does not remount a share it finds mounted, the
leftover mount would also have to be unmounted for a changed option to apply; it is left out on purpose.

**Content type** is `images` only (decision), which is what VM disks need, so Proxmox will not offer the share for ISOs, backups or
containers. Proxmox accepts `import` as a content type on an NFS entry (S6), which may be added in Phase 3 for templates.

**Nodes.** No node restriction, because one CloudStack cluster is one Proxmox cluster.

**Permissions** (from the Proxmox source, see "Source reading" in section 8): creating, changing and removing a storage entry need
`Datastore.Allocate` on `/storage`, which `PVEAuditor` does not have; the built-in `PVEDatastoreAdmin` role does. The permission set
in requirements.md grows with this phase.

**Errors.** The create call activates the storage on the node that serves the API request and undoes the entry if mounting fails,
so a failed create comes back with the mount error. Creating an id that exists fails with "storage ID '...' already defined",
which is why the handler looks the entry up first and tolerates a race.

## 4. Core changes needed (verified in code)

| Where | Change | Why |
|---|---|---|
| `StoragePoolMonitor.processConnect` (done, with a unit test that fails without it) | add `Proxmox` to the hypervisor list | without it a host that reconnects (or the whole cluster after a management server restart) does not get its pools re-checked |
| nothing else for cluster-scope NFS | | the pool lifecycle, `createStoragePool` and `connectHostsToPool` have no other hypervisor conditions for NFS; the UI needs no change for a cluster-scope pool |

Known core limits that do **not** block Phase 2 but matter later (listed in upstream-notes.md when confirmed):
- `StorageManagerImpl.getHypervisorTypeFromFormat` maps **qcow2 to KVM only**. It is used when uploading a volume and when
  building volume API responses, so an uploaded or displayed qcow2 volume is treated as KVM. Relevant in Phase 3.
- `zoneWidePoolSupportedHypervisorTypes` has no Proxmox, which is why zone-wide pools are out of scope.

## 5. Failure behaviour

- NFS server unreachable or export not allowed for the nodes: the add fails and the pool record is removed, with the Proxmox
  reason in the message.
- One node cannot mount the share: that host's `ModifyStoragePoolCommand` fails with the reason for that node; the others
  are connected.
- A node that comes back after being down: `StoragePoolMonitor` (once Proxmox is on its list) sends the command again and the
  node's storage status is re-checked.
- **After a pool is removed the share stays mounted on the Proxmox nodes** (lab check p5, and the Proxmox source). This differs from
  KVM, where removing the pool unmounts it. Proxmox has no API to unmount a storage; unmounting would need access to the node
  (for example the SSH access planned for the system VM phase). **Decision: leave it.** Removing a storage in Proxmox itself leaves the mount
too, so the plugin behaves like Proxmox; it is documented, with the manual cleanup, and can be revisited if SSH access to the nodes
becomes part of the design.
- Removing a pool needs at least one connected host that answers: `deleteDataStore` sends the command to each host record until
  one succeeds and throws "Failed to delete storage pool on host" if none does (verified in code). With every host
  disconnected the pool cannot be removed until a host is back.
- The share is deleted or the entry is removed outside CloudStack: the next capacity check reports the storage as missing.

## 6. Tests

Unit tests with the mocked API client: create (new, same, conflicting entry), per-node status answer with capacity and
`localPath`, a node whose storage is inactive, delete (present and absent), stats. Lab checks are in section 9.

## 7. Looking ahead: volumes (Phase 3 design to be written)

A Proxmox disk image is a file `images/<vmid>/<name>` and is owned by one VM id. Whether a disk can be created **before** its VM
depends on S7, so Phase 3 has two possible shapes:

- **A (preferred, if S7 works):** when CloudStack copies the template to the pool to create the **root volume**, the resource takes
  `GET /cluster/nextid`, allocates the disk with `POST /nodes/{node}/storage/{id}/content` (`vmid`, `filename`, `size`, `format`),
  and stores the Proxmox volume id (`<storage>:<vmid>/<file>`) as the CloudStack volume path. `StartCommand` later creates the VM
  **with the same vmid** and references that volume. The source shows no VM check in the storage layer; whether `qemu-server`
  accepts attaching a disk that already carries the VM's id still has to be read (Phase 3).
- **B (fallback, if a disk cannot exist without a VM):** create an empty **VM shell** with the id and the CloudStack instance name
  when the template is copied, allocate the disk under it, and let `StartCommand` finish configuring that VM.

Either way: **data volumes** need an owner VM id too, so they are created when attached unless A allows creating them earlier; and
**templates** live on the secondary NFS store, so importing a qcow2 into a VM disk (`import-from`, or `download-url` with the
`import` content type, which an NFS entry accepts, S6) is still to be designed.

## 8. Checks on the lab (spikes) and their results

| # | Question | Result (2026-10-12) |
|---|---|---|
| S1 | Does Proxmox accept a pool uuid as a storage id? | **Only if it starts with a letter**: `aa577022-...` was accepted; `1a577022-b02f-4473-a971-da91d3a82b07` was refused with "contains illegal characters". Hence the `cs-` prefix. |
| S2 | Which privileges does the API need to create and remove storage? | **From the source:** `Datastore.Allocate` on `/storage` for create, change and delete; `Datastore.Audit` or `Datastore.AllocateSpace` on the entry to list it or read its status; `Datastore.AllocateSpace` to allocate a disk. Lab confirmation with the `pveaudit` token still pending (steps below). |
| S3 | Do mount options pass through? | **Yes**, `--options` works. |
| S4 | Fields of the status call | **Confirmed:** `active`, `avail`, `content`, `enabled`, `shared`, `total`, `type`, `used`; the figures match the NFS server. |
| S5 | What does creating an entry that already exists do through the API? | **From the source:** it fails with "storage ID ... already defined" and changes nothing. Lab confirmation pending. |
| S6 | Can `import` be a content type on an NFS entry? | **Yes**, accepted. |
| S7 | Can a disk image be created for a VM id that has no VM? | **Unclear.** Reported "no" on the lab, but the error message was not recorded. The source says it works: `vdisk_alloc` and `alloc_image` create `images/<vmid>/<name>` without checking that the VM exists. **Re-run and record the exact error** (the entry used must have `images` among its content types). |
| S1b | Is the 39-character `cs-<uuid>` id (also with a digit after the prefix) accepted? | **Yes**: lab check p1 created `cs-<uuid>` through the API. |
| S8 | Does the `path` option set the mount point (`/mnt/<uuid>`)? | **Yes**: the pool added from CloudStack is mounted at `/mnt/<pool uuid>` on the nodes (`findmnt`, 2026-10-12); spike entries added by hand with `--path` were also mounted where they were told to be. |

### Source reading

The Proxmox VE source mirrors (`github.com/proxmox/...`) were read for the questions above, at the newest 8.x versions in their
history, since the lab runs 8.4.21: `pve-storage` 8.3.6, `qemu-server` 8.3.13, `pve-access-control` 8.2.2, `pve-common` 8.3.1. The
clones on `master` are Proxmox VE 9 code and were **not** used for these answers. The exact package versions on the lab were not
available (only `pve-manager 8.4.21`), so the source is a second opinion: where it and a lab run disagree, the lab wins.
What the source says:
- storage id: `^[a-z][a-z0-9\-\_\.]*[a-z0-9]$` (case-insensitive), no length limit in that schema;
- `POST`, `PUT` and `DELETE /storage`: `Datastore.Allocate` on `/storage`; `GET /storage` lists entries on which the caller has
  `Datastore.Audit` or `Datastore.AllocateSpace`; the status call needs the same on the entry;
- creating an existing id: "storage ID '...' already defined"; the create call activates the storage on the serving node and
  rolls back on error;
- the NFS plugin takes `server`, `export`, `path` (fixed at creation), `options`, `content`, `nodes`;
- built-in roles are generated from privilege groups: `PVEDatastoreAdmin` = `Datastore.Allocate`, `AllocateTemplate`,
  `AllocateSpace`, `Audit`; `PVEAuditor` = every `*.Audit` privilege.

### How to run the remaining checks

Variables used below (type secrets with `read -rs`, never paste them into chat; nothing here prints a secret):

```bash
H=10.0.35.25                                    # any Proxmox node
EXPORT=/acs/primary/ref-trl-12294-k-Ma9-prashanth-reddy/primary   # a scratch export; not the one used by the live pool
read -rs TR     # root token:  root@pam!cloudstack=<secret>
read -rs TA     # audit token: pveaudit@pam!cloudstack=<secret>
```

Proxmox leaves a share mounted after its entry is removed, so each check ends with an `umount` (run on every node that shows the
mount in `findmnt -t nfs,nfs4`).

**S5: creating an id that already exists** (root token, so permissions do not interfere):

```bash
curl -ks -i -X POST -H "Authorization: PVEAPIToken=$TR" https://$H:8006/api2/json/storage -d storage=spike-s5 -d type=nfs -d server=10.0.32.4 -d export=$EXPORT -d content=images | head -n 1
```

Expect `HTTP/1.1 200 OK`. Run the **same** command again, then once more with `-d export=$EXPORT-other`: expect an error whose first
line says `storage ID 'spike-s5' already defined`. Record the exact first lines. Clean up:

```bash
curl -ks -i -X DELETE -H "Authorization: PVEAPIToken=$TR" https://$H:8006/api2/json/storage/spike-s5 | head -n 1
```

then `umount /mnt/pve/spike-s5` on the nodes that have it.

**S2: privileges for adding and removing storage** (the `pveaudit` user has `PVEAuditor` on `/`, and so does its token):

1. Without the datastore role, create must be refused:

   ```bash
   curl -ks -i -X POST -H "Authorization: PVEAPIToken=$TA" https://$H:8006/api2/json/storage -d storage=spike-s2 -d type=nfs -d server=10.0.32.4 -d export=$EXPORT -d content=images | head -n 1
   ```

   Expect `403` and a message like `Permission check failed (/storage, Datastore.Allocate)`.

2. Give the **token only** the datastore role, then repeat the call above:

   ```bash
   pveum acl modify /storage --tokens 'pveaudit@pam!cloudstack' --roles PVEDatastoreAdmin
   ```

   The Proxmox source says the token still gets nothing, because a token's rights are intersected with its owning user's. Expect `403` again.

3. Give the **user** the role as well, then repeat the call once more:

   ```bash
   pveum acl modify /storage --users pveaudit@pam --roles PVEDatastoreAdmin
   ```

   Expect `200`. Then remove the entry with the same token (expect `200`):

   ```bash
   curl -ks -i -X DELETE -H "Authorization: PVEAPIToken=$TA" https://$H:8006/api2/json/storage/spike-s2 | head -n 1
   ```

4. Clean up the permissions you added, and the mount:

   ```bash
   pveum acl delete /storage --tokens 'pveaudit@pam!cloudstack' --roles PVEDatastoreAdmin
   pveum acl delete /storage --users pveaudit@pam --roles PVEDatastoreAdmin
   ```

   then `umount /mnt/pve/spike-s2` where it is mounted.

**S7: a disk before its VM** (as root on a node; the entry needs `images` among its content types):

```bash
pvesm add nfs spike-s7 --server 10.0.32.4 --export $EXPORT --content images
pvesm alloc spike-s7 9100 vm-9100-disk-0.qcow2 1G --format qcow2
pvesm list spike-s7
```

Record the **exact text** printed by each command, especially any error. If the disk was allocated, attach it to a VM created afterwards
with the **same** id, which is how the root volume would be used:

```bash
qm create 9100 --name s7-test --memory 128 --scsi0 spike-s7:9100/vm-9100-disk-0.qcow2
qm config 9100
```

Then a disk allocated under a **different** id, which is what an unattached data volume would be:

```bash
pvesm alloc spike-s7 9101 vm-9101-disk-0.qcow2 1G --format qcow2
qm set 9100 --scsi1 spike-s7:9101/vm-9101-disk-0.qcow2
```

Record whether each works and the exact message if not. Clean up:

```bash
qm destroy 9100 --purge
pvesm free spike-s7:9101/vm-9101-disk-0.qcow2
pvesm remove spike-s7
```

then `umount /mnt/pve/spike-s7` where it is mounted.

## 9. Lab test plan for Phase 2

1. Add a cluster-scope NFS pool: it becomes `Up` with capacity, and the storage shows in Proxmox on all three nodes.
2. Restart the management server: the pool stays connected and the hosts keep their storage status.
3. Power a node off and on: it reconnects and its storage is checked again.
4. Add the same pool twice, and a pool with a wrong server or export: clear failure, no half-created pool.
5. Remove the pool: the Proxmox entry disappears.
6. Capacity shown in CloudStack follows the NFS server's usage.

## 10. Decisions

| # | Decision |
|---|---|
| 1 | Storage id `cs-<pool uuid>`, hyphens kept (a plain uuid was tried and refused by Proxmox when it starts with a digit) |
| 2 | Cluster-scope pools only |
| 3 | `content=images` |
| 4 | Volumes and templates in Phase 3, with the VM |
| 5 | The `StoragePoolMonitor` core change |
| 6 | No lab-specific storage details are needed: the pool is added from CloudStack and CloudStack configures it on the Proxmox nodes; the mount point is `/mnt/<uuid>` as on KVM |


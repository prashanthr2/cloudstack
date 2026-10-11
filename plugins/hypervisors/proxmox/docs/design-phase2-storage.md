# Phase 2 design: NFS primary storage

Status: **proposal, for review**. Nothing described here is implemented yet.

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

| CloudStack step | Proxmox action (expected) |
|---|---|
| `CreateStoragePoolCommand` to the first host | **Once**, `POST /storage` with `type=nfs`, `storage=<id>`, `server=<NFS host>`, `export=<path>`, `content=images`, `options=<mount options>`. If an entry with that id already exists with the **same** server and export, treat it as success; if it differs, fail with a clear message. |
| `ModifyStoragePoolCommand(add=true)` to each host | For **that node**: `GET /nodes/{node}/storage/{id}/status`. The node must report the storage active; the answer carries `total` and `avail` as capacity and available bytes, and `localPath` = `/mnt/pve/<id>`. A node that cannot mount the share answers with the Proxmox reason. |
| `GetStorageStatsCommand` | Same status call; returns capacity and used. |
| `DeleteStoragePoolCommand` | `DELETE /storage/{id}`; "not found" counts as success so the call can be repeated for each host. |

**Storage id.** The CloudStack pool uuid (`047ccc61-dc29-...`) cannot be used as is: a Proxmox storage id must start with a
letter and may be limited in length (check S1). Proposal: `cs-` + the pool uuid without dashes, computed by one function so
the same id is found again after a restart. If S1 shows a length limit, use `cs-` plus the first 16 hex digits and fail loudly
on a collision.

**NFS parameters** come from the pool: host address and path from `StorageFilerTO` (`getHost()`, `getPath()`), and the mount
options from the pool details (`nfsmountopts`, passed through unchanged, for example `vers=3`). Proxmox negotiates the NFS
version when none is given.

**Content type** is `images` only for now, so Proxmox will not offer the share for ISOs, backups or containers. Templates may
need another type in Phase 3 (S6).

**Nodes.** No node restriction, because one CloudStack cluster is one Proxmox cluster.

**Permissions.** Creating and removing storage needs more than `PVEAuditor` (expected: `Datastore.Allocate` on `/storage`;
check S2). The permission set in requirements.md grows with this phase.

## 4. Core changes needed (verified in code)

| Where | Change | Why |
|---|---|---|
| `StoragePoolMonitor.processConnect` | add `Proxmox` to the hypervisor list | without it a host that reconnects (or the whole cluster after a management server restart) does not get its pools re-checked |
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
- Removing a pool needs at least one connected host that answers: `deleteDataStore` sends the command to each host record until
  one succeeds and throws "Failed to delete storage pool on host" if none does (verified in code). With every host
  disconnected the pool cannot be removed until a host is back.
- The share is deleted or the entry is removed outside CloudStack: the next capacity check reports the storage as missing.

## 6. Tests

Unit tests with the mocked API client: create (new, same, conflicting entry), per-node status answer with capacity and
`localPath`, a node whose storage is inactive, delete (present and absent), stats. Lab checks are in section 9.

## 7. Looking ahead: volumes (Phase 3 design to be written after the checks below)

A Proxmox disk image is a file `images/<vmid>/<name>` and belongs to one VM id, so a volume cannot exist without one. The
direction, to be confirmed:
- The **root volume** is created when CloudStack copies the template to the pool, before the VM is started. The resource takes
  `GET /cluster/nextid`, creates the disk under that id, and stores the Proxmox volume id (`<storage>:<vmid>/<file>`) as the
  CloudStack volume path. When the VM is started it is created with **the same vmid**.
- **Data volumes** are created when they are attached to a VM, so the owner is known; an unattached volume is only a CloudStack
  record until then (S7 checks the alternatives).
- **Templates** are on the secondary NFS store, not on Proxmox. Importing the qcow2 into the primary pool (`import-from`, or
  `download-url` with the `import` content type) needs checking on this Proxmox version (S6).

## 8. Checks to run on the lab before building (spikes)

Run on a Proxmox node unless said otherwise. Use a scratch NFS export; remove everything afterwards.

| # | Question | How |
|---|---|---|
| S1 | What does Proxmox accept as a storage id (first character, length)? | `pvesm add nfs cs-0123456789abcdef0123456789abcdef --server <nfs-ip> --export <export> --content images`; then try a 40-character id and an id starting with a digit; `pvesm remove` each |
| S2 | Which privileges does the API need to create and remove storage? | with a privilege-separated token, try `POST /storage` and `DELETE /storage/<id>` after granting `PVEAuditor` plus `Datastore.Allocate` on `/storage` (and without) |
| S3 | Do mount options pass through? | add the entry with `--options vers=3` and look at the `mount` output on each node |
| S4 | Fields of the status call | `pvesh get /nodes/<node>/storage/<id>/status`: `total`, `used`, `avail`, `active`, `enabled` |
| S5 | Behaviour of creating an entry that exists, with the same and with different parameters | repeat `pvesm add`, and `POST /storage` with a different `export` |
| S6 | Content types for importing templates | `pvesm status`, and whether `--content import` is accepted for an NFS entry on 8.4 |
| S7 | Can an image be created for a VM id that has no VM, and handed to a VM later? | `pvesm alloc <id> 9100 vm-9100-disk-0.qcow2 1G --format qcow2` for a non-existent VM, then check how it can be attached to another VM |

I will write the answers into this document.

## 9. Lab test plan for Phase 2

1. Add a cluster-scope NFS pool: it becomes `Up` with capacity, and the storage shows in Proxmox on all three nodes.
2. Restart the management server: the pool stays connected and the hosts keep their storage status.
3. Power a node off and on: it reconnects and its storage is checked again.
4. Add the same pool twice, and a pool with a wrong server or export: clear failure, no half-created pool.
5. Remove the pool: the Proxmox entry disappears.
6. Capacity shown in CloudStack follows the NFS server's usage.

## 10. Decisions for the reviewer

1. Storage id scheme `cs-<uuid without dashes>` (final form after S1).
2. Cluster-scope pools only for now.
3. `content=images` only.
4. Volumes and templates move to Phase 3, with the VM.
5. The one core change (`StoragePoolMonitor` list).
6. Which NFS server and export to use for the lab primary storage (it must allow all three Proxmox nodes).

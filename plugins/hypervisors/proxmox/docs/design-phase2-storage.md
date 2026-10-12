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
| `DeleteStoragePoolCommand` | `DELETE /storage/{id}`; "not found" counts as success so the call can be repeated for each host. |

Only NFS pools are accepted; other pool types are refused with a message.

**Storage id.** `cs-<pool uuid>`, keeping the uuid's hyphens so an administrator can match the entry with the pool at a glance.
The prefix is needed: **Proxmox refuses a storage id that starts with a digit** ("storage ID ... contains illegal characters",
checked on the lab with `1a577022-...`), and a CloudStack pool uuid is a name-based UUID that starts with a digit in about 60 % of
the cases (KVM does not mind, since a libvirt pool name has no such rule). One function computes the id, so the same entry is
found again after a restart. The 39-character form itself is checked in S1b.

**Mount point.** KVM mounts a pool at `/mnt/<pool uuid>`; the Proxmox entry is given the same mount point through its `path`
option (S8 checks that Proxmox accepts it). Without it Proxmox would use `/mnt/pve/<id>`.

**NFS parameters** come from the pool: host address and path from `StorageFilerTO` (`getHost()`, `getPath()`), and the mount
options from the pool details (`nfsmountopts`, passed through unchanged, for example `vers=3`). Proxmox negotiates the NFS
version when none is given.

**Content type** is `images` only (decision), which is what VM disks need, so Proxmox will not offer the share for ISOs, backups or
containers. Proxmox accepts `import` as a content type on an NFS entry (S6), which may be added in Phase 3 for templates.

**Nodes.** No node restriction, because one CloudStack cluster is one Proxmox cluster.

**Permissions.** Creating and removing storage needs more than `PVEAuditor` (expected: `Datastore.Allocate` on `/storage`;
check S2). The permission set in requirements.md grows with this phase.

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
- Removing a pool needs at least one connected host that answers: `deleteDataStore` sends the command to each host record until
  one succeeds and throws "Failed to delete storage pool on host" if none does (verified in code). With every host
  disconnected the pool cannot be removed until a host is back.
- The share is deleted or the entry is removed outside CloudStack: the next capacity check reports the storage as missing.

## 6. Tests

Unit tests with the mocked API client: create (new, same, conflicting entry), per-node status answer with capacity and
`localPath`, a node whose storage is inactive, delete (present and absent), stats. Lab checks are in section 9.

## 7. Looking ahead: volumes (Phase 3 design to be written)

**S7 found that Proxmox will not create a disk image for a VM id that has no VM.** So a CloudStack volume cannot exist on the
primary storage before a VM does, and the earlier idea of creating the root disk before the VM has to change. The direction,
to be confirmed in the Phase 3 design:
- When CloudStack copies a template to the pool to create the **root volume** (before it starts the VM), the resource takes
  `GET /cluster/nextid` and creates a **VM shell** with that id and the CloudStack instance name (no CPU, memory or network yet),
  then creates the disk under it. The Proxmox volume id (`<storage>:<vmid>/<file>`) is stored as the CloudStack volume path.
  `StartCommand` then finishes configuring that same VM instead of creating a new one.
- **Data volumes** cannot exist before they have a VM either, so they are created when attached; until then a data volume is
  only a CloudStack record (to be checked against how CloudStack expects `CreateObjectCommand` to behave).
- **Templates** are on the secondary NFS store, not on Proxmox. Proxmox accepts the `import` content type on an NFS entry (S6);
  how a qcow2 gets from the secondary store into a VM disk (`import-from`, or `download-url`) is still to be checked.
- The VM shell appears in the node's VM list while CloudStack still thinks the VM is starting; the power state report must
  not confuse it (to be checked in Phase 3).

## 8. Checks on the lab (spikes) and their results

| # | Question | Result (2026-10-12) |
|---|---|---|
| S1 | Does Proxmox accept a pool uuid as a storage id? | **Only if it starts with a letter**: `aa577022-...` was accepted; `1a577022-b02f-4473-a971-da91d3a82b07` was refused with "contains illegal characters". Hence the `cs-` prefix. |
| S2 | Which privileges does the API need to create and remove storage? | **Open.** How to grant them is explained below. |
| S3 | Do mount options pass through? | **Yes**, `--options` works. |
| S4 | Fields of the status call | **Confirmed:** `active`, `avail`, `content`, `enabled`, `shared`, `total`, `type`, `used`; the figures match the NFS server. |
| S5 | What does creating an entry that already exists do through the API? | **Open** (needs an API call; see below). |
| S6 | Can `import` be a content type on an NFS entry? | **Yes**, accepted. |
| S7 | Can a disk image be created for a VM id that has no VM? | **No.** This changes the volume design (section 7). |
| S1b | Is the 39-character `cs-<uuid>` id (also with a digit after the prefix) accepted? | pending |
| S8 | Does the `path` option set the mount point (`/mnt/<uuid>`)? | pending |

### Commands for the open checks

S1b and S8 (as root on a node, with a scratch NFS export; remove the entries afterwards):

```bash
pvesm add nfs cs-047ccc61-dc29-3d58-9c73-dd1ab2c100cf --server <nfs-ip> --export <export> --content images --path /mnt/047ccc61-dc29-3d58-9c73-dd1ab2c100cf
pvesm status
findmnt /mnt/047ccc61-dc29-3d58-9c73-dd1ab2c100cf
pvesm remove cs-047ccc61-dc29-3d58-9c73-dd1ab2c100cf
```

S5, through the API with the unrestricted `root@pam!cloudstack` token (set `T` with `read -rs`, and `H` to a node address):

```bash
curl -ks -X POST -H "Authorization: PVEAPIToken=$T" https://$H:8006/api2/json/storage -d storage=spike-test -d type=nfs -d server=<nfs-ip> -d export=<export> -d content=images | jq
# the same call again, then once more with a different export:
curl -ks -X POST -H "Authorization: PVEAPIToken=$T" https://$H:8006/api2/json/storage -d storage=spike-test -d type=nfs -d server=<nfs-ip> -d export=/other -d content=images | jq
curl -ks -X DELETE -H "Authorization: PVEAPIToken=$T" https://$H:8006/api2/json/storage/spike-test | jq
```

S2: a privilege such as `Datastore.Allocate` is not assigned on its own; it is part of a **role**, and a role is granted to a user or
token on a **path**. The built-in role `PVEDatastoreAdmin` contains the datastore privileges. In the web UI: Datacenter ->
Permissions -> Add -> API Token Permission, Path `/storage`, the token, Role `PVEDatastoreAdmin`. From the shell:

```bash
pveum acl modify /storage --tokens 'pveaudit@pam!cloudstack' --roles PVEDatastoreAdmin
pveum role list        # shows the privileges of each role
```

then repeat the `POST /storage` and `DELETE` calls above with the **`pveaudit` token**: expect 403 before the role is granted and
success after (grant the role to the user as well if the token still gets 403). Remove the permission afterwards if the token
should stay read-only.

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


# Phase 7 design: data volumes (create, attach, detach, resize, delete)

Status: **implemented (2026-10-21)**; lab checks D1 to D12 done (section 10); decisions 1 to 6 accepted (per-account holder). Unit tested; the lab runs of the finished feature are listed in test-log.md. "V" means read in code (CloudStack in this repository; Proxmox in the 8.x mirrors under
`promox-src`, branch `stable-bookworm`, qemu-server and pve-storage). "U" means **unverified**: `pve-manager` (the storage content API) is not mirrored, or it has not been run.
Each U has a lab check in section 8.

## 1. Goal and scope

Extra disks for an instance: `createVolume`, `attachVolume`, `detachVolume`, `resizeVolume`, `deleteVolume`, and data volumes in the `StartCommand` of an instance that has them.
Today every one of these fails with "unsupported" in the plugin (`AttachCommand` and `CreateObjectCommand` are answered for ISOs and root volumes only).

Out of scope for this phase (each answered with a clear "not supported for Proxmox yet", never silently): volume snapshots and "create volume from snapshot", uploading and
downloading a volume (`CopyCommand` between a volume and the secondary store), data disk *templates* (create a volume from a template), moving a volume to another
primary storage, `CheckVolumeCommand` / repair, and encrypted volumes.

## 2. What CloudStack does for a data volume (V)

- **Create:** `createVolume` only makes the database row (state `Allocated`, no pool, no path). The disk is created on a primary storage **at the first attach**:
  `VolumeApiServiceImpl.attachVolumeToVM` -> `volService.createVolumeAsync` -> `CloudStackPrimaryDataStoreDriverImpl.createAsync` sends **`CreateObjectCommand`**
  (`VolumeObjectTO`: uuid, size, pool, account id, volume type DATADISK) to a host of the pool; the answer is a `CreateObjectAnswer` with a `VolumeObjectTO` carrying the **path**.
  The instance may not even exist yet in the command (`vmName` is not reliable here), so the disk cannot belong to an instance.
- **Attach to a running instance:** `AttachCommand(DiskTO, vmName, controllerInfo)` to the instance's host. `DiskTO` has the slot (`diskSeq`, the "device id", from 1 up, 3 is
  reserved for the CD-ROM, at most `hypervisor_capabilities.max_data_volumes_limit` = 30 for Proxmox, which Phase 3 already inserted), the type `DATADISK` and the `VolumeObjectTO`
  (path, pool, size, cache mode, QoS limits). `controllerInfo` is the instance's details (`rootDiskController`, `dataDiskController`, ...). For a stopped instance only the database changes.
- **Start:** the `StartCommand` has a `DiskTO` for the root volume and **every attached data volume** (type `DATADISK`, with `diskSeq`).
- **Detach:** `DettachCommand` (same shape) to a running instance; the volume stays `Ready` on the pool, with its path.
- **Resize:** `ResizeVolumeCommand(path, pool, currentSize, newSize, shrinkOk, vmInstance)` (also used for the root volume). Can be sent for a volume that is not attached.
- **Delete:** `DeleteCommand` with the `VolumeObjectTO`; the plugin's `deleteVolume` today treats every volume as a root volume (its path names a VM) and would destroy a VM.
- **Statistics:** `GetVolumeStatsCommand(poolType, poolUuid, volumeUuids)` is sent periodically (`volume.stats.interval`) and answered "unsupported" today.

## 3. What Proxmox does

- **A disk image is a volume `<storage entry>:<vmid>/<name>`** in the `images` folder of the entry, **owned by the vmid in its path** (V: `parse_volname`). The storage content API can
  allocate an image for any vmid with a file name `vm-<vmid>-<anything>.qcow2` (U: `POST /nodes/{node}/storage/{storage}/content`, parameters `vmid`, `filename`, `size`, `format`; lab D1).
- **A VM may use a disk that another vmid owns.** `check_volume_access` (V) allows it with `Datastore.Allocate` on the storage (in `PVEDatastoreAdmin`) or with `Datastore.Audit` on the
  storage and `VM.Config.Disk` on the owner's path `/vms/<owner>` (both in the documented roles, since `PVEVMAdmin` is granted on `/vms` with propagation).
- **What happens to such a disk when the VM goes away or the disk is detached:**
  - **Detaching it** (`PUT config delete=<drive>`) does *not* leave an `unusedN` entry: `vmconfig_register_unused_drive` registers one **only if the VM owns the volume** (V). Removing an `unusedN`
    entry always deletes the volume (V, the API text says "Unlink of unused[n] always cause physical removal"), which is why it matters that none is created.
  - **Destroying the VM** (`destroy_vm`, which also runs for the plugin's `purge=1`) frees only disks whose owner is that VM (V: `return if ... ($owner != $vmid)`), so a disk owned by another
    vmid is kept. **But** `qm destroy` by default also destroys *unreferenced* disks that belong to the VM's own vmid (`destroy-unreferenced-disks`, default 1): so a disk must never be owned by the
    vmid of a VM that can be destroyed. (Lab D8 showed that the **API** call `DELETE .../qemu/<id>?purge=1` keeps such unreferenced disks; the Proxmox UI offers the option as a checkbox, so an administrator
    can still remove them by hand.)
- **Controllers:** `scsi0..30` (with `scsihw` virtio-scsi), `virtio0..15`, `sata0..5`. Disks are hot-plugged on the default `hotplug=network,disk,usb` for SCSI and VirtIO, SATA is not
  hot-pluggable (U, lab D10). A drive added to a running VM that cannot be hot-plugged stays *pending* (as seen for the CD-ROM in Phase 6).
- **Resize:** `PUT /nodes/{node}/qemu/{vmid}/resize` (`disk`, `size` absolute or `+nG`) works on a disk of a VM, running or stopped, grows only. There is no resize for a disk that no VM uses (V: it
  is a VM API); the storage content API has no resize.
- **Deleting an image** is `DELETE /nodes/{node}/storage/{storage}/content/{volume}` (U: lab D9).

## 4. Design

### 4.1 A data volume is an image owned by a per-account "holder" vmid

The data volumes of a CloudStack account are disk images **owned by one vmid that is never a VM, the account's holder: `900000000 + <account id>`** (account 2, the admin, is `900000002`).
The image is named after the CloudStack volume: **`vm-<holder>-<volume uuid>.qcow2`**, so the volume path stored in CloudStack is `<holder>/vm-<holder>-<volume uuid>.qcow2` and the volume ID in Proxmox is
`cs-<pool uuid>:900000002/vm-900000002-<volume uuid>.qcow2`. On the primary storage the files are grouped by account, one folder per account (`images/900000002/`, `images/900000003/`, ...), and the uuid in the
file name maps each file back to its CloudStack volume. The account id comes from the `VolumeObjectTO` of the create command.

**Reserved vmids.** Proxmox accepts vmids from 100 to 999999999. **The range 900000001 to 999999999 is reserved for CloudStack** (the holders; account ids up to 99999999 fit, a larger one makes the create fail with a message).
Proxmox itself hands out the lowest free id from 100 (`cluster/nextid`), so ordinary VMs never get there; the plugin also refuses to create a VM if Proxmox ever returns an id in the reserved range, and the documentation
(requirements.md, behaviour.md) tells administrators never to create a VM or to set the cluster's `next-id` range there.

Why a holder and not "the disk belongs to the VM it is attached to": the volume exists before any instance does and survives it; moving ownership between a holder and the VM at every attach and detach
(`qm disk move --target-vmid`) means copying disk state around and leaves `unusedN` entries behind; a disk owned by an ordinary vmid is destroyed with the VM or by `destroy-unreferenced-disks` of
another VM that gets the same number. With the holder, an attach is one configuration change, a detach is one, the file is never touched or moved, and **deleting or expunging an instance never removes its data volumes**
(the lab already showed the plugin's `purge=1` for a root volume destroys only what the VM owns).

Why per account and not one holder for everything or one per instance: one folder for all volumes grows without bound; an instance is the wrong unit because a volume is detached from one instance and attached to another
(the file cannot follow it, so the folder name would mislead), exists before its first instance, and its folder would outlive the instance. An account owns a volume for as long as it exists. If an administrator
assigns a volume to another account the file stays in the old account's folder and keeps working.

The holder is derived from the account, not a setting: the path of every volume contains it, so it cannot change later.

### 4.2 Commands

- **`CreateObjectCommand`, volume type DATADISK:** allocate the image for the account's holder in the pool entry with the name above and the size rounded up to whole GiB (`format=qcow2`, thin), and answer with the
  path, the size and the format QCOW2. Idempotent: if the image already exists (a repeated command), answer with the existing one. Root volumes keep the blank-VM path of Phase 6.
- **`AttachCommand` (DATADISK):** find the instance (cluster-wide by name); the drive is `<controller><diskSeq>` (4.3); the configuration must not already use it for another volume; set
  `<drive>=<pool entry>:<path>` (plus `cache=` from the volume's cache mode, and the iops and bandwidth limits in 4.5). For a **running** instance read the running configuration
  (`?current=1`) afterwards: if the drive is not there (SATA, or a controller that cannot hot-plug) **revert the pending change** (`PUT config revert=<drive>`) and fail with "stop and start the instance"; the
  volume stays attached in CloudStack only if the command succeeds, so the instance and CloudStack agree.
- **`DettachCommand` (DATADISK):** find the drive that holds the volume (by path) in the instance's configuration and `PUT config delete=<drive>` (a running instance unplugs it live). A volume that is not
  attached is a success. No `unusedN` entry is left (3).
- **`StartCommand`:** for every DATADISK of the command set the drive to the volume as for an attach (the instance is stopped, so this is applied at once and needs no hot-plug); drives that hold a
  data volume that is not in the command (detached since the last start) are deleted, like the CD-ROM drives of Phase 6.
- **`ResizeVolumeCommand`:** find the drive that holds the volume in the instance's configuration (the command carries `vmInstance`; for a root volume it is the root drive) and `PUT .../resize` to the new
  size in GiB. A **shrink** is refused ("Proxmox cannot shrink a disk"), a size that is not larger is a success without a change. A volume that **no instance has attached cannot be resized** (Proxmox has no way to):
  the command fails with "attach the volume to an instance first, then resize".
- **`DeleteCommand` (DATADISK):** delete the image with the storage content API; a volume that is already gone is a success. The root-volume path (destroy the VM) is selected by the volume type, not by guessing from the path.
- **`GetVolumeStatsCommand`:** answer from the image's size and used space (U: the attributes the content API returns, lab D11) instead of "unsupported".
- **Everything else on a volume** (`CopyCommand` between a volume and the secondary store, snapshots, `CheckVolumeCommand`, migration between pools) is answered with "not supported for Proxmox yet" and the reason.

### 4.3 Slot and controller

Root disk: `scsi0` (or `sata0` / `virtio0`, Phase 6, `rootDiskController`). Data disks use `<controller><diskSeq>`, where the controller is the instance detail **`dataDiskController`** (`scsi`, `virtio`, `sata`), by
default **the same family as the root disk**, so that a guest that needed `sata` for its root disk does not get disks it has no driver for. `diskSeq` is CloudStack's device id (1 and up, never 3 for a data
disk); the ranges are `scsi 1-30`, `virtio 1-15`, `sata 1-5`, a slot outside the range fails with a message naming the controller and its limit. The suggestions for `dataDiskController`
(`scsi`, `virtio`, `sata`) are added next to `rootDiskController` in `QueryManagerImpl` (a second line of the Phase 6 change).

### 4.4 Interactions

- **Live migration (Phase 5):** the precondition lists a disk as local only when its storage is not shared; the pool entry is `shared`, so a VM with data disks migrates. The holder-owned disk follows the VM's
  configuration (the configuration moves, the image stays).
- **Instance delete and expunge:** the instance's own root disk is destroyed with the VM; the data volumes are kept (owned by the holder) and stay `Ready`/detached in CloudStack, as CloudStack expects.
- **Proxmox HA:** unaffected (shared storage, references in the VM configuration).
- **`hypervisor_capabilities.max_data_volumes_limit` = 30** is kept (the SCSI maximum); fewer for `virtio` and `sata` is enforced by the slot check above.

### 4.5 Disk offering limits (optional in this phase)

The disk offering's **iops and bandwidth limits** (`VolumeObjectTO` carries `iopsReadRate`, `iopsWriteRate`, `bytesReadRate`, `bytesWriteRate`) map to the drive options `iops_rd`, `iops_wr`, `mbps_rd`, `mbps_wr`.
The same mapping can apply to the root disk. Proposed: **yes, for data and root disks**, because it is a few lines in the drive option builder and a user will expect an offering with a limit to have one.
Marked as a decision.

## 5. Privileges

No new privilege if lab check D2/D9 confirm that the documented token (`PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`) can allocate an image for the holder vmid, use it in a VM,
and delete it. requirements.md gets the confirmation.

## 6. Risks and limits

- **The reserved vmids (900000001 to 999999999) must never be used for a VM.** If someone creates a VM with an account's holder id, destroying it removes every data volume of that account (the "unreferenced disks of its
  own vmid" rule). They are very high ids that Proxmox never hands out; documented prominently, and the plugin refuses to create a VM in that range.
- **A folder holds all the data volumes of one account**; a very large account makes a large folder, which only shows in a directory listing.
- **A detached volume cannot be resized** (Proxmox limit); resize works while it is attached, to a running or stopped instance.
- **SATA and non-hot-pluggable controllers need a restart of the instance to attach**; the command says so and leaves no pending change.
- **Data volumes are not copied, snapshotted or moved between pools** in this phase.
- **Deleting a volume that a Proxmox VM still references** (an administrator attached it by hand) leaves a dangling reference in that VM's configuration; CloudStack only deletes volumes it knows are detached.

## 7. Test plan once built

Unit tests: the slot and controller mapping (ranges, `dataDiskController`, default from the root), the volume path and Proxmox ID, create (allocation parameters, size rounding, idempotence), attach (running with a
hot-plugged drive, SATA reverted and refused, slot in use, stopped), detach (by path, not attached), start with data volumes (drives set, stale drive deleted), resize (grow, shrink refused, not attached refused),
delete (data volume deletes the image and never a VM), volume stats, the unsupported cases with their messages, QoS options. Lab runs listed in the test log after the build: create and attach to a running and a stopped
instance, detach, re-attach to another instance, resize while attached, delete, delete the instance and check the volume survives, start with a volume attached, migrate an instance with a volume, and the same with
`dataDiskController=sata`.

## 8. Lab checks before building (on a Proxmox node, with the token, a throwaway pool entry or the real pool, and a throwaway VM)

| # | Check | What it settles |
|---|---|---|
| D1 | Allocate an image with the token: `POST /nodes/<n>/storage/<pool>/content` with `vmid=900000002`, `filename=vm-900000002-<uuid>.qcow2`, `size=1G`, `format=qcow2`; list it with `GET .../content?vmid=900000002` | a holder vmid with no VM and the file name are accepted; the size format; the volume ID returned |
| D2 | On a stopped throwaway VM: `PUT config scsi1=<volid>` for that image | a VM can use a disk owned by another vmid with the token (privileges) |
| D3 | Start the VM; then `PUT config scsi2=<volid2>` (a second image) on the running VM and check it is applied (`?current=1`, and the guest or `ls -l /proc/<pid>/fd`) | hot-plug of a SCSI disk |
| D4 | `PUT config delete=scsi2` on the running VM | the disk is unplugged live, the file stays, and **no `unusedN` entry** appears (`qm config`) |
| D5 | Attach the same image again to the same VM, and to another VM | re-attach and use by another VM work |
| D6 | Destroy a throwaway VM (`DELETE ...?purge=1`) that has a holder-owned disk attached | the image is still there afterwards (`pvesm list`) |
| D7 | `PUT resize disk=scsi1 size=+1G` on the running and on the stopped VM; then a size smaller than the current one | live and offline growth; the answer for a shrink |
| D8 | `qm destroy` of a VM without `purge` and with the default `destroy-unreferenced-disks`, for a VM id that is *not* the holder | confirms only the VM's own vmid folder is affected |
| D9 | `DELETE /nodes/<n>/storage/<pool>/content/<volid>` for an image that is not attached, and for one still referenced by a stopped VM | deletion by the token; what Proxmox says when it is referenced |
| D10 | `sata1=<volid>` on a running VM (hot-plug) and `virtio1=<volid>` | SATA is pending, VirtIO is applied; `revert=sata1` removes a pending change |
| D11 | `GET /nodes/<n>/storage/<pool>/content/<volid>` | the fields it returns (size, used) for `GetVolumeStatsCommand` |
| D12 | `GET .../migrate?target=<node>` on a VM with a holder-owned disk | the precondition does not list it as a local disk |

## 9. Decisions for the reviewer

1. **A per-account holder vmid, `900000000 + account id`**, owning that account's data volumes (4.1), with **900000001 to 999999999 reserved for CloudStack** and documented as such. (Chosen over one holder for everything and over one per instance.)
2. **A detached volume cannot be resized** (it must be attached first), because Proxmox offers no resize for a disk that no VM uses.
3. **Data disks use the same controller family as the root disk by default, `dataDiskController` overrides it** (4.3).
4. **A SATA (non-hot-pluggable) attach to a running instance fails with "stop and start" and leaves nothing pending** (4.2).
5. **Disk offering iops and bandwidth limits become drive options, for data and root disks** (4.5), or later.
6. **Everything listed under "out of scope" answers "not supported for Proxmox yet"** with the reason, for this phase.

## 10. Lab results (2026-10-21, token `pveaudit@pam!cloudstack`, the documented roles)

| # | Result |
|---|---|
| D1 | **Pass.** `POST /nodes/<n>/storage/<pool>/content` (`vmid=900000002`, `filename=vm-900000002-labtest-a.qcow2`, `size=1G`, `format=qcow2`) allocated the image although no VM has that id; the answer is the volume ID `cs-<pool uuid>:900000002/vm-900000002-labtest-a.qcow2`; `GET .../content?vmid=900000002` lists it (`size` 1073741824, `used` 524288, `vmid` 900000002); the file is `images/900000002/vm-900000002-labtest-a.qcow2` on the pool |
| D2 | **Pass.** `PUT config scsi1=<volume ID>` on a stopped VM that does not own the disk: HTTP 200, `qm config` shows it |
| D3 | **Pass.** On the running VM `PUT config scsi2=<volume ID>` was applied live: `qm config --current` shows it and QEMU holds the file open |
| D4 | **Pass.** `PUT config delete=scsi2` on the running VM unplugged it (file no longer open), left **no `unusedN` entry**, and the file stayed in the folder |
| D5 | **Pass.** The same image attached again to the same VM and then to another VM |
| D6 | **Pass.** `DELETE .../qemu/<id>?purge=1` of a VM that had a holder-owned disk attached destroyed the VM; both images were still listed afterwards |
| D7 | **Pass** (second run; the first had an unencoded `+` in `size=+1G`). Live and offline growth work, with an absolute size (`2G`) or `+1G`, and the resize answers with a task; **a shrink request returns a task that fails** (the disk stays at its size), so the plugin must wait for the task and refuses a shrink itself |
| D8 | **Pass, and softer than assumed.** An unreferenced image `vm-9502-orphan.qcow2` of vmid 9502 was **kept** when the API destroyed VM 9502 with `purge=1` (the VM's own disk was removed). So the API does not destroy unreferenced disks by default (the UI checkbox does) |
| D9 | **Pass, with a limit.** `DELETE .../storage/<pool>/content/<volume ID>` (the volume ID URL-encoded) by the token returned an `imgdel` task and removed the image, **also while a stopped VM still referenced it** (no protection, a dangling reference is left). CloudStack only deletes detached volumes, so the plugin relies on that |
| D10 | **Pass.** A SATA drive added to the running VM is **pending** (`qm pending` shows it, `--current` does not) and `PUT config revert=sata1` removes it; a VirtIO drive is applied live and `delete=virtio1` removes it |
| D11 | **Pass.** `GET .../content/<volume ID>` returns `size`, `used`, `format` and `path` (enough for `GetVolumeStatsCommand`) |
| D12 | **Pass.** `GET .../migrate?target=<node>` of the running VM with a holder-owned disk: `local_disks` is empty, both other nodes are allowed |


# Phase 12 design: linked clones for root volumes

Status: **built 2026-11-09** (decisions D1 to D6 in section 10 made by the user; lab spike LC1 to LC10 run and passed, section 11; unit tested, **lab checks LK1 to LK9 of test-log.md not yet run through CloudStack**). First written and parked 2026-11-05. It builds on step 2 of design-phase11-secondary-mounts.md, which since 2026-11-05 (option C) already makes the **template VM** at the copy to primary and creates root volumes as **full clones** of it (`clone full=1`); this phase adds the choice of `full=0`, the setting and the short path.

Requested on 2026-11-05: Proxmox root volumes can be **linked clones** of the template, controlled by a **setting at cluster and zone level**, and honouring the other places where KVM decides it (the offering and so on).

## 1. What decides it on the other hypervisors (read from the code)

| Hypervisor | Setting | Where it is read | Notes |
|---|---|---|---|
| KVM | `create.full.clone` in the host's `agent.properties` (default `false`, so linked) | `LibvirtStorageAdaptor.createDiskFromTemplate` | Host level only: no cluster or zone setting. Applies to **thin** volumes of format **qcow2** only |
| KVM | **Provisioning type** of the root disk (service offering and disk offering): thin, sparse or fat | same method, `switch (provisioningType)` | `THIN`: backing file (linked clone, `qemu-img create -b`) or, with `create.full.clone`, `qemu-img convert`; `SPARSE` and `FAT`: **always a full copy** (`qemu-img convert`) with `preallocation` metadata or full. A raw format is always a full copy. The overlay is made with the volume's size when it is bigger than the template |
| KVM | Template cache on primary | `KVMStorageProcessor` (template to primary), `StorageManagerImpl` garbage collector | The template is copied to each primary pool because a backing file must be on the same storage. Unused templates are marked and then evicted (`storage.cleanup.enabled`, `storage.template.cleanup.enabled`, `evictTemplateFromStoragePool`) |
| KVM | Migration with linked clones | `MigrationOptions.Type.LinkedClone` / `FullClone`, `VIR_MIGRATE_NON_SHARED_INC` | A volume moved to another storage is recreated as a linked or full clone |
| VMware | **`vmware.create.full.clone`**, a global setting of scope **StoragePool** (default `false`) | `StorageManager.VmwareCreateCloneFull`, `VmwareStorageProcessor` | Scope StoragePool means it can be set on a storage pool, a cluster, a zone or globally (`ConfigKey.Scope`: StoragePool, Cluster, Zone, Global form a chain). A **bigger volume than the template forces a full clone**; strict disk provisioning refuses a linked clone |
| XenServer | `xenserver.create.full.clone`, scope StoragePool | `StorageManager.XenserverCreateCloneFull` | Same chain as VMware |

So the closest precedent for "cluster and zone" is the VMware and XenServer setting, not KVM's agent property.

## 2. What Proxmox offers (read from the Proxmox source, not tested)

- A linked clone is `POST /nodes/{node}/qemu/{template vmid}/clone` with `full=0`, from a **Proxmox template VM** (`POST .../qemu/{vmid}/template`; its disk becomes `base-<vmid>-disk-N.qcow2`). `storage` and `format` are refused for a linked clone: it is on the template's storage, a qcow2 overlay with the relative backing file `../<basevmid>/<basename>` (`Plugin.pm` `clone_image`, `qemu_img_create_qcow2_backed`, `extended_l2=on`, `cluster_size=128k`). File-based storage only (the NFS pool qualifies); the base may be qcow2, raw or vmdk. `full=1` clones a template VM into a standalone copy.
- **The base is protected:** `vdisk_free` refuses with "base volume ... is still in use by linked clones" (`volume_is_base_and_used`, which lists the volumes and compares the base named in their volume ID).
- **Volume IDs.** A clone's volume ID is `<basevmid>/<basename>/<vmid>/<name>`, but `filesystem_path` uses only the last two parts, so the file is `images/<vmid>/<name>`, the same place as a full copy. `parse_volname` also accepts the short form `<vmid>/<name>` for the same file.

## 3. Proposed behaviour

1. **A setting `proxmox.create.full.clone`**, Boolean, **scope StoragePool** (so it can be set on a primary storage, a cluster, a zone or globally, like VMware's), category Storage, dynamic. It is read at deploy time with `valueIn(poolId)` (`PrimaryDataStoreTO.getId()` is in the command). `true`: full copy (step 2 of design-phase11); `false`: linked clone. **Default: to decide** (recommendation: `false`, as on KVM, VMware and XenServer, once the spike has passed; until then `true`, which is today's behaviour).
2. **The provisioning type of the offering decides first**, as on KVM: `THIN` follows the setting; `SPARSE` and `FAT` are always a full copy, converted with `-o preallocation=metadata` or `full` (the same mapping as KVM's `PreallocationType`; the full-copy path of step 2 should do this too, today it does not pass `preallocation`).
3. **A volume bigger than the template**: a linked clone is made and the overlay is grown (`resize`; to confirm in the spike). VMware forces a full clone here; KVM does not.
4. **A template that is not qcow2 or raw**, or an ISO: full copy or not supported, as now.
5. **The template VM is made by step 2 of design-phase11, at the copy to primary** (named `cs-tmpl-<uuid of pool and path>`, made again at a deploy if it is lost, deleted by the `DestroyCommand` of core's template eviction). This phase only changes the clone call from `full=1` to `full=0` when the decision (items 1 to 4) says so.
6. **Template deletion** is already done by step 2 (`DestroyCommand`): the template VM is destroyed when no clone uses it, and Proxmox's refusal ("base volume ... is still in use by linked clones") is answered as a failure with its text, so core keeps the row and retries; core itself counts a template as in use while a volume uses it.
7. **The volume path stays the short form** `<vmid>/vm-<vmid>-disk-0.qcow2` for linked clones too: `ProxmoxVmConfig.diskPath` removes the base part when it reads the path from the VM configuration. Nothing else in CloudStack sees the difference.
8. **Other places, by design:**
   - *Volume snapshots:* a snapshot of an overlay is an internal qcow2 snapshot as now; `qemu-img convert` reads through the backing file, so a backup is complete; "create volume" makes a standalone volume. **A revert replaces the overlay with the standalone copy**, which ends the volume's dependence on the base (the short path is unchanged).
   - *Template from a volume or a snapshot (later):* `convert` flattens.
   - *Instance snapshots:* Proxmox's own snapshots on the overlay.
   - *Migration of a VM between nodes:* the same shared pool, nothing to copy. *Storage migration of a root volume to another pool* (`move_disk`): a full copy (the clone is flattened); to confirm.
   - *Resize:* on the overlay.
   - *Deleting the volume:* destroys the VM and its overlay; the base stays.
   - *Data volumes* are never clones of a template (data disk templates are not supported), so holders, aliases and `GetVolumeStats` for data volumes are untouched.

## 4. Is the short path enough? (answer to the question of 2026-11-05)

Mostly yes, because only **root volumes** become clones and root volumes are handled by VM id and drive key, not by a path match. From a search of the plugin the places to look at are:

| Place | Why |
|---|---|
| `ProxmoxVmConfig.diskPath` (used by `createVmWithRootDisk`) | strip the base part from `pool:<base>/<basename>/<vmid>/<name>` |
| `ProxmoxVmHandler.rootVmId` and `ProxmoxVmConfig` readers | take the VM id from the first part of the path: fine for the short form |
| `volumeId(volume)` and the calls that build `poolStorage + ":" + path` (`getImageInfo` for volume stats, resolving a volume for a migration or a resize) | build the short ID; Proxmox's parser accepts it, **to confirm on the lab** for each API call used |
| `ProxmoxVolumeSnapshots` (`VOLUME_PATH`, `volumeFile`) | the file is `images/<vmid>/<name>`: the short form passes the validator unchanged |
| `ProxmoxVmConfig.drivesHolding`, `ProxmoxHolderVms`, `ProxmoxVolumeAliases` | data volumes only: not affected |
| Anything that **lists** storage content and compares volume IDs (Proxmox returns the long ID for a clone) | to find and review; no case known yet |

So the change is small and local, but "short IDs work in every API call the plugin makes on a clone" is an assumption from the source until the spike (section 8) shows it.

## 5. Where an administrator sets it

No new API or UI: a global setting with scope StoragePool appears in **Global Settings** and in the **Settings tab of a zone, a cluster and a primary storage**. The provisioning type is already in the compute and disk offering forms.

## 6. Risks and open questions

- Default value of `proxmox.create.full.clone` (section 3, item 1).
- A template VM and its base per pool use primary space and a VM id each; the id range and naming must not clash with the holder ids (900000001 to 999999999) and with `nextid`.
- Template VMs are visible in the Proxmox UI and could be removed by an administrator; a clone of a removed template fails to start. Proxmox's own protection only covers a deletion through its API.
- The overlay uses `extended_l2=on` (QEMU 5.2 or later); the plugin's `qemu-img` flatten steps must work with it (spike).
- Changing the setting later: clones that exist stay linked; new deploys follow the setting.
- Core's garbage collector evicts unused templates from a pool; the plugin refuses while clones exist, which logs an error each cycle in core.

## 7. Build plan (after step 2 and its lab checks)

1. The setting and the decision function (setting, provisioning type, format, size) with unit tests, and the preallocation mapping in the full-copy path.
2. The decision function in `createRootVolume` (`full=0` or `full=1`) and the short path in `diskPath`; the template VM, the clone call and `DestroyCommand` exist from step 2 of design-phase11; unit tests.
3. Lab checks (section 8) and a doc update (`behaviour.md`, `test-log.md`, `upstream-notes.md` for the new setting).

## 8. Lab spike, to run when this is unparked (nothing here has been run)

| # | Check | Looks for |
|---|---|---|
| LC1 | Make a VM from the 5 GB template (`qm create` with the disk converted in), `qm template`, then `qm clone <tmpl> <id> --full 0` | `qm config` of the clone shows the long volume ID; `qemu-img info --backing-chain` shows the base; the file is `images/<id>/vm-<id>-disk-0.qcow2`; time of the clone |
| LC2 | `qm start`, then use the **short ID** in `qm set`, `qm resize`, `pvesm list`, the API `GET .../storage/<s>/content/<short id>`, `qm move_disk` | Which calls accept it and which return or require the long form |
| LC3 | `qm resize` of the clone to a bigger size than the base | Works; the overlay grows |
| LC4 | `qm destroy` of the template while a clone exists, and through the API with a token | The refusal and its text |
| LC5 | Volume snapshot of the running clone (the plugin's path: VM snapshot `cs-...`, `qemu-img convert -U -l`), create volume from it, revert (stopped) | The backup is complete (boot a volume made from it); after the revert the file has no backing file (`qemu-img info`) and the template can be destroyed |
| LC6 | Instance snapshot with and without memory on the clone, revert, delete | Works |
| LC7 | `qm migrate` of the clone to another node (online and offline); `move_disk` to another pool | No copy on the shared pool; the move flattens |
| LC8 | The plugin's `qemu-img` calls on an `extended_l2` overlay: `info`, `snapshot -c/-d`, `convert`, `map` | All work |
| LC9 | Clone with `--target` another node while the template VM is on a different node | Works on shared storage |
| LC10 | Ten clones in a row and ten in parallel | Time, and no failures |

## 9. Refresh of 2026-11-09 (read in the code, not run)

What is different from the text above, now that steps 1 to 4 of design-phase11 are built and lab-tested.

**What exists and is reused**
- The template VM (step 2): `cs-tmpl-<template uuid>-<first 8 of the pool uuid>`, a Proxmox template VM per template and pool, disk `<pool entry>:<vmid>/base-<vmid>-disk-0.qcow2`. `createRootVolume` finds it by the VM id in the stored path (`ProxmoxTemplates.requireTemplateVm`) and clones it today with `cloneVm(..., full=true, target=<this node>, storage=<pool>)` (`ProxmoxVmHandler.createRootVolume`). `ProxmoxApiClient.cloneVm` already supports `full=false` (it then sends neither `storage` nor `format`, as Proxmox requires). So the build is small: one decision function and one different call.
- The grow-after-clone is already there (`createVmWithRootDisk` resizes the disk to the volume's size when that is bigger than the image), so a bigger volume works the same for a linked clone; it is an overlay that grows (spike LC3).
- The plugin runs in the management server, so the setting is read there with `valueIn(poolId)` (`PrimaryDataStoreTO.getId()`); no agent property is needed. A `ConfigKey` is added in `ProxmoxConfig` as the other settings are, with scope `StoragePool`.
- `VolumeObjectTO.getProvisioningType()` is in the command, so the offering's provisioning type is available at the clone.

**What Proxmox does that the first version did not know (read from `qemu-server` and `pve-storage`)**
- `target` (the node where the new VM is made) is allowed for a linked clone exactly as for a full one: the check is that the template VM's disks are on **shared** storage (`$sharedvm`, `die "can't clone VM to node ... (VM uses local storage)"`). The NFS entries the plugin makes are shared, and full clones with `target` already pass the same check, so no new condition.
- **Preallocation is a property of the storage entry**, not of a clone or a disk (`preallocation` in the storage definition, applied by `clone_image` and `alloc_image`). So the offering's provisioning type cannot make one volume `metadata` or `full` through the clone API as KVM does with `qemu-img -o preallocation`. Item 2 of section 3 is changed accordingly (decision D2): sparse and fat give a **full** clone, without preallocation, and the limit is documented.
- A linked clone's disk in the VM configuration is `<pool entry>:<basevmid>/<basename>/<vmid>/<name>`; `ProxmoxVmConfig.diskPath` returns everything after the first colon, so it would store `101/base-101-disk-0.qcow2/104/vm-104-disk-0.qcow2`, which the validators (`DISK_PATH` in `ProxmoxTemplates`, `VOLUME_PATH` in `ProxmoxVolumeSnapshots`) refuse and the later commands do not expect. The build changes `diskPath` to return the last two parts (`104/vm-104-disk-0.qcow2`), which Proxmox's `parse_volname` accepts for the same file.

**Places that touch a root volume, one line each (what must hold for a clone)**
| Place | Needs |
|---|---|
| Volume snapshot backup (`ProxmoxVolumeSnapshots`, `qemu-img convert -U -l` of the snapshot) | Reads through the backing file; the backup is complete (LC5) |
| Revert of a volume snapshot (`ProxmoxVolumeSnapshots.revert`, stopped instance) | Replaces the file with a flat copy: the volume stops depending on the base (LC5) |
| Create volume from snapshot | A standalone volume, as now |
| Template from a volume (step 4, `createFromVolume`, `checkImage(ownImage=true)`) | `qemu-img convert` flattens: the template has no backing file (this is the planned step 4 check T8) |
| Storage migration of a root drive (`ProxmoxVmHandler` `move_disk`) | A full copy on the other pool: the clone is flattened (LC7) |
| Instance snapshots (Proxmox's own) | On the overlay (LC6) |
| Template eviction (`ProxmoxTemplates.destroy`, `DestroyCommand`) | Proxmox refuses to destroy a base with clones; already answered as a failure so core keeps the row and retries (LC4) |
| VM delete | Destroys the overlay only |

**What becomes riskier with a linked clone (new since the first version)**
- As with a linked clone on any hypervisor, a clone reads its base file for as long as it runs. Proxmox's own protection (it refuses to destroy a base that has clones, LC4) is the same kind of guard as for a KVM template file; nothing more is built.
- All clones **read the base from the same NFS file**, so a busy base is a shared hot spot for reads; writes go to each overlay. To measure with ten running clones (LC10).
- The same base also decides **where** the clone can be made: the template VM's configuration is on one node (open-issues.md, "A template VM is on one node"); that is unchanged by `full=0`.
- Core's template cleanup (`storage.template.cleanup.enabled`) will try to evict an unused template that still has clones; the refusal is logged by core on every cycle. Accepted.

**What the change buys (measured on the lab, open-issues.md, TV10)**: a full clone of the 100 GiB template took 22 to 26 s alone and 205 to 243 s each with ten in parallel, with two failures on the pool's lock. A linked clone creates a small overlay file and takes the storage lock for a moment, so it should be seconds and removes the parallel-deploy failures. Space: a root volume starts at a few MiB instead of the template's size.

## 10. Decisions wanted (recommendations first)

- **D1: default of `proxmox.create.full.clone`.** Recommended: `false` (linked, as on KVM, VMware and XenServer), but only after the spike has passed; the code is built with the setting, and the default is switched to `false` in the same change as the passed spike results. Until then the default stays `true`, which is today's behaviour and keeps every existing lab as it is. Alternative: ship `true` and leave the switch to the administrator.
- **D2: provisioning type.** Recommended: `THIN` follows the setting; `SPARSE` and `FAT` always give a full clone **without** preallocation (not possible per volume on Proxmox, see section 9), documented as a limit. Alternative: ignore the provisioning type altogether (only the setting decides).
- **D3: a volume bigger than the template.** Recommended: still a linked clone, grown (as KVM; the grow code exists). Alternative: a full clone in that case (as VMware).
- **D4: where the setting lives (decided 2026-11-09, changed the same day from the scope list to StoragePool and Global).** One Boolean `proxmox.create.full.clone`, category Storage, dynamic, declared with the single scope **StoragePool**. A key with one scope is looked up at the pool and then **globally** (`ConfigKey.valueInGlobalOrAvailableParentScope` returns the global value when `scopes.size() <= 1`), which is exactly how `vmware.create.full.clone` and `xenserver.create.full.clone` behave (they have no cluster or zone value either; the text of section 1 that calls it a chain of four levels is wrong). So: a value on the primary storage wins, else the global value; there is nothing to rank. It appears in Global Settings and in the Settings tab of each primary storage. Reason for no cluster or zone level: the template VM and its base file are per pool, so a pool is where the dependency is, and Proxmox clusters have few pools. Cluster and zone can be added later without breaking anything (pool values and the global value stay). **Default: false (linked), D1.**
- **D5: the stored volume path.** Recommended: the short form `<vmid>/vm-<vmid>-disk-0.qcow2` (no change anywhere else), after LC2 shows Proxmox accepts it in every call the plugin makes on a clone. If it does not, the long form is stored and the validators are widened.
- **D6: what a revert and a storage migration do.** Recommended: accept that they flatten the volume (it stops depending on the base), without any message.

## 11. Spike results (2026-11-09, test-log.md "Linked clones, lab spike")

LC1 to LC4, LC6, LC8, LC9 and LC10 passed; LC2 confirms D5 (the short id works in the content call, and the plugin lists content only for ISOs); LC7 passed for the offline and the online migration; the backup of a clone through its backing file is complete (LC5). Still open: (a) after a revert (the overlay replaced by a flat file) whether Proxmox still treats the VM as a clone of the base, because the VM configuration keeps the long id and so the base may stay "in use" (decides whether the revert must also rewrite the disk entry); (b) `move_disk` of a clone to another pool. New facts: the linked clone is 1.6 s against 50 s for a full clone, ten at once take 7 s without failures; the overlay is `extended_l2=off` on this Proxmox version; The base cannot be made immutable on NFS (`chattr +i` fails), the same as a KVM template file there.

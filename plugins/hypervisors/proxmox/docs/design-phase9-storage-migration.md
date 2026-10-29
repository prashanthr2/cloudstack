# Phase 9 design: storage migration (volumes, instances with their volumes, router and system VM migration)

Status: **third version: plan agreed, lab checks M1 to M8 done (section 8); nothing built yet.** "V" means read in code (CloudStack in this repository; Proxmox in the mirrors under `promox-src`, `qemu-server` branch `master`). "U" means **unverified**: `pve-manager` (the storage content API) is not mirrored, or it has not been run; each U has a lab check in section 7.

## 1. Goal and scope

Three pieces, all asked for (open issues 17 and 37):

- **(a) Move a volume to another primary storage:** `migrateVolume` for a detached volume, for the volume of a stopped instance, and for a volume of a running instance (live).
- **(b) Migrate a running instance to another host together with its volumes** (`migrateVirtualMachineWithVolume`, storage motion).
- **(c) Router, SSVM and CPVM migration** (`migrateSystemVm`, open issue 37), which core refuses today until Proxmox claims the storage motion functionality.

Test setup (the user): a second NFS primary storage added to the Proxmox cluster in CloudStack. Both pools are then mounted on every node by the plugin (`cs-<pool uuid>` entries, Phase 2), so a disk can be copied between them by Proxmox itself.

## 2. What CloudStack does (V)

**Volume move, the commands the plugin must answer:**

| API / state | Core path | Command to the plugin |
|---|---|---|
| `migrateVolume`, volume detached or VM stopped | `VolumeOrchestrator.migrateVolume` -> `volService.copyVolume` -> `AncientDataMotionStrategy.copyVolumeBetweenPools`: a **new volume row** is made on the destination pool (new uuid), a `CopyCommand` (source volume, destination volume) is sent to a host that sees both pools (no secondary storage staging when both pools have the same scope), and the source is deleted afterwards (`DeleteCommand`) | `CopyCommand`, volume to volume, both on primary storage |
| `migrateVolume` with `livemigrate=true`, VM running | allowed only if `hypervisor_capabilities.storage_motion_supported` is set for the host's exact hypervisor version; **for KVM it is refused** ("use migrateVirtualMachineWithVolumes", libvirt cannot refresh the domain), the check is `HypervisorType.KVM` only, so Proxmox would pass. Then `volService.migrateVolume` -> `AncientDataMotionStrategy.migrateVolumeToPool` (same volume row, the path is updated from the answer) | **`MigrateVolumeCommand`** (volume path, destination pool, attached VM name) -> `MigrateVolumeAnswer(new path)` |

**Instance with volumes** (`migrateVirtualMachineWithVolume`): gates in `UserVmManagerImpl`: the VM must be Running; the hypervisor must have the functionality **`VmStorageMigration`** (`Hypervisor.java`); then `getHostsForMigrateVmWithStorage` needs the **`storage_motion_supported` capability**. Then `VirtualMachineManagerImpl.orchestrateMigrateWithStorage`: `prepareNicForMigration`, `volumeMgr.migrateVolumes` -> `DataMotionService.copyAsync(volumeMap, vmTo, srcHost, destHost)`, which asks every `DataMotionStrategy.canHandle(volumeMap, srcHost, destHost)`: `AncientDataMotionStrategy` says CANT_HANDLE, the KVM and managed-storage strategies need a KVM source host or a managed pool. **No strategy handles Proxmox**, so the call ends with "Unsupported operation requested for copying data". A new strategy is needed (a plugin may register one: StorPool and Linstor do, as Spring beans of the plugin module).
After the strategy, core only checks that the VM is on the destination host (`checkVmOnHost`).

**System VMs and routers:** `migrateSystemVm` with a host calls `migrateVirtualMachineWithVolume` with an empty volume map. With the functionality and the capability set, `isVmCanBeMigratedWithoutStorage` is true and, for a VM that is not a user VM, core calls the ordinary `migrateVirtualMachine`, **which already works for Proxmox (Phase 5)**. So (c) needs no new command: it is the capability row and the functionality flag, set last (lab 2026-10-25 showed both gates).

## 3. What Proxmox does (V in `qemu-server`, U where noted)

- **`POST /nodes/<node>/qemu/<vmid>/move_disk`** (`disk`, `storage`, `format`, `delete`, `bwlimit`): copies one disk of a VM to another storage, **running or stopped** (running: QEMU drive mirror), returns a task. The copy is a **new volume allocated by Proxmox on the target storage, named `vm-<vmid>-disk-<n>`** (`clone_disk`), the config is switched to it, and the old volume is deleted with `delete=1` (otherwise it stays as `unusedN`). Refuses the same storage and format, a cdrom, and `delete=1` for a disk with snapshots. Needs `VM.Config.Disk` on the VM and `Datastore.AllocateSpace` on the target storage (in the documented roles; U: lab check M1).
- **Migration with disks:** `POST /nodes/<node>/qemu/<vmid>/migrate` with `online=1`, `with-local-disks=1`, `targetstorage=<id or map source:target>`, `target=<node>` moves a running VM and its **local** disks in one task. **It does not move disks that are on shared storage:** `QemuMigrate.pm` (`scan_local_volumes`) applies the storage map only to volumes whose storage is not `shared` ("local ignores shared mappings") and skips shared volumes. The `cs-` entries are NFS, which Proxmox always treats as shared (`@SHARED_STORAGE`, `Plugin.pm`), so this call cannot move a disk from one NFS pool to another (V; lab check M3 confirms).
- **A detached data volume has no VM**, so `move_disk` does not apply to it (it is owned by the account's holder vmid, Phase 7, which is not a VM). The storage content API has a copy call (`POST /nodes/<node>/storage/<storage>/content/<volume>`, `target`, `target_node`) that pve-manager marks experimental (U: lab M2).
- **Volume ownership is the problem for data volumes.** Phase 7 keeps data volumes owned by a holder vmid so that destroying or purging an instance never removes them. `move_disk` of an *attached* data volume makes a volume owned by the VM (`vm-<vmid>-disk-<n>`), which `purge` then destroys with the VM, and `destroy-unreferenced-disks` can also take it (design-phase7 section 3).

## 4. Design

### 4.1 Volume to volume copy (offline, `CopyCommand`)

For a volume that no running VM uses (detached, or its VM stopped): the plugin copies the image between the two entries of the pools, **keeping the naming rules of its type**:

- **Data volume (holder-owned):** a copy of `cs-<src>:<holder>/vm-<holder>-<old uuid>.qcow2` to `cs-<dst>:<holder>/vm-<holder>-<new uuid>.qcow2` (the new uuid is the new volume row core made), through the content copy API (U, check M2). The answer carries the new path. Core deletes the source (`DeleteCommand`, already handled).
- **Root volume of a stopped VM:** `move_disk` with `delete=1` on the VM's disk (`scsi0`): needs no running VM, the new volume is `<vmid>/vm-<vmid>-disk-<n>` on the destination entry, which is exactly the root volume form, and the plugin reads the new path from the VM configuration afterwards. Since core makes a new volume row and expects the source deleted by `DeleteCommand`, there is a mismatch: `move_disk` already moved the VM's disk, so the later `DeleteCommand` for the old root volume must not destroy the VM (it would). To be settled in 4.2 (decision 2).

### 4.2 Live volume move (`MigrateVolumeCommand`)

Same volume row, new path: the plugin runs `move_disk` for the drive that holds the volume (found from the path, in the configuration of the VM named in the command), `delete=1`, waits for the task (timeout `migrate.wait`), reads the new volume id back, and answers with the new path.
- **Root volume:** fine, the new volume is owned by the same VM.
- **Data volume attached to the running VM:** the new volume is owned by the VM, not the holder. Options in decision 1.

### 4.3 Instance with its volumes (`migrateVirtualMachineWithVolume`)

A new Spring-registered `ProxmoxDataMotionStrategy` in the plugin (no core change): `canHandle(volumeMap, srcHost, destHost)` is HYPERVISOR priority when both hosts are Proxmox; `copyAsync(volumeMap, vmTo, srcHost, destHost)` sends **one command to the source host**. Because Proxmox's combined migration does not move shared (NFS) disks (section 3), the plugin does it in two steps, both understood: **(1) `move_disk` (live) for every volume that changes pool, (2) the ordinary node migration of Phase 5** (`migrate`, `online=1`; the disks are now on the destination pools, visible from every node). It then reads back the new volume ids and returns them; the strategy updates the volume rows (path, pool). If the destination host is the node the VM is already on, step 2 is skipped. A failure in step 2 leaves the disks moved: the answer reports the new paths anyway so that core's rows match Proxmox, and the migration can be repeated.

### 4.4 Router, SSVM, CPVM (c)

Set `storage_motion_supported = 1` for the Proxmox row of `hypervisor_capabilities` (a schema/DB change; the row for the exact hypervisor version, with `max_data_volumes_limit` as Phase 3 inserted it) and the `VmStorageMigration` functionality for Proxmox in `Hypervisor.java` (one line; before the flag it was shown to be needed, 2026-10-25). Order: after (a) and (b), because the capability also turns on live `migrateVolume` and the host search of `findHostsForMigration`.

### 4.5 Pool mapping, privileges, limits

- The destination is the Proxmox entry `cs-<destination pool uuid>`; the plugin checks that it is visible on the node that runs the VM (`GET /nodes/<node>/storage`), and refuses with a message otherwise (open issue 24 shows the NFS visibility problem exists).
- No new privileges expected: `VM.Config.Disk` (`PVEVMAdmin`), `Datastore.AllocateSpace` (`PVEDatastoreAdmin`); the content copy needs `Datastore.AllocateSpace` and `Datastore.Allocate` (U).
- Volumes with VM snapshots: not moved (Proxmox refuses with `delete=1`; VM snapshots are not implemented in the plugin yet anyway).
- Bandwidth: `bwlimit` is left to Proxmox (datacenter or storage setting); no new setting.
- The `cst-`, `csi-`, `csp-` entries are not volume pools and are never a destination.

## 5. Core changes

1. **`Hypervisor.java`: Proxmox added to the `VmStorageMigration` functionality** (one line, reverted on 2026-10-25 and brought back with this phase).
2. **`hypervisor_capabilities`: `storage_motion_supported = 1` for Proxmox** (schema SQL or the existing row of Phase 3).
3. Nothing in `UserVmManagerImpl` or `VolumeApiServiceImpl`: the KVM-only refusal of live volume migration stays (it names KVM), and the "no volume to move" case for non-user VMs already falls back to `migrateVirtualMachine`.

## 6. Agreed plan (discussion 2026-10-28) and open points

Principle: **core changes stay near zero** (only the functionality flag and the capability row, section 5), and **attach, detach and the volume path stay as in Phase 7** (core cannot learn a new path after an attach, and a stopped instance gets no command at attach).

1. **Attach and detach: unchanged.** A data volume stays under its account's holder number; attach and detach only add or remove the drive line in the instance's configuration.
2. **Live move of a volume, and migration of an instance with its volumes:** Proxmox `move_disk` (live) for every volume that changes pool, then (for an instance migration) the existing node migration. The copy is owned by the instance and named `vm-<vmid>-disk-<n>`; the new path and pool are returned in the answer (`MigrateVolumeAnswer`, or the strategy updates the rows), which core applies.
3. **Giving the volume back to the holder.** Proxmox can hand a disk to another VM only (`move_disk` with `target-vmid`, a rename: both VMs must exist, be on the same node, and the source must not be running). So **the holder becomes a real, empty VM (config only, never started) with the vmid `900000000 + <account id>`, the number Phase 7 already uses, so every existing data volume is already owned by it (lab M7: a holder VM can list a volume of that owner as `unusedN`; nothing is renamed). It is created on first use with `protection=1` (lab M8: Proxmox then refuses to destroy it or its disks, by the UI or the API; the plugin lifts it only on purpose)**, one per account (more than one if an account passes the 256 `unused` entries of a configuration; the number of holders then needs a rule, open point A). The hand-over happens
   - **at detach** of a volume that the instance owns (after a live move): `delete=<drive>` (the instance records it as `unusedK`), then reassign `unusedK` to the holder; the detach answer carries the restored path (core applies `volumePath` of a detach answer);
   - **at destroy of the instance:** the instance is stopped, every disk that is not the root volume is reassigned to the holder, then the VM is destroyed with `purge`. The data volumes CloudStack was told to delete are then removed by the delete commands core sends, as today.
   The plugin moves the holder's configuration to the node of the instance first (a config-only move on shared storage) and takes a lock per holder.
4. **Offline copy of a detached volume** (`CopyCommand`): `move_disk` on the holder VM for the volume's `unusedK` entry (replaces the experimental content copy, check M2 only as a fallback). The new file is named by Proxmox (`vm-<holder>-disk-<n>`), so the uuid is no longer in the file name; the volume path in CloudStack is what ties it.
5. **Router, SSVM, CPVM (c):** the flag and the capability row, last.
6. **Order of work:** the holder VM and the hand-over (new, and a change to Phase 7), then the offline copy, the live move of a root volume and of data volumes, instance migration, then (c).

**Open points**
- **A.** How many holders per account and how a volume is assigned to one (first with a free `unused` slot, recorded in the path).
- **B.** Holders created on first use, or at account creation (only first use is possible: the plugin sees no account events).
- **C.** Existing Phase 7 volumes (no holder VM yet): the holder VM is created when first needed; a volume that is detached is brought under a holder at its first move.
- **D.** A crash between "recorded as `unusedK` of the instance" and "reassigned": a repair step at the next start, detach or destroy of that instance (an `unusedK` entry that holds a CloudStack data volume is reassigned, never deleted).

## 7. Lab checks before building (a Proxmox node with the token; a throwaway VM with a disk on one NFS entry, the second NFS entry added)

| # | Check | What it settles |
|---|---|---|
| M1 | `POST .../qemu/<vmid>/move_disk` (`disk=scsi0`, `storage=<second entry>`, `delete=1`) by the token, on a stopped and then a running VM; read the config, the file on both entries | the token's privileges, the new volume name, the source removed, live works |
| M2 | `POST .../storage/<first entry>/content/<holder>/vm-<holder>-x.qcow2` (`target=<second entry>:<holder>/vm-<holder>-y.qcow2`, `target_node`) by the token on a holder-owned image; list the content of both entries | whether Proxmox can copy a holder image under a name we choose, and with which privileges |
| M3 | `POST .../qemu/<vmid>/migrate` (`online=1`, `with-local-disks=1`, `target=<other node>`, `targetstorage=<first>:<second>`) on a running VM with its disk on the first NFS entry | confirms that the disk stays on the first entry (the code says shared storage is skipped) |
| M4 | A running VM with a holder-owned data disk: `move_disk` of that disk; the new volume name and owner; then destroy a throwaway VM with `purge=1` that does not own it | whether the volume keeps the holder name (it should not) and what a purge does to it |
| M5 | A real empty VM `<holder>` (`POST .../qemu`, no disks, never started). On a running VM X that owns a disk `scsi1`: `PUT config delete=scsi1` (look for `unused0`), then `POST .../qemu/X/move_disk` with `disk=unused0`, `target-vmid=<holder>`, `target-disk=unused0`, both VMs on the same node; then the other way (holder's `unused0` to X's `scsi1`), by the token | the hand-over to and from a holder VM works for a running source (through its `unused` entry), with the documented roles, and which names result |


## 8. Lab results (2026-10-28, token `pveaudit@pam!cloudstack`, the documented roles, three-node cluster, two NFS entries A and B)

| # | Result |
|---|---|
| M1 | **Pass.** `move_disk` (`disk=scsi0`, `storage`, `format=qcow2`, `delete=1`) by the token, on a stopped VM and on a running one. The copy is `<vmid>/vm-<vmid>-disk-<n>.qcow2` on the target entry (`n` = the first free number there, so the plugin reads the path back), the config points at it, the source file is removed |
| M4 | **Pass, ownership confirmed.** A holder-owned data disk (`900000002/vm-900000002-m4.qcow2`) attached to a running VM, `move_disk` to the other entry: the new volume is `<vmid>/vm-<vmid>-disk-0.qcow2`, owned by the VM; the holder file is gone. (My first read came before the live move ended; the final config shows the new volume on `scsi1`) |
| M5 | **Pass.** On the running VM `PUT config delete=scsi1` unplugged the drive and recorded `unused0` (owner: the VM); `move_disk` with `disk=unused0`, `target-vmid=<holder VM>`, `target-disk=unused0` worked **with the source running**, as a rename (same file, `<holder>/vm-<holder>-disk-0.qcow2`, no copy); the way back (`disk=unused0`, `target-vmid=<VM>`, `target-disk=scsi1`) worked too, and the VM's config was identical to before the round trip. The token was allowed to create the empty holder VM (`qmcreate`, no disks) |
| M6 | **Pass.** `POST .../qemu/<holder>/migrate?target=<other node>` by the token moves the holder's config to another node, also **with an `unusedN` entry** on shared NFS (the entry and its digest are unchanged afterwards). The holder and the VM must be on the same node for a hand-over, so the plugin moves the holder first |
| M7 | **Pass.** `PUT config unused5=<existing volume owned by the account number>` on the holder VM is accepted by the token. Destroying the holder with `purge=1` did **not** delete that file (a disk is freed only by its owner; here the owner was the account number, not the holder VM) |
| M8 | **Pass.** A VM created with `protection=1` is refused by `DELETE ...?purge=1` ("can't remove VM ... protection mode enabled"); `PUT config protection=0` lifts it |
| M2, M3 | Not run: the holder VM covers the offline copy (`move_disk` of an `unusedN` entry), and the code reading says the combined migration skips shared storage |

Not yet shown: `move_disk` of an `unusedN` entry of the holder to another storage (the offline copy of a detached volume, 4.1), and what a purge of a holder does to a volume it *does* own (the holder must never be purged; `protection=1` guards it).


## 9. Step 1 built (2026-10-28): the holder VM primitives

`ProxmoxHolderVms` (unit tested, 14 tests; plugin total 434): `ensureHolder` (create the empty VM `900000000 + account` with `protection=1`, the tag `cloudstack-data` and a description, or move it to the node of the instance), `adopt` (list a volume as the first free `unusedN`, repeated calls reuse the entry, an error at 256), `handBack` (the instance's `unusedN` to the holder) and `handTo` (the holder's entry to an instance drive), both reading the new volume id back, `deleteListed`, and a lock per holder. New client calls: `reassignDisk` (`move_disk` with `target-vmid`) and `moveDisk` (`move_disk` with `storage`, `delete=1`).
**Behaviour change now:** the delete of a data volume (`DeleteCommand`) first removes the volume's `unusedN` entry when the account's holder VM lists it (so no dangling entry is left), and then deletes the image if it is still there. Nothing lists volumes yet, so today nothing changes for existing volumes. Attach, detach and start are not changed yet.

## 10. A problem found while building: the detach of a stopped instance sends no command

Core sends `DettachCommand` only for a *running* instance (`VolumeApiServiceImpl.orchestrateDetachVolumeFromVM`: `sendCommand = vm.getState() == Running`, else the database only). After a live move the volume is owned by the instance, with a path of the form `<vmid>/vm-<vmid>-disk-<n>.qcow2`. If the instance is then stopped and the volume detached, **the plugin hears nothing**: the volume stays in the instance's configuration, owned by it, and at the next start or at the destruction of the instance the plugin has to hand it to the holder, **which renames the file, while CloudStack still stores the old path** (core takes a new path only from the answers to detach and migrate commands, not from a stop, a start or a delete). **Decided (2026-10-29): an alias map** (section 11).


## 11. The alias map (built 2026-10-29, unit tested)

When the plugin hands a volume to the holder VM at a moment when core cannot be told the new path (the instance was stopped when the volume was detached, so core sent no command), it records **old volume id -> new volume id** in the **description of the holder VM**, below the line `--- CloudStack volume aliases (kept by the plugin, do not edit) ---`, one line per alias `<pool entry>|<old path>|<new path>`, after the text that explains the VM. A Proxmox description holds at most 8192 characters; the plugin stops at 8000, which is about 70 to 80 aliases per holder. Before a rename the plugin checks that one more alias fits and refuses otherwise (`checkRoomForAlias`), so no volume is ever renamed without a way back. A second rename of the same volume rewrites the earlier lines (a lookup is always one step).

**Where a stored path is resolved** (`resolveVolumeId`: the image of the stored path is looked for first, which is all a volume needs that was never renamed; only when it is missing is the holder's alias map read): attach, start (every data volume of the command), detach, resize (the account comes from the instance name `i-<account>-<id>-VM`, the command carries none), and delete (the current volume is deleted and the aliases of it are removed). Not resolved: `GetVolumeStatsCommand` (it carries paths without an account), so a renamed volume has no statistics until core stores its new path.

**Aliases end** when the volume is deleted, or when core is given the new path (a later detach or migrate answer): `forgetAliases`.

**Where the hand-over with an alias happens** (built together with the live move, step 3): attach of a volume that is still in another, stopped instance's configuration, the start of an instance whose configuration still lists a detached data volume, the destruction of an instance, and the delete of a volume still listed by a stopped instance. At a *running* detach the new path goes in the detach answer and no alias is made.

## 12. Step 2 as built: offline copy of a detached data volume

`CopyCommand` volume to volume, both on primary storage, handled by `ProxmoxVmHandler.copyDataVolume`:

1. the source id is resolved (alias map), and the copy is refused when the volume is on the destination pool already or has no image;
2. under the holder lock: `ensureHolder` on the executing node, `adopt` lists the volume as `unusedN`, then `move_disk` of that entry to the destination storage with `delete=0`;
3. the holder's `unusedN` now names the copy (`<holder>/vm-<holder>-disk-<n>.qcow2` on the destination); the answer carries that path and the size read from the image.

The source stays listed as a further `unusedK`, so a failed copy loses nothing; core's `DeleteCommand` for the old volume then removes it through `deleteListed`. Not covered here (decision still open, 4.1): the root volume of a stopped instance. Lab check to run: M9 below.

**M9 (lab):** with a detached data volume on pool A and pool B added, run "migrate volume" to B (API `migrateVolume`, no live flag); expect the volume path `<holder>/vm-<holder>-disk-<n>.qcow2` on B, the holder VM listing the new volume, and no file left on A.

## 13. Step 3 as built: live move and the hand-overs

- **`MigrateVolumeCommand`** (`ProxmoxVmHandler.migrateVolume`): the drive that holds the path is found in the configuration of the instance named in the command (a path core still has for a volume handed over with an alias is found through the alias map: `resolveByPath`, since this command names no source pool), `move_disk` with `delete=1` runs (timeout `migrate.wait`), the answer carries the new path `<vmid>/vm-<vmid>-disk-<n>.qcow2`. The holder's entry for the old volume (a copy of step 2 leaves one) and its aliases are removed afterwards (best effort).
- **Hand-over hooks** (`giveBackToHolder`: drive removed so the instance lists `unusedN`, the holder moved to the instance's node, `unusedN` reassigned to the holder):
  - *detach of a running instance's volume the instance owns:* the answer's context `volumePath` carries the holder path, so core learns it (no alias);
  - *start:* a drive that holds a disk the instance owns, that is not the root volume (told by path) and that the command does not list, was detached while stopped: handed back with an alias;
  - *destroy of the instance (root `DeleteCommand`):* every such drive goes back before the VM is destroyed with its disks;
  - *attach:* a volume that another instance still lists goes back first, with an alias;
  - *delete of a data volume:* an instance that still lists it loses the drive and the entry, which removes the disk (refused while the drive is in a running VM).
- **Behaviour change to settle:** at start, any disk of an instance that is not its root volume and not in the command, and that the instance owns, is treated as a data volume and handed to the holder (it cannot be told from a disk someone added by hand in Proxmox). Before this step such a disk was left alone.
- **Lab checks to run before M-checks of step 3** (they decide whether a holder with `protection=1` works): see test-log "Phase 9, step 3".

## 14. Lab M9 (2026-10-29): a protected holder

On a VM with `protection=1`: `qm set <vm> --unused0 <A>:<vm>/<file>` works; `qm move-disk <vm> unused0 <B> --format qcow2 --delete 0` works (the copy is `<B>:<vm>/vm-<vm>-disk-0.qcow2` as `unused0`, the source stays as `unused1`); **`qm set <vm> --delete unused0` is refused: "can't remove unused disk ... protection mode enabled".** So the plugin switches `protection` off for the one change that removes an entry (`deleteListed`) and on again in `finally`. Not yet shown on a protected holder: the reassign (`target-vmid`) into and out of it (M5/M6 may have used an unprotected one).

## 15. Same path on two pools (found in lab T3, 2026-10-29)

Proxmox numbers a VM's disks per storage, and CloudStack stores the path without the pool entry. After live moves a root volume and a data volume of one instance can both be `106/vm-106-disk-0.qcow2` (one on each pool). Lookups that matched a drive by path alone could take the wrong drive. Now:
- commands that carry the pool (attach, detach, resize, and every hand-over) match the whole volume id with `ProxmoxVmConfig.driveHoldingVolume`;
- `MigrateVolumeCommand` names no source pool: when more than one drive holds the path, the volume type chooses (root volume: a drive of the root slots; data volume: any other), and the command is refused if that still does not give one drive;
- the start and destroy hooks recognise the root volume by its whole id (by path only when its pool is not known).
Proxmox picks the first free number on the target storage, so a moved disk is renamed (`disk-0` to `disk-1` when the target already has a `disk-0` of that VM); the answer carries the new path.

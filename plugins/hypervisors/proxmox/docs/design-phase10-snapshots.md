# Phase 10 design: snapshots

Status: **first version, for discussion; nothing built.** "V" means read in code (CloudStack in this repository, Proxmox in the mirrors under `promox-src`), "U" means unverified, with a lab check in section 5.

## 1. Two features that CloudStack calls snapshots

| | **Instance snapshot** (`VMSnapshot`, `createVMSnapshot`) | **Volume snapshot** (`Snapshot`, `createSnapshot`) |
|---|---|---|
| What it is | a snapshot of the whole instance (its disks, optionally its memory), kept on primary storage, reverted to as one | a point-in-time copy of one volume, normally copied to **secondary storage**; can create a volume or a template from it, and be scheduled by policy |
| Core flow (V) | `DefaultVMSnapshotStrategy` sends `CreateVMSnapshotCommand`, `DeleteVMSnapshotCommand`, `RevertToVMSnapshotCommand` to the host that runs the instance | `DefaultSnapshotStrategy`: `CreateObjectCommand` (snapshot on primary), then `CopyCommand` primary to secondary; revert is `RevertSnapshotCommand`; a volume or template from a snapshot is a `CopyCommand` |
| Gate (V) | `hypervisor_capabilities.vm_snapshot_enabled` of the **`default`** row (`VMSnapshotManagerImpl` asks with the version `default`); the Proxmox row has 0 | none in core: the plugin answers "not supported" today |
| Plugin work | three commands, a capability value, no new core code | a snapshot on primary, a copy to and from secondary storage, revert, delete: much more, see section 3 |

## 2. What Proxmox gives us (V in `qemu-server` and `pve-storage`)

- **Instance snapshots are native:** `POST /nodes/<node>/qemu/<vmid>/snapshot` (`snapname`, `description`, `vmstate`), `POST .../snapshot/<name>/rollback`, `DELETE .../snapshot/<name>`. They cover every disk in the VM's configuration and, with `vmstate=1`, the memory. For a qcow2 image on a directory or NFS storage Proxmox uses the qcow2 **internal** snapshots (`qemu-img snapshot -c`, `Plugin.pm` `volume_snapshot`); the file keeps its name. Newer Proxmox can use an external chain (`snapshot-as-volume-chain` storage option); the lab has 8.4.21, internal.
- **There is no snapshot of a single disk that no VM uses.** A detached data volume (owned by the holder VM, which is never started) has no API call to snapshot it, and a snapshot of an attached disk is a snapshot of the instance.
- **Getting a snapshot out of the node needs a file copy.** The content copy API copies the *current* image only, not a snapshot, and the plugin has no shell on the nodes (API only). A copy to secondary storage as KVM does it (`qemu-img convert` of the snapshot) is not possible from the API alone.
- **Interaction with Phase 9 (V in the code, U in the lab):** `move_disk` with `delete=1` is refused for a disk that has snapshots (so a live move or an instance migration with volumes fails for an instance that has instance snapshots; core already refuses to storage-migrate an instance that has snapshots for hypervisors that do not claim `VmStorageMigrationWithSnapshots`, which Proxmox does not). A rollback restores the configuration of the snapshot time, including the drive lines; CloudStack does not allow attach or detach while an instance has snapshots, so they agree.

## 3. Options

**A. Instance snapshots only (small, recommended first).** Handle the three commands in the plugin and use Proxmox's snapshots; set `vm_snapshot_enabled` to 1 in the schema default row. `DefaultVMSnapshotStrategy` already handles Proxmox for a stopped instance and for a running one with memory (`canHandle` refuses a running instance without memory: disk-only snapshot of a running instance needs its own strategy, as KVM has one; for Proxmox a "disk only" snapshot of a running instance is the same call with `vmstate=0`, which is crash consistent; to decide, 4.2). No secondary storage, no files to copy, nothing to schedule.

**B. Volume snapshots.** Three levels, each needing a decision:
1. *Not supported* (today's answer), and say so in the docs.
2. *Primary only, attached volumes:* the snapshot of the instance that uses the volume, under the volume snapshot's name (`vm_snapshot` per volume is not a Proxmox thing; the disk-only snapshot covers all disks). Wrong for a volume of an instance with several disks, and impossible for a detached volume.
3. *Full (primary and secondary, create volume or template from it):* needs a file copy on the node, so it needs **node access that the plugin does not have** (an SSH key per node as the system VM work used an SSH key for the guest, or a small agent on the node), or a workaround: a point-in-time copy through `move_disk` with `delete=0` (a live mirror: the copy is consistent at the moment of the switch, it is a full copy each time, and the VM ends on the copy). Neither is attractive.

## 4. Proposal and open questions

**Proposal:** build A now (instance snapshots, three commands, tests, lab checks S1 to S6); record B as a known limit with the reason (no per-disk snapshot of a detached volume and no way to read a snapshot from the node); decide B with you later.

Questions:
1. **Is A enough for the first version, with B recorded as a limit?** (Volume snapshots, scheduled snapshots and "create template from snapshot" would not work on Proxmox.)
2. **Running instance, disk only:** should that work? It needs a plugin strategy (a subclass of `DefaultVMSnapshotStrategy` with another `canHandle`, a bean in the plugin like the data motion strategy), small. Or only memory snapshots for running instances, which is what the default strategy gives with no extra code.
3. **For B level 3, is node access acceptable** (SSH key from a setting, as the guest agent access is today)? If not, B stays a limit.

## 5. Lab checks before building A

| # | Check | Answers |
|---|---|---|
| S1 | on a stopped VM that has a holder-owned data volume attached: `qm snapshot <vmid> s1` | does Proxmox snapshot a disk the VM does not own (the Phase 7 data volume)? |
| S2 | the same on a running VM, `qm snapshot <vmid> s2 --vmstate 1`, then `qm snapshot <vmid> s3 --vmstate 0` | memory and disk only on NFS qcow2; where the memory goes (`vmstatestorage`) |
| S3 | `qm rollback <vmid> s1`, then `qm delsnapshot <vmid> s1` | rollback of a data volume of a holder, and delete leaves the file right |
| S4 | with a snapshot present: `qm move-disk <vmid> scsi0 <other pool> --delete 1` | confirms the refusal (so core's refusal is right and the plugin's message must say so) |
| S5 | with a snapshot present on the instance: `qm destroy <vmid> --purge` | whether the VM and its snapshots go (the destroy hook hands data volumes to the holder first: with snapshots they cannot be handed over) |
| S6 | `qm config` after S1 | does the snapshot section list the data drive, and what does a rollback do to the drive lines |

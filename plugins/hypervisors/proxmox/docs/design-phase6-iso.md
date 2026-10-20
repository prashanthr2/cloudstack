# Phase 6 design: ISOs (attach, detach, an ISO at start, deploy from an ISO)

Status: **proposal, for review.** Nothing described here is implemented. "V" means read in code (CloudStack in this repository; Proxmox in the 8.x mirrors under
`promox-src`, branch `stable-bookworm`, pve-storage and qemu-server). "U" means **unverified**: it has not been run, and each U has a lab check in section 7.

## 1. Goal and scope

Three things, all asked for:

1. **Attach and detach an ISO** to a running VM (`attachIso` / `detachIso`). On the lab it fails today with `Unsupported command issued: ...AttachCommand`
   (the plugin answers every command it does not handle that way).
2. **An ISO attached when the VM starts**: a VM that has an ISO attached, or empty CD-ROM drives, gets them when it is started.
3. **Deploy a VM from an ISO**: a new VM with a blank root disk that boots from the CD-ROM, for installing an operating system.

Not in scope: ISOs that are not on an NFS secondary store (S3 is already turned into a cache copy on an NFS store by core before the command is sent, V),
ISOs registered for *direct download*, config-drive ISOs (the same disk type, handled later with the VM user data work), and the VMware tools ISO.

## 2. What CloudStack does today (V)

- **Running VM**: `TemplateManagerImpl.attachISOToVM` sends `AttachCommand` / `DettachCommand` to the VM's host, **only when the VM is Running**; for a stopped VM it
  only records the attachment and the ISO is given to the VM at the next start. The command holds a `DiskTO` of type `ISO` with `diskSeq` (the slot) and a
  `TemplateObjectTO` whose data store is the NFS secondary store (`NfsTO`, with the URL) and whose path is the file below it, for example
  `template/tmpl/2/231/9f1c....iso`; and the VM name.
- **Start**: `prepareIsoForVmProfile` adds one `ISO` disk to the `StartCommand` for **every CD-ROM slot** of the VM, an ISO where one is attached and an
  **empty** `TemplateObjectTO` (no path) where not. Slots are numbered from `CDROM_PRIMARY_DEVICE_SEQ` = 3; how many exist is the setting `vm.iso.max.count`
  per cluster, clamped to what the host advertises in the host detail `host.cdrom.max.count` (default 1 when the host says nothing). The comment in core says
  why: *"QEMU/IDE refuses to hot-add new cdrom drives, so runtime attachIso can only media-swap into a slot the domain already owns."* The same holds in Proxmox
  (section 3).
- **Boot from the ISO**: when the ISO in the first slot is bootable, core sets `VirtualMachineTO.getBootloader()` to `CD`; otherwise the VM boots from disk.
- **Deploy from an ISO**: the root volume is **not created from a template** (`VolumeOrchestrator`: the template branches skip `ISO`, the volume only gets
  `isoId`); it is created blank by the primary storage driver, which sends **`CreateObjectCommand`** with the `VolumeObjectTO` (size, uuid) to a host of the
  pool (`CloudStackPrimaryDataStoreDriverImpl.createAsync`). The answer is a `CreateObjectAnswer` with a `VolumeObjectTO` carrying the path.
  The `StartCommand` then has the root disk and the ISO disk(s) as above.
- The KVM plugin answers `AttachCommand` / `DettachCommand` through `KVMStorageProcessor.attachIso` / `dettachIso` (a media change in libvirt) with a plain `Answer`.

## 3. What Proxmox does

- **A CD-ROM drive** is a disk option with `media=cdrom`: `ide0` to `ide3`, `sata0` to `sata5` or `scsi<n>` (V). `ide2=<volid>,media=cdrom` is the usual one; an **empty**
  drive is `ide2=none,media=cdrom`. Changing the medium of an existing drive of a running VM is applied at once; adding a drive to a running VM is not (IDE is
  not hot-pluggable, U for what the API does: lab check I3).
- **An ISO is a volume `<storage entry>:iso/<file>.iso`**, and the name allows **no folder** below `iso/` (`Plugin.pm`: `^iso/([^/]+<iso extension>)$`, V; the
  extension is `.iso` or `.img`). A storage entry lists ISOs from one folder of its path (`content-dirs iso=<folder>`).
- **An absolute path** instead of a volume is accepted only for `root@pam` (the same rule as `import-from`, V in Phase 3), so the token cannot use
  `/mnt/.../x.iso`.
- CloudStack keeps each ISO **in its own folder** (`template/tmpl/<account>/<id>/<file>`), so no single entry sees them as `iso/<file>`: one entry per ISO folder is needed.
- **Boot order** is the VM option `boot=order=<dev>;<dev>` (the plugin sets `order=scsi0` today).
- **Migration with an ISO attached:** the precondition (`GET .../migrate`) lists a CD-ROM volume under `local_disks` unless its storage is shared (`shared=1`),
  which Phase 5 treats as a blocker; the ISO entry must therefore be `shared=1` like the template entries (U: lab check I6).
- Privileges: attaching a volume to a VM needs `VM.Config.CDROM` on the VM (in `PVEVMAdmin`) and access to the storage entry (in `PVEDatastoreAdmin` /
  `PVEVMAdmin` on `/storage`, as for the template entries; U: lab check I5).

## 4. Design

### 4.1 One entry per ISO folder

The first time an ISO is used, the plugin makes sure the secondary store is mounted (the existing `css-` entry, as for templates) and then adds a read-only
directory entry for the ISO's folder, **on the mounted secondary store**:

```
dir: csi-<hash of server, export and folder>
    path /mnt/css-<...>                      (the secondary store's mount point)
    content iso
    content-dirs iso=template/tmpl/2/231
    shared 1
    is_mountpoint yes
    create-subdirs 0
    create-base-path 0
```

The ISO is then `csi-<hash>:iso/<file>.iso`. The name is a hash because folder paths are long; it is built like the template entry names (`ProxmoxStorageNames`,
new `isoStorageId`, prefix `csi-`). The entry is created through the API with the token (as the template entries are), is **kept** after use, and is shared by every VM
that uses the ISO; a different ISO gets its own entry. The number of entries is the number of **different ISOs ever attached**, not VMs or accounts. The docs say so
and how to remove unused ones; removing one automatically when no VM uses it is possible later but needs a scan of the VMs' configurations, so it is not part of this.

The file name must end in `.iso` or `.img`, otherwise Proxmox would not list it: the command fails with a message that says so.

### 4.2 Commands

- **`AttachCommand` and `DettachCommand` (ISO only)**, answered by `ProxmoxResource` through the VM handler. For anything that is not an ISO disk (a data volume) the answer
  stays "unsupported" until data volumes exist.
  - Attach: find the VM by name (cluster-wide, like stop); map `diskSeq` to the drive (4.3); make sure the entry exists and is active on the VM's node; set
    `<drive>=<volid>,media=cdrom` on the VM and wait for the task. For an HA VM the configuration change is not redirected, so there is nothing to wait for.
  - Detach: set the drive to `none,media=cdrom` (eject), which keeps the drive for the next ISO. An ISO that is not the one in the drive is answered with success.
  - A drive that does not exist on a running VM cannot be added (IDE): the command **fails with a clear message** ("stop and start the instance to add a CD-ROM
    drive"). This cannot happen when the drives were created at start (below) and `vm.iso.max.count` was not raised afterwards.
- **`StartCommand`** (an ISO at start): `ProxmoxVmConfig.startOptions` adds a drive for **every ISO disk of the command**, filled or empty: `<drive>=<volid>,media=cdrom` or
  `<drive>=none,media=cdrom`, after the handler has made sure the entry for each ISO exists. The `boot` option becomes `order=<cdrom drive>;scsi0` when the
  bootloader is `CD`, and `order=scsi0` otherwise (and `scsi0;<cdrom>` is not used: once the OS is installed the admin detaches the ISO, which is CloudStack's flow).
  Drives that the VM has from an earlier start but that are not in the command are set to empty so that a detached ISO does not come back.
- **`CreateObjectCommand`** (a blank root volume, the deploy from an ISO): the same VM creation as for a template, without `import-from`: the root disk is
  `<pool entry>:<size in GiB, rounded up>,format=qcow2`; the answer is a `CreateObjectAnswer` whose volume has the same path form as before
  (`<vmid>/vm-<vmid>-disk-0.qcow2`) and the real size, so the later commands (start, stop, delete, migrate) need no change. A volume that is not a root volume is answered
  "unsupported" for now.
- **Host capability:** the startup command's host details get `host.cdrom.max.count` = 4 (IDE has four slots; the first drive is `ide2` by Proxmox convention), so that
  `vm.iso.max.count` up to 4 works. Without it core clamps to 1.

### 4.3 Slot to drive

CloudStack's slot `3 + i` becomes the IDE drive in this order: `ide2`, `ide3`, `ide0`, `ide1` (i = 0 to 3). Slot 3 is therefore `ide2`, as everywhere in Proxmox.

### 4.4 Guests that cannot use the defaults

A blank VM is for installing an operating system, and some installers (Windows) do not have drivers for what the plugin uses today: the **virtio-scsi** disk and the **virtio**
NIC. KVM lets the user choose with the VM details `rootDiskController` and `nicAdapter`. Proposed: the plugin honours both, mapping `ide`, `sata` and `scsi`/`virtio-scsi` to the root disk
(`ide0`/`ide1`... would collide with the CD-ROM slots, so `sata0` for `sata`, `scsi0` otherwise, and **`ide` is refused** with a message) and `e1000`, `rtl8139`, `virtio`
to the NIC model; the default stays unchanged. This part is small but touches the root disk, so it is marked as a **decision** (section 8): without it, ISO deployment works for
operating systems with virtio drivers (all current Linux) only.

## 5. Privileges

No new privilege beyond the ones already documented (`PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`), if lab check I5 confirms that adding an entry and attaching
its volume work with them. requirements.md gets the confirmation.

## 6. Risks and limits

- **Entries accumulate** (4.1); harmless and documented. Removal of an entry that a VM still uses fails in Proxmox, which is a safety net.
- **An ISO deleted from CloudStack** leaves its entry and, if a VM still has it in a drive, a CD-ROM that points to a missing file (the VM runs; the drive reads nothing).
- **A node that cannot mount the secondary store** fails the attach with the entry's status in the message (the same message as for templates).
- **A VM with an ISO in a drive cannot be migrated** if the entry was made without `shared=1`; entries made by this plugin are shared. Entries from an earlier plugin version
  do not exist (new feature).
- **The CD-ROM count is bounded by IDE** (4); a larger `vm.iso.max.count` is clamped by core to the advertised 4.
- **No hot-add of a drive.** A VM started before `vm.iso.max.count` was raised needs a stop and start; the error says so.

## 7. Lab checks before building (on a Proxmox node, with a CloudStack ISO folder on the secondary store, and a test VM)

All with the API token (typed with `read -rs`, never pasted), unless a check says root.

| # | Check | What it settles |
|---|---|---|
| I1 | Find the secondary store's mount (`pvesm status`, a `css-` entry) and an ISO folder in it. Create the `csi-` entry through the token (POST `/storage`, parameters as in 4.1) and list `GET /nodes/<n>/storage/<id>/content?content=iso` | the entry-per-folder approach lists the ISO as `iso/<file>.iso`; the token may create it |
| I2 | On a **stopped** test VM: `PUT config ide2=<volid>,media=cdrom` by token; `qm config` shows it | attach to a stopped VM, privileges (also I5) |
| I3 | On a **running** test VM: with an existing empty drive (`ide2=none,media=cdrom`), `PUT config ide2=<volid>,media=cdrom`, then `ide2=none,media=cdrom`; check in the guest or `qm monitor` (`info block`); then try to add a **new** drive (`ide3=...`) to the running VM and note whether it is applied or pending | media change on a running VM works; the behaviour of adding a drive |
| I4 | Create a blank VM with the token (`POST /nodes/<n>/qemu` with `scsi0=<pool>:2,format=qcow2`, `boot=order=ide2;scsi0`, and an ISO in `ide2`), start it and look at the console | deploy from an ISO: the VM boots the installer; the disk size form `<pool>:<GiB>` is accepted; the path form of the volume |
| I5 | Repeat I1 and I2 with a token that has **only** the documented roles (`PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`) | no new privilege is needed |
| I6 | `GET .../migrate?target=<node>` for the VM of I3 with the ISO attached from a `shared=1` entry | Phase 5's precondition check does not block a VM with an ISO |
| I7 | Create the entry with the same path from two nodes' view at once (two POSTs) and a repeated POST | the "exists already" handling (as for templates) |
| I8 | Create a VM with `ide2=<volid>,media=cdrom`, an unsupported file name (`x.txt`) in the folder: is it listed? | the message for a wrong extension |

## 8. Decisions for the reviewer

1. **One entry per ISO folder, kept after use (4.1).** Alternatives (a flat folder for all ISOs, a core change) were rejected: they change where CloudStack stores ISOs.
2. **Up to four CD-ROM drives, IDE `ide2`, `ide3`, `ide0`, `ide1` (4.3)**, advertised through `host.cdrom.max.count` = 4. A smaller number if you prefer one only.
3. **Honour `rootDiskController` and `nicAdapter` (4.4)**, so that Windows installs work, or **leave them for later** and document that the guest needs virtio drivers.
4. **A CD-ROM drive is never added to a running VM**; the command fails with an explanation (stop and start).
5. **Only root volumes are created by `CreateObjectCommand`** for now; data volumes come with their own phase.

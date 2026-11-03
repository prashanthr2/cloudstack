# Phase 10 design: volume snapshots (option B of design-phase10-snapshots.md)

Status: **decided with the user (2026-11-03), nothing built; the lab checks of section 7 come first.** The answers to the questions are in section 8. "V" means read in code (CloudStack in this repository, Proxmox in the mirrors under `promox-src`), "U" means unverified, with a lab check in section 7.

Instance snapshots (option A) are built and lab-verified. This document is about the other feature: the **volume snapshot** (`createSnapshot`, `Snapshot`, `SnapshotPolicy`), which copies a point in time of one volume to **secondary storage**, can be scheduled, and can create a volume or a template. Proxmox has no API for it (design-phase10-snapshots.md section 2), so the plugin does the work on the node with `qemu-img`, over the SSH login that Add Host stores (Phase 10).

## 1. What core sends, what KVM does, what Proxmox would do

Core uses `DefaultSnapshotStrategy` for every hypervisor (V). The commands are the same as for KVM; the plugin answers them.

| Step | Core sends (V) | KVM (V, `KVMStorageProcessor`) | Proposed for Proxmox |
|---|---|---|---|
| Take | `CreateObjectCommand(SnapshotObjectTO)` to a host | creates an internal qcow2 snapshot named by a random UUID on the volume's file; answers a path `<disk path>/<uuid>` | stopped or detached volume: `qemu-img snapshot -c <name> <file>`; volume of a running VM: a Proxmox VM snapshot (`vmstate=0`) named `cs-<uuid>` (section 3). Answer path `<volume path>/<name>` |
| Back up | `CopyCommand` snapshot on primary to snapshot on secondary (`NfsTO`) | `qemu-img convert` of the snapshot into a file on secondary, then **deletes the snapshot on primary** (`deleteSnapshotOnPrimary`) | `qemu-img convert -O qcow2 -l snapshot.name=<name> <file> <secondary>/<path>`, then delete the snapshot on primary, as KVM does. So the primary snapshot is transient and nothing stays in the data file |
| Delete | `DeleteCommand(SnapshotObjectTO)`; on secondary it goes to the secondary storage VM, on primary to a host | `qemu-img snapshot -d` | on primary: `qemu-img snapshot -d` or `DELETE .../snapshot/<name>`; on secondary: nothing in the plugin (core and the secondary storage VM remove the file) |
| Volume from snapshot | `CopyCommand` snapshot on secondary to `VolumeObjectTO` on primary | `qemu-img convert` into the pool | `qemu-img convert -O qcow2` into `images/<holder>/vm-<holder>-<volume uuid>.qcow2`, the same place and name as any data volume (behaviour.md section 20), answer the volume path |
| Revert | `RevertSnapshotCommand` (core allows it when the snapshot is on secondary, the volume is qcow2 (U: that the database has `QCOW2` as the format of a Proxmox volume, lab check V9) and the instance is stopped and has no instance snapshots, V `DefaultSnapshotStrategy.revertSnapshot`, `SnapshotManagerImpl`) | copies the secondary file over the volume | convert into a temporary file next to the volume, then `mv` it over the volume file, then `qemu-img resize` to the volume's size in CloudStack (section 5) |
| Template from snapshot | `CopyCommand` snapshot to `TemplateObjectTO` | convert, write `template.properties` | **not in this round** (section 6) |
| Scheduling, limits, usage | core | core | nothing to do: they call the commands above |

Which endpoint gets each command is chosen by core's default selection for a hypervisor other than KVM (`DefaultEndPointSelector.select`, V: the special routing in `getEndPointForSnapshotOperationsInKvm` is for KVM only). It has to be a Proxmox host that reaches the primary storage; for anything on the secondary store core may pick the secondary storage VM or a host (U, check in the log, lab check V9).

## 2. The mechanics on the node

All of it is `qemu-img` (and `mv`, `rm`, `mkdir`) run as the SSH user, with `sudo` when that user is not root (`ProxmoxSsh.sudo`). The paths:
- primary: `/mnt/<pool uuid>/images/<vmid or holder>/<file>` (read from `GET /nodes/<node>/storage/<id>/content` or the volume's `path`, which the plugin already uses: `ProxmoxApiClient.getImageInfo`);
- secondary: a mount of the NFS export read-write that the plugin makes over SSH when it is needed (section 5).

`qemu-img` runs on one node and the file is on shared NFS, so any node works; the plugin uses the node of the resource that got the command.

## 3. A volume that a running instance uses

**Decided: volumes of running instances and detached volumes are both in this round.**

`qemu-img snapshot -c` on a file that QEMU has open is not safe. A volume of a running instance therefore needs the VM to make the snapshot:
- **Proposed:** a Proxmox VM snapshot with `vmstate=0` named `cs-<uuid>`, which snapshots every disk of the VM (instance snapshots already work this way). The backup then reads **only that snapshot** of the one volume's file (`qemu-img convert -U -l snapshot.name=<name>`, `-U` because the file is open), which is safe because the clusters of a snapshot are never written (U, lab check V2). The VM snapshot is deleted right after the backup (transient), so `move_disk delete=1` (refused while a snapshot exists) and CloudStack's own checks see nothing left over.
- The snapshot of the other disks of the VM is a side effect: some seconds of extra work and an entry in the VM's snapshot list for the length of the backup. A failure in between leaves a `cs-<uuid>` snapshot; `ProxmoxVmSnapshots.deleteAll` removes it when the instance is expunged, and the delete command removes it when CloudStack deletes the snapshot.
- Core refuses a volume snapshot while the instance has a snapshot **with memory** (V `validateVmSnapshot`), and refuses a revert while it has any instance snapshot. Both stay as core has them.

A stopped instance or a detached volume needs none of this: `qemu-img snapshot -c` directly.

## 4. The SSH login in the resource (what must be built first)

The resource (`ProxmoxResource`) has no login today. `host_details` keeps `sshuser`, `sshpassword` (encrypted by the plugin **only when the management server's database encryption is on**; in a lab without it they are plain text, as every password in the database) and `sshfingerprint` (Phase 10), and the management server's private key is the hidden global setting `ssh.privatekey`. Proposal: `ProxmoxServerDiscoverer` puts the login, the key and the fingerprint into the resource's parameters at every connect, as it already does for the API token, and the resource builds an `SshTarget` from them (key first, then password, pinned fingerprint: the same `ProxmoxSsh` that Add Host and Update Host password use). They live in the management server's memory only, next to the API token secret that is there already. After `updateHostPassword` the new login must reach the resource at its next reconnect (it does for the token; U for this).

**Security of the login in the resource.** The login adds no storage and no network exposure: the password is already in `host_details`, the key is the existing hidden setting `ssh.privatekey`, and the resource runs in the management server's JVM next to the API token secret (neither `ResourceManagerImpl` nor the plugin logs the resource's parameters, read in code). What changes is the strength of what is in memory: the API token is limited by the Proxmox roles, the SSH login is root (or a sudo user) on every node, so a heap dump of the management server shows more than before, as it does for the login of a KVM host. **Passwords are in the database as plain text unless database encryption is switched on** (`DBEncryptionUtil` returns its input otherwise): production needs it on, for the SSH password, the token secret and the key alike. The real risk of this feature is command injection: the commands put CloudStack strings (the volume path, the snapshot name, the secondary store URL) into a root shell. Every value is checked against a strict pattern before it is used (UUIDs; paths of `[A-Za-z0-9/._-]` only; the NFS server and export from the store's URL) and anything else is refused, and the existing `ProxmoxSsh.quote` is used on top of it. When the SSH user is not root, `sudo` can be limited to `qemu-img`, `mount`, `mkdir`, `mv` and `rm`.

## 5. Secondary storage, sizes and revert

- **A writable secondary mount, made by the plugin when needed.** The entry the plugin makes for templates is `vers=3,ro` (behaviour.md, template to primary). For snapshots the plugin mounts the secondary store's NFS export read-write over SSH, in its own directory (`/mnt/css-rw-<hash of server and export>`), only when it is not mounted yet (`findmnt` first), and leaves it mounted. It is not a Proxmox storage entry, so Proxmox does not know it and it does not survive a reboot of the node; the next snapshot mounts it again. The export must let the node's root write to it (`no_root_squash`, as for KVM). Mounting the same export a second time with other options next to the read-only entry may be refused (U: the NFS client shares one connection per server and export); lab check V3.
- **Sizes.** The answers carry the virtual size and the physical size (`qemu-img info`), as KVM does.
- **Revert after a resize.** The converted file has the virtual size of the snapshot. If the volume was resized up since, the plugin resizes it back to the size CloudStack has (`VolumeObjectTO.getSize()`), or the disk would be smaller than the database says. Lab check V6. **A note in the UI** at the confirmation of Revert snapshot says so (storage.js has `message.action.revert.snapshot`, and `AutogenView.vue` accepts a message that is a function of the record, so no server code changes); it is shown only for a snapshot of a Proxmox volume, which needs the hypervisor in the snapshot's API record (U: lab check V10).
- **Failures.** A convert that fails removes its partial file on secondary; the primary snapshot is removed in a `finally`; nothing is deleted before the new file is complete (revert uses a temporary file and `mv`).
- **Locking.** One lock per volume path in the plugin, as the holder locks.

## 6. Not in this round

- **Template from snapshot.** It writes a template and its `template.properties` on secondary, which overlaps the template and ISO layout redesign (a later round). It stays unsupported for Proxmox, with the clear text of the answer.
- Incremental snapshots, snapshots of encrypted volumes, group snapshots.
- Moving a volume that has snapshots on secondary: the snapshots are on secondary, not in the file, so the move works as it does now.

## 7. Lab checks before building

| # | Check | Answers |
|---|---|---|
| V1 | On a node, detached data volume: `qemu-img snapshot -c t1 <file>`, then `qemu-img convert -O qcow2 -l snapshot.name=t1 <file> /tmp/out.qcow2`, change the file, compare `qemu-img compare` or checksums of the snapshot copy against the old state | the convert reads the snapshot, not the current data |
| V2 | A running VM: `qm snapshot <vmid> v1 --vmstate 0`, write in the guest, then `qemu-img convert -U -O qcow2 -l snapshot.name=v1 <data file> /tmp/out.qcow2`, mount the copy | a snapshot of a file that QEMU has open is readable and consistent |
| V3 | `mkdir /mnt/rwtest && mount -t nfs -o vers=3 <server>:<export> /mnt/rwtest` while the plugin's read-only entry of the same export is mounted; `touch` and `qemu-img convert` into it as root and as the sudo user | the export lets the node write; whether a second, writable mount of the same export next to the read-only one works; what the options must be |
| V4 | `qemu-img convert -O qcow2 <snapshot file on secondary> /mnt/<pool>/images/<holder>/vm-<holder>-<uuid>.qcow2`, then `pvesm list` and attach it to a VM | a volume made outside the API is a normal data volume to Proxmox |
| V5 | Revert: convert into `<file>.tmp` beside the volume, `mv` over the volume, boot the VM that uses it | the file replaced under the same name works, and the internal snapshots inside it are gone |
| V6 | Resize a volume up, snapshot, resize up again, revert | the size after a revert, and that `qemu-img resize` brings it back |
| V7 | `qemu-img snapshot -d` of one of several internal snapshots | the others stay; the file does not grow |
| V8 | The same with a non-root SSH user using `sudo -n` | `sudo` runs `qemu-img` and the writes need no extra right |
| V9 | `createSnapshot` on a volume with the current build and read `jetty.log` | which endpoint core picks for each command of a hypervisor other than KVM, and the format core has for the volume |
| V10 | `cmk list snapshots` on a snapshot of a Proxmox volume (any state) | whether the record has a `hypervisor` field the UI can use for the revert note |

## 8. Decisions (2026-11-03)

1. **Scope:** take, back up, delete, **create volume from snapshot** and revert. **Template from snapshot** is not in this round (open-issues.md, "Later work").
2. **Volumes of running instances and detached volumes both work** (section 3).
3. **The SSH login in the resource parameters is acceptable;** the points of section 4 (command injection checks, database encryption in production, limited `sudo`) go with it.
4. **The plugin mounts the secondary store read-write over SSH when needed and does not mount it again when it is mounted** (section 5), instead of a second Proxmox storage entry.
5. **Revert resizes the volume back to CloudStack's size,** with a note in the UI at the confirmation, if that needs no core change (section 5).

# Phase 11, step 4 design: a template from a volume and from a snapshot

Status: **design written 2026-11-06, decided with the user 2026-11-08 (section 8), volume part built 2026-11-08 (`ProxmoxTemplates.createFromVolume`, `ProxmoxTemplatesFromVolumeTest`), lab checks not yet run.** "V" means read in code (CloudStack in this repository), "U" means unverified, with a lab check in section 7. Steps 1 to 3 (templates as template VMs, ISOs as binds, the per-operation secondary mount) are built and lab-tested; this step reuses their mount and SSH foundation (`ProxmoxSecondaryMounts.withMount`, `ProxmoxSsh`).

## 1. What is wanted

The two "Create template" buttons of CloudStack: **from a volume** (the root volume of a stopped instance, or a detached volume) and **from a volume snapshot**. Today the plugin answers both with "Copying ... is not supported for Proxmox" (`ProxmoxVmHandler.copy`). The result must be an ordinary template on the secondary store that every other flow already handles: it is listed, copied to other zones by the secondary storage VM, and deployed on Proxmox through step 2 (copy to a pool as a template VM, clone per deploy).

## 2. What core does (V)

`TemplateManagerImpl.createPrivateTemplate` picks the image store and calls `TemplateServiceImpl.createTemplateFromVolumeAsync` or `createTemplateFromSnapshotAsync`, both a plain `copyAsync` that ends in `AncientDataMotionStrategy`. The two cases take **different ways**:

| | From a volume | From a snapshot |
|---|---|---|
| Core's checks | The volume must be inactive: `volumeInactive` refuses a volume attached to a non-stopped instance (so the file is never open while it is read). Encrypted volumes are refused | The snapshot must be `BackedUp`; encrypted refused. Core backs the snapshot up to secondary first when it is only on primary (`backupSnapshotToSecondaryStorageIfNotExists`); the plugin already backs every snapshot up (behaviour.md section 24), so it is on secondary |
| Command | `CopyCommand` (`VolumeObjectTO` on primary, `TemplateObjectTO` on the image store) through `copyObject` | `CopyCommand` (`SnapshotObjectTO`, `TemplateObjectTO`) through `createTemplateFromSnapshot` |
| Sent to | A **host** of the volume's pool (`DefaultEndPointSelector.select(src, dest)` with primary to image: `findEndPointForImageMove`): a Proxmox agent | The **secondary storage VM** of the image store (snapshot and template are both on an image store: `moveBetweenImages`) |
| Who does the work on KVM | `KVMStorageProcessor.createTemplateFromVolume` on the host: `qemu-img convert` into `<template folder>/<uuid>.qcow2`, `template.properties` through `QCOW2Processor` and `TemplateLocation` | `NfsSecondaryStorageResource.copySnapshotToTemplateFromNfsToNfs`: `cp` of the snapshot file and `template.properties`; **only when the snapshot's hypervisor type is `KVM` (or `XenServer`)** |

Answer core expects: a `TemplateObjectTO` with the path `<template folder>/<file>.qcow2`, the virtual size, the physical size and the format; the folder is `template/tmpl/<account id>/<template id>` and is the dest `TemplateObjectTO.getPath()`.

## 3. From a volume: built in the plugin

A host-side operation, like step 2's copy, over the SSH login and a **read-write** per-operation mount of the store (`withMount(ssh, store, false, ...)`; the mount is made for this one command and unmounted at once).

1. **Refuse early** (nothing touched): the source is not a `VolumeObjectTO` on a Proxmox pool; the volume has no path. A data volume is **accepted** (decision D3, changed 2026-11-08). The state of the instance is not checked by the plugin: core refuses a volume of a running instance (section 2), and a template made from a snapshot goes through a detached volume, so no instance is involved.
2. **Find the file**: `ProxmoxStorageNames.mountPath(poolUuid) + "/images/" + volume.getPath()` (the path is `<vmid>/vm-<vmid>-disk-0.qcow2`), `test -f`, and `qemu-img info` for the format (qcow2; a raw file is converted the same way).
3. **Make the folder**: `mkdir -p <mount>/<template folder>`; remember whether it existed (so a failure removes only what this call made).
4. **Convert**: `qemu-img convert -O qcow2 <file> <folder>/<name>.qcow2.tmp`, then `mv` to `<name>.qcow2` (a failure leaves no half file). `convert` writes a **flat** image, so a volume that is a linked clone (design-phase12) is flattened and the template has no backing file; compression is not used (decision D5: the same as KVM).
5. **Write `template.properties`** last, in the format that `TemplateLocation` writes (`id`, `public`, `uniquename`, `qcow2=true`, `qcow2.filename`, `qcow2.size`, `qcow2.virtualsize`, `virtualsize`, `size`; U, lab check T1 compares with one the secondary storage VM wrote). The plugin writes it by `printf` over SSH from values it validated, since the plugin has no `TemplateLocation` (that class is in `cloud-core`, which the plugin may use: decision D6 in section 8 picks between writing the file by hand and using the class on a local temporary file that is then copied).
6. **Answer** the path `<template folder>/<name>.qcow2`, virtual size and physical size (`qemu-img info`: `virtual-size`, `actual-size`), format `QCOW2`, name.

**The file name** is the template's CloudStack UUID (`<template uuid>.qcow2`, `TemplateObjectTO.getUuid()` of the destination) where KVM uses a random UUID: it ties the file to the template when someone looks at the store (the same wish as the names in steps 2 and 3), and a repeated command after a failure overwrites its own file instead of leaving a second one. Decision D2.

**Afterwards** nothing else is needed: the template is Ready on the store; a deploy on Proxmox goes through `copyToPrimary` (a template VM is made on the pool from this file) exactly as for a registered template.

## 4. From a snapshot: needs a change outside the plugin

The command goes to the secondary storage VM, not to Proxmox, and the SSVM code only handles a snapshot whose hypervisor type is KVM or XenServer; for any other type it answers an empty failure (`copySnapshotToTemplateFromNfsToNfs` ends in `new CopyCmdAnswer("")`, V). The snapshot file the plugin backs up is a plain qcow2 (`qemu-img convert -O qcow2`), which is the same thing KVM's code copies. So:

- **Option 1, the real fix (small, in core):** let the SSVM treat `Proxmox` like `KVM` in that one method (`srcData.getHypervisorType() == KVM || == Proxmox`). It is a one-line change in `NfsSecondaryStorageResource`, and it only takes effect after the **system VM template is rebuilt** (the SSVM runs the classes of its image; `upstream-notes.md` item 10 is the same limit). It is core code, so it would be a separate pull request.
- **Option 2, no core change (the workaround, works today after step 4's volume part):** create a **volume from the snapshot** (built, behaviour.md section 24), then **create a template from that volume** (section 3). Two steps in the UI, the same result, and the volume can be removed afterwards.

Recommendation (decision D1): build the volume part now and document option 2 as the way to make a template from a snapshot; raise option 1 as its own change, to be done when the system VM template is rebuilt anyway.

## 5. Failure and cleanup

- A failed convert removes its `.tmp` file; a failed `mv` or properties write removes the file and, when this call made it, the folder (`rmdir`, which only removes an empty one). Nothing is deleted that this call did not create.
- A mount that cannot be made fails the command with the reason (the same text as step 2: the store, the options, the exit code), after the 60 s timeout of the mount; the node's own daemons are never involved (no storage entry for the store).
- A `DeleteCommand` for the template is core's and the SSVM's (`NfsSecondaryStorageResource`); the plugin does nothing on the store.
- The size of a copy can be large (an 100 GiB root disk): the command's wait is the cluster's `create.private.template.from.volume` wait (`cmd.getWaitInMillSeconds()`), used for the convert; U: whether the default of that setting is long enough (lab check T4).
- Locking: one convert per (template folder) is enough, core makes the folder per template; no plugin lock.

## 6. Documents and tests that go with it

- `behaviour.md`: the table of commands (copy volume to template), section 13/25 (templates), the list of what is not supported (template from volume removed; template from snapshot stays with the workaround); `requirements.md`: the NFS export of the secondary store must be writable by the nodes (read-write mount), as for volume snapshots, which already need it; `open-issues.md`: close the "Template from a volume snapshot, and template from a volume" item for the volume part and keep the snapshot part with the core change; `test-log.md`: the T checks.
- Unit tests (new class `ProxmoxTemplatesFromVolumeTest`, same fake SSH link as step 2): the refusals (not a volume, data volume, no path, running VM, store not NFS), the order of the commands (mount rw, mkdir, convert to `.tmp`, mv, properties, unmount), the answer (path, sizes, format, name), the clean-up after each failing step, a repeated command, a mount that fails, values with shell characters refused before they reach a shell, no SSH login.

## 7. Lab checks

| Check | What | Expected |
|---|---|---|
| T1 | `cat template.properties` of a template that the secondary storage VM registered, and of a snapshot-based one if any | The keys and format section 3 step 5 must write |
| T2 | Stopped instance from template T1: Create template from its root volume | Ready; a folder `template/tmpl/<account>/<id>` with `<template uuid>.qcow2` and `template.properties`; sizes in the UI equal `qemu-img info` |
| T3 | Deploy an instance from the new template | A template VM `cs-tmpl-<new uuid>-<pool>` is made on the pool, the clone boots with the data of the instance that the template was made from |
| T4 | A root volume of 100 GiB with data | The convert runs within the wait; time recorded; no `css-tmp` mount is left (`findmnt -t nfs,nfs4`) |
| T5 | Instance running: Create template from its volume | Core refuses it (not the plugin) |
| T6 | Block the NFS server on the node, then Create template from a volume | Fails with the reason within about a minute; nothing left on the store or the node |
| T7 | Remove the volume's file by hand, then Create template | Fails with "does not exist"; nothing left |
| T8 | Linked clone volume (after design-phase12) | The template has no backing file (`qemu-img info` shows none) |
| T9 | Volume snapshot then template from the snapshot | Fails now with core's/SSVM's answer; the workaround (volume from snapshot, then template from volume) works |

## 8. Decisions (2026-11-08)

- **D1: template from a snapshot.** Volume part now. The snapshot case stays unsupported and is recorded in open-issues.md with the two-step workaround (volume from the snapshot, then template from that volume) and the one-line SSVM change as a separate core change, effective after the system VM template is rebuilt.
- **D2: the file name.** The template's UUID (`<template uuid>.qcow2`).
- **D3: a data volume as the source.** First decided 2026-11-08 as "refuse, with a clear message", on the premise that the template would be a data disk template that the plugin cannot use. **That premise was wrong (V, `createPrivateTemplateRecord`):** a template made from any volume gets the type `USER`, not `DATADISK`, so it deploys like any other template. And a volume made from a snapshot is a **data volume**, so refusing data volumes would break the snapshot workaround of D1. Changed to: **a data volume is accepted**, same code path (its file is `<holder>/vm-<holder>-<uuid>.qcow2` below the pool mount). To be confirmed by the user.
- **D4: running instance.** No plugin check (see section 3 step 1).
- **D5: compression.** None (plain `qemu-img convert -O qcow2`), as KVM.
- **D6: `template.properties`.** Written with `TemplateLocation` and `QCOW2Processor` on a local temporary file, then copied with the template, if those classes are usable from the plugin's classpath (checked when building; if not, the lines are written by hand and the lab check T1 compares them).

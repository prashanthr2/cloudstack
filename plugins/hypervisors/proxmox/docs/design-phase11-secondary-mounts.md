# Phase 11 design: templates and ISOs read from a plain secondary mount (no storage entries)

Status: decided 2026-11-05, lab checks M1 to M6 to run, nothing built yet. Replaces the per-account `cst-` and per-ISO `csi-` storage entries (design-phase3 4.1, design-phase6) and the whole-store `css-` entry.

## 1. Why

- Today every account whose templates are used gets a `cst-<account>` entry and every ISO a `csi-<hash>` entry in Proxmox's `storage.cfg` (open issue 8: never removed). They exist only because Proxmox lets a **token** use a file only as a volume of a storage entry (an absolute path is for `root@pam`; design-phase3 section 3).
- The plugin now has an SSH login with sudo (design-phase10-ssh-access.md), which can do the root-only part.
- **A secondary store that goes down must not affect the Proxmox nodes.** An NFS storage entry is polled by Proxmox's own daemons (`pvestatd`, the web UI, `pvesm status`); with a `hard` mount a dead export leaves those processes in uninterruptible sleep and the node shows as unavailable. The existing `css-` entry and the `css-rw-` mount (volume snapshots) are `hard` mounts today.
- KVM and VMware mount the secondary store on demand on the host that needs it (`KVMStorageProcessor.attachIso` through `getStoragePoolByURI`; `VmwareResource.prepareSecondaryDatastoreOnHost`, one datastore per store) and leave it mounted. Neither has Proxmox's cluster-wide, polled entry.

## 2. Decision (2026-11-05)

1. **No Proxmox storage entries for secondary storage** (no `css-`, `cst-`, `csi-`). Proxmox's daemons never see it.
2. **A plain NFS mount over SSH, on demand, per node**, in one place for templates, ISOs, volume snapshots and later template creation: `/mnt/css-rw-<uuid of server:export>` today (to be renamed or split, see 5). Read-only for reads, read-write only where the plugin writes.
3. **Mount options for a dead export that return an error instead of hanging:** `soft,timeo=<short>,retrans=<small>,nosharecache` (`nosharecache` because a secondary export that lies below the primary's export shares one kernel mount with it, and the options of the first mount win: lab VS8), plus the store's `vers=`. Every command that touches the mount runs under `timeout` (`mountpoint -q`, `qemu-img`, `ls`).
4. **Templates:** mount, check the image (no backing file, no data file: the template is untrusted), `qemu-img convert` into the VM's disk file on primary (`images/<vmid>/vm-<vmid>-disk-0.qcow2`), unmount when no other command uses the mount, then create the VM through the API with that disk attached (a disk before its VM is accepted, design-phase3 section 3). `CopyCommand` template to primary only checks the file and returns a path.
5. **ISOs:** attach and detach run over SSH as root: `qm set <vmid> --ide2 <path>,media=cdrom`, because the API refuses a path from the token. The mount stays while an ISO from it is attached (QEMU reads it live) and is removed when none is.
6. **Migration:** `PrepareForMigrationCommand` (destination) mounts the stores of the VM's ISOs there first, as KVM does; the path must be the same on every node.
7. **HA and node restarts (accepted gap):** when Proxmox itself restarts a VM that has an ISO attached on a node without the mount, or after a reboot, the VM does not start until the ISO is detached. CloudStack-started VMs get the mount in the start command. To be documented in behaviour.md when built; for a VM CloudStack starts, an ISO whose mount cannot be made is ejected with a log line rather than failing the start (to confirm when designing the start).
8. Strings from CloudStack reach the root shell only as validated paths and slot names (same rule as volume snapshots: regex, `ProxmoxSsh.quote`).

## 3. Not decided yet

- Whether the unmount after a template deploy is worth its races with a second deploy on the same node (a per-store counter in the resource, or leave the read-only mount for a few minutes).
- Removing the entries that exist from the old design: left as they are, removed by hand (listed in the upgrade note when built). Instances started from the old path keep working until they are stopped.
- Template path stored in `template_spool_ref` (`cst-<id>:<template id>/<file>` today): old paths must still be understood.

## 4. Related, to check (open-issues.md)

The config drive ISO is on secondary storage by default and on primary with `vm.configdrive.primarypool.enabled`. Core sends `HandleConfigDriveIsoCommand` for it; the Proxmox plugin has no handler. Its path is `configdrive/...` below the store, not `template/tmpl/...`: the path handling must not assume the template layout.

## 5. Lab checks (run before building)

M1 plain `soft,ro,nosharecache` mount works for the SSH user with sudo; M2 ISO by path on a stopped and a running VM (boot, eject, attach); M3 online migration and instance snapshots (with and without memory) with a path ISO, with the mount missing and present on the destination; M4 a dead export (a separate NFS server, not the lab's primary): the node, `pvestatd`, `pvesm status` and the VM stay responsive and errors come back within the timeout; M5 convert a template from the mount into `images/<vmid>/` and create the VM with that disk (time against `import-from`: 7.6 s for 8 GiB); M6 the image check finds a backing file and a data file. Steps and results in test-log.md.

# Phase 3 design: deploying a VM (template, root volume, start, stop, destroy)

Status: **proposal, for review.** Nothing described here is implemented yet.

Sources: "CloudStack" means read in this repository. "Proxmox source" means the 8.x mirrors read on 2026-10-12 (`qemu-server` 8.3.13,
`pve-storage` 8.3.6, `pve-access-control` 8.2.2, `pve-common` 8.3.1; the lab runs 8.4.21). "Lab" means run on the lab. Everything else is
marked **to verify** and is covered by a check in section 8.

## 1. Scope

**Phase 3a (this design):** register a template with hypervisor `Proxmox`, deploy a VM from it on an **L2 or shared VLAN network** (no
virtual router), start, stop, reboot and destroy it, and keep its state in sync. Disk space is the NFS pool from Phase 2.

**Not in 3a** (section 6): console, data volumes, resize, ISO, user data, live migration, HA, snapshots.

## 2. What CloudStack does for a deploy (verified in code)

1. The **template** must be `Ready` on secondary storage in the zone (the SSVM downloads it; registering a Proxmox template needs no
   core change, it takes the generic path like KVM).
2. **Template to primary.** `CopyCommand(src = TemplateObjectTO on the NFS secondary store, dest = TemplateObjectTO on the primary
   pool)` goes to a host of the pool's cluster. The source carries `path`, `uuid`, `origUrl`, `checksum`, `size`, `format` and the
   `NfsTO` (url, nfs version); the destination carries the `PrimaryDataStoreTO` (uuid, host, path). Answer:
   `CopyCmdAnswer(TemplateObjectTO{path, size, physicalSize, format})`. CloudStack stores `path` as `template_spool_ref.local_path` and
   `install_path`, and the size.
3. **Root volume.** `CopyCommand(src = that template on primary, dest = VolumeObjectTO)` to a host of the cluster. `VolumeObjectTO` has
   `uuid`, `name`, `size` (the root disk size), `dataStore`, `vmName`, `deviceId`. Answer: `CopyCmdAnswer(VolumeObjectTO{path, size,
   format})`. CloudStack stores `volumes.path`, and the size and the **format only if the answer sets it**.
4. **`StartCommand(VirtualMachineTO)`** to the deployment host, with the disks as `DiskTO{data = VolumeObjectTO (path, dataStore),
   deviceId}`. The answer must be a **`StartAnswer`**; a plain answer fails the start.
5. **Stop** is `StopCommand` (`getVmName()`, force flag; answer `StopAnswer`), **reboot** `RebootCommand` (answer `RebootAnswer`).
   On expunge CloudStack stops the VM, then deletes its volumes with `DeleteCommand(VolumeObjectTO)`.

Which host gets the copy commands: `DefaultEndPointSelector` picks a **random `Up` host that has the pool** (no hypervisor filter), not
the host the VM will run on. So the root volume is created on one node and the VM may be started on another.

## 3. What Proxmox can do (from the Proxmox source)

| Topic | Fact |
|---|---|
| Getting a qcow2 onto a node | A read-only `nfs` entry on the secondary export with `content-dirs import=<folder>` (S9-6); otherwise only through the API: **`POST /nodes/{node}/storage/{storage}/download-url`** (an HTTP(S) URL; content `import`; the file lands directly in the storage's `import/` directory; needs `Datastore.AllocateTemplate` on the storage and `Sys.Audit`/`Sys.Modify` on `/` or `Sys.AccessNetwork` on the node) or an upload (not used). Files must end in `.qcow2`, `.raw`, `.vmdk`, `.ova`. **Listing is not recursive and a volume name cannot contain a folder (S9-5).** |
| Import into a VM disk | `scsi0=<storage>:0,import-from=<storage>:import/<file>.qcow2,format=qcow2` in a VM **create** or **config update**. The size is taken from the source. The source must be an `import` (or `images`) volume of a storage; **an absolute path is for `root@pam` only, and an API token never counts as `root@pam`**. The source is checked as untrusted (no backing file, no data file). |
| Disk before its VM | A disk can be allocated for a VM id that has no VM, and a VM created later accepts it (lab, S7). But the only API that **fills** a disk from an image is the VM-level `import-from`. The storage-level copy API is experimental and documented as failing. |
| Clone | `POST .../qemu/{vmid}/clone` (full copy, or linked clone from a *template* VM on qcow2/NFS). Creates the new VM together with its disks. |
| Ownership | A disk belongs to the VM id in its path. Deleting a VM (`DELETE .../qemu/{vmid}?purge=1`) deletes the disks **it owns** and keeps attached disks of another owner (lab, S7). |
| VM create | `POST /nodes/{node}/qemu` is asynchronous (returns a task id, UPID). `vmid` 100..999999999. Names use the DNS-name format; **`i-2-10-VM` is valid**; no length limit and no duplicate check. |
| NIC | `net0=virtio=<mac>,bridge=<bridge>,tag=<vlan>,rate=<MB/s>,firewall=0`. `rate` is in megabytes per second, CloudStack's `networkRateMbps` is megabits. A plain bridge name matches `^[-_.\w\d]+$`. |
| Power | `status/start`, `shutdown` (guest agent or ACPI, `timeout`, `forceStop`), `stop` (hard), `reset`, `reboot`: all asynchronous. `status/current` reports `status` (running or stopped) and `qmpstatus`. A VM under Proxmox HA is started and stopped through the HA manager. |
| Config update | `POST .../config` (asynchronous, accepts `import-from`) or `PUT .../config` (synchronous). A stopped VM applies changes at once. `PUT .../resize` grows a disk (never shrinks) and needs `VM.Config.Disk` and `Datastore.AllocateSpace`. |
| Privileges | Create needs `VM.Allocate`, `Datastore.AllocateSpace` on each storage, and one `VM.Config.*` privilege per option group (CPU, Memory, HWType, Options, Network, Disk). Start and power need `VM.PowerMgmt`. A bridge needs `SDN.Use` according to the API text; **how a plain `vmbrN` is authorised is not in the mirrors (check P5)**. `args`, `lock`, real `serial` devices, `skiplock` and absolute-path import are root-only. |

Not in the mirrors, so only known from experience and **to verify**: the task status call (`GET /nodes/{node}/tasks/{upid}/status` with
`status` and `exitstatus`), `GET /cluster/nextid`, and the offline-migrate call (`POST .../qemu/{vmid}/migrate`).

## 4. The design

### 4.1 Template on primary: a read-only entry per template

The management server cannot mount the secondary store, and a token cannot import from an absolute path. What does work (spikes S9-2, S9-6)
is a Proxmox **`nfs` storage entry on the secondary export, limited to one template's folder**:

1. The `CopyCommand` carries the secondary store (`NfsTO`: server, export, NFS version) and the template's path
   (`template/tmpl/<account id>/<template id>/<uuid>.qcow2`). The plugin creates, if it does not exist yet, the entry
   `cst-<template uuid>` (the id must start with a letter): `type nfs`, `server`, `export`, `content=import`,
   `content-dirs import=template/tmpl/<account id>/<template id>`, `options=vers=<n>,ro`. Proxmox mounts it on the nodes as needed.
2. It reads the entry's listing (`GET /nodes/{node}/storage/cst-<uuid>/content?content=import`) to confirm the file is visible and to
   get its size.
3. Answer `CopyCmdAnswer(TemplateObjectTO{path = "cst-<uuid>:import/<uuid>.qcow2", size, format = QCOW2})`; nothing is copied yet.
   Running it again just finds the entry.
4. When CloudStack deletes the template from the pool (`DeleteCommand` for a template), the entry is removed.

Why one entry per template: the entry can map `import` to **one** folder (`content-dirs`), and a volume name cannot contain a folder
(S9-5: `unable to parse directory volume name`). The cost is one storage entry (and one NFS mount, which stays after the entry is removed,
as in test p5) per template in use.

**Limits:** NFS secondary stores only; the template must be on the secondary store (any registration method, uploaded or created from a
volume or snapshot, works the same). Proven in S9-7 and S9-8: the token creates and deletes the entry, and a node that did not receive the command mounts it
and imports from it.

### 4.2 Root volume: the VM is born with its disk

Because the only way to fill a disk is the VM-level import, the **root volume copy creates the VM**:

1. `vmid` = `GET /cluster/nextid`.
2. `POST /nodes/{node}/qemu` (values URL-encoded) with `vmid`, `name` = the instance name (`VolumeObjectTO.vmName`), a small `memory`, and
   `scsi0=<cs-uuid>:0,import-from=cst-<template uuid>:import/<template uuid>.qcow2,format=qcow2` (the template path from 4.1). Wait for the task.
3. If the root disk is larger than the template's, `PUT .../resize disk=scsi0 size=<bytes>`.
4. Answer `CopyCmdAnswer(VolumeObjectTO{path = "<vmid>/vm-<vmid>-disk-0.qcow2", size, format = QCOW2})`. The volume path therefore carries the
   vmid, which is how later commands find the VM; the disk is owned by that VM, so destroying the VM removes it.

The VM is created on the node that received the command, which is **a random pool host, not necessarily the one CloudStack will start
the VM on** (section 2). Handled in 4.3.

A full copy per deploy is slower than KVM's backing-file clone for large templates; linked clones from a Proxmox template VM could be
added later as an optimisation.

### 4.3 StartCommand

1. Find the VM: the vmid from the root disk's volume path (`<vmid>/...`); fall back to the VM list by name.
2. If the VM is on another node than the deployment host's node, move it first with an **offline migrate** to that node (a stopped VM on
   shared storage only moves its configuration; confirmed by token in S9-9).
3. Configure it with `POST .../config` from the `VirtualMachineTO`:

| CloudStack | Proxmox |
|---|---|
| `cpus` | `cores` (and `sockets=1`) |
| `maxRam` (bytes) | `memory` (MiB), ballooning off |
| guest OS (`platformEmulator`, from the `guest_os_hypervisor` row for `Proxmox`) | `ostype` (`l26`, `win10`, `w2k8`, ...; `other` when there is no row, as KVM falls back to the display name) |
| disks | `scsi<deviceId>=<cs-uuid>:<path>`, `scsihw=virtio-scsi-single`, `boot=order=scsi0` |
| nics | `net<deviceId>=virtio=<mac>,bridge=<traffic label>,tag=<vlan id>,rate=<Mbps / 8>,firewall=0` (no `tag` for an untagged network) |
| name | unchanged (the instance name) |
| everything else | defaults; CPU speed and the offering's other details are not mapped yet |

4. `POST .../status/start`; wait for the task; answer **`new StartAnswer(cmd)`**.

### 4.4 Stop, reboot, destroy

- **Stop:** a missing or already stopped VM is success. A force stop uses `status/stop`; otherwise `shutdown` with a timeout and
  `forceStop=1`, so a guest that ignores ACPI is not left running. Answer `StopAnswer`.
- **Reboot:** `status/reboot`; answer `RebootAnswer`.
- **Destroy:** CloudStack stops the VM and then sends `DeleteCommand` for the root volume. For a Proxmox root volume that **means
  destroying the VM** (`DELETE .../qemu/{vmid}?purge=1`), which removes the disk it owns; if the VM is already gone the answer is
  success. No `finalizeExpunge` command is needed.

### 4.5 State and names

The VM name is the CloudStack instance name, so the ping report already matches (Phase 1). A VM created at volume time and still stopped
reports `PowerOff` while CloudStack has a deploy job running; CloudStack ignores power reports for VMs with a pending job.

### 4.6 L2 networking

- The bridge comes from `NicTO.name`, the **traffic label**. For an unknown hypervisor CloudStack asserts or returns null, so Proxmox
  reads the **KVM label column** (core change, section 5). Set the zone's KVM label for guest traffic to the Proxmox VLAN-aware bridge (the
  lab: `vmbr1`).
- The VLAN is the number in `broadcastUri` (`vlan://100`); none for `vlan://untagged`. The MAC and `deviceId` come from CloudStack.
- CloudStack's system VMs on this zone run on the KVM host; the Proxmox VMs join the same VLAN through the trunk.

### 4.7 Tasks and timeouts

Create, import, download, resize, start and stop are asynchronous. A small helper waits for a task id to finish, reading the task status
and, on failure, the task log for the reason (which becomes the answer text). Import and download can take minutes for a large template;
the commands' own timeouts come from CloudStack (the template copy has its own long setting), and a global plugin setting for the polling
limit is proposed (`proxmox.task.timeout`).

### 4.8 The hypervisor guru and the guest OS mapping

Consistent with KVM, XenServer and VMware, the guest OS mapping lives in `guest_os_hypervisor`, with `hypervisor_type = 'Proxmox'`,
`hypervisor_version = 'default'` and `guest_os_name` = the Proxmox `ostype`. The lookup is the same call KVM uses
(`findByOsIdAndHypervisor`, which falls back to the `default` version through `hypervisor_capabilities`).

- **Rows:** `schema-42300to2400.sql` inserts one row per existing guest OS (`INSERT ... SELECT` from `guest_os`, with a guard against
  duplicates) with the `ostype` chosen from the display name, following the Proxmox documentation of the option
  (`other`, `wxp`, `w2k`, `w2k3`, `w2k8`, `wvista`, `win7`, `win8`, `win10`, `win11`, `l24`, `l26`, `solaris`; default `other`):

  | CloudStack guest OS (display name starts with) | `ostype` |
  |---|---|
  | Windows XP | `wxp` |
  | Windows 2000 | `w2k` |
  | Windows Server 2003 | `w2k3` |
  | Windows Server 2008 (including R2) | `w2k8` |
  | Windows Vista | `wvista` |
  | Windows 7 | `win7` |
  | Windows 8, Windows Server 2012 (including R2) | `win8` |
  | Windows 10, Windows Server 2016, Windows Server 2019 | `win10` |
  | Windows 11, Windows Server 2022, Windows Server 2025 | `win11` |
  | Solaris, OpenSolaris | `solaris` |
  | Linux with a 2.4 kernel (none in the stock list) | `l24` |
  | BSD, Apple, Novell, DOS, `Other` (not the PV entries), `None`, an unrecognised Windows | `other` |
  | all other guest OS (Linux, and the `Other PV` entries) | `l26` |

  The values are the ones Proxmox lists for the `ostype` option, which says what each enables ("special optimization/features for specific
  operating systems"). New guest OS entries added by later upgrade scripts add a Proxmox row next to the KVM one with
  `ADD_GUEST_OS_AND_HYPERVISOR_MAPPING`.
- **Guru:** `ProxmoxHypervisorGuru` sets `os` (display name) and `platformEmulator` (the row's name, or `other` when there is none),
  as `KVMGuru.configureVmOsDescription` does, and merges the service offering details into the VM details as `ExternalHypervisorGuru` does.
- **API:** `addGuestOsMapping` accepts only KVM, XenServer and VMware, so administrators could not add or fix Proxmox mappings; see
  section 5.

### 4.9 Privileges the CloudStack token needs (confirmed in S9-2, S9-3, S9-4, except where marked)

Roles for **both the user and the token**: `PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`, and **`PVESDNUser` on
`/sdn/zones/localnetwork`** (a plain bridge needs `SDN.Use` on `/sdn/zones/localnetwork/<bridge>/<vlan tag>`). `VM.Migrate` is part of
`PVEVMAdmin` (the offline move in 4.3 is confirmed in S9-9). Every configuration value must be URL-encoded in the request.

## 5. Core changes needed (verified in code)

| Where | Change | Why |
|---|---|---|
| `VolumeOrchestrator.getSupportedImageFormatForCluster` (and `VolumeDaoImpl.getImageFormat`) | `Proxmox -> QCOW2` | otherwise the volume's format is null |
| `PhysicalNetworkTrafficTypeDaoImpl.getNetworkTag` and four branches in `NetworkModelImpl` | Proxmox reads the KVM label column | otherwise the NIC has no bridge (an assertion fails with `-ea`) |
| `schema-42300to2400.sql` | a `hypervisor_capabilities` row for Proxmox (version `default`) | attaching a volume or a VM snapshot dereferences it without a null check; the guest OS lookup uses it for the version fallback |
| `schema-42300to2400.sql` | `guest_os_hypervisor` rows for Proxmox (4.8) | the guest OS mapping, as for the other hypervisors |
| `ManagementServerImpl.addGuestOsMapping` | accept Proxmox in the hypervisor check | otherwise mappings cannot be added or changed through the API |
| later (3b/3c) | resize list in `VolumeApiServiceImpl`; console branch; migration list in `UserVmManagerImpl` | see section 6 |

Known core limits that do not block 3a: `getHypervisorTypeFromFormat(QCOW2)` returns KVM (affects volume upload and some API responses).

## 6. Not in 3a

- **Console.** CloudStack asks the VM's host for a VNC address and port and has the console proxy connect with CloudStack's VNC password.
  A Proxmox VM's VNC is opened by the API (`vncproxy`, a short-lived port and a one-time ticket) and cannot take CloudStack's password, so
  it needs a Proxmox branch in the console code, as External has one. Designed separately.
- **Data volumes, attach, detach, resize, ISO.** Data volumes can be allocated before attaching (S7) under a neutral owner id and are
  deleted explicitly, since deleting a VM leaves foreign-owned disks. Hot-plug needs `AttachCommand`/`DettachCommand`; resize needs Proxmox
  in the core resize list.
- **User data / config drive, VM snapshots, migration (offline move is reused), HA, metrics, VPC and virtual-router networks.**

## 7. Risks and open questions

- One storage entry and one NFS mount per template in use; the number of templates is expected to be small. The entry grows the
  cluster's `storage.cfg`; mounts stay behind when an entry is removed.
- A large template imported per deploy is a full copy; first deploy time depends on the NFS server.
- VM and disk exist on the node that received the volume command; the offline move at start is assumed to work on shared storage.
- `SDN.Use` on `/sdn/zones/localnetwork` is required (S9-3) and must be part of the documented token setup.
- Task polling and `nextid` come from Proxmox parts not in the mirrors (P0).

## 8. Checks

Done (see test-log.md, "Phase 3 spikes"): S9-0 to S9-10, i.e. the import from secondary by token, the NIC on `vmbr1` with `SDN.Use`, start,
stop, destroy, the per-template `nfs` entry (created by token, usable from any node), the offline move of a stopped VM by token (S9-9), and the import time (7.6 s for 8 GiB, S9-10).

Still to run before building:

- `GET /cluster/nextid` (not in the mirrored source) and the task `log` call for the error text of a failed task.

## 9. Lab test plan for 3a

Before starting: the new SQL is applied (capabilities row and guest OS rows), the zone's KVM guest traffic label is `vmbr1`, and the token
has the roles in 4.9 (a `root@pam` token already does).

1. Register a qcow2 template for hypervisor `Proxmox`, format `QCOW2`; it becomes `Ready` on the secondary store.
2. Deploy a VM from it on an L2 network. In Proxmox there is a `cst-<id>` storage entry for the template's folder, and a VM named like the
   instance with an imported root disk, the right CPU, memory and `net0` (bridge `vmbr1`, the VLAN tag); CloudStack shows it Running.
3. Stop and start it again. Repeat until a deploy lands on a different node than the one that created the VM (the log shows
   "Moving Proxmox VM ... from node ... to ...").
4. Reboot it. Destroy and expunge it: the Proxmox VM and its disk are gone.
5. Failure cases: a template folder the Proxmox nodes cannot read (the answer says what to check), a second deploy of the same template
   (the entry is reused, nothing is created again), a stop of a VM that is already stopped, a deploy on a network whose zone has no KVM
   traffic label (the start fails with a message that names the label).

## 10. Decisions for the reviewer

1. Template to primary by a read-only per-template `nfs` entry on the secondary store (4.1); the entry is removed when the template is deleted from the pool.
2. The VM is created together with its root disk, at root-volume time (4.2), and moved offline to the deployment node when needed (4.3).
3. Console, data volumes, resize, ISO and migration are outside 3a.
4. Core changes: image format (2 places), template space, traffic label (DAO and 4 branches), `hypervisor_capabilities` row.
5. Guest OS: `guest_os_hypervisor` rows for Proxmox in the upgrade script (4.8), plus Proxmox accepted by `addGuestOsMapping`, so it behaves like the other hypervisors.

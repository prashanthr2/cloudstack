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
| Getting a qcow2 onto a node | Only through the API: **`POST /nodes/{node}/storage/{storage}/download-url`** (an HTTP(S) URL; content `import`; the file lands directly in the storage's `import/` directory; needs `Datastore.AllocateTemplate` on the storage and `Sys.Audit`/`Sys.Modify` on `/` or `Sys.AccessNetwork` on the node) or an upload. Files must end in `.qcow2`, `.raw`, `.vmdk`, `.ova`. **Listing is not recursive.** |
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

### 4.1 Template on primary: let the node download it

The management server cannot mount the secondary store, and the only API route onto a node is a download or an upload. So the
`CopyCommand` for a template is answered by asking **Proxmox to download the template itself**:

1. Make sure the pool's storage entry has the content types `images,import` (Phase 2 created it with `images` only; the entry is
   updated with `PUT /storage/{id}` when needed; it is idempotent).
2. `POST /nodes/{node}/storage/<cs-uuid>/download-url` with `content=import`, `url` = the template's **original URL**
   (`TemplateObjectTO.origUrl`), `filename=<template uuid>.qcow2`, the checksum and algorithm when CloudStack has them, and
   `verify-certificates` as configured. Wait for the task to finish (section 4.7).
3. Answer `CopyCmdAnswer(TemplateObjectTO{path = "import/<template uuid>.qcow2", size = source size, physicalSize, format = QCOW2})`.
   Running it again for a template that is already there only checks the file and answers.

**Limits, stated plainly:** this works for templates **registered by URL that the Proxmox nodes can reach**. A template created from a
volume or snapshot, or uploaded, has no usable `origUrl`; those need another route (the SSVM's extract URL, or the secondary store mounted
by the nodes; to be designed later). For the prototype the templates are registered by URL from a web server in the lab.

### 4.2 Root volume: the VM is born with its disk

Because the only way to fill a disk is the VM-level import, the **root volume copy creates the VM**:

1. `vmid` = `GET /cluster/nextid`.
2. `POST /nodes/{node}/qemu` with `vmid`, `name` = the instance name (`VolumeObjectTO.vmName`), a small `memory`, and
   `scsi0=<cs-uuid>:0,import-from=<cs-uuid>:import/<template uuid>.qcow2,format=qcow2`. Wait for the task.
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
   shared storage only moves its configuration; **to verify**, P4).
3. Configure it with `POST .../config` from the `VirtualMachineTO`:

| CloudStack | Proxmox |
|---|---|
| `cpus` | `cores` (and `sockets=1`) |
| `maxRam` (bytes) | `memory` (MiB), ballooning off |
| guest OS (`platformEmulator`, else a mapping from `os`) | `ostype` (`l26`, `win10`, `w2k8`, ...; `l26` or `other` when unknown) |
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

### 4.8 The hypervisor guru

`ProxmoxHypervisorGuru` sets `os` and `platformEmulator` (looked up in `guest_os_hypervisor` for `Proxmox`, with a built-in fallback from
the OS name, so no large SQL is needed), and merges the service offering details into the VM details as `ExternalHypervisorGuru` does.

### 4.9 Privileges the CloudStack token needs (to verify, P5)

`VM.Allocate`, `VM.Config.CPU`, `.Memory`, `.Disk`, `.Network`, `.Options`, `.HWType`, `VM.PowerMgmt`, `VM.Audit`, `VM.Migrate` (4.3),
`Datastore.AllocateSpace`, `Datastore.AllocateTemplate`, `Datastore.Allocate` (storage entries, Phase 2), `Sys.Audit` on `/`, and `SDN.Use`
for the bridge if the lab shows it is needed. In built-in roles: `PVEVMAdmin` + `PVEDatastoreAdmin` + `PVEAuditor` (+ `PVESDNUser`), for the
token **and** its user.

## 5. Core changes needed (verified in code)

| Where | Change | Why |
|---|---|---|
| `VolumeOrchestrator.getSupportedImageFormatForCluster` (and `VolumeDaoImpl.getImageFormat`) | `Proxmox -> QCOW2` | otherwise the volume's format is null |
| `StorageManagerImpl.getBytesRequiredForTemplate` | add Proxmox | the template's space on the pool is not reserved otherwise |
| `PhysicalNetworkTrafficTypeDaoImpl.getNetworkTag` and four branches in `NetworkModelImpl` | Proxmox reads the KVM label column | otherwise the NIC has no bridge (an assertion fails with `-ea`) |
| `schema-42300to2400.sql` | a `hypervisor_capabilities` row for Proxmox | attaching a volume or a VM snapshot dereferences it without a null check |
| later (3b/3c) | resize list in `VolumeApiServiceImpl`; console branch; migration list in `UserVmManagerImpl` | see section 6 |

Known core limits that do not block 3a: the "add guest OS mapping" API refuses non-KVM types (rows can only come from SQL, so the guru
carries a fallback); `getHypervisorTypeFromFormat(QCOW2)` returns KVM (affects volume upload and some API responses).

## 6. Not in 3a

- **Console.** CloudStack asks the VM's host for a VNC address and port and has the console proxy connect with CloudStack's VNC password.
  A Proxmox VM's VNC is opened by the API (`vncproxy`, a short-lived port and a one-time ticket) and cannot take CloudStack's password, so
  it needs a Proxmox branch in the console code, as External has one. Designed separately.
- **Data volumes, attach, detach, resize, ISO.** Data volumes can be allocated before attaching (S7) under a neutral owner id and are
  deleted explicitly, since deleting a VM leaves foreign-owned disks. Hot-plug needs `AttachCommand`/`DettachCommand`; resize needs Proxmox
  in the core resize list.
- **User data / config drive, VM snapshots, migration (offline move is reused), HA, metrics, VPC and virtual-router networks.**

## 7. Risks and open questions

- The node-side download requires the template URL to be reachable from the Proxmox nodes; other templates are unsupported in 3a.
- A large template imported per deploy is a full copy; first deploy time depends on the NFS server.
- VM and disk exist on the node that received the volume command; the offline move at start is assumed to work on shared storage.
- The privileges for a plain bridge are unknown (P5); if `SDN.Use` is needed it is granted on the `localnetwork` zone.
- Task polling and `nextid` come from Proxmox parts not in the mirrors (P0).

## 8. Checks to run on the lab before building

Use the unrestricted `root@pam!cloudstack` token for P0 to P4 and the `pveaudit` token for P5. `<id>` is the storage entry of your pool
(`pvesm status`), `<node>` and `<node2>` two Proxmox nodes, `<url>` a template qcow2 served over HTTP from a machine the nodes can reach
(for example `python3 -m http.server 8000` in the folder with the file, on the management VM).

**P0 and P1, download the template through the API** (also shows the task status format and the next id):

```bash
pvesm set <id> --content images,import
```

```bash
curl -ks -X POST -H "Authorization: PVEAPIToken=$TR" https://$H:8006/api2/json/nodes/<node>/storage/<id>/download-url -d content=import -d url=<url> -d filename=p1-test.qcow2 | jq
```

That returns a task id. Then, with the id (it contains `:`, so quote it):

```bash
curl -ks -H "Authorization: PVEAPIToken=$TR" "https://$H:8006/api2/json/nodes/<node>/tasks/<task-id>/status" | jq
```

Repeat the status call until it says stopped, and record the output. Then `pvesm list <id> --content import`, and:

```bash
curl -ks -H "Authorization: PVEAPIToken=$TR" https://$H:8006/api2/json/cluster/nextid | jq
```

Also try one **wrong URL** and record the task's final status (that text becomes the error shown to the administrator).

**P2, create a VM with the imported disk, in one call:**

```bash
qm create 9200 --name p2-test --memory 512 --scsi0 <id>:0,import-from=<id>:import/p1-test.qcow2,format=qcow2
```

```bash
qm config 9200
```

Note how long it took and the disk's size in `pvesm list <id>`.

**P3, resize, NIC and start:**

```bash
qm resize 9200 scsi0 +1G
```

```bash
qm set 9200 --net0 virtio=02:00:00:aa:bb:01,bridge=vmbr1,tag=100,firewall=0 --scsihw virtio-scsi-single --boot order=scsi0
```

```bash
qm start 9200
```

```bash
qm status 9200
```

**P4, stop and move a stopped VM to another node:**

```bash
qm stop 9200
```

```bash
qm migrate 9200 <node2>
```

Record whether it works and how long it takes (the VM's disk is on the shared NFS entry). Clean up with `qm destroy 9200 --purge` on the node that now holds it, then
`pvesm free <id>:import/p1-test.qcow2`.

**P5, the same create with a non-root token.** Give the `pveaudit` user **and** token `PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`
(see section 8 of design-phase2-storage.md for the command pattern), then repeat P2 and P3's `net0` through the API with that token
(`POST /nodes/<node>/qemu` with `vmid=9201 name=p5-test memory=512 scsi0=<id>:0,import-from=<id>:import/p1-test.qcow2,format=qcow2
net0=virtio,bridge=vmbr1,tag=100` and `PUT .../config` with a `net0`). Record **every** permission error text; if the `net0` call is refused for the bridge, grant
`PVESDNUser` on `/sdn/zones/localnetwork` (the zone Proxmox lists for local bridges) to user and token and retry.

## 9. Lab test plan for 3a

1. Register a qcow2 template for hypervisor `Proxmox` by URL; it becomes `Ready` on secondary storage.
2. Deploy a VM from it on an L2 network (KVM guest label set to `vmbr1`): a Proxmox VM named like the instance exists with an imported disk, the
   right CPU, memory and `net0`; CloudStack shows it Running.
3. Stop and start it again, also with the VM started on a different node than the one that holds it.
4. Reboot it. Destroy and expunge it: the Proxmox VM and its disk are gone.
5. Failure cases: an unreachable template URL, a second deploy of the same template (the downloaded file is reused), a pool without the
   `import` content type, a stop of a VM that is already stopped.

## 10. Decisions for the reviewer

1. Template to primary by a node-side download of the template's original URL (4.1), with the stated limit.
2. The VM is created together with its root disk, at root-volume time (4.2), and moved offline to the deployment node when needed (4.3).
3. Console, data volumes, resize, ISO and migration are outside 3a.
4. Core changes: image format (2 places), template space, traffic label (DAO and 4 branches), `hypervisor_capabilities` row.
5. Guest OS: a built-in fallback instead of a large SQL mapping.

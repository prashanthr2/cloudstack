# Open issues (to go over at the end)

Parked items, with what is known and what decides each one. Closed items are removed when they are closed (the test log keeps the history).
Last updated 2026-10-21 (items 19 to 22 added).

## Decisions waiting for you

None at the moment.

## To verify on the lab

| # | Item | Notes |
|---|---|---|
| 4 | **Phase 5 runs still open** | a repeat of P5-6 to verify the "report only running VMs" fix during a node failure (P5-7 already shows the fix works for a Proxmox-initiated move; the trade-off of the fix is that an out-of-band guest shutdown is found after about two ping intervals). P5-1 to P5-7 pass. Steps and expected results are in test-log.md |
| 5 | **HA stop and the shutdown timeout** | After a CloudStack stop `ha-manager` shows the resource `stopped` (confirmed in P5-5). Still not measured: whether the HA stop honours the 60 s shutdown timeout (the VM stopped after about 30 s in L4) |
| 19 | **Control bridge at host add (N1 to N5)** | Implemented and unit tested (behaviour.md section 16, test-log.md), not run on the lab: it needs a Proxmox node that is not added yet (nodes already added are skipped). N1 bridge exists, no change; N2 missing and no `Sys.Modify`, the add fails with the grant-or-by-hand message; N3 pending network changes, refused (and check that `GET /nodes/<node>/network` carries `changes` next to `data`: not seen yet); N4 missing and `Sys.Modify` granted, created and applied; N5 the setting names something that is not a bridge. Also still to see on the lab: the same lab T1 side effect, Proxmox rewrites `/etc/network/interfaces` |
| 20 | **System VM start through the guest agent (8a)** | Implemented and unit tested (behaviour.md section 17), not run on the lab. Needs: the KVM system VM template registered for Proxmox (`registerTemplate`, `templatetype=SYSTEM`) and a zone where the SSVM and CPVM get created on a Proxmox node. To see: the VM boots on the Proxmox node, takes its boot arguments from the guest agent, the readiness check passes, `CheckSshCommand` is answered, the SSVM connects to the management server and the CPVM serves a console |
| 21 | **Patch files for system VMs (8a): implemented, to see on the lab** | Payload CD from one `dir` entry (`csp-...`), design 4.2 step 5 and 4.8, behaviour.md section 17; unit tested (371). To see: the padded read is exactly `agent.zip` (P3 stopped at the unpadded size), `/dev/sr0` is the reserved drive in the template, a second node gets its own directory, and an SSVM and a CPVM come up and connect. Item 20 covers the same run |
| 22 | **Troubleshooting login from a node: implemented, to see on the lab** | Decided: the Proxmox administrator creates one key pair (`ssh-keygen -t ed25519 -f /etc/pve/priv/cloudstack_systemvm` on any node) and puts the public key in the global setting `proxmox.systemvm.ssh.key`; the plugin appends it to every system VM's `authorized_keys` through the guest agent after the VM is configured (behaviour.md section 17, requirements.md "Logging in to a system VM from a node"). To see: that `/etc/pve/priv` accepts the new file (otherwise create it in `/root/.ssh` and copy it to each node), that `ssh -i ... -p 3922 root@<control address>` works from the node, and that a bad value in the setting is ignored with a warning |

## Known limits (not bugs; confirm they are acceptable)

| # | Limit | Notes |
|---|---|---|
| 6 | **A console session is single use** | A reload or a dropped connection needs "View console" again (seen once on 2026-10-20: "disconnected", then it worked). The API token secret is inside the console proxy token |
| 7 | **A stale instance page lets you click "Console" on a VM that is already stopped** | CloudStack answers "must be running to connect console"; correct behaviour. The state of an out-of-band stop reaches CloudStack within one `ping.interval` (60 s) |
| 8 | **ISO storage entries (`csi-...`) are kept after use** | One per different ISO ever attached; no cleanup. Removing unused ones needs a scan of the VMs' configurations. The per-account template entries (`cst-...`) and the per-account `dir` entries are in the same situation |
| 9 | **A CD-ROM drive cannot be added to a running VM** | Proxmox keeps it pending until a restart. VMs started before the ISO build have no drive: one stop and start fixes it. Raising `vm.iso.max.count` needs a restart of the VMs too |
| 10 | **Bridges are not created on the destination of a migration** | The administrator keeps the bridges identical on all nodes; preparation refuses a destination that lacks one |
| 11 | **CloudStack does not set Proxmox's HA maintenance mode** | No REST endpoint (lab L8); run `ha-manager crm-command node-maintenance enable <node>` on a node if Proxmox HA must also avoid it |
| 12 | **TLS: only "verify against the Java truststore" or "do not verify"** | No certificate pinning |
| 13 | **A detached data volume cannot be resized** | Proxmox has no resize for a disk that no VM uses; the volume must be attached first. A shrink is refused (CloudStack itself refuses it for QCOW2) |
| 14 | **The vmids 900000001 to 999999999 are reserved for CloudStack** | They own the data volumes (`900000000 + account id`). A VM with such an id, destroyed with the "destroy unreferenced disks" option of the Proxmox UI, would remove that account's volumes. Documented; the plugin refuses to create a VM in that range |
| 15 | **A SATA data disk cannot be attached to or detached from a running instance** | Proxmox cannot hot-plug it: attach or detach while the instance is stopped. SCSI and VirtIO work live |
| 16 | **Proxmox deletes an image that a VM still refers to** | The plugin deletes a data volume's image only when CloudStack asks (it only does so for a detached volume); nothing in the plugin checks the VM configurations |
| 17 | **Data volumes cannot be copied, snapshotted, uploaded, downloaded or moved to another pool yet** | Answered with "not supported for Proxmox yet" |
| 18 | **`dataDiskController` and `rootDiskController` are read-only for regular users** | The global setting `user.vm.readonly.details` makes them admin-only by default; remove them from it if users should set them |

## Later work (not started)

- System VMs and virtual routers on Proxmox.
- Snapshots (VM and volume), backup.
- Removing storage entries that are no longer used (items 8).
- A way for a hypervisor plugin to declare its own VM settings instead of the hard-coded list in core (upstream-notes.md, 16).
- Everything in upstream-notes.md (items 1 to 18) is to be turned into separate, minimal pull requests; the core changes are kept as small as they are for that reason.

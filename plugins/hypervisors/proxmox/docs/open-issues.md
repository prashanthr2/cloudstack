# Open issues (to go over at the end)

Parked items, with what is known and what decides each one. Closed items are removed when they are closed (the test log keeps the history).
Last updated 2026-10-20.

## Decisions waiting for you

| # | Issue | What is known | Proposal | Status |
|---|---|---|---|---|
| 1 | **Spurious "Out of band VM power off" event and `ERROR ... Invalid answer received in response to a StopCommand` after a normal stop** | The plugin reports every VM on the node, stopped ones as PowerOff (`ProxmoxResource.getHostVmStateReport`). KVM reports only running VMs. After a stop, core gets a PowerOff report for a VM it knows is Stopped and runs its out-of-band handler (`VirtualMachineManagerImpl.handlePowerOffReportWithNoPendingJobsOnVM`). Harmless, seen in the management log at 15:25:22 on 2026-10-20 | Report only running VMs, like KVM. Out-of-band stops are then found by core's missing-report rule after about two ping intervals instead of one; HA and migration are unaffected | **Parked, awaiting a decision** |
| 2 | **Forced stop while a graceful shutdown is still running** | Proxmox has `overrule-shutdown` to abort the running shutdown task; the plugin does not send it. Not reproduced: the forced stop on 2026-10-20 took about 1 s. The option is refused for HA resources ("Not applicable for HA resources") | Send it, and retry without it when Proxmox answers with that HA message | **Parked, add only if a stop is seen to fail** (test: Stop, then Force stop a few seconds later, on a guest that ignores shutdown) |

## Being investigated

| # | Item | Notes |
|---|---|---|
| 13 | **Do simultaneous deployments queue?** | Three instances deployed at once: the third Proxmox task started only after one of the first two finished. No lock or limit in the plugin; candidates are the global setting `execute.in.sequence.hypervisor.commands` (if true, commands to one host run one after the other; Proxmox is not in core's exempt list with KVM, XenServer, Hyper-V and LXC, so it follows the setting) and the plugin's HTTP client (Apache default of 2 connections per Proxmox address, which only matters for very many parallel commands). To check: the value of the setting and the log timestamps of the three `CopyCommand`s |

## To verify on the lab

| # | Item | Notes |
|---|---|---|
| 3 | **CentOS 5 instance boots with `rootDiskController` = `sata`** | The P6-3 instance panicked at boot ("Volume group VolGroup00 not found"): the guest's ramdisk has no virtio-scsi driver, the ISO is not the cause. Set the detail on the stopped instance (or on the template) and start it. If it still panics, try `vm.iso.max.count` = 1 to rule out the four CD drives |
| 4 | **Phase 5 runs still open** | P5-3 (failed migration), P5-6 (node failure, L6), P5-7 (report during a Proxmox-initiated move, L7). P5-1, P5-2, P5-4 and P5-5 pass. Steps and expected results are in test-log.md |
| 5 | **HA stop and the shutdown timeout** | After a CloudStack stop `ha-manager` shows the resource `stopped` (confirmed in P5-5). Still not measured: whether the HA stop honours the 60 s shutdown timeout (the VM stopped after about 30 s in L4) |

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

## Later work (not started)

- Data volumes (`AttachCommand` for a volume, `CreateObjectCommand` for a data volume, volume resize and delete).
- System VMs and virtual routers on Proxmox.
- Snapshots (VM and volume), backup.
- Removing storage entries that are no longer used (items 8).
- A way for a hypervisor plugin to declare its own VM settings instead of the hard-coded list in core (upstream-notes.md, 16).
- Everything in upstream-notes.md (items 1 to 16) is to be turned into separate, minimal pull requests; the core changes are kept as small as they are for that reason.

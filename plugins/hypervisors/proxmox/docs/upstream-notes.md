# Observations about CloudStack itself, for later issues or discussion

Things noticed in CloudStack core while building this plugin. They are **not** changed by the plugin (so that a pull request
stays focused) and are listed here so they can be raised separately, as issues or on the dev list, when it makes sense.
Each entry says where it was seen. Check [SECURITY.md](../../../../SECURITY.md) before filing anything that could be a
security problem: those are reported privately, not as public issues.

## Behaviour

1. **`updateHostPassword` stores the new credentials before the hosts have accepted them.**
   `ManagementServerImpl.updateHostPassword` / `updateHostsInCluster` write the new username and password to `host_details` in
   one transaction; only afterwards does `ResourceManagerImpl.updateClusterPassword` ask each host to apply them
   (`update_passwd_on_host=true`). A failed call therefore leaves the new, possibly wrong, value stored, and hosts that reconnect
   later use it. Possible directions: validate before storing, or restore the previous values when applying fails.
   (Plugin side: documented in requirements.md and behaviour.md section 8.)

2. **The failure message of `updateClusterPassword` was a fixed sentence** whatever the host answered. The plugin's change
   appends the host's reason (see behaviour.md section 8); this is a small improvement to core that applies to every hypervisor
   and could be proposed separately.

3. **The alert text for a lost agent is misleading.** `AgentManagerImpl` sends "If the agent for host [...] is not restarted
   within alert.wait seconds, host will go to Alert state", but for a host that was `Up` the state machine
   (`Status.java`: `Up` + `AgentDisconnected` -> `Alert`) moves it to `Alert` at once. The wait only applies to a host that is
   already `Disconnected`.

4. **`ResourceManagerImpl.markHostAsDisconnected` does not mark the host `Disconnected`.** It raises `AgentDisconnected`, which
   makes an `Up` host `Alert` (its own comment says "Change agent status to Alert").

5. **`host.last_ping` is stored in units of `milliseconds >> 10`**, not seconds, which is easy to get wrong when querying the
   table or comparing it with `alert.wait`. Not documented where the column is defined.

6. **A plugin must put its credentials into the host details to survive a management server restart.** Only the details a
   discoverer returns are stored for a host, and `DiscovererBase.reloadResource` rebuilds the resource from them. This is
   implicit in the code and not described for plugin authors.

## UI

7. **`HostAdd.vue` prepends `http://` to the host field for every hypervisor except External.** For API-driven hypervisors the
   field is just an address, so the prefix is unwanted (the Proxmox plugin ignores the scheme).

8. **The host "Change password" action is shown for every hypervisor except External**, although `updateHostPassword` supports
   a single host only for some of them (XenServer and Proxmox refuse it and ask for a cluster id). There is no cluster-level
   action in the UI.

## Security-relevant (check the reporting policy first)

9. **The VMware and XenServer plugins accept any server certificate, and set that JVM-wide.** `VmwareContext` and
   `XenServerConnectionPool` install a trust-all `TrustManager` and an always-true hostname verifier in static initializers and
   register them as the defaults of `HttpsURLConnection`, so once either class has loaded, every JDK HTTPS connection in the
   management server stops verifying certificates. Check `THREAT_MODEL.md` and `SECURITY.md` for whether this is a known and
   accepted position before deciding how to raise it. (The Proxmox plugin uses its own SSL setup and a setting,
   `proxmox.api.verify.tls`, that defaults to off only to match these plugins.)

## Adding a hypervisor type

10. **A new hypervisor type needs the system VMs rebuilt, and nothing says so.** `HypervisorType.valueOf` throws for a name it does not know,
    and the secondary storage VM runs the API and core classes of the system VM image it was built from. A command that carries a template or
    volume (for example `DeleteCommand` for a template on secondary storage) serialises `"hypervisorType":"Proxmox"`, and an SSVM from before the
    type existed fails to read it: `IllegalArgumentException: HypervisorType 'Proxmox' not found` in `HypervisorTypeAdaptor.deserialize`. On a
    normal upgrade the system VMs are replaced, but a plugin author (or a custom hypervisor name) has to know to rebuild the system VM image
    and recreate the SSVM. Possible directions: make the adaptor tolerant of an unknown type for commands that do not need it, or document it
    where a hypervisor type is added.
    Found on the lab: after the system VM files were rebuilt, the SSVM still failed until `agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh`
    were also copied to `/usr/share/cloudstack-common/vms/` on the KVM host (the agent copies them into the VM from there) and the SSVM was
    patched with `patchSystemVm forced=true` (the version check only compares the scripts' checksum, so a jar-only change is skipped without it).

## UI behaviour

11. **Deleting an instance that the background expunge task has already removed fails with an error, although the end state is the one requested.**
    An instance whose start failed is moved to `Error`, and `UserVmManagerImpl.ExpungeTask` removes instances in the states `Destroyed`, `Expunging` and
    `Error` once they are older than `expunge.delay` (`UserVmDaoImpl.findDestroyedVms`). On the lab the start failed at 23:19:52 and the row was
    marked removed at 23:20:22, while the list in the UI still showed `Error`; a delete clicked at 23:23:05 (the only destroy request, job-200) failed with
    `431: unable to find a virtual machine with id 21`, the first check of `UserVmManagerImpl.destroyVm` (`vm == null || vm.getRemoved() != null`).
    Possible directions: treat a destroy of an instance that is already removed as success, or have the UI refresh the list when a job fails with
    that error. Not specific to Proxmox. (The lab has `expunge.delay` and `expunge.interval` set to 20 seconds, which is why the instance was removed
    so soon; with the defaults of one day the window for this error would be much longer, and users could delete the instance themselves.)

## Migration and HA

12. **`UserVmManagerImplTest.verifyIfHypervisorSupportRootdiskSizeOverrideTest` hard-codes the number of hypervisors without root-disk-size override**
    (`hypervisorTypeArray.length - 6`), so it fails whenever a hypervisor type is added that does not support it. Proxmox made it 7. Possible direction:
    count the types for which `isFunctionalitySupported` is false instead of a constant.
13. **HA skips are per hypervisor type, repeated in four places.** `HighAvailabilityManagerImpl` (host handling, `scheduleRestart`, a further `VMware` check) and
    `VirtualMachineManagerImpl` (out-of-band power off) each list VMware and Hyper-V by name; Proxmox is added to each, and the live-migration allow-lists
    in `ManagementServerImpl` and `UserVmManagerImpl` are separate lists again. A capability on `HypervisorType` ("has its own HA", "supports live
    migration") would replace six places.
14. **The clean-up after a failed migration cannot be told from a user's stop.** Core sends a plain `StopCommand` (`checkBeforeCleanup=false`) to the
    *destination* host while the VM still runs on the source. A hypervisor whose API is cluster-wide (Proxmox) would stop the real VM. The plugin works around
    it with an in-memory mark made by `PrepareForMigrationCommand`. A flag on the command saying "this is the clean-up of a failed migration" would remove the guess.
15. **Proxmox HA only queues start, stop and migrate**, so a command's task ends before the VM has changed state. Any plugin for an API with that property has to wait
    for the real state; nothing in core helps (the `StopAnswer` is trusted at once and a stop of a still-running VM would be recorded as Stopped).
16. **The VM and template setting suggestions are a hard-coded list per hypervisor** (`QueryManagerImpl.fillVMOrTemplateDetailOptions`). A hypervisor that is not listed shows only the generic
    entries (keyboard, UEFI, cpu cores per socket, root disk size), so settings its plugin honours (Proxmox: `nicAdapter`, `rootDiskController`) are invisible in the UI until the list is extended.
    Proxmox is added to it (one block). A way for a hypervisor plugin to declare its own settings would avoid the edit.
17. **`KVMGuru.getCommandHostDelegation` changes commands of every hypervisor.** `HypervisorGuruManagerImpl.getGuruProcessedCommandTargetHost(hostId, cmd)` asks all gurus, and `KVMGuru`
    sets `executeInSequence` on every `StorageSubSystemCommand` (false) and on every `CopyCommand` to a primary storage (true) without looking at the hypervisor, before it returns that the host
    did not change. A hypervisor that is not KVM therefore gets its copy commands serialised per host whatever `execute.in.sequence.hypervisor.commands` says (seen with Proxmox: three
    deployments at once, the third waited). Possible direction: act only on commands whose `hypervisorType` is KVM. The Proxmox plugin works around it in its own guru.
18. **Volume resize is allowed per hypervisor by a hard-coded list** (`VolumeApiServiceImpl.SupportedHypervisorsForVolResize`, KVM, XenServer, VMware, Simulator, Any, None), checked in two places, so a new hypervisor
    type fails "does not support volume resize" for every resize until it is added (Proxmox is added, one line). The same pattern as the live-migration lists (13); a capability on `HypervisorType` would replace them.

19. **The Add Host form has no place for hypervisor-specific requirements** (UI, `AddHost.vue` and the locale strings). A Proxmox host needs things a new user cannot guess: the API token entered as user and token id (`user@realm!id`) plus the secret, the roles the token needs, and the control bridge `cloud0` with the link-local address on every node (with `Sys.Modify` only if CloudStack is to create it). Planned, later, as a separate small UI change: one short static hint shown when the hypervisor is Proxmox that links to the plugin's requirements document and does not repeat commands (so it cannot contradict the plugin's own error message at host add, which stays the authoritative check).
20. **`system.vm.default.hypervisor` cannot be set to a new hypervisor from the UI list.** The setting (`ResourceManager.SystemVMDefaultHypervisor`) lists its valid values as a fixed string (`XenServer, KVM, VMware, Hyperv, VirtualBox, Parralels, BareMetal, Ovm, LXC, Any`), so Proxmox is not offered; it is the only way to steer the secondary storage VM and the console proxy VM to Proxmox in a zone that has several hypervisors (core picks a random hypervisor type of the zone's clusters otherwise). The plugin documents setting it with `updateConfiguration` (worked on the lab, 2026-10-22). Possible direction: build the list from `HypervisorType`.
21. **The system VM template for a hypervisor that core does not know has to be registered by hand** (`registerTemplate` with `templatetype=SYSTEM`; `SystemVmTemplateRegistration` works per known hypervisor and architecture). Worked on the lab; a registration step in the plugin or in core would remove it.
22. **A system VM's agent registers under `hostname/ip` when `hostname` takes more than 500 ms at startup** (`Agent.retrieveHostname` runs it with a 500 ms timeout and falls back to `InetAddress.getLocalHost()`), after which core cannot find the SSVM by name (`findByInstanceName`): "Secondary storage VM [s-N-VM/ip] does not exist", set-up commands skipped, agent state not Up. Seen once on a first boot of a slow VM (open issue 23); not reproduced in three later runs. The fix belongs in core: give `hostname` more time, or use the name from the boot arguments (`name=`) that the VM already has.
23. **Router template and router migration are chosen per hypervisor in two more hard-coded places.** `NetworkHelperImpl.setupHypervisorsMap` and `VirtualNetworkApplianceManager` (the global setting that names the router template of a hypervisor; Proxmox gets its own, `router.template.proxmox`, one key and one line) and `NetworkOrchestrator.prepareNicForMigration` (routers of KVM and VMware include their hot-plugged interfaces; one condition added for Proxmox). The same pattern as items 13 and 18: a capability on `HypervisorType` would replace them.
24. **The system VM scripts count the CPUs by the word "processor" in `/proc/cpuinfo`, which is also in the model name of some CPUs.** `CsRpsrfs.cpus()` in `systemvm/debian/opt/cloud/bin/cs/CsAddress.py`, `ipassoc.sh` and `setup/common.sh` (twice) count every line that contains "processor"; on a guest whose CPU model is "Common KVM processor" (Proxmox's default `kvm64`; QEMU's `kvm64` anywhere) a one-vCPU router counts 2, enables receive packet steering with a mask for 2 CPUs and fails with `OSError: [Errno 75]` in `update_config.py`, so no network command works. The fix is to count the lines that start with "processor" (done in this branch, three small edits).
25. **The list of hypervisors that support a VPC is hard-coded** (`VpcManagerImpl.setupSupportedVpcHypervisorsList`; Proxmox added, one line), the same pattern as items 13, 18 and 23.
26. **`migrateSystemVm` with a host needs storage motion support even when no volume moves.** `MigrateSystemVMCmd` calls `UserVmManagerImpl.migrateVirtualMachineWithVolume` with an empty volume map. That method requires the hypervisor to have the functionality `VmStorageMigration`, then (in `getHostsForMigrateVmWithStorage`) the storage motion capability of the hypervisor and its exact version (`HypervisorCapabilitiesDaoImpl.isStorageMotionSupported`, which falls back to any row only for KVM), and only afterwards finds out that no volume has to move and calls `migrateVirtualMachine`. A router or an SSVM on shared storage of a hypervisor whose plugin does not implement storage motion (Proxmox, not yet) can therefore not be migrated between hosts. Not changed in this branch (decision: it is revisited with the storage migration of the plugin, open issue 17 and 37). Possible direction for upstream: move the capability checks after the "no volume to move" test.
27. **`vpc_netusage.sh` never creates the counters it reads** (`systemvm/debian/opt/cloud/bin/vpc_netusage.sh`): `-c` exits early and `create_usage_rules` is not called, and no other script makes `NETWORK_STATS_<dev>` for a VPC router, so the traffic statistics of a VPC are always zero.

## Pull request plan

How the changes outside `plugins/hypervisors/proxmox` could be split. Each PR is minimal and stands alone; the plugin PR goes last (it needs the type from the first). Item numbers are those of this file.

| PR | Contents | Files | Tests | Notes |
|---|---|---|---|---|
| A. The hypervisor type | `HypervisorType.Proxmox` and its capabilities row, schema entry in `hypervisor_capabilities` | `api/.../Hypervisor.java`, `schema-*.sql` | none yet (add a capability test) | Everything else depends on it; no behaviour change for other hypervisors |
| B. Hard-coded hypervisor lists | Proxmox added to the lists of live migration, volume resize, VM setting suggestions, VPC support (items 13, 16, 18, 23, 25) | `ManagementServerImpl`, `UserVmManagerImpl`, `VirtualMachineManagerImpl`, `VolumeOrchestrator`, `VolumeApiServiceImpl`, `QueryManagerImpl`, `VpcManagerImpl` | one test per list (`VpcManagerImplTest` +1) | One line or block each; could be split per list if a reviewer asks |
| C. Router template setting | `router.template.proxmox`, key in `VirtualNetworkApplianceManager`, registered in the impl, line in `NetworkHelperImpl.setupHypervisorsMap` | 3 files | `NetworkHelperImplTest` +1 | Default name `SystemVM Template (Proxmox)`, zone scoped |
| D. Router NIC preparation for migration | Proxmox next to KVM and VMware in `NetworkOrchestrator.prepareNicForMigration` | 1 file, 1 line | none yet (add one) | Needed for the live migration of a router, which is blocked by item 26 |
| E. System VM scripts | CPU count by lines that start with "processor" (item 24) | `CsAddress.py`, `ipassoc.sh`, `setup/common.sh` | none (scripts); verified on KVM routers | Bug fix independent of Proxmox; also fixes any guest whose CPU model contains "processor" |
| F. Console and VNC | The Proxmox console changes (Phase 4) | `ConsoleAccessManagerImpl`, `ConsoleProxy*`, `NoVnc*`, `VncPasswordAuthenticator`, `WebSocketReverseProxy` | their tests | The largest core change; separate review |
| G. Host, HA and network manager touches | HA (`HighAvailabilityManagerImpl`), `ResourceManagerImpl`, `NetworkModelImpl`, `StoragePoolMonitor`, `PhysicalNetworkTrafficTypeDaoImpl`, `VolumeDaoImpl`, `Config`, schema | see `git diff --stat` against the base | their tests | Each from an earlier phase; see behaviour.md for the reason of each |
| H. UI | `RegisterOrUploadTemplate.vue` and the Add Host hints (item 19) | `ui/` | none | Optional |
| I. The plugin | `plugins/hypervisors/proxmox` and `plugins/pom.xml` | the module | 419 unit tests | Last |

Not proposed as changes (reported only): items 1 to 12, 14, 15, 17, 20 to 22, 26 and 27 are observations for issues; items 26 (storage motion gate for `migrateSystemVm`) and 27 (`vpc_netusage.sh`) would be small fixes in their own PRs when the storage migration is done.


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


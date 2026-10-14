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

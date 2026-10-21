# Phase 8 design: system VMs (SSVM, console proxy) and virtual routers

Status: **proposal, second version for review** (the first version put the control traffic on management-network addresses; that was dropped because it needs the management network stretched to every node). Nothing described
here is implemented. "V" means read in code (CloudStack in this repository; Proxmox in the 8.x mirrors under `promox-src`, branch `stable-bookworm`). "U" means **unverified**: it has not been run, and each U has a lab check in section 8.

## 1. Goal and scope

Run CloudStack's own appliances on Proxmox, so that a zone that has only Proxmox hosts works end to end:

- **8a, the secondary storage VM (SSVM) and the console proxy VM (CPVM).** Needed for templates and ISOs, and for the console.
- **8b, the virtual router (VR)** of isolated and shared networks (DHCP, DNS, user data, source NAT, port forwarding, static NAT, load balancing, firewall, VPN).
- **8c, VPC virtual routers** (tiers, ACLs, private gateways, static routes) and the **hot-plug of NICs**, which VPC and public IP association need and which also gives user VMs "add NIC" and "remove NIC".

Out of scope for now: redundant routers (VRRP; nothing in the design prevents it, but it is not tested), NSX and Netris, security groups (the capability is off), traffic and metric statistics of VMs (`GetVmStatsCommand`,
`GetVmDiskStatsCommand`, `GetVmNetworkStatsCommand`; they are answered "unsupported" today for user VMs too), Kubernetes (CKS) and shared file system VMs.

## 2. How it works on KVM (V)

1. **Template.** Each hypervisor has a system VM template (for KVM `systemvmtemplate-<version>-kvm.qcow2`), a template of type `SYSTEM` for that hypervisor. The SSVM and CPVM use any ready `SYSTEM` template of the zone's
   hypervisor; the VR uses the one named by `router.template.kvm` (zone scoped; other hypervisors have their own setting).
2. **Boot parameters.** `cloud-early-config` (`systemvm/debian/opt/cloud/bin/setup/init.sh`) works out the hypervisor with `virt-what`, and for `kvm` starts `qemu-guest-agent` and **waits up to 60 s for the file
   `/var/cache/cloud/cmdline` to be written by the host through the guest agent**. The KVM agent does that right after the VM starts (`LibvirtStartCommandWrapper` -> `patch.sh`): `guest-ping`, `guest-sync`,
   then `guest-file-open`/`guest-file-write`/`guest-file-close` with the boot arguments base64 encoded.
3. **Networks.** A system VM has a *control* NIC (a link-local `169.254.x.y` address on the host-only bridge `cloud0`), a *management* NIC (an address of the pod's management range; SSVM and CPVM only, they
   connect out to the management server on it), a *public* NIC (SSVM, CPVM, and the VR's source NAT) and, for a VR, *guest* NICs. The KVM agent reaches the VM over the link-local control network: SSH (port 3922, the fixed
   key `id_rsa.cloud`) from the host, copying the patch files (`agent.zip`, `cloud-scripts.tgz`, `patch-sysvms.sh`) and checking `isSystemVMSetup`, all before it answers the start command.
4. **Router commands.** Everything the management server sends to a VR (`NetworkElementCommand`s: IP association, guest network, firewall, DHCP, user data ...) goes to the VR's *host*; the host's `VirtualRoutingResource`
   turns each into files and scripts and runs them in the VR over that SSH. For an IP association the KVM host also **hot-plugs a NIC** into the running VR when the IP's VLAN has none yet, and works out the NIC number
   (`ethN`) of each IP from the MAC addresses of the VM's NICs (`LibvirtComputingResource.prepareNetworkElementCommand(IpAssocCommand)`).

## 3. What is different for Proxmox

- **No agent on the node, and the resource runs in the management server**, so the management server cannot reach a node-local link-local network. VMware and Hyper-V (whose resources also run in the management server) solve this
  by putting the router's control NIC on a *management-network* address and reaching it from the management server (V: `ControlNetworkGuru`). **That needs the management network at every host that runs a router, which is the
  stretched L2 this design avoids.**
- **Proxmox has a channel into the VM that needs no IP network: the guest agent, through the REST API** (V: `Agent.pm`, the VM needs `agent=1` and the agent running in the guest):
  - `agent/ping`,
  - `agent/file-write`: `file`, `content` (at most 60 KiB, base64 by default),
  - `agent/exec`: `command` (a list), `input-data` (stdin, at most 64 KiB), answers a pid,
  - `agent/exec-status`: `exited`, `exitcode`, `out-data`, `err-data`, `out-truncated`.

  They need `VM.GuestAgent.Unrestricted` (or `FileWrite` for `file-write`), and **`PVEVMAdmin` includes `VM.GuestAgent.Unrestricted`** (V: `AccessControl.pm`), so the documented token already has it.
  This is what `patch.sh` does for the boot parameters (with `virsh qemu-agent-command`), and what SSH does for scripts and files, so **the whole control path can go through the API**, on the node the VM runs on, wherever
  Proxmox HA moves it.
- **Core's router code only passes the "router IP" around as a key for the resource's helper methods** (`VirtualRoutingResource` calls `_vrDeployer.executeInVR(routerIp, script, args)` and `createFileInVR`, and the deployer
  makes the connection, V). The deployer of Proxmox can therefore ignore the address and work through the agent of the VM that the address belongs to. The address is the router's **control IP, a link-local address that stays
  unreachable and is only the key**; every `NetworkElementCommand` carries it together with the router's name (`ROUTER_IP`, `ROUTER_NAME`), so the plugin keeps a map from control IP to VM name (filled by `prepareCommand`).
- **The control NIC is still needed**, because the system VM scripts count on `eth0` being the control NIC (`eth1` management or guest ...). It keeps its link-local address, on a **node-local bridge with no ports** (the
  Proxmox counterpart of `cloud0`, created by the administrator on every node, like the guest bridge), named by a setting. Nothing is routed through it and no L2 is stretched.
- **Detecting the hypervisor.** A Proxmox guest is a QEMU/KVM guest: `virt-what` answers `kvm`, so the **unchanged KVM system VM template** takes the `kvm` branch of `init.sh` (U: lab check S4).
- **NIC hot-plug** is supported for a running VM (`PUT config net<N>=...`, the default `hotplug` includes network, U: lab check S6).
- **A Proxmox VM's NICs are `net0` to `netN`, and their order in the guest is the order of the PCI slots** (U: lab check S5), the same as the order of the device ids CloudStack gives, which the system VM scripts rely on.

## 4. Design

### 4.1 Templates

- **System VM template:** the KVM template (`.qcow2`) is registered **by hand** as a template of type `SYSTEM` for the hypervisor **Proxmox** (`registerTemplate` with `templatetype=SYSTEM`, format QCOW2). The automatic
  registration of core (`SystemVmTemplateRegistration`) is by hypervisor and architecture and does not know Proxmox; adding Proxmox there is a larger core change that is not needed (decision 5).
- **Router template:** core finds the router template by the name in `router.template.kvm`, with the hypervisor type of the router. The core change is to let Proxmox use the **same setting as KVM**
  (`NetworkHelperImpl` has a map from hypervisor to setting, V). The Proxmox template is registered with **the same name as the KVM one**; names only have to be unique per hypervisor, and a zone with both just works.

### 4.2 Starting a system VM or a router (`StartCommand` for a VM that is not a user VM)

1. The VM is created and configured as any VM (Phases 3, 6 and 7), with **`agent=1`** for the types SSVM, CPVM and router.
2. **NICs.** The *control* NIC (type Control) goes on the control bridge (setting `proxmox.control.bridge`, default `cloud0`), without a VLAN tag; the plugin checks before the start that the node has that bridge and fails with a
   clear message if not. *Management*, *public* and *guest* NICs map as for user VMs (management and public traffic labels, guest label, the VLAN tag from the broadcast URI). `net<deviceId>` is as CloudStack numbers them.
3. After the VM is running the plugin **waits for the guest agent** (`agent/ping`, polled for a few minutes), writes the boot arguments to `/var/cache/cloud/cmdline` with `agent/file-write`, and the system VM boots on from there.
4. **Waiting for the system VM** (what KVM does over SSH): the plugin runs `isSystemVMSetup` (core's `VirtualRoutingResource`, which uses the deployer) until the setup is done, or fails the start with the reason.
5. **Patching.** KVM copies the patch files at every start. Here the plugin asks the system VM for its scripts' version and checksum (the same script KVM's `PatchSystemVmCommand` uses) and **copies the patch files only if the
   checksum differs from `cloud-scripts.tgz` on the management server** (or when forced). The copy goes through the agent: `exec` with the file in base64 on stdin, 48 KiB of the file per call (the stdin limit is 64 KiB),
   appending to a file, then the same patch script as on KVM; **small for a router (scripts only), large for an SSVM or CPVM (`agent.zip`)**, see the risks and lab check S9.
   The patch files are read from `/usr/share/cloudstack-common/vms/` **on the management server**.

### 4.3 The router and system VM commands

- `ProxmoxResource` implements **`VirtualRouterDeployer`** and owns a `VirtualRoutingResource`, like `VmwareResource`:
  - `executeInVR(controlIp, script, args)` -> `agent/exec` of `/opt/cloud/bin/<script> <args>` in the VM that the control IP belongs to, polled with `exec-status` until it has exited (the timeout is the one the caller gives),
    answering `exitcode == 0` and the output;
  - `createFileInVR(controlIp, path, name, content)` -> `agent/file-write` (a file above 60 KiB goes in chunks with `exec`, the same as a patch file);
  - `prepareCommand`/`cleanupCommand` (below).
  `executeRequest` hands every `NetworkElementCommand` to it. This answers `GetDomRVersionCmd`, `CheckRouterCommand`, the alerts and monitor commands, `NetworkUsageCommand` and the rest.
- **`CheckSshCommand`** (core sends it after a start): answered by asking the VM's agent to run a command, not by connecting to the address.
- **`prepareCommand`** also does what KVM's `prepareNetworkElementCommand` does, with the Proxmox API in place of libvirt: for `IpAssocCommand` (and the VPC variant), `SetupGuestNetworkCommand` and `SetSourceNatCommand` it
  reads the VM's configuration, **finds the NIC number from the MAC address** of each IP (`net<N>` is `eth<N>`), **hot-plugs a NIC** when the IP's VLAN has none (the bridge is `IpAddressTO.networkName`, the traffic label core puts
  there, V; the VLAN is its `broadcastUri`), and sets `nicDevId`. A NIC that is no longer needed is removed when its last IP is released.
- **`PatchSystemVmCommand`** (the "patch system VM" API and the check after an upgrade): the same steps as 4.2 step 5.

### 4.4 NIC hot-plug for any VM

`PlugNicCommand`, `UnPlugNicCommand` (and the router NIC updates above) set or delete `net<deviceId>` on the running VM. This makes **"add NIC to instance" and "remove NIC from instance"** work for user VMs on Proxmox (they are
"unsupported" today), and is what VPC tiers and public IPs on new VLANs use.

### 4.5 Core changes (fewer than in the first version; the control network needs none)

1. `NetworkHelperImpl`: a router on Proxmox uses the router template setting of KVM (one line in the map).
2. `VpcManagerImpl`: Proxmox in the list of hypervisors that support VPC.
3. `NetworkOrchestrator.prepareNicForMigration`: Proxmox next to KVM and VMware for routers (their hot-plugged NICs are not in the database), so a router can be live migrated (Phase 5).

Not needed any more (they were in the first version): `ControlNetworkGuru`, `NetworkModelImpl.getNetworkTag`, the router boot arguments for the management route, and the SSH check target of the SSVM and CPVM.

### 4.6 HA, migration and console

- **HA:** the system offerings have HA on, so with `proxmox.ha.manage` the plugin puts system VMs under Proxmox HA like any HA VM (Phase 5). A restarted VM boots from its saved `/var/cache/cloud/cmdline` (the file stays on its disk).
- **Live migration** of a VM with a hot-plugged NIC works (the NIC is in the VM's configuration); core's `prepareNicForMigration` change above covers the database side. The agent channel follows the VM to its new node, because
  the plugin looks the VM up cluster-wide by name for every call.
- **Console:** the console of an SSVM or a VR is the websocket console of Phase 4. The CPVM *itself* is the proxy and needs the patched files of Phase 4 (delivered by 4.2 step 5, a slow copy when it is a development build).

## 5. Privileges

No new privilege is expected: `VM.GuestAgent.Unrestricted` (agent ping, exec, file write), `VM.Config.Network` (NIC hot-plug) and `VM.Config.Options` are all in `PVEVMAdmin`. requirements.md gets the confirmation from lab checks
S1 to S3 and S6. **Note:** `VM.GuestAgent.Unrestricted` allows running commands as root in any guest with an agent (that is how the router commands are run); it is part of the existing role, not new, but it is now used.

## 6. Risks and limits

- **The patch copy of an SSVM or CPVM can be slow.** The files go in 48 KiB pieces through the API, one call each (a file of N MiB needs about 21 x N calls). It only happens when the scripts' checksum on the management server differs
  from the one in the system VM, that is with a development build or a template of another version; a template that matches the server needs no copy. The size of the files decides how bad it is (lab check S9; the
  plugin logs the progress and has a timeout setting).
- **A VR or system VM without a working guest agent cannot be managed at all** (no IP path to fall back on). The KVM template has the agent and enables it for `kvm`.
- **Every router command is several API calls** (a file write, an exec and polls of its status); a router update takes longer than over SSH, a fraction of a second more per command.
- **The control bridge must exist on every node** (an empty Linux bridge, named by `proxmox.control.bridge`, default `cloud0`), like the guest bridge today; the start fails with a message if the node lacks it.
- **Redundant routers, NSX, Netris, security groups, VM statistics and CKS are not part of this phase** (section 1).
- **The core changes are small** (4.5) and are recorded in upstream-notes.md.

## 7. Test plan once built

**Unit tests:**
- The start of a system VM: `agent=1`, the NICs mapped (control to the control bridge, the others as user VMs), a node without the control bridge failing with a message, the boot arguments written after the agent answers, a failed ping or write failing the start.
- The deployer against a fake API: `executeInVR` (exit code, output, timeout, polling), `createFileInVR` (a small file, a file above 60 KiB in chunks), the control IP to VM name map, a VM that is not found.
- The router commands: the NIC number from MAC addresses, the hot-plug of a missing NIC with the VLAN tag, the removal of a NIC, `nicDevId` set.
- `PlugNicCommand`, `UnPlugNicCommand`, `CheckSshCommand`, `PatchSystemVmCommand` (checksum equal: nothing copied; different: chunked copy and the patch script).

**Lab runs, after the build:**
- 8a: SSVM and CPVM come up and register (template download works), the console of a user VM and of the CPVM itself.
- 8b: an isolated network with a VR (DHCP, DNS, user data), a public IP with source NAT, port forwarding, static NAT, firewall rules.
- 8c: a VPC with two tiers, an ACL, a public IP in another VLAN (a NIC hot-plugged into the VR), add and remove a NIC on a user VM.

## 8. Lab checks before building (on a Proxmox node with the token; a throwaway VM from the KVM system VM template)

| # | Check | What it settles |
|---|---|---|
| S1 | With a running throwaway VM that has `agent=1` and a working guest agent: `POST .../agent/ping` and `POST .../agent/file-write` (`file=/tmp/x`, `content=...`) by the token, then read the file in the guest | files can be written through the API with the documented roles |
| S2 | `POST .../agent/exec` (`command=["/bin/sh","-c","echo hi; echo err 1>&2; exit 3"]`) and `GET .../agent/exec-status?pid=` by the token; then a command that takes 5 seconds; then one that prints about 1 MiB | exit code, output, the polling, the time per call, what happens to a large output |
| S3 | `exec` with `input-data` (64 KiB of base64) into `base64 -d >> /tmp/big` repeated 20 times, then `sha256sum` against the source; time it | a large file can be copied in chunks, and how fast |
| S4 | Boot the KVM system VM template as a Proxmox VM with `agent=1` (no cmdline): `virt-what` says `kvm`, `qemu-guest-agent` runs, `init.sh` waits for `/var/cache/cloud/cmdline`; then write it through S1 and watch the VM configure itself (`/var/log/cloud.log`) | the unchanged template takes the KVM path on Proxmox and accepts the file written by the API |
| S5 | A VM with `net0`, `net1`, `net2` on different bridges: in the guest `ip link` shows `eth0`, `eth1`, `eth2` in that order, with the MACs of `net0`, `net1`, `net2` | the NIC order the system VM scripts expect |
| S6 | On a running VM: `PUT config net3=virtio=<mac>,bridge=<bridge>,tag=<vlan>` by the token, see `eth3` appear; then `PUT config delete=net3` | NIC hot-plug and hot-unplug by the token, with a VLAN tag |
| S7 | Create an empty Linux bridge `cloud0` (no ports) on a node, and start a VM with `net0=virtio=<mac>,bridge=cloud0` | the node-local control bridge works and is listed by `GET /nodes/<n>/network` |
| S8 | Register the system VM template as `SYSTEM` for the hypervisor Proxmox (`registerTemplate`) and see it become Ready on the secondary store | the template can be registered by hand |
| S9 | The sizes of `agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh` in `/usr/share/cloudstack-common/vms/` of the management server | how long a chunked copy takes for a router and for an SSVM or CPVM |

## 9. Decisions for the reviewer

1. **No IP path to the system VMs: the whole control path goes through the guest agent API** (exec, file write), with the control NIC on a **node-local empty bridge** (`proxmox.control.bridge`, default `cloud0`). No management
   addresses for routers, no stretched L2. Replaces the first version's use of management-network addresses.
2. **Boot parameters through the guest agent API** (`agent/file-write`), with `agent=1` for system VMs.
3. **Patching only when the scripts' checksum differs, copied through the agent in chunks** (slow for an SSVM or CPVM of another version); no SSH and nothing needed on the nodes.
4. **The router template setting is shared with KVM** (`router.template.kvm`, the Proxmox template registered with the same name), instead of a new setting.
5. **The system VM template is registered by hand** (no change to `SystemVmTemplateRegistration`).
6. **Split into 8a (SSVM, CPVM), 8b (VR) and 8c (VPC and NIC hot-plug)**, each with its lab runs.
7. **Redundant routers, VM statistics, NSX, Netris, security groups and CKS are left for later.**

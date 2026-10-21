# Phase 8 design: system VMs (SSVM, console proxy) and virtual routers

Status: **proposal, for review.** Nothing described here is implemented. "V" means read in code (CloudStack in this repository; Proxmox in the 8.x mirrors under
`promox-src`, branch `stable-bookworm`). "U" means **unverified**: it has not been run, and each U has a lab check in section 8.

## 1. Goal and scope

Run CloudStack's own appliances on Proxmox, so that a zone that has only Proxmox hosts works end to end:

- **8a, the secondary storage VM (SSVM) and the console proxy VM (CPVM).** Needed for templates and ISOs, and for the console.
- **8b, the virtual router (VR)** of isolated and shared networks (DHCP, DNS, user data, source NAT, port forwarding, static NAT, load balancing, firewall, VPN).
- **8c, VPC virtual routers** (tiers, ACLs, private gateways, static routes) and the **hot-plug of NICs**, which VPC and public IP association need and which also gives user VMs "add NIC" and "remove NIC".

Out of scope for now: redundant routers (VRRP; nothing in the design prevents it, but it is not tested), NSX and Netris, security groups (the capability is off), traffic and metric
statistics of VMs (`GetVmStatsCommand`, `GetVmDiskStatsCommand`, `GetVmNetworkStatsCommand`; they are answered "unsupported" today for user VMs too), Kubernetes (CKS) and shared file system VMs.

## 2. How it works on KVM (V)

1. **Template.** Each hypervisor has a system VM template (for KVM `systemvmtemplate-<version>-kvm.qcow2`), a template of type `SYSTEM` for that hypervisor. The SSVM and CPVM use any ready `SYSTEM`
   template of the zone's hypervisor; the VR uses the one named by `router.template.kvm` (zone scoped setting; for other hypervisors `router.template.vmware` and so on).
2. **Boot parameters.** The system VM does not read a kernel command line; `cloud-early-config` (`systemvm/debian/opt/cloud/bin/setup/init.sh`) works out the hypervisor with `virt-what`, and for `kvm`
   starts `qemu-guest-agent` and **waits up to 60 s for the file `/var/cache/cloud/cmdline` to be written by the host through the guest agent**. The KVM agent does that right after the VM
   starts (`LibvirtStartCommandWrapper` -> `patch.sh`): `guest-ping`, `guest-sync`, then `guest-file-open`/`guest-file-write`/`guest-file-close` with the boot arguments base64 encoded.
3. **Networks.** A system VM has a *control* NIC (a link-local `169.254.x.y` address on the host-only bridge `cloud0`), a *management* NIC (an address of the pod's management range, on the
   management bridge), a *public* NIC (SSVM, CPVM, and the VR's source NAT) and, for a VR, *guest* NICs. The KVM agent reaches the VM over the link-local control network: it opens SSH (port 3922,
   the fixed key `id_rsa.cloud`) from the host, copies the patch files (`agent.zip`, `cloud-scripts.tgz`, `patch-sysvms.sh`) and checks `isSystemVMSetup`, all before it answers the start command.
4. **Router commands.** Everything the management server sends to a VR (`NetworkElementCommand`s: IP association, guest network, firewall, DHCP, user data ...) goes to the VR's *host*;
   the host's `VirtualRoutingResource` turns each into scripts and files and runs them in the VR over the same SSH. For an IP association the KVM host also **hot-plugs a NIC** into the running VR when the
   IP's VLAN has none yet, and works out the NIC number (`ethN`) of each IP from the MAC addresses of the VM's NICs (`LibvirtComputingResource.prepareNetworkElementCommand(IpAssocCommand)`).

## 3. What is different for Proxmox

- **No agent on the node, and the resource runs in the management server.** The link-local control network is not reachable from the management server, so the KVM way of working in a VM cannot be used.
  **VMware and Hyper-V have the same constraint (their resource also runs in the management server) and core already has the answer for them, which Proxmox can follow:**
  - For a VMware or Hyper-V **router**, `ControlNetworkGuru` gives the control NIC a **management-network address** instead of a link-local one ("we have to get management/private ip for the control
    nic for vmware/hyperv due ssh issues", V); `VirtualNetworkApplianceManagerImpl` adds the management CIDR and gateway to its boot arguments so the router has a route back to the management server.
  - For a VMware **SSVM/CPVM** the control NIC is placed on the management network (`NetworkModelImpl.getNetworkTag` turns Control into Management for VMware); for Hyper-V, `CheckSshCommand` is aimed at the
    management NIC instead of the control NIC (`ConsoleProxyManagerImpl`, `SecondaryStorageManagerImpl.verifySshAccessOnManagementNicForSystemVm`).
  - The VMware resource implements `VirtualRouterDeployer` (core) and runs `VirtualRoutingResource` itself: SSH **from the management server** to the router's control IP with the fixed key
    `scripts/vm/systemvm/id_rsa.cloud` that ships with CloudStack, and the patch files from `/usr/share/cloudstack-common/vms/` **on the management server** (`VmwareResource`, V).
- **The boot parameters need another channel, and Proxmox has one.** The guest agent API `POST /nodes/{node}/qemu/{vmid}/agent/file-write` (`file`, `content`, at most 60 KiB, base64 by default) does what
  `patch.sh` does with `virsh qemu-agent-command` (V: `Agent.pm`). It needs `VM.GuestAgent.FileWrite` or `VM.GuestAgent.Unrestricted`, and **`PVEVMAdmin` includes `VM.GuestAgent.Unrestricted`** (V:
  `AccessControl.pm`), so the documented token already has it. The VM needs the agent enabled (`agent=1`, which creates the virtio-serial channel the system VM's `qemu-guest-agent` listens on, U).
- **The patch files are too big for the agent** (60 KiB per write against several MiB), so they are copied over SSH, as VMware does.
- **Detecting the hypervisor.** A Proxmox guest is a QEMU/KVM guest: `virt-what` answers `kvm`, so the **unchanged KVM system VM template** takes the `kvm` branch of `init.sh` (U: lab check S2).
- **NIC hot-plug** is supported by Proxmox for a running VM (`PUT config net<N>=...`, the default `hotplug` includes network, U: lab check S4).
- **A Proxmox VM's NICs are `net0` to `netN`, and their order in the guest is the order of the PCI slots** (U: lab check S3), the same as the order of the device ids CloudStack gives, which the system VM scripts rely on (`eth0` control, `eth1` management ...).

## 4. Design

### 4.1 Templates

- **System VM template:** the KVM template (`.qcow2`) is registered **by hand** as a template of type `SYSTEM` for the hypervisor **Proxmox** (`registerTemplate` with `templatetype=SYSTEM`, format QCOW2). The automatic
  registration of core (`SystemVmTemplateRegistration`, which also fills `router.template.*`) is by hypervisor and architecture and does not know Proxmox; adding Proxmox there is possible but is a larger core change that is not
  needed (decision 5).
- **Router template:** core finds the router template by the name in `router.template.kvm`, with the hypervisor type of the router. The one core change is to let Proxmox use the **same setting as KVM** (`NetworkHelperImpl`
  has a map from hypervisor to setting, V). The Proxmox template is then registered with **the same name as the KVM one**; names only have to be unique per hypervisor. A zone that has both just works.

### 4.2 Starting a system VM or a router (`StartCommand` for a VM that is not a user VM)

1. The VM is created and configured as any VM (Phases 3, 6 and 7), with **`agent=1`** added for the types SSVM, CPVM and router.
2. **NICs.** Control and management NICs map to the **management traffic label** (the bridge of the management network), the public NIC to the public label with its VLAN, the guest NICs to the guest label with the
   VLAN (all as for user VMs, 4.3 for the control NIC). `net<deviceId>` is as CloudStack numbers them.
3. After the VM is running the plugin **waits for the guest agent** (`agent/ping`, polled, up to a few minutes), writes the boot arguments to `/var/cache/cloud/cmdline` with `agent/file-write`, and the system VM
   boots on from there.
4. **Waiting for the system VM.** The plugin connects over SSH (port 3922, the key `id_rsa.cloud`, the **control IP**, which on Proxmox is a management-network address, 4.3) as `VirtualRoutingResource.connect` does, copies the
   patch files and checks `isSystemVMSetup` (as KVM and VMware do), then answers the start. A failure at any step fails the start with the reason.
5. The patch files are read from `/usr/share/cloudstack-common/vms/` **on the management server** (the `cloudstack-common` package puts them there; on a development setup they have to be copied there, as they are copied to
   each KVM host today).

### 4.3 Networks and the control IP (core changes, all in the VMware and Hyper-V pattern)

1. `ControlNetworkGuru` (`reserve` and `release`): add **Proxmox** to the router branch, so a router's control NIC gets a management-network address (reachable from the management server).
2. `NetworkModelImpl.getNetworkTag`: treat Control as Management for Proxmox, so the control NIC of an **SSVM/CPVM** (which keeps its link-local address, unused) is put on the management bridge as on VMware.
3. `VirtualNetworkApplianceManagerImpl`: add Proxmox to the VMware/Hyper-V branch that adds the management CIDR and gateway to the router's boot arguments (a route to the management server).
4. `ConsoleProxyManagerImpl` and `SecondaryStorageManagerImpl.verifySshAccessOnManagementNicForSystemVm`: add Proxmox where Hyper-V's `CheckSshCommand` is aimed at the management NIC.
5. `NetworkOrchestrator.prepareNicForMigration`: add Proxmox to KVM and VMware for routers (their hot-plugged NICs are not in the database), so a router can be live migrated (Phase 5).

### 4.4 Router commands

- `ProxmoxResource` implements **`VirtualRouterDeployer`** and owns a `VirtualRoutingResource`, like `VmwareResource`: `executeInVR` and `createFileInVR` run over SSH from the management server (port 3922, `id_rsa.cloud`);
  `executeRequest` hands every `NetworkElementCommand` to it. This also answers `CheckSshCommand`, `GetDomRVersionCmd`, `CheckRouterCommand`, the alerts and monitor commands, `NetworkUsageCommand` and the rest.
- **`prepareCommand`** does what KVM's `prepareNetworkElementCommand` does, with the Proxmox API in place of libvirt: for `IpAssocCommand` (and the VPC variant), `SetupGuestNetworkCommand` and `SetSourceNatCommand` it reads the
  VM's configuration, **finds the NIC number from the MAC address** of each IP (`net<N>` is `eth<N>`), **hot-plugs a NIC** when the IP's VLAN has none (the bridge is `IpAddressTO.networkName`, the traffic label core puts
  there, the VLAN is its `broadcastUri`), and sets `nicDevId`. A NIC that is no longer needed is removed when its last IP is released.
- **`PatchSystemVmCommand`** (the "patch system VM" API and the check after an upgrade): the same SSH steps as in `LibvirtPatchSystemVmCommandWrapper`, from the management server.

### 4.5 NIC hot-plug for any VM

`PlugNicCommand`, `UnPlugNicCommand` (and the router NIC updates above) set or delete `net<deviceId>` on the running VM. This makes **"add NIC to instance" and "remove NIC from instance"** work for user VMs on Proxmox (they
are "unsupported" today), and is what VPC tiers and public IPs on new VLANs use.

### 4.6 HA, migration and console

- **HA:** the system offerings have HA on, so with `proxmox.ha.manage` the plugin puts system VMs under Proxmox HA like any HA VM (Phase 5). A restarted VM boots from its saved `/var/cache/cloud/cmdline` (the file stays on its disk).
- **Live migration** of a VM with a hot-plugged NIC works (the NIC is in the VM's configuration; core's `prepareNicForMigration` change above covers the database side).
- **Console:** the console of an SSVM or a VR is the websocket console of Phase 4. The CPVM *itself* is the proxy and needs the patched files of Phase 4 (delivered by 4.2 step 4 now).

## 5. Privileges

No new privilege is expected: `VM.GuestAgent.Unrestricted` (agent ping and file write), `VM.Config.Network` (NIC hot-plug) and `VM.Config.Options` are all in `PVEVMAdmin`. requirements.md gets the confirmation from lab checks S1 and S4.
**Note:** `VM.GuestAgent.Unrestricted` also allows running commands in any guest through the agent (`agent/exec`); that is part of the existing role, not new.

## 6. Risks and limits

- **The management server must reach the management bridge of the Proxmox nodes** (system VMs have management-network addresses; SSH and the agent connections go both ways). That is the same requirement as VMware.
- **The patch files must be on the management server** at `/usr/share/cloudstack-common/vms/`; a development setup has to copy them there.
- **The guest agent must come up in the system VM** (it does in the KVM template) or the boot parameters never arrive; the start then fails after the wait with that reason.
- **Redundant routers, NSX, Netris, security groups, VM statistics and CKS are not part of this phase** (section 1).
- **Several core changes** (4.3 and 4.1), all one-liners that add Proxmox to the VMware or Hyper-V pattern; each is recorded in upstream-notes.md.

## 7. Test plan once built

**Unit tests:**
- The start of a system VM: `agent=1`, the NICs mapped to the right labels and VLANs, the boot arguments written after the agent answers, a failed ping or write failing the start.
- The router commands: the NIC number from MAC addresses, the hot-plug of a missing NIC with the VLAN tag, the removal of a NIC, `nicDevId` set.
- `PlugNicCommand` and `UnPlugNicCommand`.
- `CheckSshCommand`, `PatchSystemVmCommand` and the deployer methods against a fake SSH.

**Lab runs, after the build:**
- 8a: SSVM and CPVM come up and register (template download works), the console of a user VM and of the CPVM itself.
- 8b: an isolated network with a VR (DHCP, DNS, user data), a public IP with source NAT, port forwarding, static NAT, firewall rules.
- 8c: a VPC with two tiers, an ACL, a public IP in another VLAN (a NIC hot-plugged into the VR), add and remove a NIC on a user VM.

## 8. Lab checks before building (on a Proxmox node with the token; a throwaway VM from the KVM system VM template)

| # | Check | What it settles |
|---|---|---|
| S1 | With a running throwaway VM that has `agent=1` and a working guest agent: `POST .../agent/ping` and `POST .../agent/file-write` (`file=/tmp/x`, `content=...`) by the token, then read the file in the guest | the boot parameters can be written through the API with the documented roles |
| S2 | Import the KVM system VM template (qcow2) as a VM disk, add `agent=1`, boot it (it has no cmdline) and see that `virt-what` says `kvm`, that `qemu-guest-agent` runs and that `init.sh` waits for `/var/cache/cloud/cmdline` | the unchanged template takes the KVM path on Proxmox |
| S3 | A VM with `net0`, `net1`, `net2` on different bridges: in the guest `ip link` shows `eth0`, `eth1`, `eth2` in that order, with the MACs of `net0`, `net1`, `net2` | the NIC order the system VM scripts expect |
| S4 | On a running VM: `PUT config net3=virtio=<mac>,bridge=<bridge>,tag=<vlan>` by the token, see `eth3` appear; then `PUT config delete=net3` | NIC hot-plug and hot-unplug by the token, with a VLAN tag |
| S5 | From the management server: SSH to a VM on the management bridge (port 22 is enough for the check) | the management server reaches the management bridge of the nodes |
| S6 | Write a cmdline file through S1 into the booted system VM of S2 and watch it configure itself (`/var/log/cloud.log`) | the whole chain for an SSVM, without CloudStack |
| S7 | `file-write` of a payload close to 60 KiB, and what the API says above it | the real size limit |
| S8 | Register the system VM template as `SYSTEM` for the hypervisor Proxmox (`registerTemplate`) and see it become Ready on the secondary store | the template can be registered by hand |

## 9. Decisions for the reviewer

1. **The VMware and Hyper-V pattern for the control network** (a router's control NIC on a management-network address, an SSVM/CPVM's control NIC on the management bridge, `CheckSshCommand` on the management
   NIC): the only way a management-server resource can reach the VMs, with the core changes listed in 4.3.
2. **Boot parameters through the guest agent API** (`agent/file-write`), with `agent=1` for system VMs.
3. **Patching over SSH from the management server**, with the patch files on the management server, as VMware does.
4. **The router template setting is shared with KVM** (`router.template.kvm`, the Proxmox template registered with the same name), instead of a new setting.
5. **The system VM template is registered by hand** (no change to `SystemVmTemplateRegistration`).
6. **Split into 8a (SSVM, CPVM), 8b (VR) and 8c (VPC and NIC hot-plug)**, each with its lab runs.
7. **Redundant routers, VM statistics, NSX, Netris, security groups and CKS are left for later.**

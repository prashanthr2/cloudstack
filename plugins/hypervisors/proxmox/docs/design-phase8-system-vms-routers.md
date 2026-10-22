# Phase 8 design: system VMs (SSVM, console proxy) and virtual routers

Status: **proposal, third version.** Implemented so far, unit tested and not yet run on the lab: the control bridge check and creation at host add (4.7, behaviour.md section 16), and the start of system VMs and routers with the commands through the guest agent (4.2 steps 1 to 4, 4.3 except the NIC hot-plug; behaviour.md section 17). The patching with the payload CD (4.2 step 5, 4.8; behaviour.md section 17) is implemented and unit tested too. 8b is implemented and unit tested (behaviour.md section 18): interface numbers by MAC address, the hot-plug and removal of public interfaces, the traffic counters, the reboot of a router, and core changes 1 and 3 of 4.5; run on the lab: pending. Not implemented: `PlugNicCommand` and `UnPlugNicCommand` for user VMs (4.4), the VPC list (4.5, change 2), and the VPC-specific commands, all of 8c.

- Version 1 put the control traffic on management-network addresses: dropped, it needs the management network stretched to every node.
- Version 2 sent the control traffic through the guest agent API and left the control address unused.
- **Version 3 (this one)** keeps the guest agent API as the only control path, and adds a **real node-local link-local network on every node, for troubleshooting only**: an administrator logs in to the node and reaches a system VM's shell from there, as on KVM. No SSH credentials of the nodes are held by CloudStack.

"V" means read in code (CloudStack in this repository; Proxmox in the 8.x mirrors under `promox-src`, branch `stable-bookworm`). "U" means **unverified**: it has not been run, and each U has a lab check in section 8.

## 1. Goal and scope

Run CloudStack's own appliances on Proxmox, so that a zone that has only Proxmox hosts works end to end:

- **8a, the secondary storage VM (SSVM) and the console proxy VM (CPVM).** Needed for templates and ISOs, and for the console. Includes the control bridge and the troubleshooting access (4.7).
- **8b, the virtual router (VR)** of isolated and shared networks (DHCP, DNS, user data, source NAT, port forwarding, static NAT, load balancing, firewall, VPN).
- **8c, VPC virtual routers** (tiers, ACLs, private gateways, static routes) and the **hot-plug of NICs**, which VPC and public IP association need and which also gives user VMs "add NIC" and "remove NIC".

Out of scope for now: redundant routers (VRRP; nothing in the design prevents it, but it is not tested), NSX and Netris, security groups (the capability is off), traffic and metric statistics of VMs (`GetVmStatsCommand`, `GetVmDiskStatsCommand`, `GetVmNetworkStatsCommand`; they are answered "unsupported" today for user VMs too), Kubernetes (CKS) and shared file system VMs. The rework of how templates and ISOs reach the nodes is a separate piece, after this phase.

## 2. How it works on KVM (V)

1. **Template.** Each hypervisor has a system VM template (for KVM `systemvmtemplate-<version>-kvm.qcow2`), a template of type `SYSTEM` for that hypervisor. The SSVM and CPVM use any ready `SYSTEM` template of the zone's hypervisor; the VR uses the one named by `router.template.kvm` (zone scoped; other hypervisors have their own setting).
2. **Boot parameters.** `cloud-early-config` (`systemvm/debian/opt/cloud/bin/setup/init.sh`) works out the hypervisor with `virt-what`, and for `kvm` starts `qemu-guest-agent` and **waits up to 60 s for the file `/var/cache/cloud/cmdline` to be written by the host through the guest agent**. The KVM agent does that right after the VM starts (`LibvirtStartCommandWrapper` -> `patch.sh`): `guest-ping`, `guest-sync`, then `guest-file-open`/`guest-file-write`/`guest-file-close` with the boot arguments base64 encoded.
3. **Networks.** A system VM has a *control* NIC (a link-local `169.254.x.y` address, allocated per pod by `ControlNetworkGuru`, on the host-only bridge `cloud0`, whose address on the host is the link-local gateway), a *management* NIC (an address of the pod's management range; SSVM and CPVM only, they connect out to the management server on it), a *public* NIC (SSVM, CPVM, and the VR's source NAT) and, for a VR, *guest* NICs. **The position of the control NIC depends on the type** (V: `init.sh`, `setup_interface_sshd`): `eth0` for SSVM, CPVM and VPC routers, `eth1` for an isolated-network router (`eth0` is its guest NIC). sshd (port 3922) listens on the control address and trusts the management server's public key (`ssh.publickey`, passed in the boot arguments as `authorized_key=`; V: `VirtualNetworkApplianceManagerImpl`, `common.sh`). A KVM host keeps the matching private key as `/root/.ssh/id_rsa.cloud` (V: `ServerResource.SSHPRVKEYPATH`), and the firewall opens 3922 on the control NIC only.
4. **Control.** The KVM agent reaches the VM over that link-local network: SSH from the host, copying the patch files (`agent.zip`, `cloud-scripts.tgz`, `patch-sysvms.sh`) and checking `isSystemVMSetup`, all before it answers the start command. Everything the management server sends to a VR (`NetworkElementCommand`s: IP association, guest network, firewall, DHCP, user data ...) goes to the VR's *host*; the host's `VirtualRoutingResource` turns each into files and scripts and runs them in the VR over that SSH. For an IP association the KVM host also **hot-plugs a NIC** into the running VR when the IP's VLAN has none yet, and works out the NIC number (`ethN`) of each IP from the MAC addresses of the VM's NICs (`LibvirtComputingResource.prepareNetworkElementCommand(IpAssocCommand)`).

## 3. What is different for Proxmox

- **No agent on the node, and the resource runs in the management server**, so the management server cannot reach a node-local link-local network. VMware and Hyper-V solve this by putting the router's control NIC on a *management-network* address and reaching it from the management server (V: `ControlNetworkGuru`). That needs the management network at every host that runs a router: a stretched L2, which this design avoids.
- **Proxmox has a channel into the VM that needs no IP network: the guest agent, through the REST API** (V: `Agent.pm`, the VM needs `agent=1` and the agent running in the guest): `agent/ping`; `agent/file-write` (`file`, `content`, at most 60 KiB); `agent/exec` (`command` as a list, `input-data` as stdin, at most 64 KiB, answers a pid); `agent/exec-status` (`exited`, `exitcode`, `out-data`, `err-data`, `out-truncated`). They need `VM.GuestAgent.Unrestricted` (or `FileWrite` for `file-write`), and **`PVEVMAdmin` includes `VM.GuestAgent.Unrestricted`** (V: `AccessControl.pm`), so the documented token already has it. This is what `patch.sh` does for the boot parameters, and what SSH does for scripts and files, so **the whole control path goes through the API**, on the node the VM runs on, wherever Proxmox HA moves it.
- **Core's router code only passes the "router IP" around as a key for the resource's helper methods** (`VirtualRoutingResource` calls `_vrDeployer.executeInVR(routerIp, script, args)` and `createFileInVR`, and the deployer makes the connection, V). The deployer of Proxmox ignores the address and works through the agent of the VM it belongs to. Every `NetworkElementCommand` carries the control IP and the router's name (`ROUTER_IP`, `ROUTER_NAME`), so the plugin keeps a map from control IP to VM name (filled by `prepareCommand`).
- **The control NIC and its link-local address stay as on KVM** (core allocates them unchanged, no core change). The NIC goes on a **node-local bridge** (the counterpart of `cloud0`) that has the link-local gateway address on the node. Nothing is routed through it, the management server never uses it, and no L2 is stretched. **It is only for troubleshooting** (4.7).
- **Detecting the hypervisor.** A Proxmox guest is a QEMU/KVM guest: `virt-what` answers `kvm`, so the **unchanged KVM system VM template** takes the `kvm` branch of `init.sh` (U: lab check S4).
- **NIC hot-plug** is supported for a running VM (`PUT config net<N>=...`, the default `hotplug` includes network, U: lab check S6).
- **A Proxmox VM's NICs are `net0` to `netN`, and their order in the guest is the order of the PCI slots** (U: lab check S5), the same as the order of the device ids CloudStack gives, which the system VM scripts rely on. The plugin does not assume which `net<N>` is the control NIC: it is the NIC CloudStack marks as type Control.

## 4. Design

### 4.1 Templates

- **System VM template:** the KVM template (`.qcow2`) is registered **by hand** as a template of type `SYSTEM` for the hypervisor **Proxmox** (`registerTemplate` with `templatetype=SYSTEM`, format QCOW2). The automatic registration of core (`SystemVmTemplateRegistration`) is by hypervisor and architecture and does not know Proxmox; adding Proxmox there is a larger core change that is not needed.
- **Router template:** core finds the router template by the name in a global setting per hypervisor (`router.template.kvm` ...). **Proxmox gets its own, `router.template.proxmox`** (decided, replacing the earlier plan to share the KVM one: a zone with both hypervisors can then name the two templates differently, and the "same name for two hypervisors" question does not arise). A `NetworkHelperImpl` line and a new `ConfigKey` in `VirtualNetworkApplianceManager`.
- How the template reaches a node is whatever Phases 3 and 6 do today; the rework of that is the separate later piece.

### 4.2 Starting a system VM or a router (`StartCommand` for a VM that is not a user VM)

1. The VM is created and configured as any VM (Phases 3, 6 and 7), with **`agent=1`** for the types SSVM, CPVM and router.
2. **NICs.** The *control* NIC (type Control) goes on the control bridge (setting `proxmox.control.bridge`, default `cloud0`), without a VLAN tag; the plugin checks before the start that the node has that bridge and fails with a clear message if not. *Management*, *public* and *guest* NICs map as for user VMs (traffic labels, the VLAN tag from the broadcast URI). `net<deviceId>` is as CloudStack numbers them.
3. After the VM is running the plugin **waits for the guest agent** (`agent/ping`, polled for a few minutes; the system VM only waits 60 s for the file after its agent starts, so the poll is frequent), writes the boot arguments to `/var/cache/cloud/cmdline` with `agent/file-write`, and the system VM boots on from there. The boot arguments contain the control address, so the control NIC gets it here.
4. **Waiting for the system VM** (what KVM does over SSH): the plugin runs `isSystemVMSetup` (core's `VirtualRoutingResource`, which uses the deployer) until the setup is done, or fails the start with the reason.
5. **Patching** (what KVM does with `scpPatchFiles`; sizes from an installed management server, `/usr/share/cloudstack-common/vms/`: `agent.zip` 115,550,932 bytes, `cloud-scripts.tgz` 149,002 bytes, `patch-sysvms.sh` 5,135 bytes). The template has the scripts but **no Java agent**, so
   an SSVM or CPVM needs `agent.zip` at its first start (it keeps the agent on its disk afterwards), and a router needs `cloud-scripts.tgz` at every start; `cloud-early-config` waits only 2 minutes for `cloud-scripts.tgz`, and `agent.zip` must be in place by then.
   Through the guest agent API a copy of 110 MiB would take 12 to 34 minutes (lab S3: about 56 kB/s), so the files are delivered in two ways:
   - **Small files (`cloud-scripts.tgz`, `patch-sysvms.sh`): written through the API**, with `agent/file-write` (up to 48,000 bytes) or in pieces with `exec`, a few seconds.
   - **`agent.zip`: through a payload CD.** The plugin keeps one storage entry (`csp-<hash>`, see 4.8) that is a directory on every node, uploads `agent.zip` to it once per node and version with the storage upload API (as a file whose name ends in `.iso`; the bytes are
     raw, not an ISO image: Proxmox only checks the name), and puts it into the VM's CD drive. In the guest one command copies exactly the known number of bytes from `/dev/sr0` to `/var/cache/cloud/agent.zip`. **The file is padded with zeros to a multiple of 2048 bytes before the upload**, because a CD exposes only whole 2048-byte sectors (lab P3: a 115,550,932 byte file showed as 115,550,208 bytes in the guest and the last 724 bytes were missing); the copy still reads only the original size.
   - **Every system VM and router is created with one empty CD drive reserved** (a drive cannot be added to a running VM, but the medium of an existing drive can be changed live, open issue 9), and the medium is ejected again after the copy.
   - **Order:** after the guest agent answers: write the boot arguments; copy `agent.zip` from the CD; then write `cloud-scripts.tgz` last, because `cloud-early-config` stops waiting when that file is complete. `patch-sysvms.sh` is written with it.
   - **Skipped when not needed:** the plugin first asks the VM for its `cloud-scripts-signature` and whether `/usr/local/cloud/systemvm` has files, and compares the signature with the SHA-512 of the management server's `cloud-scripts.tgz`; if they match (a restart of an SSVM or CPVM), nothing is copied and no CD is attached.
   - **If the upload is not possible** (no privilege, no space on the node), the start **fails with a message** that says what to fix; there is no silent slow copy.
   The patch files are read from `/usr/share/cloudstack-common/vms/` **on the management server**.

### 4.3 The router and system VM commands

- `ProxmoxResource` implements **`VirtualRouterDeployer`** and owns a `VirtualRoutingResource`, like `VmwareResource`:
  - `executeInVR(controlIp, script, args)` -> `agent/exec` of `/opt/cloud/bin/<script> <args>` in the VM that the control IP belongs to, polled with `exec-status` until it has exited (the timeout is the one the caller gives), answering `exitcode == 0` and the output;
  - `createFileInVR(controlIp, path, name, content)` -> `agent/file-write` (a file above 60 KiB goes in chunks with `exec`, the same as a patch file);
  - `prepareCommand`/`cleanupCommand` (below).
  `executeRequest` hands every `NetworkElementCommand` to it. This answers `GetDomRVersionCmd`, `CheckRouterCommand`, the alerts and monitor commands, `NetworkUsageCommand` and the rest.
- **`CheckSshCommand`** (core sends it after a start): answered by asking the VM's agent to run a command, not by connecting to the address.
- **`prepareCommand`** also does what KVM's `prepareNetworkElementCommand` does, with the Proxmox API in place of libvirt: for `IpAssocCommand` (and the VPC variant), `SetupGuestNetworkCommand` and `SetSourceNatCommand` it reads the VM's configuration, **finds the NIC number from the MAC address** of each IP (`net<N>` is `eth<N>`), **hot-plugs a NIC** when the IP's VLAN has none (the bridge is `IpAddressTO.networkName`, the traffic label core puts there, V; the VLAN is its `broadcastUri`), and sets `nicDevId`. A NIC that is no longer needed is removed when its last IP is released.
- **`PatchSystemVmCommand`** (the "patch system VM" API and the check after an upgrade): the same steps as 4.2 step 5.

### 4.4 NIC hot-plug for any VM

`PlugNicCommand`, `UnPlugNicCommand` (and the router NIC updates above) set or delete `net<deviceId>` on the running VM. This makes **"add NIC to instance" and "remove NIC from instance"** work for user VMs on Proxmox (they are "unsupported" today), and is what VPC tiers and public IPs on new VLANs use.

### 4.5 Core changes (the control network needs none)

1. `NetworkHelperImpl` and `VirtualNetworkApplianceManager`: a new setting `router.template.proxmox` (default `SystemVM Template (Proxmox)`, zone scoped) and one line in the map from hypervisor to setting.
2. `VpcManagerImpl`: Proxmox in the list of hypervisors that support VPC.
3. `NetworkOrchestrator.prepareNicForMigration`: Proxmox next to KVM and VMware for routers (their hot-plugged NICs are not in the database), so a router can be live migrated (Phase 5).

### 4.6 HA, migration and console

- **HA:** the system offerings have HA on, so with `proxmox.ha.manage` the plugin puts system VMs under Proxmox HA like any HA VM (Phase 5). A restarted VM boots from its saved `/var/cache/cloud/cmdline` (the file stays on its disk).
- **Live migration** of a VM with a hot-plugged NIC works (the NIC is in the VM's configuration); core's `prepareNicForMigration` change above covers the database side. The agent channel follows the VM to its new node, because the plugin looks the VM up cluster-wide by name for every call. **The control bridge keeps the same address on every node, so a migrated or HA-restarted VM stays reachable for troubleshooting from its new node.**
- **Console:** the console of an SSVM or a VR is the websocket console of Phase 4. The CPVM *itself* is the proxy and needs the patched files of Phase 4 (delivered by 4.2 step 5, a slow copy when it is a development build).

### 4.7 The control bridge and troubleshooting access

**The bridge on every node.** An empty Linux bridge named by `proxmox.control.bridge` (default `cloud0`), with the **link-local gateway address on the node** (the address core uses as the gateway of the control network: `NetUtils.getLinkLocalGateway()` with the mask of `NetUtils.getLinkLocalCIDR()`; the same address on every node, as on KVM hosts). The address makes the node a neighbour of every system VM on it: the node has a connected route to `169.254.0.0/16` through the bridge.

**How it is created, no SSH needed.** The Proxmox API creates and applies a bridge: `POST /nodes/<node>/network` (`iface`, `type=bridge`, `cidr`, `autostart`), then `PUT /nodes/<node>/network` to apply (U: lab check T1). It needs `Sys.Modify` on `/nodes/<node>`, which `PVEVMAdmin` does not have. So:

**The control path, step by step (decided).** The check and the creation happen **when a host is added** (the plugin's discovery of the node, before the host is accepted), once per node:

1. The plugin reads `GET /nodes/<node>/network` and looks for the interface named by `proxmox.control.bridge` (default `cloud0`).
2. **The bridge exists and is a bridge:** nothing is changed, whoever made it. If its address is not the link-local gateway, a warning is logged (system VMs still start; only the troubleshooting login is affected). The administrator who creates the bridge by hand before adding the hosts takes this path and needs no privilege.
3. **The bridge is missing:** the plugin tries to create it (`POST /nodes/<node>/network`: `iface`, `type=bridge`, `cidr`, `autostart`, no ports) and applies it (`PUT`, a task that it waits for). It first refuses if `interfaces.new` exists, so that it never applies somebody else's half-made change.
4. **If it cannot create it, adding the host fails** with a message that says what to do. A node without the bridge could not start a system VM or router, and finding that out at the first deployment is worse than at host add. The messages (the first is the usual case, a token without `Sys.Modify`):
   - HTTP 403: *"Proxmox node <node> has no control bridge '<bridge>' and the API token is not allowed to create it (it needs the privilege Sys.Modify on /nodes/<node>). Either grant it: `pveum role add CloudStackNet --privs Sys.Modify`, then `pveum acl modify /nodes/<node> --users <user> --tokens '<user>!<token>' --roles CloudStackNet --propagate 0`, or create the bridge '<bridge>' by hand on every node as described in the Proxmox plugin requirements (\"The link-local control bridge\"), and add the host again."*
   - Pending network changes: *"Proxmox node <node> has pending network changes (/etc/network/interfaces.new), so CloudStack will not apply the network configuration to create the control bridge '<bridge>'. Apply or discard the pending changes in the Proxmox web interface, or create the bridge by hand, and add the host again."*
   - The name is taken by something that is not a bridge: *"Proxmox node <node> has an interface named '<bridge>' that is not a bridge; choose another name in the setting proxmox.control.bridge or remove it."*
   - Any other Proxmox error: the message names the step (create or apply) and carries Proxmox's own text.
5. **After the host is added** the plugin does not create or change anything on the node again. At a later connect it only checks that the bridge is still there and logs a warning if it is not; a system VM or router start on a node without it fails with a message that names the bridge and points to the same requirements section. Nodes that join the cluster later go through the same steps when they are added as hosts.


**Lab T1, second run (the role granted):** create, apply and delete all worked through the API with only `Sys.Modify` on the node. One side effect: Proxmox rewrites the whole `/etc/network/interfaces` from its parsed configuration, so the comments and layout of the file are lost (settings kept). This is documented in requirements.md and is a reason to leave the privilege optional.

**`Sys.Modify` is needed only for this bridge.** Nothing else in Phase 8 or in the phases before changes a node's network configuration: isolated, shared, VPC and public networks use the bridges the administrator already made for the zone's traffic labels, with the VLAN as the NIC's `tag=` (lab S6; for a bridge that is not VLAN-aware Proxmox makes the VLAN sub-bridge itself when the NIC is plugged, `PVE::Network::tap_plug`). `Sys.Modify` also covers other node settings (DNS, time, services), so it is documented as an optional, separate role. A system VM start on a node without it fails with a clear message.

**Using it.** Nothing in the control path depends on it. To reach a system VM's shell, an administrator logs in to the node it runs on (their own account) and runs `ssh -i <private key> -p 3922 root@<control address of the VM>`. The control address is shown in the VM's NICs (the "control" NIC of `listSystemVms` or `listRouters`). **The key is a troubleshooting key that the Proxmox administrator creates** (decided; any cluster size, one key): `ssh-keygen -t ed25519 -f /etc/pve/priv/cloudstack_systemvm` on one node (Proxmox replicates that directory to every node and keeps it root-only), its public half in the global setting `proxmox.systemvm.ssh.key`, which the plugin adds to the `authorized_keys` of every system VM and router through the guest agent after it is configured. The management server's private key is not copied to any node. A list of the nodes' own keys was rejected: a global setting holds at most 8191 characters, about ten RSA keys. Steps and caveats are in requirements.md, "Logging in to a system VM from a node". The system VMs have no root password, keys only.

### 4.8 The patch storage entry

One storage entry of type `dir` is created by the plugin when the first patch is needed (and found again by name afterwards): name `csp-<hash of the path>`, path from the setting `proxmox.patch.path` (default `/var/lib/cloudstack-patch`), content `iso`, **not shared**
(each node has its own directory under the same entry). The API can make the directory: a `dir` storage creates its base path and the standard sub-directories (`template/iso`) on a node when it is activated there (`create-base-path` and `create-subdirs`, both default on; read in
`DirPlugin.pm`), so nothing is created by hand and no SSH is needed. The file for `agent.zip` is `iso/cs-patch-<first 16 characters of the SHA-512 of agent.zip>.iso`, so a new management server version uploads a new file; files of earlier versions on a node are removed when a new one is
uploaded. The entry is one fixed, clearly named entry, not one per ISO. Needs `Datastore.Allocate` (create the entry) and `Datastore.AllocateTemplate` (upload) on `/storage`, which `PVEDatastoreAdmin` has (lab check P1, P2).

## 5. Privileges

- `VM.GuestAgent.Unrestricted` (agent ping, exec, file write), `VM.Config.Network` (NIC hot-plug) and `VM.Config.Options` are all in `PVEVMAdmin`: nothing new for the control path. requirements.md gets the confirmation from lab checks S1 to S3 and S6. **Note:** `VM.GuestAgent.Unrestricted` allows running commands as root in any guest with an agent (that is how the router commands are run); it is part of the existing role, not new, but it is now used.
- **`Sys.Modify` on `/nodes/<node>` only to let the plugin create the control bridge** (lab T1: refused with 403 without it). It is optional: without it the administrator creates the bridge by hand on every node before adding the hosts, or the host add fails with a message saying so. It is the only broader privilege in this phase and is not used for any other network (4.7). It is given in a role of its own (`CloudStackNet`, only `Sys.Modify`) on the node paths, without propagation.

## 6. Risks and limits

- **The first start of an SSVM or CPVM uploads 110 MiB to the node** (once per node and version; later starts find the file). A node without room or a token without upload rights fails the start with a message (4.2 step 5, 4.8). A router only needs a 150 kB file.
- **A VR or system VM without a working guest agent cannot be managed at all** (no IP path to fall back on). The KVM template has the agent and enables it for `kvm`.
- **Every router command is several API calls** (a file write, an exec and polls of its status); a router update takes longer than over SSH, a fraction of a second more per command.
- **The control bridge must exist on every node.** The plugin creates it at host add only with `Sys.Modify`; otherwise it is a documented manual step that must be done before the host is added (the host add fails with a clear message), and a node that loses the bridge later cannot start a system VM or router (clear message).
- **Applying a network change reloads the node's network configuration** (`ifreload`). An empty new bridge does not touch any other interface, and the plugin refuses when other changes are pending, but it is the one action of the plugin that changes a node's network settings.
- **The troubleshooting access is per node.** An administrator has to go to the node a VM currently runs on (shown in the UI), and has to have the key and a login on it.
- **Redundant routers, NSX, Netris, security groups, VM statistics and CKS are not part of this phase** (section 1).
- **The core changes are small** (4.5) and are recorded in upstream-notes.md.

## 7. Test plan once built

**Unit tests:**
- The start of a system VM: `agent=1`, the NICs mapped (control to the control bridge, the others as user VMs, the control NIC found by type and not by position), a node without the control bridge failing with a message, the boot arguments written after the agent answers, a failed ping or write failing the start.
- The control bridge at host add: present (left alone, warning on a different address), present but not a bridge (refused with the message), missing and creatable (create then apply, in that order, and the apply waited for), missing with HTTP 403 (host add fails with the "grant Sys.Modify or create by hand" message, which contains the node, bridge, user and token names), missing with pending network changes (refused with its message), any other Proxmox error (the step and Proxmox's text); at a later connect: missing gives a warning only, and nothing is created.
- The deployer against a fake API: `executeInVR` (exit code, output, timeout, polling), `createFileInVR` (a small file, a file above 60 KiB in chunks), the control IP to VM name map, a VM that is not found.
- The router commands: the NIC number from MAC addresses, the hot-plug of a missing NIC with the VLAN tag, the removal of a NIC, `nicDevId` set.
- `PlugNicCommand`, `UnPlugNicCommand`, `CheckSshCommand`, `PatchSystemVmCommand` (checksum equal: nothing copied; different: chunked copy and the patch script).

**Lab runs, after the build:**
- 8a: SSVM and CPVM come up and register (template download works), the console of a user VM and of the CPVM itself, SSH from the node to the SSVM on 3922, and again after the VM moves to another node.
- 8b: an isolated network with a VR (DHCP, DNS, user data), a public IP with source NAT, port forwarding, static NAT, firewall rules.
- 8c: a VPC with two tiers, an ACL, a public IP in another VLAN (a NIC hot-plugged into the VR), add and remove a NIC on a user VM.

## 8. Lab checks before building (on a Proxmox node with the token; a throwaway VM from the KVM system VM template)

P1 to P3 (2026-10-21, test-log.md): the API creates the directory, the token uploads 115 MB in 1.7 s, the medium of an existing CD drive changes live and the guest reads the bytes (after padding, see 4.2 step 5). Earlier results are in test-log.md ("Phase 8 pre-build lab checks"): T1 refused (`Sys.Modify` needed) and passing once the role is granted, S1 to S6 pass, T2 reaches the VM and sshd on 3922 but the key login is still to be shown with the real boot arguments, S8 and S9 not run.

| # | Check | What it settles |
|---|---|---|
| T1 | With the token: `GET /nodes/<n>/network`, then `POST` a bridge (`cstest0`, no ports, a link-local `cidr`), look at `interfaces.new`, `PUT` to apply, `ip addr show`, then `DELETE` and apply again. If the `POST` is refused, note the message | the API can create and apply an empty bridge with an address, and which privilege it needs; the bridge is listed (this also covers the node-local bridge of the earlier S7) |
| S1 | With a running throwaway VM that has `agent=1` and a working guest agent: `POST .../agent/ping` and `POST .../agent/file-write` (`file=/tmp/x`, `content=...`) by the token, then read the file in the guest | files can be written through the API with the documented roles |
| S2 | `POST .../agent/exec` (`command` a list: `/bin/sh -c "echo hi; echo err 1>&2; exit 3"`) and `GET .../agent/exec-status?pid=` by the token; then a command that takes 5 seconds; then one that prints about 1 MiB | exit code, output, the polling, the time per call, what happens to a large output |
| S3 | `exec` with `input-data` (48,000 bytes as base64) into `base64 -d >> /tmp/big`, 20 times, then `sha256sum` against the source; time it | a large file can be copied in chunks, and how fast |
| S4 | Boot the KVM system VM template as a Proxmox VM with `agent=1` (no cmdline): `virt-what` says `kvm`, `qemu-guest-agent` runs, `init.sh` waits for `/var/cache/cloud/cmdline`; write it through S1 within the 60 s and see `/var/log/cloud.log` say it was received | the unchanged template takes the KVM path on Proxmox and accepts the file written by the API |
| S5 | A VM with `net0`, `net1`, `net2` on different bridges: in the guest `ip link` shows `eth0`, `eth1`, `eth2` in that order, with the MACs of `net0`, `net1`, `net2` | the NIC order the system VM scripts expect |
| S6 | On a running VM: `PUT config net3=virtio,bridge=<bridge>,tag=<vlan>` by the token, see `eth3` appear with the MAC of the configuration; then `PUT config delete=net3` | NIC hot-plug and hot-unplug by the token, with a VLAN tag |
| T2 | The throwaway VM has `net0` on the bridge of T1 with a link-local address set in the guest; from the node: `ping`, then `ssh -i <key> -p 3922 root@<address>` (the boot arguments must carry `authorized_key=`) | the node reaches a system VM on its own control bridge, with no credentials of CloudStack on the node |
| P1 | With the token: create a `dir` storage entry (`type=dir`, `path=/var/lib/cloudstack-patch`, `content=iso`, `shared=0`) and see the directory and `template/iso` appear on a node, and on a second node if there is one | the API creates the directory; no SSH |
| P2 | Upload a 115 MB file named `*.iso` to `/nodes/<n>/storage/<entry>/upload` (`content=iso`) with the token, and time it; list it with `GET .../content` | the upload is allowed for the token, takes seconds, and accepts raw bytes |
| P3 | Attach that file as an extra CD to the throwaway VM (`ide3=<entry>:iso/<file>,media=cdrom`) on a running VM, read it in the guest with `head -c <size> /dev/sr0 | sha256sum` and compare with the source; then eject it (`ide3=none,media=cdrom`) | the medium of an existing drive changes live, the guest reads exactly the bytes, the copy is fast |
| S8 | Register the system VM template as `SYSTEM` for the hypervisor Proxmox (`registerTemplate`) and see it become Ready on the secondary store | the template can be registered by hand |
| S9 | The sizes of `agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh` in `/usr/share/cloudstack-common/vms/` of the management server | how long a chunked copy takes for a router and for an SSVM or CPVM |

## 9. Decisions for the reviewer

Settled in the discussion: the control path is the guest agent API only (no SSH to nodes, no tunnel); a link-local network on every node for troubleshooting; the template and ISO rework is a separate piece after this phase.

1. **The control bridge is created by the plugin when a host is added, if it is missing; if that fails, adding the host fails with a message that tells the administrator to grant `Sys.Modify` or to create the bridge by hand** (4.7). Decided. An administrator who prefers not to grant the privilege creates `cloud0` on every node first and the plugin leaves it alone (lab T1: the token is refused without `Sys.Modify`, works with it).
2. **Boot parameters through the guest agent API** (`agent/file-write`), with `agent=1` for system VMs.
3. **Patching** (decided): small files through the API, `agent.zip` through a payload CD from one `dir` storage entry on the nodes (4.2 step 5, 4.8); a start fails with a message when the upload is not possible; nothing is copied when the VM's signature already matches; nothing needed on the nodes.
4. **Proxmox has its own router template setting, `router.template.proxmox`** (decided; the plan to share `router.template.kvm` was dropped).
5. **The system VM template is registered by hand** (no change to `SystemVmTemplateRegistration`).
6. **Split into 8a (SSVM, CPVM and the control bridge), 8b (VR) and 8c (VPC and NIC hot-plug)**, each with its lab runs.
7. **Redundant routers, VM statistics, NSX, Netris, security groups and CKS are left for later.**

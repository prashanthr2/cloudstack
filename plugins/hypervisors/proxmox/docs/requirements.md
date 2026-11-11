# Proxmox plugin: requirements

What has to be in place for CloudStack to manage a Proxmox VE cluster. Items marked **to verify** are what the code or the
Proxmox documentation says but that has not been confirmed on the lab yet; they are tracked in [test-log.md](test-log.md).

## 1. Network ports

CloudStack needs only the Proxmox API at this stage. Open these before adding a cluster:

| From | To | Port | Used for | Since |
|---|---|---|---|---|
| Management server | **every** Proxmox node | TCP 8006 (HTTPS) | Proxmox REST API: discovery, host status, VM list, storage | Phase 1 |
| **Every** Proxmox node | the NFS server of the primary storage | NFS: TCP 2049; NFSv3 also needs the portmapper and mount ports as for any NFS client | the nodes mount the share themselves, the management server does not | Phase 2 |

- The management server talks to the cluster through any node's API, and keeps the address of every node to fail over.
  It therefore has to reach **all** nodes, not only the one used to add the cluster.
- A non-default API port is accepted: add the host as `address:port` (for example `10.0.35.25:8443`). The port given is used
  for every node of the cluster, so all nodes must listen on the same port.
- The scheme (`http://`, which the UI adds) and any path or query typed after the address are ignored; the API is always
  reached over HTTPS.
- Ports used between the Proxmox nodes themselves (cluster communication, SSH between nodes) are Proxmox's own
  requirement and are not CloudStack's concern.

The NFS export must allow all the Proxmox nodes (CloudStack configures the storage on them; nothing is mounted on the management
server). When a pool is removed from CloudStack, Proxmox leaves the share mounted at `/mnt/<pool uuid>` on every node; unmount it
with `umount /mnt/<pool uuid>` (and `rmdir` the directory) before unexporting or deleting the share. Later phases will add to this table (the console connection, and whatever the system VM work needs); it is updated
with each phase.

## 2. TLS

The Proxmox API is HTTPS only. Whether the plugin **verifies** the server certificate is the global setting
`proxmox.api.verify.tls`, which is **off by default**. This is the same as the VMware and XenServer plugins, which accept
any server certificate; it keeps a default Proxmox installation (with its own self-signed cluster CA) working out of the box.

With the setting off the connection is encrypted but the server is not authenticated, so something that can sit between the
management server and a Proxmox node could impersonate it and obtain the API token. Turn verification on where that
matters.

### Turning certificate verification on

Java trusts only the CAs in the truststore of the Java runtime that runs the management server. Proxmox signs each node's
certificate with its own cluster CA (`/etc/pve/pve-root-ca.pem`, confirmed on Proxmox VE 8.4), so that CA has to be added:

1. Copy the CA certificate from any Proxmox node to the management server:

   ```bash
   scp root@<proxmox-node>:/etc/pve/pve-root-ca.pem .
   ```

2. Add it to the truststore of the Java runtime that runs the management server. Either way, the Java in use must be the
   one the management server really runs with (`readlink -f "$(which java)"` shows it).

   - **Debian / Ubuntu (recommended).** The system tool also updates Java's truststore, and a Java package update does not
     remove the entry. The file must end in `.crt`:

     ```bash
     sudo cp pve-root-ca.pem /usr/local/share/ca-certificates/pve-root-ca.crt
     sudo update-ca-certificates
     ```

   - **RHEL and derivatives.** Java uses the system trust store there:

     ```bash
     sudo cp pve-root-ca.pem /etc/pki/ca-trust/source/anchors/pve-root-ca.pem
     sudo update-ca-trust
     ```

   - **Any system, directly with `keytool`.** `-cacerts` selects the default truststore of the Java that `keytool` belongs to
     (default password `changeit`), so no path or `JAVA_HOME` is needed:

     ```bash
     keytool -importcert -trustcacerts -cacerts -alias pve-root-ca -file pve-root-ca.pem -storepass changeit
     ```

     A Java package update can replace that file. If that is a concern, keep a dedicated truststore and point the
     management server at it with `-Djavax.net.ssl.trustStore` in its `JAVA_OPTS`. (The `cloud.jks` keystore in
     `/etc/cloudstack/management` holds the management server's own HTTPS certificate and is not used for this.)

   To check that Java now trusts it: `keytool -list -cacerts -storepass changeit | grep -i -A1 pve`.

3. Restart the management server so the truststore is read again.
4. Set the global setting `proxmox.api.verify.tls` to `true`. It is dynamic and applies when a host (re)connects.
5. Check: the hosts reconnect and stay `Up`; a failed check shows in the management server log as
   `PKIX path building failed ... unable to find valid certification path`.

The certificate must also contain the address used to reach the node (its IP address or name) in its subject alternative
names, or the hostname check fails (**to verify** for default Proxmox node certificates). If the nodes are reached by an
address that is not in the certificate, either add that address to the certificate or leave verification off.

## 3. Adding a host, and the API token

**Since Phase 10 (design-phase10-ssh-access.md) Add Host takes a login, not a token.** Username and password are a Proxmox login that can run `pveum`: normally `root`,
or another user that may use `sudo` (it needs `NOPASSWD` for `sudo -n`, or the plugin gives the password to `sudo -S`; the same user must exist on every node). The
password may be left empty when the management server's key (the global setting `ssh.privatekey`, as for a KVM host) is in the node's `authorized_keys`: the key is
tried first, then the password. Proxmox's `root` login by key works on every node once it is in `/etc/pve/priv/authorized_keys`, which the cluster shares.

With that login the plugin, over SSH:
1. checks the node's SSH host key (kept with the host, and compared on later connections);
2. makes the user `cloudstack@pve` and the roles below, and **one API token for the Proxmox cluster** (`cs-<cluster name>`, with privilege separation; users and tokens
   belong to the whole cluster, so it works on every node), and gives the token its rights on the user and on the token: `PVEAuditor` on `/`, `PVEVMAdmin` on `/vms`,
   `PVEDatastoreAdmin` on `/storage`, `PVESDNUser` on `/sdn/zones/localnetwork`, the role `CloudStackNet` (`Sys.Modify`) on the own path of each node it adds, without
   propagation, and always the role `CloudStackHA` (`Sys.Audit`, `Sys.Console`) on `/` without propagation (see "Permissions for HA management" for why that is safe and why it is always given);
3. keeps the token with the **CloudStack cluster** (`cluster_details`: `username` is the token id, `password` the secret, encrypted like a host's), and the SSH login
   and host key with each host. A host added later in the same cluster reads the token there and makes no new one;
4. from then on uses the API with the token. When the last Proxmox host of a cluster is deleted, the token is removed and, if the user `cloudstack@pve` has no other token, the user (with its entries in the access control list) and the roles `CloudStackNet` and `CloudStackHA` as well (best effort: a failure is a warning in the management server log with the command to run by hand).

If Proxmox stops accepting the cluster's token (it was removed, or its secret changed), the next Add Host makes a new one; hosts already added take it up when they
reconnect (restart the management server, or disconnect and reconnect them).

A node where the login does not work is not added, and the log says why. Hosts added with a token before this change must be deleted and added again.
**Where SSH is used and where the API is used** is in design-phase10-ssh-access.md section 1; this file keeps the permissions each API call needs.

The rest of this section describes the token and the permissions, which the plugin now sets up itself; the commands are what it runs, and what to do by hand for a node
where you do not want to give an SSH login (such a host cannot be added any more: it is kept for reference).

## 3a. API token (reference)

CloudStack authenticates to Proxmox with an API token, not with a user password. A token can be revoked on its own, can be
limited in what it may do, does not expire after a couple of hours like a login ticket, and works for accounts that use
two-factor authentication.

Add Host does not take a token (see section 3): the plugin makes the token and keeps it with the CloudStack cluster. The rest of this section is reference: what the token is
and what it may do, for anyone who looks at it in Proxmox.

### Permissions for HA management from CloudStack (optional; global setting `proxmox.ha.manage`, Phase 5)

**By default CloudStack does not manage Proxmox HA.** The plugin gives the token the HA role at Add Host whether or not the setting is on (see the security note below for why that does not reach the nodes' consoles), so turning the setting on later needs no new token and no change in Proxmox. The global setting `proxmox.ha.manage`
is **off**; then the HA flag of a compute offering has **no effect** on a VM in Proxmox, and an administrator makes VMs highly available in Proxmox itself
(`ha-manager add vm:<id>`, or Datacenter > HA). Proxmox HA restarts such VMs after a node failure, and CloudStack follows them (see behaviour.md, section 6).

**When the setting is turned on**, CloudStack makes a VM a Proxmox HA resource when its offering has HA enabled (and removes the resource it created when the
flag is off), and the token then needs these **extra permissions on the root path `/`**, which `PVEVMAdmin` does not have. Confirmed on the lab: with only the
roles above the HA API answers `HTTP 403`:

| Call | Needs | Error without it |
|---|---|---|
| read `/cluster/ha/resources` | `Sys.Audit` on `/` | `Permission check failed (/, Sys.Audit)` |
| create or remove a resource | `Sys.Console` on `/` | `Permission check failed (/, Sys.Console)` |

**Security note:** `Sys.Console` is Proxmox's privilege for **console access to the node itself** (the Shell button), a much wider right than managing VMs, and
the HA API needs it. The plugin gives it with the grant as narrow as possible, always: a role with only these two privileges, on `/`
and **without propagation** (the HA API checks `/` itself, while node console access is checked on the node's own path, which a non-propagating entry at `/` does
not reach; **confirmed on Proxmox VE 8.4.21**, and you can check it on yours with the call below):

```bash
pveum role add CloudStackHA --privs "Sys.Audit Sys.Console"
pveum acl modify / --users <user> --tokens '<user>!<token>' --roles CloudStackHA --propagate 0
```

Check that the token can manage HA resources but cannot open a node console (this call must be refused: `Permission check failed (/nodes/<node>, Sys.Console)`; if it
returns a port and a ticket, the grant reaches the nodes and should be removed):

```bash
curl -ks -w "\nHTTP %{http_code}\n" -X POST -H "Authorization: PVEAPIToken=<user>@<realm>!<token>=<secret>" https://<node>:8006/api2/json/nodes/<node>/termproxy
```

With the setting on and the permissions missing, starting a VM whose offering has HA enabled **fails** with a message that names the permissions, rather than starting a
VM that its owner believes is protected.

### The link-local control bridge for system VMs and routers (Phase 8; design only, `Sys.Modify` confirmed needed on the lab 2026-10-21)

**What it is and why.** Every system VM (secondary storage VM, console proxy) and virtual router has a *control* NIC with a link-local address (`169.254.x.y`). In
CloudStack that NIC must sit on a host-only bridge, `cloud0` on a KVM host. CloudStack's control of the VM does **not** use it on Proxmox (that goes through the
guest agent and the API), but the NIC still has to be attached to a bridge of the node, and the node should have the link-local gateway address on that bridge so that
an administrator can log in to the node and reach the system VM's shell (`ssh -p 3922`) for troubleshooting (the key for that login is set up as described in "Logging in to a system VM from a node" below). The bridge has no ports: nothing leaves the node.
**It must exist on every node of the cluster**, because Proxmox HA or a migration can start the VM on any of them; a system VM or router cannot start on a node without it
(the start fails with a message that names the bridge). The name is the setting `proxmox.control.bridge` (default `cloud0`).

**What CloudStack does at host add.** It looks for the bridge on the node. If it is there (made by hand, for example) it is left alone. If it is missing, CloudStack tries to create and apply it; if the token is not allowed to, **adding the host fails** with a message that says to grant `Sys.Modify` (below) or to create the bridge by hand on every node and add the host again. So the order for a cluster without the privilege is: create `cloud0` on each node, then add the hosts.

**Why `Sys.Modify` is needed, and for nothing else.** Creating a bridge changes the node's network configuration, and the Proxmox API (`POST /nodes/<node>/network`,
then `PUT /nodes/<node>/network` to apply it) requires `Sys.Modify` on `/nodes/<node>`. Lab 2026-10-21: with `PVEVMAdmin`, `PVEDatastoreAdmin` and `PVESDNUser` both
calls (and the delete) answer `HTTP 403 Permission check failed (/nodes/<node>, Sys.Modify)`. **This is the only reason CloudStack would ask for it, and it is optional**:

- **Not needed for isolated, shared or VPC networks, public networks or any other traffic.** Those NICs use the bridges that already exist and are named by the zone's traffic
  labels, and the VLAN is only the `tag=` of the NIC (lab S6: a NIC with a tag was added to a running VM with the standard roles). For a bridge that is not VLAN-aware,
  Proxmox itself creates the VLAN sub-bridge when the NIC is plugged (`PVE::Network::tap_plug`, run by the node, not with the token's rights). The administrator creates the
  zone's bridges once, as for any VM (open issue 10); CloudStack never creates or changes them.
- **Not needed once the bridge exists.** The plugin only creates the control bridge when it is missing, never changes an existing one, and refuses to apply the network
  configuration when other changes are pending.

**What the API does to `/etc/network/interfaces`** (lab 2026-10-21, with the grant below): creating a bridge through the API makes Proxmox write the **whole file again** from its own parsed
configuration. The settings of every interface were kept and the node stayed reachable, but **comments and the original layout are lost** (the header comment and the comments on
the management and trunk bridges were dropped, interfaces were reordered and the indentation changed). An administrator who keeps notes in that file should know this before
granting the privilege, or create the bridge by hand (below), which only appends the new lines.

**What `Sys.Modify` also allows** (be aware before granting it): in Proxmox it means "edit node settings", not only the network: it also covers node DNS, time, hosts
file and services on that node, and some VM monitor commands. Give it in a role of its own, on the node paths only and without propagation:

```bash
pveum role add CloudStackNet --privs "Sys.Modify"
pveum acl modify /nodes/<node> --users <user> --tokens '<user>!<token>' --roles CloudStackNet --propagate 0
```

(once per node; a node added later needs the same line). As for the other roles, the grant is needed on the **user and on the token**.

**If you do not grant it: create the bridge by hand on every node** (once, and again for every node that joins the cluster). In the web interface: node > System > Network >
Create > Linux Bridge, name `cloud0` (the value of `proxmox.control.bridge`), IPv4/CIDR `169.254.0.1/16`, no gateway, no bridge ports, autostart on > Create >
Apply Configuration. The same on the command line, on each node:

```bash
cat >> /etc/network/interfaces <<'EOF'

auto cloud0
iface cloud0 inet static
        address 169.254.0.1/16
        bridge-ports none
        bridge-stp off
        bridge-fd 0
EOF
ifreload -a
ip -br addr show cloud0
```

Check that `interfaces.new` does not exist first (`ls /etc/network/interfaces.new`: pending changes made in the web interface would be applied by `ifreload -a` too). Keep the same
name and address on every node. The address `169.254.0.1/16` is the link-local gateway CloudStack uses for the control network; it is only needed for the troubleshooting login, the
bridge alone is enough for a system VM to start.

### Patching the system VMs (Phase 8a; changed 2026-11-11)

A new secondary storage VM or console proxy VM needs the Java agent (`agent.zip`, about 95 to 115 MB) from the management server, and the template does not have it; every router and system VM also gets `cloud-scripts.tgz` and `patch-sysvms.sh`. CloudStack copies them into the VM over SSH, as it does on KVM, through the node the VM runs on:

- **What is needed:** the SSH login to the node that Add Host keeps (the same one that templates and ISOs use), and that `sshd` on the nodes allows TCP forwarding (the default; `sshd -T | grep -i allowtcpforwarding` must say `yes`). Nothing is made on the nodes, there is no storage entry and no setting, and the API token needs no privilege for it.
- **The key:** the management server's own key pair (`ssh.privatekey`), which every system VM trusts. It is used on the management server only.
- **The management server** needs `agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh` in `/usr/share/cloudstack-common/vms/` (the package `cloudstack-common` installs them). If they are missing, the start of the system VM fails with a message that names the directory.
- **Upgrade note:** an earlier version made one storage entry `csp-<uuid>` (path `/var/lib/cloudstack-patch`) with files `cs-patch-*.iso` in it. They are not used any more and can be removed once no VM needs them: `pvesm remove csp-<uuid>` on any node (the entry is cluster-wide), then `rm -r /var/lib/cloudstack-patch` on every node. The global setting `proxmox.patch.path` no longer exists.

## ISOs of the secondary storage (design-phase11, step 3)

- **Entry:** `csiso-<uuid>` (a `dir` storage that **is shared**), path from the global setting **`proxmox.iso.path`** (default `/var/lib/cloudstack-iso`), content `iso`, created by CloudStack when the first ISO is attached or a VM with an ISO starts; Proxmox makes the directory and `template/iso` on each node. The token needs what it needs for the `csp-` entry (`PVEDatastoreAdmin` on `/storage`).
- **SSH:** the ISO file is bound onto a file of that directory over the SSH login of the host (`mount`, `mountpoint`, `touch`, `rm`, run with `sudo sh -c`), from a short read-only NFS mount of the secondary store, so the user needs full `sudo` as for volume snapshots, and the node needs a route to the NFS server (firewall) and an export that the node may read.
- **Upgrade note:** the plugin no longer makes or uses the entries `css-*` (the NFS mount of the secondary store), `cst-*` (templates) and `csi-*` (ISOs). Those that exist can be removed by hand with `pvesm remove <id>` once no instance and no template uses them (instances that were started with an ISO keep a drive that names a `csi-` volume until they are started again or the ISO is detached); a `hard` NFS mount of the old `css-` entry that is still on a node goes with the entry.

- **The management server** needs `agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh` in `/usr/share/cloudstack-common/vms/` (the package `cloudstack-common` installs them). If they are missing, the start of the system VM fails with a message that names the directory.
- The entry appears in the Proxmox web interface as one fixed entry named `csp-...`; it is not removed when the last SSVM goes away.

### Logging in to a system VM from a node (troubleshooting; Phase 8a)

**What this is for.** The control path of system VMs and routers is the guest agent API, so CloudStack never needs to log in to them. To look inside one (logs, `iptables`, the agent), an administrator logs in to the **Proxmox node the VM runs on** and
connects to the VM's control address (`169.254.x.y`, shown as the control NIC of `listSystemVms` and `listRouters`) on port 3922. That works through the control bridge (see "The link-local control bridge" above). **The system VMs have no root password; only keys work.**

**Why a key of your own is needed.** A system VM trusts only the management server's public key (`ssh.publickey`). On a KVM host CloudStack copies the matching private key to the host at host add, over SSH. For Proxmox CloudStack has no SSH access to the nodes and does not
copy the management server's private key anywhere. Instead **you create a key pair for troubleshooting and give CloudStack its public half**; CloudStack adds that public key to every system VM and router it starts.

**Automatic set-up (Add Host).** Since Phase 10 Add Host does the set-up below itself, because it has root (or sudo) on the nodes: it makes the key pair `/etc/pve/priv/cloudstack_systemvm` when that file does not exist (a pair that is there is used, never replaced; a private key without its `.pub` file gets its public key derived), and adds the public key to the setting `proxmox.systemvm.ssh.key`, after any keys that are there already (keys of administrators stay; a key that is in the setting is not added twice). The system VMs and routers started **after** that accept the key: restart the ones that run already. If the pair cannot be made (for example the write to `/etc/pve/priv` is refused), the host is still added, the management server log says why, and the manual steps below still work.

**Set up by hand, once per CloudStack installation** (needed only when the automatic set-up did not run or you want another key; any size of cluster: one key, one setting):

1. On **any** Proxmox node, as root, create the key pair in Proxmox's cluster-wide private directory. Proxmox replicates `/etc/pve/priv` to every node of the cluster, current and future, and keeps it readable by root only, so the private key is then on all nodes:
   ```bash
   ssh-keygen -t ed25519 -N "" -C "cloudstack-systemvm" -f /etc/pve/priv/cloudstack_systemvm
   cat /etc/pve/priv/cloudstack_systemvm.pub
   ```
   The second command prints one line, `ssh-ed25519 AAAA... cloudstack-systemvm`. Lab-confirmed on 8.4 for a key made by hand. If `/etc/pve/priv` does not accept a new file on your system, create the pair in `/root/.ssh/` on one node and copy the private file (mode 600, owner root) to the same
   place on every node yourself; nothing else changes.
2. Put that line into the global setting **`proxmox.systemvm.ssh.key`** (Global Settings in the UI, or `cmk update configuration name=proxmox.systemvm.ssh.key value="ssh-ed25519 AAAA... cloudstack-systemvm"`). The value is the public key only: never the contents of the file without `.pub`.
   Several public keys can be given, separated by `|` (for example one per administrator). The setting is dynamic: no restart of the management server is needed.
3. **Restart the system VMs and routers** that should accept the key (stop and start, or destroy the SSVM and CPVM so they are created again; for a router, restart the network). The key is added when CloudStack starts a VM, not to VMs that are already running.

**Logging in**, from the node the VM runs on (the VM's host is shown in CloudStack and in the Proxmox web interface):

```bash
ssh -i /etc/pve/priv/cloudstack_systemvm -p 3922 root@<control address of the VM>
```

**Good to know**
- **Where the private key is used.** Only on the Proxmox nodes, by the administrators who log in there. The management server's private key stays on the management server.
- **Who gets access.** Whoever has the private key can log in as root to every system VM and router. The control network exists only inside each node, so this is no more than what root on the node can already do with its VMs.
- **Rotating the key.** Create a new pair, replace the setting, and restart the VMs. Remove the old private file.
- **A reboot from inside the VM** (not a start by CloudStack) rewrites its `authorized_keys` from its boot arguments and removes the key until CloudStack starts the VM again.
- **A bad value is ignored, not fatal.** An entry that is not a public key (`ssh-...`, `ecdsa-...`, `sk-...`, a base64 key, an optional comment) is skipped with a warning in the management server log; the VM still starts and only the troubleshooting login is affected.
- **The setting is empty by default.** Then nothing is added, and the only way in is the management server's own key.

### Virtual routers on Proxmox (Phase 8b)

- **Template.** The router of an isolated or shared network is started from the system VM template whose name is in the zone-scoped global setting **`router.template.proxmox`** (default `SystemVM Template (Proxmox)`; one setting per hypervisor, like `router.template.kvm`). The template must be registered for the hypervisor **Proxmox** (type `SYSTEM`, format QCOW2), the same way as for the secondary storage VM and the console proxy VM. The simplest is to point the setting at the template you already registered for them: `cmk update configuration name=router.template.proxmox value=<name of that template> zoneid=<zone id>` (or without `zoneid` for all zones), or to register one more template under the default name. The setting is dynamic.
- **Bridges.** The router's guest and public interfaces use the bridges of the traffic labels of the zone's physical networks and the VLANs of the networks, exactly like user VMs; CloudStack never creates them. Every node that can run a router needs those bridges and the control bridge (see above).
- **Privileges.** Nothing beyond the roles already listed: the interfaces are added and removed with `VM.Config.Network`, which `PVEVMAdmin` has (lab S6).
- The router's own scripts run through the guest agent like those of the other system VMs, and the **login keys** (see "Logging in to a system VM from a node") work for routers too.

### How the token and the SSH login are stored

- **The API token** is kept with the CloudStack cluster, in `cluster_details`: `username` is the token id (`cloudstack@pve!cs-<cluster>`), `password` the secret.
  Every host of the cluster reads it from there when it connects. Database encryption, when it is on, covers a detail named `password`, as for the other plugins.
- **The SSH login** is kept with each host, in `host_details`: `sshuser`, `sshpassword` (encrypted by the plugin when the management server's database encryption is on, plain text otherwise; absent for a login by key) and `sshfingerprint`
  (the node's SSH host key). The plugin uses it for what the API cannot do (see design-phase10-ssh-access.md section 1), and to remove the token when the last host
  of a cluster is deleted.

### Changing the SSH login of hosts that are already added

CloudStack's `updateHostPassword` changes the login the plugin keeps for the hosts of a cluster. For Proxmox the username and password it takes are the **SSH login**
(what Add Host asked for), never the API token: the token is made and kept by the plugin and is not given or changed by hand.

```bash
cmk update hostpassword clusterid=<cloudstack-cluster-id> username=root password=<new-password>
```

- **With the cluster id only.** A single-host update is refused for Proxmox (including the UI "Change password" action on one host), because the same login is used on
  every node of the cluster.
- **What it stores.** CloudStack saves the username and password for every host of the cluster (core behaviour, as for KVM and XenServer). The plugin then uses them
  as the SSH login in place of the ones from Add Host, and never as the API token.
- **`update_passwd_on_host=true`** makes each host's resource check the new login with its node (the stored host key is checked, then the password): a refused password
  fails the call with the node's reason, and the message never contains the password. Nothing is changed on the Proxmox node: change the password there first (for
  example `passwd` on every node), then run this. The value is stored before it is checked, as for every hypervisor, so after a failed call run it again with the correct one.
- **Without the flag** nothing is checked; the login is used the next time a command needs a shell on a node.
- **A login by key** is replaced by a password login when this is run; to go back to the key, add the host again.
- **Rotating the API token** is not an operation of its own: delete the hosts of the cluster (the token is removed with the last one) and add them again (a new token is
  made). A token that Proxmox stops accepting is replaced by the next Add Host (section 3).

## 4. Proxmox cluster

- One CloudStack cluster maps to one Proxmox cluster. Adding a node of a different Proxmox cluster to a CloudStack
  cluster that already holds nodes is refused.
- Proxmox HA is configured on the Proxmox side. CloudStack does not configure or restart anything for HA.

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

## 3. API token

CloudStack authenticates to Proxmox with an API token, not with a user password. A token can be revoked on its own, can be
limited in what it may do, does not expire after a couple of hours like a login ticket, and works for accounts that use
two-factor authentication.

When adding a host:

| Field | Value |
|---|---|
| Username | the token id: `user@realm!tokenname`, for example `cloudstack@pve!cs` |
| Password | the token secret (a UUID, shown by Proxmox only once when the token is created) |

### Creating a token

Run on a Proxmox node. A dedicated user with a token whose rights are limited with privilege separation is recommended.
Grant the role to the token as well as to the user: for a privilege-separated token, Proxmox computes the owning user's permissions
and **intersects** them with the token's (pve-access-control, `RPCEnvironment::permissions`; `root@pam` is the exception), so
the token only gets what **both** have. A token that had only the user's role got no access, and the setup with the role on both
worked. The commands below are the Proxmox VE 8 syntax. The
lab used the `pam` realm (`pveaudit@pam!cloudstack`); `@pve`, Proxmox's own user database, is the usual choice for a
service account because no matching operating system account is needed on every node. Tokens do not use a password, so
either realm works.

```bash
pveum user add cloudstack@pve
pveum user token add cloudstack@pve cs --privsep 1
pveum acl modify / --users cloudstack@pve --roles PVEAuditor
pveum acl modify / --tokens 'cloudstack@pve!cs' --roles PVEAuditor
```

For a quick lab, a token of `root@pam` with privilege separation turned off also works but gives CloudStack full control
of the cluster; do not use that outside a lab.

### Permissions

What the plugin calls, and what it therefore needs. **Verified on Proxmox VE 8.4** with a user and a privilege-separated
token that were both given the built-in `PVEAuditor` role at `/` (`pveum acl list` showed one entry of type `user` and one
of type `token`, both with propagate on):

| API call | Used for | Result with `PVEAuditor` |
|---|---|---|
| `GET /version` | check the connection when adding a cluster | 200 (works for any valid token) |
| `GET /cluster/status` | list nodes, node addresses, cluster name | 200 (403 without privileges) |
| `GET /nodes` | fallback for a standalone node | 200 (names only without privileges) |
| `GET /nodes/{node}/status` | CPU, memory, version, health check, statistics | real values (empty without privileges) |
| `GET /nodes/{node}/qemu` | VMs on a node, for the power state report | lists the VMs (a test VM created with `qm create` showed up) |

At this stage everything is read-only and the built-in `PVEAuditor` role (it contains `Sys.Audit` and `VM.Audit`) is enough.
### Permissions for primary storage (Phase 2; from the Proxmox source, create and delete confirmed on the lab)

| API call | Used for | Privilege (expected) |
|---|---|---|
| `GET /storage` | find the pool's entry | `Datastore.Audit` or `Datastore.AllocateSpace` on the entry (a caller without either gets an **empty list**; `PVEAuditor` has `Datastore.Audit`) |
| `POST /storage` | create the entry | `Datastore.Allocate` on `/storage` (part of `PVEDatastoreAdmin`; not in `PVEAuditor`) |
| `DELETE /storage/{id}` | remove the entry | `Datastore.Allocate` on `/storage` |
| `GET /nodes/{node}/storage/{id}/status` | state and capacity on a node | `Datastore.Audit` or `Datastore.AllocateSpace` on the entry |

Confirmed on the lab with a privilege-separated token: with only `PVEAuditor` creating storage is refused (403); `PVEDatastoreAdmin` on
`/storage` for the token alone is still refused; with the role on the **user as well** as the token, create and delete work.

Privileges are granted through a **role** on a **path**, not one by one; for the storage calls give the token a role that contains
`Datastore.Allocate` (the built-in `PVEDatastoreAdmin`) on `/storage`, for example
`pveum acl modify /storage --tokens '<token id>' --roles PVEDatastoreAdmin` **and** the same for the owning user
(`--users <user>`). The privileges for VMs, templates, networks, the console and migration are in the next section,
and those for managing Proxmox HA from CloudStack in the one after.

**A token without permissions looks half-working.** `version` and `nodes` answer 200, `cluster/status` answers 403, node
status comes back without CPU and memory, and the VM list is empty. If adding a cluster fails with `HTTP 403`, or hosts show
no capacity, check that the role was granted to the **token** itself (`pveum acl list` shows one line for the user and one
for the token); a privilege-separated token never gets more than it is granted directly.

### Permissions for VMs, templates, networks, the console and migration (Phases 3 to 5; confirmed on the lab)

Give **both the user and the token** (a privilege-separated token gets only what both have) these roles:

| Role | Path | Used for |
|---|---|---|
| `PVEVMAdmin` | `/vms` | create, configure, start, stop, move and destroy VMs; `VM.Console` (the console); `VM.Migrate` (migration) |
| `PVEDatastoreAdmin` | `/storage` | the primary storage entry and the secondary storage entries (templates) |
| `PVESDNUser` | `/sdn/zones/localnetwork` | attach a VM to a bridge (`SDN.Use` on `/sdn/zones/localnetwork/<bridge>/<vlan tag>`; without it a NIC is refused) |

```bash
pveum acl modify /vms --users <user> --tokens '<user>!<token>' --roles PVEVMAdmin
pveum acl modify /storage --users <user> --tokens '<user>!<token>' --roles PVEDatastoreAdmin
pveum acl modify /sdn/zones/localnetwork --users <user> --tokens '<user>!<token>' --roles PVESDNUser
```

Migration, the console and everything else CloudStack does by default need nothing more than this. Migrating a VM with the token does not need
root: only the options `force`, `migration_type` and `migration_network` are root-only, and the plugin never sends them (lab check L1).

### Data volumes (Phase 7): reserved VM ids and the permissions they need (lab D1 to D12, 2026-10-21)

**The VM ids 900000001 to 999999999 are reserved for CloudStack.** The data volumes of an account are disk images owned by the vmid `900000000 + <account id>`, a number that is never a VM, so that they survive the
deletion of instances. **Never create a VM with an id in that range, and do not set the cluster's `next-id` range (Datacenter > Options) there.** Destroying a VM that has such an id with the option "Destroy unreferenced
disks owned by guest" would remove that account's volumes.

No permission beyond the previous sections is needed: with the roles `PVEVMAdmin` on `/vms` and `PVEDatastoreAdmin` on `/storage` the token can allocate an image for a holder id, use it in any VM (`Datastore.Allocate` on the
storage, or `Datastore.Audit` on the storage and `VM.Config.Disk` on `/vms/<holder>`), hot-plug and unplug it, resize it and delete it. The token needs `Datastore.Allocate` (in `PVEDatastoreAdmin`) to delete an image.

### Permissions for HA management from CloudStack (optional; global setting `proxmox.ha.manage`, Phase 5)

**By default CloudStack does not manage Proxmox HA, and the token needs nothing beyond the previous section.** The global setting `proxmox.ha.manage`
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
the HA API needs it. Grant it only if you want CloudStack to manage HA, and keep the grant as narrow as possible: a role with only these two privileges, on `/`
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

### The patch file of system VMs (Phase 8a; lab P1 to P3)

A new secondary storage VM or console proxy VM needs the Java agent (`agent.zip`, about 110 MiB) from the management server, and the template does not have it. CloudStack delivers it through one storage entry:

- **Entry:** `csp-<uuid>` (a `dir` storage that is not shared), path from the global setting **`proxmox.patch.path`** (default `/var/lib/cloudstack-patch`), content `iso`. CloudStack creates it when the first SSVM or CPVM is started, and Proxmox creates the directory and its
  `template/iso` folder on each node when the entry is activated there: nothing is made by hand. It uploads `agent.zip` once per node and version as `cs-patch-<16 characters>.iso` (a file whose name ends in `.iso`; the bytes are not an ISO image) and removes the files of older versions.
- **Privileges:** creating the entry and uploading need `Datastore.Allocate` and `Datastore.AllocateTemplate` on `/storage`, which the role `PVEDatastoreAdmin` that the token already has for primary storage includes (lab P1 and P2, no extra privilege). `Sys.Modify` is not needed.
- **Room:** 110 MiB on the node's disk under that path, once per version of the management server.
- **The management server** needs `agent.zip`, `cloud-scripts.tgz` and `patch-sysvms.sh` in `/usr/share/cloudstack-common/vms/` (the package `cloudstack-common` installs them). If they are missing, the start of the system VM fails with a message that names the directory.
- The entry appears in the Proxmox web interface as one fixed entry named `csp-...`; it is not removed when the last SSVM goes away.

### Logging in to a system VM from a node (troubleshooting; Phase 8a)

**What this is for.** The control path of system VMs and routers is the guest agent API, so CloudStack never needs to log in to them. To look inside one (logs, `iptables`, the agent), an administrator logs in to the **Proxmox node the VM runs on** and
connects to the VM's control address (`169.254.x.y`, shown as the control NIC of `listSystemVms` and `listRouters`) on port 3922. That works through the control bridge (see "The link-local control bridge" above). **The system VMs have no root password; only keys work.**

**Why a key of your own is needed.** A system VM trusts only the management server's public key (`ssh.publickey`). On a KVM host CloudStack copies the matching private key to the host at host add, over SSH. For Proxmox CloudStack has no SSH access to the nodes and does not
copy the management server's private key anywhere. Instead **you create a key pair for troubleshooting and give CloudStack its public half**; CloudStack adds that public key to every system VM and router it starts.

**Set up, once per CloudStack installation** (any size of cluster: one key, one setting):

1. On **any** Proxmox node, as root, create the key pair in Proxmox's cluster-wide private directory. Proxmox replicates `/etc/pve/priv` to every node of the cluster, current and future, and keeps it readable by root only, so the private key is then on all nodes:
   ```bash
   ssh-keygen -t ed25519 -N "" -C "cloudstack-systemvm" -f /etc/pve/priv/cloudstack_systemvm
   cat /etc/pve/priv/cloudstack_systemvm.pub
   ```
   The second command prints one line, `ssh-ed25519 AAAA... cloudstack-systemvm`. **Not yet confirmed on a lab:** that `/etc/pve/priv` accepts a new file with that name. If it does not, create the pair in `/root/.ssh/` on one node and copy the private file (mode 600, owner root) to the same
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

### How the secret is stored

The token id and secret are saved with each host under the host detail keys `username` and `password`. The `password` key
is encrypted by the shared host details DAO when database encryption is enabled, exactly as for the VMware, XenServer,
Hyper-V and OVM plugins, which store their passwords under the same key. With encryption off (a default development
setup) it is stored as plain text, as for those plugins.

### Changing the token of hosts that are already added

A Proxmox token belongs to the whole Proxmox cluster, while CloudStack keeps one copy of it per host. Recreating the token
in Proxmox therefore invalidates every host's copy at once, and all of them have to be updated together. Use CloudStack's
`updateHostPassword` **with the cluster id**: the token id goes in `username` and the token secret in `password`.

- **Single-host updates are refused for Proxmox** (including the UI "Change password" action on one host), with a message
  asking for the cluster id. This is the same as for XenServer pools, and it prevents a half-updated cluster.
- **Same token id, new secret (rotation).** A token id is unique per user, so a new secret for the same id means deleting
  the token and creating it again. **The old secret stops working at once, so the hosts go to `Alert` and then
  `Disconnected` before CloudStack can be updated**; that is expected. Recreate the token in Proxmox under the same id
  (`pveum user token remove cloudstack@pve cs`, then `pveum user token add cloudstack@pve cs --privsep 1`, and give the new
  token its permissions again), then store the new secret for the whole cluster:

  ```bash
  cmk update hostpassword clusterid=<cloudstack-cluster-id> username='cloudstack@pve!cs' password=<new-secret>
  ```

  The hosts pick the new token up at the next reconnect scan (see behaviour.md, section 5a) and return to `Up`, within about
  90 seconds by default. The value is **not checked** when it is stored: if it is wrong the hosts stay `Disconnected` and
  the management server log shows the Proxmox reason (for example HTTP 401). Run the call again with the correct secret.
- **A stored token is only used when a host (re)connects.** A connected host keeps the token it started with in memory; the
  update changes the stored details only. While the old token is still valid in Proxmox the hosts therefore stay `Up` on it,
  and a wrong secret stored by mistake stays hidden until the next reconnect or management server restart, when the hosts fail
  with it. To apply a stored token at once, use the host action **Force Reconnect** (`reconnectHost`), or restart the
  management server. After the old token has been revoked this happens by itself, because the failing pings trigger a
  reconnect.
- **`update_passwd_on_host=true` is not for the rotation above.** With it, each host's running connection is told to use the
  new token after Proxmox has accepted it (nothing is changed on the Proxmox node). That only works for hosts that are
  connected, which they are not once the old token has been revoked. It is useful only while the old token is still valid.
- **Every host must be connected** for `update_passwd_on_host=true`; a host that is `Alert` or `Disconnected` makes the call
  fail with "The host is not connected" (the new value has been stored by then, see the next point).
- **The new value is stored first, then checked.** CloudStack saves the new username and password for all hosts of the cluster
  before it asks each host to use them. If the call fails (a wrong secret, or a host that is not connected) the stored value
  is already the new one, and a host that reconnects uses it. After a failed call run it again with the correct secret.
- **The reason for a failure is in the error message**, for example "Proxmox rejected the API token (HTTP 401): the token id or
  the secret is wrong, or the token was removed or has expired", "... does not have the permissions CloudStack needs
  (HTTP 403)", or "The host is not connected; wait until it is Up and try again". The secret is never part of a message.
- **A different token id or user is not supported by this operation**: CloudStack requires the username to match the stored
  one and refuses otherwise ("The username is not same for the hosts"). To change the id, disable and remove the hosts and
  add them again.

## 4. Proxmox cluster

- One CloudStack cluster maps to one Proxmox cluster. Adding a node of a different Proxmox cluster to a CloudStack
  cluster that already holds nodes is refused.
- Proxmox HA is configured on the Proxmox side. CloudStack does not configure or restart anything for HA.

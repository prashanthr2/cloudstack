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
(`--users <user>`). The privileges needed for VM and network management
are listed here as those features are added.

**A token without permissions looks half-working.** `version` and `nodes` answer 200, `cluster/status` answers 403, node
status comes back without CPU and memory, and the VM list is empty. If adding a cluster fails with `HTTP 403`, or hosts show
no capacity, check that the role was granted to the **token** itself (`pveum acl list` shows one line for the user and one
for the token); a privilege-separated token never gets more than it is granted directly.

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

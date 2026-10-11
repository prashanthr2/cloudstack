# Proxmox plugin: requirements

What has to be in place for CloudStack to manage a Proxmox VE cluster. Items marked **to verify** are what the code or the
Proxmox documentation says but that has not been confirmed on the lab yet; they are tracked in [test-log.md](test-log.md).

## 1. Network ports

CloudStack needs only the Proxmox API at this stage. Open these before adding a cluster:

| From | To | Port | Used for | Since |
|---|---|---|---|---|
| Management server | **every** Proxmox node | TCP 8006 (HTTPS) | Proxmox REST API: discovery, host status, VM list | Phase 1 |

- The management server talks to the cluster through any node's API, and keeps the address of every node to fail over.
  It therefore has to reach **all** nodes, not only the one used to add the cluster.
- A non-default API port is accepted: add the host as `address:port` (for example `10.0.35.25:8443`). The port given is used
  for every node of the cluster, so all nodes must listen on the same port.
- The scheme (`http://`, which the UI adds) and any path or query typed after the address are ignored; the API is always
  reached over HTTPS.
- Ports used between the Proxmox nodes themselves (cluster communication, SSH between nodes) are Proxmox's own
  requirement and are not CloudStack's concern.

Later phases will add to this table (NFS from the Proxmox nodes to the storage server, the console connection, and
whatever the system VM work needs); it is updated with each phase.

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
Grant the role to the token as well as to the user (the effective rights of a privilege-separated token cannot exceed
those of its user). A token that had only the user's role got no access, and the setup with the role on both worked; whether
the user's own entry is strictly required was not tested separately. The commands below are the Proxmox VE 8 syntax. The
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
The privileges needed for VM, storage and network management are listed here as those features are added.

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

Use the existing CloudStack `updateHostPassword` operation (also the "Change password" action on a host in the UI). The
token id goes in `username` and the token secret in `password`.

- **Same token id, new secret (rotation).** Recreate the token in Proxmox under the same id
  (`pveum user token remove cloudstack@pve cs`, then `pveum user token add cloudstack@pve cs --privsep 1`, and give the new
  token its permissions again), then update CloudStack. For the whole cluster in one call:

  ```bash
  cmk update hostpassword clusterid=<cloudstack-cluster-id> username='cloudstack@pve!cs' password=<new-secret> update_passwd_on_host=true
  ```

  The UI action works per host and sends no `update_passwd_on_host`.
- **What `update_passwd_on_host=true` does for Proxmox.** Nothing is changed on the Proxmox node (a token's secret cannot be
  changed there). The running connection is told to use the new token after Proxmox has accepted it, so the change takes
  effect at once and a wrong secret is reported. Without it the new token is stored and used the next time the host
  reconnects; if the old token was revoked in the meantime the host shows `Alert` until the reconnect scan (see
  behaviour.md, section 5a) picks up the new one.
- **A different token id or user is not supported by this operation**: CloudStack requires the username to match the stored
  one and refuses otherwise ("The username is not same for the hosts"). To change the id, disable and remove the hosts and
  add them again.

## 4. Proxmox cluster

- One CloudStack cluster maps to one Proxmox cluster. Adding a node of a different Proxmox cluster to a CloudStack
  cluster that already holds nodes is refused.
- Proxmox HA is configured on the Proxmox side. CloudStack does not configure or restart anything for HA.

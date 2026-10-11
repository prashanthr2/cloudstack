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

2. Import it into the truststore of the Java runtime that runs the management server. The default truststore is
   `$JAVA_HOME/lib/security/cacerts` (default password `changeit`), unless the process is started with
   `-Djavax.net.ssl.trustStore=...`:

   ```bash
   keytool -importcert -trustcacerts -alias pve-root-ca -file pve-root-ca.pem \
       -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
   ```

   Use the Java installation the management server really runs with. A Java package update can replace `cacerts`; if that
   is a concern, keep a dedicated truststore and point the management server at it with `-Djavax.net.ssl.trustStore` in
   its `JAVA_OPTS`. (The `cloud.jks` keystore in `/etc/cloudstack/management` holds the management server's own HTTPS
   certificate and is not used for this.)

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

### Creating a token (**to verify** on the lab)

Run on a Proxmox node. A dedicated user with a token whose rights are limited with privilege separation is recommended;
the effective rights of such a token are the intersection of the token's and the user's permissions.

```bash
pveum user add cloudstack@pve
pveum user token add cloudstack@pve cs --privsep 1
pveum acl modify / --users cloudstack@pve --roles PVEAuditor
pveum acl modify / --tokens 'cloudstack@pve!cs' --roles PVEAuditor
```

For a quick lab, a token of `root@pam` with privilege separation turned off also works but gives CloudStack full control
of the cluster; do not use that outside a lab.

### Permissions

What the plugin calls, and what it therefore needs:

| API call | Used for | Privilege (**to verify**) |
|---|---|---|
| `GET /version` | check the connection when adding a cluster | none beyond a valid token |
| `GET /cluster/status` | list nodes, node addresses, cluster name | `Sys.Audit` |
| `GET /nodes` | fallback for a standalone node | `Sys.Audit` |
| `GET /nodes/{node}/status` | CPU, memory, version, health check, statistics | `Sys.Audit` on `/nodes/{node}` |
| `GET /nodes/{node}/qemu` | VMs on a node, for the power state report | `VM.Audit` on `/vms` (without it the list comes back **empty**, so VM state would silently be missing) |

At this stage everything is read-only, and the built-in `PVEAuditor` role (it contains `Sys.Audit` and `VM.Audit`) is
expected to be enough. The privileges needed for VM, storage and network management will be listed here as those features
are added.

### How the secret is stored

The token id and secret are saved with each host under the host detail keys `username` and `password`. The `password` key
is encrypted by the shared host details DAO when database encryption is enabled, exactly as for the VMware, XenServer,
Hyper-V and OVM plugins, which store their passwords under the same key. With encryption off (a default development
setup) it is stored as plain text, as for those plugins.

## 4. Proxmox cluster

- One CloudStack cluster maps to one Proxmox cluster. Adding a node of a different Proxmox cluster to a CloudStack
  cluster that already holds nodes is refused.
- Proxmox HA is configured on the Proxmox side. CloudStack does not configure or restart anything for HA.

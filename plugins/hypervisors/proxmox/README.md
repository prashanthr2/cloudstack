# Proxmox VE hypervisor plugin (prototype)

Native Proxmox VE support for CloudStack, as a hypervisor type of its own (`Proxmox`) rather than through the
extensions framework.

**Status:** prototype, work in progress. Phase 1 (registration, discovery, host lifecycle, failure detection) is done
and tested in a lab. VM lifecycle, storage, networking, migration and HA are not implemented yet.
See [docs/requirements.md](docs/requirements.md) for what has to be in place (ports, TLS, API token),
[docs/behaviour.md](docs/behaviour.md) for every behaviour and the reasoning behind it, and
[docs/test-log.md](docs/test-log.md) for what was tested and the bugs the tests found, ,
[docs/design-phase2-storage.md](docs/design-phase2-storage.md) for the NFS storage design (proposal), and
[docs/upstream-notes.md](docs/upstream-notes.md) for observations about CloudStack itself that are not changed here.

## How it works

- A Proxmox cluster is a CloudStack **cluster**; each Proxmox node is a CloudStack **host**.
- Nothing is installed on the nodes. One `ProxmoxResource` per node runs inside the management server and talks to the
  Proxmox REST API (`/api2/json`) with an API token.
- Proxmox does its own HA. CloudStack never restarts Proxmox VMs by itself (see "HA stance" in the behaviour doc).

## Adding a cluster

Read [docs/requirements.md](docs/requirements.md) first: ports, TLS and the API token.

1. Create a cluster with hypervisor `Proxmox`.
2. Add a host:
   - **Host name:** the address of any node, with a port if it is not 8006: `10.0.35.25` or `10.0.35.25:8443`.
     (The UI puts `http://` in front of what you type; the scheme is ignored and the API is always reached over HTTPS.)
   - **Username:** the API token id, `user@realm!tokenname`.
   - **Password:** the token secret.
3. One call adds **every online node** of the Proxmox cluster, one host per node. Adding the same address again adds only
   nodes that are not added yet, which is how a node that joined the Proxmox cluster later is picked up.
4. A CloudStack cluster holds one Proxmox cluster; nodes of another Proxmox cluster are refused.

The API certificate is not verified by default (`proxmox.api.verify.tls` is `false`), like the VMware and XenServer plugins.
To verify it, import the Proxmox cluster CA into the management server's Java truststore and set the setting to `true`;
the steps are in [docs/requirements.md](docs/requirements.md).

## Changes outside the plugin

| File | Change |
|---|---|
| `api/.../hypervisor/Hypervisor.java` | New `HypervisorType.Proxmox` (qcow2, root disk size override) |
| `server/.../configuration/Config.java` | `Proxmox` added to the default `hypervisor.list` |
| `engine/schema/.../db/schema-42300to2400.sql` | Appends `Proxmox` to `hypervisor.list` on existing installs |
| `plugins/pom.xml`, `client/pom.xml` | New module, and the dependency that packages it with the management server |

## Building and testing

```bash
mvn install -pl plugins/hypervisors/proxmox          # builds and runs the unit tests
```

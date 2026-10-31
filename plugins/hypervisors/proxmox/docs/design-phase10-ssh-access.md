# Phase 10 design: SSH access to the Proxmox nodes (first version, for discussion)

Status: **first version; nothing built.** Direction decided with the user (2026-10-31): the plugin keeps using the Proxmox API for everything the API can do, and uses SSH for **host commands** that CloudStack's way of working needs and the API does not offer. Add Host takes **only a username and a password, or an SSH key**, as for KVM; the plugin then creates the API user and token itself. The snapshot work (design-phase10-snapshots.md) and a simpler template and ISO layout come after this.

## 1. The rule: where SSH is used and where the API is used

> **The API is the default.** Anything Proxmox offers as an API call (VM and disk lifecycle, storage entries, migration, tasks, statistics, the console, the guest agent) goes through the API with the token, as now. **SSH is used only for commands on the node itself** that the API cannot do: creating the API user, reading or writing files on the node's filesystem, `qemu-img`, mounts and links that the storage entries do not cover.

This section is the list to keep up to date. Every feature that uses SSH is added here with the command it runs and why the API could not do it.

### 1.1 Uses the API only (everything built so far)

| Area | What | API (examples) |
|---|---|---|
| Host add and discovery | connection check, nodes, cluster name, version, control bridge (needs `Sys.Modify`) | `GET /version`, `/cluster/status`, `/nodes/{node}/network` |
| Primary storage | one NFS entry per CloudStack pool, per node mount check | `POST/DELETE /storage`, `/nodes/{node}/storage` |
| Templates and ISOs | read-only NFS entry per secondary store, one directory entry per account folder, `import-from` | `POST /storage`, content list |
| Instances | create, configure, start, stop, reboot, destroy, HA flag, statistics, ping | `/nodes/{node}/qemu/...` |
| Volumes | create (allocate), resize, delete, attach, detach, the holder VM and its alias map, copy and live move (`move_disk`), hand-over (`move_disk` with `target-vmid`) | `/nodes/{node}/storage/{storage}/content`, `qemu/{vmid}/config`, `move_disk` |
| Migration | instance, instance with volumes, router | `qemu/{vmid}/migrate` and the above |
| System VMs and routers | commands and files through the guest agent | `qemu/{vmid}/agent/...` |
| Console | websocket to the VM | `vncproxy`, websocket with the token |

### 1.2 Will use SSH (new)

| Feature | Command on the node | Why the API cannot do it |
|---|---|---|
| Add Host: create the API user, role and token | `pveum user add`, `pveum role add`, `pveum acl modify`, `pveum user token add` | the API token needed for the API does not exist yet, and the token secret is shown only when it is created |
| Add Host: install the management server's public key for the system VM login | write one line to a file on the node | the API cannot write files on the node (replaces the manual `proxmox.systemvm.ssh.key` step, open issue 22) |
| Volume snapshots (later) | `qemu-img snapshot`, `qemu-img convert -l`, `qemu-img snapshot -d` | the API snapshots a VM, not a disk, and cannot read a snapshot out of the file |
| Templates and ISOs (later, to decide) | links or copies in the node's filesystem | the API accepts only volume ids in storage entries (root-only for other paths), hence one entry per account folder today |

## 2. Add Host

| Field | Value |
|---|---|
| Username | the Proxmox login that may run `pveum` and the commands above, normally `root` |
| Password | its password; **may be empty** when the management server's public key (the global setting `ssh.privatekey`, as for KVM) is in the node's `authorized_keys` |

Order, as in `LibvirtServerDiscoverer.find`: the key first, then the password. With the login the plugin:
1. connects over SSH to the node it was given and checks the host key (below);
2. creates, once per cluster, the user `cloudstack@pve` and a role with the privileges of requirements.md section 3; creates **one token per host** (`cs-<host guid>`) with privilege separation, gives role to user and token, and keeps the secret in the host details (encrypted as the password is today). One token per host because the secret is shown only at creation: a shared token would have to be recreated for the second node, which would break the first;
3. installs the management server's public key for the system VM login;
4. from then on talks to the API with the token. The SSH login is kept (user, and the password if one was given) for the features in 1.2.

Removing a host revokes its token (best effort). A host whose token was removed by the administrator reports a clear message and is fixed by Update Host password (the plugin creates a new token).

**Hosts added before this change** keep the token they have; they work as today and have no SSH login, so a feature that needs SSH says "SSH is not set up for this host" in its error. Adding the host again gives it SSH.

**Host key:** the node's host key fingerprint is stored when the host is added and checked on every later connection (the counterpart of `proxmox.api.verify.tls`); a changed key fails the command with a message.

## 3. Where the commands run

A command that touches the node's filesystem runs on the node that holds the thing, like the API calls do: the host the CloudStack command was sent to. Storage that all nodes see (NFS) can be reached from any node, so a detached volume's command may run on the node the command arrived at.

## 4. Open questions

1. **Token-only Add Host:** removed for new hosts as you said ("only username and password or SSH key"); existing hosts keep their token. Is that right, or should a token login stay as a documented alternative for people who will not give SSH?
2. **SSH user:** always what is typed in Add Host (root by default), as KVM. A restricted user with `sudo` for named commands is possible later.
3. **Privileges of the created role:** the minimum from requirements.md section 3 plus `Sys.Modify` for the bridge; I would write the exact list in the build step and test it with a fresh user.
4. **Where the SSH login is kept for later use:** host details (the password encrypted, as KVM does); the key stays the global `ssh.privatekey`.
5. **Order:** SSH layer and Add Host first (a small step, lab-testable by itself), then volume snapshots, then the template and ISO layout, instance snapshots whenever.

## 5. Plan

1. `ProxmoxSsh`: run a command on a node, copy a file, timeouts, host key check (CloudStack's `SSHCmdHelper` is already in the code base); unit tests with a fake connection.
2. Add Host: key then password, create user, role and token, install the system VM key, store the login; revoke on removal; messages for each failure. Existing token hosts unchanged.
3. Docs: requirements.md (Add Host, privileges, what SSH is used for), behaviour.md, this table kept current, tests, lab checks (add a host with a password, with a key, with a wrong password, with `PermitRootLogin` off; revoke).

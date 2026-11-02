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

Removing the last host of a cluster revokes the token (best effort). If Proxmox stops accepting the token (removed by the administrator), the next Add Host in that cluster makes a new one and the other hosts take it up when they reconnect.

**Hosts added before this change** keep the token they have; they work as today and have no SSH login, so a feature that needs SSH says "SSH is not set up for this host" in its error. Adding the host again gives it SSH.

**Host key:** the node's host key fingerprint is stored when the host is added and checked on every later connection (the counterpart of `proxmox.api.verify.tls`); a changed key fails the command with a message.

## 3. Where the commands run

A command that touches the node's filesystem runs on the node that holds the thing, like the API calls do: the host the CloudStack command was sent to. Storage that all nodes see (NFS) can be reached from any node, so a detached volume's command may run on the node the command arrived at.

## 4. Decisions and open points

Decided with the user (2026-10-31):
1. **No token login as an alternative.** Add Host takes a username and password or an SSH key, and nothing else. A token-only host would have no SSH, so volume snapshots would not work for it and the template and ISO layout would have to keep creating one storage entry per account folder. Hosts are deleted and added again as a fresh start; no migration of existing token hosts.
2. **The SSH user may be a non-root user, with `sudo`.** The documentation says that the user needs `sudo`. **Before building**, check in the lab that `sudo` can replace root for every host command the plugin runs (section 6); if one cannot be done through `sudo`, the documentation says so.
3. **Order:** SSH layer and Add Host first, then volume snapshots, then the template and ISO layout; instance snapshots whenever.

Open:
- **Privileges of the created API user (decided: the narrow set).** Proxmox grants rights as privileges collected in roles, and the token can do only what its roles allow. The plugin creates the user and token with the roles requirements.md already prescribes: the built-in `PVEVMAdmin` on `/vms`, `PVEDatastoreAdmin` on `/storage`, `PVESDNUser` on `/sdn/zones/localnetwork`, plus two small custom roles, `CloudStackHA` (`Sys.Audit`, `Sys.Console`) and `CloudStackNet` (`Sys.Modify`, to create the control bridge). The alternative is one broad built-in role (`PVEAdmin`), which is simpler and gives the token more than it needs. Decided by the user (2026-10-31): the narrow set.
- **How `sudo` asks for a password:** `sudo -n` (needs `NOPASSWD` for the user) or `sudo -S` with the login password. Proposal: support both, `NOPASSWD` recommended and documented, `sudo -S` with the Add Host password when there is none.

## 5. Plan

1. `ProxmoxSsh`: run a command on a node, copy a file, timeouts, host key check (CloudStack's `SSHCmdHelper` is already in the code base); unit tests with a fake connection.
2. Add Host: key then password, create user, role and token, install the system VM key, store the login; revoke on removal; messages for each failure. Existing token hosts unchanged.
3. Docs: requirements.md (Add Host, privileges, what SSH is used for), behaviour.md, this table kept current, tests, lab checks (add a host with a password, with a key, with a wrong password, with `PermitRootLogin` off; revoke).

## 6. Lab checks for a non-root user with sudo (before building)

On one node, create a user and give it `sudo` (first with `NOPASSWD: ALL`, later with the list of commands only):

```bash
adduser csadmin
echo 'csadmin ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/csadmin && chmod 440 /etc/sudoers.d/csadmin
```

Then as `csadmin` (over `ssh csadmin@<node>`, not `su`, since the plugin connects like that):

| # | Command | Answers |
|---|---|---|
| X1 | `sudo -n pveum user add cltest@pve && sudo -n pveum user token add cltest@pve t1 --privsep 1 --output-format json` | can `pveum` create user and token, is the secret printed in a form we can read |
| X2 | `sudo -n pveum role add CloudStackNetTest --privs "Sys.Modify" && sudo -n pveum acl modify / --users cltest@pve --roles CloudStackNetTest` | roles and ACLs through `sudo` |
| X3 | `sudo -n ls /etc/pve/priv` and `echo test \| sudo -n tee /etc/pve/priv/cltest_key > /dev/null` | writing under `/etc/pve` (the cluster filesystem) through `sudo` |
| X4 | `sudo -n qemu-img info /mnt/<pool uuid>/images/<some vmid>/<disk>.qcow2` on an NFS volume | reading a disk image through `sudo` on NFS (root squash can change the answer) |
| X5 | `sudo -n qemu-img snapshot -l <detached volume file>` | listing snapshots, the first step of a volume snapshot |
| X6 | `sudo -n ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` | reading the host key fingerprint |
| X7 | the same X1 with `sudo` **without** `NOPASSWD` and `echo '<password>' \| sudo -S -p '' pveum user list` | `sudo -S` with a password on standard input |
| X8 | `sudo -n id` over a non-interactive `ssh csadmin@<node> sudo -n id` | no terminal needed (`requiretty`) |

Clean up afterwards: `sudo pveum user delete cltest@pve`, `sudo pveum role delete CloudStackNetTest`, `sudo rm -f /etc/pve/priv/cltest_key`.

### Results of the sudo checks (lab, node pve2, user `csadmin` with `NOPASSWD: ALL`, 2026-10-31)

| # | Result |
|---|---|
| X1 | **Pass.** `sudo -n pveum user add` and `user token add --output-format json` work; the output is `{"full-tokenid":"cltest@pve!t1","info":{"privsep":"1"},"value":"<secret>"}`, so the secret is read from `value` |
| X2 | **Pass.** `sudo -n pveum role add` and `acl modify` work |
| X3 | **Not run properly** (the command was typed with `and` instead of `;`; the `ls` failed, the `tee` ran without an error but was not checked). To repeat: `sudo -n ls /etc/pve/priv; echo test \| sudo -n tee /etc/pve/priv/cltest_key > /dev/null; sudo -n cat /etc/pve/priv/cltest_key` |
| X4 | **Pass.** `sudo -n qemu-img info` reads a volume on the NFS pool (root squash does not stop it through `sudo`) |
| X5 | **Pass.** `sudo -n qemu-img snapshot -l` on a holder volume answers (an empty list: no snapshots) |
| X6 | **Pass.** `sudo -n ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` gives `256 SHA256:x5aX... (ED25519)`, the form the plugin stores as the host key fingerprint |
| X7 | **Not a real test**: with `NOPASSWD: ALL` the password on standard input was never asked for. To repeat with a sudoers line without `NOPASSWD` (`csadmin ALL=(ALL) ALL`) |
| X8 | **Pass.** `ssh csadmin@<node> sudo -n id` over a non-interactive connection gives `uid=0(root)`; no terminal is needed (`requiretty` is not set) |

## 7. Step 1 as built: the SSH layer (`org.apache.cloudstack.hypervisor.proxmox.ssh`)

- `SshLink` (one connection: connect with a host key check, login with a key or a password, run a command with standard input) and `TrileadSshLink` (on the Trilead library CloudStack uses elsewhere; the fingerprint is `SHA256:` plus the base64 of the SHA-256 of the key blob without padding, the form `ssh-keygen -l` prints).
- `ProxmoxSsh.open(target, links)`: key first, then password, as for a KVM host; the host key is compared with the one kept for the node (a changed key fails with a message that says what to do; the first connection reports the key to keep); no key and no password is refused with a message.
- `ProxmoxSsh.sudo(...)`: root runs commands as they are; another user runs `sudo -n sh -c '<command>'` when `sudo -n true` works, else `sudo -S -p ''` with the login password as the first line of standard input; with neither, the message says to give the user `NOPASSWD` or the password in Add Host. `sudoOrFail` raises an exception with what the command printed. `writeFile` writes a root-owned file from standard input (the content is never on a command line) and sets its mode. `quote` writes a word for the shell.
- Unit tests only (`ProxmoxSshTest`, 15 tests, with a fake link). **The real `TrileadSshLink` has not been used against a node yet**; the first lab use is Add Host (step 2). Lab checks X3 and X7 of section 6 are still to repeat.

## 8. Step 2 as built: Add Host with a login (one token for the cluster)

- `ProxmoxApiAccess` (package `ssh`): `createClusterToken(ssh, clusterName, manageHa)` makes the user `cloudstack@pve` and the roles `CloudStackNet` and (when HA is managed) `CloudStackHA` if they are missing, replaces the token `cs-<cluster name>` with a new one with privilege separation (the secret comes from `pveum user token add --output-format json`, lab X1), and gives the roles to the user and the token on the paths of requirements.md section 3; `grantNode` gives `PVEAuditor` and `CloudStackNet` on one node's own path without propagation (Proxmox does not add the roles of a path to the inherited ones, it replaces them: with `CloudStackNet` alone the token lost `Sys.Audit` on the node and the node status failed with HTTP 403, found in the first lab round, 2026-11-02); `revokeToken` removes a token by its id and forgives one that is gone.
- `ProxmoxServerDiscoverer.find`: logs in to the given address (the management key first, then the password). The token comes from the cluster's details when the CloudStack cluster has one; otherwise it is made (the name is the Proxmox cluster's, read with `pvesh get /cluster/status`, or the node's name when it is not in a cluster) and kept there. If Proxmox answers 401 to a kept token, a new one is made. Each online node that is not added yet: the same login is made on it (a node that refuses is skipped and logged), its host key is kept, it gets `CloudStackNet` on its path, the control bridge is made with the token, and the resource is returned with the token (as `username` and `password`) and the host details `sshuser`, `sshpassword` (encrypted by the plugin with `DBEncryptionUtil`, since core encrypts only a detail named `password`) and `sshfingerprint`.
- `buildConfigParams` (the discoverer overrides it, no core change): a host is rebuilt after a restart from its details plus the cluster's `username` and `password`; a host that still has its own (added before this change) keeps it.
- `deleteHost`: when the host is the last Proxmox host of its CloudStack cluster, the token is removed over SSH and the cluster's details `username` and `password` are deleted; best effort.
- Not built yet: the use of the stored login by a feature (the first is volume snapshots), Update Host password for the new details, and giving the HA role when `proxmox.ha.manage` is turned on after hosts were added.
- Tests: `ProxmoxApiAccessTest` (9), `ProxmoxServerDiscovererTest` (24): 515 plugin tests pass. **Not run against a node.**

## 9. Proxmox VE 9: comparison with the source (2026-11-02)

The plugin was built and lab-tested only on Proxmox VE 8.4.21. Before adding Proxmox 9 hosts, every API call and `pveum` command the plugin uses was compared with the Proxmox VE 9 source mirrors in `promox-src` (`qemu-server` 9.2.11, `pve-storage` 9.1.13, `pve-access-control` 9.1.2, `pve-common` 9.2.3). This is reading only: nothing was run on a Proxmox 9 node. **Result: no difference found that needs a change in the plugin.**

| Area | What was compared | Result on 9 |
|---|---|---|
| `move_disk` | `storage`, `format`, `delete`, `target-vmid`, `target-disk`; refusal of a disk with snapshots when the source is deleted; disk names | same parameters; same refusal ("you can't move a disk with snapshots and delete the source"); names still `vm-<vmid>-disk-<n>`, so the holder VM and alias logic apply |
| `migrate` | `target`, `online`, `force`, `targetstorage`; the pre-check call | same |
| VM power and config | `status/shutdown` (`forceStop`, `timeout`), `status/stop` (`overrule-shutdown`), `vncproxy` (`websocket`), `config?current=1`, delete with `purge` | same |
| Storage entries | dir and NFS options `content-dirs`, `is_mountpoint`, `create-subdirs`, `create-base-path`, `shared`; the `import` content type | same. New option `snapshot-as-volume-chain` exists (fixed at creation); the plugin does not set it |
| Disk create | content API `filename`, `vmid`, `format`, `size` | same |
| Roles and privileges | `PVEVMAdmin`, `PVEDatastoreAdmin`, `PVESDNUser`, `PVEAuditor`, `Sys.Modify`, `Sys.Audit`, `Sys.Console` | all still exist. 9 dropped `VM.Monitor`, split `VM.GuestAgent.*` and added `VM.Replicate`; the plugin names none of them, the built-in roles carry the right ones |
| Token | `pveum user token add` with `--privsep` and `--output-format json` | same |
| SSH | host and key algorithms of Debian 13 OpenSSH against the Trilead library (`build-217-jenkins-27`: Curve25519, ECDH, ED25519, ECDSA, RSA) | expected to work; confirm on a node |

**Not checked (not in the mirrors; confirm on a Proxmox 9 node):**
- cluster and node calls: `/version`, `/cluster/status`, `/cluster/resources`, `/cluster/nextid`, node status, tasks, node network create, apply and revert (the control bridge);
- HA: 9 added HA rules; the plugin uses only the HA resources call (`/cluster/ha/resources`, no group);
- the ACL path `/sdn/zones/localnetwork` that lets the token use bridges (the check is in `pve-guest-common`, not mirrored);
- a cluster with both 8 and 9 nodes (only meaningful during an upgrade).

**Lab check on one Proxmox 9 node (own cluster, so the 8.4 cluster is not touched):** Add Host with root and password; one token `cs-<cluster>`; start a VM with a network (control bridge and bridge ACL); attach and detach a data volume; live volume migration (T8); HA enabled and disabled for a VM; delete the host removes the token. Record the results in `test-log.md`.

## 10. First lab round (2026-11-02): two findings

- **Add Host failed with HTTP 403 `Sys.Audit` on the node** after the control bridge was made: the node path got `CloudStackNet` alone, and Proxmox replaces the inherited roles with the ones set on a path. Fixed: `grantNode` gives `PVEAuditor` and `CloudStackNet` (section 8).
- **Deleting the last host did not remove the token.** `ExternalServerDiscoverer.deleteHost` answered for every host and ended the dispatch before the Proxmox adapter was asked (upstream-notes 29). Fixed with a type guard in both adapters: the Proxmox adapter returns `null` for a host that is not a Proxmox routing host. Lab check to repeat: delete all hosts of the cluster, then `pveum user token list cloudstack@pve` is empty and the `username` and `password` cluster details are gone.
- **The console of a system VM failed ("access token has expired").** `POST .../vncproxy` has no `proxyto` (qemu-server `API2/Qemu.pm`): the node that *receives* the call opens the listener on its own `localhost` (port 5900 and up) and reaches the VM's node by SSH. The plugin sent the call to whichever cluster address its client was using, and built the websocket URL from the address of the node that holds the VM. When the two differ, the receiving node waits 10 seconds for a connection that never comes (task result `connection timed out`) and the VM's node, which gets the websocket, finds nothing on `localhost:5900` (`pveproxy: connect to 'localhost:5900' failed: Connection refused`). The lab showed both for the console proxy VM 101 (on `pve3`, call served by `pve2`); the user VM 103 worked because it was on the node whose resource handled the call. Fixed: `ProxmoxApiClient.atEndpoint` makes a view that talks to one address, and `ProxmoxVmHandler.console` sends `vncproxy` to the address that goes into the URL. Lab check to repeat: the console of a system VM and of a user VM on a node other than the one whose resource answers.

## 11. Step 2b as built: the system VM login key (2026-11-02)

Add Host makes the key pair for the login from a node to a system VM and puts its public key into the setting `proxmox.systemvm.ssh.key` (what the administrator did by hand in Phase 8, requirements.md "Logging in to a system VM from a node").
- `ProxmoxSystemVmKey.ensure(ssh)`: reads `/etc/pve/priv/cloudstack_systemvm.pub`; when it is missing, derives the public key from a private key that is there (`ssh-keygen -y`) or makes the pair (`ssh-keygen -q -t ed25519 -N '' -C cloudstack-systemvm -f /etc/pve/priv/cloudstack_systemvm`) through `sudo`. The pair is made once for the whole Proxmox cluster, because `/etc/pve/priv` is cluster-wide.
- `ProxmoxSystemVmKey.addTo(setting, key)`: the new value with the key after the keys that are there (separator `|`), or null when the key (type and key, not the comment) is in the setting already. The setting is written with `ConfigurationDao.update`.
- `ProxmoxServerDiscoverer.installSystemVmLoginKey`: called once per Add Host, after the token. Any problem (a refused write, a setting that cannot be updated) is a warning in the log, never a failed Add Host.
- Not done on delete: the pair and the setting stay when the last host is removed (the key opens no door without the private file, which is only on the nodes).
- Tests: `ProxmoxSystemVmKeyTest` (6) and four in `ProxmoxServerDiscovererTest`. Lab checks: (1) on a cluster without the pair, Add Host makes it (`ls -l /etc/pve/priv/cloudstack_systemvm*` on every node) and the setting has the public key; (2) a setting that has another key keeps it and gets ours after it; (3) adding a host again changes nothing; (4) restart a system VM and log in from its node with `ssh -i /etc/pve/priv/cloudstack_systemvm -p 3922 root@<control address>`; (5) with `chmod` or the write refused the host is still added and the log says why.

## 12. Add Host form: the SSH key choice (2026-11-02)

The Add Host form showed "Authentication Method" with the single choice Password for a Proxmox cluster: `ui/src/views/infra/HostAdd.vue` offered the SSH key button for KVM only. One condition changed (`['KVM', 'Proxmox']`); the form already sends an empty password for that choice, which the plugin reads as "use the management server's key" (`ssh.privatekey`). Part of the UI row (H) of the pull request plan in upstream-notes.md. Needs the UI to be rebuilt (`npm run build` in `ui/`, or the dev server) to show.

# Phase 4 design: console access (noVNC) for Proxmox VMs

Status: **proposal, for review.** Nothing described here is implemented. "V" means read in code (CloudStack in this repository, Proxmox in the
8.x mirrors under `promox-src`: `qemu-server`, `pve-access-control`, `pve-common`; the lab runs 8.4.21). "U" means **unverified**: it lives in
`pve-manager` / `pve-http-server` (pveproxy), which are not mirrored, or has not been run. Each U has a lab check in section 7.

## 1. Goal and scope

A user clicks "View console" on a running Proxmox VM and gets the noVNC console in the browser, through CloudStack's console proxy VM (CPVM),
like other hypervisors. Not in scope: SPICE, the serial (xterm) console, copy and paste, a console for system VMs.

## 2. What CloudStack does (V)

1. The UI (`ui/src/components/widgets/Console.vue`) calls `createConsoleEndpoint`; it has no hypervisor logic.
2. `ConsoleAccessManagerImpl.generateConsoleEndpoint` / `getConsoleConnectionDetails` decides host, port and password. For most hypervisors it
   sends `GetVncPortCommand` to the VM's host and gets an address and port; the password (`sid`) is `vm.getVncPassword()`, which KVM and VMware
   set on the VM at start. It then builds a token (AES-encrypted `ConsoleProxyClientParam`: host, port, password, tag, ticket, session, and
   `websocketUrl`) and a noVNC URL on the CPVM (`.../noVNC/vnc.html?...&token=<token>`).
3. The browser opens a websocket to the CPVM (`websockify`, binary). The CPVM (`ConsoleProxyNoVNCHandler`, `ConsoleProxyNoVncClient`) decrypts the
   token and connects to the VM's console in one of three ways: a raw TCP socket to host:port speaking RFB (KVM and others), an HTTP CONNECT
   tunnel (XenServer), or **a websocket to `websocketUrl`** (VMware 7 and later: `WebSocketReverseProxy`).
4. With a `websocketUrl` the CPVM does no RFB handling: it relays bytes, the browser's noVNC does RFB end to end, and the CPVM skips its
   callback to the management server (`ConsoleProxy.authenticateConsoleAccess`).
5. `External` hypervisors return `GetExternalConsoleAnswer` (url, host, port, password, protocol) through the extensions framework; protocol
   `direct` makes the UI open the returned URL itself.
6. There is no Proxmox reference in any console code. `vm_instance.vnc_password` exists for every VM (random); no VNC port is stored anywhere.

## 3. What Proxmox does (V unless marked U)

**Request sequence** (`qemu-server` `API2/Qemu.pm`):
1. `POST /nodes/{node}/qemu/{vmid}/vncproxy` with `websocket=1`. Permission `VM.Console` on `/vms/{vmid}`; nothing excludes API tokens. Returns
   `port`, `ticket`, `password` (8 characters), `cert`, `user`, `upid`. The `user` is the full `user@realm!tokenid` for a token. The VM must be running
   and have a display (`vga` not `none`; with `serial0` the console is a text terminal).
2. A worker on the node listens on `localhost:<port>` (5900 to 5999, no TLS) and waits for **one** connection, with an alarm (10 s as reported).
   It then runs `qm vncproxy`, which sets the VNC password in QEMU with a 30 s expiry and relays to QEMU's unix socket. A Proxmox VM's QEMU has no
   TCP VNC listener (Proxmox starts it with `-vnc unix:...` only), and `args` (the only way to get one) is root-only. This is the difference from
   KVM: the KVM agent writes `<graphics type='vnc' autoport='yes' listen='<host address>' passwd='<vnc password>'/>` into the libvirt definition, so QEMU
   listens on TCP at 5900 and up, `GetVncPortCommand` answers with that address and port, and the CPVM connects to it directly (V:
   `LibvirtVMDef.GraphicDef`, `LibvirtGetVncPortCommandWrapper`).
3. `GET wss://<node>:8006/api2/json/nodes/{node}/qemu/{vmid}/vncwebsocket?port=<port>&vncticket=<urlencoded ticket>`. pveproxy checks the
   permission and `verify_vnc_ticket`: an RSA-signed ticket bound to the **same user (token) string**, the path and the port, accepted for about
   40 s. The websocket then carries plain RFB; the VNC password is the `password` from step 1.
4. U: the websocket request is authenticated like any API request. A browser cannot set headers, so the Proxmox GUI uses a login cookie; a
   server-side client can send `Authorization: PVEAPIToken=...`. This is the first thing to verify (check C2).

Consequences:
- Every console session needs its own `vncproxy` call; the ticket, the port and the password are good for seconds and for one connection. A
  reconnect needs a new call, so noVNC's automatic reconnect cannot work: the user clicks "View console" again.
- Only TCP 8006 of the node must be reachable from the CPVM. The 5900 to 5999 listener stays on localhost.
- `vncproxy` and `vncwebsocket` have no `proxyto`: the call must go to the node that holds the VM (the plugin knows it: Phase 3 finds a VM
  anywhere in the cluster).
- Migration or HA relocation kills the QEMU process of the source and closes the console; the user reopens it.
- Privileges: the token's roles from Phase 3 already include `VM.Console` (it is in `PVEVMAdmin`; the lab token's permission list shows it).
- The non-websocket mode listens on all interfaces with TLS from the first byte, which the CPVM's raw TCP code cannot speak, and needs ports
  5900 to 5999 open; no advantage over the websocket mode.

## 4. Options

| | Idea | Core change | Verdict |
|---|---|---|---|
| **A** | **The CPVM opens the websocket (the VMware 7 path) and sends the API token as a header** | `ConsoleAccessManagerImpl`, `ConsoleProxyClientParam` (two copies), the CPVM handler and `NoVncClient`, plus the noVNC URL; the CPVM must be patched | **Recommended** (section 5) |
| B | A relay in the management server: the plugin opens the websocket itself and exposes a one-time TCP port to the CPVM, translating RFB authentication | none (`GetVncPortCommand` is enough) | Possible, not recommended: custom security-sensitive RFB code in the management server, an open port there for a few seconds without a password, and all console traffic through it |
| C | The browser connects to Proxmox directly (External "direct" mode) | small | Not possible: the websocket needs a login cookie or a token header, which a browser cannot send |
| D | `args -vnc` for a persistent TCP VNC | none | Not possible for a token (root-only), and insecure |

## 5. Proposed design (option A)

**Click time** (management server):
1. `ConsoleAccessManagerImpl` recognises a Proxmox VM and asks its host (the node the VM is on) for the console, with a command that returns the
   websocket URL, the VNC password and the authorization header value. To avoid a new core class this reuses `GetExternalConsoleCommand` /
   `GetExternalConsoleAnswer` (url, password, protocol), unless a field for the header is needed, in which case one is added.
2. The plugin (`ProxmoxResource`): finds the VM by name, calls `vncproxy` with `websocket=1`, and answers with
   `wss://<node ip>:8006/api2/json/nodes/<node>/qemu/<vmid>/vncwebsocket?port=<port>&vncticket=<encoded ticket>`, the `password`, and the
   header `PVEAPIToken=<token id>=<secret>`.
3. `ConsoleAccessManagerImpl` puts the URL into the token's `websocketUrl` and the password into the noVNC URL (`password=`: noVNC then
   authenticates without asking; it is valid for one connection and about 30 s), and the header value into a new `ConsoleProxyClientParam` field.

**Connect time** (CPVM): `ConsoleProxyHttpHandlerHelper`, `ConsoleProxyNoVNCHandler` and `NoVncClient.connectToWebSocket` carry the header and
call `addHeader` on the websocket client before it connects. The CPVM already accepts any certificate for `wss`, skips its own authentication
callback when a websocket URL is present, and routes to the node. The CPVM's network must reach `<node>:8006`.

**Core change list:** `ConsoleAccessManagerImpl` (Proxmox branch, URL and password handling), `ConsoleProxyClientParam` in `server` and in
`services/console-proxy` (new field, kept in sync), `ConsoleProxyHttpHandlerHelper`, `ConsoleProxyNoVNCHandler`, `NoVncClient` (header), and
possibly `GetExternalConsoleAnswer`. The console proxy VM must be rebuilt and patched, as the secondary storage VM had to be (see the
upstream notes, 10). **Plugin:** a console handler in `ProxmoxResource` and a small `ProxmoxApiClient` call (`vncproxy`).

**Risks and limits, stated plainly:**
- **The API token secret travels to the CPVM** inside the encrypted console token, so that the CPVM can authenticate the websocket. It is a
  long-lived credential; the CPVM is a trusted system VM and the token is consumed with a one-time session, but this widens where the secret is
  held. Mitigation for later: a dedicated token with only `VM.Console` for consoles (the ticket must be created and used by the same token).
- **Timing:** the websocket must connect within seconds of the click (the listener waits about 10 s, the ticket about 40 s). Opening noVNC and
  connecting normally takes a few seconds; check C3 measures the window. A slow page load fails the first connection and the user clicks again.
- **No reconnect** after a drop (see section 3).
- The password is in the noVNC URL for a few seconds (single use, 30 s expiry).
- A Proxmox VM with `vga: none` or without a running QEMU has no console.

## 6. Core impact in short

Option A touches about six core files and the CPVM; option B touches none but adds custom RFB code and an open port to the plugin. Both
were weighed (section 4); the choice between them is the main decision below.

## 7. Lab checks before building

Run on a Proxmox node that holds a **running** VM (use a VM deployed from CloudStack), with the token in `TP` (`source ~/tp.sh`). The script is
`docs/scripts/console-check.py` (standard library only):

```bash
python3 console-check.py <node name> <vmid>
```

- **C1** `vncproxy` with the token returns a port, a ticket and a password (permission `VM.Console` is sufficient).
- **C2** the websocket upgrade with the **same token as an `Authorization` header** answers `101 Switching Protocols`. If it answers 401 or 403
  the token header is not accepted on the websocket and option A as described does not work.
- **C3** the first bytes from the VM are `RFB 003.008` (the VM's VNC server speaks through the tunnel). Then repeat with a delay to find the
  real window: `python3 console-check.py <node> <vmid> 8`, then `15`, then `30`: the upgrade should succeed for short delays and fail after
  the listener's timeout; the numbers tell how fast the CPVM must connect.

## 8. Test plan once built

1. A running Proxmox VM: "View console" shows the guest's screen and keyboard and mouse work (admin and a normal user).
2. Open the console twice in a row, from two browsers: each gets its own session.
3. A stopped VM, a VM with `vga none`, and a VM that was just migrated: a clear message, no hang.
4. The CPVM log shows the websocket to the node and no stack traces; the token secret does not appear in any log.

## 9. Decisions for the reviewer

1. Option A (CPVM websocket with the token header, about six core files and a CPVM patch) or option B (a relay in the management server, no core
   change, custom RFB code), after the lab checks.
2. Accept that the API token secret is sent to the CPVM for consoles, or require a dedicated console token first.
3. Accept that consoles do not reconnect automatically.

#!/usr/bin/env python3
"""Lab check for the Proxmox console (design-phase4-console.md, checks C1 to C3). Standard library only.

Usage (on a Proxmox node, with the API token in the environment variable TP, "user@realm!tokenid=secret"):
    python3 console-check.py <node name> <vmid> [delay seconds between the two requests, default 0]

It does what the console proxy would do: POST vncproxy (websocket=1) with the API token, then opens the websocket with the ticket and the
SAME token as an Authorization header, and prints the answer of the upgrade (101 means it works) and the first bytes the VM's VNC server
sends (the RFB banner, e.g. "RFB 003.008"). The VM must be running with a display (vga std).
"""
import base64
import http.client
import json
import os
import socket
import ssl
import sys
import time
import urllib.parse

token = os.environ["TP"]
node, vmid = sys.argv[1], sys.argv[2]
delay = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
host = "127.0.0.1"

context = ssl.create_default_context()
context.check_hostname = False
context.verify_mode = ssl.CERT_NONE

connection = http.client.HTTPSConnection(host, 8006, context=context)
connection.request("POST", "/api2/json/nodes/%s/qemu/%s/vncproxy" % (node, vmid), body="websocket=1",
                   headers={"Authorization": "PVEAPIToken=" + token, "Content-Type": "application/x-www-form-urlencoded"})
response = connection.getresponse()
body = response.read()
print("C1 vncproxy: HTTP", response.status)
data = json.loads(body).get("data")
if not data:
    print("   no data:", body[:300])
    sys.exit(1)
print("   port:", data["port"], "| user:", data["user"], "| password given:", "password" in data, "| ticket length:", len(data["ticket"]))
started = time.time()
if delay:
    print("   waiting %.0f s before the websocket" % delay)
    time.sleep(delay)

path = "/api2/json/nodes/%s/qemu/%s/vncwebsocket?port=%s&vncticket=%s" % (node, vmid, data["port"], urllib.parse.quote(data["ticket"], safe=""))
raw = socket.create_connection((host, 8006), timeout=15)
tls = context.wrap_socket(raw, server_hostname=host)
key = base64.b64encode(os.urandom(16)).decode()
request = ("GET %s HTTP/1.1\r\nHost: %s:8006\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
           "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\nAuthorization: PVEAPIToken=%s\r\n\r\n") % (path, host, key, token)
tls.sendall(request.encode())
buffer = b""
while b"\r\n\r\n" not in buffer:
    chunk = tls.recv(4096)
    if not chunk:
        break
    buffer += chunk
head, _, rest = buffer.partition(b"\r\n\r\n")
print("C2 websocket upgrade after %.1f s: %s" % (time.time() - started, head.split(b"\r\n")[0].decode(errors="replace")))
if b" 101 " not in head.split(b"\r\n")[0]:
    print("   full answer:", head.decode(errors="replace")[:500])
    sys.exit(2)
def read_frame(buffer):
    """Reads one websocket frame from the TLS socket; returns (opcode, payload, remaining bytes)."""
    while len(buffer) < 2:
        buffer += tls.recv(4096)
    opcode = buffer[0] & 0x0F
    length = buffer[1] & 0x7F
    offset = 2
    if length == 126:
        while len(buffer) < 4:
            buffer += tls.recv(4096)
        length = int.from_bytes(buffer[2:4], "big")
        offset = 4
    elif length == 127:
        while len(buffer) < 10:
            buffer += tls.recv(4096)
        length = int.from_bytes(buffer[2:10], "big")
        offset = 10
    while len(buffer) < offset + length:
        buffer += tls.recv(4096)
    return opcode, buffer[offset:offset + length], buffer[offset + length:]


names = {1: "text", 2: "binary", 8: "close", 9: "ping", 10: "pong"}
opcode, payload, rest = read_frame(rest)
print("C3 first frame from the VM's VNC server: type %s (%d), %d bytes: %r" % (names.get(opcode, "other"), opcode, len(payload), payload[:20]))
if payload.startswith(b"RFB 003.008"):
    # answer like a viewer would, to see what comes next: the same version, then read the security types
    tls.sendall(bytes([0x82, 0x80 | len(payload)]) + b"\x00\x00\x00\x00" + bytes(b ^ 0 for b in payload))
    try:
        opcode, payload, rest = read_frame(rest)
        print("C4 second frame: type %s (%d), %d bytes: %r" % (names.get(opcode, "other"), opcode, len(payload), payload[:20]))
    except Exception as e:
        print("C4 no second frame:", e)
tls.close()

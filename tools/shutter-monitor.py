#!/usr/bin/env python3
"""Live monitor for the Shutter Remote trigger phone.

Listens for the app's UDP status datagrams and shows them in real time: the
countdown line is rewritten in place; alerts scroll above it. A gap of >5 s
between updates is flagged (possible stall / phone offline).

Enable "Send status to workstation" in the app, set this machine's LAN IP and
the matching port, then run:

    ./shutter-monitor.py [port]   # default 5005
"""
import socket
import sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 5005
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", port))
sock.settimeout(5.0)
print(f"Listening for Shutter status on UDP :{port} … (Ctrl-C to quit)")

while True:
    try:
        data, _ = sock.recvfrom(2048)
    except socket.timeout:
        sys.stdout.write("\r\033[K(no update for >5 s)")
        sys.stdout.flush()
        continue
    except KeyboardInterrupt:
        print()
        break
    kind, _, msg = data.decode("utf-8", "replace").partition("\t")
    if kind == "ALERT":
        sys.stdout.write("\r\033[K" + msg + "\n")   # permanent line
    else:
        sys.stdout.write("\r\033[K" + msg)          # rewrite in place
    sys.stdout.flush()

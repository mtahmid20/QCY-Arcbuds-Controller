#!/usr/bin/env python3
"""Serve the ArcBuds web UI.

On this machine, http://localhost is a secure context, so Web Bluetooth works
in Chrome/Edge directly. On a phone over the LAN, plain http:// is NOT a secure
context and Web Bluetooth is blocked -- see the printed instructions.
"""

from __future__ import annotations

import http.server
import socket
import socketserver
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "web"
PORT = 8770


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT), **kw)

    def log_message(self, fmt, *args):  # quieter
        sys.stderr.write(f"  {self.address_string()} {fmt % args}\n")


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main() -> None:
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
        ip = lan_ip()
        print(f"\n  ArcBuds control  ->  http://localhost:{PORT}")
        print(f"  from your phone  ->  http://{ip}:{PORT}\n")
        print("  Desktop Chrome/Edge: just open the localhost URL.")
        print("  Phone Chrome: http over LAN is not a secure context, so either")
        print("    1. host web/index.html on any HTTPS static site (GitHub Pages,")
        print("       Netlify Drop, Cloudflare Pages) -- it is a single file, or")
        print("    2. open chrome://flags/#unsafely-treat-insecure-origin-as-secure,")
        print(f"       add  http://{ip}:{PORT}  and set it to Enabled.\n")
        print("  ctrl-c to stop\n")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("  stopped")


if __name__ == "__main__":
    main()

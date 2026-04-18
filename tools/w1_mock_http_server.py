#!/usr/bin/env python3
"""Minimal HTTP mock of a W1-style recorder for Android emulator (host) testing.

Run on your Mac/PC:
  python3 tools/w1_mock_http_server.py

Emulator base URL (default in app): http://10.0.2.2:8765
Physical device on same LAN: http://<your-pc-lan-ip>:8765

Endpoints:
  GET /recordings  -> JSON array
  GET /recordings/{id}/content -> raw bytes
"""
from __future__ import annotations

import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

RECORDINGS = [
    {
        "id": "rec-live-1",
        "name": "sample.bin",
        "sizeBytes": 11,
        "sha256": None,
        "completedAtEpochMs": int(time.time() * 1000),
    }
]
CONTENT = {RECORDINGS[0]["id"]: b"hello-w1-real"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        print(fmt % args)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/recordings":
            body = json.dumps(RECORDINGS).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        prefix = "/recordings/"
        suffix = "/content"
        if self.path.startswith(prefix) and self.path.endswith(suffix):
            rid = self.path[len(prefix) : -len(suffix)]
            data = CONTENT.get(rid)
            if data is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)


if __name__ == "__main__":
    host = "0.0.0.0"
    port = 8765
    print(f"W1 mock listening on http://{host}:{port}")
    HTTPServer((host, port), Handler).serve_forever()

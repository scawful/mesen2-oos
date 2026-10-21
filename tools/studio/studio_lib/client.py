"""Minimal Mesen2 socket client (newline-delimited JSON over a Unix socket)."""

from __future__ import annotations

import json
import os
import socket
from typing import Any


class StudioError(RuntimeError):
    pass


def resolve_socket(socket_path: str | None, instance: str | None) -> str:
    """Explicit socket only: --socket, --instance NAME (/tmp/mesen2-NAME.sock) or MESEN2_SOCKET_PATH."""
    if socket_path:
        return socket_path
    if instance:
        return f"/tmp/mesen2-{instance}.sock"
    env = os.environ.get("MESEN2_SOCKET_PATH")
    if env:
        return env
    raise StudioError("No emulator socket: pass --socket PATH, --instance NAME or set MESEN2_SOCKET_PATH")


class MesenSocket:
    def __init__(self, path: str, timeout: float = 10.0):
        self.path = path
        self.timeout = timeout

    def request(self, command: str, **params: Any) -> Any:
        payload: dict[str, str] = {"type": command}
        for key, value in params.items():
            if value is None:
                continue
            if isinstance(value, bool):
                value = "true" if value else "false"
            payload[key] = str(value)

        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
                conn.settimeout(self.timeout)
                conn.connect(self.path)
                conn.sendall((json.dumps(payload) + "\n").encode())
                buffer = b""
                while b"\n" not in buffer:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    buffer += chunk
        except OSError as exc:
            raise StudioError(f"{command}: cannot reach {self.path}: {exc}") from exc

        line = buffer.split(b"\n", 1)[0].strip()
        if not line:
            raise StudioError(f"{command}: empty response from {self.path}")
        response = json.loads(line)
        if not response.get("success"):
            raise StudioError(f"{command}: {response.get('error', 'failed')}")
        return response.get("data")

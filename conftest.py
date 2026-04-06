"""Shared pytest fixtures for Mesen2 socket integration tests.

Uses canonical discovery from socket_discovery.discover_socket_path()
(env -> status files -> glob by mtime). Verifies socket with PING before use.
"""

import json
import socket

import pytest

from socket_discovery import discover_socket_path


def _send_ping(path: str, timeout: float = 5.0) -> bool:
    """Return True if socket at path responds to PING."""
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(timeout)
            s.connect(path)
            s.sendall(b'{"type":"PING"}\n')
            buf = b""
            while b"\n" not in buf:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
        if buf:
            data = json.loads(buf.decode().strip())
            return data.get("success") is True and data.get("data") == "PONG"
    except Exception:
        pass
    return False


@pytest.fixture(scope="session")
def socket_path():
    """Discover Mesen2 socket path; skip if not found or PING fails."""
    path = discover_socket_path(verify=_send_ping)
    if path is None:
        pytest.skip("No responsive Mesen2 socket found (set MESEN2_SOCKET_PATH or run Mesen2)")
    return path


@pytest.fixture(scope="session")
def sock(socket_path):
    """Connected socket to Mesen2; skip if discovery or PING fails."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(5.0)
    try:
        s.connect(socket_path)
        yield s
    finally:
        s.close()

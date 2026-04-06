"""Integration tests for Mesen2 Socket API - P2 Events.

Tests SUBSCRIBE and the status file written alongside the socket.

Requires the ``socket_path`` and ``sock`` fixtures from conftest.py.
"""

import json
import os
import time

import pytest


def send_command(sock, cmd):
    """Send a command dict over a connected socket and return the parsed response."""
    sock.sendall((json.dumps(cmd) + "\n").encode())
    buf = b""
    while b"\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    return json.loads(buf.decode().strip())


def test_subscribe_acknowledges(sock):
    """SUBSCRIBE to known events returns a success response."""
    resp = send_command(sock, {
        "type": "SUBSCRIBE",
        "events": "breakpoint_hit,frame_complete",
    })
    assert resp.get("success"), f"SUBSCRIBE failed: {resp.get('error')}"


def test_status_file_has_required_fields(socket_path):
    """Status file written next to the socket contains romHash and paused fields."""
    status_path = socket_path.replace(".sock", ".status")
    if not os.path.exists(status_path):
        pytest.skip(f"Status file not found: {status_path}")

    with open(status_path, "r") as f:
        try:
            data = json.load(f)
        except Exception as exc:
            pytest.fail(f"Could not parse status file: {exc}")

    assert "romHash" in data, "Status file missing 'romHash' field"
    assert "paused" in data, "Status file missing 'paused' field"


def test_frame_complete_event_received(socket_path):
    """frame_complete event is delivered within 5 seconds when emulator is running.

    This test opens its own short-lived socket so it can receive push events
    without racing against the shared ``sock`` fixture.  It is skipped if no
    status file indicates the emulator is currently running.
    """
    import socket as _socket

    # Check status file to determine whether the emulator is actually running.
    status_path = socket_path.replace(".sock", ".status")
    if os.path.exists(status_path):
        try:
            with open(status_path, "r") as f:
                status = json.load(f)
            if status.get("paused", True):
                pytest.skip("Emulator is paused; frame_complete events will not fire")
        except Exception:
            pass  # Proceed anyway; worst case the test times out.

    s = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
    s.settimeout(5.0)
    try:
        s.connect(socket_path)

        # Subscribe
        s.sendall((json.dumps({"type": "SUBSCRIBE", "events": "frame_complete"}) + "\n").encode())

        # Read subscription acknowledgement
        buf = b""
        while b"\n" not in buf:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk

        resp = json.loads(buf.decode().split("\n")[0].strip())
        assert resp.get("success"), f"SUBSCRIBE failed: {resp.get('error')}"

        # Wait for a frame_complete event
        deadline = time.time() + 5.0
        received = False
        leftover = b"\n".join(buf.split(b"\n")[1:])  # bytes after the first newline
        event_buf = leftover

        while time.time() < deadline:
            try:
                chunk = s.recv(4096)
                if not chunk:
                    break
                event_buf += chunk
            except _socket.timeout:
                break

            while b"\n" in event_buf:
                line, event_buf = event_buf.split(b"\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line.decode())
                    if msg.get("type") == "EVENT" and msg.get("event") == "frame_complete":
                        received = True
                        break
                except Exception:
                    continue
            if received:
                break

        assert received, "Did not receive frame_complete event within 5 seconds"
    finally:
        s.close()

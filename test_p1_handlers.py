"""Integration tests for Mesen2 Socket API - P1 Handlers.

Tests the P1 handlers:
- LOG_LEVEL: set/get log level
- STATE_DIFF: state caching and localized diffs
- WATCH_TRIGGER: conditional breakpoints/events

Requires the ``sock`` fixture from conftest.py (live Mesen2 instance).
"""

import json
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


def test_log_level(sock):
    """LOG_LEVEL get/set round-trips without error."""
    result = send_command(sock, {"type": "LOG_LEVEL", "action": "get"})
    assert result.get("success"), f"LOG_LEVEL get failed: {result.get('error')}"
    assert "level" in result["data"], "Missing level in response"
    original_level = result["data"]["level"]

    new_level = "debug" if original_level == "info" else "info"
    result = send_command(sock, {"type": "LOG_LEVEL", "action": "set", "level": new_level})
    assert result.get("success"), f"LOG_LEVEL set failed: {result.get('error')}"

    result = send_command(sock, {"type": "LOG_LEVEL", "action": "get"})
    assert result["data"]["level"] == new_level, (
        f"Level not set correctly. Expected {new_level}, got {result['data']['level']}"
    )

    # Restore original
    send_command(sock, {"type": "LOG_LEVEL", "action": "set", "level": original_level})


def test_state_diff_first_call_is_full(sock):
    """First STATE_DIFF call returns a full state with firstCall=true."""
    result = send_command(sock, {"type": "STATE_DIFF"})
    assert result.get("success"), f"STATE_DIFF initial failed: {result.get('error')}"
    data = result["data"]
    assert data.get("firstCall") is True, "Expected firstCall=true on first call"
    assert "frame" in data, "Missing frame in full state"


def test_state_diff_second_call_is_diff(sock):
    """Second STATE_DIFF call returns a diff with firstCall=false and a changes key."""
    # Prime the cache with a first call.
    send_command(sock, {"type": "STATE_DIFF"})
    time.sleep(0.1)

    result = send_command(sock, {"type": "STATE_DIFF"})
    assert result.get("success"), f"STATE_DIFF diff failed: {result.get('error')}"
    data = result["data"]
    assert data.get("firstCall") is False, "Expected firstCall=false on subsequent call"
    assert "changes" in data, "Missing changes object in diff response"


def test_watch_trigger_lifecycle(sock):
    """WATCH_TRIGGER add/list/check/remove completes without error."""
    addr = "0x7E0020"
    trigger_id = None
    try:
        result = send_command(sock, {
            "type": "WATCH_TRIGGER",
            "action": "add",
            "addr": addr,
            "value": "0",
            "condition": "eq",
        })
        assert result.get("success"), f"WATCH_TRIGGER add failed: {result.get('error')}"
        assert "id" in result["data"], "Missing id in response"
        trigger_id = result["data"]["id"]

        result = send_command(sock, {"type": "WATCH_TRIGGER", "action": "list"})
        assert result.get("success"), f"WATCH_TRIGGER list failed: {result.get('error')}"
        triggers = result["data"]["triggers"]
        found = any(t["id"] == trigger_id for t in triggers)
        assert found, "Trigger not found in list after add"

        matching = next(t for t in triggers if t["id"] == trigger_id)
        assert matching["addr"] == int(addr, 16), "Address mismatch in trigger list"
        assert matching["condition"] == "eq", "Condition mismatch in trigger list"

        result = send_command(sock, {"type": "WATCH_TRIGGER", "action": "check"})
        assert result.get("success"), f"WATCH_TRIGGER check failed: {result.get('error')}"
        assert "triggered" in result["data"], "Missing triggered field in check response"
    finally:
        if trigger_id is not None:
            result = send_command(sock, {
                "type": "WATCH_TRIGGER",
                "action": "remove",
                "trigger_id": str(trigger_id),
            })
            assert result.get("success"), f"WATCH_TRIGGER remove failed: {result.get('error')}"

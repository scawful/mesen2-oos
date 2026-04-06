"""Tests for Mesen2 debugger hooks (P register and memory write tracking)."""

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


def test_state_reports_frame(sock):
    """Emulator state is reachable and contains a frame counter."""
    state = send_command(sock, {"type": "STATE"})
    assert state.get("success"), f"STATE failed: {state.get('error')}"
    assert "frame" in state["data"], "Missing frame in STATE response"
    assert "fps" in state["data"], "Missing fps in STATE response"


def test_p_watch_start(sock):
    """P_WATCH start action acknowledges without error."""
    result = send_command(sock, {"type": "P_WATCH", "action": "start", "depth": "500"})
    assert result.get("success"), f"P_WATCH start failed: {result.get('error')}"
    assert result.get("data") is not None


def test_mem_watch_writes_add(sock):
    """MEM_WATCH_WRITES add returns a watch_id for each registered address."""
    watches = [
        ("0x7E0022", 2),
        ("0x7E0020", 2),
        ("0x7E0116", 2),
    ]
    for addr, size in watches:
        result = send_command(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": addr,
            "size": str(size),
            "depth": "50",
        })
        assert result.get("success"), f"MEM_WATCH_WRITES add {addr} failed: {result.get('error')}"
        assert "watch_id" in result["data"], f"Missing watch_id for {addr}"


def test_p_log_returns_entries(sock):
    """P_LOG returns a log structure with total and entries fields."""
    # Start the watcher so there is something to query even if count is 0.
    send_command(sock, {"type": "P_WATCH", "action": "start", "depth": "500"})

    result = send_command(sock, {"type": "P_LOG", "count": "20"})
    assert result.get("success"), f"P_LOG failed: {result.get('error')}"
    assert "total" in result["data"], "Missing total in P_LOG response"
    assert "entries" in result["data"], "Missing entries in P_LOG response"


def test_mem_watch_writes_list(sock):
    """MEM_WATCH_WRITES list returns a watches array."""
    # Add at least one watch so the list is non-trivial.
    send_command(sock, {
        "type": "MEM_WATCH_WRITES",
        "action": "add",
        "addr": "0x7E0022",
        "size": "2",
        "depth": "50",
    })

    result = send_command(sock, {"type": "MEM_WATCH_WRITES", "action": "list"})
    assert result.get("success"), f"MEM_WATCH_WRITES list failed: {result.get('error')}"
    assert "watches" in result["data"], "Missing watches in list response"
    assert isinstance(result["data"]["watches"], list)


def test_mem_blame_returns_writes(sock):
    """MEM_BLAME returns writes/count fields for a watched address."""
    send_command(sock, {
        "type": "MEM_WATCH_WRITES",
        "action": "add",
        "addr": "0x7E0022",
        "size": "2",
        "depth": "50",
    })

    blame = send_command(sock, {"type": "MEM_BLAME", "addr": "0x7E0022"})
    assert blame.get("success"), f"MEM_BLAME failed: {blame.get('error')}"
    assert "writes" in blame["data"], "Missing writes in MEM_BLAME response"

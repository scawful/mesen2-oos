"""Integration tests for Mesen2 Socket API - Phase 1-3 features.

Tests commands added in the agentic debugging improvements:
- Phase 1: READBLOCK_BINARY, HELP
- Phase 2: Enhanced SUBSCRIBE
- Phase 3: GAMESTATE, SPRITES

Requires the ``sock`` fixture from conftest.py (live Mesen2 instance with ROM loaded).
"""

import base64
import json

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


@pytest.fixture(scope="module")
def rom_loaded(sock):
    """Skip module tests that require an active ROM if none is loaded."""
    state = send_command(sock, {"type": "STATE"})
    if not state.get("success") and "No ROM" in state.get("error", ""):
        pytest.skip("No ROM loaded in Mesen2")
    return True


@pytest.fixture(scope="module")
def debugger_ready(sock, rom_loaded):
    """Skip debugger-dependent tests when debugger is unavailable."""
    cpu = send_command(sock, {"type": "CPU"})
    if not cpu.get("success") and "Debugger not available" in cpu.get("error", ""):
        pytest.skip("Debugger not available in current Mesen2 instance")
    return True


def test_help_list(sock):
    """HELP lists all commands, including GAMESTATE and SPRITES."""
    result = send_command(sock, {"type": "HELP"})
    assert result.get("success"), f"HELP failed: {result.get('error')}"
    data = result["data"]
    assert "commands" in data, "Missing commands list"
    assert "version" in data, "Missing version"
    assert len(data["commands"]) >= 40, f"Expected 40+ commands, got {len(data['commands'])}"
    assert "GAMESTATE" in data["commands"], "GAMESTATE not in command list"
    assert "SPRITES" in data["commands"], "SPRITES not in command list"


def test_help_specific(sock):
    """HELP with a specific command returns description, params, and example."""
    result = send_command(sock, {"type": "HELP", "command": "BREAKPOINT"})
    assert result.get("success"), f"HELP failed: {result.get('error')}"
    data = result["data"]
    assert "command" in data, "Missing command field"
    assert "description" in data, "Missing description"
    assert "params" in data, "Missing params"
    assert "example" in data, "Missing example"


def test_readblock_binary(sock, debugger_ready):
    """READBLOCK_BINARY returns valid base64 of the requested size."""
    result = send_command(sock, {"type": "READBLOCK_BINARY", "addr": "0x7E0000", "size": "256"})
    assert result.get("success"), f"READBLOCK_BINARY failed: {result.get('error')}"
    data = result["data"]
    assert "bytes" in data, "Missing bytes field"
    assert "size" in data, "Missing size field"
    assert data["size"] == 256, f"Expected size 256, got {data['size']}"

    decoded = base64.b64decode(data["bytes"])
    assert len(decoded) == 256, f"Decoded length {len(decoded)} != 256"


def test_savestate_label(sock, rom_loaded):
    """SAVESTATE_LABEL set/get/clear round-trips correctly."""
    label = "agent-label-test"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "set", "slot": "1", "label": label})
    assert result.get("success"), f"SAVESTATE_LABEL set failed: {result.get('error')}"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "get", "slot": "1"})
    assert result.get("success"), f"SAVESTATE_LABEL get failed: {result.get('error')}"
    assert result["data"].get("label") == label, (
        f"Expected label '{label}', got '{result['data'].get('label')}'"
    )

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "clear", "slot": "1"})
    assert result.get("success"), f"SAVESTATE_LABEL clear failed: {result.get('error')}"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "get", "slot": "1"})
    assert result.get("success"), f"SAVESTATE_LABEL get after clear failed: {result.get('error')}"
    assert result["data"].get("label") in (None, ""), "Expected cleared label to be empty"


def test_subscribe_list(sock):
    """SUBSCRIBE list returns available event types including breakpoint_hit and all."""
    result = send_command(sock, {"type": "SUBSCRIBE", "action": "list"})
    assert result.get("success"), f"SUBSCRIBE list failed: {result.get('error')}"
    data = result["data"]
    assert "available_events" in data, "Missing available_events"
    events = data["available_events"]
    assert "breakpoint_hit" in events, "Missing breakpoint_hit event"
    assert "frame_complete" in events, "Missing frame_complete event"
    assert "all" in events, "Missing 'all' event"


def test_gamestate(sock, rom_loaded):
    """GAMESTATE returns link, health, items, and game sections with expected fields."""
    result = send_command(sock, {"type": "GAMESTATE"})
    assert result.get("success"), f"GAMESTATE failed: {result.get('error')}"
    data = result["data"]

    assert "link" in data, "Missing link section"
    assert "health" in data, "Missing health section"
    assert "items" in data, "Missing items section"
    assert "game" in data, "Missing game section"

    link = data["link"]
    assert "x" in link, "Missing link.x"
    assert "y" in link, "Missing link.y"
    assert "direction" in link, "Missing link.direction"

    health = data["health"]
    assert "current" in health, "Missing health.current"
    assert "hearts" in health, "Missing health.hearts"


def test_sprites(sock, rom_loaded):
    """SPRITES returns count and sprites array; active sprites have required fields."""
    result = send_command(sock, {"type": "SPRITES"})
    assert result.get("success"), f"SPRITES failed: {result.get('error')}"
    data = result["data"]

    assert "count" in data, "Missing count"
    assert "sprites" in data, "Missing sprites array"

    if data["count"] > 0:
        sprite = data["sprites"][0]
        assert "slot" in sprite, "Missing sprite.slot"
        assert "type" in sprite, "Missing sprite.type"
        assert "x" in sprite, "Missing sprite.x"
        assert "y" in sprite, "Missing sprite.y"


def test_sprites_all(sock, rom_loaded):
    """SPRITES with all=true returns a non-negative count."""
    result = send_command(sock, {"type": "SPRITES", "all": "true"})
    assert result.get("success"), f"SPRITES all failed: {result.get('error')}"
    assert result["data"]["count"] >= 0, "Invalid count"

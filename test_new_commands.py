#!/usr/bin/env python3
"""Integration tests for Mesen2 Socket API - Phase 1-3 features.

Tests the new commands added in the agentic debugging improvements:
- Phase 1: READBLOCK_BINARY, HELP
- Phase 2: Enhanced SUBSCRIBE
- Phase 3: GAMESTATE, SPRITES

Run with Mesen2 running and ALTTP/Oracle ROM loaded:
    python3 test_new_commands.py
"""

import socket
import json
import sys
import glob
import base64

def find_socket():
    """Find the active Mesen2 socket."""
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if not sockets:
        print("Error: No Mesen2 socket found. Is Mesen running?")
        sys.exit(1)
    return sorted(sockets, key=lambda x: -int(x.split('-')[1].split('.')[0]))[0]

def send_command(sock_path, cmd):
    """Send a command and receive response."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(sock_path)
    s.sendall((json.dumps(cmd) + "\n").encode())
    s.settimeout(2.0)
    response = b""
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            response += chunk
            if b"\n" in response:
                break
    except socket.timeout:
        pass
    s.close()
    return json.loads(response.decode().strip())

def test_help_list(sock):
    """Test HELP command - list all commands."""
    print("Testing HELP (list all)... ", end="")
    result = send_command(sock, {"type": "HELP"})
    assert result.get("success"), f"HELP failed: {result.get('error')}"
    data = result["data"]
    assert "commands" in data, "Missing commands list"
    assert "version" in data, "Missing version"
    assert len(data["commands"]) >= 40, f"Expected 40+ commands, got {len(data['commands'])}"
    assert "GAMESTATE" in data["commands"], "GAMESTATE not in command list"
    assert "SPRITES" in data["commands"], "SPRITES not in command list"
    print(f"PASSED ({len(data['commands'])} commands)")

def test_help_specific(sock):
    """Test HELP command - specific command details."""
    print("Testing HELP (specific)... ", end="")
    result = send_command(sock, {"type": "HELP", "command": "BREAKPOINT"})
    assert result.get("success"), f"HELP failed: {result.get('error')}"
    data = result["data"]
    assert "command" in data, "Missing command field"
    assert "description" in data, "Missing description"
    assert "params" in data, "Missing params"
    assert "example" in data, "Missing example"
    print("PASSED")

def test_readblock_binary(sock):
    """Test READBLOCK_BINARY command."""
    print("Testing READBLOCK_BINARY... ", end="")
    result = send_command(sock, {"type": "READBLOCK_BINARY", "addr": "0x7E0000", "size": "256"})
    assert result.get("success"), f"READBLOCK_BINARY failed: {result.get('error')}"
    data = result["data"]
    assert "bytes" in data, "Missing bytes field"
    assert "size" in data, "Missing size field"
    assert data["size"] == 256, f"Expected size 256, got {data['size']}"
    
    # Verify base64 is valid
    decoded = base64.b64decode(data["bytes"])
    assert len(decoded) == 256, f"Decoded length {len(decoded)} != 256"
    print(f"PASSED (base64 len={len(data['bytes'])}, decoded={len(decoded)} bytes)")

def test_savestate_label(sock):
    """Test SAVESTATE_LABEL command."""
    print("Testing SAVESTATE_LABEL... ", end="")
    label = "agent-label-test"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "set", "slot": "1", "label": label})
    assert result.get("success"), f"SAVESTATE_LABEL set failed: {result.get('error')}"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "get", "slot": "1"})
    assert result.get("success"), f"SAVESTATE_LABEL get failed: {result.get('error')}"
    data = result["data"]
    assert data.get("label") == label, f"Expected label '{label}', got '{data.get('label')}'"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "clear", "slot": "1"})
    assert result.get("success"), f"SAVESTATE_LABEL clear failed: {result.get('error')}"

    result = send_command(sock, {"type": "SAVESTATE_LABEL", "action": "get", "slot": "1"})
    assert result.get("success"), f"SAVESTATE_LABEL get after clear failed: {result.get('error')}"
    data = result["data"]
    assert data.get("label") in (None, ""), "Expected cleared label"

    print("PASSED")

def test_subscribe_list(sock):
    """Test SUBSCRIBE list action."""
    print("Testing SUBSCRIBE (list)... ", end="")
    result = send_command(sock, {"type": "SUBSCRIBE", "action": "list"})
    assert result.get("success"), f"SUBSCRIBE list failed: {result.get('error')}"
    data = result["data"]
    assert "available_events" in data, "Missing available_events"
    events = data["available_events"]
    assert "breakpoint_hit" in events, "Missing breakpoint_hit event"
    assert "frame_complete" in events, "Missing frame_complete event"
    assert "all" in events, "Missing 'all' event"
    print(f"PASSED ({len(events)} event types)")

def test_gamestate(sock):
    """Test GAMESTATE command."""
    print("Testing GAMESTATE... ", end="")
    result = send_command(sock, {"type": "GAMESTATE"})
    assert result.get("success"), f"GAMESTATE failed: {result.get('error')}"
    data = result["data"]
    
    # Verify structure
    assert "link" in data, "Missing link section"
    assert "health" in data, "Missing health section"
    assert "items" in data, "Missing items section"
    assert "game" in data, "Missing game section"
    
    # Verify link fields
    link = data["link"]
    assert "x" in link, "Missing link.x"
    assert "y" in link, "Missing link.y"
    assert "direction" in link, "Missing link.direction"
    
    # Verify health fields
    health = data["health"]
    assert "current" in health, "Missing health.current"
    assert "hearts" in health, "Missing health.hearts"
    
    print(f"PASSED (Link at {link['x']},{link['y']}, {health['hearts']} hearts)")

def test_sprites(sock):
    """Test SPRITES command."""
    print("Testing SPRITES... ", end="")
    result = send_command(sock, {"type": "SPRITES"})
    assert result.get("success"), f"SPRITES failed: {result.get('error')}"
    data = result["data"]
    
    assert "count" in data, "Missing count"
    assert "sprites" in data, "Missing sprites array"
    
    # Verify sprite structure if any are active
    if data["count"] > 0:
        sprite = data["sprites"][0]
        assert "slot" in sprite, "Missing sprite.slot"
        assert "type" in sprite, "Missing sprite.type"
        assert "x" in sprite, "Missing sprite.x"
        assert "y" in sprite, "Missing sprite.y"
    
    print(f"PASSED ({data['count']} active sprites)")

def test_sprites_all(sock):
    """Test SPRITES command with all=true."""
    print("Testing SPRITES (all)... ", end="")
    result = send_command(sock, {"type": "SPRITES", "all": "true"})
    assert result.get("success"), f"SPRITES all failed: {result.get('error')}"
    data = result["data"]
    
    # With all=true, we should get all 16 slots
    assert data["count"] >= 0, "Invalid count"
    print(f"PASSED ({data['count']} sprites including inactive)")

def main():
    sock = find_socket()
    print(f"Using socket: {sock}\n")

    # Check if ROM is loaded
    state = send_command(sock, {"type": "STATE"})
    if not state.get("success"):
        print("Error: Could not get state. Is emulation running?")
        sys.exit(1)
    
    print(f"Emulation: frame={state['data']['frame']}, fps={state['data']['fps']:.1f}\n")
    print("=" * 50)

    # Run tests
    tests = [
        test_help_list,
        test_help_specific,
        test_readblock_binary,
        test_savestate_label,
        test_subscribe_list,
        test_gamestate,
        test_sprites,
        test_sprites_all,
    ]

    passed = 0
    failed = 0
    for test in tests:
        try:
            test(sock)
            passed += 1
        except AssertionError as e:
            print(f"FAILED: {e}")
            failed += 1
        except Exception as e:
            print(f"ERROR: {e}")
            failed += 1

    print("=" * 50)
    print(f"\nResults: {passed} passed, {failed} failed")
    
    return 0 if failed == 0 else 1

if __name__ == "__main__":
    sys.exit(main())

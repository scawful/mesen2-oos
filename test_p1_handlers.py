#!/usr/bin/env python3
"""Integration tests for Mesen2 Socket API - P1 Handlers.

Tests the new P1 handlers:
- LOG_LEVEL: set/get log level
- STATE_DIFF: state caching and localized diffs
- WATCH_TRIGGER: conditional breakpoints/events

Run with Mesen2 running and a ROM loaded:
    python3 test_p1_handlers.py
"""

import socket
import json
import sys
import glob
import time

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

def test_log_level(sock):
    """Test LOG_LEVEL command."""
    print("Testing LOG_LEVEL... ", end="")
    
    # Test GET
    result = send_command(sock, {"type": "LOG_LEVEL", "action": "get"})
    assert result.get("success"), f"LOG_LEVEL get failed: {result.get('error')}"
    assert "level" in result["data"], "Missing level in response"
    original_level = result["data"]["level"]
    
    # Test SET
    new_level = "debug" if original_level == "info" else "info"
    result = send_command(sock, {"type": "LOG_LEVEL", "action": "set", "level": new_level})
    assert result.get("success"), f"LOG_LEVEL set failed: {result.get('error')}"
    
    # Verify SET
    result = send_command(sock, {"type": "LOG_LEVEL", "action": "get"})
    assert result["data"]["level"] == new_level, f"Level not set correctly. Expected {new_level}, got {result['data']['level']}"
    
    # Restore original
    send_command(sock, {"type": "LOG_LEVEL", "action": "set", "level": original_level})
    print(f"PASSED (get/set {original_level}->{new_level}->{original_level})")

def test_state_diff(sock):
    """Test STATE_DIFF command."""
    print("Testing STATE_DIFF... ", end="")
    
    # First call - full state
    result = send_command(sock, {"type": "STATE_DIFF"})
    assert result.get("success"), f"STATE_DIFF initial failed: {result.get('error')}"
    data = result["data"]
    assert data.get("firstCall") is True, "Expected firstCall=true"
    assert "frame" in data, "Missing frame in full state"
    
    # Wait a bit or ensure state changes (e.g. frame count increases if running)
    time.sleep(0.1)
    
    # Second call - diff
    result = send_command(sock, {"type": "STATE_DIFF"})
    assert result.get("success"), f"STATE_DIFF diff failed: {result.get('error')}"
    data = result["data"]
    assert data.get("firstCall") is False, "Expected firstCall=false"
    assert "changes" in data, "Missing changes object"
    # Note: changes might be empty if paused and nothing changed, but usually frame time or something might change if not paused or if FPS fluctuates?
    # Actually FPS is in state, so it might change.
    
    print(f"PASSED (full then diff)")

def test_watch_trigger(sock):
    """Test WATCH_TRIGGER command."""
    print("Testing WATCH_TRIGGER... ", end="")
    
    # Use a safe address around 7E0000 (WRAM)
    addr = "0x7E0020"
    trigger_id = None
    
    try:
        # ADD
        result = send_command(sock, {
            "type": "WATCH_TRIGGER", 
            "action": "add", 
            "addr": addr, 
            "value": "0", 
            "condition": "eq"
        })
        assert result.get("success"), f"WATCH_TRIGGER add failed: {result.get('error')}"
        assert "id" in result["data"], "Missing id in response"
        trigger_id = result["data"]["id"]
        
        # LIST
        result = send_command(sock, {"type": "WATCH_TRIGGER", "action": "list"})
        assert result.get("success"), f"WATCH_TRIGGER list failed: {result.get('error')}"
        triggers = result["data"]["triggers"]
        found = False
        for t in triggers:
            if t["id"] == trigger_id:
                found = True
                assert t["addr"] == int(addr, 16), "Address mismatch"
                assert t["condition"] == "eq", "Condition mismatch"
                break
        assert found, "Trigger not found in list"
        
        # CHECK
        result = send_command(sock, {"type": "WATCH_TRIGGER", "action": "check"})
        assert result.get("success"), f"WATCH_TRIGGER check failed: {result.get('error')}"
        triggered = result["data"]["triggered"]
        # Might trigger or not depending on memory value
        
    finally:
        # REMOVE
        if trigger_id is not None:
            result = send_command(sock, {"type": "WATCH_TRIGGER", "action": "remove", "trigger_id": str(trigger_id)})
            assert result.get("success"), f"WATCH_TRIGGER remove failed: {result.get('error')}"
            
    print("PASSED (add/list/check/remove)")

def main():
    sock = find_socket()
    print(f"Using socket: {sock}\n")
    
    # Ensure emulator is running/accessible
    state = send_command(sock, {"type": "STATE"})
    if not state.get("success"):
        print("Error: Could not get state")
        sys.exit(1)
        
    tests = [
        test_log_level,
        test_state_diff,
        test_watch_trigger
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
            
    print(f"\nResults: {passed} passed, {failed} failed")
    return 0 if failed == 0 else 1

if __name__ == "__main__":
    sys.exit(main())

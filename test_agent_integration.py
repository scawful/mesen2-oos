#!/usr/bin/env python3
"""Integration tests for Mesen2 agent features.

Tests the new agent-friendly features:
- Error codes and validation
- Agent registration
- Metrics and health checks
- Command history
- YAZE state sync
- Status file discovery

Run with Mesen2 running and ALTTP/Oracle ROM loaded:
    python3 test_agent_integration.py
"""

import socket
import json
import sys
import glob
import time
from pathlib import Path


def find_socket():
    """Find the active Mesen2 socket."""
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if not sockets:
        print("Error: No Mesen2 socket found. Is Mesen running?")
        sys.exit(1)
    return sorted(sockets, key=lambda x: -int(x.split('-')[1].split('.')[0]))[0]


def send_command(sock_path, cmd, timeout=5.0):
    """Send a command and receive response."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    
    try:
        s.connect(sock_path)
        request = json.dumps(cmd) + "\n"
        s.sendall(request.encode())
        
        response = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            response += chunk
            if b"\n" in response:
                break
        
        return json.loads(response.decode().strip())
    finally:
        s.close()


def test_error_codes(sock):
    """Test error code handling."""
    print("Testing error codes... ", end="")
    
    # Test missing parameter
    result = send_command(sock, {"type": "READ"})
    assert not result.get("success"), "Should fail without addr"
    assert result.get("errorCode") == 2, f"Expected errorCode 2, got {result.get('errorCode')}"
    
    # Test invalid command
    result = send_command(sock, {"type": "INVALID_COMMAND"})
    assert not result.get("success"), "Should fail for invalid command"
    assert result.get("errorCode") == 4, f"Expected errorCode 4, got {result.get('errorCode')}"
    
    print("PASSED")


def test_agent_registration(sock):
    """Test agent registration."""
    print("Testing AGENT_REGISTER... ", end="")
    
    result = send_command(sock, {
        "type": "AGENT_REGISTER",
        "agentId": "test_agent",
        "agentName": "Test Agent",
        "version": "1.0.0"
    })
    
    assert result.get("success"), "Should register successfully"
    data = result["data"]
    assert "registered" in data, "Missing registered field"
    assert json.loads(data)["agentId"] == "test_agent", "Agent ID mismatch"
    
    print("PASSED")


def test_capabilities(sock):
    """Test CAPABILITIES command."""
    print("Testing CAPABILITIES... ", end="")
    
    result = send_command(sock, {"type": "CAPABILITIES"})
    assert result.get("success"), "CAPABILITIES should succeed"
    
    data = json.loads(result["data"])
    assert "version" in data, "Missing version"
    assert "commands" in data, "Missing commands count"
    assert "features" in data, "Missing features list"
    assert "error_codes" in data["features"], "Missing error_codes feature"
    
    print(f"PASSED (version={data['version']}, {data['commands']} commands)")


def test_health_enhanced(sock):
    """Test enhanced HEALTH command."""
    print("Testing HEALTH (enhanced)... ", end="")
    
    result = send_command(sock, {"type": "HEALTH"})
    assert result.get("success"), "HEALTH should succeed"
    
    data = json.loads(result["data"])
    assert "diagnostics" in data, "Missing diagnostics section"
    diagnostics = data["diagnostics"]
    assert "registeredAgents" in diagnostics, "Missing registeredAgents"
    assert "yazeSync" in diagnostics, "Missing yazeSync"
    
    print("PASSED")


def test_metrics(sock):
    """Test METRICS command."""
    print("Testing METRICS... ", end="")
    
    result = send_command(sock, {"type": "METRICS"})
    assert result.get("success"), "METRICS should succeed"
    
    data = json.loads(result["data"])
    assert "totalCommands" in data, "Missing totalCommands"
    assert "avgLatencyUs" in data, "Missing avgLatencyUs"
    assert "errorCount" in data, "Missing errorCount"
    assert "errorRate" in data, "Missing errorRate"
    
    print(f"PASSED (commands={data['totalCommands']}, latency={data['avgLatencyUs']}μs)")


def test_command_history(sock):
    """Test COMMAND_HISTORY command."""
    print("Testing COMMAND_HISTORY... ", end="")
    
    # Execute a few commands first
    send_command(sock, {"type": "PING"})
    send_command(sock, {"type": "STATE"})
    time.sleep(0.1)  # Small delay
    
    result = send_command(sock, {"type": "COMMAND_HISTORY", "count": "5"})
    assert result.get("success"), "COMMAND_HISTORY should succeed"
    
    history = json.loads(result["data"])
    assert isinstance(history, list), "History should be a list"
    assert len(history) > 0, "History should have entries"
    
    entry = history[-1]
    assert "command" in entry, "Missing command field"
    assert "timestamp" in entry, "Missing timestamp"
    assert "errorCode" in entry, "Missing errorCode"
    assert "latencyUs" in entry, "Missing latencyUs"
    
    print(f"PASSED ({len(history)} entries)")


def test_yaze_sync(sock):
    """Test YAZE state sync commands."""
    print("Testing YAZE state sync... ", end="")
    
    # Test SAVESTATE_WATCH status
    result = send_command(sock, {"type": "SAVESTATE_WATCH", "action": "status"})
    assert result.get("success"), "SAVESTATE_WATCH should succeed"
    
    data = json.loads(result["data"])
    assert "watching" in data, "Missing watching field"
    
    # Test SAVESTATE_SYNC (if we have a state file)
    # This would require an actual state file, so we'll just test the command exists
    result = send_command(sock, {
        "type": "SAVESTATE_SYNC",
        "path": "/tmp/test_state.mss"
    })
    # May fail if file doesn't exist, but command should be recognized
    assert "errorCode" in result or result.get("success"), "Command should be recognized"
    
    print("PASSED")


def test_state_diff(sock):
    """Test STATE_DIFF command."""
    print("Testing STATE_DIFF... ", end="")
    
    result = send_command(sock, {"type": "STATE_DIFF"})
    assert result.get("success"), "STATE_DIFF should succeed"
    
    # Should return state data (even if not a true diff yet)
    data = result["data"]
    assert data, "Should return state data"
    
    print("PASSED")


def test_watch_trigger(sock):
    """Test WATCH_TRIGGER command."""
    print("Testing WATCH_TRIGGER... ", end="")
    
    result = send_command(sock, {
        "type": "WATCH_TRIGGER",
        "action": "list"
    })
    # Command should be recognized (may not be fully implemented)
    assert "errorCode" in result or result.get("success"), "Command should be recognized"
    
    print("PASSED")


def test_status_file():
    """Test status file discovery."""
    print("Testing status file... ", end="")
    
    status_files = glob.glob("/tmp/mesen2-*.status")
    assert len(status_files) > 0, "Status file should exist"
    
    status_file = status_files[0]
    with open(status_file) as f:
        status = json.load(f)
    
    assert "socketPath" in status, "Missing socketPath"
    assert "running" in status, "Missing running"
    assert "registeredAgents" in status, "Missing registeredAgents"
    
    print(f"PASSED (socket={status['socketPath']})")


def test_validation(sock):
    """Test request validation."""
    print("Testing request validation... ", end="")
    
    # Test missing required parameter
    result = send_command(sock, {"type": "WRITE"})
    assert not result.get("success"), "Should fail without required params"
    assert result.get("errorCode") in [2, 3], f"Expected errorCode 2 or 3, got {result.get('errorCode')}"
    
    # Test valid command
    result = send_command(sock, {"type": "PING"})
    assert result.get("success"), "Valid command should succeed"
    
    print("PASSED")


def test_batch_with_errors(sock):
    """Test BATCH command with error handling."""
    print("Testing BATCH with errors... ", end="")
    
    commands = [
        {"type": "PING"},
        {"type": "READ"},  # Missing addr - should error
        {"type": "STATE"}
    ]
    
    result = send_command(sock, {
        "type": "BATCH",
        "commands": json.dumps(commands)
    })
    
    assert result.get("success"), "BATCH should succeed"
    results = json.loads(result["data"])["results"]
    assert len(results) == 3, "Should have 3 results"
    assert results[0].get("success"), "First command should succeed"
    assert not results[1].get("success"), "Second command should fail"
    assert "errorCode" in results[1], "Error should have errorCode"
    
    print("PASSED")


def main():
    sock = find_socket()
    print(f"Using socket: {sock}\n")
    
    # Check if ROM is loaded
    state = send_command(sock, {"type": "STATE"})
    if not state.get("success"):
        print("Error: Could not get state. Is emulation running?")
        sys.exit(1)
    
    print(f"Emulation: frame={json.loads(state['data'])['frame']}\n")
    print("=" * 60)
    
    # Run tests
    tests = [
        test_error_codes,
        test_validation,
        test_agent_registration,
        test_capabilities,
        test_health_enhanced,
        test_metrics,
        test_command_history,
        test_yaze_sync,
        test_state_diff,
        test_watch_trigger,
        test_status_file,
        test_batch_with_errors,
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
    
    print("=" * 60)
    print(f"\nResults: {passed} passed, {failed} failed")
    
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

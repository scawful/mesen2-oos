import base64
import glob
import json
import os
import tempfile
import time

import pytest

# Socket path and sock fixtures come from conftest.py (canonical discovery).


def send_command(sock, cmd_type, **params):
    """Helper to send command and return data."""
    cmd = {"type": cmd_type}
    cmd.update(params)
    request = json.dumps(cmd) + "\n"
    sock.sendall(request.encode())
    
    response = b""
    while True:
        chunk = sock.recv(4096)
        if not chunk:
            break
        response += chunk
        if b"\n" in response:
            break
            
    result = json.loads(response.decode().strip())
    # Allow caller to handle success/failure, but return whole object
    return result

# --- Core Tests ---

def test_ping(sock):
    res = send_command(sock, "PING")
    assert res["success"]
    assert res["data"] == "PONG"

def test_state(sock):
    res = send_command(sock, "STATE")
    assert res["success"]
    data = res["data"]
    assert "running" in data
    assert "frame" in data
    assert isinstance(data["frame"], int)
    assert "lastSave" in data
    assert "lastLoad" in data
    if data["lastSave"] is not None:
        assert "success" in data["lastSave"]
    if data["lastLoad"] is not None:
        assert "success" in data["lastLoad"]

def test_health(sock):
    res = send_command(sock, "HEALTH")
    assert res["success"]
    data = res["data"]
    # Check for enhanced health fields
    assert "running" in data
    if "diagnostics" in data:
        assert "registeredAgents" in data["diagnostics"]

# --- Control Tests ---

def test_pause_resume(sock):
    # Pause
    res = send_command(sock, "PAUSE")
    assert res["success"]
    
    # Wait for state to update
    for _ in range(10):
        state = send_command(sock, "STATE")
        if state["data"]["paused"] is True:
            break
        time.sleep(0.05)
    else:
        pytest.fail("State did not update to paused")
    
    # Frame advance while paused
    res = send_command(sock, "FRAME")
    assert res["success"]
    
    # Resume
    res = send_command(sock, "RESUME")
    assert res["success"]
    
    # Wait for state to update
    for _ in range(10):
        state = send_command(sock, "STATE")
        if state["data"]["paused"] is False:
            break
        time.sleep(0.05)
    else:
        pytest.fail("State did not update to running")

def test_savestate_pause_param(sock):
    res = send_command(sock, "SAVESTATE", slot="1", pause="true")
    assert res["success"]
    res = send_command(sock, "LOADSTATE", slot="1", pause="true")
    assert res["success"]

def test_step(sock):
    send_command(sock, "PAUSE")
    try:
        cpu_before = send_command(sock, "CPU")["data"]
        cycles_before = cpu_before["cycles"]
        
        # Step 100 instructions to be absolutely sure cycles move
        res = send_command(sock, "STEP", count="100")
        assert res["success"]
        
        # Wait a bit for emulator state to settle if needed
        time.sleep(0.1)
        
        cpu_after = send_command(sock, "CPU")["data"]
        cycles_after = cpu_after["cycles"]
        
        assert cycles_after > cycles_before
    finally:
        send_command(sock, "RESUME")

# ...

def test_search(sock):
    # Write a unique pattern
    addr = "0x7E0100"
    send_command(sock, "WRITE", addr=addr, value="0xDE")
    send_command(sock, "WRITE", addr="0x7E0101", value="0xAD")
    
    # Verify write
    check = send_command(sock, "READBLOCK", addr=addr, len="2")
    val = check["data"].replace('"', '').replace('0x', '')
    assert "dead" in val.lower()
    
    # Use SnesMemory to support absolute SNES addresses
    res = send_command(sock, "SEARCH", pattern="DE AD", memtype="SnesMemory", start="0x7E0000", end="0x7E0200")
    assert res["success"]
    matches = res["data"]["matches"]
    
    target = 0x7E0100
    found = False
    for m in matches:
        if isinstance(m, str):
            val = int(m.replace('"', '').replace("0x", ""), 16)
        else:
            val = m
            
        if val == target:
            found = True
            break
            
    if not found:
        print(f"DEBUG: Search matches: {matches}")
        
    assert found

# --- Discovery Tests ---

def test_capabilities(sock):
    res = send_command(sock, "CAPABILITIES")
    assert res["success"]
    data = res["data"]
    if isinstance(data, str):
        data = json.loads(data)
    assert "version" in data
    assert "features" in data

def test_help_list(sock):
    res = send_command(sock, "HELP")
    assert res["success"]
    data = res["data"]
    if isinstance(data, str):
        data = json.loads(data)
    assert "commands" in data
    assert len(data["commands"]) > 30


# --- ALTTP Specifics ---

def test_gamestate(sock):
    # This might fail if not playing Zelda 3, so we check error code or success
    res = send_command(sock, "GAMESTATE")
    if res["success"]:
        data = res["data"]
        # If success, must have structure
        if "link" in data:
            assert "x" in data["link"]
            assert "y" in data["link"]
    else:
        # If failed, acceptable if game specific
        pass

def test_stateinspect_includes_gamestate(sock):
    res = send_command(sock, "STATEINSPECT", includeGameState="true")
    assert res["success"]
    data = res["data"]
    assert "watchEntries" in data
    if "gameState" in data:
        assert isinstance(data["gameState"], dict)

def test_sprites(sock):
    res = send_command(sock, "SPRITES")
    if res["success"]:
        data = res["data"]
        assert "sprites" in data
        assert isinstance(data["sprites"], list)

# --- Agent Features ---

def test_agent_register(sock):
    res = send_command(sock, "AGENT_REGISTER", agentId="pytest_runner", agentName="Pytest", version="2.0")
    assert res["success"]
    assert res["data"]["registered"] is True

def test_metrics(sock):
    res = send_command(sock, "METRICS")
    assert res["success"]
    assert "avgLatencyUs" in res["data"]

def test_batch(sock):
    cmds = json.dumps([
        {"type": "PING"},
        {"type": "CPU"}
    ])
    res = send_command(sock, "BATCH", commands=cmds)
    assert res["success"]
    
    # Check if data is already a dict or needs parsing
    if isinstance(res["data"], str):
        results = json.loads(res["data"])["results"]
    else:
        results = res["data"]["results"]
        
    assert len(results) == 2
    assert results[0]["data"] == "PONG"
    assert "pc" in results[1]["data"] # lowercase

# --- Input ---

def test_input_macro(sock):
    # Simple input test (don't hold too long)
    res = send_command(sock, "INPUT", buttons="A", frames="1")
    assert res["success"]

# --- Advanced Agentic Debugging Tests ---

def test_p_register_tracking(sock):
    # Start tracking
    res = send_command(sock, "P_WATCH", action="start", depth="100")
    assert res["success"]
    assert res["data"]["enabled"] is True
    
    # Run some frames to generate changes
    send_command(sock, "RESUME")
    time.sleep(0.1)
    send_command(sock, "PAUSE")
    
    # Check log
    res = send_command(sock, "P_LOG", count="10")
    assert res["success"]
    data = res["data"]
    # Entries might be empty if P didn't change, but structure should be there
    assert "entries" in data
    assert "total" in data
    
    # Stop tracking
    res = send_command(sock, "P_WATCH", action="stop")
    assert res["success"]

def test_memory_write_attribution(sock):
    # Watch Link's X position (ALTTP) or similar active address
    addr = "0x7E0022"
    res = send_command(sock, "MEM_WATCH_WRITES", action="add", addr=addr, size="2", depth="10")
    assert res["success"]
    watch_id = res["data"]["watch_id"]
    
    # Run some frames to allow Link to move or the game to update state
    send_command(sock, "RESUME")
    time.sleep(0.2)
    send_command(sock, "PAUSE")
    
    # Check blame
    res = send_command(sock, "MEM_BLAME", watch_id=str(watch_id))
    assert res["success"]
    # We don't strictly assert len > 0 because Link might not move, but we check command works
    
    # Cleanup
    send_command(sock, "MEM_WATCH_WRITES", action="remove", watch_id=str(watch_id))

def test_trace_execution(sock):
    # Trace usually returns recent execution
    res = send_command(sock, "TRACE", count="10")
    assert res["success"]
    assert "entries" in res["data"]
    assert len(res["data"]["entries"]) <= 10

def test_symbols_integration(sock):
    # Use the discovered oos.mlb (or fallback to JSON); API accepts file= or path=
    mlb_path = "/Users/scawful/src/hobby/oracle-of-secrets/Roms/oos.mlb"
    if not os.path.exists(mlb_path):
        pytest.skip(f"Symbol file not found: {mlb_path}")

    res = send_command(sock, "SYMBOLS_LOAD", file=mlb_path)
    # This might fail if the ROM doesn't match or path is inaccessible to Mesen
    if res["success"]:
        # SYMBOLS_RESOLVE expects symbol (name -> addr), not addr
        res = send_command(sock, "SYMBOLS_RESOLVE", symbol="Reset")
        # Success only if that symbol exists in the loaded table
        assert "success" in res
    else:
        print(f"DEBUG: SYMBOLS_LOAD failed: {res.get('error')}")

def test_collision_overlay(sock):
    res = send_command(sock, "COLLISION_OVERLAY")
    assert res["success"]
    assert "enabled" in res["data"]
    
    # Toggle (smoke test)
    send_command(sock, "COLLISION_OVERLAY", enabled="true", colmap="A")
    res = send_command(sock, "COLLISION_OVERLAY")
    assert res["data"]["enabled"] is True
    
    send_command(sock, "COLLISION_OVERLAY", enabled="false")

def test_collision_dump(sock):
    res = send_command(sock, "COLLISION_DUMP", colmap="A")
    # Might fail if colmap not loaded
    if res["success"]:
        assert "data" in res["data"]
        assert "width" in res["data"]


def test_symbols_load_path_alias(sock):
    """SYMBOLS_LOAD accepts path= as alias for file=."""
    json_path = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False
    )
    try:
        json_path.write('{"TestLabel": {"addr": "8000", "size": 1, "type": "code"}}')
        json_path.close()
        res = send_command(sock, "SYMBOLS_LOAD", path=json_path.name)
        assert res["success"], res.get("error")
        assert "loaded" in res["data"]
    finally:
        try:
            os.unlink(json_path.name)
        except OSError:
            pass


def test_symbols_resolve_addr(sock):
    """SYMBOLS_RESOLVE with addr= returns symbol containing that address."""
    json_path = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False
    )
    try:
        json_path.write(
            '{"Foo": {"addr": "8000", "size": 1, "type": "code"}, '
            '"Bar": {"addr": "8100", "size": 10, "type": "code"}}'
        )
        json_path.close()
        res = send_command(sock, "SYMBOLS_LOAD", file=json_path.name, clear="true")
        if not res["success"]:
            pytest.skip("SYMBOLS_LOAD failed")
        res = send_command(sock, "SYMBOLS_RESOLVE", addr="0x8000")
        assert res["success"], res.get("error")
        data = json.loads(res["data"]) if isinstance(res["data"], str) else res["data"]
        assert "name" in data
        assert data["name"] == "Foo"
        res = send_command(sock, "SYMBOLS_RESOLVE", addr="0x8105")
        assert res["success"]
        data = json.loads(res["data"]) if isinstance(res["data"], str) else res["data"]
        assert data["name"] == "Bar"
        res = send_command(sock, "SYMBOLS_RESOLVE", addr="0x7E0000")
        assert res["success"] is False or "error" in res
    finally:
        try:
            os.unlink(json_path.name)
        except OSError:
            pass


def test_discovery_via_status_file(socket_path):
    """Discover socket via status file and verify schema fields."""
    status_files = glob.glob("/tmp/mesen2-*.status")
    if not status_files:
        pytest.skip("No status files found")
    found = False
    for sf in status_files:
        try:
            with open(sf) as f:
                data = json.load(f)
            if data.get("socketPath") != socket_path:
                continue
            assert "socketPath" in data
            assert "pid" in data
            assert "emulatorRunning" in data
            assert "frameCount" in data
            assert "registeredAgents" in data
            found = True
            break
        except (OSError, json.JSONDecodeError, KeyError):
            continue
    assert found, "No status file matched current socket_path"


def test_step_mode_over(sock):
    """STEP with mode=over succeeds (step over, do not enter calls)."""
    send_command(sock, "PAUSE")
    try:
        res = send_command(sock, "STEP", count="1", mode="over")
        assert res["success"], res.get("error")
    finally:
        send_command(sock, "RESUME")

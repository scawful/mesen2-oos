import pytest
import socket
import json
import glob
import time
import base64
import os

# --- Fixtures ---

@pytest.fixture(scope="session")
def socket_path():
    """Find the active Mesen2 socket."""
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if not sockets:
        pytest.skip("No Mesen2 socket found. Is Mesen running?")
    # Sort by PID (best effort to find latest)
    return sorted(sockets, key=lambda x: -int(x.split('-')[1].split('.')[0]))[0]

@pytest.fixture(scope="session")
def sock(socket_path):
    """Create a connection to the Mesen2 socket."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(5.0)
    try:
        s.connect(socket_path)
        yield s
    finally:
        s.close()

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
    # Use the discovered oos.mlb
    mlb_path = "/Users/scawful/src/hobby/oracle-of-secrets/Roms/oos.mlb"
    if not os.path.exists(mlb_path):
        pytest.skip(f"Symbol file not found: {mlb_path}")
        
    res = send_command(sock, "SYMBOLS_LOAD", path=mlb_path)
    # This might fail if the ROM doesn't match or path is inaccessible to Mesen
    if res["success"]:
        # Try to resolve a known label if possible, or just check success
        res = send_command(sock, "SYMBOLS_RESOLVE", addr="0x008000")
        assert res["success"]
        # Resolving might return None if no label at address, but command succeeds
    else:
        # If it fails, check if it's because of path
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

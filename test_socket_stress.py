"""Stress test: 100+ consecutive varied socket commands to verify server stability.

Run with: pytest test_socket_stress.py -v
Requires a running Mesen2 instance (socket discovered via conftest).
"""

import base64
import json
import tempfile

import pytest

# Reuse send_command pattern from test_socket_api_full
def send_command(sock, cmd_type, **params):
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
    return result


def test_stress_100_commands(sock):
    """Run 100+ varied commands; assert server responds without crashing."""
    commands = []
    # Mix of lightweight and moderate commands (no ROM required for many)
    for i in range(25):
        commands.append(("PING", {}))
        commands.append(("STATE", {}))
        commands.append(("HEALTH", {}))
        commands.append(("HELP", {}))
    # BATCH a few times
    for _ in range(5):
        batch = json.dumps([{"type": "PING"}, {"type": "STATE"}])
        commands.append(("BATCH", {"commands": batch}))
    # TRACE with small count (may fail if no ROM/debugger)
    for _ in range(5):
        commands.append(("TRACE", {"count": "5"}))
    # CPU (may fail if no ROM)
    for _ in range(10):
        commands.append(("CPU", {}))
    # READ one byte (may fail if no ROM)
    for _ in range(10):
        commands.append(("READ", {"addr": "0x7E0000", "size": "1"}))

    errors = []
    for idx, (cmd_type, params) in enumerate(commands):
        try:
            res = send_command(sock, cmd_type, **params)
        except (json.JSONDecodeError, ConnectionError, OSError) as e:
            errors.append((idx, cmd_type, str(e)))
            continue
        if "success" not in res:
            errors.append((idx, cmd_type, "missing 'success' in response"))
    assert len(commands) >= 100, "expected at least 100 commands"
    assert not errors, f"commands failed or malformed: {errors[:10]}"


def test_stress_symbols_load_clear(sock):
    """Repeated SYMBOLS_LOAD and clear to stress symbol table path."""
    json_path = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False
    )
    try:
        json_path.write('{"S1": {"addr": "8000", "size": 1, "type": "code"}}')
        json_path.close()
        for _ in range(20):
            res = send_command(sock, "SYMBOLS_LOAD", file=json_path.name, clear="true")
            assert "success" in res
            res = send_command(sock, "SYMBOLS_LOAD", path=json_path.name)
            assert "success" in res
    finally:
        try:
            import os
            os.unlink(json_path.name)
        except Exception:
            pass


def test_stress_exec_lua_safe(sock):
    """Minimal EXEC_LUA payloads; server must return response (not crash)."""
    # Minimal Lua: return 1 (base64)
    minimal = base64.b64encode(b"return 1").decode()
    for _ in range(10):
        res = send_command(sock, "EXEC_LUA", code=minimal)
        assert "success" in res
        # May fail with DebuggerNotAvailable or script error; we only require no crash
    # Invalid base64
    res = send_command(sock, "EXEC_LUA", code="!!!")
    assert "success" in res
    assert res.get("success") is False
    assert "error" in res

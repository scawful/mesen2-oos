"""Synchronous PAUSE/STEP/FRAME wait=true and BATCH Oracle-tool coverage on a private core.

Run: MESEN2_CORE_PATH=/path/to/MesenCore python3 -m pytest -q -s test_socket_wait_and_batch.py
This never discovers or connects to an existing emulator.
"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

import pytest


CHILD = r'''
import ctypes
import json
import os
import socket
import sys
import tempfile
import time

core_path, rom_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
socket_dir = tempfile.TemporaryDirectory(prefix="mwait-", dir="/tmp")
socket_path = os.path.join(socket_dir.name, "mesen.sock")
os.environ["MESEN2_SOCKET_PATH"] = socket_path
os.environ.pop("MESEN2_TCP_PORT", None)
os.environ.pop("MESEN2_STATUS_PATH", None)

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitDll.restype = None
core.InitializeEmu.argtypes = [ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p,
    ctypes.c_bool, ctypes.c_bool, ctypes.c_bool, ctypes.c_bool]
core.InitializeEmu.restype = None
core.LoadRom.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
core.LoadRom.restype = ctypes.c_bool
core.InitializeDebugger.argtypes = []
core.InitializeDebugger.restype = None
core.Release.argtypes = []
core.Release.restype = None


class Client:
    def __init__(self):
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.settimeout(8)
        self.socket.connect(socket_path)
        self.reader = self.socket.makefile("rb")

    def request(self, kind, **params):
        self.socket.sendall((json.dumps({"type": kind, **params}) + "\n").encode())
        line = self.reader.readline()
        assert line, "private socket closed"
        return json.loads(line)

    def close(self):
        self.reader.close()
        self.socket.close()


client = None
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
try:
    assert core.LoadRom(rom_path.encode(), None), "generated ROM load failed"
    core.InitializeDebugger()
    client = Client()

    # Let emulation run a couple frames first
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        st = client.request("STATE")["data"]
        if st["frame"] >= 2:
            break
        time.sleep(0.01)

    # 1. PAUSE with wait=true settles immediately in STATE.paused == True
    pause_resp = client.request("PAUSE", wait="true", timeout_ms="2000")
    assert pause_resp == {"success": True, "data": "OK"}, pause_resp
    st = client.request("STATE")["data"]
    assert st["paused"] is True, st
    pc_at_pause = client.request("CPU")["data"]["pc"]

    # Repeated PAUSE wait=true on an already-paused emulator is an idempotent no-op
    pause_again = client.request("PAUSE", wait="true")
    assert pause_again == {"success": True, "data": "OK"}, pause_again
    assert client.request("CPU")["data"]["pc"] == pc_at_pause

    # 2. FRAME count=3 with wait=true advances 3 frames and returns settled-paused
    before_frame = st["frame"]
    frame_resp = client.request("FRAME", count="3", wait="true")
    assert frame_resp == {"success": True, "data": "OK"}, frame_resp
    after_st = client.request("STATE")["data"]
    assert after_st["paused"] is True, after_st
    assert before_frame + 3 <= after_st["frame"] <= before_frame + 4, (before_frame, after_st)

    # 3. BATCH supports INPUT + MEM_WATCH_WRITES + FRAME(wait=true) + MEM_BLAME + STACK_RETADDR
    batch_cmds = json.dumps([
        {"type": "MEM_WATCH_WRITES", "action": "add", "addr": "0x7E0010", "size": "1", "depth": "32"},
        {"type": "INPUT", "buttons": "A", "frames": "2", "player": "0"},
        {"type": "FRAME", "count": "2", "wait": "true"},
        {"type": "INPUT", "buttons": "", "frames": "0", "player": "0"},
        {"type": "MEM_BLAME", "addr": "0x7E0010"},
        {"type": "STACK_RETADDR", "count": "2", "mode": "rtl"},
        {"type": "MEM_WATCH_WRITES", "action": "clear"},
        {"type": "STATE"},
    ])
    batch_resp = client.request("BATCH", commands=batch_cmds)
    assert batch_resp["success"] is True, batch_resp
    results = batch_resp["data"]["results"]
    assert len(results) == 8, results
    assert results[4]["data"]["count"] >= 1, results[4]
    assert results[7]["data"]["paused"] is True, results[7]

    # 4. BATCH also accepts a native JSON array (not just a double-encoded JSON string)
    native_batch = client.request("BATCH", commands=[{"type": "PING"}, {"type": "STATE"}])
    assert native_batch["success"] is True, native_batch
    assert native_batch["data"]["results"][0]["data"] == "PONG", native_batch

    print("SOCKET_WAIT_AND_BATCH_PASS", flush=True)
finally:
    if client:
        try:
            client.close()
        except Exception:
            pass
    core.Release()
    socket_dir.cleanup()
'''


def _core_path() -> Path:
    configured = os.environ.get("MESEN2_CORE_PATH")
    if not configured:
        if os.environ.get("CI"):
            pytest.fail("set MESEN2_CORE_PATH to the freshly built native MesenCore library")
        pytest.skip("set MESEN2_CORE_PATH to an explicitly selected native MesenCore library")
    path = Path(configured)
    if not path.is_file():
        pytest.fail(f"MESEN2_CORE_PATH does not exist: {path}")
    return path.resolve()


def _write_wram_tick_rom(path: Path) -> None:
    # SEI; CLC; XCE; SEP #$30; loop: INC $0010; BRA loop
    rom = bytearray([0xEA] * 0x8000)
    rom[:9] = bytes.fromhex("78 18 FB E2 30 E6 10 80 FC")
    rom[0x7FC0:0x7FD5] = b"MESEN WAIT BATCH     "
    rom[0x7FD5:0x7FE0] = bytes.fromhex("20 00 05 00 01 33 00 00 00 FF FF")
    for offset in range(0x7FE0, 0x8000, 2):
        rom[offset:offset + 2] = bytes.fromhex("00 80")
    path.write_bytes(rom)


def test_wait_and_batch(tmp_path: Path) -> None:
    core_path = _core_path()
    rom_path = tmp_path / "wait_batch.sfc"
    _write_wram_tick_rom(rom_path)
    result = subprocess.run(
        [sys.executable, "-B", "-c", CHILD, str(core_path), str(rom_path), str(tmp_path / "home")],
        capture_output=True,
        text=True,
        timeout=30,
    )
    print(result.stdout, end="")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "SOCKET_WAIT_AND_BATCH_PASS" in result.stdout

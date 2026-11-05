"""FRAME must advance frames, using only a generated ROM and private core.

Set MESEN2_CORE_PATH to a freshly built native library and run:
python3 -m pytest -q test_socket_frame.py
"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest


CHILD = r"""
import ctypes
import json
import os
import socket
import sys
import tempfile
import threading
import time

core_path, rom_path, home, count = sys.argv[1:]
os.makedirs(home, exist_ok=True)
socket_dir = tempfile.TemporaryDirectory(prefix="mframe-", dir="/tmp")
socket_path = os.path.join(socket_dir.name, "mesen.sock")
os.environ["MESEN2_SOCKET_PATH"] = socket_path
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
core.Step.argtypes = [ctypes.c_int, ctypes.c_uint32, ctypes.c_int]
core.Step.restype = None
core.Pause.argtypes = []
core.Pause.restype = None
core.Release.argtypes = []
core.Release.restype = None
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.UnregisterNotificationCallback.argtypes = [ctypes.c_void_p]
core.UnregisterNotificationCallback.restype = None
code_break = threading.Event()

@callback_type
def on_notification(kind, parameter):
    # ConsoleNotificationType::CodeBreak
    if kind == 5:
        code_break.set()

core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
connection = None
reader = None
listener = None
try:
    assert core.LoadRom(rom_path.encode(), None), "generated ROM load failed"
    core.InitializeDebugger()
    listener = core.RegisterNotificationCallback(on_notification)
    assert listener, "notification registration failed"
    core.Pause()
    assert code_break.wait(5), "initial pause did not complete"

    # Stop well inside a frame so a one-PPU-cycle step cannot accidentally
    # satisfy the default one-frame assertion by crossing a frame boundary.
    code_break.clear()
    core.Step(0, 100, 7)  # CpuType::Snes, StepType::SpecificScanline
    assert code_break.wait(5), "scanline fixture did not pause"

    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.settimeout(5)
    connection.connect(socket_path)
    reader = connection.makefile("rb")

    def request(kind, **params):
        connection.sendall((json.dumps({"type": kind, **params}) + "\n").encode())
        line = reader.readline()
        assert line, "private socket closed"
        return json.loads(line)

    def sample():
        state = request("STATE")
        timing = request("STATEINSPECT")
        assert state["success"] and timing["success"], (state, timing)
        return {"frame": state["data"]["frame"],
            "paused": state["data"]["paused"],
            "clock": timing["data"]["system"]["masterClock"]}

    before = sample()
    assert before["paused"], before
    code_break.clear()
    params = {} if count == "default" else {"count": count}
    response = request("FRAME", **params)
    if count in ("0", "-1"):
        # Rejection must preserve the paused state, including emulated time.
        time.sleep(0.05)
        after = sample()
        print(json.dumps({"count": count, "before": before, "after": after,
            "response": response}), flush=True)
        assert response["success"] is False, "non-positive FRAME count was accepted"
        assert after == before, "rejected FRAME resumed or advanced the emulator"
    else:
        assert response["success"], response
        # A fresh completion notification avoids accepting the old paused state
        # immediately after the asynchronous command's acknowledgement.
        assert code_break.wait(5), "FRAME did not finish and pause"
        after = sample()
        expected = 1 if count == "default" else int(count)
        print(json.dumps({"count": count, "before": before, "after": after,
            "expected_frames": expected, "actual_frames": after["frame"] - before["frame"]}),
            flush=True)
        assert after["clock"] > before["clock"], "FRAME did not execute"
        assert after["paused"], "FRAME did not leave the emulator paused"
        assert after["frame"] - before["frame"] == expected, "FRAME advanced the wrong number of frames"
    print("SOCKET_FRAME_PASS", flush=True)
finally:
    if reader is not None:
        reader.close()
    if connection is not None:
        connection.close()
    if listener:
        core.UnregisterNotificationCallback(listener)
    core.Release()
    socket_dir.cleanup()
"""


def _core_path() -> Path:
    configured = os.environ.get("MESEN2_CORE_PATH")
    if not configured:
        if os.environ.get("CI"):
            pytest.fail("set MESEN2_CORE_PATH to the freshly built native MesenCore library")
        pytest.skip("set MESEN2_CORE_PATH to the native MesenCore library")
    path = Path(configured)
    if not path.is_file():
        pytest.fail(f"MESEN2_CORE_PATH does not exist: {path}")
    return path.resolve()


def _write_loop_rom(path: Path) -> None:
    rom = bytearray([0xEA] * 0x8000)
    rom[:6] = bytes.fromhex("78 18 FB 4C 03 80")  # SEI; CLC; XCE; JMP $8003
    rom[0x7FC0:0x7FD5] = b"MESEN FRAME TEST     "
    rom[0x7FD5:0x7FE0] = bytes.fromhex("20 00 05 00 01 33 00 00 00 FF FF")
    for offset in range(0x7FE0, 0x8000, 2):
        rom[offset:offset + 2] = bytes.fromhex("00 80")
    path.write_bytes(rom)


@pytest.mark.parametrize("count", ["default", "3", "0", "-1"])
def test_frame_advancement_and_count_validation(tmp_path: Path, count: str) -> None:
    core_path = _core_path()
    rom_path = tmp_path / "frame.sfc"
    _write_loop_rom(rom_path)
    result = subprocess.run(
        [sys.executable, "-c", textwrap.dedent(CHILD), str(core_path),
         str(rom_path), str(tmp_path / "home"), count],
        capture_output=True,
        text=True,
        timeout=20,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "SOCKET_FRAME_PASS" in result.stdout

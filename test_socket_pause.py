"""Bounded socket and UI C API pause using a generated ROM and private core.

Candidate: MESEN2_CORE_PATH=/path/to/MesenCore python3 -m pytest -q -s test_socket_pause.py
Old control: add MESEN2_PAUSE_EXPECT_BLOCKED=1. This expects the gated request
to block and spin until the callback releases it, rather than passing the fix.
Neither mode discovers or connects to an existing emulator.
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
import threading
import time

core_path, rom_path, home, scenario, expect_blocked = sys.argv[1:]
expect_blocked = expect_blocked == "1"
os.makedirs(home, exist_ok=True)
socket_dir = tempfile.TemporaryDirectory(prefix="mpause-", dir="/tmp")
socket_path = os.path.join(socket_dir.name, "mesen.sock")
os.environ["MESEN2_SOCKET_PATH"] = socket_path
# Do not inherit an alternate TCP listener from the test runner's environment.
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
core.Pause.argtypes = []
core.Pause.restype = None
core.Resume.argtypes = []
core.Resume.restype = None
core.Release.argtypes = []
core.Release.restype = None
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.UnregisterNotificationCallback.argtypes = [ctypes.c_void_p]
core.UnregisterNotificationCallback.restype = None

code_break = threading.Event()
gate_armed = threading.Event()
gate_entered = threading.Event()
gate_release = threading.Event()
gate_expired = threading.Event()
gate_finished = threading.Event()

@callback_type
def on_notification(kind, parameter):
    if kind == 5:  # ConsoleNotificationType::CodeBreak
        code_break.set()
    elif kind == 7 and gate_armed.is_set():  # PpuFrameDone, on emulation thread
        gate_armed.clear()
        gate_entered.set()
        # This models an unresponsive emulation callback. The independent
        # timeout also releases old cores whose PAUSE blocks the socket thread.
        if not gate_release.wait(2.0):
            gate_expired.set()
        gate_finished.set()

class Client:
    def __init__(self):
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.settimeout(4)
        self.socket.connect(socket_path)
        self.reader = self.socket.makefile("rb")

    def request(self, kind):
        self.socket.sendall((json.dumps({"type": kind}) + "\n").encode())
        line = self.reader.readline()
        assert line, "private socket closed"
        return json.loads(line)

    def close(self):
        self.reader.close()
        self.socket.close()

def state(client):
    response = client.request("STATE")
    assert response["success"] is True, response
    return response["data"]

def wait_frames(client, start_frame, count=3):
    deadline = time.monotonic() + 2
    last = None
    while time.monotonic() < deadline:
        last = state(client)
        if last["frame"] >= start_frame + count:
            assert last["paused"] is False, last
            return last
        time.sleep(0.01)
    raise AssertionError(("frames did not resume", start_frame, last))

def pause(client, direct=False):
    code_break.clear()
    if direct:
        core.Pause()  # The void C API invoked by EmuApi.Pause in the UI.
    else:
        response = client.request("PAUSE")
        assert response["success"] is True, response
    assert code_break.wait(2), "pause was acknowledged without a fresh break"
    stopped = state(client)
    assert stopped["paused"] is True, stopped
    time.sleep(0.03)
    assert state(client)["frame"] == stopped["frame"], "paused frame advanced"
    return stopped

def ping(client):
    started = time.monotonic()
    response = client.request("PING")
    elapsed = time.monotonic() - started
    assert response["success"] is True and response["data"] == "PONG", response
    assert elapsed < 0.5, ("PING blocked", elapsed)
    return elapsed

clients = []
listener = None
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
try:
    assert core.LoadRom(rom_path.encode(), None), "generated ROM load failed"
    core.InitializeDebugger()
    listener = core.RegisterNotificationCallback(on_notification)
    assert listener, "notification registration failed"
    client = Client()
    clients.append(client)
    assert state(client)["debugging"] is True, "debugger fixture is absent"
    initial = pause(client)

    if scenario == "normal":
        rounds = []
        for _ in range(3):
            before = state(client)
            assert client.request("RESUME")["success"] is True
            running = wait_frames(client, before["frame"])
            stopped = pause(client)
            rounds.append({"running_frame": running["frame"],
                "paused_frame": stopped["frame"], "ping_seconds": ping(client)})
        print(json.dumps({"scenario": scenario, "rounds": rounds}), flush=True)
    else:
        gate_armed.set()
        assert client.request("RESUME")["success"] is True
        assert gate_entered.wait(2), "emulation callback gate was not entered"
        frozen = state(client)
        assert frozen["paused"] is False, "gate must stall running emulation"
        code_break.clear()
        cpu_start, wall_start = time.process_time(), time.monotonic()
        response = core.Pause() if scenario == "direct" else client.request("PAUSE")
        elapsed = time.monotonic() - wall_start
        cpu_seconds = time.process_time() - cpu_start
        measurement = {"scenario": scenario, "old_control": expect_blocked,
            "pause_seconds": elapsed, "process_cpu_seconds": cpu_seconds,
            "response": response, "gate_expired": gate_expired.is_set()}
        # Emit measurements even when the following assertions reject a core.
        print(json.dumps(measurement), flush=True)

        if expect_blocked:
            if scenario != "direct":
                assert response["success"] is True, response
            assert elapsed >= 1.5 and gate_expired.is_set(), measurement
            assert cpu_seconds > 0.5, ("old control did not reproduce busy wait", measurement)
            assert code_break.wait(2), "old pause never completed after gate release"
            assert client.request("RESUME")["success"] is True
        else:
            if scenario != "direct":
                assert response["success"] is False, response
                assert response.get("errorCode") == 10, response  # SocketErrorCode::Timeout
                assert response.get("retryable") is True, response
                assert isinstance(response.get("error"), str) and response["error"], response
            assert 0.35 <= elapsed < 1.1, measurement
            assert cpu_seconds < max(0.2, elapsed * 0.45), measurement
            assert not gate_expired.is_set() and not gate_finished.is_set(), measurement
            measurement["same_connection_ping_seconds"] = ping(client)
            second = Client()
            clients.append(second)
            measurement["new_connection_ping_seconds"] = ping(second)
            assert not gate_finished.is_set(), "gate ended before IPC responsiveness checks"
            assert state(client)["frame"] == frozen["frame"], "gate did not hold emulation"
            assert not code_break.is_set(), "timed-out request unexpectedly paused emulation"
            gate_release.set()

        assert gate_finished.wait(1), "callback did not finish"
        # Candidate deliberately sends no RESUME here. A leaked break request
        # or delayed pause from the timed-out command must fail this assertion.
        recovered = wait_frames(client, frozen["frame"])
        stopped = pause(client, direct=scenario == "direct")
        measurement.update({"recovered_frame": recovered["frame"],
            "paused_again_frame": stopped["frame"], "final_ping_seconds": ping(client)})
        print(json.dumps(measurement), flush=True)
    print("SOCKET_PAUSE_PASS", flush=True)
finally:
    gate_armed.clear()
    gate_release.set()
    if gate_entered.is_set():
        gate_finished.wait(3)
    for connection in clients:
        connection.close()
    if listener:
        core.UnregisterNotificationCallback(listener)
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


def _write_loop_rom(path: Path) -> None:
    rom = bytearray([0xEA] * 0x8000)
    rom[:6] = bytes.fromhex("78 18 FB 4C 03 80")  # SEI; CLC; XCE; JMP $8003
    rom[0x7FC0:0x7FD5] = b"MESEN PAUSE TEST     "
    rom[0x7FD5:0x7FE0] = bytes.fromhex("20 00 05 00 01 33 00 00 00 FF FF")
    for offset in range(0x7FE0, 0x8000, 2):
        rom[offset:offset + 2] = bytes.fromhex("00 80")
    path.write_bytes(rom)


@pytest.mark.parametrize("scenario", ["normal", "gated", "direct"])
def test_socket_pause_and_recovery(tmp_path: Path, scenario: str) -> None:
    core_path = _core_path()
    rom_path = tmp_path / "pause.sfc"
    _write_loop_rom(rom_path)
    result = subprocess.run(
        [sys.executable, "-B", "-c", CHILD, str(core_path), str(rom_path),
         str(tmp_path / "home"), scenario,
         "1" if os.environ.get("MESEN2_PAUSE_EXPECT_BLOCKED") == "1" else "0"],
        capture_output=True,
        text=True,
        timeout=20,
    )
    print(result.stdout, end="")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "SOCKET_PAUSE_PASS" in result.stdout

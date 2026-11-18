"""RESUME/STEP/FRAME gating and bounded break, using a generated ROM and private core.

Run: MESEN2_CORE_PATH=/path/to/MesenCore python3 -m pytest -q -s test_socket_resume_gate.py
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
import threading
import time

core_path, rom_path, home, scenario = sys.argv[1:]
os.makedirs(home, exist_ok=True)
socket_dir = tempfile.TemporaryDirectory(prefix="mgate-", dir="/tmp")
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
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.UnregisterNotificationCallback.argtypes = [ctypes.c_void_p]
core.UnregisterNotificationCallback.restype = None

GATE_SECONDS = 4.0
code_break = threading.Event()
gate_armed = threading.Event()
gate_entered = threading.Event()
gate_release = threading.Event()
gate_finished = threading.Event()

@callback_type
def on_notification(kind, parameter):
    if kind == 5:  # ConsoleNotificationType::CodeBreak
        code_break.set()
    elif kind == 7 and gate_armed.is_set():  # PpuFrameDone, on emulation thread
        gate_armed.clear()
        gate_entered.set()
        gate_release.wait(GATE_SECONDS)  # models an unresponsive emulation thread
        gate_finished.set()

class Client:
    def __init__(self):
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.settimeout(6)
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

def state(client):
    response = client.request("STATE")
    assert response["success"] is True, response
    return response["data"]

def wait_running(client, start_frame, count=3):
    deadline = time.monotonic() + 2
    last = None
    while time.monotonic() < deadline:
        last = state(client)
        if last["frame"] >= start_frame + count:
            assert last["paused"] is False, last
            return last
        time.sleep(0.01)
    raise AssertionError(("frames did not advance", start_frame, last))

def pause(client):
    code_break.clear()
    response = client.request("PAUSE")
    assert response["success"] is True, response
    assert code_break.wait(2), "pause did not reach a break"
    stopped = state(client)
    assert stopped["paused"] is True, stopped
    return stopped

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
    running = wait_running(client, state(client)["frame"])

    if scenario == "not_paused":
        resume = client.request("RESUME")
        assert resume["success"] is True and resume["data"] == "NOT_PAUSED", resume
        for kind, params in (("STEP", {}), ("STEP", {"mode": "frame"}), ("FRAME", {}), ("FRAME", {"count": "3"})):
            response = client.request(kind, **params)
            assert response["success"] is False, (kind, response)
            assert response.get("errorCode") == 15, (kind, response)  # SocketErrorCode::InvalidState
            assert "not paused" in response["error"], (kind, response)
        # None of the rejected commands may pause or stall the running emulator.
        after = wait_running(client, state(client)["frame"])
        stopped = pause(client)
        # A step that has not landed yet still counts as paused.
        for _ in range(20):
            assert client.request("STEP")["success"] is True
        code_break.clear()
        assert client.request("FRAME")["success"] is True
        assert code_break.wait(2), "FRAME from a paused emulator did not pause again"
        assert client.request("RESUME") == {"success": True, "data": "OK"}
        wait_running(client, stopped["frame"])
        print(json.dumps({"scenario": scenario, "running_frame": after["frame"]}), flush=True)
    elif scenario == "pause_then_command":
        # PAUSE is a one-instruction step: a command sent before that step lands
        # must still be treated as paused, or RESUME would leave the emulator stuck.
        for _ in range(200):
            assert client.request("PAUSE")["success"] is True
            resume = client.request("RESUME")
            assert resume == {"success": True, "data": "OK"}, resume
        before = state(client)
        wait_running(client, before["frame"])
        for _ in range(50):
            assert client.request("PAUSE")["success"] is True
            code_break.clear()
            frame = client.request("FRAME")
            assert frame["success"] is True, frame
            assert code_break.wait(2), "FRAME did not pause again"
            assert client.request("RESUME")["success"] is True
        wait_running(client, state(client)["frame"])
        print(json.dumps({"scenario": scenario}), flush=True)
    elif scenario == "stress":
        # Old cores replace the step request under the running emulation thread here.
        stop = threading.Event()
        errors = []
        counts = {}
        def hammer(kinds):
            mine = Client()
            clients.append(mine)
            try:
                while not stop.is_set():
                    for kind in kinds:
                        response = mine.request(kind)
                        key = kind + (":ok" if response["success"] else ":" + str(response.get("errorCode")))
                        counts[key] = counts.get(key, 0) + 1
                        if not response["success"] and response.get("errorCode") not in (10, 15):
                            errors.append((kind, response))
            except Exception as error:
                errors.append(repr(error))
        threads = [threading.Thread(target=hammer, args=(kinds,)) for kinds in
            (("RESUME",), ("PAUSE", "RESUME"), ("STEP", "RESUME"), ("FRAME", "RESUME"))]
        for thread in threads:
            thread.start()
        time.sleep(4)
        stop.set()
        for thread in threads:
            thread.join(8)
            assert not thread.is_alive(), "socket client hung"
        assert not errors, errors[:5]
        assert client.request("PING")["data"] == "PONG"
        resume = client.request("RESUME")
        assert resume["success"] is True, resume
        wait_running(client, state(client)["frame"])
        print(json.dumps({"scenario": scenario, "counts": counts}), flush=True)
    else:
        stopped = pause(client)
        gate_armed.set()
        assert client.request("RESUME") == {"success": True, "data": "OK"}
        assert gate_entered.wait(2), "emulation callback gate was not entered"
        frozen = state(client)
        # The emulator is running but cannot reach a stop point: the command must
        # give up at its deadline instead of spinning and stalling the socket server.
        kind = scenario.split("_", 1)[1]
        cpu_start, wall_start = time.process_time(), time.monotonic()
        response = client.request(kind)
        elapsed = time.monotonic() - wall_start
        cpu_seconds = time.process_time() - cpu_start
        measurements = {"kind": kind, "seconds": elapsed, "cpu": cpu_seconds, "response": response}
        print(json.dumps({"scenario": scenario, "measurements": measurements}), flush=True)
        assert not gate_finished.is_set(), "gate ended before the deadline was measured"
        assert response["success"] is False, response
        assert response.get("errorCode") == 10, response  # SocketErrorCode::Timeout
        assert response.get("retryable") is True, response
        assert 1.8 <= elapsed < 2.6, measurements
        assert cpu_seconds < elapsed * 0.45, ("busy wait", measurements)
        second = Client()
        clients.append(second)
        started = time.monotonic()
        assert second.request("PING")["data"] == "PONG"
        assert time.monotonic() - started < 0.5, "socket server stalled"
        assert state(client)["frame"] == frozen["frame"], "gate did not hold emulation"
        gate_release.set()
        assert gate_finished.wait(1), "callback did not finish"
        # The cancelled request must not leak a break request or a delayed pause.
        wait_running(client, frozen["frame"])
        pause(client)
        assert client.request("RESUME") == {"success": True, "data": "OK"}
    print("SOCKET_RESUME_GATE_PASS", flush=True)
finally:
    gate_armed.clear()
    gate_release.set()
    if gate_entered.is_set():
        gate_finished.wait(GATE_SECONDS + 1)
    for connection in clients:
        try:
            connection.close()
        except Exception:
            pass
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
    rom[0x7FC0:0x7FD5] = b"MESEN GATE TEST      "
    rom[0x7FD5:0x7FE0] = bytes.fromhex("20 00 05 00 01 33 00 00 00 FF FF")
    for offset in range(0x7FE0, 0x8000, 2):
        rom[offset:offset + 2] = bytes.fromhex("00 80")
    path.write_bytes(rom)


@pytest.mark.parametrize("scenario", ["not_paused", "pause_then_command", "stress", "gated_RESUME", "gated_STEP", "gated_FRAME"])
def test_resume_step_frame_gate(tmp_path: Path, scenario: str) -> None:
    core_path = _core_path()
    rom_path = tmp_path / "gate.sfc"
    _write_loop_rom(rom_path)
    result = subprocess.run(
        [sys.executable, "-B", "-c", CHILD, str(core_path), str(rom_path),
         str(tmp_path / "home"), scenario],
        capture_output=True,
        text=True,
        timeout=40,
    )
    print(result.stdout, end="")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "SOCKET_RESUME_GATE_PASS" in result.stdout

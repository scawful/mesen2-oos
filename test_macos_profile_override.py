"""Process regression for first launch into an explicitly isolated Mesen profile."""

from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import signal
import socket as unix_socket
import time
import uuid

import pytest


pytestmark = pytest.mark.skipif(sys.platform != "darwin", reason="macOS app regression")


@pytest.fixture
def private_socket_path():
    with tempfile.TemporaryDirectory(prefix="mesen-profile-", dir="/tmp") as directory:
        yield Path(directory) / "test.sock"


def test_first_launch_keeps_new_profile_in_mesen2_home(
    tmp_path: Path, private_socket_path: Path,
) -> None:
    configured_app = os.environ.get("MESEN_TEST_APP")
    if not configured_app:
        pytest.skip("set MESEN_TEST_APP to a separately built .app bundle")

    app = Path(configured_app).resolve()
    executable = app / "Contents" / "MacOS" / "Mesen"
    assert executable.is_file(), f"app executable missing: {executable}"
    profile = tmp_path / "empty-profile"
    profile.mkdir()
    socket = private_socket_path
    assert len(os.fsencode(socket)) < 104, "macOS Unix socket path would be truncated"

    # A broken first-launch path must not write to the real user profile.
    # The disposable test directory must live outside that directory.
    user_home = Path.home().resolve()
    if tmp_path.resolve().is_relative_to(user_home):
        pytest.fail("use --basetemp outside the user home for this isolation test")
    policy = f'(version 1) (allow default) (deny file-write* (subpath {json.dumps(str(user_home))}))'
    env = os.environ.copy()
    env.update({
        "MESEN2_HOME": str(profile),
        "MESEN2_SOCKET_PATH": str(socket),
        "SDL_AUDIODRIVER": "dummy",
        "SDL_VIDEODRIVER": "dummy",
    })
    result = subprocess.run(
        ["/usr/bin/sandbox-exec", "-p", policy, str(executable),
         "--headless", "--noaudio", "--noinput", f"--instanceGuid={uuid.uuid4()}"],
        capture_output=True, text=True, env=env, timeout=30,
    )

    # No ROM was requested: this proves startup/profile routing, not gameplay.
    assert result.returncode == 255, result.stdout + result.stderr
    assert "Headless mode: no ROM specified." in result.stdout
    settings = profile / "settings.json"
    assert settings.is_file(), "first launch escaped the MESEN2_HOME override"
    assert isinstance(json.loads(settings.read_text(encoding="utf-8-sig")), dict)
    assert (profile / "MesenCore.dylib").is_file(), "dependencies were extracted outside MESEN2_HOME"
    assert hashlib.sha256((profile / "MesenCore.dylib").read_bytes()).digest() == hashlib.sha256(
        (app / "Contents/MacOS/MesenCore.dylib").read_bytes()
    ).digest(), "embedded core differs from the app-local core"
    assert not socket.exists(), "temporary emulator socket remained after shutdown"


def test_headless_first_launch_applies_default_controller(
    tmp_path: Path, private_socket_path: Path,
) -> None:
    from test_spc_savestate import _write_loop_rom

    configured_app = os.environ.get("MESEN_TEST_APP")
    if not configured_app:
        pytest.skip("set MESEN_TEST_APP to a separately built .app bundle")
    executable = Path(configured_app).resolve() / "Contents" / "MacOS" / "Mesen"
    assert executable.is_file()
    user_home = Path.home().resolve()
    assert not tmp_path.resolve().is_relative_to(user_home), "use --basetemp outside the user home"
    profile = tmp_path / "new-controller-profile"
    rom = tmp_path / "controller.sfc"
    script = tmp_path / "controller.lua"
    _write_loop_rom(rom)
    script.write_text('''
local done = false
emu.addEventCallback(function()
    if done then return end
    done = true
    local ok = pcall(function()
        local input = emu.getInput(0)
        for _, name in ipairs({"a", "b", "x", "y", "l", "r", "start", "select",
                               "up", "down", "left", "right"}) do
            assert(type(input[name]) == "boolean", "missing SNES controller: " .. name)
        end
        emu.setInput({b = true}, 0)
        assert(emu.getInput(0).b == true, "controller did not accept input")
        emu.setInput({b = false}, 0)
        assert(emu.getInput(0).b == false, "controller did not release input")
    end)
    emu.stop(ok and 0 or 42)
end, emu.eventType.startFrame)
''')
    env = dict(os.environ, MESEN2_HOME=str(profile),
               MESEN2_SOCKET_PATH=str(private_socket_path))
    policy = f'(version 1) (allow default) (deny file-write* (subpath {json.dumps(str(user_home))}))'
    result = subprocess.run(
        ["/usr/bin/sandbox-exec", "-p", policy, str(executable), "--headless",
         "--noaudio", "--noinput", str(rom), str(script), f"--instanceGuid={uuid.uuid4()}"],
        capture_output=True, text=True, env=env, timeout=20,
    )
    assert result.returncode == 0, (
        f"headless controller probe exited {result.returncode} (42 means controller check failed)\n"
        + result.stdout + result.stderr
    )
    assert not private_socket_path.exists(), "temporary emulator socket remained after shutdown"


@pytest.mark.parametrize("mode", ["running", "paused", "recording"])
def test_headless_sigterm_stops_before_native_teardown(
    tmp_path: Path, private_socket_path: Path, mode: str,
) -> None:
    from test_spc_savestate import _write_loop_rom

    configured_app = os.environ.get("MESEN_TEST_APP")
    if not configured_app:
        pytest.skip("set MESEN_TEST_APP to a separately built .app bundle")
    executable = Path(configured_app).resolve() / "Contents/MacOS/Mesen"
    rom = tmp_path / "shutdown.sfc"
    _write_loop_rom(rom)
    profile = tmp_path / "shutdown-profile"
    assert not tmp_path.resolve().is_relative_to(Path.home().resolve())
    policy = f'(version 1) (allow default) (deny file-write* (subpath {json.dumps(str(Path.home()))}))'
    env = dict(os.environ, MESEN2_HOME=str(profile),
               MESEN2_SOCKET_PATH=str(private_socket_path))
    process = subprocess.Popen(
        ["/usr/bin/sandbox-exec", "-p", policy, str(executable), "--headless",
         "--noaudio", "--noinput", str(rom), f"--instanceGuid={uuid.uuid4()}"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env,
    )
    try:
        deadline = time.monotonic() + 15
        while not private_socket_path.exists() and process.poll() is None:
            assert time.monotonic() < deadline, "private app socket did not appear"
            time.sleep(0.02)
        assert process.poll() is None, process.communicate()
        with unix_socket.socket(unix_socket.AF_UNIX) as connection:
            connection.settimeout(5)
            connection.connect(str(private_socket_path))
            with connection.makefile("rb") as reader:
                def request(kind, **params):
                    connection.sendall((json.dumps({"type": kind, **params}) + "\n").encode())
                    response = json.loads(reader.readline())
                    assert response["success"], response
                    return response.get("data", {})
                state = request("STATE")
                while state.get("frame", 0) < 5:
                    assert time.monotonic() < deadline, "private ROM did not run"
                    time.sleep(0.02)
                    state = request("STATE")
                if mode in ("paused", "recording"):
                    request("CPU")  # Instantiate the debugger before confirming Pause.
                    request("PAUSE")
                    assert request("STATE")["paused"] is True
                if mode == "recording":
                    capabilities = request("CAPABILITIES")
                    assert "native_video_record_v1" in capabilities["features"]
                    request("VIDEO_RECORD", action="start", token="shutdown-test",
                            path=str(tmp_path / "shutdown.avi"))
                    request("FRAME", count="120")
                    # Leave recording active: graceful shutdown must flush it.
        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=15)
        assert process.returncode == 0, stdout + stderr
        assert not private_socket_path.exists(), "owned socket survived SIGTERM"
        log = (profile / "instance.log").read_text()
        assert "received SIGTERM: stopping" in log
        assert "emulation stopped (stop code 0)" in log
        if mode == "recording":
            data = (tmp_path / "shutdown.avi").read_bytes()
            assert data[:4] == b"RIFF" and data[8:12] == b"AVI "
            assert len(data) > 256, "recording was not finalized on shutdown"
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

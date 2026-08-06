"""Process-level regressions for the native interop lifetime gate."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest


pytestmark = pytest.mark.skipif(sys.platform != "darwin", reason="macOS interop regression")


LEASE_DRAIN_CHILD = r"""
import ctypes
import os
import sys
import threading
import time

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.Release.argtypes = []
core.IsRunning.restype = ctypes.c_bool
core.GetMesenVersion.restype = ctypes.c_uint32

class ExecuteShortcutParams(ctypes.Structure):
    _fields_ = [
        ("shortcut", ctypes.c_int),
        ("param", ctypes.c_uint32),
        ("param_ptr", ctypes.c_void_p),
    ]

callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.ExecuteShortcut.argtypes = [ExecuteShortcutParams]

callback_entered = threading.Event()
finish_callback = threading.Event()
callback_finished = threading.Event()
release_finished = threading.Event()

def on_notification(notification_type, parameter):
    if notification_type == 10:  # ExecuteShortcut
        callback_entered.set()
        assert finish_callback.wait(5), "callback release timed out"
        callback_finished.set()

callback = callback_type(on_notification)
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
assert core.RegisterNotificationCallback(callback)

api_thread = threading.Thread(
    target=lambda: core.ExecuteShortcut(ExecuteShortcutParams(0, 0, None))
)
api_thread.start()
assert callback_entered.wait(5), "interop callback did not start"

release_thread = threading.Thread(target=lambda: (core.Release(), release_finished.set()))
release_thread.start()
assert not release_finished.wait(0.25), "Release returned while an API lease was active"

finish_callback.set()
api_thread.join(5)
release_thread.join(5)
assert callback_finished.is_set(), "callback did not finish"
assert not api_thread.is_alive(), "API call did not drain"
assert release_finished.is_set(), "Release did not finish after the API drained"
assert core.GetMesenVersion() == 0, "post-release API admission remained open"
assert not core.IsRunning(), "post-release IsRunning did not fail closed"
print("lease drained", flush=True)
"""


REENTRANT_RELEASE_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
core.Release.argtypes = []
core.GetMesenVersion.restype = ctypes.c_uint32

class ExecuteShortcutParams(ctypes.Structure):
    _fields_ = [
        ("shortcut", ctypes.c_int),
        ("param", ctypes.c_uint32),
        ("param_ptr", ctypes.c_void_p),
    ]

callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.ExecuteShortcut.argtypes = [ExecuteShortcutParams]
reentrant_release_returned = threading.Event()

def on_notification(notification_type, parameter):
    if notification_type == 10:
        core.Release()
        reentrant_release_returned.set()

callback = callback_type(on_notification)
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
assert core.RegisterNotificationCallback(callback)
core.ExecuteShortcut(ExecuteShortcutParams(0, 0, None))
assert reentrant_release_returned.is_set(), "reentrant Release deadlocked"
assert core.GetMesenVersion() != 0, "reentrant Release partially tore down the emulator"

core.Release()
assert core.GetMesenVersion() == 0, "terminal Release did not close admission"
print("reentrant release rejected", flush=True)
"""


HISTORY_RELEASE_SCOPE_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.HistoryViewerRelease.argtypes = []
core.Release.argtypes = []

class ExecuteShortcutParams(ctypes.Structure):
    _fields_ = [
        ("shortcut", ctypes.c_int),
        ("param", ctypes.c_uint32),
        ("param_ptr", ctypes.c_void_p),
    ]

core.ExecuteShortcut.argtypes = [ExecuteShortcutParams]
callback_entered = threading.Event()
finish_callback = threading.Event()
history_release_finished = threading.Event()

def on_notification(notification_type, parameter):
    if notification_type == 10:  # ExecuteShortcut
        callback_entered.set()
        assert finish_callback.wait(5), "callback release timed out"

callback = callback_type(on_notification)
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
assert core.RegisterNotificationCallback(callback)

api_thread = threading.Thread(
    target=lambda: core.ExecuteShortcut(ExecuteShortcutParams(0, 0, None))
)
api_thread.start()
assert callback_entered.wait(5), "main callback did not start"

history_release_thread = threading.Thread(
    target=lambda: (core.HistoryViewerRelease(), history_release_finished.set())
)
history_release_thread.start()
assert history_release_finished.wait(1), (
    "history release waited for an unrelated main-emulator callback"
)

finish_callback.set()
history_release_thread.join(5)
api_thread.join(5)
assert not history_release_thread.is_alive(), "history release hung"
assert not api_thread.is_alive(), "main callback did not drain"
core.Release()
print("history callback scope isolated", flush=True)
"""


SAVE_PREVIEW_RELEASE_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, rom_path, home, state_path = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
core.LoadRom.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
core.LoadRom.restype = ctypes.c_bool
core.SaveStateFile.argtypes = [ctypes.c_char_p]
core.GetSaveStatePreview.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_uint8)]
core.GetSaveStatePreview.restype = ctypes.c_int32
core.Release.argtypes = []

core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
assert core.LoadRom(rom_path.encode(), None), "ROM load failed"
core.SaveStateFile(state_path.encode())
assert os.path.isfile(state_path), "state write failed"

start = threading.Barrier(3)
errors = []
preview_buffer = (ctypes.c_uint8 * (512 * 478 * 4))()

def exercise_save_apis():
    try:
        start.wait()
        for index in range(25):
            if index % 5 == 0:
                core.SaveStateFile(state_path.encode())
            core.GetSaveStatePreview(state_path.encode(), preview_buffer)
    except BaseException as exc:
        errors.append(exc)

def release():
    try:
        start.wait()
        core.Release()
    except BaseException as exc:
        errors.append(exc)

api_thread = threading.Thread(target=exercise_save_apis)
release_thread = threading.Thread(target=release)
api_thread.start()
release_thread.start()
start.wait()
api_thread.join(10)
release_thread.join(10)
assert not api_thread.is_alive(), "save/preview API thread hung during Release"
assert not release_thread.is_alive(), "Release hung behind save/preview APIs"
assert not errors, errors
assert core.GetSaveStatePreview(state_path.encode(), preview_buffer) == 0
print("save preview release safe", flush=True)
"""


def _core_path() -> Path:
	path = os.environ.get("MESEN2_CORE_PATH")
	if not path:
		if os.environ.get("CI"):
			pytest.fail("set MESEN2_CORE_PATH to the freshly built MesenCore.dylib")
		pytest.skip("run `make test-macos-shutdown` or set MESEN2_CORE_PATH")
	core_path = Path(path)
	if not core_path.is_file():
		pytest.fail(f"MESEN2_CORE_PATH does not exist: {core_path}")
	return core_path.resolve()


def _write_minimal_snes_rom(path: Path) -> None:
	rom = bytearray([0xEA] * 0x8000)
	rom[0:7] = bytes.fromhex("78 18 FB 4C 03 80 EA")
	rom[0x7FC0:0x7FE0] = bytes.fromhex(
		"4D 45 53 45 4E 20 4C 49 46 45 54 49 4D 45 20 54 "
		"45 53 54 20 20 20 00 05 00 01 33 00 4A 24 B5 DB"
	)
	rom[0x7FE4:0x8000] = bytes.fromhex(
		"00 80 00 80 EA EA 00 80 EA EA 00 80 EA EA EA EA "
		"00 80 00 80 EA EA 00 80 00 80 00 80"
	)
	path.write_bytes(rom)


def _run_child(script: str, *args: str, timeout: int = 15) -> subprocess.CompletedProcess[str]:
	return subprocess.run(
		[sys.executable, "-c", textwrap.dedent(script), str(_core_path()), *args],
		capture_output=True,
		text=True,
		timeout=timeout,
	)


def test_release_waits_for_in_flight_interop_lease(tmp_path: Path) -> None:
	result = _run_child(LEASE_DRAIN_CHILD, str(tmp_path / "home"))
	assert result.returncode == 0, result.stdout + result.stderr
	assert "lease drained" in result.stdout


def test_reentrant_release_is_rejected_without_partial_teardown(tmp_path: Path) -> None:
	result = _run_child(REENTRANT_RELEASE_CHILD, str(tmp_path / "home"))
	assert result.returncode == 0, result.stdout + result.stderr
	assert "reentrant release rejected" in result.stdout


def test_history_release_does_not_wait_for_main_callbacks(tmp_path: Path) -> None:
	result = _run_child(HISTORY_RELEASE_SCOPE_CHILD, str(tmp_path / "home"))
	assert result.returncode == 0, result.stdout + result.stderr
	assert "history callback scope isolated" in result.stdout


def test_save_preview_calls_racing_release_are_safe(tmp_path: Path) -> None:
	rom_path = tmp_path / "minimal.sfc"
	state_path = tmp_path / "preview.mss"
	_write_minimal_snes_rom(rom_path)
	for iteration in range(10):
		result = _run_child(
			SAVE_PREVIEW_RELEASE_CHILD,
			str(rom_path),
			str(tmp_path / f"home-{iteration}"),
			str(state_path),
			timeout=20,
		)
		assert result.returncode == 0, (
			f"iteration {iteration}: " + result.stdout + result.stderr
		)
		assert "save preview release safe" in result.stdout

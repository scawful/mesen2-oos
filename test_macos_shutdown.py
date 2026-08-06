"""Process-level regressions for macOS native-core shutdown."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest


pytestmark = pytest.mark.skipif(sys.platform != "darwin", reason="macOS shutdown regression")


PAUSED_EXIT_CHILD = r"""
import ctypes
import os
import sys
import time

core_path, rom_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitDll.restype = None
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
core.InitializeEmu.restype = None
core.LoadRom.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
core.LoadRom.restype = ctypes.c_bool
core.InitializeDebugger.argtypes = []
core.InitializeDebugger.restype = None
core.Pause.argtypes = []
core.Pause.restype = None
core.IsExecutionStopped.argtypes = []
core.IsExecutionStopped.restype = ctypes.c_bool

core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
assert core.LoadRom(rom_path.encode(), None), "ROM load failed"
core.InitializeDebugger()
core.Pause()

deadline = time.monotonic() + 5
while time.monotonic() < deadline and not core.IsExecutionStopped():
    time.sleep(0.005)
assert core.IsExecutionStopped(), "debugger did not pause"
print("paused", flush=True)

# Deliberately skip Release(). Process-exit destruction must stop and join the
# paused emulation thread before Emulator members are destroyed.
"""


FOREIGN_CALLBACK_EXIT_CHILD = r"""
import ctypes
import os
import sys

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitDll.restype = None
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p

core.InitDll()
abort_callback = callback_type(("abort", ctypes.CDLL(None)))
listener = core.RegisterNotificationCallback(abort_callback)
assert listener, "notification listener registration failed"

# Any callback during native process-exit cleanup aborts this child. Correct
# cleanup disables interop listeners before Emulator::Stop() sends
# BeforeGameUnload.
print("callback armed", flush=True)
"""


REENTRANT_CALLBACK_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, rom_path, home = sys.argv[1:]
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
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.Release.argtypes = []

class ExecuteShortcutParams(ctypes.Structure):
    _fields_ = [
        ("shortcut", ctypes.c_int),
        ("param", ctypes.c_uint32),
        ("param_ptr", ctypes.c_void_p),
    ]

core.ExecuteShortcut.argtypes = [ExecuteShortcutParams]
outer_callback_entered = threading.Event()
release_outer_callback = threading.Event()
nested_callback_seen = threading.Event()

def on_notification(notification_type, parameter):
    if notification_type == 18:  # BeforeGameLoad
        outer_callback_entered.set()
        assert release_outer_callback.wait(5), "outer callback release timed out"
    elif notification_type == 10:  # ExecuteShortcut
        nested_callback_seen.set()

callback = callback_type(on_notification)
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
assert core.RegisterNotificationCallback(callback)

loader = threading.Thread(target=lambda: core.LoadRom(rom_path.encode(), None))
loader.start()
assert outer_callback_entered.wait(5), "BeforeGameLoad callback did not start"

nested_returned = threading.Event()
nested = threading.Thread(
    target=lambda: (
        core.ExecuteShortcut(ExecuteShortcutParams(0, 0, None)),
        nested_returned.set(),
    )
)
nested.start()
returned_without_waiting = nested_returned.wait(2)
release_outer_callback.set()
nested.join()
loader.join()

assert returned_without_waiting, "nested notification waited for the outer callback"
assert nested_callback_seen.is_set(), "nested notification callback was not received"
core.Release()
print("nested callback returned", flush=True)
"""


EXIT_FROM_CALLBACK_CHILD = r"""
import ctypes
import os
import sys

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p

class ExecuteShortcutParams(ctypes.Structure):
    _fields_ = [
        ("shortcut", ctypes.c_int),
        ("param", ctypes.c_uint32),
        ("param_ptr", ctypes.c_void_p),
    ]

core.ExecuteShortcut.argtypes = [ExecuteShortcutParams]
libc = ctypes.CDLL(None)
libc.exit.argtypes = [ctypes.c_int]

def on_notification(notification_type, parameter):
    libc.exit(0)

callback = callback_type(on_notification)
core.InitDll()
assert core.RegisterNotificationCallback(callback)
core.ExecuteShortcut(ExecuteShortcutParams(0, 0, None))
raise AssertionError("exit returned")
"""


CROSS_THREAD_CALLBACK_EXIT_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p

class ExecuteShortcutParams(ctypes.Structure):
    _fields_ = [
        ("shortcut", ctypes.c_int),
        ("param", ctypes.c_uint32),
        ("param_ptr", ctypes.c_void_p),
    ]

core.ExecuteShortcut.argtypes = [ExecuteShortcutParams]
libc = ctypes.CDLL(None)
libc.exit.argtypes = [ctypes.c_int]
callback_entered = threading.Event()
hold_callback = threading.Event()

def on_notification(notification_type, parameter):
    callback_entered.set()
    hold_callback.wait(30)

callback = callback_type(on_notification)
core.InitDll()
assert core.RegisterNotificationCallback(callback)
worker = threading.Thread(
    target=lambda: core.ExecuteShortcut(ExecuteShortcutParams(0, 0, None))
)
worker.start()
assert callback_entered.wait(5), "callback did not start"
print("cross-thread callback active", flush=True)

# The atexit handler runs on this thread while the callback and its interop API
# are still active elsewhere. It must fail fast rather than waiting on a cycle
# in which that callback needs this thread.
libc.exit(0)
raise AssertionError("exit returned")
"""


EXIT_FROM_EMULATION_CALLBACK_CHILD = r"""
import ctypes
import os
import sys
import time

core_path, rom_path, home, target_notification, exit_label, enable_video = sys.argv[1:]
target_notification = int(target_notification)
enable_video = bool(int(enable_video))
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
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
libc = ctypes.CDLL(None)
libc.exit.argtypes = [ctypes.c_int]

def on_notification(notification_type, parameter):
    if notification_type == target_notification:
        print(exit_label, flush=True)
        libc.exit(0)

callback = callback_type(on_notification)
core.InitDll()
window_handle = ctypes.c_void_p(1) if enable_video else None
core.InitializeEmu(
    home.encode(), window_handle, window_handle, True, True, not enable_video, True
)
assert core.RegisterNotificationCallback(callback)
assert core.LoadRom(rom_path.encode(), None), "ROM load failed"
time.sleep(5)
raise AssertionError("emulation callback did not exit")
"""


UNREGISTER_THEN_EXIT_CALLBACK_CHILD = r"""
import ctypes
import os
import sys
import time

core_path, rom_path, home = sys.argv[1:]
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
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.UnregisterNotificationCallback.argtypes = [ctypes.c_void_p]
libc = ctypes.CDLL(None)
libc.exit.argtypes = [ctypes.c_int]
listener = None

def on_notification(notification_type, parameter):
    if notification_type == 7:  # PpuFrameDone
        print("unregister then exit", flush=True)
        core.UnregisterNotificationCallback(listener)
        libc.exit(0)

callback = callback_type(on_notification)
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
listener = core.RegisterNotificationCallback(callback)
assert listener
assert core.LoadRom(rom_path.encode(), None), "ROM load failed"
time.sleep(5)
raise AssertionError("emulation callback did not exit")
"""


UNREGISTER_DURING_CALLBACK_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, rom_path, home = sys.argv[1:]
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
callback_type = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)
core.RegisterNotificationCallback.argtypes = [callback_type]
core.RegisterNotificationCallback.restype = ctypes.c_void_p
core.UnregisterNotificationCallback.argtypes = [ctypes.c_void_p]
core.Release.argtypes = []

callback_entered = threading.Event()
release_callback = threading.Event()

def on_notification(notification_type, parameter):
    if notification_type == 18:  # BeforeGameLoad
        callback_entered.set()
        assert release_callback.wait(5), "callback release timed out"

callback = callback_type(on_notification)
core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
listener = core.RegisterNotificationCallback(callback)
assert listener, "notification listener registration failed"

load_result = []
loader = threading.Thread(
    target=lambda: load_result.append(core.LoadRom(rom_path.encode(), None))
)
loader.start()
assert callback_entered.wait(5), "BeforeGameLoad callback did not start"

unregistered = threading.Event()
unregister = threading.Thread(
    target=lambda: (core.UnregisterNotificationCallback(listener), unregistered.set())
)
unregister.start()
returned_without_waiting = unregistered.wait(2)
release_callback.set()
unregister.join()
loader.join()

assert returned_without_waiting, "unregister waited for an in-flight callback"
assert load_result == [True], "ROM load failed"
core.Release()
print("unregister returned", flush=True)
"""


CROSS_THREAD_RELEASE_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitDll.restype = None
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
core.InitializeEmu.restype = None
core.Release.argtypes = []
core.Release.restype = None

core.InitDll()
errors = []

def initialize_input():
    try:
        core.InitializeEmu(
            home.encode(),
            ctypes.c_void_p(1),
            ctypes.c_void_p(1),
            True,
            True,
            True,
            False,
        )
    except BaseException as exc:
        errors.append(exc)

worker = threading.Thread(target=initialize_input)
worker.start()
worker.join()
if errors:
    raise errors[0]

# This mirrors MainWindow: input initialization runs on a worker, while
# terminal release runs on the UI thread.
core.Release()
print("released", flush=True)
"""


CONCURRENT_RELEASE_CHILD = r"""
import ctypes
import os
import sys
import threading

core_path, home = sys.argv[1:]
os.makedirs(home, exist_ok=True)
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(home, "mesen.sock")

core = ctypes.CDLL(core_path)
core.InitDll.argtypes = []
core.InitDll.restype = None
core.InitializeEmu.argtypes = [
    ctypes.c_char_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
    ctypes.c_bool,
]
core.InitializeEmu.restype = None
core.Release.argtypes = []
core.Release.restype = None

core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
barrier = threading.Barrier(3)

def release():
    barrier.wait()
    core.Release()

threads = [threading.Thread(target=release) for _ in range(2)]
for thread in threads:
    thread.start()
barrier.wait()
for thread in threads:
    thread.join()
print("released twice", flush=True)
"""


def _core_path() -> Path:
    override = os.environ.get("MESEN2_CORE_PATH")
    if not override:
        if os.environ.get("CI"):
            pytest.fail("set MESEN2_CORE_PATH to the freshly built MesenCore.dylib")
        pytest.skip("run `make test-macos-shutdown` or set MESEN2_CORE_PATH")

    core_path = Path(override)
    if not core_path.is_file():
        pytest.fail(f"MESEN2_CORE_PATH does not exist: {core_path}")
    return core_path.resolve()


def _write_minimal_snes_rom(path: Path) -> None:
    rom = bytearray([0xEA] * 0x8000)
    rom[0:7] = bytes.fromhex("78 18 FB 4C 03 80 EA")
    rom[0x7FC0:0x7FE0] = bytes.fromhex(
        "4D 45 53 45 4E 20 53 48 55 54 44 4F 57 4E 20 54 "
        "45 53 54 20 20 20 00 05 00 01 33 00 4A 24 B5 DB"
    )
    rom[0x7FE4:0x8000] = bytes.fromhex(
        "00 80 00 80 EA EA 00 80 EA EA 00 80 EA EA EA EA "
        "00 80 00 80 EA EA 00 80 00 80 00 80"
    )
    path.write_bytes(rom)


def test_process_exit_while_debugger_is_paused(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(PAUSED_EXIT_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "paused" in result.stdout


def test_process_exit_disables_foreign_notification_callbacks(tmp_path: Path) -> None:
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(FOREIGN_CALLBACK_EXIT_CHILD),
            str(_core_path()),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "callback armed" in result.stdout


def test_callbacks_do_not_hold_lock_while_calling_foreign_code(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(REENTRANT_CALLBACK_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=15,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "nested callback returned" in result.stdout


def test_process_exit_from_callback_fails_fast_without_deadlock(tmp_path: Path) -> None:
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(EXIT_FROM_CALLBACK_CHILD),
            str(_core_path()),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 1, result.stdout + result.stderr


def test_process_exit_with_callback_active_on_other_thread_fails_fast(
    tmp_path: Path,
) -> None:
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(CROSS_THREAD_CALLBACK_EXIT_CHILD),
            str(_core_path()),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 1, result.stdout + result.stderr
    assert "cross-thread callback active" in result.stdout


def test_process_exit_from_emulation_callback_does_not_self_join(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(EXIT_FROM_EMULATION_CALLBACK_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
            "7",
            "exit on emulation thread",
            "0",
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 1, result.stdout + result.stderr
    assert "exit on emulation thread" in result.stdout


def test_process_exit_from_decoder_callback_does_not_self_join(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(EXIT_FROM_EMULATION_CALLBACK_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
            "8",
            "exit on decoder thread",
            "0",
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 1, result.stdout + result.stderr
    assert "exit on decoder thread" in result.stdout


def test_process_exit_from_renderer_callback_does_not_deadlock(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(EXIT_FROM_EMULATION_CALLBACK_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
            "22",
            "exit on renderer thread",
            "1",
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 1, result.stdout + result.stderr
    assert "exit on renderer thread" in result.stdout


def test_unregister_then_process_exit_from_callback_is_safe(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(UNREGISTER_THEN_EXIT_CALLBACK_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert result.returncode == 1, result.stdout + result.stderr
    assert "unregister then exit" in result.stdout


def test_unregister_does_not_wait_for_in_flight_callback(tmp_path: Path) -> None:
    rom_path = tmp_path / "minimal.sfc"
    _write_minimal_snes_rom(rom_path)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(UNREGISTER_DURING_CALLBACK_CHILD),
            str(_core_path()),
            str(rom_path),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=15,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "unregister returned" in result.stdout


def test_key_manager_release_across_run_loops(tmp_path: Path) -> None:
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            textwrap.dedent(CROSS_THREAD_RELEASE_CHILD),
            str(_core_path()),
            str(tmp_path / "home"),
        ],
        capture_output=True,
        text=True,
        timeout=15,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "released" in result.stdout


def test_concurrent_release_is_idempotent(tmp_path: Path) -> None:
    core_path = str(_core_path())
    for iteration in range(20):
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                textwrap.dedent(CONCURRENT_RELEASE_CHILD),
                core_path,
                str(tmp_path / f"home-{iteration}"),
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )

        assert result.returncode == 0, (
            f"iteration {iteration}: " + result.stdout + result.stderr
        )
        assert "released twice" in result.stdout

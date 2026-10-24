"""SPC binary save/load regression using a generated ROM and disposable core.

Run with MESEN2_CORE_PATH=/path/to/libMesenCore.dylib pytest -q
test_spc_savestate.py. No running emulator, game ROM, or existing save is used.
"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest


LUA_REGRESSION = r"""
local phase, steps = 0, 0
local saved, initial, continued

local function snapshot()
    local result = {state = {}, ram = {}, dsp = {}}
    for key, value in pairs(emu.getState()) do
        if string.sub(key, 1, 4) == "spc." then
            result.state[key] = value
        end
    end
    assert(result.state["spc.cpuRegs[0]"] ~= nil, "SPC state map missing")
    assert(emu.getMemorySize(emu.memType.spcRam) == 65536, "SPC RAM missing")
    assert(emu.getMemorySize(emu.memType.spcDspRegisters) == 128, "DSP missing")
    for address = 0, 65535 do
        result.ram[address] = emu.read(address, emu.memType.spcRam)
    end
    for address = 0, 127 do
        result.dsp[address] = emu.read(address, emu.memType.spcDspRegisters)
    end
    return result
end

local function compare(expected, actual, label)
    local errors = {}
    for _, section in ipairs({"state", "ram", "dsp"}) do
        for key, value in pairs(expected[section]) do
            if actual[section][key] ~= value then
                table.insert(errors, section .. "." .. tostring(key) .. ": " ..
                    tostring(value) .. " -> " .. tostring(actual[section][key]))
            end
        end
        for key, _ in pairs(actual[section]) do
            if expected[section][key] == nil then
                table.insert(errors, section .. "." .. tostring(key) .. ": unexpected")
            end
        end
    end
    table.sort(errors)
    assert(#errors == 0, label .. " mismatches (" .. #errors .. "):\n" ..
        table.concat(errors, "\n"))
end

local function check()
    if phase == 0 then
        -- Wait in emulated time for the IPL to finish its RAM clear.
        if emu.getState().frameCount < 3 then return end
        -- The generated main CPU program only loops. Leave the SPC IPL waiting
        -- for an upload, with explicit non-IPL ports and disabled timers.
        local fixture = {
            ["spc.timersDisabled"] = true,
            ["spc.timersEnabled"] = false,
        }
        for index = 0, 3 do
            fixture["spc.cpuRegs[" .. index .. "]"] = 0x31 + index
            fixture["spc.outputReg[" .. index .. "]"] = 0x61 + index
        end
        for index = 0, 2 do
            local timer = "spc.timer" .. index .. "."
            fixture[timer .. "enabled"] = false
            fixture[timer .. "timersEnabled"] = false
            fixture[timer .. "target"] = 0x21 + index
            fixture[timer .. "output"] = 5 + index
        end
        emu.setState(fixture)
        for key, value in pairs(fixture) do
            assert(emu.getState()[key] == value, "fixture not set: " .. key)
        end
        emu.write(0x8000, 0x5A, emu.memType.spcRam)
        emu.write(0x0C, 0x37, emu.memType.spcDspRegisters)

        -- Binary save catches SPC up to the main CPU. Snapshot AFTER that
        -- catch-up, before any further emulated instruction can execute.
        saved = emu.createSavestate()
        assert(type(saved) == "string" and #saved > 0, "empty binary savestate")
        initial = snapshot()
        assert(initial.state["spc.cpuRegs[0]"] == 0x31, "fixture did not survive save")
        assert(initial.state["spc.timersDisabled"] == true, "timer fixture lost")
        phase = 1
    elseif phase == 1 then
        -- Use a new main-CPU callback for load: save may run nested SPC
        -- callbacks, which clear the Lua API's save/load permission flag.
        -- Explicit mutations make a no-op load fail even if the SPC is idle.
        emu.setState({["spc.cpuRegs[0]"] = 0x99})
        emu.write(0x8000, 0xA5, emu.memType.spcRam)
        emu.write(0x0C, 0x73, emu.memType.spcDspRegisters)
        local mutated = snapshot()
        assert(mutated.state["spc.cpuRegs[0]"] ~= initial.state["spc.cpuRegs[0]"])
        assert(mutated.ram[0x8000] ~= initial.ram[0x8000])
        assert(mutated.dsp[0x0C] ~= initial.dsp[0x0C])
        assert(emu.loadSavestate(saved), "binary savestate load failed")
        compare(initial, snapshot(), "immediate binary restore")
        phase, steps = 2, 0
    elseif phase == 3 then
        assert(emu.loadSavestate(saved), "second binary load failed")
        compare(initial, snapshot(), "second binary restore")
        phase, steps = 4, 0
    else
        steps = steps + 1
        if steps == 512 then
            -- Catch up identically at the end of each continuation. This also
            -- exercises serialized instruction latches absent from getState().
            emu.createSavestate()
            if phase == 2 then
                continued = snapshot()
                assert(continued.state["spc.cycle"] > initial.state["spc.cycle"],
                    "SPC did not execute during continuation")
                phase = 3
            else
                compare(continued, snapshot(), "deterministic continuation")
                phase = 5
                emu.log("SPC_SAVELOAD_PASS: full SPC state, RAM, DSP and continuation")
                emu.breakExecution()
            end
        end
    end
end

emu.addMemoryCallback(function()
    if phase == 5 then return end
    local ok, err = pcall(check)
    if not ok then
        phase = 5
        emu.log("SPC_SAVELOAD_FAIL: " .. tostring(err))
        emu.breakExecution()
    end
end, emu.callbackType.exec, 0x8003, 0x8003, emu.cpuType.snes, emu.memType.snesMemory)
"""


CHILD = r"""
import ctypes
import os
from pathlib import Path
import sys
import tempfile
import time

core_path, rom_path, home, lua_path = sys.argv[1:]
os.makedirs(home, exist_ok=True)
# macOS pytest paths can exceed the Unix socket pathname limit. A private,
# short directory also prevents discovery or reuse of any existing emulator.
socket_dir = tempfile.TemporaryDirectory(prefix="mspc-", dir="/tmp")
os.environ["MESEN2_SOCKET_PATH"] = os.path.join(socket_dir.name, "mesen.sock")
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
core.LoadScript.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int32]
core.LoadScript.restype = ctypes.c_int32
core.GetScriptLog.argtypes = [ctypes.c_int32, ctypes.c_char_p, ctypes.c_uint32]
core.GetScriptLog.restype = None
core.RemoveScript.argtypes = [ctypes.c_int32]
core.RemoveScript.restype = None
core.Release.argtypes = []
core.Release.restype = None

core.InitDll()
core.InitializeEmu(home.encode(), None, None, True, True, True, True)
script_id = None
try:
    assert core.LoadRom(rom_path.encode(), None), "generated ROM load failed"
    core.InitializeDebugger()
    script_id = core.LoadScript(b"SPC save/load regression", lua_path.encode(),
        Path(lua_path).read_bytes(), -1)
    assert script_id >= 0, "Lua script load failed"
    log_buffer = ctypes.create_string_buffer(65536)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        core.GetScriptLog(script_id, log_buffer, len(log_buffer))
        log = log_buffer.value.decode("utf-8", errors="replace")
        if "SPC_SAVELOAD_PASS:" in log or "SPC_SAVELOAD_FAIL:" in log:
            break
        time.sleep(0.01)
    print(log, flush=True)
    assert "SPC_SAVELOAD_FAIL:" not in log, "SPC save/load changed the saved state"
    assert "SPC_SAVELOAD_PASS:" in log, "regression callback did not complete"
finally:
    if script_id is not None and script_id >= 0:
        core.RemoveScript(script_id)
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
    """32 KiB LoROM: SEI; CLC; XCE; JMP $8003, with valid reset vectors."""
    rom = bytearray([0xEA] * 0x8000)
    rom[:6] = bytes.fromhex("78 18 FB 4C 03 80")
    rom[0x7FC0:0x7FD5] = b"MESEN SPC STATE TEST "
    rom[0x7FD5:0x7FE0] = bytes.fromhex("20 00 05 00 01 33 00 00 00 FF FF")
    for offset in range(0x7FE0, 0x8000, 2):
        rom[offset:offset + 2] = bytes.fromhex("00 80")
    path.write_bytes(rom)


def test_binary_savestate_preserves_spc_and_continuation(tmp_path: Path) -> None:
    core_path = _core_path()
    rom_path = tmp_path / "spc-state.sfc"
    lua_path = tmp_path / "spc-state.lua"
    _write_loop_rom(rom_path)
    lua_path.write_text(LUA_REGRESSION)
    result = subprocess.run(
        [sys.executable, "-c", textwrap.dedent(CHILD), str(core_path),
         str(rom_path), str(tmp_path / "home"), str(lua_path)],
        capture_output=True,
        text=True,
        timeout=20,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "SPC_SAVELOAD_PASS:" in result.stdout

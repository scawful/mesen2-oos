"""SPC STOP/SLEEP save-state regression with an isolated synthetic ROM."""

from pathlib import Path
import subprocess
import sys
import textwrap

import pytest

from test_spc_savestate import CHILD, _core_path, _write_loop_rom


LUA_STOPSTATE = r"""
local phase, steps = 0, 0
local running, stopped, runningCycle, stoppedCycle
local function state() return emu.getState() end
local function cycle() return state()["spc.cycle"] end

local function check()
    if phase == 0 then
        if state().frameCount < 3 then return end
        -- Catch up before replacing IPL RAM even when loading a separate
        -- legacy fixture later; otherwise pending IPL writes race the setup.
        local fresh = emu.createSavestate()
        running = LEGACY_RUNNING or fresh
        runningCycle = cycle()
        phase = 1
    elseif phase == 1 then
        -- The IPL is waiting for a CPU upload. Replace its backing RAM with
        -- STOP (or SLEEP) and unmap the ROM so a real halt instruction runs.
        for address = 0xFFC0, 0xFFFF do
            emu.write(address, HALT_OPCODE, emu.memType.spcRam)
        end
        emu.setState({["spc.romEnabled"] = false})
        phase, steps = 2, 0
    elseif phase == 2 then
        steps = steps + 1
        if steps == 512 then
            stopped = emu.createSavestate()
            stoppedCycle = cycle()
            assert(stoppedCycle > runningCycle, "halt fixture never executed")
            phase, steps = 3, 0
        end
    elseif phase == 3 then
        steps = steps + 1
        if steps == 512 then
            emu.createSavestate()
            assert(cycle() == stoppedCycle, "SPC did not halt")
            emu.log("SPC_STOPSTATE_HALTED: opcode=" .. HALT_OPCODE)
            phase = 4
        end
    elseif phase == 4 then
        assert(emu.loadSavestate(running), "running binary load failed")
        if LEGACY_RUNNING then
            runningCycle = cycle()
        else
            assert(cycle() == runningCycle, "running cycle was not restored")
        end
        phase, steps = 5, 0
    elseif phase == 5 then
        steps = steps + 1
        if steps == 512 then
            emu.createSavestate()
            assert(cycle() > runningCycle, "SPC stayed halted after loading a running save")
            phase = 6
        end
    elseif phase == 6 then
        assert(emu.loadSavestate(stopped), "halted binary load failed")
        stoppedCycle = cycle()
        -- Unrelated partial Lua updates must preserve the loaded halt state.
        emu.setState({["spc.cpuRegs[0]"] = 0x43})
        phase, steps = 7, 0
    elseif phase == 7 then
        steps = steps + 1
        if steps == 512 then
            -- Force the same SPC catch-up as a normal binary save. If either
            -- restore or the partial map update resumed it, cycles advance.
            emu.createSavestate()
            assert(cycle() == stoppedCycle, "restored halted SPC executed")
            phase = 8
            emu.log("SPC_SAVELOAD_PASS: running and halted SPC roundtrip; partial map preserved")
            emu.breakExecution()
        end
    end
end

emu.addMemoryCallback(function()
    if phase == 8 then return end
    local ok, err = pcall(check)
    if not ok then
        phase = 8
        emu.log("SPC_SAVELOAD_FAIL: " .. tostring(err))
        emu.breakExecution()
    end
end, emu.callbackType.exec, 0x8003, 0x8003, emu.cpuType.snes, emu.memType.snesMemory)
"""


# Make a legacy fixture from this same core/ROM by removing only the new named
# field. This runs inside the disposable native child before its Lua test loads.
LEGACY_SETUP = r"""
    import struct
    import zlib

    core.SaveStateFile.argtypes = [ctypes.c_char_p]
    core.SaveStateFile.restype = None
    legacy_path = Path(home) / "legacy-running.mss"
    core.SaveStateFile(str(legacy_path).encode())
    raw = legacy_path.read_bytes()
    assert raw[:3] == b"MSS"
    assert struct.unpack_from("<I", raw, 7)[0] == 4, "unexpected state format"
    # MSS header, compressed video (five words), then the ROM filename.
    pos = 15 + 20 + struct.unpack_from("<I", raw, 31)[0]
    pos += 4 + struct.unpack_from("<I", raw, pos)[0]
    assert raw[pos] == 1, "expected compressed serializer"
    unpacked_size, packed_size = struct.unpack_from("<II", raw, pos + 1)
    fields = zlib.decompress(raw[pos + 9:pos + 9 + packed_size])
    assert len(fields) == unpacked_size
    kept, cursor, removed = bytearray(), 0, 0
    while cursor < len(fields):
        end = fields.index(0, cursor)
        key = fields[cursor:end]
        size = struct.unpack_from("<I", fields, end + 1)[0]
        next_field = end + 5 + size
        assert next_field <= len(fields)
        if key == b"spc.stopState":
            assert fields[end + 5:next_field] == b"\x00", "fixture SPC must be running"
            removed += 1
        else:
            kept.extend(fields[cursor:next_field])
        cursor = next_field
    assert removed in (0, 1), "duplicate stopState field"
    assert b"spc.stopState\x00" not in kept
    packed = zlib.compress(kept)
    legacy = raw[:pos] + b"\x01" + struct.pack("<II", len(kept), len(packed)) + packed
    legacy_path.write_bytes(legacy)
    # Decimal Lua string escapes transport the binary fixture without Lua IO.
    literal = "".join("\\%03d" % value for value in legacy)
    lua_file = Path(lua_path)
    lua_file.write_text('local LEGACY_RUNNING = "' + literal + '"\n' + lua_file.read_text())
    print("SPC_STOPSTATE_LEGACY: missing stopState key; removed=" + str(removed), flush=True)
"""


@pytest.mark.parametrize(
    "opcode,legacy",
    [(0xFF, False), (0xEF, False), (0xFF, True)],
    ids=["stop", "sleep", "legacy-running-after-stop"],
)
def test_spc_halt_state_roundtrip(tmp_path: Path, opcode: int, legacy: bool) -> None:
    core_path = _core_path()
    rom_path = tmp_path / "spc-halt.sfc"
    lua_path = tmp_path / "spc-halt.lua"
    _write_loop_rom(rom_path)
    lua_path.write_text(f"local HALT_OPCODE = {opcode}\n" + LUA_STOPSTATE)
    child = textwrap.dedent(CHILD)
    if legacy:
        anchor = "    core.InitializeDebugger()\n"
        assert child.count(anchor) == 1, "shared native harness initialization changed"
        child = child.replace(anchor, anchor + LEGACY_SETUP)
    result = subprocess.run(
        [sys.executable, "-c", child, str(core_path),
         str(rom_path), str(tmp_path / "home"), str(lua_path)],
        capture_output=True, text=True, timeout=20,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "SPC_STOPSTATE_HALTED:" in result.stdout
    if legacy:
        assert "SPC_STOPSTATE_LEGACY: missing stopState key" in result.stdout
    assert "SPC_SAVELOAD_PASS:" in result.stdout

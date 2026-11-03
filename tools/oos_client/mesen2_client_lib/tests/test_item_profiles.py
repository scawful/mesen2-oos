"""Profile-sensitive items use fake memory only; no emulator connection."""
import pytest
import sys

from mesen2_client_lib import cli, save_data_transaction
from mesen2_client_lib.client import OracleDebugClient
from mesen2_client_lib.constants import SAVEFILE_WRAM_START
from mesen2_client_lib.constants import ITEMS, OracleRAM
from mesen2_client_lib.save_data_profiles import ProfileRollbackError, SaveDataProfile, iter_profile_expectations
from mesen2_client_lib.save_data_transaction import apply_profile_transaction


class Bridge:
    def __init__(self, memory=None):
        self.memory = dict(memory or {})
        self.calls = []
        self.short_read = False

    def read_memory(self, addr, memtype=None):
        self.calls.append(("read", addr))
        return self.memory.get(addr, 0)

    def is_connected(self):
        return True

    def read_block(self, addr, length):
        self.calls.append(("read_block", addr))
        return b"" if self.short_read else bytes([self.memory.get(addr, 0)])

    def write_memory(self, addr, value, memtype=None):
        self.calls.append(("write", addr, value))
        self.memory[addr] = value
        return True


def client(profile=None, memory=None):
    result = OracleDebugClient.__new__(OracleDebugClient)
    result.item_profile = profile
    result.bridge = Bridge(memory)
    return result


@pytest.mark.parametrize("name", ["rings", "portalrod", "customrods"])
@pytest.mark.parametrize("method", ["get", "set"])
def test_missing_profile_refuses_before_memory_access(name, method):
    c = client()
    with pytest.raises(ValueError, match="requires --item-profile"):
        c.get_item(name) if method == "get" else c.set_item(name, 1)
    assert c.bridge.calls == []


@pytest.mark.parametrize("profile,address", [("default", 0x7EF3A2), ("legacy", 0x7EF3D8)])
def test_ring_mapping(profile, address):
    c = client(profile, {address: 7})
    assert c.get_item("rings")[0] == 7
    assert c.set_item("rings", 8)
    assert c.bridge.calls == [("read_block", address), ("write", address, 8)]


@pytest.mark.parametrize("before,value,after", [(0, 0, 0), (1, 0, 1), (2, 0, 1), (0, 1, 2), (1, 1, 2), (2, 1, 2)])
def test_legacy_rod_preserves_fishing(before, value, after):
    c = client("legacy", {0x7EF351: before})
    assert c.get_item("portalrod")[0] == int(before == 2)
    assert c.set_item("portalrod", value)
    assert c.bridge.memory[0x7EF351] == after


def test_default_rod_has_own_cell():
    c = client("default", {0x7EF3A6: 1})
    assert c.get_item("portalrod")[0] == 1
    assert c.set_item("portalrod", 0)
    assert c.bridge.memory[0x7EF3A6] == 0


@pytest.mark.parametrize("raw,short", [(3, False), (0, True)])
def test_legacy_bad_read_never_writes(raw, short):
    c = client("legacy", {0x7EF351: raw})
    c.bridge.short_read = short
    with pytest.raises(ValueError):
        c.set_item("portalrod", 1)
    assert all(call[0] != "write" for call in c.bridge.calls)


@pytest.mark.parametrize("dry_run", [False, True])
def test_transaction_preflights_all_items_before_writing(tmp_path, dry_run):
    c = client()
    p = SaveDataProfile(tmp_path / "p.json", {"items": {"flute": 1, "rings": 1}})
    with pytest.raises(ValueError, match="requires --item-profile"):
        apply_profile_transaction(c, p, persist=False, verify=False, dry_run=dry_run)
    assert c.bridge.calls == []


@pytest.mark.parametrize("value,initial,expected", [(0, 2, 1), (1, 1, 2)])
def test_legacy_transaction_verifies_decoded_rod(tmp_path, value, initial, expected):
    c = client("legacy", {0x7EF351: initial})
    p = SaveDataProfile(tmp_path / "p.json", {"items": {"rings": 4, "portalrod": value}})
    result = apply_profile_transaction(c, p, persist=False, verify=True)
    assert result["ok"] and result["wram_issues"] == []
    assert c.bridge.memory[0x7EF351] == expected
    assert c.bridge.memory[0x7EF3D8] == 4


def test_all_items_requires_profile_before_any_read():
    c = client()
    with pytest.raises(ValueError, match="requires --item-profile"):
        c.get_all_items()
    assert c.bridge.calls == []


def test_all_items_without_profile_items_skips_them():
    c = client()
    items = c.get_all_items(include_profile_items=False)
    assert "lamp" in items
    assert not {"rings", "portalrod", "customrods"} & items.keys()
    touched = {call[1] for call in c.bridge.calls}
    assert not {0x7EF3A2, 0x7EF3D8, 0x7EF3A6, 0x7EF351} & touched


@pytest.mark.parametrize("raw,value", [(0, 1), (1, 1), (2, 0)])
def test_legacy_conflicting_rod_profile_never_writes(tmp_path, raw, value):
    c = client("legacy")
    p = SaveDataProfile(tmp_path / "p.json", {"items": {"flute": 1, "customrods": raw, "portalrod": value}})
    with pytest.raises(ValueError, match="values conflict"):
        apply_profile_transaction(c, p, persist=False, verify=False)
    assert c.bridge.calls == []


@pytest.mark.parametrize("raw,value", [(0, 0), (1, 0), (2, 1)])
def test_legacy_persisted_rod_verification(tmp_path, monkeypatch, raw, value):
    from mesen2_client_lib.save_data_profiles import iter_profile_expectations

    blob = bytearray(0x500)
    blob[0x7EF351 - SAVEFILE_WRAM_START] = raw
    monkeypatch.setattr(save_data_transaction, "read_slot_saveblock", lambda *a, **k: bytes(blob))
    p = SaveDataProfile(tmp_path / "p.json", {"items": {"portalrod": value}})
    ops = iter_profile_expectations(p, "legacy")
    assert save_data_transaction._verify_sram_slot(client("legacy"), 1, ops) == []


def test_cli_passes_explicit_profile(monkeypatch, capsys):
    c = client("legacy", {0x7EF3D8: 9})
    seen = []
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: seen.append(kw) or c)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "legacy", "items", "rings"])
    cli.main()
    assert seen == [{"item_profile": "legacy"}]
    assert "value=9" in capsys.readouterr().out


def test_cli_missing_profile_reports_error(monkeypatch, capsys):
    c = client()
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda: c)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "give", "portalrod", "1"])
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 1
    assert "requires --item-profile" in capsys.readouterr().out
    assert c.bridge.calls == []


def test_cli_capture_without_profile_creates_nothing(tmp_path, monkeypatch, capsys):
    c = client()
    output = tmp_path / "new" / "profile.json"
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda: c)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "save-data", "profile-capture", "test", "--out", str(output)])
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 1
    assert "requires --item-profile" in capsys.readouterr().err
    assert not output.parent.exists()
    assert c.bridge.calls == []


def test_cli_apply_without_profile_does_not_pause(tmp_path, monkeypatch, capsys):
    c = client()
    p = SaveDataProfile(tmp_path / "p.json", {"items": {"flute": 1, "rings": 1}})
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda: c)
    monkeypatch.setattr(cli, "load_save_profile", lambda name: p)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "save-data", "profile-apply", "test"])
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 1
    assert "requires --item-profile" in capsys.readouterr().out
    assert c.bridge.calls == []


@pytest.mark.parametrize("name", ["goldstar", "goldstar_inventory_version"])
def test_legacy_goldstar_has_no_cell(name):
    c = client("legacy")
    with pytest.raises(ValueError, match="no cell in the legacy profile"):
        c.get_item(name)
    assert c.bridge.calls == []


@pytest.mark.parametrize("version,ok", [(0xA5, True), (0x00, False)])
def test_default_goldstar_write_needs_migrated_save(version, ok):
    c = client("default", {0x7EF3AA: version})
    if ok:
        assert c.set_item("goldstar", 1)
        assert c.bridge.memory[0x7EF3A9] == 1
    else:
        with pytest.raises(ValueError, match="0xA5 first"):
            c.set_item("goldstar", 1)
        assert all(call[0] != "write" for call in c.bridge.calls)


def test_goldstar_profile_writes_version_first(tmp_path):
    c = client("default", {0x7EF3AA: 0})
    p = SaveDataProfile(tmp_path / "p.json", {"items": {"flute": 1, "goldstar": 1, "goldstar_inventory_version": 0xA5}})
    result = apply_profile_transaction(c, p, persist=False, verify=True)
    assert result["ok"] and result["wram_issues"] == []
    writes = [call[1] for call in c.bridge.calls if call[0] == "write"]
    assert writes.index(0x7EF3AA) < writes.index(0x7EF3A9)


@pytest.mark.parametrize("items", [
    {"flute": 1, "goldstar": 1},
    {"flute": 1, "goldstar": 1, "goldstar_inventory_version": 0},
])
def test_unmigrated_goldstar_profile_never_writes(tmp_path, items):
    c = client("default", {0x7EF3AA: 0})
    p = SaveDataProfile(tmp_path / "p.json", {"items": items})
    with pytest.raises(ValueError, match="0xA5"):
        apply_profile_transaction(c, p, persist=False, verify=False)
    assert all(call[0] != "write" for call in c.bridge.calls)


def test_legacy_all_items_marks_goldstar_unavailable():
    c = client("legacy", {0x7EF3D8: 3})
    items = c.get_all_items()
    assert items["rings"]["value"] == 3
    for name in ("goldstar", "goldstar_inventory_version"):
        assert items[name] == {"value": None, "description": "no cell in the legacy profile"}
    assert 0x7EF3A9 not in {call[1] for call in c.bridge.calls}


def test_unmigrated_goldstar_read_is_refused_not_zero():
    # Legacy Hookshot level 2 becomes Goldstar ownership on the next menu open.
    c = client("default", {0x7EF342: 2, 0x7EF3AA: 0, 0x7EF3A9: 0})
    with pytest.raises(ValueError, match="not readable until"):
        c.get_item("goldstar")
    items = c.get_all_items()
    assert items["goldstar"]["value"] is None
    assert items["goldstar_inventory_version"]["value"] == 0


def test_capture_leaves_out_unavailable_items(tmp_path, monkeypatch):
    import json

    c = client("legacy", {0x7EF3D8: 3})
    output = tmp_path / "profile.json"
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: c)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "legacy", "save-data", "profile-capture", "t", "--out", str(output)])
    cli.main()
    items = json.loads(output.read_text())["items"]
    assert items["rings"] == 3
    assert "goldstar" not in items and "goldstar_inventory_version" not in items


def test_unmigrated_capture_does_not_clear_goldstar_on_replay(tmp_path, monkeypatch):
    import json

    source = client("default", {0x7EF342: 1, 0x7EF3AA: 0})
    output = tmp_path / "profile.json"
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: source)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "default", "save-data", "profile-capture", "t", "--out", str(output)])
    cli.main()
    data = json.loads(output.read_text())
    assert "goldstar" not in data["items"] and "goldstar_inventory_version" not in data["items"]

    target = client("default", {0x7EF342: 1, 0x7EF3A9: 1, 0x7EF3AA: 0xA5})
    apply_profile_transaction(target, SaveDataProfile(output, data), persist=False, verify=False)
    assert target.bridge.memory[0x7EF3AA] == 0xA5
    assert target.bridge.memory[0x7EF3A9] == 1


def _capture(tmp_path, monkeypatch, source, *extra):
    import json

    output = tmp_path / "profile.json"
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: source)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "default", "save-data", "profile-capture", "t", "--out", str(output), *extra])
    cli.main()
    return SaveDataProfile(output, json.loads(output.read_text()))


def test_nonzero_capture_does_not_skip_migration_on_replay(tmp_path, monkeypatch):
    profile = _capture(tmp_path, monkeypatch, client("default", {0x7EF342: 0, 0x7EF3A9: 0, 0x7EF3AA: 0xA5}), "--only-nonzero")
    assert "goldstar_inventory_version" not in profile.data["items"]

    target = client("default", {0x7EF342: 2, 0x7EF3A9: 0, 0x7EF3AA: 0})
    apply_profile_transaction(target, profile, persist=False, verify=False)
    assert target.bridge.memory[0x7EF3AA] == 0
    assert target.bridge.memory[0x7EF342] == 2


def test_version_only_profile_is_refused(tmp_path):
    profile = SaveDataProfile(tmp_path / "p.json", {"items": {"goldstar_inventory_version": 0xA5}})
    with pytest.raises(ValueError, match="needs goldstar"):
        iter_profile_expectations(profile, "default")


def test_failed_goldstar_write_rolls_back_the_version(tmp_path):
    target = client("default", {0x7EF342: 2, 0x7EF3A9: 0, 0x7EF3AA: 0})
    write = target.bridge.write_memory
    target.bridge.write_memory = lambda addr, value, memtype=None: False if addr == 0x7EF3A9 and value == 1 else write(addr, value, memtype)
    profile = SaveDataProfile(tmp_path / "p.json", {"items": {"goldstar": 1, "goldstar_inventory_version": 0xA5}})

    with pytest.raises(RuntimeError, match="Failed to set item: goldstar"):
        apply_profile_transaction(target, profile, persist=False, verify=False)
    assert target.bridge.memory == {0x7EF342: 2, 0x7EF3A9: 0, 0x7EF3AA: 0}


def _raise_on(bridge, addr):
    write = bridge.write_memory

    def write_memory(a, value, memtype=None):
        if a == addr:
            raise ConnectionError("socket closed")
        return write(a, value, memtype)

    bridge.write_memory = write_memory


def test_rollback_continues_past_a_raising_restore(tmp_path):
    target = client("default", {0x7EF342: 2, 0x7EF3A9: 0, 0x7EF3AA: 0})
    _raise_on(target.bridge, 0x7EF3A9)
    profile = SaveDataProfile(tmp_path / "p.json", {"items": {"goldstar": 1, "goldstar_inventory_version": 0xA5}})

    with pytest.raises(ProfileRollbackError, match="0x7EF3A9"):
        apply_profile_transaction(target, profile, persist=False, verify=False)
    assert target.bridge.memory[0x7EF3AA] == 0


def test_rollback_restores_the_boots_ability_bit(tmp_path):
    boots, flute = ITEMS["boots"][0], ITEMS["flute"][0]
    target = client("default", {boots: 0, OracleRAM.ABILITY_FLAGS: 0, flute: 0})
    write = target.bridge.write_memory
    target.bridge.write_memory = lambda addr, value, memtype=None: False if addr == flute and value == 1 else write(addr, value, memtype)
    profile = SaveDataProfile(tmp_path / "p.json", {"items": {"boots": 1, "flute": 1}})

    with pytest.raises(RuntimeError, match="Failed to set item: flute"):
        apply_profile_transaction(target, profile, persist=False, verify=False)
    assert target.bridge.memory == {boots: 0, OracleRAM.ABILITY_FLAGS: 0, flute: 0}


def test_cli_stays_paused_when_rollback_is_incomplete(tmp_path, monkeypatch):
    import json

    target = client("default", {0x7EF342: 2, 0x7EF3A9: 0, 0x7EF3AA: 0})
    _raise_on(target.bridge, 0x7EF3A9)
    paused = {"value": False}
    target.bridge.get_state = lambda: {"success": True, "data": {"paused": paused["value"]}}
    def pause():
        target.bridge.calls.append(("pause",))
        paused["value"] = True
        return True
    target.bridge.pause = pause
    target.bridge.resume = lambda: target.bridge.calls.append(("resume",))
    path = tmp_path / "p.json"
    path.write_text(json.dumps({"items": {"goldstar": 1, "goldstar_inventory_version": 0xA5}}))
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: target)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "default", "save-data", "profile-apply", str(path), "--no-persist", "--no-verify"])

    with pytest.raises(SystemExit):
        cli.main()
    assert ("pause",) in target.bridge.calls
    assert ("resume",) not in target.bridge.calls


def _profile_cli(tmp_path, monkeypatch, target, *extra):
    import json

    path = tmp_path / "pause-profile.json"
    path.write_text(json.dumps({"items": {"flute": 1}}))
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: target)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "default",
                                     "save-data", "profile-apply", str(path),
                                     "--no-persist", "--no-verify", *extra])


@pytest.mark.parametrize("state", [
    {"success": False},
    {"success": True, "data": {}},
    {"success": True, "data": {"paused": "false"}},
    {"success": True, "data": "not-json"},
])
def test_cli_refuses_unknown_pause_state_before_writes(tmp_path, monkeypatch, state):
    target = client("default")
    target.bridge.get_state = lambda: state
    target.bridge.pause = lambda: target.bridge.calls.append(("pause",))
    target.bridge.resume = lambda: target.bridge.calls.append(("resume",))
    _profile_cli(tmp_path, monkeypatch, target)
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 1
    assert not any(call[0] in ("pause", "resume", "write") for call in target.bridge.calls)


@pytest.mark.parametrize("pause_result", [False, True])
def test_cli_refuses_unconfirmed_pause_before_writes(tmp_path, monkeypatch, pause_result):
    target = client("default")
    target.bridge.get_state = lambda: {"success": True, "data": {"paused": False}}
    target.bridge.pause = lambda: (target.bridge.calls.append(("pause",)), pause_result)[1]
    target.bridge.resume = lambda: target.bridge.calls.append(("resume",))
    _profile_cli(tmp_path, monkeypatch, target)
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 1
    assert ("pause",) in target.bridge.calls
    assert not any(call[0] in ("resume", "write") for call in target.bridge.calls)


@pytest.mark.parametrize("initially_paused", [False, True])
def test_cli_resumes_only_its_own_confirmed_pause(tmp_path, monkeypatch, initially_paused):
    target = client("default")
    paused = {"value": initially_paused}
    target.bridge.get_state = lambda: {"success": True, "data": {"paused": paused["value"]}}
    def pause():
        target.bridge.calls.append(("pause",))
        paused["value"] = True
        return True
    target.bridge.pause = pause
    target.bridge.resume = lambda: target.bridge.calls.append(("resume",))
    _profile_cli(tmp_path, monkeypatch, target)
    cli.main()
    assert any(call[0] == "write" for call in target.bridge.calls)
    assert (("pause",) in target.bridge.calls) is not initially_paused
    assert (("resume",) in target.bridge.calls) is not initially_paused


def test_cli_dry_run_does_not_require_or_change_pause(tmp_path, monkeypatch):
    target = client("default")
    target.bridge.get_state = lambda: (_ for _ in ()).throw(AssertionError("unexpected state query"))
    target.bridge.pause = lambda: target.bridge.calls.append(("pause",))
    target.bridge.resume = lambda: target.bridge.calls.append(("resume",))
    _profile_cli(tmp_path, monkeypatch, target, "--dry-run")
    cli.main()
    assert not any(call[0] in ("pause", "resume", "write") for call in target.bridge.calls)


def test_cli_refuses_to_write_when_pause_fails(tmp_path, monkeypatch, capsys):
    import json

    target = client("default", {0x7EF342: 2, 0x7EF3A9: 0, 0x7EF3AA: 0})
    _raise_on(target.bridge, 0x7EF3A9)
    target.bridge.get_state = lambda: {"success": True, "data": {"paused": False}}
    target.bridge.pause = lambda: target.bridge.calls.append(("pause",)) or False
    target.bridge.resume = lambda: target.bridge.calls.append(("resume",))
    path = tmp_path / "p.json"
    path.write_text(json.dumps({"items": {"goldstar": 1, "goldstar_inventory_version": 0xA5}}))
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    monkeypatch.setattr(cli, "OracleDebugClient", lambda **kw: target)
    monkeypatch.setattr(sys, "argv", ["mesen2_client.py", "--item-profile", "default", "save-data", "profile-apply", str(path), "--no-persist", "--no-verify"])

    with pytest.raises(SystemExit):
        cli.main()
    out = capsys.readouterr().out
    assert not [c for c in target.bridge.calls if c[0] == "write"]
    assert "left paused" not in out
    assert ("resume",) not in target.bridge.calls

"""Profile-sensitive items use fake memory only; no emulator connection."""
import pytest
import sys

from mesen2_client_lib import cli, save_data_transaction
from mesen2_client_lib.client import OracleDebugClient
from mesen2_client_lib.constants import SAVEFILE_WRAM_START
from mesen2_client_lib.save_data_profiles import SaveDataProfile
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

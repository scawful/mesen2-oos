"""Entrance-loader contracts; memory-only, no socket or emulator."""
from unittest.mock import Mock
import pytest

# OracleCheats uses the loaded bus bytes, independent of the debug-warp path above.
from mesen2_client_lib.oracle_cheats import OracleCheats, CheatsError


class EntranceROMBridge:
    def __init__(self, expanded=False, outside=0x87):
        self.memory = {}
        self.reads = []
        self.writes = []
        self.put(0x02D8B3, bytes.fromhex("8B 4B AB"))
        self.put(0x02D99F, bytes.fromhex("AA 0A 0A A8 BD 13 C8"))
        self.put(0x02C813 + 0x29 * 2, bytes.fromhex("60 00"))
        self.put(0x0F8000 + 0x29 * 2, bytes.fromhex("90 00"))
        if expanded:
            self.put(0x0FF000, bytes.fromhex("00 01 00 00 00 00 00 00 AA 0A 0A A8 E2 20 A9 0F 48 AB C2 20 6B 29 0F 85 AA A9 02 48 AB 6B"))
            self.put(0x02D99F, bytes.fromhex("22 08 F0 0F"))
            self.put(0x02DACE, bytes.fromhex("22 15 F0 0F"))
            for site, operand in ((0xD9A3, 0x8000), (0xD9AB, 0x8C00), (0xD9B8, 0x8A00),
                                  (0xD9CB, 0x8E00), (0xD9D0, 0x9000), (0xD9D5, 0x9200),
                                  (0xD9E0, 0x9400), (0xD9F0, 0x9D00), (0xDA74, 0x9600),
                                  (0xDA7A, 0x9F00), (0xDA91, 0x9700), (0xDA96, 0x9800), (0xDA9C, 0x9900),
                                  (0xDAA1, 0x9A00), (0xDAAA, 0x9A00), (0xDAB2, 0x9B00),
                                  (0xDABB, 0x9B00), (0xDAC2, 0x9C00), (0xDACB, 0x9C00)):
                self.put(0x020000 | site, b"\xBD" + operand.to_bytes(2, "little"))
            for i in range(8):
                self.put(0x02DA1A + i * 6, b"\xB9" + (0x8200 + i).to_bytes(2, "little"))
        # Native backward scan chooses the last matching exit row, not a label.
        self.put(0x02DD8A + 0x41 * 2, bytes.fromhex("90 00"))
        self.put(0x02DE28 + 0x41, bytes([0x7A]))
        self.put(0x02DD8A + 0x42 * 2, bytes.fromhex("90 00"))
        self.put(0x02DE28 + 0x42, bytes([outside]))
        self.put(0x02DD8A + 0x43 * 2, bytes.fromhex("60 00"))
        self.put(0x02DE28 + 0x43, bytes([0x33]))

    def put(self, address, raw):
        self.memory.update((address + i, value) for i, value in enumerate(raw))

    def read_block(self, address, size):
        self.reads.append((address, size))
        return bytes(self.memory.get(address + i, 0) for i in range(size))


def entrance_cheats(bridge):
    cheats = OracleCheats.__new__(OracleCheats)
    cheats.b = bridge
    return cheats


def test_legacy_entrance_uses_loader_selected_room_and_actual_exit():
    bridge = EntranceROMBridge()
    assert entrance_cheats(bridge)._entrance_destination({"id": "0x29", "area": "0x7A"}) == (0x60, 0x33)
    assert bridge.writes == []


@pytest.mark.parametrize("outside", [0x7A, 0x83, 0x87])
def test_expanded_entrance_ignores_stale_legacy_room_and_label(outside):
    bridge = EntranceROMBridge(expanded=True, outside=outside)
    assert entrance_cheats(bridge)._entrance_destination({"id": "0x29", "area": "0x7A"}) == (0x90, outside)
    assert (0x02C813 + 0x29 * 2, 2) not in bridge.reads
    assert bridge.writes == []


@pytest.mark.parametrize("expanded,address,raw", [
    (False, 0x02D9A3, b"\xAD"),       # wrong indexed load opcode
    (False, 0x02D9A4, b"\x00\x80"),  # unrecognized table with no DBR hook
    (True, 0x02D9A3, b"\xAD"),
    (True, 0x02DACE, b"\xEA"),       # cannot restore DBR
    (True, 0x0FF010, b"\x02"),       # helper selects wrong data bank
    (True, 0x02DA74, b"\xBD\x81\xD3"), # mixed expanded/legacy layout
    (True, 0x0FF000, b"\x00\x00"),  # recognized hook but marker missing
])
def test_unknown_or_inconsistent_loader_refuses_before_room_read(expanded, address, raw):
    bridge = EntranceROMBridge(expanded=expanded)
    bridge.put(address, raw)
    with pytest.raises(CheatsError, match="Unsupported or malformed"):
        entrance_cheats(bridge)._entrance_destination({"id": "0x29"})
    assert all(addr not in (0x02C865, 0x0F8052) for addr, size in bridge.reads)
    assert bridge.writes == []


def test_entrance_loader_short_read_refuses_without_controls():
    bridge = EntranceROMBridge(expanded=True)
    bridge.read_block = Mock(return_value=b"\x00")
    with pytest.raises(CheatsError, match="Could not read"):
        entrance_cheats(bridge)._entrance_destination({"id": "0x29"})
    assert bridge.writes == []


@pytest.mark.parametrize("address,size", [(0x02D8B3, 543), (0x0FF000, 31), (0x0F8052, 2)])
def test_each_loader_or_room_short_read_refuses(address, size):
    bridge = EntranceROMBridge(expanded=True)
    original = bridge.read_block
    def shortened(addr, count):
        data = original(addr, count)
        return data[:-1] if addr == address else data
    bridge.read_block = shortened
    with pytest.raises(CheatsError, match="Could not read"):
        entrance_cheats(bridge)._entrance_destination({"id": "0x29"})
    assert bridge.writes == []


@pytest.mark.parametrize("eid", [-1, 0x85])
def test_invalid_public_entrance_refuses_before_reads(eid):
    bridge = EntranceROMBridge(expanded=True)
    with pytest.raises(CheatsError, match="Entrance must"):
        entrance_cheats(bridge)._entrance_destination({"id": eid})
    assert bridge.reads == []
    assert bridge.writes == []

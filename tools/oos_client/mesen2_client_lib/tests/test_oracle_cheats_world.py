"""Exercise actual warp code with mocked memory/load completion, never a socket."""

import pytest

from mesen2_client_lib import oracle_cheats as oc


class MemoryBridge:
    def __init__(self, world=0):
        self.memory = {}
        self.writes = []
        self.loaded_worlds = []
        self.saved = []
        self.paused = True
        for address in range(0x02DD8A, 0x02DE77):
            self.memory[address] = 0xFF
        for address in range(0x1BB96F, 0x1BBBF4):
            self.memory[address] = 0xFF
        for address in range(0x1BB800, 0x1BB85F):
            self.memory[address] = 0xFF
        self.memory.update({oc.MODULE: 9, oc.AREA: 0x40 if world else 0x33,
                            oc.SAVED_WORLD: world, oc.WORLD_FLAG: int(world != 0),
                            0x7E02B2: 8, 0x7E00BC: 0x42})

    def seed_word(self, address, value):
        self.memory[address] = value & 0xFF
        self.memory[address + 1] = value >> 8

    def seed_exit(self, index, room, area):
        self.seed_word(0x02DD8A + index * 2, room)
        self.memory[0x02DE28 + index] = area

    def seed_door(self, index, entrance, area):
        self.seed_word(0x1BB96F + index * 2, area)
        self.seed_word(0x1BBA71 + index * 2, 0x1234)
        self.memory[0x1BBB73 + index] = entrance

    def seed_hole(self, index, entrance, area, map16=0x011E):
        self.seed_word(0x1BB800 + index * 2, map16)
        self.seed_word(0x1BB826 + index * 2, area)
        self.memory[0x1BB84C + index] = entrance

    def read_memory(self, address):
        return self.memory.get(address, 0)

    def read_memory16(self, address):
        return self.read_memory(address) | (self.read_memory(address + 1) << 8)

    def read_block(self, address, size, **kwargs):
        return bytes(self.read_memory(address + i) for i in range(size))

    def write_block(self, address, data, memtype=None):
        assert memtype == "sram"
        self.saved.append((address, bytes(data)))
        return True

    def write_memory(self, address, value):
        assert self.paused and 0x7E0000 <= address <= 0x7EFFFF
        self.writes.append((address, value))
        self.memory[address] = value
        if address == oc.MODULE:
            self.loaded_worlds.append(self.read_memory(oc.SAVED_WORLD))
            # Simulate only completion of the selected native loader. World/form
            # initialization is left entirely to the production code under test.
            if value == 8:
                room = self.read_memory16(oc.ROOM)
                area = next(self.read_memory(0x02DE28 + i)
                            for i in range(0x4E, -1, -1)
                            if self.read_memory16(0x02DD8A + i * 2) == room)
                self.memory.update({oc.MODULE: 9, oc.AREA: area, oc.INDOORS: 0})
            elif value in (5, 15):
                room = self.read_memory16(0x02C813 + self.read_memory16(oc.ENTRANCE) * 2)
                self.seed_word(oc.ROOM, room)
                self.memory.update({oc.MODULE: 7, oc.INDOORS: 1})
        return True

    def pause(self):
        self.paused = True
        return True

    def resume(self):
        self.paused = False
        return True

    def send_command(self, command):
        assert command == "STATE"
        return {"data": {"paused": self.paused}}


@pytest.fixture
def make_cheats(monkeypatch, tmp_path):
    monkeypatch.setattr(oc.time, "sleep", lambda _: None)

    def make(world=0):
        bridge = MemoryBridge(world)
        cheats = oc.OracleCheats(bridge, root=tmp_path)
        cheats._warps = {"entrances": [], "areas": []}
        return bridge, cheats

    return make


def add_entrance(cheats, entrance=3, room=0x76, area=0x4B):
    cheats._warps["entrances"].append({
        "id": hex(entrance), "label": "fixture", "name": "fixture", "group": "test",
        "room": hex(room), "area": hex(area), "exit": "table"})


def add_area(cheats, area=0x40, room=0x20):
    cheats._warps["areas"].append({
        "area": hex(area), "name": "fixture", "via_room": hex(room), "landing": "test"})


def assert_world(bridge, world):
    assert bridge.read_memory(oc.SAVED_WORLD) == world
    assert bridge.read_memory(oc.WORLD_FLAG) == int(world != 0)
    assert all(value == world for value in bridge.loaded_worlds)
    assert not any(address in (0x7E02B2, 0x7E00BC) for address, _ in bridge.writes)


@pytest.mark.parametrize("start,world,area", [(0, 0x40, 0x40), (0x40, 0, 0x33)])
def test_area_initializes_world_before_loader(make_cheats, start, world, area):
    bridge, cheats = make_cheats(start)
    add_area(cheats, area)
    bridge.seed_exit(2, 0x20, area)
    assert cheats.warp_area(hex(area))["ok"]
    assert_world(bridge, world)


@pytest.mark.parametrize("method", ["auto", "door", "reload"])
def test_cross_world_dungeon_uses_full_reload(make_cheats, method):
    bridge, cheats = make_cheats()
    add_entrance(cheats)
    bridge.seed_word(0x02C819, 0x76)
    bridge.seed_exit(2, 0x76, 0x4B)
    result = cheats.warp_entrance("0x03", method)
    assert result["ok"]
    assert result["method"] == "reload"
    assert (oc.MODULE, 5) in bridge.writes
    assert_world(bridge, 0x40)


def test_loaded_rom_entrance_overrides_stale_catalog(make_cheats):
    bridge, cheats = make_cheats(0x40)
    add_entrance(cheats, 0x68, 0x11E, 0x35)
    bridge.seed_word(0x02C8E3, 0x0F)
    bridge.seed_exit(2, 0x0F, 0x1B)
    assert cheats.warp_entrance("0x68")["ok"]
    assert_world(bridge, 0)


@pytest.mark.parametrize("area", [0x40, 0xA0])
def test_stale_exit_or_special_area_is_rejected_before_writes(make_cheats, area):
    bridge, cheats = make_cheats()
    add_area(cheats, area)
    bridge.seed_exit(2, 0x20, area)
    bridge.seed_exit(5, 0x20, 0x43)  # Native reverse scan takes this later record.
    with pytest.raises(oc.CheatsError):
        cheats.warp_area(hex(area))
    assert bridge.writes == []


def test_special_world_entrance_preserves_world(make_cheats):
    bridge, cheats = make_cheats(0x40)
    add_entrance(cheats, 4, 0x88, 0x80)
    bridge.seed_word(0x02C81B, 0x88)
    bridge.seed_door(2, 4, 0x80)
    assert cheats.warp_entrance("0x04")["ok"]
    assert not any(address in (oc.SAVED_WORLD, oc.WORLD_FLAG) for address, _ in bridge.writes)


@pytest.mark.parametrize("world", [0, 0x40])
def test_house_primes_destination_area_even_in_same_world(make_cheats, world):
    bridge, cheats = make_cheats(world)
    add_entrance(cheats, 0x6B, 0x11F, 0x10)
    add_area(cheats, 0x10, 0x60)
    bridge.seed_word(0x02C8E9, 0x11F)
    bridge.seed_door(2, 0x6B, 0x10)
    bridge.seed_exit(2, 0x60, 0x10)
    assert cheats.warp_entrance("0x6B")["ok"]
    assert (oc.MODULE, 8) in bridge.writes
    assert_world(bridge, 0)


@pytest.mark.parametrize("warp", [True, False])
def test_checkpoint_saves_destination_before_reload_and_preserves_no_warp(make_cheats, warp):
    bridge, cheats = make_cheats()
    add_entrance(cheats)
    bridge.seed_word(0x02C819, 0x76)
    bridge.seed_exit(2, 0x76, 0x4B)
    cheats._checkpoints = {"checkpoints": [{
        "id": "eon", "location": {"entrance": "0x03"},
        "writes": [{"addr": "0x7EF3CA", "value": "0x00"}]}]}
    assert cheats.apply_checkpoint("eon", warp=warp)["ok"]
    assert len(bridge.saved) == 2
    assert bridge.saved[0][1][0x3CA] == (0x40 if warp else 0)
    if warp:
        assert_world(bridge, 0x40)
        assert (oc.MODULE, 5) in bridge.writes
    else:
        assert not any(address == oc.WORLD_FLAG for address, _ in bridge.writes)


def seed_room_zero_hole(bridge, hole_area=0x57):
    # Entrance $7B: room $0000, whose zero-filled unused exit rows read area $00.
    bridge.seed_word(0x02C813 + 0x7B * 2, 0x0000)
    bridge.seed_exit(0x3E, 0x0000, 0x00)
    bridge.seed_exit(0x46, 0x0000, 0x00)
    if hole_area is not None:
        for index, map16 in enumerate((0x011E, 0x019E, 0x01A0, 0x0120)):
            bridge.seed_hole(index, 0x7B, hole_area, map16)


@pytest.mark.parametrize("warp", ["entrance", "checkpoint"])
def test_room_zero_hole_entrance_uses_hole_area_world(make_cheats, warp):
    bridge, cheats = make_cheats()
    seed_room_zero_hole(bridge)
    if warp == "entrance":
        result = cheats.warp_entrance("0x7B")
    else:
        cheats._checkpoints = {"checkpoints": [{"id": "kyd", "location": {"entrance": "0x7B"}, "writes": []}]}
        result = cheats.apply_checkpoint("kyd")
        assert bridge.saved[0][1][0x3CA] == 0x40
    assert result["ok"]
    assert (oc.MODULE, 5) in bridge.writes
    assert bridge.read_memory16(oc.ROOM) == 0
    assert_world(bridge, 0x40)


def test_room_zero_without_door_or_hole_keeps_exit_row(make_cheats):
    bridge, cheats = make_cheats(0x40)
    seed_room_zero_hole(bridge, hole_area=None)
    assert cheats.warp_entrance("0x7B")["ok"]
    assert_world(bridge, 0)


def test_door_table_resolves_before_hole_table(make_cheats):
    bridge, cheats = make_cheats(0x40)
    bridge.seed_word(0x02C813 + 5 * 2, 0x0055)  # No exit-table row for this room.
    bridge.seed_door(2, 5, 0x10)
    bridge.seed_hole(0, 5, 0x50)
    assert cheats.warp_entrance("0x05")["ok"]
    assert_world(bridge, 0)

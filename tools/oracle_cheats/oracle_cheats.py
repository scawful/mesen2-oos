"""Oracle of Secrets cheats and warps over the Mesen2-OOS socket.

Data: <oracle-of-secrets>/Data/debug/warps.json and checkpoints.json (see that README).
CLI:  mesen2_client.py checkpoint | warp-entrance | warp-area (registered by cli.py).
The Mesen2-OOS panel implements the same steps in UI/Utilities/OracleCheats.cs.

Warp paths (all verified headless on oos-v0.9.0-b17):
  door   From overworld play (module $09): the vanilla door entry that FindEntrance
         starts ($010E = entrance, $10 = $0F, $010C = $06; usdasm bank_1B.asm:1060).
         Underworld_LoadEntrance saves the current overworld spot, so leaving a
         house returns Link to where he was standing.
  reload From a dungeon/cave/special area: Module05_LoadFile with $010A = 1 and
         $1B = 1 (the death-continue path), which loads $010E without touching the
         overworld cache.
  area   Module08 with $A0 = a room from UnderworldExitData (the dungeon-exit path;
         Kydrog's banish uses the same with room $20).
Rooms $0100-$017F (houses, shops) exit to the cached overworld spot, not to a table
entry. Entering one from indoors first does an area warp to its door's area, then
the door path, so the cache is valid.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

MODULE = 0x7E0010
SUBMODULE = 0x7E0011
INDOORS = 0x7E001B
AREA = 0x7E008A
ROOM = 0x7E00A0          # 16-bit
BRIGHTNESS = 0x7E0013
HDMA_ENABLE = 0x7E009B
SUBSUB = 0x7E00B0
LINK_Y = 0x7E0020
LINK_X = 0x7E0022
ENTRANCE = 0x7E010E      # 16-bit
CONTINUE_FLAG = 0x7E010A
NEXT_MODULE = 0x7E010C
RESPAWN_FLAG = 0x7E04AA
FOLLOWER = 0x7EF3CC
SAVED_WORLD = 0x7EF3CA
WORLD_FLAG = 0x7E0FFF
SAVE_START = 0x7EF000
SAVE_SIZE = 0x500
SRAM_ACTIVE_SLOT = 0x1FFE

MOD_DUNGEON = 0x07
MOD_OVERWORLD = 0x09
MOD_SPECIAL = 0x0B
MOD_MESSAGE = 0x0E   # text box / menu; a hint can open right after a warp lands
PLAY_MODULES = (MOD_DUNGEON, MOD_OVERWORLD, MOD_SPECIAL)
# Oracle patches the old-man follower check in Underworld_LoadEntrance to follower $02
# (Sprites/NPCs/followers.asm:613); with it the game loads the spawn point instead.
SPAWN_FOLLOWER = 0x02

COMMANDS = ("checkpoint", "warp-entrance", "warp-area")


def oracle_root() -> Path:
    env = os.environ.get("OOS_ROOT") or os.environ.get("ORACLE_OF_SECRETS_ROOT")
    if env:
        return Path(env).expanduser().resolve()
    try:
        from .paths import REPO_ROOT  # type: ignore
        return REPO_ROOT
    except Exception:
        for parent in Path(__file__).resolve().parents:
            if (parent / "oracle-of-secrets" / "Data").is_dir():
                return parent / "oracle-of-secrets"
        return Path.home() / "src" / "hobby" / "oracle-of-secrets"


def hexint(value: Any) -> int:
    if isinstance(value, int):
        return value
    return int(str(value).strip().replace("$", ""), 16)


def load_json(name: str, root: Path | None = None) -> dict:
    path = (root or oracle_root()) / "Data" / "debug" / name
    if not path.is_file():
        raise FileNotFoundError(f"{path} not found (set OOS_ROOT to the oracle-of-secrets checkout)")
    return json.loads(path.read_text())


# ---------------------------------------------------------------- data lookups

def resolve_checkpoint(data: dict, cp_id: str) -> dict:
    """Return {checkpoint, chain, writes, location, expect} with 'after' resolved oldest first."""
    by_id = {c["id"]: c for c in data.get("checkpoints", [])}
    if cp_id not in by_id:
        todo = {t["id"]: t for t in data.get("todo", [])}
        if cp_id in todo:
            raise KeyError(f"checkpoint '{cp_id}' is a TODO: {todo[cp_id].get('reason', '')}")
        raise KeyError(f"unknown checkpoint '{cp_id}'")
    chain: list[dict] = []
    cur: dict | None = by_id[cp_id]
    seen: set[str] = set()
    while cur is not None:
        if cur["id"] in seen:
            raise ValueError(f"checkpoint chain loops at '{cur['id']}'")
        seen.add(cur["id"])
        chain.append(cur)
        parent = cur.get("after")
        cur = by_id.get(parent) if parent else None
        if parent and cur is None:
            raise KeyError(f"checkpoint '{chain[-1]['id']}' is after unknown '{parent}'")
    chain.reverse()
    writes = [w for c in chain for w in c.get("writes", [])]
    cp = by_id[cp_id]
    return {"checkpoint": cp, "chain": [c["id"] for c in chain], "writes": writes,
            "location": cp.get("location", {}), "expect": cp.get("expect", {})}


def apply_write(current: int, write: dict) -> int:
    if "value" in write:
        return hexint(write["value"]) & 0xFF
    if "set_bits" in write:
        return (current | hexint(write["set_bits"])) & 0xFF
    if "clear_bits" in write:
        return current & ~hexint(write["clear_bits"]) & 0xFF
    raise ValueError(f"write for {write.get('addr')} has no value/set_bits/clear_bits")


def find_entrance(warps: dict, query: str) -> dict:
    entrances = warps.get("entrances", [])
    q = query.strip().lower()
    # Ids are hex: "0x26", "$26", or bare digits ("26"). "d1" is a name, not $D1.
    try:
        eid = hexint(q) if (q.startswith(("0x", "$")) or (q.isdigit() and len(q) <= 2)) else None
    except ValueError:
        eid = None
    if eid is not None:
        for e in entrances:
            if hexint(e["id"]) == eid:
                return e
        return {"id": f"0x{eid:02X}", "name": f"Entrance 0x{eid:02X}", "label": f"Entrance 0x{eid:02X}",
                "group": "unknown", "room": None, "exit": "unknown"}
    exact = [e for e in entrances if q in (e["label"].lower(), e["name"].lower())]
    if len(exact) == 1:
        return exact[0]
    words = q.split()
    hits = [e for e in entrances
            if all(w in f"{e['label']} {e['name']} {e['group']} {e.get('area_name', '')}".lower() for w in words)]
    # "d1" style shortcuts match the dungeon label prefix.
    prefix = [e for e in hits if e["label"].lower().startswith(q)]
    if len(prefix) == 1:
        return prefix[0]
    if len(prefix) > 1:
        # "d3" -> the main door; "d8" -> the Fortress, not the Final Boss Route.
        def score(e: dict) -> tuple[bool, bool]:
            label = e["label"].lower()
            return ("(main)" in label, "(" not in label and "route" not in label)
        ranked = sorted(prefix, key=score, reverse=True)
        if score(ranked[0]) > score(ranked[1]):
            return ranked[0]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise KeyError(f"no entrance matches '{query}'")
    names = ", ".join(f"{e['id']} {e['label']}" for e in hits[:12])
    raise KeyError(f"'{query}' matches {len(hits)} entrances: {names}")


def find_area(warps: dict, query: str) -> dict:
    areas = warps.get("areas", [])
    q = query.strip().lower()
    try:
        area_id = hexint(q)
        for a in areas:
            if hexint(a["area"]) == area_id:
                return a
        raise KeyError(f"area 0x{area_id:02X} has no exit-table room; warpable areas: "
                       + " ".join(a["area"] for a in areas))
    except ValueError:
        pass
    hits = [a for a in areas if q in f"{a['name']} {a['landing']}".lower()]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise KeyError(f"no area matches '{query}'")
    raise KeyError(f"'{query}' matches {len(hits)} areas: " + ", ".join(f"{a['area']} {a['name']}" for a in hits))


# ---------------------------------------------------------------- live session

@dataclass
class GameState:
    module: int
    submodule: int
    indoors: int
    area: int
    room: int
    entrance: int
    x: int
    y: int

    def describe(self) -> str:
        where = f"room 0x{self.room:04X}" if self.indoors else f"area 0x{self.area:02X}"
        return f"module 0x{self.module:02X}/0x{self.submodule:02X} {where} link {self.x},{self.y}"

    def as_dict(self) -> dict:
        return {"module": self.module, "submodule": self.submodule, "indoors": self.indoors, "area": self.area,
                "room": self.room, "entrance": self.entrance, "x": self.x, "y": self.y}


class CheatsError(RuntimeError):
    pass


class OracleCheats:
    def __init__(self, bridge, root: Path | None = None):
        self.b = bridge
        self.root = root or oracle_root()
        self._warps: dict | None = None
        self._checkpoints: dict | None = None

    @property
    def warps(self) -> dict:
        if self._warps is None:
            self._warps = load_json("warps.json", self.root)
        return self._warps

    @property
    def checkpoints(self) -> dict:
        if self._checkpoints is None:
            self._checkpoints = load_json("checkpoints.json", self.root)
        return self._checkpoints

    # -- memory helpers
    def r(self, addr: int) -> int:
        return self.b.read_memory(addr)

    def r16(self, addr: int) -> int:
        return self.b.read_memory16(addr)

    def w(self, addr: int, value: int) -> None:
        if not self.b.write_memory(addr, value & 0xFF):
            raise CheatsError(f"write failed at 0x{addr:06X}")

    def state(self) -> GameState:
        return GameState(self.r(MODULE), self.r(SUBMODULE), self.r(INDOORS), self.r(AREA),
                         self.r16(ROOM), self.r(ENTRANCE), self.r16(LINK_X), self.r16(LINK_Y))

    def _is_paused(self) -> bool:
        res = self.b.send_command("STATE")
        data = res.get("data", {}) if isinstance(res, dict) else {}
        return bool(isinstance(data, dict) and data.get("paused"))

    def _require_idle(self) -> GameState:
        s = self.state()
        if s.module not in PLAY_MODULES or s.submodule != 0:
            raise CheatsError(f"needs normal play (module $07/$09/$0B, submodule 0); now {s.describe()}")
        return s

    def _poke(self, writes: list[tuple[int, int]]) -> None:
        """Write a batch while paused so no frame sees half of it."""
        was_paused = self._is_paused()
        if not was_paused:
            self.b.pause()
        try:
            for addr, value in writes:
                self.w(addr, value)
        finally:
            if not was_paused:
                self.b.resume()

    def wait_play(self, modules=PLAY_MODULES, timeout: float = 15.0, settle: float = 0.3) -> GameState | None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            s = self.state()
            if s.module in modules and (s.submodule == 0 or s.module not in PLAY_MODULES):
                time.sleep(settle)
                s2 = self.state()
                if s2.module in modules and (s2.submodule == 0 or s2.module not in PLAY_MODULES):
                    return s2
            time.sleep(0.05)
        return None

    # -- warps
    def _entrance_area(self, entrance: dict) -> dict | None:
        area = entrance.get("area")
        if area is None:
            return None
        for a in self.warps.get("areas", []):
            if hexint(a["area"]) == hexint(area):
                return a
        return None

    def _fallback_area(self, entrance: dict) -> dict:
        abyss = entrance.get("area") is not None and hexint(entrance["area"]) & 0x40
        return find_area(self.warps, "0x40" if abyss else "0x33")

    @staticmethod
    def _uses_cached_exit(room: int) -> bool:
        return 0x100 <= room < 0x180 and room != 0x104

    def _exit_area(self, room: int) -> int | None:
        # Read one bus block, not 79 socket requests. Native scans the table backwards.
        data = bytes(self.b.read_block(0x02DD8A, 0x4F * 3))
        if len(data) != 0x4F * 3:
            raise CheatsError("Could not read this ROM's exit table")
        for i in range(0x4E, -1, -1):
            if int.from_bytes(data[i * 2:i * 2 + 2], "little") == room:
                return data[0x4F * 2 + i]
        return None

    def _entrance_destination(self, entrance: dict) -> tuple[int, int]:
        eid = hexint(entrance["id"])
        if not 0 <= eid < 0x85:
            raise CheatsError("Entrance must be between $00 and $84")
        room = self.r16(0x02C813 + eid * 2)
        area = None if self._uses_cached_exit(room) else self._exit_area(room)
        if area is None:
            data = bytes(self.b.read_block(0x1BB96F, 0x81 * 5))
            if len(data) != 0x81 * 5:
                raise CheatsError("Could not read this ROM's entrance table")
            areas = {int.from_bytes(data[i * 2:i * 2 + 2], "little")
                     for i in range(0x81)
                     if data[0x81 * 4 + i] == eid and
                     int.from_bytes(data[0x81 * 2 + i * 2:0x81 * 2 + i * 2 + 2], "little") != 0xFFFF}
            known = hexint(entrance["area"]) if entrance.get("area") is not None else None
            area = known if known in areas else next(iter(areas)) if len(areas) == 1 else None
        if room > 0x1FF or area is None:
            raise CheatsError(f"Cannot resolve entrance ${eid:02X} from this ROM's door/exit tables")
        return room, area

    @staticmethod
    def _world_writes(area: int) -> list[tuple[int, int]]:
        # Special OW/Lyra entrances preserve the current saved world.
        if not 0 <= area < 0x80:
            return []
        world = area & 0x40
        return [(SAVED_WORLD, world), (WORLD_FLAG, int(world != 0))]

    def warp_entrance(self, query: str | int, method: str = "auto", wait: bool = True,
                      modules: tuple[int, ...] = (MOD_DUNGEON, MOD_MESSAGE)) -> dict:
        entrance = find_entrance(self.warps, f"0x{query:02X}" if isinstance(query, int) else str(query))
        eid = hexint(entrance["id"])
        s = self._require_idle()
        if self.r(FOLLOWER) == SPAWN_FOLLOWER:
            raise CheatsError("follower $02 makes Underworld_LoadEntrance load the spawn point; clear $7EF3CC first")
        room, destination = self._entrance_destination(entrance)
        changes_world = destination < 0x80 and (destination & 0x40) != self.r(SAVED_WORLD)
        steps: list[str] = []
        resume_after = self._is_paused()
        if resume_after:
            self.b.resume()
        try:
            if method == "auto":
                if (s.module == MOD_OVERWORLD and not s.indoors and not changes_world and
                        (not self._uses_cached_exit(room) or s.area == destination)):
                    method = "door"
                elif self._uses_cached_exit(room):
                    area = next((a for a in self.warps.get("areas", []) if hexint(a["area"]) == destination), None)
                    area = area or find_area(self.warps, "0x40" if destination & 0x40 else "0x33")
                    steps.append(f"area 0x{hexint(area['area']):02X} first (house exit needs an overworld spot)")
                    self._warp_area_raw(area)
                    landed = self.wait_play((MOD_OVERWORLD, MOD_SPECIAL))
                    if landed is None:
                        raise CheatsError(f"area step did not finish; {self.state().describe()}")
                    method = "door" if landed.module == MOD_OVERWORLD else "reload"
                else:
                    method = "reload"
            if method == "door" and changes_world and not self._uses_cached_exit(room):
                method = "reload"  # Full native sprite-property initialization across worlds.
            if (self._uses_cached_exit(room) and destination < 0x80 and
                    (destination & 0x40) != self.r(SAVED_WORLD)):
                raise CheatsError("Cross-world house warps require the auto method to prepare an exit cache")
            if method == "door":
                self._poke(self._world_writes(destination) + [(ENTRANCE, eid), (ENTRANCE + 1, 0), (CONTINUE_FLAG, 0), (RESPAWN_FLAG, 0),
                            (0x7E004D, 0), (0x7E0046, 0), (NEXT_MODULE, 0x06), (SUBMODULE, 0), (SUBSUB, 0),
                            (MODULE, 0x0F)])
            elif method == "reload":
                self._poke(self._world_writes(destination) + [(ENTRANCE, eid), (ENTRANCE + 1, 0), (CONTINUE_FLAG, 1), (RESPAWN_FLAG, 0),
                            (INDOORS, 1), (SUBMODULE, 0), (SUBSUB, 0), (MODULE, 0x05)])
            else:
                raise CheatsError(f"unknown warp method '{method}'")
            steps.append(f"entrance 0x{eid:02X} via {method}")
            result = {"ok": True, "entrance": entrance["id"], "label": entrance["label"], "method": method,
                      "steps": steps, "expected_room": f"0x{room:04X}" if room is not None else None}
            if wait:
                landed = self.wait_play(modules)
                if landed is None:
                    result.update(ok=False, error=f"did not reach dungeon play; {self.state().describe()}")
                else:
                    result["state"] = landed.as_dict()
                    if landed.module not in PLAY_MODULES + (MOD_MESSAGE,):
                        # e.g. room $104 with IntroState 0 hands off to the arrival scenes ($0C/$0D).
                        result["note"] = f"room load handed off to module 0x{landed.module:02X}"
                    elif room is not None and landed.room != room:
                        result.update(ok=False, error=f"landed in room 0x{landed.room:04X}, expected 0x{room:04X}")
            return result
        finally:
            if resume_after:
                self.b.pause()

    def _warp_area_raw(self, area: dict) -> None:
        room = hexint(area["via_room"])
        destination = hexint(area["area"])
        if not 0 <= destination < 0x80:
            raise CheatsError("Special overworld areas require their dedicated loader or checkpoint")
        if self._uses_cached_exit(room) or self._exit_area(room) != destination:
            raise CheatsError(f"Area ${destination:02X} no longer matches room ${room:03X}'s exit in this ROM; refresh warps.json")
        self._poke(self._world_writes(destination) + [(ROOM, room & 0xFF), (ROOM + 1, room >> 8), (CONTINUE_FLAG, 0), (RESPAWN_FLAG, 0),
                    (BRIGHTNESS, 0x80), (HDMA_ENABLE, 0), (SUBMODULE, 0), (SUBSUB, 0), (MODULE, 0x08)])

    def prime_entrance(self, area: dict) -> dict:
        """An entrance to reload through before an area warp: the area's own exit room if an
        entrance leads there, else a table-exit entrance on that area, else Link's house/Origins."""
        entrances = self.warps.get("entrances", [])
        via = hexint(area["via_room"])
        for e in entrances:
            if e.get("room") and hexint(e["room"]) == via:
                return e
        for e in entrances:
            if e.get("area") and hexint(e["area"]) == hexint(area["area"]) and e.get("exit") == "table":
                return e
        return find_entrance(self.warps, "0x76" if hexint(area["area"]) & 0x40 else "0x01")

    def warp_area(self, query: str | int, wait: bool = True) -> dict:
        area = find_area(self.warps, f"0x{query:02X}" if isinstance(query, int) else str(query))
        self._require_idle()
        resume_after = self._is_paused()
        if resume_after:
            self.b.resume()
        try:
            self._warp_area_raw(area)
            target = hexint(area["area"])
            result = {"ok": True, "area": area["area"], "name": area["name"], "via_room": area["via_room"],
                      "landing": area["landing"]}
            if wait:
                landed = self.wait_play((MOD_OVERWORLD, MOD_SPECIAL, MOD_MESSAGE))
                if landed is None:
                    result.update(ok=False, error=f"did not reach overworld play; {self.state().describe()}")
                else:
                    result["state"] = landed.as_dict()
                    if landed.area != target:
                        result.update(ok=False, error=f"landed in area 0x{landed.area:02X}, expected 0x{target:02X}")
            return result
        finally:
            if resume_after:
                self.b.pause()

    # -- save data
    def active_slot(self) -> int:
        raw = self.b.read_block(SRAM_ACTIVE_SLOT, 2, memtype="sram")
        value = raw[0] | (raw[1] << 8) if len(raw) == 2 else 0
        return value // 2 if value in (2, 4, 6) else 1

    def sync_sram(self) -> int:
        block = bytearray(self.b.read_block(SAVE_START, SAVE_SIZE))
        if len(block) != SAVE_SIZE:
            raise CheatsError(f"read {len(block)} bytes of $7EF000, expected {SAVE_SIZE}")
        total = sum(block[i] | (block[i + 1] << 8) for i in range(0, 0x4FE, 2)) & 0xFFFF
        inverse = (0x5A5A - total) & 0xFFFF
        block[0x4FE] = inverse & 0xFF
        block[0x4FF] = inverse >> 8
        slot = self.active_slot()
        base = (slot - 1) * SAVE_SIZE
        for addr, value in ((SAVE_START + 0x4FE, block[0x4FE]), (SAVE_START + 0x4FF, block[0x4FF])):
            self.w(addr, value)
        for offset in (base, base + 0xF00):
            if not self.b.write_block(offset, bytes(block), memtype="sram"):
                raise CheatsError(f"SRAM write failed at 0x{offset:04X}")
        return slot

    def apply_checkpoint(self, cp_id: str, sram: bool = True, warp: bool = True, wait: bool = True) -> dict:
        resolved = resolve_checkpoint(self.checkpoints, cp_id)
        self._require_idle()
        destination = None
        entrance_method = "auto"
        loc = resolved["location"]
        if warp and "entrance" in loc:
            entry = find_entrance(self.warps, loc["entrance"])
            room, destination = self._entrance_destination(entry)
            # Checkpoint writes can change SavedWorld before the warp sees its source.
            entrance_method = "auto" if self._uses_cached_exit(room) else "reload"
        elif warp and "area" in loc:
            area = find_area(self.warps, loc["area"])
            destination = hexint(area["area"])
            room = hexint(area["via_room"])
            if not 0 <= destination < 0x80 or self._uses_cached_exit(room) or self._exit_area(room) != destination:
                raise CheatsError("Checkpoint area does not have a supported matching exit in this ROM")
        expected: dict[int, int] = {}
        was_paused = self._is_paused()
        if not was_paused:
            self.b.pause()
        try:
            for write in resolved["writes"]:
                addr = hexint(write["addr"])
                current = expected.get(addr, self.r(addr))
                expected[addr] = apply_write(current, write)
            for addr, value in expected.items():
                self.w(addr, value)
            if destination is not None:
                for addr, value in self._world_writes(destination):
                    self.w(addr, value)
            slot = self.sync_sram() if sram else None
        finally:
            if not was_paused:
                self.b.resume()
        result: dict[str, Any] = {"ok": True, "checkpoint": cp_id, "chain": resolved["chain"],
                                  "bytes_written": len(expected), "sram_slot": slot}
        loc = resolved["location"]
        exp_modules = tuple(hexint(m) for m in resolved["expect"].get("modules", [])) or (MOD_DUNGEON,)
        if warp and loc:
            if "entrance" in loc:
                result["warp"] = self.warp_entrance(loc["entrance"], method=entrance_method, wait=wait, modules=exp_modules)
            elif "area" in loc:
                # Module08 keeps the HUD hearts and follower graphics of the previous load.
                # A full reload through an entrance first rebuilds both (Module05_LoadFile
                # calls LoadFollowerGraphics and RebuildHUD), then the area warp follows.
                prime = self.prime_entrance(find_area(self.warps, loc["area"]))
                primed = self.warp_entrance(prime["id"], method="reload", wait=True)
                if not primed["ok"]:
                    result.update(ok=False, error=f"reload step failed: {primed.get('error')}", warp=primed)
                    return result
                result["prime"] = prime["id"]
                result["warp"] = self.warp_area(loc["area"], wait=wait)
            result["ok"] = result["warp"]["ok"]
            if result["warp"].get("error"):
                result["error"] = result["warp"]["error"]
        if wait and warp:
            exp = resolved["expect"]
            mods = [hexint(m) for m in exp.get("modules", [])]
            s = self.state()
            result["state"] = s.as_dict()
            problems = [result["error"]] if result.get("error") else []
            cutscene = s.module not in PLAY_MODULES + (MOD_MESSAGE,)
            if mods and s.module not in mods:
                problems.append(f"module 0x{s.module:02X} not in {exp['modules']}")
            if "room" in exp and s.indoors and not cutscene and s.room != hexint(exp["room"]):
                problems.append(f"room 0x{s.room:04X} != {exp['room']}")
            if "area" in exp and not s.indoors and not cutscene and s.area != hexint(exp["area"]):
                problems.append(f"area 0x{s.area:02X} != {exp['area']}")
            for addr in (0x7EF3C5, 0x7EF37A):
                if addr in expected and self.r(addr) != expected[addr]:
                    problems.append(f"${addr:06X} = 0x{self.r(addr):02X}, expected 0x{expected[addr]:02X}")
            if problems:
                result.update(ok=False, error="; ".join(problems))
        return result


# ---------------------------------------------------------------- CLI glue

def add_parsers(subparsers) -> None:
    cp = subparsers.add_parser("checkpoint", help="Jump to a story checkpoint (Data/debug/checkpoints.json)")
    cp.add_argument("id", nargs="?", help="Checkpoint id; omit to list")
    cp.add_argument("--list", action="store_true", help="List checkpoints")
    cp.add_argument("--wram-only", action="store_true", help="Do not copy the save block to SRAM")
    cp.add_argument("--no-warp", action="store_true", help="Write flags/items only")
    cp.add_argument("--no-wait", action="store_true", help="Do not wait for the warp to finish")
    cp.add_argument("--json", "-j", action="store_true")

    we = subparsers.add_parser("warp-entrance", help="Warp through an entrance id or name (Data/debug/warps.json)")
    we.add_argument("target", nargs="?", help="Entrance id (0x26, 26) or name (d1, 'village shop')")
    we.add_argument("--list", nargs="?", const="", metavar="FILTER", help="List entrances (optional filter)")
    we.add_argument("--method", choices=("auto", "door", "reload"), default="auto")
    we.add_argument("--no-wait", action="store_true")
    we.add_argument("--json", "-j", action="store_true")

    wa = subparsers.add_parser("warp-area", help="Warp to an overworld area through its exit-table room")
    wa.add_argument("target", nargs="?", help="Area id (0x10) or name")
    wa.add_argument("--list", action="store_true", help="List warpable areas")
    wa.add_argument("--no-wait", action="store_true")
    wa.add_argument("--json", "-j", action="store_true")


def _print(result: dict, as_json: bool) -> int:
    if as_json:
        print(json.dumps(result, indent=2))
    else:
        status = "OK" if result.get("ok") else "FAILED"
        head = result.get("checkpoint") or result.get("label") or result.get("name") or ""
        print(f"{status}: {head}")
        for key in ("chain", "sram_slot", "method", "steps", "landing", "error"):
            if result.get(key) not in (None, [], ""):
                print(f"  {key}: {result[key]}")
        warp = result.get("warp")
        if warp:
            print(f"  warp: {warp.get('label') or warp.get('name')} ({warp.get('method', 'area')})"
                  + (f" error: {warp['error']}" if warp.get("error") else ""))
        state = result.get("state") or (warp or {}).get("state")
        if state:
            where = f"room 0x{state['room']:04X}" if state["indoors"] else f"area 0x{state['area']:02X}"
            print(f"  now: module 0x{state['module']:02X} {where} link {state['x']},{state['y']}")
    return 0 if result.get("ok") else 2


def run(args, client) -> int:
    cheats = OracleCheats(client.bridge)
    try:
        if args.command == "checkpoint":
            if args.list or not args.id:
                data = cheats.checkpoints
                if args.json:
                    print(json.dumps({"checkpoints": [{k: c.get(k) for k in ("id", "label", "beat", "after", "location")}
                                                      for c in data["checkpoints"]], "todo": data.get("todo", [])}, indent=2))
                    return 0
                for c in data["checkpoints"]:
                    loc = c.get("location", {})
                    where = f"entrance {loc['entrance']}" if "entrance" in loc else f"area {loc.get('area')}"
                    print(f"  {c['id']:<16} beat {c.get('beat', '?'):>2}  {c['label']}  [{where}]")
                for t in data.get("todo", []):
                    print(f"  {t['id']:<16} TODO     {t['label']}")
                return 0
            if not args.json:
                print("Warning: overwrites story flags and items in WRAM"
                      + ("" if args.wram_only else " and in the active SRAM save slot (main + mirror)") + ".")
            result = cheats.apply_checkpoint(args.id, sram=not args.wram_only, warp=not args.no_warp,
                                             wait=not args.no_wait)
            return _print(result, args.json)
        if args.command == "warp-entrance":
            if args.list is not None or not args.target:
                flt = (args.list or "").lower()
                rows = [e for e in cheats.warps["entrances"]
                        if flt in f"{e['id']} {e['label']} {e['name']} {e['group']}".lower()]
                if args.json:
                    print(json.dumps(rows, indent=2))
                    return 0
                for e in rows:
                    print(f"  {e['id']}  {e['group']:<8} {e['label']:<40} room {e['room']}  area {e.get('area', '--')}  exit {e['exit']}")
                return 0
            return _print(cheats.warp_entrance(args.target, method=args.method, wait=not args.no_wait), args.json)
        if args.command == "warp-area":
            if args.list or not args.target:
                rows = cheats.warps["areas"]
                if args.json:
                    print(json.dumps(rows, indent=2))
                    return 0
                for a in rows:
                    print(f"  {a['area']}  {a['world']:<7} {a['name']:<18} via room {a['via_room']}  {a['landing']}")
                return 0
            return _print(cheats.warp_area(args.target, wait=not args.no_wait), args.json)
    except (CheatsError, KeyError, FileNotFoundError, ValueError) as exc:
        msg = exc.args[0] if isinstance(exc, KeyError) and exc.args else str(exc)
        if getattr(args, "json", False):
            print(json.dumps({"ok": False, "error": msg}))
        else:
            print(f"Error: {msg}")
        return 2
    return 1

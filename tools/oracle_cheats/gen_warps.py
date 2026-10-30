#!/usr/bin/env python3
"""Generate oracle-of-secrets/Data/debug/warps.json from the ROM and the entrance comments.

Sources (all read-only):
  - Overworld/entrances.asm, comment table "; 0xNN - OW XX - Name"   (entrance names)
  - Docs/World/LocationRegistry.md sections                          (grouping)
  - Data/location_registry.json "overworld_areas"                    (area names)
  - ROM tables (LoROM, headerless):
      EntranceData.room_id          $02:C813  2 bytes x $85  (usdasm bank_02.asm:13134)
      Overworld_EntranceScreens     $1B:B96F  2 bytes x $81  (usdasm bank_1B.asm:394)
      Overworld_EntranceTileIndex   $1B:BA71  2 bytes x $81  (usdasm bank_1B.asm:529)
      Overworld_Entrance_ID         $1B:BB73  1 byte  x $81  (usdasm bank_1B.asm:663)
      UnderworldExitData.room_id    $02:DD8A  2 bytes x $4F  (usdasm bank_02.asm:16261)
      UnderworldExitData.overworld  $02:DE28  1 byte  x $4F
      UnderworldExitData.y / .x     $02:E051 / $02:E0EF  2 bytes x $4F

Usage:
  gen_warps.py --oracle ~/src/hobby/oracle-of-secrets --rom Roms/Playtest/oos-v0.9.0-b17.sfc [--out Data/debug/warps.json]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from pathlib import Path

ENTRANCE_COUNT = 0x85
OW_ENTRANCE_COUNT = 0x81
EXIT_COUNT = 0x4F

# Rooms whose exit door uses the exit table (LoadOverworldFromUnderworld, usdasm bank_02.asm:17340):
# room < $0100, room == $0104, or room >= $0180. Rooms $0100-$017F return to the cached
# overworld position that Underworld_LoadEntrance saved when Link walked in.
def exit_kind(room: int) -> str:
    if room == 0x0104 or room < 0x0100 or room >= 0x0180:
        return "table"
    return "cache"


def pc(snes: int) -> int:
    return ((snes >> 16) & 0x7F) * 0x8000 + ((snes & 0xFFFF) - 0x8000)


GROUP_BY_SECTION = {
    "Dungeons": "dungeon",
    "Shrines": "shrine",
    "Caves by Region": "cave",
    "Houses": "town",
    "Shops": "town",
}

# LocationRegistry.md "Special Areas" rows, placed in the task groups.
SPECIAL_GROUPS = {0x02: "town", 0x12: "cave", 0x24: "dungeon", 0x37: "dungeon", 0x4E: "dungeon", 0x59: "town"}

# Dungeon labels: Docs/World/LocationRegistry.md "Dungeons (7)" + beat sheet row 27 (D8).
DUNGEON_LABELS = {
    0x26: "D1 Mushroom Grotto",
    0x15: "D2 Tail Palace",
    0x2B: "D3 Kalyxo Castle (main)",
    0x28: "D3 Kalyxo Castle (west)",
    0x2A: "D3 Kalyxo Castle (basement route)",
    0x32: "D3 Kalyxo Castle (prison)",
    0x0A: "D3 Kalyxo Castle (secret courtyard)",
    0x25: "D4 Zora Temple",
    0x4E: "D4 Zora Temple (waterfall)",
    0x34: "D5 Glacia Estate",
    0x27: "D6 Goron Mines",
    0x35: "D7 Dragon Ship",
    0x37: "D8 Fortress of Secrets",
    0x24: "D8 Final Boss Route",
}

# Preferred exit room per area when several rooms exit there.
AREA_VIA_OVERRIDES = {
    0x0B: 0x0059,  # Kalyxo Castle main door (entrance $2B)
    0x40: 0x0020,  # Abyss arrival: Kydrog_WarpPlayerAway loads room $20's exit (Sprites/Bosses/kydrog.asm:166)
}
# Landing labels for exit rooms that no named entrance uses.
EXTRA_ROOM_LABELS = {
    0x0020: "at the Abyss arrival point (Kydrog's banish exit)",
}

# Entrances that entrances.asm leaves unnamed but other project docs name.
EXTRA_ENTRANCES = {
    0x76: ("Shrine of Origins", "shrine",
           "Roms/TestBuilds/rc-intro-origins-goron-2026-09-25/playtest.md (entrance $76 -> room $05); "
           "OracleDebugActions.WarpTargets"),
    0x80: ("Wayward Village hole", "cave",
           "Docs/Planning/Plans/impa_follower_hints_2026-09-25.md:23 (village hole = entrance 128)"),
}


def parse_entrance_comments(path: Path) -> dict[int, tuple[str, str | None, int]]:
    out: dict[int, tuple[str, str | None, int]] = {}
    pat = re.compile(r"^; 0x([0-9A-Fa-f]{2}) - OW\s*([0-9A-Fa-fX]{2})?\s*-?\s*(.*)$")
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        m = pat.match(line.strip())
        if not m:
            continue
        eid = int(m.group(1), 16)
        area = m.group(2)
        name = m.group(3).strip()
        if not name or name.lower() == "available":
            continue
        # "; 0x65 - OW Fortune Teller" has no area column.
        if area is None and name:
            pass
        out[eid] = (name, area, lineno)
    return out


def parse_location_registry_md(path: Path) -> dict[int, str]:
    groups: dict[int, str] = {}
    section = ""
    for line in path.read_text().splitlines():
        if line.startswith("## "):
            section = re.sub(r"\s*\(\d+\)\s*$", "", line[3:].strip())
            continue
        if not line.startswith("| 0x"):
            continue
        cell = line.split("|")[1].strip()
        ids: list[int] = []
        for part in cell.split(","):
            part = part.strip().split(" ")[0]
            if "-" in part:
                a, b = part.split("-")
                ids.extend(range(int(a, 16), int(b, 16) + 1))
            elif part.startswith("0x"):
                ids.append(int(part, 16))
        for eid in ids:
            if section == "Special Areas":
                if eid in SPECIAL_GROUPS:
                    groups[eid] = SPECIAL_GROUPS[eid]
            elif section in GROUP_BY_SECTION:
                groups.setdefault(eid, GROUP_BY_SECTION[section])
    return groups


def guess_group(name: str) -> str:
    low = name.lower()
    if any(k in low for k in ("cave", "route", "fairy", "grave", "waterfall", "hole")):
        return "cave"
    return "town"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--oracle", required=True, type=Path)
    ap.add_argument("--rom", required=True, help="ROM path (relative to --oracle or absolute)")
    ap.add_argument("--out", default="Data/debug/warps.json")
    args = ap.parse_args()

    root = args.oracle.expanduser().resolve()
    rom_path = Path(args.rom)
    if not rom_path.is_absolute():
        rom_path = root / rom_path
    rom = rom_path.read_bytes()

    def w(addr: int) -> int:
        return struct.unpack_from("<H", rom, addr)[0]

    ent_rooms = [w(pc(0x02C813) + 2 * i) for i in range(ENTRANCE_COUNT)]
    doors: dict[int, list[dict]] = {}
    for i in range(OW_ENTRANCE_COUNT):
        area = w(pc(0x1BB96F) + 2 * i)
        tile = w(pc(0x1BBA71) + 2 * i)
        eid = rom[pc(0x1BBB73) + i]
        if tile == 0xFFFF:
            continue
        # Tile index -> pixel position inside the area (64 map16 per row, 16 px each).
        x = (tile & 0x7E) * 8
        y = (tile >> 7) * 16
        doors.setdefault(eid, []).append({"area": f"0x{area:02X}", "tile": f"0x{tile:04X}",
                                          "x": x, "y": y})

    exits = []
    for i in range(EXIT_COUNT):
        exits.append({
            "index": i,
            "room": w(pc(0x02DD8A) + 2 * i),
            "area": rom[pc(0x02DE28) + i],
            "y": w(pc(0x02E051) + 2 * i),
            "x": w(pc(0x02E0EF) + 2 * i),
        })
    # LoadOverworldFromUnderworld scans the table from the top (index $4E) down.
    effective_exit: dict[int, dict] = {}
    for e in reversed(exits):
        effective_exit.setdefault(e["room"], e)

    comments = parse_entrance_comments(root / "Overworld/entrances.asm")
    groups = parse_location_registry_md(root / "Docs/World/LocationRegistry.md")
    registry = json.loads((root / "Data/location_registry.json").read_text())
    area_names = {}
    for world, table in registry.get("overworld_areas", {}).items():
        base = 0x40 if world == "dark_world" else 0x00
        for key, name in table.items():
            area_names[base + int(key, 16)] = name
    special_names = {0x80: "Maku Tree Area", 0x81: "Zora Falls West", 0x82: "Zora Falls East"}
    area_names.update(special_names)

    def area_name(area: int) -> str:
        return area_names.get(area, f"Area 0x{area:02X}")

    entrances = []
    all_ids = sorted(set(comments) | set(EXTRA_ENTRANCES))
    for eid in all_ids:
        if eid >= ENTRANCE_COUNT:
            continue
        if eid in comments:
            name, comment_area, lineno = comments[eid]
            source = f"Overworld/entrances.asm:{lineno}"
            group = groups.get(eid) or guess_group(name)
        else:
            name, group, source = EXTRA_ENTRANCES[eid]
            comment_area, lineno = None, None
        room = ent_rooms[eid]
        rom_doors = doors.get(eid, [])
        rom_area = int(rom_doors[0]["area"], 16) if rom_doors else None
        entry = {
            "id": f"0x{eid:02X}",
            "name": name,
            "label": DUNGEON_LABELS.get(eid, name),
            "group": "dungeon" if eid in DUNGEON_LABELS else group,
            "room": f"0x{room:04X}",
            "exit": exit_kind(room),
            "source": source,
        }
        if rom_area is not None:
            entry["area"] = f"0x{rom_area:02X}"
            entry["area_name"] = area_name(rom_area)
            entry["doors"] = rom_doors
        if comment_area and comment_area.upper() != "XX":
            entry["comment_area"] = f"0x{int(comment_area, 16):02X}"
            if rom_area is not None and int(comment_area, 16) != rom_area:
                entry["note"] = (f"entrances.asm says OW {comment_area.upper()}; the ROM door table puts "
                                 f"this entrance on area 0x{rom_area:02X}.")
        if entry["exit"] == "table" and room not in effective_exit:
            entry["exit"] = "table-missing"
            entry["note"] = (entry.get("note", "") + " Room is not in UnderworldExitData; "
                             "the room's own exit door may be elsewhere (multi-room cave).").strip()
        entrances.append(entry)

    # Overworld areas reachable through an exit-table room (vanilla Module08 path).
    room_labels = dict(EXTRA_ROOM_LABELS)
    for eid, room in enumerate(ent_rooms):
        label = DUNGEON_LABELS.get(eid) or (comments.get(eid, (None,))[0]) or (EXTRA_ENTRANCES.get(eid, (None,))[0])
        if label and room not in room_labels:
            room_labels[room] = label
    candidates: dict[int, list[dict]] = {}
    for room, e in effective_exit.items():
        if room in (0x0000, 0xFFFF) or room > 0x01FF or exit_kind(room) != "table" or e["x"] == 0xFFFF:
            continue
        # Special areas ($80+) need the Module0A/0B setup; loading them through Module08
        # breaks their edges (tested on b17: area $80 walked off into area $90 with garbage tiles).
        if e["area"] >= 0x80:
            continue
        candidates.setdefault(e["area"], []).append(e)
    areas = []
    for area in sorted(candidates):
        opts = sorted(candidates[area], key=lambda e: (e["room"] != AREA_VIA_OVERRIDES.get(area),
                                                       e["room"] not in room_labels, e["index"]))
        best = opts[0]
        landing = room_labels.get(best["room"])
        areas.append({
            "area": f"0x{area:02X}",
            "name": area_name(area),
            "world": "abyss" if area & 0x40 else "kalyxo",
            "via_room": f"0x{best['room']:04X}",
            "exit_index": f"0x{best['index']:02X}",
            "x": f"0x{best['x']:04X}",
            "y": f"0x{best['y']:04X}",
            "landing": (EXTRA_ROOM_LABELS[best["room"]] if best["room"] in EXTRA_ROOM_LABELS
                        else f"outside {landing}" if landing else f"exit of room 0x{best['room']:04X}"),
        })

    rom_rel = str(rom_path.relative_to(root)) if rom_path.is_relative_to(root) else str(rom_path)
    out = {
        "schema": "oracle-debug-warps/1",
        "description": "Warp targets for the Mesen2-OOS Cheats & Warps panel and mesen2_client.py warp-entrance / warp-area. "
                       "Generated by mesen2-oos tools/oracle_cheats/gen_warps.py; see Data/debug/README.md.",
        "rom": {"path": rom_rel, "sha256": hashlib.sha256(rom).hexdigest()},
        "sources": {
            "names": "Overworld/entrances.asm comment table",
            "groups": "Docs/World/LocationRegistry.md sections",
            "area_names": "Data/location_registry.json overworld_areas",
            "rooms": "ROM EntranceData.room_id $02:C813 (usdasm bank_02.asm:13134)",
            "doors": "ROM Overworld_EntranceScreens/TileIndex/Entrance_ID $1B:B96F/$1B:BA71/$1B:BB73 (usdasm bank_1B.asm:394-663)",
            "areas": "ROM UnderworldExitData $02:DD8A (usdasm bank_02.asm:16259), first match scanning down like LoadOverworldFromUnderworld",
        },
        "entrances": entrances,
        "areas": areas,
    }
    out_path = Path(args.out)
    if not out_path.is_absolute():
        out_path = root / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {out_path}: {len(entrances)} entrances, {len(areas)} areas")


if __name__ == "__main__":
    main()

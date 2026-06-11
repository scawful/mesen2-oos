#!/usr/bin/env python3
"""Bridge live Oracle runtime/save-data info into the Mesen UI."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any


ITEM_TOGGLE_GROUPS: list[tuple[str, list[str]]] = [
    ("Core Tools", ["lamp", "hammer", "firerod", "icerod", "feather", "book", "somaria"]),
    ("Masks", ["zoramask", "dekumask", "bunnymask", "stonemask", "wolfmask"]),
    ("Travel / Gear", ["boots", "flippers", "moonpearl"]),
]

ITEM_CHOICE_GROUPS: list[tuple[str, list[str]]] = [
    ("Equipment", ["sword", "shield", "armor", "gloves"]),
    ("Inventory Upgrades", ["bow", "boomerang", "hookshot", "mirror", "flute", "ocarina_song"]),
]

FLAG_TOGGLE_GROUPS: list[tuple[str, list[str]]] = [
    ("Story", ["intro", "hall", "pendant", "mastersword", "fortress", "impa", "sanctuary", "kydrog", "bookflag", "makutree", "dekuquest", "zoraquest"]),
    ("Dungeons", ["d1", "d2", "d3", "d4", "d5", "d6", "d7"]),
    ("Pendants", ["wisdom", "power", "courage"]),
    ("Side Quests", ["masksalesman", "dekufound", "goronquest"]),
]

FLAG_CHOICE_GROUPS: list[tuple[str, list[str]]] = [
    ("Progress Values", ["gamestate"]),
]

PRESET_ORDER = ["all_items", "early_game", "dungeon_ready", "clear_progress"]

PRESET_DEFINITIONS: dict[str, dict[str, Any]] = {
    "all_items": {
        "label": "All Items",
        "description": "Full gameplay loadout without story progress bits.",
        "items": {
            "bow": 4,
            "boomerang": 2,
            "hookshot": 2,
            "bombs": 50,
            "powder": 2,
            "firerod": 1,
            "icerod": 1,
            "lamp": 1,
            "hammer": 1,
            "flute": 5,
            "ocarina_song": 4,
            "feather": 1,
            "book": 1,
            "somaria": 1,
            "mirror": 2,
            "zoramask": 1,
            "dekumask": 1,
            "bunnymask": 1,
            "stonemask": 1,
            "wolfmask": 1,
            "sword": 4,
            "shield": 3,
            "armor": 2,
            "gloves": 2,
            "boots": 1,
            "flippers": 1,
            "moonpearl": 1,
            "health": 160,
            "maxhealth": 160,
            "magic": 128,
            "rupees": 9999,
            "arrows": 70,
            "keys": 9,
        },
    },
    "early_game": {
        "label": "Early Game",
        "description": "Basic movement and combat kit for quick early-route testing.",
        "items": {
            "sword": 1,
            "shield": 1,
            "lamp": 1,
            "boots": 1,
            "bow": 1,
            "boomerang": 1,
            "bombs": 10,
            "arrows": 15,
            "rupees": 150,
            "health": 48,
            "maxhealth": 48,
            "magic": 128,
        },
        "flags": {
            "intro": True,
            "impa": True,
        },
    },
    "dungeon_ready": {
        "label": "Dungeon Ready",
        "description": "Mid-progression combat + traversal loadout for dungeon iteration.",
        "items": {
            "sword": 2,
            "shield": 2,
            "armor": 1,
            "gloves": 1,
            "lamp": 1,
            "feather": 1,
            "flippers": 1,
            "bow": 2,
            "hookshot": 1,
            "boomerang": 2,
            "bombs": 20,
            "arrows": 30,
            "rupees": 500,
            "health": 96,
            "maxhealth": 96,
            "magic": 128,
        },
        "flags": {
            "intro": True,
            "impa": True,
            "sanctuary": True,
        },
    },
    "clear_progress": {
        "label": "Clear Progress",
        "description": "Strip inventory and story bits back to a clean baseline.",
        "items": {
            "bow": 0,
            "boomerang": 0,
            "hookshot": 0,
            "bombs": 0,
            "powder": 0,
            "firerod": 0,
            "icerod": 0,
            "lamp": 0,
            "hammer": 0,
            "flute": 0,
            "ocarina_song": 0,
            "feather": 0,
            "book": 0,
            "somaria": 0,
            "mirror": 0,
            "zoramask": 0,
            "dekumask": 0,
            "bunnymask": 0,
            "stonemask": 0,
            "wolfmask": 0,
            "sword": 0,
            "shield": 0,
            "armor": 0,
            "gloves": 0,
            "boots": 0,
            "flippers": 0,
            "moonpearl": 0,
            "health": 48,
            "maxhealth": 48,
            "magic": 128,
            "rupees": 0,
            "arrows": 0,
            "keys": 0,
        },
        "flags": {
            "gamestate": 0,
            "oosprog": 0,
            "oosprog2": 0,
            "crystals": 0,
            "pendants": 0,
            "sidequest": 0,
            "makutree": 0,
            "dekuquest": 0,
            "zoraquest": 0,
            "intro": False,
            "hall": False,
            "pendant": False,
            "mastersword": False,
            "fortress": False,
            "impa": False,
            "sanctuary": False,
            "kydrog": False,
            "bookflag": False,
            "d1": False,
            "d2": False,
            "d3": False,
            "d4": False,
            "d5": False,
            "d6": False,
            "d7": False,
            "wisdom": False,
            "power": False,
            "courage": False,
            "masksalesman": False,
            "dekufound": False,
            "goronquest": False,
        },
    },
}


def _expand_home(path: str) -> Path:
    if path.startswith("~"):
        return Path(path.replace("~", str(Path.home()), 1)).expanduser()
    return Path(path).expanduser()


def _resolve_oos_root() -> Path | None:
    for env_key in ("ORACLE_OF_SECRETS_ROOT", "OOS_ROOT", "MESEN2_PROJECT_ROOT"):
        raw = os.getenv(env_key)
        if raw:
            candidate = _expand_home(raw)
            if candidate.exists():
                return candidate

    mesen_root = Path(__file__).resolve().parents[1]
    sibling = mesen_root.parent / "oracle-of-secrets"
    if sibling.exists():
        return sibling

    default_root = Path.home() / "src" / "hobby" / "oracle-of-secrets"
    return default_root if default_root.exists() else None


def _bootstrap() -> tuple[Any, dict[str, Any], dict[str, Any]]:
    oos_root = _resolve_oos_root()
    if not oos_root:
        raise RuntimeError("Oracle-of-Secrets root not found")

    scripts_dir = oos_root / "scripts"
    if not scripts_dir.exists():
        raise RuntimeError(f"Oracle scripts folder not found: {scripts_dir}")

    scripts_str = str(scripts_dir)
    if scripts_str not in sys.path:
        sys.path.insert(0, scripts_str)

    from mesen2_client_lib.client import OracleDebugClient  # type: ignore
    from mesen2_client_lib.constants import ITEMS, STORY_FLAGS  # type: ignore

    client = OracleDebugClient(socket_path=os.getenv("MESEN2_SOCKET_PATH") or None)
    if not client.ensure_connected():
        raise RuntimeError("Mesen2 socket not found or not responding")

    return client, ITEMS, STORY_FLAGS


def _state_summary(client: Any) -> dict[str, Any]:
    state = client.get_oracle_state()
    story = client.get_story_state()

    indoors = bool(state.get("indoors"))
    area = int(state.get("area", 0) or 0)
    room_layout = int(state.get("room", 0) or 0)
    dungeon_room = int(state.get("dungeon_room", 0) or 0)
    room_value = dungeon_room if indoors else room_layout
    room_digits = 4 if indoors else 2

    return {
        "mode": int(state.get("mode", 0) or 0),
        "mode_name": str(state.get("mode_name") or "Unknown"),
        "submode": int(state.get("submode", 0) or 0),
        "indoors": indoors,
        "map_name": "Dungeon" if indoors else "Overworld",
        "area_id": area,
        "area_name": str(state.get("area_name") or f"Area 0x{area:02X}"),
        "room_layout": room_layout,
        "room_name": str(state.get("room_name") or f"Room 0x{room_value:0{room_digits}X}"),
        "room_value": room_value,
        "room_label": f"0x{room_value:0{room_digits}X}",
        "dungeon_room": dungeon_room,
        "link_x": int(state.get("link_x", 0) or 0),
        "link_y": int(state.get("link_y", 0) or 0),
        "link_dir_name": str(state.get("link_dir_name") or "?"),
        "link_state": int(state.get("link_state", 0) or 0),
        "link_form_name": str(state.get("link_form_name") or "Unknown"),
        "health": int(state.get("health", 0) or 0),
        "max_health": int(state.get("max_health", 0) or 0),
        "magic": int(state.get("magic", 0) or 0),
        "rupees": int(state.get("rupees", 0) or 0),
        "game_state": int(story.get("game_state", 0) or 0),
        "crystals": int(story.get("crystals", 0) or 0),
        "pendants": int(story.get("pendants", 0) or 0),
    }


def _build_toggle_entries(client: Any, registry: dict[str, Any], groups: list[tuple[str, list[str]]], *, is_flag: bool) -> list[dict[str, Any]]:
    current = client.get_all_flags() if is_flag else client.get_all_items()
    entries: list[dict[str, Any]] = []
    for group_name, keys in groups:
        for key in keys:
            if key not in registry or key not in current:
                continue
            _, label, _ = registry[key]
            raw = current[key]
            entries.append(
                {
                    "group": group_name,
                    "key": key,
                    "label": str(label),
                    "description": str(raw.get("description") or label),
                    "checked": bool(raw.get("is_set", False)) if is_flag else int(raw.get("value", 0) or 0) != 0,
                    "value": int(raw.get("value", 0) or 0),
                }
            )
    return entries


def _build_choice_entries(client: Any, registry: dict[str, Any], groups: list[tuple[str, list[str]]], *, is_flag: bool) -> list[dict[str, Any]]:
    current = client.get_all_flags() if is_flag else client.get_all_items()
    entries: list[dict[str, Any]] = []
    for group_name, keys in groups:
        for key in keys:
            if key not in registry or key not in current:
                continue
            _, label, values = registry[key]
            if not isinstance(values, dict):
                continue
            raw = current[key]
            entries.append(
                {
                    "group": group_name,
                    "key": key,
                    "label": str(label),
                    "description": str(label),
                    "value": int(raw.get("value", 0) or 0),
                    "choices": [
                        {"value": int(choice_value), "label": str(choice_label)}
                        for choice_value, choice_label in sorted(values.items(), key=lambda item: int(item[0]))
                    ],
                }
            )
    return entries


def _build_presets() -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for preset_id in PRESET_ORDER:
        preset = PRESET_DEFINITIONS[preset_id]
        entries.append(
            {
                "id": preset_id,
                "label": str(preset["label"]),
                "description": str(preset["description"]),
            }
        )
    return entries


def _parse_assignment(raw: str) -> tuple[str, int | bool]:
    if "=" not in raw:
        raise ValueError(f"Invalid assignment '{raw}' (expected key=value)")
    key, value = raw.split("=", 1)
    key = key.strip()
    raw_value = value.strip()
    lowered = raw_value.lower()
    if not key:
        raise ValueError(f"Invalid assignment '{raw}' (empty key)")
    if lowered in ("1", "true", "yes", "on"):
        return key, True
    if lowered in ("0", "false", "no", "off"):
        return key, False
    return key, int(raw_value, 0)


def _apply_items(client: Any, updates: dict[str, int | bool]) -> int:
    for key, value in updates.items():
        if isinstance(value, bool):
            client.set_item(key, 1 if value else 0)
        else:
            client.set_item(key, int(value))
    return len(updates)


def _apply_flags(client: Any, updates: dict[str, int | bool]) -> int:
    for key, value in updates.items():
        client.set_flag(key, value)
    return len(updates)


def _apply_preset(client: Any, preset_id: str) -> tuple[int, int]:
    if preset_id not in PRESET_DEFINITIONS:
        raise ValueError(f"Unknown preset: {preset_id}")
    preset = PRESET_DEFINITIONS[preset_id]
    changed_items = _apply_items(client, dict(preset.get("items") or {}))
    changed_flags = _apply_flags(client, dict(preset.get("flags") or {}))
    return changed_items, changed_flags


def cmd_summary(_: argparse.Namespace) -> dict[str, Any]:
    client, _, _ = _bootstrap()
    return {"ok": True, "state": _state_summary(client)}


def cmd_editor(_: argparse.Namespace) -> dict[str, Any]:
    client, items_registry, flags_registry = _bootstrap()
    return {
        "ok": True,
        "state": _state_summary(client),
        "items": _build_toggle_entries(client, items_registry, ITEM_TOGGLE_GROUPS, is_flag=False),
        "flags": _build_toggle_entries(client, flags_registry, FLAG_TOGGLE_GROUPS, is_flag=True),
        "item_choices": _build_choice_entries(client, items_registry, ITEM_CHOICE_GROUPS, is_flag=False),
        "flag_choices": _build_choice_entries(client, flags_registry, FLAG_CHOICE_GROUPS, is_flag=True),
        "presets": _build_presets(),
    }


def cmd_apply(args: argparse.Namespace) -> dict[str, Any]:
    client, _, _ = _bootstrap()
    item_updates = dict(_parse_assignment(raw) for raw in args.item)
    flag_updates = dict(_parse_assignment(raw) for raw in args.flag)

    was_paused = client.is_paused()
    sync_result: dict[str, Any] | None = None
    changed_items = 0
    changed_flags = 0

    try:
        if was_paused is False:
            client.pause()

        if args.preset:
            preset_items, preset_flags = _apply_preset(client, args.preset)
            changed_items += preset_items
            changed_flags += preset_flags

        changed_items += _apply_items(client, item_updates)
        changed_flags += _apply_flags(client, flag_updates)

        if args.sync:
            sync_result = client.sync_wram_save_to_sram()
    finally:
        if was_paused is False:
            client.resume()

    return {
        "ok": True,
        "changed_items": changed_items,
        "changed_flags": changed_flags,
        "preset": args.preset or "",
        "synced": bool(sync_result),
        "sync": sync_result,
        "state": _state_summary(client),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("summary", help="Emit live Oracle runtime summary")
    subparsers.add_parser("editor", help="Emit save-data editor state")

    apply_parser = subparsers.add_parser("apply", help="Apply item/flag/preset changes")
    apply_parser.add_argument("--item", action="append", default=[], help="Item value in key=value form")
    apply_parser.add_argument("--flag", action="append", default=[], help="Flag value in key=value form")
    apply_parser.add_argument("--preset", default="", help="Named preset to apply before explicit edits")
    apply_parser.add_argument("--sync", action="store_true", help="Sync WRAMSAVE into SRAM after applying changes")

    args = parser.parse_args()

    try:
        if args.command == "summary":
            payload = cmd_summary(args)
        elif args.command == "editor":
            payload = cmd_editor(args)
        elif args.command == "apply":
            payload = cmd_apply(args)
        else:
            raise RuntimeError(f"Unknown command: {args.command}")

        print(json.dumps(payload))
        return 0
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

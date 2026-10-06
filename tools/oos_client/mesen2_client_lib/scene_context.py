"""Ground paused dungeon captures in the loaded ROM's object and door records.

This is an opt-in debug capture, not the passive spectator. It never changes
run state or gameplay. A single image cannot establish door traversal.
"""
from __future__ import annotations

import base64
import hashlib
import html
import json
import math
from pathlib import Path
import struct
import subprocess


def _reply(bridge, command):
    result = bridge.send_command(command)
    if not result.get("success"):
        raise ValueError(f"{command}: {result.get('error', 'no response')}")
    data = result.get("data")
    return json.loads(data) if isinstance(data, str) else data


def _signed_delta(value, origin):
    return (value - origin + 32768) % 65536 - 32768


def decode_scene_state(ram):
    if len(ram) != 0x500:
        raise ValueError("Scene capture requires the complete $0000-$04FF block")
    word = lambda offset: int.from_bytes(ram[offset:offset + 2], "little")
    # BG2 scroll mirrors, not the adjacent $E1/$E7 high bytes of BG1.
    return {
        "module": ram[0x10], "submodule": ram[0x11],
        "indoors": bool(ram[0x1B]), "room": word(0xA0),
        "loaded_room": word(0x48E), "link_x": word(0x22),
        "link_y": word(0x20), "link_state": ram[0x5D],
        "collision_layer": ram[0xEE], "layer_0476": ram[0x476],
        "bg1_scroll_x": word(0xE0), "bg1_scroll_y": word(0xE6),
        "scroll_x": word(0xE2), "scroll_y": word(0xE8),
    }


def ground_entities(room, state, target):
    if room.get("room_id") != state["room"]:
        raise ValueError("Room export does not match the captured room")
    room_id = state["room"]
    if not 0 <= room_id < 0x128:
        raise ValueError("Room ID is outside the supported dungeon table")
    origin_x, origin_y = (room_id & 15) * 512, (room_id >> 4) * 512
    entities = []
    for kind, rows in (("door", room.get("doors", [])),
                       ("object", room.get("objects", []))):
        for row in rows:
            index = row["index"] if kind == "door" else row["stream_index"]
            tx, ty = ((row["tile_x"], row["tile_y"]) if kind == "door"
                      else (row["x"], row["y"]))
            if not 0 <= tx < 64 or not 0 <= ty < 64:
                raise ValueError("Entity anchor lies outside its room")
            label = (f"{row['direction']} {row['type']}" if kind == "door"
                     else f"{row.get('name', 'Unnamed object')} {row['object_id']}")
            wx, wy = origin_x + tx * 8, origin_y + ty * 8
            entity = {
                "id": f"{kind}:{index}", "kind": kind, "index": index,
                "label": label, "record": row, "room_tile8": [tx, ty],
                "world_anchor_px": [wx, wy],
                "screen_anchor_px": [_signed_delta(wx, state["scroll_x"]),
                                     _signed_delta(wy, state["scroll_y"])],
                "distance_to_link_anchor_px": round(math.hypot(
                    wx - state["link_x"], wy - state["link_y"]), 2),
            }
            if kind == "door" and "bounds_px" in row:
                bounds = row["bounds_px"]
                entity["screen_bounds_px"] = {
                    "x": _signed_delta(origin_x + bounds["x"], state["scroll_x"]),
                    "y": _signed_delta(origin_y + bounds["y"], state["scroll_y"]),
                    "width": bounds["width"], "height": bounds["height"],
                }
            entities.append(entity)
    selected = next((entry for entry in entities if entry["id"] == target), None)
    if target is not None and selected is None:
        raise ValueError(f"Unknown target {target!r}; use door:<index> or object:<stream_index>")
    return {
        "room_origin_px": [origin_x, origin_y], "entities": entities,
        "target": selected, "traversal": "not_evaluated_by_single_capture",
        "projection": "BG2 scroll; entity anchors/bounds, not collision footprints",
        "link_screen_anchor_px": [_signed_delta(state["link_x"], state["scroll_x"]),
                                  _signed_delta(state["link_y"], state["scroll_y"])],
    }


def write_scene_report(path, payload, png):
    if png[:8] != b"\x89PNG\r\n\x1a\n" or png[12:16] != b"IHDR":
        raise ValueError("Screenshot is not a PNG")
    width, height = struct.unpack(">II", png[16:24])
    scene = payload["scene"]
    target = scene["target"]
    encoded = base64.b64encode(png).decode()
    svg = [f'<svg viewBox="0 0 {width} {height}" xmlns="http://www.w3.org/2000/svg">',
           f'<image width="{width}" height="{height}" href="data:image/png;base64,{encoded}"/>']
    visible = []
    for entity in scene["entities"]:
        x, y = entity["screen_anchor_px"]
        if not (0 <= x < width and 0 <= y < height):
            continue
        visible.append(entity)
        selected = target is not None and entity["id"] == target["id"]
        color = "#ffdb59" if selected else ("#65d7ff" if entity["kind"] == "door" else "#ed89df")
        label = html.escape(entity["id"] + " · " + entity["label"])
        svg.append(f'<g><title>{label}</title><circle cx="{x}" cy="{y}" r="2" fill="{color}"/>')
        if selected and "screen_bounds_px" in entity:
            b = entity["screen_bounds_px"]
            svg.append(f'<rect x="{b["x"]}" y="{b["y"]}" width="{b["width"]}" height="{b["height"]}" fill="none" stroke="{color}" stroke-width="1"/>')
        if selected:
            svg.append(f'<text x="{max(1, min(x, width-75))}" y="{max(9,y-3)}" fill="{color}" font-size="7" stroke="#151920" stroke-width=".4" paint-order="stroke">{html.escape(entity["id"])}</text>')
        svg.append('</g>')
    x, y = scene["link_screen_anchor_px"]
    svg.append(f'<circle cx="{x+8}" cy="{y+16}" r="3" fill="none" stroke="#88ff96"/><title>Link reference point</title></svg>')
    selected_label = (target["id"] + " · " + target["label"]) if target else "No target selected"
    rows = ''.join('<tr><td>'+html.escape(e['id'])+'</td><td>'+html.escape(e['label'])+'</td><td>'+str(e['room_tile8'])+'</td></tr>' for e in sorted(visible,key=lambda e:(e['kind']!='door',e['index'])))
    text = f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Dungeon scene target</title><style>body{{background:#151920;color:#eee;font:16px/1.5 system-ui;max-width:1050px;margin:auto;padding:24px}}svg{{width:100%;max-width:768px;image-rendering:pixelated}}td,th{{padding:6px;text-align:left;border-bottom:1px solid #485269}}code{{overflow-wrap:anywhere}}</style><h1>Room ${payload['state']['room']:03X}</h1><p><b>Target: {html.escape(selected_label)}</b></p><p>Yellow: target. Blue: door records. Pink: object anchors. Green: Link reference point. Marks identify records, not collision footprints.</p>{''.join(svg)}<p><b>This capture does not prove traversal.</b> Require a controller trace across the selected door or a verified room transition. Passing an object does not qualify a door.</p><table><tr><th>Entity</th><th>ROM record / catalog label</th><th>Room tile (8 px)</th></tr>{rows}</table><p>Object names come from Yaze's vanilla catalog; hacks can reuse their art or behavior.</p><p>ROM SHA-256: <code>{payload['rom_sha256']}</code> · Frame {payload['frame']}</p></html>'''
    Path(path).write_text(text)


def capture_dungeon_scene(bridge, rom_path, z3ed_path, target=None, report=None):
    """Capture an already paused owned instance; refuse stale ROM/scene identity."""
    initial = _reply(bridge, "STATE")
    if not initial.get("paused"):
        raise ValueError("Pause the owned test instance before a grounded scene capture")
    rom_path, z3ed_path = Path(rom_path).resolve(), Path(z3ed_path).resolve()
    rom = rom_path.read_bytes()
    sha1 = hashlib.sha1(rom).hexdigest()
    info = _reply(bridge, "ROMINFO")
    if info.get("sha1", "").lower() != sha1:
        raise ValueError("Scene ROM does not match the emulator's loaded ROM")
    state = decode_scene_state(bridge.read_block(0x7E0000, 0x500))
    if (state["module"] != 7 or state["submodule"] != 0 or not state["indoors"]
            or state["room"] != state["loaded_room"]):
        raise ValueError("Scene capture requires stable dungeon gameplay, not a transition")
    if not 0 <= state["room"] < 0x128:
        raise ValueError("Room ID is outside the supported dungeon table")
    command = [str(z3ed_path), "dungeon-describe-room", f"--rom={rom_path}",
               f"--room={state['room']:03X}", "--include-objects", "--format=json"]
    output = subprocess.run(command, capture_output=True, text=True, check=True, timeout=30)
    room = json.loads(output.stdout)
    scene = ground_entities(room, state, target)
    png = bridge.screenshot() if report else None
    final = _reply(bridge, "STATE")
    if not final.get("paused") or final.get("frame") != initial.get("frame"):
        raise ValueError("Emulator advanced during capture; discard the scene")
    if _reply(bridge, "ROMINFO").get("sha1", "").lower() != sha1 or rom_path.read_bytes() != rom:
        raise ValueError("ROM identity changed during capture")
    final_state = decode_scene_state(bridge.read_block(0x7E0000, 0x500))
    if final_state != state:
        raise ValueError("Scene state changed during capture; discard the scene")
    payload = {"schema": "mesen.dungeon-scene.v1", "state": state, "scene": scene,
               "frame": initial["frame"], "rom_sha1": sha1,
               "rom_sha256": hashlib.sha256(rom).hexdigest(),
               "room_export_command": command, "room_export": room}
    if report:
        if not png:
            raise ValueError("No screenshot returned")
        write_scene_report(report, payload, png)
        payload["report"] = str(Path(report).resolve())
    return payload

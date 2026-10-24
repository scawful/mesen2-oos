"""Regression: crossing the prison-cell object cannot qualify the north door."""
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from mesen2_client_lib.scene_context import (
    capture_dungeon_scene, decode_scene_state, ground_entities, write_scene_report,
)


def ram_for(room=5):
    ram = bytearray(0x500)
    ram[0x10], ram[0x1B] = 7, 1
    for offset, value in [(0xA0, room), (0x48E, room), (0x22, 2936),
                          (0x20, 308), (0xE0, 2816), (0xE2, 2816),
                          (0xE6, 256), (0xE8, 256)]:
        ram[offset:offset+2] = value.to_bytes(2, 'little')
    return ram


def room_export(room=5):
    return {'room_id': room, 'objects': [
        {'stream_index': 49, 'object_id': '0xF97', 'name': 'Prison cell',
         'x': 40, 'y': 42, 'name_source': 'vanilla_object_catalog'}],
        'doors': [{'index': 2, 'direction': 'North', 'type': 'Normal Door',
                   'tile_x': 46, 'tile_y': 36,
                   'bounds_px': {'x': 368, 'y': 288, 'width': 32, 'height': 24}}]}


class Bridge:
    def __init__(self, rom):
        self.sha1 = hashlib.sha1(rom).hexdigest()
        self.ram = ram_for()
        self.paused = True
        self.frames = [100, 100]
        self.commands = []

    def send_command(self, command):
        self.commands.append(command)
        if command == 'STATE':
            return {'success': True, 'data': {'paused': self.paused,
                                             'frame': self.frames.pop(0)}}
        assert command == 'ROMINFO'
        return {'success': True, 'data': {'sha1': self.sha1}}

    def read_block(self, address, size):
        assert address == 0x7E0000 and size == 0x500
        return bytes(self.ram)


def test_gate_and_door_are_distinct_even_after_passing_gate():
    scene = ground_entities(room_export(), decode_scene_state(ram_for()), 'door:2')
    assert scene['target']['kind'] == 'door'
    assert scene['target']['label'] == 'North Normal Door'
    assert scene['target']['screen_anchor_px'] == [112, 32]
    gate = next(e for e in scene['entities'] if e['id'] == 'object:49')
    assert gate['kind'] == 'object' and gate['label'] == 'Prison cell 0xF97'
    assert gate['screen_anchor_px'] == [64, 80]
    assert scene['link_screen_anchor_px'] == [120, 52]
    assert scene['traversal'] == 'not_evaluated_by_single_capture'


def test_room_id_preserves_high_byte_and_scroll_pairs_are_adjacent_words():
    ram = ram_for(0x105)
    state = decode_scene_state(ram)
    assert state['room'] == 0x105
    assert state['scroll_x'] == 2816 and state['scroll_y'] == 256
    scene = ground_entities(room_export(0x105), state, 'door:2')
    assert scene['room_origin_px'] == [2560, 8192]


@pytest.mark.parametrize('target', ['door:49', 'object:2', 'prison', 'door:-1'])
def test_target_cannot_silently_resolve_to_another_entity_kind(target):
    with pytest.raises(ValueError, match='Unknown target'):
        ground_entities(room_export(), decode_scene_state(ram_for()), target)


@pytest.mark.parametrize('fault', ['wrong_rom', 'running', 'transition', 'wrong_loaded_room', 'unsupported_room', 'advanced'])
def test_capture_refuses_mismatched_or_unstable_scene(tmp_path, fault):
    rom = tmp_path/'game.sfc';rom.write_bytes(b'controlled fixture')
    bridge = Bridge(rom.read_bytes())
    if fault == 'wrong_rom': bridge.sha1 = '0'*40
    if fault == 'running': bridge.paused = False
    if fault == 'transition': bridge.ram[0x11] = 1
    if fault == 'wrong_loaded_room': bridge.ram[0x48E] = 6
    if fault == 'unsupported_room': bridge.ram[0xA0:0xA2] = bridge.ram[0x48E:0x490] = bytes([0x28, 1])
    if fault == 'advanced': bridge.frames = [100, 101]
    with patch('mesen2_client_lib.scene_context.subprocess.run', return_value=SimpleNamespace(stdout=json.dumps(room_export()))):
        with pytest.raises(ValueError):
            capture_dungeon_scene(bridge, rom, tmp_path/'z3ed', 'door:2')
    assert set(bridge.commands) <= {'STATE', 'ROMINFO'}


def test_capture_uses_same_rom_export_and_never_mutates_emulator(tmp_path):
    rom = tmp_path/'game.sfc';rom.write_bytes(b'controlled fixture')
    bridge = Bridge(rom.read_bytes())
    with patch('mesen2_client_lib.scene_context.subprocess.run', return_value=SimpleNamespace(stdout=json.dumps(room_export()))) as run:
        result = capture_dungeon_scene(bridge, rom, tmp_path/'z3ed', 'door:2')
    assert result['scene']['target']['id'] == 'door:2'
    assert result['rom_sha256'] == hashlib.sha256(rom.read_bytes()).hexdigest()
    assert '--room=005' in run.call_args.args[0]
    assert f'--rom={rom}' in run.call_args.args[0]
    assert run.call_args.kwargs.get('shell', False) is False
    assert set(bridge.commands) == {'STATE', 'ROMINFO'}


def test_report_escapes_catalog_labels_and_disclaims_completion(tmp_path):
    # Header enough for the report's size parser; it is not a rendering fixture.
    png = b'\x89PNG\r\n\x1a\n'+b'\0\0\0\rIHDR'+(256).to_bytes(4,'big')+(224).to_bytes(4,'big')
    state = decode_scene_state(ram_for());room=room_export()
    room['doors'][0]['type'] = '<script>oops</script>'
    scene = ground_entities(room, state, 'door:2')
    out=tmp_path/'review.html'
    write_scene_report(out, {'state':state,'scene':scene,'frame':100,'rom_sha256':'fixture'},png)
    text=out.read_text()
    assert '<script>' not in text and '&lt;script&gt;' in text
    assert 'does not prove traversal' in text
    assert 'Prison cell 0xF97' in text

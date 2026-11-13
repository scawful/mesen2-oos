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


class PausedReloadBridge(EntranceROMBridge):
    """Actual loader bytes plus paused WRAM IO; never connects to Mesen."""
    def __init__(self):
        super().__init__(expanded=True)
        self.memory.update({0x7E0010:7,0x7E001B:1})
        self.paused=True;self.debugging=True;self.controls=[];self.states=[]
        self.state_calls=0;self.attempts=[];self.fail_write_at=None

    def read_memory(self,address):return self.memory.get(address,0)

    def read_memory16(self,address):
        return self.read_memory(address)|(self.read_memory(address+1)<<8)

    def write_memory(self,address,value):
        assert self.paused
        self.attempts.append((address,value))
        if len(self.attempts)==self.fail_write_at:return False
        self.writes.append((address,value));self.memory[address]=value;return True

    def send_command(self,command):
        assert command=='STATE';self.state_calls+=1
        if self.states:
            state=self.states.pop(0)
            if isinstance(state,Exception):raise state
            return state
        return {'success':True,'data':{'paused':self.paused,'debugging':self.debugging}}

    def pause(self):self.controls.append('pause');self.paused=True;return True

    def resume(self):self.controls.append('resume');self.paused=False;return True


def paused_reload_cheats():
    bridge=PausedReloadBridge();cheats=entrance_cheats(bridge)
    cheats._warps={'entrances':[],'areas':[]}
    cheats.wait_play=Mock(side_effect=AssertionError('Caller owns exact frame advances'))
    return bridge,cheats


EXPECTED_RELOAD29=[(0x7E010E,0x29),(0x7E010F,0),(0x7E010A,1),
                   (0x7E04AA,0),(0x7E001B,1),(0x7E0011,0),
                   (0x7E00B0,0),(0x7E0010,5)]


def test_paused_reload29_uses_same_eight_writes_without_resume_or_wait():
    bridge,cheats=paused_reload_cheats()
    result=cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=False)
    assert bridge.writes==EXPECTED_RELOAD29 and bridge.controls==[]
    assert bridge.paused and bridge.debugging and bridge.state_calls==2
    assert result['ok'] and result['method']=='reload' and result['expected_room']=='0x0090'
    assert result['execution']=='paused_setup' and 'state' not in result
    cheats.wait_play.assert_not_called()


def test_default_reload_preserves_existing_resume_pause_and_writer():
    bridge,cheats=paused_reload_cheats()
    result=cheats.warp_entrance(0x29,method='reload',wait=False)
    assert bridge.writes==EXPECTED_RELOAD29
    assert bridge.controls==['resume','pause','resume','pause'] and bridge.paused
    assert 'execution' not in result
    cheats.wait_play.assert_not_called()


@pytest.mark.parametrize('method,wait',[('auto',False),('door',False),('reload',True),('reload',0)])
def test_paused_reload_rejects_unsupported_combinations_before_io(method,wait):
    bridge,cheats=paused_reload_cheats()
    with pytest.raises(CheatsError,match='requires method'):
        cheats.warp_entrance(0x29,method=method,wait=wait,auto_resume=False)
    assert not bridge.writes and not bridge.reads and not bridge.controls and bridge.state_calls==0


@pytest.mark.parametrize('value',[0,1,None,'false'])
def test_reload_auto_resume_requires_explicit_bool(value):
    bridge,cheats=paused_reload_cheats()
    with pytest.raises(CheatsError,match='must be a boolean'):
        cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=value)
    assert not bridge.writes and bridge.state_calls==0


@pytest.mark.parametrize('state',[
    None,{}, {'success':False,'error':'No ROM loaded'},
    {'success':True,'data':None}, {'success':True,'data':[]},
    {'success':True,'data':{'paused':False,'debugging':True}},
    {'success':True,'data':{'paused':True,'debugging':False}},
    {'success':True,'data':{'paused':True}},
    {'success':True,'data':{'paused':1,'debugging':True}},
    {'success':1,'data':{'paused':True,'debugging':True}},
    {'success':True,'error':'conflicting failure','data':{'paused':True,'debugging':True}},
])
def test_paused_reload_refuses_running_malformed_or_unowned_state_before_writes(state):
    bridge,cheats=paused_reload_cheats();bridge.states=[state]
    with pytest.raises(CheatsError,match='confirmed paused debugger'):
        cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=False)
    assert not bridge.writes and not bridge.controls and not bridge.reads


def test_paused_reload_rechecks_actual_state_immediately_before_batch():
    bridge,cheats=paused_reload_cheats()
    bridge.states=[{'success':True,'data':{'paused':True,'debugging':True}},
                   {'success':True,'data':{'paused':False,'debugging':True}}]
    with pytest.raises(CheatsError,match='confirmed paused debugger'):
        cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=False)
    assert not bridge.writes and not bridge.controls and bridge.state_calls==2


def test_paused_reload_unknown_loader_refuses_before_writes():
    bridge,cheats=paused_reload_cheats();bridge.put(0x02D99F,b'\xEA')
    with pytest.raises(CheatsError,match='Unsupported or malformed'):
        cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=False)
    assert not bridge.writes and not bridge.controls


def test_paused_reload_state_exception_refuses_before_writes():
    bridge,cheats=paused_reload_cheats();bridge.states=[TimeoutError('offline state deadline')]
    with pytest.raises(TimeoutError):
        cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=False)
    assert not bridge.writes and not bridge.controls


def test_paused_reload_write_failure_stops_without_resume_or_repair():
    bridge,cheats=paused_reload_cheats();bridge.fail_write_at=2
    with pytest.raises(CheatsError,match='write failed'):
        cheats.warp_entrance(0x29,method='reload',wait=False,auto_resume=False)
    assert bridge.attempts==EXPECTED_RELOAD29[:2] and bridge.writes==EXPECTED_RELOAD29[:1]
    assert not bridge.controls and bridge.paused
    cheats.wait_play.assert_not_called()


def test_default_reload_wait_still_uses_existing_wait_play_and_result():
    from mesen2_client_lib.oracle_cheats import GameState
    bridge,cheats=paused_reload_cheats()
    landed=GameState(7,0,1,0x87,0x90,0x29,0,0)
    cheats.wait_play=Mock(return_value=landed)
    result=cheats.warp_entrance(0x29,method='reload',modules=(7,))
    assert bridge.writes==EXPECTED_RELOAD29
    assert bridge.controls==['resume','pause','resume','pause'] and bridge.paused
    assert result['ok'] and result['state']==landed.as_dict() and 'execution' not in result
    cheats.wait_play.assert_called_once_with((7,))

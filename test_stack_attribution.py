"""Stack corruption attribution tests for Mesen2 OOS socket server.

Integration tests requiring a live Mesen2 instance with a ROM loaded.
Tests validate the MEM_WATCH_WRITES -> MEM_BLAME -> STACK_RETADDR workflow
used to diagnose stack corruption bugs in Oracle of Secrets.

Socket discovery and PING verification are handled by the ``socket_path``
and ``sock`` fixtures in conftest.py.
"""

import json
import time

import pytest


def _parse_response_data(resp):
    """Return resp['data'] as a dict. Server may send data as a JSON string."""
    raw = resp.get("data")
    if isinstance(raw, str):
        return json.loads(raw)
    return raw


def _send(sock, cmd):
    """Send a command dict over a connected socket and return the parsed response."""
    sock.sendall((json.dumps(cmd) + "\n").encode())
    buf = b""
    while b"\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    return json.loads(buf.decode().strip())


@pytest.fixture(scope="session")
def rom_loaded(sock):
    """Ensure a ROM is loaded in the running Mesen2 instance."""
    state = _send(sock, {"type": "STATE"})
    if not state.get("success"):
        pytest.skip("Cannot query Mesen2 state")
    if "No ROM" in state.get("error", ""):
        pytest.skip("No ROM loaded in Mesen2")
    return True


@pytest.fixture(autouse=True)
def cleanup_watches(sock):
    """Clear all memory watches before and after each test."""
    _send(sock, {"type": "MEM_WATCH_WRITES", "action": "clear"})
    yield
    _send(sock, {"type": "MEM_WATCH_WRITES", "action": "clear"})


# --- MEM_WATCH_WRITES tests ---

class TestMemWatchWrites:
    def test_add_returns_watch_id(self, sock, rom_loaded):
        """Verify watch creation on a stack range returns a valid watch_id."""
        resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E01FC",
            "size": "3",
            "depth": "500",
        })
        assert resp["success"], f"Failed to add watch: {resp.get('error')}"
        assert "watch_id" in resp["data"]
        assert isinstance(resp["data"]["watch_id"], int)

    def test_list_shows_active_watches(self, sock, rom_loaded):
        """Verify listing watches shows the one we just created."""
        # Add a watch first
        add_resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E01FC",
            "size": "3",
            "depth": "100",
        })
        assert add_resp["success"]
        watch_id = add_resp["data"]["watch_id"]

        # List watches
        list_resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "list",
        })
        assert list_resp["success"]
        watches = list_resp["data"]["watches"]
        assert len(watches) >= 1

        found = any(w["watch_id"] == watch_id for w in watches)
        assert found, f"Watch {watch_id} not found in list: {watches}"

        # Verify watch covers the right range
        watch = next(w for w in watches if w["watch_id"] == watch_id)
        assert watch["addr"] == "0x7E01FC" or int(watch["addr"], 0) == 0x7E01FC

    def test_add_multiple_watches(self, sock, rom_loaded):
        """Verify multiple watches can coexist."""
        ids = []
        for addr in ["0x7E01FC", "0x7E0022", "0x7E0010"]:
            resp = _send(sock, {
                "type": "MEM_WATCH_WRITES",
                "action": "add",
                "addr": addr,
                "size": "2",
                "depth": "50",
            })
            assert resp["success"]
            ids.append(resp["data"]["watch_id"])

        # All IDs should be unique
        assert len(set(ids)) == 3

        list_resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "list",
        })
        assert len(list_resp["data"]["watches"]) >= 3

    def test_remove_watch(self, sock, rom_loaded):
        """Verify a watch can be removed."""
        add_resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E01FC",
            "size": "3",
            "depth": "100",
        })
        watch_id = add_resp["data"]["watch_id"]

        rm_resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "remove",
            "watch_id": str(watch_id),
        })
        assert rm_resp["success"]

        list_resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "list",
        })
        found = any(w["watch_id"] == watch_id for w in list_resp["data"]["watches"])
        assert not found


# --- MEM_BLAME tests ---

class TestMemBlame:
    def test_blame_returns_write_log(self, sock, rom_loaded):
        """Verify MEM_BLAME returns write attribution after execution."""
        # Watch an actively-written address (Link Y position)
        resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E0020",
            "size": "2",
            "depth": "50",
        })
        assert resp["success"]
        watch_id = resp["data"]["watch_id"]

        # Let the game run briefly to generate writes
        _send(sock, {"type": "RESUME"})
        time.sleep(0.3)
        _send(sock, {"type": "PAUSE"})

        # Get blame by watch_id
        blame = _send(sock, {
            "type": "MEM_BLAME",
            "watch_id": str(watch_id),
        })
        assert blame["success"], f"MEM_BLAME failed: {blame.get('error')}"
        assert "writes" in blame["data"]
        assert "count" in blame["data"]

    def test_blame_includes_pc_and_sp(self, sock, rom_loaded):
        """Verify blame entries include PC and SP for attribution."""
        resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E0020",
            "size": "2",
            "depth": "50",
        })
        watch_id = resp["data"]["watch_id"]

        _send(sock, {"type": "RESUME"})
        time.sleep(0.3)
        _send(sock, {"type": "PAUSE"})

        blame = _send(sock, {
            "type": "MEM_BLAME",
            "watch_id": str(watch_id),
        })
        assert blame["success"]
        writes = blame["data"]["writes"]
        if len(writes) > 0:
            entry = writes[0]
            assert "pc" in entry, "Blame entry missing 'pc' field"
            assert "sp" in entry, "Blame entry missing 'sp' field"
            assert "value" in entry, "Blame entry missing 'value' field"
            assert "cycle" in entry, "Blame entry missing 'cycle' field"
            assert "addr" in entry, "Blame entry missing 'addr' field"
            assert "size" in entry, "Blame entry missing 'size' field"

    def test_blame_by_addr(self, sock, rom_loaded):
        """Verify MEM_BLAME can query by address (not just watch_id)."""
        _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E0020",
            "size": "2",
            "depth": "50",
        })

        _send(sock, {"type": "RESUME"})
        time.sleep(0.3)
        _send(sock, {"type": "PAUSE"})

        blame = _send(sock, {
            "type": "MEM_BLAME",
            "addr": "0x7E0020",
        })
        assert blame["success"], f"MEM_BLAME by addr failed: {blame.get('error')}"
        assert "writes" in blame["data"]

    def test_blame_includes_opcode(self, sock, rom_loaded):
        """Verify blame entries include the opcode that performed the write.

        This test validates Phase 1B: opcode capture in MemoryWriteRecord.
        If this fails, the opcode field has not been added to the response yet.
        """
        resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E0020",
            "size": "2",
            "depth": "50",
        })
        watch_id = resp["data"]["watch_id"]

        _send(sock, {"type": "RESUME"})
        time.sleep(0.3)
        _send(sock, {"type": "PAUSE"})

        blame = _send(sock, {
            "type": "MEM_BLAME",
            "watch_id": str(watch_id),
        })
        assert blame["success"]
        writes = blame["data"]["writes"]
        if len(writes) > 0:
            entry = writes[0]
            assert "opcode" in entry, (
                "Blame entry missing 'opcode' field - "
                "MemoryWriteRecord needs opcode capture (Phase 1B)"
            )


# --- STACK_RETADDR tests ---

class TestStackRetaddr:
    def test_stack_retaddr_decodes_rtl(self, sock, rom_loaded):
        """Verify STACK_RETADDR can decode return addresses from the stack."""
        _send(sock, {"type": "PAUSE"})

        resp = _send(sock, {"type": "STACK_RETADDR"})
        assert resp["success"], f"STACK_RETADDR failed: {resp.get('error')}"
        data = _parse_response_data(resp)
        assert isinstance(data, dict)
        assert "sp" in data
        assert "mode" in data
        assert "entries" in data

    def test_stack_retaddr_modes(self, sock, rom_loaded):
        """Verify STACK_RETADDR supports both RTL and RTS decoding modes."""
        _send(sock, {"type": "PAUSE"})

        for mode in ["rtl", "rts"]:
            resp = _send(sock, {
                "type": "STACK_RETADDR",
                "mode": mode,
            })
            assert resp["success"], f"STACK_RETADDR mode={mode} failed: {resp.get('error')}"


# --- Integration: Full blame workflow ---

class TestBlameWorkflow:
    def test_stack_region_watch_and_blame(self, sock, rom_loaded):
        """End-to-end test: watch stack region $01FC-$01FE, run, get blame."""
        # Set up watch on the exact stack region that gets corrupted
        resp = _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E01FC",
            "size": "3",
            "depth": "500",
        })
        assert resp["success"]
        watch_id = resp["data"]["watch_id"]

        # Run a few frames
        _send(sock, {"type": "RESUME"})
        time.sleep(0.5)
        _send(sock, {"type": "PAUSE"})

        # Get blame
        blame = _send(sock, {
            "type": "MEM_BLAME",
            "watch_id": str(watch_id),
        })
        assert blame["success"]
        # The stack region may or may not have writes depending on game state
        assert "writes" in blame["data"]
        assert "count" in blame["data"]

        # Also try STACK_RETADDR for the current state
        retaddr = _send(sock, {"type": "STACK_RETADDR"})
        assert retaddr["success"]

    def test_cpu_state_during_blame(self, sock, rom_loaded):
        """Verify CPU state can be read alongside blame for full context."""
        _send(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": "0x7E01FC",
            "size": "3",
            "depth": "100",
        })

        _send(sock, {"type": "RESUME"})
        time.sleep(0.2)
        _send(sock, {"type": "PAUSE"})

        # Get CPU state
        cpu = _send(sock, {"type": "CPU"})
        assert cpu["success"]
        assert "pc" in cpu["data"]
        assert "p" in cpu["data"] or "status" in cpu["data"]
        assert "sp" in cpu["data"]

        # Get blame
        blame = _send(sock, {
            "type": "MEM_BLAME",
            "addr": "0x7E01FC",
        })
        assert blame["success"]

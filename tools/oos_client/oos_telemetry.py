#!/usr/bin/env python3
"""oos_telemetry: host-side play/test recorder for Oracle of Secrets (telemetry M0).

One recorder, two backends, one JSONL schema:

  snesclassic  RetroArch network commands over UDP (default 127.0.0.1:55355, the
               oos-snesclassic USB bridge). One datagram per sample carries all
               READ_CORE_RAM commands (newline-separated); RetroArch answers each
               with its own datagram. READ_CORE_RAM takes a hex address and a
               DECIMAL length. WRAM is at 0x000000, cartridge SRAM at 0x020000.
               Read-only: the recorder never sends commands that write files or
               change emulator state on the console.
  mesen2       Mesen2-OOS socket. One BATCH of READBLOCK (+ STATE) per sample.
               Requires --instance or --socket (never auto-attaches).

No ROM, emulator, or console changes. Host clock only (the console clock is ~1970).

Output: <out-root>/<source>/<YYYY-MM-DD UTC>/<session_id>/events.jsonl
        (default out-root: <oracle-of-secrets>/Roms/telemetry, gitignored via Roms/*)

Schema oos-telemetry-v1
-----------------------
One JSON object per line. Every record has:
  type   "session_start" | "sample" | "event" | "session_end"
  seq    line counter, 0-based
  t      host UNIX time in seconds (float, ms precision)
  dt     seconds since session start (host monotonic clock)

session_start: schema, session_id, tester, source ("mesen2" | "snesclassic"),
  emulator {name, endpoint, instance?, core_status?, ...},
  rom {path, sha256, crc32, emulator_crc32?, emulator_sha1?, match?},
  flags [feature flags the build was made with, from --flags],
  sampling {target_hz, interval_ms, gap_threshold_ms, blocks [{name, space, offset, length, every}], batched},
  fields {name: "$7Exxxx/width"}, host {hostname, platform, python}, recorder {name, version, path}, note.
sample: f {field: int}  (frame, module, submodule, indoors, area, room_layout, room,
  link_x, link_y, link_dir, link_state, link_form, health, max_health, magic, rupees,
  game_state, oosprog, oosprog2, sidequest, crystals, pendants, intro_state),
  lat_ms (read round trip), emu {frame, paused} (mesen2 only).
event: kind + kind-specific keys + where {module, indoors, area, room, x, y}:
  module    from, to, from_name, to_name
  area      from, to (overworld area $8A; also emitted when leaving an interior)
  room      from, to (room $048E; emitted on room change indoors and on entering)
  form      from, to (Link form $02B2)
  hp        from, to, delta, max, t_first (increases within 0.6 s are merged)
  max_hp    from, to
  death     hp_before (module becomes $12)
  item      addr, name, from, to   (inventory/equipment/stats bytes in $7EF340-$7EF3A0)
  flag      addr, name, from, to, set, cleared, region ("story", "room", "overworld", "save")
            room/overworld flags carry room_id/area; bulk=true, count=N replaces
            per-byte events when more than 24 bytes change in one sample
  save      slot, changed_bytes (SRAM slot body changed; identical re-saves are not seen)
  warp      reason ("mirror" | "state_load" | "teleport"), from {...}, to {...}
  pause     paused (mesen2 STATE / RetroArch GET_STATUS)
  content   from, to (RetroArch GET_STATUS content/crc32 changed mid-session: wrong data source)
  capture   label, files [...] (mesen2: PNG + savestate; snesclassic: WRAM + SRAM dumps)
  marker    text, origin ("cli" | "stdin")
  gap       start_dt, duration_s, missed, reason ("error" | "late" | "capture"), error
session_end: reason, summary {duration_s, samples, target_hz, achieved_hz, gaps, gap_s,
  max_interval_ms, lat_ms {p50, p95, max}, events {kind: n}, deaths, saves, warps,
  first {...}, last {...}}.

Item/flag/hp/area/room events are only emitted while both samples are in a gameplay
module (not title, file select, name entry, load, or attract); entering gameplay
re-baselines silently so loading a file does not flood the log.

Usage
-----
  oos_telemetry.py record --source mesen2 --instance oos-telemetry-m0 --duration 300 \\
      --rom Roms/TestBuilds/<build>/oos168x.sfc --flags a,b,c --tester scawful
  oos_telemetry.py record --source snesclassic --rom <same .sfc as deployed> --wait 600
  oos_telemetry.py mark "boss door softlock"         # marker into the active session
  oos_telemetry.py capture --label after-boss        # capture into the active session
  oos_telemetry.py summarize <session_dir|events.jsonl>
  oos_telemetry.py schema                            # print this schema text

While `record` runs in a terminal, typing a line + Enter adds a marker; a line
starting with "!capture" requests a capture.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
import platform
import re
import secrets
import select
import signal
import socket
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCHEMA = "oos-telemetry-v1"
RECORDER_VERSION = "0.1.0"
SCRIPT_PATH = Path(__file__).resolve()
SCRIPT_DIR = SCRIPT_PATH.parent

# Oracle of Secrets checkout (same rule as mesen2_client_lib.paths).
OOS_ROOT = Path(
    os.environ.get("OOS_ROOT")
    or os.environ.get("ORACLE_OF_SECRETS_ROOT")
    or SCRIPT_PATH.parents[3] / "oracle-of-secrets"
).expanduser().resolve()
DEFAULT_OUT_ROOT = OOS_ROOT / "Roms" / "telemetry"


# ---------------------------------------------------------------------------
# Memory layout
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Block:
    name: str
    space: str      # "wram" (offset from $7E0000) or "sram" (cartridge RAM offset)
    offset: int
    length: int
    every: int = 1  # read every Nth sample


BLOCKS = [
    Block("lo", "wram", 0x0000, 0x100),          # $7E0000-$7E00FF: module, frame, Link, area
    Block("form", "wram", 0x02B2, 1),            # $7E02B2 Link form
    Block("room", "wram", 0x048E, 2),            # $7E048E room
    Block("save", "wram", 0xF300, 0x120),        # $7EF300-$7EF41F story, items, stats
    Block("rooms", "wram", 0xF000, 0x300, 10),   # $7EF000-$7EF2FF room + overworld flags (1 Hz at 10 Hz)
    # SRAM slots 1-3 (main copies), one block each: RetroArch 1.9.10 drops replies for
    # reads of 3840 bytes over the bridge (1280 works).
    Block("slot1", "sram", 0x0000, 0x500, 10),
    Block("slot2", "sram", 0x0500, 0x500, 10),
    Block("slot3", "sram", 0x0A00, 0x500, 10),
]
SLOT_BLOCKS = ("slot1", "slot2", "slot3")
SLOW_BLOCKS = tuple(b.name for b in BLOCKS if b.every > 1)
BLOCK_BY_NAME = {b.name: b for b in BLOCKS}

# name: (address, width). Same addresses as oos-snesclassic/tools/ra_state.py FIELDS
# (and Mesen's OraclePanelBridge), plus oosprog2 and sidequest.
FIELDS = {
    "frame": (0x7E001A, 1),
    "module": (0x7E0010, 1), "submodule": (0x7E0011, 1), "indoors": (0x7E001B, 1),
    "area": (0x7E008A, 1), "room_layout": (0x7E00A0, 1), "room": (0x7E048E, 2),
    "link_x": (0x7E0022, 2), "link_y": (0x7E0020, 2), "link_dir": (0x7E002F, 1),
    "link_state": (0x7E005D, 1), "link_form": (0x7E02B2, 1),
    "health": (0x7EF36D, 1), "max_health": (0x7EF36C, 1), "magic": (0x7EF36E, 1),
    "rupees": (0x7EF360, 2), "game_state": (0x7EF3C5, 1), "oosprog": (0x7EF3D6, 1),
    "oosprog2": (0x7EF3C6, 1), "sidequest": (0x7EF3D7, 1),
    "crystals": (0x7EF37A, 1), "pendants": (0x7EF374, 1), "intro_state": (0x7EF39E, 1),
}

MODULES = {
    0x00: "Title", 0x01: "File Select", 0x02: "Copy File", 0x03: "Erase File", 0x04: "Name Entry",
    0x05: "Load File", 0x06: "Dungeon Load", 0x07: "Dungeon", 0x08: "OW Load", 0x09: "Overworld",
    0x0A: "Special OW Load", 0x0B: "Special OW", 0x0C: "Arrival", 0x0D: "Experiment Scene",
    0x0E: "Interface", 0x0F: "Spotlight Close", 0x10: "Spotlight Open", 0x11: "Falling", 0x12: "Death",
    0x13: "Boss Victory", 0x14: "Attract", 0x15: "Mirror Warp", 0x16: "Crystal Victory",
    0x17: "Save & Quit", 0x18: "Ganon Emerges", 0x19: "Triforce Room", 0x1A: "Ending", 0x1B: "Spawn Select",
}
NON_GAMEPLAY = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x14}
MODULE_DEATH = 0x12
MODULE_MIRROR = 0x15

# Bytes in the "save" block ($7EF300-$7EF41F) that are not diffed (own event or noise).
SAVE_DIFF_EXCLUDE = (
    {0x7EF36C, 0x7EF36D, 0x7EF36E}              # max hp / hp (own events), magic (samples only)
    | {0x7EF361, 0x7EF362, 0x7EF363}            # rupee high byte (read as 16-bit) + HUD display counter
    | {0x7EF372, 0x7EF373, 0x7EF375, 0x7EF376}  # refill counters
    | set(range(0x7EF3CD, 0x7EF3D3))            # follower position cache
    | set(range(0x7EF3D9, 0x7EF3E3))            # player name + checksum
)
ITEM_RANGE = range(0x7EF340, 0x7EF3A0)          # items, equipment, bottles, stats, keys, collectibles
BULK_THRESHOLD = 24
HP_MERGE_S = 0.6


def load_sram_names(root: Path) -> dict[int, str]:
    """Map $7EFxxx addresses to Oracle names from Core/sram.asm (first label wins)."""
    names: dict[int, str] = {}
    path = root / "Core" / "sram.asm"
    try:
        text = path.read_text(errors="replace")
    except OSError:
        return names
    for m in re.finditer(r"^\s*([A-Za-z_]\w*)\s*=\s*\$7EF([0-9A-Fa-f]{3})\b", text, re.M):
        names.setdefault(0x7EF000 + int(m.group(2), 16), m.group(1))
    return names


def wram_addr(block: Block, index: int) -> int:
    return 0x7E0000 + block.offset + index


def decode_fields(data: dict[str, bytes]) -> dict[str, int]:
    out: dict[str, int] = {}
    for name, (addr, width) in FIELDS.items():
        off = addr - 0x7E0000
        for bname in ("lo", "form", "room", "save"):
            blk = BLOCK_BY_NAME[bname]
            raw = data.get(bname)
            if raw is not None and blk.offset <= off and off + width <= blk.offset + len(raw):
                i = off - blk.offset
                out[name] = int.from_bytes(raw[i:i + width], "little")
                break
    return out


# ---------------------------------------------------------------------------
# Backends
# ---------------------------------------------------------------------------

class BackendError(Exception):
    pass


class RetroArchBackend:
    """RetroArch UDP network commands (read-only subset)."""

    source = "snesclassic"
    SRAM_BASE = 0x20000

    def __init__(self, host: str, port: int, timeout: float) -> None:
        self.host, self.port, self.timeout = host, port, timeout
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.batched = True
        self.status: dict[str, Any] = {}

    # -- transport ---------------------------------------------------------
    def _drain(self) -> None:
        self.sock.setblocking(False)
        try:
            while True:
                self.sock.recvfrom(65535)
        except (BlockingIOError, OSError):
            pass
        finally:
            self.sock.setblocking(True)

    def _exchange(self, commands: list[str], expect: int) -> list[str]:
        self._drain()
        replies: list[str] = []
        deadline = time.monotonic() + self.timeout
        if self.batched:
            self.sock.sendto("\n".join(commands).encode() + b"\n", (self.host, self.port))
        else:
            for c in commands:
                self.sock.sendto(c.encode() + b"\n", (self.host, self.port))
        while len(replies) < expect:
            left = deadline - time.monotonic()
            if left <= 0:
                break
            self.sock.settimeout(left)
            try:
                data, _ = self.sock.recvfrom(65535)
            except socket.timeout:
                break
            for line in data.decode(errors="replace").splitlines():
                if line.strip():
                    replies.append(line.strip())
        return replies

    def command(self, text: str) -> str | None:
        r = self._exchange([text], 1)
        return r[0] if r else None

    @staticmethod
    def _addr(block: Block, start: int = 0) -> int:
        base = RetroArchBackend.SRAM_BASE if block.space == "sram" else 0
        return base + block.offset + start

    # -- API ---------------------------------------------------------------
    def read(self, blocks: list[Block], want_status: bool = False) -> tuple[dict[str, bytes], dict[str, Any]]:
        cmds = [f"READ_CORE_RAM {self._addr(b):x} {b.length}" for b in blocks]
        if want_status:
            cmds.append("GET_STATUS")
        replies = self._exchange(cmds, len(cmds))
        by_addr: dict[int, list[str]] = {}
        extra: dict[str, Any] = {}
        for r in replies:
            parts = r.split()
            if parts[0] == "READ_CORE_RAM" and len(parts) >= 3:
                try:
                    by_addr[int(parts[1], 16)] = parts[2:]
                except ValueError:
                    continue
            elif parts[0] == "GET_STATUS":
                extra["status"] = self._parse_status(r)
        out: dict[str, bytes] = {}
        for b in blocks:
            toks = by_addr.get(self._addr(b))
            if toks is None:
                raise BackendError(f"no READ_CORE_RAM reply for {b.name} (got {len(replies)}/{len(cmds)} replies)")
            if toks[:1] == ["-1"]:
                raise BackendError(f"READ_CORE_RAM {self._addr(b):x} -> -1 (no game or no memory map)")
            raw = bytes(int(x, 16) for x in toks)
            if len(raw) != b.length:
                raise BackendError(f"READ_CORE_RAM {b.name}: {len(raw)} bytes, wanted {b.length}")
            out[b.name] = raw
        if "status" in extra:
            self.status = extra["status"]
            extra["paused"] = extra["status"].get("state") == "PAUSED"
        return out, extra

    @staticmethod
    def _parse_status(line: str) -> dict[str, Any]:
        # "GET_STATUS PLAYING super_nes,CLV-H-OOSRA,crc32=68476e09" | "GET_STATUS CONTENTLESS"
        parts = line.split(None, 2)
        st: dict[str, Any] = {"raw": line, "state": parts[1] if len(parts) > 1 else None}
        if len(parts) > 2:
            fields = parts[2].split(",")
            st["system"] = fields[0]
            if len(fields) > 1:
                st["content"] = fields[1]
            for f in fields[2:]:
                if f.startswith("crc32="):
                    st["crc32"] = f[6:].upper()
        return st

    def probe(self) -> dict[str, Any]:
        """Check the link, decide batched vs one-datagram-per-command, return identity."""
        version = self.command("VERSION")
        status_line = self.command("GET_STATUS")
        status = self._parse_status(status_line) if status_line else {}
        test = [Block("p0", "wram", 0x0010, 1), Block("p1", "wram", 0x048E, 2)]
        self.batched = True
        try:
            self.read(test)
        except BackendError as exc:
            self.batched = False
            try:
                self.read(test)
            except BackendError:
                raise BackendError(f"RetroArch not answering READ_CORE_RAM ({exc}); "
                                   f"VERSION={version!r} GET_STATUS={status_line!r}") from None
        self.status = status
        return {"name": "RetroArch", "endpoint": f"udp://{self.host}:{self.port}", "version": version,
                "core_status": status, "batched": self.batched}

    def rom_crc32(self) -> str | None:
        return self.status.get("crc32")

    def capture(self, dest: Path, stem: str) -> list[str]:
        """Read-only capture: WRAM (128 KB) and SRAM (8 KB) dumps via READ_CORE_RAM."""
        files = []
        for space, size, suffix in (("wram", 0x20000, "wram.bin"), ("sram", 0x2000, "sram.bin")):
            chunk, per = 0x400, 8
            data = bytearray()
            blocks = [Block(f"c{o:x}", space, o, chunk) for o in range(0, size, chunk)]
            for i in range(0, len(blocks), per):
                got, _ = self.read(blocks[i:i + per])
                for b in blocks[i:i + per]:
                    data += got[b.name]
            p = dest / f"{stem}-{suffix}"
            p.write_bytes(bytes(data))
            files.append(p.name)
        return files

    def close(self) -> None:
        self.sock.close()


class MesenBackend:
    """Mesen2-OOS socket: one BATCH of READBLOCK (+ STATE) per sample."""

    source = "mesen2"

    def __init__(self, socket_path: str, instance: str | None, timeout: float) -> None:
        sys.path.insert(0, str(SCRIPT_DIR))
        from mesen2_client_lib.bridge import MesenBridge  # noqa: E402
        self.bridge = MesenBridge(socket_path, auto_reconnect=False)
        self.socket_path, self.instance, self.timeout = socket_path, instance, timeout
        self.rominfo: dict[str, Any] = {}
        self.batched = True

    @staticmethod
    def resolve_socket(instance: str | None, socket_path: str | None) -> str:
        if socket_path:
            return socket_path
        if not instance:
            raise BackendError("mesen2 needs --instance or --socket (the recorder never auto-attaches)")
        reg_dir = Path(os.getenv("MESEN2_REGISTRY_DIR") or OOS_ROOT / ".context" / "scratchpad" / "mesen2" / "instances")
        rec = reg_dir / f"{instance}.json"
        if rec.is_file():
            try:
                sp = json.loads(rec.read_text()).get("socket")
                if sp:
                    return sp
            except json.JSONDecodeError:
                pass
        fallback = f"/tmp/mesen2-{instance}.sock"
        if os.path.exists(fallback):
            return fallback
        raise BackendError(f"could not resolve Mesen2 instance {instance!r} to a socket")

    def _send(self, cmd: str, params: dict[str, Any] | None = None, timeout: float | None = None) -> dict[str, Any]:
        try:
            res = self.bridge.send_command(cmd, params or {}, timeout=timeout or self.timeout)
        except Exception as exc:  # ConnectionError, TimeoutError
            raise BackendError(f"{cmd}: {exc}") from None
        if not res.get("success"):
            raise BackendError(f"{cmd}: {res.get('error')}")
        return res

    @staticmethod
    def _readblock(b: Block) -> dict[str, str]:
        if b.space == "sram":
            return {"type": "READBLOCK", "addr": f"0x{b.offset:06X}", "len": str(b.length), "memtype": "SnesSaveRam"}
        return {"type": "READBLOCK", "addr": f"0x{0x7E0000 + b.offset:06X}", "len": str(b.length)}

    def read(self, blocks: list[Block], want_status: bool = False) -> tuple[dict[str, bytes], dict[str, Any]]:
        cmds = [self._readblock(b) for b in blocks] + [{"type": "STATE"}]
        res = self._send("BATCH", {"commands": json.dumps(cmds)})
        data = res.get("data")
        if isinstance(data, str):
            data = json.loads(data)
        results = (data or {}).get("results", [])
        if len(results) != len(cmds):
            raise BackendError(f"BATCH returned {len(results)}/{len(cmds)} results")
        out: dict[str, bytes] = {}
        for b, r in zip(blocks, results):
            if not r.get("success"):
                raise BackendError(f"READBLOCK {b.name}: {r.get('error')}")
            raw = bytes.fromhex(str(r.get("data", "")).replace('"', ""))
            if len(raw) != b.length:
                raise BackendError(f"READBLOCK {b.name}: {len(raw)} bytes, wanted {b.length}")
            out[b.name] = raw
        st = results[-1].get("data") or {}
        extra = {"emu": {"frame": st.get("frame"), "paused": bool(st.get("paused"))},
                 "paused": bool(st.get("paused"))}
        return out, extra

    def probe(self) -> dict[str, Any]:
        self._send("PING", timeout=2.0)
        info = self._send("ROMINFO").get("data") or {}
        if isinstance(info, str):
            info = json.loads(info)
        self.rominfo = info
        self.read([Block("p0", "wram", 0x0010, 1)])
        return {"name": "Mesen2-OOS", "endpoint": self.socket_path, "instance": self.instance,
                "rominfo": info, "batched": True}

    def rom_crc32(self) -> str | None:
        c = self.rominfo.get("crc32")
        return c.upper() if isinstance(c, str) else None

    def capture(self, dest: Path, stem: str) -> list[str]:
        files = []
        res = self._send("SCREENSHOT", timeout=5.0)
        png = res.get("data")
        if isinstance(png, str):
            p = dest / f"{stem}.png"
            p.write_bytes(base64.b64decode(png.strip('"')))
            files.append(p.name)
        mss = dest / f"{stem}.mss"
        self._send("SAVESTATE", {"path": str(mss), "allow_external": "true"}, timeout=5.0)
        for _ in range(20):  # the state file is written asynchronously
            if mss.exists() and mss.stat().st_size:
                files.append(mss.name)
                break
            time.sleep(0.05)
        return files

    def close(self) -> None:
        pass


# ---------------------------------------------------------------------------
# Session writer + event detection
# ---------------------------------------------------------------------------

def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def file_hashes(path: Path) -> dict[str, str]:
    data = path.read_bytes()
    return {"sha256": hashlib.sha256(data).hexdigest(), "sha1": hashlib.sha1(data).hexdigest().upper(),
            "crc32": f"{binascii.crc32(data) & 0xFFFFFFFF:08X}"}


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(round(q * (len(s) - 1))))], 1)


class Recorder:
    def __init__(self, backend, args, session_dir: Path, session_id: str) -> None:
        self.backend, self.args = backend, args
        self.session_dir, self.session_id = session_dir, session_id
        self.fh = open(session_dir / "events.jsonl", "a", buffering=1)
        self.control_path = session_dir / "control.jsonl"
        self.control_path.touch()
        self.control_pos = 0
        self.seq = 0
        self.t0_mono = time.monotonic()
        self.names = load_sram_names(OOS_ROOT)
        # state
        self.prev: dict[str, Any] | None = None     # last good sample: fields, blocks, emu, t
        self.samples = 0
        self.latencies: list[float] = []
        self.max_interval = 0.0
        self.event_counts: dict[str, int] = {}
        self.gaps = 0
        self.gap_s = 0.0
        self.gap_open: dict[str, Any] | None = None
        self.hp_pending: dict[str, Any] | None = None
        self.last_hp_alive = None
        self.paused: bool | None = None
        self.content: str | None = None
        self.first: dict[str, Any] | None = None
        self.stop_reason: str | None = None
        self.capture_n = 0
        # Slow blocks (read every Nth sample) whose baseline predates a state load or a
        # non-gameplay stretch: the next fresh read re-baselines them instead of diffing.
        self.stale: set[str] = set()

    # -- output --------------------------------------------------------------
    def write(self, rtype: str, **payload: Any) -> None:
        rec = {"type": rtype, "seq": self.seq, "t": round(time.time(), 3),
               "dt": round(time.monotonic() - self.t0_mono, 3)}
        rec.update(payload)
        self.fh.write(json.dumps(rec, separators=(",", ":")) + "\n")
        self.seq += 1

    def event(self, kind: str, where: dict[str, Any] | None = None, **payload: Any) -> None:
        self.event_counts[kind] = self.event_counts.get(kind, 0) + 1
        if where is not None:
            payload["where"] = where
        self.write("event", kind=kind, **payload)
        if not self.args.quiet:
            brief = {k: v for k, v in payload.items() if k != "where"}
            print(f"[{time.monotonic() - self.t0_mono:7.1f}s] {kind:8s} {json.dumps(brief, separators=(',', ':'))}",
                  flush=True)

    @staticmethod
    def where(f: dict[str, int]) -> dict[str, Any]:
        return {"module": f.get("module"), "indoors": f.get("indoors"), "area": f.get("area"),
                "room": f.get("room"), "x": f.get("link_x"), "y": f.get("link_y")}

    # -- session ---------------------------------------------------------------
    def start(self, ident: dict[str, Any], rom: dict[str, Any]) -> None:
        a = self.args
        interval = 1.0 / a.hz
        self.write(
            "session_start", schema=SCHEMA, session_id=self.session_id, tester=a.tester,
            source=self.backend.source, emulator=ident, rom=rom,
            flags=[x for x in (a.flags or "").split(",") if x],
            sampling={"target_hz": a.hz, "interval_ms": round(interval * 1000, 1),
                      "gap_threshold_ms": a.gap_ms, "batched": getattr(self.backend, "batched", True),
                      "blocks": [{"name": b.name, "space": b.space, "offset": b.offset,
                                  "length": b.length, "every": b.every} for b in BLOCKS]},
            fields={k: f"${v[0]:06X}/{v[1]}" for k, v in FIELDS.items()},
            host={"hostname": socket.gethostname(), "platform": platform.platform(),
                  "python": platform.python_version(), "utc": utc_now().isoformat(timespec="seconds")},
            recorder={"name": "oos_telemetry", "version": RECORDER_VERSION, "path": str(SCRIPT_PATH)},
            note=a.note or "",
        )

    def finish(self, reason: str) -> dict[str, Any]:
        now = time.monotonic()
        if self.gap_open:
            self.close_gap(now, reason="end")
        self.flush_hp(force=True)
        duration = now - self.t0_mono
        last = self.prev["f"] if self.prev else None
        summary = {
            "duration_s": round(duration, 2), "samples": self.samples, "target_hz": self.args.hz,
            "achieved_hz": round(self.samples / duration, 2) if duration > 0 else 0.0,
            "gaps": self.gaps, "gap_s": round(self.gap_s, 2), "max_interval_ms": round(self.max_interval * 1000, 1),
            "lat_ms": {"p50": percentile(self.latencies, 0.5), "p95": percentile(self.latencies, 0.95),
                       "max": round(max(self.latencies), 1) if self.latencies else None},
            "events": dict(sorted(self.event_counts.items())),
            "deaths": self.event_counts.get("death", 0), "saves": self.event_counts.get("save", 0),
            "warps": self.event_counts.get("warp", 0),
            "first": self.first, "last": last,
        }
        self.write("session_end", reason=reason, summary=summary)
        self.fh.close()
        return summary

    # -- gaps --------------------------------------------------------------------
    def open_gap(self, now: float, error: str, reason: str = "error") -> None:
        if self.gap_open is None:
            start = self.prev["t"] if self.prev else now
            self.gap_open = {"start": start, "missed": 0, "error": error, "reason": reason}
        self.gap_open["missed"] += 1
        self.gap_open["error"] = error

    def close_gap(self, now: float, reason: str | None = None) -> None:
        g = self.gap_open
        self.gap_open = None
        if not g:
            return
        dur = now - g["start"]
        self.gaps += 1
        self.gap_s += dur
        self.event("gap", start_dt=round(g["start"] - self.t0_mono, 3), duration_s=round(dur, 3),
                   missed=g["missed"], reason=reason or g["reason"], error=g["error"])

    # -- hp merge ----------------------------------------------------------------
    def flush_hp(self, force: bool = False, now: float | None = None) -> None:
        p = self.hp_pending
        if not p:
            return
        if not force and now is not None and now - p["t_last"] < HP_MERGE_S:
            return
        self.hp_pending = None
        self.event("hp", p["where"], **{"from": p["from"], "to": p["to"], "delta": p["to"] - p["from"],
                                        "max": p["max"], "t_first": round(p["t_first"] - self.t0_mono, 3)})

    def note_hp(self, old: int, new: int, maxhp: int, where: dict[str, Any], now: float) -> None:
        p = self.hp_pending
        rising = new > old
        if p and p["rising"] == rising and rising and now - p["t_last"] < HP_MERGE_S:
            p.update(to=new, t_last=now, max=maxhp, where=where)
            return
        self.flush_hp(force=True)
        self.hp_pending = {"from": old, "to": new, "rising": rising, "t_first": now, "t_last": now,
                           "max": maxhp, "where": where}
        if not rising:
            self.flush_hp(force=True)  # damage is discrete: write now

    # -- diffing -------------------------------------------------------------------
    def diff_bytes(self, name: str, old: bytes, new: bytes, where: dict[str, Any]) -> None:
        blk = BLOCK_BY_NAME[name]
        changed = []
        for i, (a, b) in enumerate(zip(old, new)):
            if a == b:
                continue
            addr = wram_addr(blk, i)
            if name == "save" and addr in SAVE_DIFF_EXCLUDE:
                continue
            changed.append((addr, a, b))
        if not changed:
            return
        if len(changed) > BULK_THRESHOLD:
            self.event("flag", where, bulk=True, count=len(changed), block=name,
                       first=f"${changed[0][0]:06X}", last=f"${changed[-1][0]:06X}")
            return
        for addr, a, b in changed:
            label = self.names.get(addr)
            if name == "save" and addr == 0x7EF360:
                a16 = int.from_bytes(old[0x60:0x62], "little")
                b16 = int.from_bytes(new[0x60:0x62], "little")
                self.event("item", where, addr=f"${addr:06X}", name="Rupees", **{"from": a16, "to": b16})
                continue
            if name == "save" and addr in ITEM_RANGE:
                self.event("item", where, addr=f"${addr:06X}", name=label, **{"from": a, "to": b})
                continue
            extra: dict[str, Any] = {}
            if name == "rooms":
                off = addr - 0x7EF000
                if off < 0x280:
                    extra = {"region": "room", "room_id": f"${off // 2:03X}"}
                else:
                    extra = {"region": "overworld", "area": f"${off - 0x280:02X}"}
            else:
                extra = {"region": "save" if addr >= 0x7EF3D9 and addr not in (0x7EF3C5, 0x7EF3C6) else "story"}
            self.event("flag", where, addr=f"${addr:06X}", name=label, set=f"${(b & ~a) & 0xFF:02X}",
                       cleared=f"${(a & ~b) & 0xFF:02X}", **{"from": a, "to": b}, **extra)

    def diff_slot(self, slot: int, o: bytes, n: bytes, where: dict[str, Any]) -> None:
        if o != n:
            self.event("save", where, slot=slot, changed_bytes=sum(1 for x, y in zip(o, n) if x != y))

    def detect(self, cur: dict[str, Any], now: float) -> None:
        prev = self.prev
        f = cur["f"]
        w = self.where(f)
        if prev is None:
            return
        p = prev["f"]
        dt = now - prev["t"]
        pm, cm = p.get("module"), f.get("module")
        # module
        if pm != cm:
            self.event("module", w, **{"from": pm, "to": cm, "from_name": MODULES.get(pm, f"${pm:02X}"),
                                       "to_name": MODULES.get(cm, f"${cm:02X}")})
            if cm == MODULE_DEATH:
                self.flush_hp(force=True)
                self.event("death", w, hp_before=self.last_hp_alive)
            if cm == MODULE_MIRROR:
                self.event("warp", w, reason="mirror", **{"from": self.where(p), "to": w})
        # pause (mesen STATE, RetroArch GET_STATUS)
        if "paused" in cur and cur["paused"] is not None and cur["paused"] != self.paused:
            if self.paused is not None:
                self.event("pause", w, paused=cur["paused"])
            self.paused = cur["paused"]
        # running content (RetroArch GET_STATUS, ~1 Hz)
        st = cur.get("status")
        if st:
            ident = f"{st.get('state')} {st.get('content')} crc32={st.get('crc32')}"
            if self.content is not None and ident.split(" ", 1)[1] != self.content.split(" ", 1)[1]:
                self.event("content", w, **{"from": self.content, "to": ident})
            self.content = ident
        # emulator discontinuity (savestate load / reset) - mesen2 only
        state_load = False
        pe, ce = (prev.get("emu") or {}).get("frame"), (cur.get("emu") or {}).get("frame")
        if isinstance(pe, int) and isinstance(ce, int):
            expected = dt * 60.0
            if ce < pe or ce - pe > expected * 2 + 120:
                state_load = True
                self.event("warp", w, reason="state_load", emu_frame_from=pe, emu_frame_to=ce,
                           **{"from": self.where(p), "to": w})
        fresh = cur.get("fresh", set())
        if state_load:
            self.stale.update(SLOW_BLOCKS)  # savestates also restore SRAM
        # SRAM slot bodies: a save can land during Save & Quit, so not gated on gameplay.
        if not state_load:
            for i, name in enumerate(SLOT_BLOCKS, 1):
                if name not in fresh:
                    continue
                if name in self.stale:
                    self.stale.discard(name)
                    continue
                if name in prev["blocks"]:
                    self.diff_slot(i, prev["blocks"][name], cur["blocks"][name], w)
        gameplay = pm not in NON_GAMEPLAY and cm not in NON_GAMEPLAY
        if not gameplay or state_load:
            self.stale.update(n for n in SLOW_BLOCKS if n not in SLOT_BLOCKS)
            return  # re-baseline silently
        # area / room / form
        if p.get("indoors") != f.get("indoors"):
            if f.get("indoors"):
                self.event("room", w, **{"from": None, "to": f.get("room"), "via": "enter"})
            else:
                self.event("area", w, **{"from": None, "to": f.get("area"), "via": "exit"})
        else:
            if f.get("indoors") and p.get("room") != f.get("room"):
                self.event("room", w, **{"from": p.get("room"), "to": f.get("room")})
            if not f.get("indoors") and p.get("area") != f.get("area"):
                self.event("area", w, **{"from": p.get("area"), "to": f.get("area")})
            # teleport heuristic (same interior/exterior; stairs and room changes excluded)
            if cm in (0x07, 0x09) and pm in (0x07, 0x09) and dt < 0.5:
                jump = max(abs(f["link_x"] - p["link_x"]), abs(f["link_y"] - p["link_y"]))
                same_place = (p.get("room") == f.get("room")) if f.get("indoors") else (p.get("area") == f.get("area"))
                if (same_place and jump > 0x100) or (not f.get("indoors") and not same_place and jump > 0x400):
                    self.event("warp", w, reason="teleport", jump=jump, **{"from": self.where(p), "to": w})
        if p.get("link_form") != f.get("link_form"):
            self.event("form", w, **{"from": p.get("link_form"), "to": f.get("link_form")})
        # hp
        if p.get("max_health") != f.get("max_health"):
            self.event("max_hp", w, **{"from": p.get("max_health"), "to": f.get("max_health")})
        if p.get("health") != f.get("health"):
            self.note_hp(p["health"], f["health"], f.get("max_health"), w, now)
        # sram mirror diffs
        for name in ("save", "rooms"):
            if name not in fresh:
                continue
            if name in self.stale:
                self.stale.discard(name)
                continue
            if name in prev["blocks"]:
                self.diff_bytes(name, prev["blocks"][name], cur["blocks"][name], w)

    # -- control (markers / captures) -----------------------------------------------
    def poll_control(self) -> list[dict[str, Any]]:
        out = []
        try:
            size = self.control_path.stat().st_size
        except OSError:
            return out
        if size <= self.control_pos:
            return out
        with open(self.control_path) as fh:
            fh.seek(self.control_pos)
            chunk = fh.read()
            self.control_pos = fh.tell()
        for line in chunk.splitlines():
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return out

    def poll_stdin(self) -> list[dict[str, Any]]:
        if self.args.no_stdin or not sys.stdin.isatty():
            return []
        out = []
        while select.select([sys.stdin], [], [], 0)[0]:
            line = sys.stdin.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            if line.startswith("!capture"):
                out.append({"cmd": "capture", "label": line[8:].strip() or "stdin", "origin": "stdin"})
            else:
                out.append({"cmd": "mark", "text": line, "origin": "stdin"})
        return out

    def handle_control(self, items: list[dict[str, Any]]) -> None:
        w = self.where(self.prev["f"]) if self.prev else None
        for c in items:
            if c.get("cmd") == "mark":
                self.event("marker", w, text=c.get("text", ""), origin=c.get("origin", "cli"))
            elif c.get("cmd") == "capture":
                self.capture_n += 1
                label = re.sub(r"[^A-Za-z0-9._-]+", "_", c.get("label") or "capture")[:40]
                stem = f"capture{self.capture_n:02d}-{label}"
                cdir = self.session_dir / "captures"
                cdir.mkdir(exist_ok=True)
                t0 = time.monotonic()
                try:
                    files = self.backend.capture(cdir, stem)
                    err = None
                except (BackendError, OSError) as exc:
                    files, err = [], str(exc)
                self.event("capture", w, label=c.get("label") or "capture", note=c.get("note", ""),
                           files=[f"captures/{x}" for x in files], took_s=round(time.monotonic() - t0, 3),
                           origin=c.get("origin", "cli"), error=err)

    # -- main loop -----------------------------------------------------------------
    def run(self, duration: float) -> str:
        interval = 1.0 / self.args.hz
        gap_threshold = self.args.gap_ms / 1000.0
        next_t = time.monotonic()
        n = 0
        while self.stop_reason is None:
            now = time.monotonic()
            if duration and now - self.t0_mono >= duration:
                self.stop_reason = "duration"
                break
            if now < next_t:
                time.sleep(min(next_t - now, 0.05))
                continue
            next_t += interval
            if next_t < now - interval:  # fell behind (host stall): resync, gap logic reports it
                next_t = now + interval
            blocks = [b for b in BLOCKS if n % b.every == 0]
            want_status = (n % 10 == 0)
            n += 1
            t_req = time.monotonic()
            try:
                data, extra = self.backend.read(blocks, want_status=want_status)
            except BackendError as exc:
                self.open_gap(time.monotonic(), str(exc))
                continue
            t_done = time.monotonic()
            lat = (t_done - t_req) * 1000.0
            if self.prev:
                interval_s = t_done - self.prev["t"]
                self.max_interval = max(self.max_interval, interval_s)
                if self.gap_open:
                    self.close_gap(t_done)
                elif interval_s > gap_threshold:
                    self.gap_open = {"start": self.prev["t"], "missed": max(0, int(interval_s / interval) - 1),
                                     "error": None, "reason": self.args._gap_reason or "late"}
                    self.close_gap(t_done)
            elif self.gap_open:
                self.close_gap(t_done)
            self.args._gap_reason = None
            merged = dict(self.prev["blocks"]) if self.prev else {}
            merged.update(data)
            fields = decode_fields(merged)
            cur = {"f": fields, "blocks": merged, "fresh": set(data), "t": t_done, "emu": extra.get("emu"),
                   "paused": extra.get("paused"), "status": extra.get("status")}
            rec: dict[str, Any] = {"f": fields, "lat_ms": round(lat, 1)}
            if extra.get("emu"):
                rec["emu"] = extra["emu"]
            self.write("sample", **rec)
            self.samples += 1
            self.latencies.append(lat)
            if self.first is None:
                self.first = fields
            self.detect(cur, t_done)
            if fields.get("health"):
                self.last_hp_alive = fields.get("health")
            self.flush_hp(now=t_done)
            self.prev = cur
            ctl = self.poll_control() + self.poll_stdin()
            if ctl:
                self.handle_control(ctl)
                if any(c.get("cmd") == "capture" for c in ctl):
                    self.args._gap_reason = "capture"
        return self.stop_reason or "stopped"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def active_dir(out_root: Path) -> Path:
    return out_root / "active"


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def find_active(out_root: Path, source: str | None) -> Path:
    cands = []
    for p in sorted(active_dir(out_root).glob("*.json")):
        try:
            d = json.loads(p.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        if source and d.get("source") != source:
            continue
        if pid_alive(int(d.get("pid", 0))):
            cands.append(Path(d["session_dir"]))
    if not cands:
        raise SystemExit("no active recording found (pass --session DIR)")
    if len(cands) > 1:
        raise SystemExit(f"several active recordings; pass --source or --session: {', '.join(map(str, cands))}")
    return cands[0]


def cmd_record(a: argparse.Namespace) -> int:
    a._gap_reason = None
    out_root = Path(a.out_root).expanduser().resolve()
    if a.source == "mesen2":
        sock = MesenBackend.resolve_socket(a.instance, a.socket)
        backend: Any = MesenBackend(sock, a.instance, a.timeout)
    else:
        backend = RetroArchBackend(a.host, a.port, a.timeout)

    # Wait for the emulator/game to answer before opening a session.
    deadline = time.monotonic() + a.wait
    while True:
        try:
            ident = backend.probe()
            break
        except BackendError as exc:
            if time.monotonic() >= deadline:
                print(f"oos_telemetry: {exc}", file=sys.stderr)
                return 2
            print(f"waiting for {a.source}: {exc}", file=sys.stderr, flush=True)
            time.sleep(min(5.0, max(0.5, deadline - time.monotonic())))

    rom: dict[str, Any] = {"path": None, "sha256": None, "crc32": None}
    emu_crc = backend.rom_crc32()
    if a.rom:
        rp = Path(a.rom).expanduser().resolve()
        h = file_hashes(rp)
        rom = {"path": str(rp), **h}
    if emu_crc:
        rom["emulator_crc32"] = emu_crc
        if rom.get("crc32"):
            rom["match"] = rom["crc32"] == emu_crc
    if a.source == "mesen2" and backend.rominfo.get("sha1"):
        rom["emulator_sha1"] = backend.rominfo["sha1"].upper()
        rom["emulator_filename"] = backend.rominfo.get("filename")
        if rom.get("sha1"):
            rom["match"] = rom["sha1"] == rom["emulator_sha1"]
    if rom.get("match") is False and not a.allow_rom_mismatch:
        print(f"oos_telemetry: --rom does not match the running game ({rom}); "
              "pass --allow-rom-mismatch to record anyway", file=sys.stderr)
        return 2

    start = utc_now()
    session_id = f"{start:%Y%m%dT%H%M%SZ}-{a.source}-{secrets.token_hex(2)}"
    session_dir = out_root / a.source / f"{start:%Y-%m-%d}" / session_id
    session_dir.mkdir(parents=True, exist_ok=False)
    rec = Recorder(backend, a, session_dir, session_id)
    act = active_dir(out_root)
    act.mkdir(parents=True, exist_ok=True)
    act_file = act / f"{session_id}.json"
    act_file.write_text(json.dumps({"session_dir": str(session_dir), "pid": os.getpid(), "source": a.source}))

    def _stop(signum, _frame):
        rec.stop_reason = signal.Signals(signum).name.lower()

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    print(f"recording {a.source} -> {session_dir / 'events.jsonl'}", flush=True)
    try:
        rec.start(ident, rom)
        reason = rec.run(a.duration)
    except Exception as exc:  # keep the log closed cleanly
        reason = f"error: {exc}"
        raise
    finally:
        summary = rec.finish(reason if "reason" in locals() else "error")
        backend.close()
        act_file.unlink(missing_ok=True)
        print(json.dumps({"session_dir": str(session_dir), "reason": locals().get("reason"),
                          **{k: summary[k] for k in ("duration_s", "samples", "achieved_hz", "gaps", "gap_s",
                                                     "max_interval_ms", "lat_ms", "events")}}, indent=1))
    return 0


def append_control(a: argparse.Namespace, item: dict[str, Any]) -> None:
    out_root = Path(a.out_root).expanduser().resolve()
    session = Path(a.session).expanduser().resolve() if a.session else find_active(out_root, a.source)
    ctl = session / "control.jsonl"
    if not ctl.exists():
        raise SystemExit(f"{ctl} not found (is this a session directory?)")
    item["t"] = round(time.time(), 3)
    with open(ctl, "a") as fh:
        fh.write(json.dumps(item) + "\n")
    print(f"queued {item['cmd']} -> {session}")


def cmd_mark(a: argparse.Namespace) -> int:
    append_control(a, {"cmd": "mark", "text": " ".join(a.text), "origin": "cli"})
    return 0


def cmd_capture(a: argparse.Namespace) -> int:
    append_control(a, {"cmd": "capture", "label": a.label, "note": a.note or "", "origin": "cli"})
    return 0


def summarize(path: Path) -> dict[str, Any]:
    if path.is_dir():
        path = path / "events.jsonl"
    start = end = None
    samples = 0
    events: list[dict[str, Any]] = []
    first_dt = last_dt = None
    for line in path.read_text().splitlines():
        r = json.loads(line)
        if r["type"] == "session_start":
            start = r
        elif r["type"] == "session_end":
            end = r
        elif r["type"] == "sample":
            samples += 1
            first_dt = r["dt"] if first_dt is None else first_dt
            last_dt = r["dt"]
        elif r["type"] == "event":
            events.append(r)
    counts: dict[str, int] = {}
    for e in events:
        counts[e["kind"]] = counts.get(e["kind"], 0) + 1
    out = {"file": str(path), "session_id": start and start.get("session_id"), "source": start and start.get("source"),
           "tester": start and start.get("tester"), "rom_sha256": start and (start.get("rom") or {}).get("sha256"),
           "samples": samples, "events": dict(sorted(counts.items())),
           "end": end and {"reason": end.get("reason"), **{k: end["summary"].get(k) for k in (
               "duration_s", "achieved_hz", "gaps", "gap_s", "max_interval_ms", "lat_ms")}}}
    if end is None and first_dt is not None and last_dt and last_dt > first_dt:
        out["achieved_hz_estimate"] = round((samples - 1) / (last_dt - first_dt), 2)
    out["timeline"] = [
        f"{e['dt']:7.1f}s {e['kind']:8s} " + json.dumps({k: v for k, v in e.items()
                                                         if k not in ("type", "seq", "t", "dt", "kind", "where")},
                                                        separators=(",", ":"))
        for e in events]
    return out


def cmd_summarize(a: argparse.Namespace) -> int:
    s = summarize(Path(a.path).expanduser())
    timeline = s.pop("timeline")
    print(json.dumps(s, indent=1))
    if not a.no_timeline:
        print("\n".join(timeline[: a.limit] if a.limit else timeline))
    return 0


def cmd_schema(_a: argparse.Namespace) -> int:
    doc = __doc__ or ""
    i = doc.find("Schema oos-telemetry-v1")
    j = doc.find("Usage\n-----")
    print(doc[i:j].rstrip())
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        prog="oos_telemetry.py", description=(__doc__ or "").split("\n\n")[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Schema: run `oos_telemetry.py schema`. Logs: <out-root>/<source>/<date>/<session_id>/events.jsonl")
    ap.add_argument("--out-root", default=str(DEFAULT_OUT_ROOT), help=f"log root (default {DEFAULT_OUT_ROOT})")
    sub = ap.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("record", help="record a session", formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    r.add_argument("--source", required=True, choices=["mesen2", "snesclassic"])
    r.add_argument("--duration", type=float, default=0, help="seconds to record (0 = until Ctrl-C)")
    r.add_argument("--hz", type=float, default=10.0, help="target sample rate")
    r.add_argument("--gap-ms", type=float, default=300.0, help="interval between good samples that counts as a gap")
    r.add_argument("--timeout", type=float, default=0.25, help="per-sample read timeout (s)")
    r.add_argument("--wait", type=float, default=0, help="wait up to N s for the game to answer before starting")
    r.add_argument("--tester", default=os.environ.get("USER", "unknown"))
    r.add_argument("--rom", help="the .sfc being played (hashed on the Mac; CRC/SHA-1 checked against the emulator)")
    r.add_argument("--allow-rom-mismatch", action="store_true")
    r.add_argument("--flags", default="", help="comma list of feature flags the build was made with")
    r.add_argument("--note", default="", help="free text stored in session_start")
    r.add_argument("--quiet", action="store_true", help="do not print events to stdout")
    r.add_argument("--no-stdin", action="store_true", help="ignore terminal input (markers)")
    g = r.add_argument_group("mesen2")
    g.add_argument("--instance", help="Mesen2 registry instance name")
    g.add_argument("--socket", help="Mesen2 socket path or tcp://host:port")
    g = r.add_argument_group("snesclassic")
    g.add_argument("--host", default="127.0.0.1")
    g.add_argument("--port", type=int, default=55355)
    r.set_defaults(func=cmd_record)

    m = sub.add_parser("mark", help="add a marker event to the active session")
    m.add_argument("text", nargs="+")
    m.add_argument("--session", help="session directory (default: the single active recording)")
    m.add_argument("--source", choices=["mesen2", "snesclassic"])
    m.set_defaults(func=cmd_mark)

    c = sub.add_parser("capture", help="ask the active recorder for a capture")
    c.add_argument("--label", default="capture")
    c.add_argument("--note")
    c.add_argument("--session")
    c.add_argument("--source", choices=["mesen2", "snesclassic"])
    c.set_defaults(func=cmd_capture)

    s = sub.add_parser("summarize", help="summarize a session log")
    s.add_argument("path", help="session directory or events.jsonl")
    s.add_argument("--no-timeline", action="store_true")
    s.add_argument("--limit", type=int, default=0)
    s.set_defaults(func=cmd_summarize)

    sc = sub.add_parser("schema", help="print the oos-telemetry-v1 schema")
    sc.set_defaults(func=cmd_schema)

    a = ap.parse_args(argv)
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())

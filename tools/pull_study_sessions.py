#!/usr/bin/env python3
"""Pull Android Study Sessions from Retroid via adb and generate session_report.md timelines."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

DEFAULT_SERIAL = "4ecb9f77"
REMOTE_SESSIONS_ROOT = "/storage/emulated/0/Android/data/ca.mesen.oos/files/Captures/Sessions"
DEFAULT_OUT_DIR = Path.home() / "Zelda" / "StudySessions"


def fmt_mmss(seconds: int | float | None) -> str:
    if seconds is None:
        return "00:00"
    sec = max(0, int(seconds))
    return f"{sec // 60:02d}:{sec % 60:02d}"


def hex2(val: int | None) -> str:
    if val is None or val < 0:
        return "—"
    return f"${val & 0xFF:02X}"


def hex4(val: int | None) -> str:
    if val is None or val < 0:
        return "—"
    return f"${val & 0xFFFF:04X}"


def pull_sessions(serial: str, out_dir: Path, session_name: str | None) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    if session_name:
        remote = f"{REMOTE_SESSIONS_ROOT}/{session_name}"
        subprocess.run(["adb", "-s", serial, "pull", remote, str(out_dir)], check=True)
        pulled = out_dir / session_name
        return [pulled] if pulled.is_dir() else []

    ls_proc = subprocess.run(
        ["adb", "-s", serial, "shell", f"ls -1 {REMOTE_SESSIONS_ROOT} 2>/dev/null"],
        capture_output=True, text=True, check=False,
    )
    names = [line.strip() for line in ls_proc.stdout.splitlines() if line.strip()]
    dirs: list[Path] = []
    for name in names:
        remote = f"{REMOTE_SESSIONS_ROOT}/{name}"
        subprocess.run(["adb", "-s", serial, "pull", remote, str(out_dir)], check=True)
        local = out_dir / name
        if local.is_dir():
            dirs.append(local)
    return dirs


def parse_ndjson(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    events: list[dict[str, Any]] = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            if isinstance(obj, dict):
                events.append(obj)
        except json.JSONDecodeError:
            continue
    return events


def write_session_report(session_dir: Path) -> Path:
    session_json_path = session_dir / "session.json"
    bookmarks_json_path = session_dir / "bookmarks.json"
    telemetry_path = session_dir / "telemetry.ndjson"

    session: dict[str, Any] = {}
    if session_json_path.is_file():
        session = json.loads(session_json_path.read_text(encoding="utf-8"))
    bookmarks_data: dict[str, Any] = {}
    if bookmarks_json_path.is_file():
        bookmarks_data = json.loads(bookmarks_json_path.read_text(encoding="utf-8"))

    events = parse_ndjson(telemetry_path)
    transitions = [e for e in events if e.get("event") == "transition"]
    anomalies_ndjson = [e for e in events if e.get("event") == "anomaly"]

    rom_info = session.get("rom", {}) if isinstance(session.get("rom"), dict) else {}
    counts = session.get("counts", {}) if isinstance(session.get("counts"), dict) else {}
    segments = session.get("segments", []) if isinstance(session.get("segments"), list) else []
    anchors = session.get("anchors", []) if isinstance(session.get("anchors"), list) else []
    anomalies = session.get("anomalies", []) if isinstance(session.get("anomalies"), list) else []
    bookmarks = bookmarks_data.get("bookmarks", []) if isinstance(bookmarks_data.get("bookmarks"), list) else []

    lines: list[str] = [
        f"# Study Session Report — `{session_dir.name}`",
        "",
        f"- **ROM:** `{rom_info.get('name', 'oracle')}` (SHA-1: `{rom_info.get('sha1', '') or '—'}`)",
        f"- **Started (UTC):** `{session.get('startedUtc', '—')}`",
        f"- **Duration:** `{fmt_mmss(session.get('durationSec', 0))}` | **Clean Shutdown:** `{session.get('cleanShutdown', False)}` (`{session.get('stopReason', '') or 'running'}`)",
        f"- **Counts:** `{counts.get('segments', len(segments))}` video segs | `{counts.get('transitions', len(transitions))}` transitions | `{counts.get('bookmarks', len(bookmarks))}` bookmarks | `{counts.get('anomalies', len(anomalies))}` anomalies | `{counts.get('samples', 0)}` telemetry samples",
        "",
        "## Video Segments",
        "",
        "| Seg | File | Session Window | Size | Closed |",
        "|---:|---|---|---:|---|",
    ]
    for seg in segments:
        fname = seg.get("file", "")
        link = f"[{fname}](file://{(session_dir / fname).resolve()})" if fname else "—"
        window = f"{fmt_mmss(seg.get('startSec'))} – {fmt_mmss(seg.get('endSec'))}"
        size_mb = (seg.get("bytes", 0) or 0) / (1024 * 1024)
        lines.append(f"| #{seg.get('index', '?')} | {link} | `{window}` | {size_mb:.2f} MB | `{seg.get('closed', False)}` |")
    if not segments:
        lines.append("| — | *(none)* | — | — | — |")

    lines.extend([
        "",
        "## Bookmarks (Menu-Only, Non-Evicted)",
        "",
        "| # | Tag | Video Timestamp | Location (Mod/Area/Room/World) | State & Screenshot |",
        "|---:|---|---|---|---|",
    ])
    for bm in bookmarks:
        seg_file = bm.get("seg_file") or f"video_seg_{int(bm.get('seg', 1)):03d}.mp4"
        ts = f"`{seg_file} @ {fmt_mmss(bm.get('seg_sec', 0))}` (total `{fmt_mmss(bm.get('session_sec', 0))}`)"
        loc = f"mod `{hex2(bm.get('module'))}.{hex2(bm.get('sub'))}` · ind `{bm.get('indoors')}` · area `{hex2(bm.get('area'))}` · rm `{hex4(bm.get('room'))}` · w `{hex2(bm.get('world'))}` ({bm.get('x')},{bm.get('y')})"
        parts = []
        if bm.get("state"):
            parts.append(f"[{Path(bm['state']).name}](file://{(session_dir / bm['state']).resolve()})")
        if bm.get("screenshot"):
            parts.append(f"[{Path(bm['screenshot']).name}](file://{(session_dir / bm['screenshot']).resolve()})")
        lines.append(f"| {bm.get('index', '?')} | `{bm.get('tag', '')}` | {ts} | {loc} | {' · '.join(parts) or '—'} |")
    if not bookmarks:
        lines.append("| — | *(no bookmarks)* | — | — | — |")

    lines.extend([
        "",
        "## Auto-Detected Anomalies",
        "",
        "| # | Kind | Video Timestamp | Detail | State & Screenshot |",
        "|---:|---|---|---|---|",
    ])
    if anomalies:
        for an in anomalies:
            seg_file = an.get("segFile") or f"video_seg_{int(an.get('seg', 1)):03d}.mp4"
            ts = f"`{seg_file} @ {fmt_mmss(an.get('seg_sec', an.get('segSec', 0)))}`"
            parts = []
            if an.get("state"):
                parts.append(f"[{Path(an['state']).name}](file://{(session_dir / an['state']).resolve()})")
            if an.get("screenshot"):
                parts.append(f"[{Path(an['screenshot']).name}](file://{(session_dir / an['screenshot']).resolve()})")
            lines.append(f"| {an.get('index', '?')} | `{an.get('kind', '')}` | {ts} | {an.get('detail', '')} | {' · '.join(parts) or '—'} |")
    elif anomalies_ndjson:
        for idx, an in enumerate(anomalies_ndjson, 1):
            seg_file = f"video_seg_{int(an.get('seg', 1)):03d}.mp4"
            ts = f"`{seg_file} @ {fmt_mmss(an.get('seg_sec', 0))}`"
            lines.append(f"| {idx} | `{an.get('kind', '')}` | {ts} | {an.get('detail', '')} | — |")
    else:
        lines.append("| — | *(none)* | — | — | — |")

    lines.extend([
        "",
        "## Area & Room Transitions",
        "",
        "| Video Timestamp | Mod.Sub | Ind | Area | Room | World | Pos |",
        "|---|---|---|---|---|---|---|",
    ])
    for tr in transitions[:200]:
        seg_file = f"video_seg_{int(tr.get('seg', 1)):03d}.mp4"
        ts = f"`{seg_file} @ {fmt_mmss(tr.get('seg_sec', 0))}`"
        lines.append(
            f"| {ts} | `{hex2(tr.get('module'))}.{hex2(tr.get('sub'))}` | `{tr.get('indoors')}` | `{hex2(tr.get('area'))}` | `{hex4(tr.get('room'))}` | `{hex2(tr.get('world'))}` | `({tr.get('x')},{tr.get('y')})` |"
        )
    if not transitions:
        lines.append("| — | *(no transitions logged)* | — | — | — | — | — |")

    lines.extend([
        "",
        "## Rolling Recovery Anchors (FIFO max 24)",
        "",
    ])
    if anchors:
        for anc in anchors:
            rel = anc.get("file", "")
            link = f"[{Path(rel).name}](file://{(session_dir / rel).resolve()})" if rel else "—"
            seg_file = f"video_seg_{int(anc.get('seg', 1)):03d}.mp4"
            lines.append(
                f"- {link} — `{anc.get('reason', '')}` at `{seg_file} @ {fmt_mmss(anc.get('segSec', 0))}` (`mod={hex2(anc.get('module'))} area={hex2(anc.get('area'))} rm={hex4(anc.get('room'))}`)"
            )
    else:
        lines.append("- *(none)*")

    out_path = session_dir / "session_report.md"
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out_path


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Pull and index Android Study Sessions from Retroid.")
    ap.add_argument("--serial", default=DEFAULT_SERIAL, help=f"adb device serial (default: {DEFAULT_SERIAL})")
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR, help=f"Local destination folder (default: {DEFAULT_OUT_DIR})")
    ap.add_argument("--session", default=None, help="Specific remote session folder name to pull")
    ap.add_argument("--no-pull", action="store_true", help="Do not call adb; index existing session folder(s) under --out-dir")
    args = ap.parse_args(argv)

    if args.no_pull:
        if args.session:
            cand = args.out_dir / args.session
            session_dirs = [cand] if cand.is_dir() else []
        elif (args.out_dir / "session.json").is_file() or (args.out_dir / "telemetry.ndjson").is_file():
            session_dirs = [args.out_dir]
        elif args.out_dir.is_dir():
            session_dirs = sorted(p for p in args.out_dir.iterdir() if p.is_dir() and ((p / "session.json").is_file() or (p / "telemetry.ndjson").is_file()))
        else:
            session_dirs = []
    else:
        session_dirs = pull_sessions(args.serial, args.out_dir, args.session)

    if not session_dirs:
        print("No study sessions found.")
        return 0

    for sdir in session_dirs:
        report = write_session_report(sdir)
        print(f"Indexed {sdir.name} -> {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

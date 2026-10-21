#!/usr/bin/env python3
"""Mesen Studio: screenshots, recordings with audio, a virtual camera and a live
browser view for any Mesen2-OOS instance, driven over its socket (no window or
computer-use needed). Works for any yaze project (.yaze) or ROM; per-hack
settings live in an optional <Project>.mesen.json sidecar.

Examples:
  mesen_studio.py project ~/src/hobby/oracle-of-secrets/Oracle-of-Secrets.yaze
  mesen_studio.py --instance agent-a shot /tmp/now.png --preset closeup
  mesen_studio.py --instance agent-a record start /tmp/run.avi --preset trailer
  mesen_studio.py --instance agent-a record stop
  mesen_studio.py export /tmp/run.avi /tmp/run.mp4 --scale 4
  mesen_studio.py --instance agent-a live --port 8770
  mesen_studio.py --instance agent-a reel shots.json --out ~/Movies/oos-trailer --export-scale 4
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from studio_lib.client import MesenSocket, StudioError, resolve_socket  # noqa: E402
from studio_lib.live import LiveSession  # noqa: E402
from studio_lib.media import export_command  # noqa: E402
from studio_lib.project import StudioProject, load_project  # noqa: E402
from studio_lib.reel import run_reel  # noqa: E402


def emit(data: Any) -> None:
    print(json.dumps(data, indent=2) if not isinstance(data, str) else data)


def connect(args: argparse.Namespace) -> MesenSocket:
    return MesenSocket(resolve_socket(args.socket, args.instance))


def current_project(args: argparse.Namespace, mesen: MesenSocket | None) -> StudioProject:
    """--project wins; otherwise ask the emulator which project/ROM it loaded."""
    if args.project:
        return load_project(args.project)
    if mesen is not None:
        info = mesen.request("PROJECT")
        if info.get("loaded"):
            return load_project(info["project"])
        rom_path = info.get("current_rom")
        if rom_path and Path(rom_path).exists():
            return load_project(rom_path)
    return StudioProject()


def parse_size(value: str | None) -> tuple[int, int] | None:
    if not value:
        return None
    width, _, height = value.lower().partition("x")
    return int(width), int(height)


def apply_camera(mesen: MesenSocket, project: StudioProject, preset: str | None, size: str | None) -> None:
    params: dict[str, Any] = project.camera_params(preset) if preset else {}
    dims = parse_size(size)
    if dims:
        params["width"], params["height"] = dims
    if params:
        mesen.request("CAMERA", action="set", **params)


def absolute(path: str) -> Path:
    return Path(path).expanduser().resolve()


def cmd_project(args: argparse.Namespace) -> int:
    mesen = None
    if not args.file and not args.project:
        mesen = connect(args)
    project = load_project(args.file) if args.file else current_project(args, mesen)
    emit(project.describe())
    return 0


def cmd_shot(args: argparse.Namespace) -> int:
    mesen = connect(args)
    use_camera = bool(args.preset or args.size or args.camera)
    if use_camera:
        apply_camera(mesen, current_project(args, mesen), args.preset, args.size)
    emit(mesen.request("SCREENSHOT", path=absolute(args.out), camera=use_camera, overwrite=args.overwrite))
    return 0


def cmd_record(args: argparse.Namespace) -> int:
    mesen = connect(args)
    if args.action == "status":
        emit(mesen.request("RECORD", action="status"))
    elif args.action == "stop":
        emit(mesen.request("RECORD", action="stop", kind=args.kind))
    else:
        if not args.out:
            raise StudioError("record start needs an output path")
        use_camera = bool(args.preset or args.size or args.camera)
        if use_camera:
            apply_camera(mesen, current_project(args, mesen), args.preset, args.size)
        out = absolute(args.out)
        emit(mesen.request("RECORD", action="start", path=out, camera=use_camera, codec=args.codec, overwrite=args.overwrite))
        if args.wav:
            emit(mesen.request("RECORD", action="start", path=out.with_suffix(".wav"), overwrite=args.overwrite))
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    cmd = export_command(absolute(args.src), absolute(args.dst), scale=args.scale, crf=args.crf, prores=args.prores)
    return subprocess.run(cmd).returncode


def cmd_camera(args: argparse.Namespace) -> int:
    mesen = connect(args)
    if args.action == "get":
        emit(mesen.request("CAMERA", action="get"))
    elif args.action == "reset":
        emit(mesen.request("CAMERA", action="reset"))
    elif args.action == "preset":
        params = current_project(args, mesen).camera_params(args.name)
        emit(mesen.request("CAMERA", action="set", **params))
    elif args.action == "set":
        params = dict(item.split("=", 1) for item in args.params)
        emit(mesen.request("CAMERA", action="set", **params))
    elif args.action == "pan":
        frames = args.frames if args.frames else round(args.seconds * 60.0988)
        emit(mesen.request("CAMERA", action="pan", x=args.x, y=args.y, zoom=args.zoom, frames=frames,
                           ease="linear" if args.linear else "smooth"))
    elif args.action == "presets":
        project = current_project(args, mesen)
        emit({"adapter": project.adapter, "presets": project.presets(), "follow_sources": project.follow_sources()})
    return 0


def cmd_live(args: argparse.Namespace) -> int:
    mesen = connect(args)
    project = current_project(args, mesen)
    if args.preset or args.size:
        apply_camera(mesen, project, args.preset, args.size)
    label = args.label or project.name or "Mesen"
    session = LiveSession(mesen, args.port, args.scale, args.every, not args.no_audio, label=label,
                          max_pixels=args.max_stream_pixels)
    try:
        url = session.start(replace=args.replace)
        print(f"Live view: {url}  (HLS: {url}hls/live.m3u8). Ctrl-C stops the stream, not the emulator.", flush=True)
        code = session.wait()
    finally:
        session.stop()
    return code


def cmd_reel(args: argparse.Namespace) -> int:
    mesen = connect(args)
    project = current_project(args, mesen)
    out_dir = absolute(args.out) if args.out else project.capture_dir()
    if out_dir is None:
        raise StudioError("reel needs --out, or capture_dir in the project's .mesen.json profile")
    emit(run_reel(mesen, project, absolute(args.reel), out_dir, overwrite=args.overwrite, export_scale=args.export_scale))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--socket", help="Emulator socket path")
    parser.add_argument("--instance", help="Instance name (socket /tmp/mesen2-NAME.sock)")
    parser.add_argument("--project", help=".yaze project or ROM used for presets (default: what the emulator loaded)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("project", help="Show the resolved project, profile, adapter and presets")
    p.add_argument("file", nargs="?", help=".yaze project or ROM (default: ask the emulator)")
    p.set_defaults(func=cmd_project)

    def camera_opts(p: argparse.ArgumentParser) -> None:
        p.add_argument("--preset", help="Camera preset (full, trailer, closeup, square, or profile presets)")
        p.add_argument("--size", help="Camera output size, e.g. 1280x720")

    p = sub.add_parser("shot", help="Save a PNG screenshot")
    p.add_argument("out")
    p.add_argument("--camera", action="store_true", help="Frame through the current camera")
    p.add_argument("--overwrite", action="store_true")
    camera_opts(p)
    p.set_defaults(func=cmd_shot)

    p = sub.add_parser("record", help="Record video with audio (AVI/GIF) or audio only (WAV)")
    p.add_argument("action", choices=["start", "stop", "status"])
    p.add_argument("out", nargs="?", help="Output .avi/.gif/.wav (start)")
    p.add_argument("--camera", action="store_true", help="Record through the current camera")
    p.add_argument("--codec", default="zmbv", choices=["zmbv", "cscd", "raw"])
    p.add_argument("--wav", action="store_true", help="Also write a separate WAV next to the video")
    p.add_argument("--kind", default="all", choices=["all", "video", "audio"], help="What to stop")
    p.add_argument("--overwrite", action="store_true")
    camera_opts(p)
    p.set_defaults(func=cmd_record)

    p = sub.add_parser("export", help="Convert a recording to MP4 (H.264) or MOV (ProRes) with pixel-perfect scaling")
    p.add_argument("src")
    p.add_argument("dst")
    p.add_argument("--scale", type=int, default=4)
    p.add_argument("--crf", type=int, default=14)
    p.add_argument("--prores", action="store_true")
    p.set_defaults(func=cmd_export)

    p = sub.add_parser("camera", help="Control the virtual capture camera")
    csub = p.add_subparsers(dest="action", required=True)
    csub.add_parser("get")
    csub.add_parser("reset")
    csub.add_parser("presets")
    cp = csub.add_parser("preset")
    cp.add_argument("name")
    cp = csub.add_parser("set", help="Raw CAMERA params, e.g. mode=manual x=128 y=112 zoom=2")
    cp.add_argument("params", nargs="+")
    cp = csub.add_parser("pan")
    cp.add_argument("--x", type=float)
    cp.add_argument("--y", type=float)
    cp.add_argument("--zoom", type=float)
    cp.add_argument("--seconds", type=float, default=2.0)
    cp.add_argument("--frames", type=int)
    cp.add_argument("--linear", action="store_true")
    p.set_defaults(func=cmd_camera)

    p = sub.add_parser("live", help="Serve a live browser view (video + audio) of the instance")
    p.add_argument("--port", type=int, default=8770)
    p.add_argument("--scale", type=int, help="Viewer size as a multiple of the camera size (default 2 for game-size, else 1)")
    p.add_argument("--every", type=int, default=1, help="Send every Nth frame (2 = 30 fps)")
    p.add_argument("--max-stream-pixels", type=int, default=640 * 360,
                   help="Raw frames larger than this are downscaled in the emulator before the pipe")
    p.add_argument("--no-audio", action="store_true")
    p.add_argument("--replace", action="store_true", help="Take over a stream another viewer is still reading")
    p.add_argument("--label")
    camera_opts(p)
    p.set_defaults(func=cmd_live)

    p = sub.add_parser("reel", help="Capture a scripted shot list (savestate + camera moves) for trailers")
    p.add_argument("reel")
    p.add_argument("--out", help="Output folder (default: profile capture_dir)")
    p.add_argument("--export-scale", type=int, default=0, help="Also export MP4s at this scale")
    p.add_argument("--overwrite", action="store_true")
    p.set_defaults(func=cmd_reel)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except (StudioError, KeyError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

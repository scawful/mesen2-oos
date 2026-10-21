#!/usr/bin/env python3
"""Native smoke check of the capture commands against one running instance.

Needs a ROM loaded in the instance (headless is fine) and ffmpeg/ffprobe.
  native_smoke.py --socket /tmp/mesen2-NAME.sock --out /tmp/studio-smoke [--yaze PATH]
Prints one JSON report; exits 1 if any check fails. Resumes the emulator.
"""

from __future__ import annotations

import argparse
import json
import shutil
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from studio_lib.client import MesenSocket, StudioError  # noqa: E402
from studio_lib.project import load_project  # noqa: E402


def png_size(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:24]
    return struct.unpack(">II", data[16:24])


def probe(path: Path) -> dict:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height,sample_rate",
                          "-show_entries", "format=duration", "-of", "json", str(path)],
                         capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def wait_frames(mesen: MesenSocket, count: int, timeout: float = 30.0) -> None:
    start = int(mesen.request("STATE")["frame"])
    deadline = time.time() + timeout
    while int(mesen.request("STATE")["frame"]) - start < count:
        if time.time() > deadline:
            raise StudioError(f"emulator did not advance {count} frames")
        time.sleep(0.05)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--yaze", help="Expected loaded .yaze project")
    parser.add_argument("--live-seconds", type=float, default=8.0)
    args = parser.parse_args()

    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    mesen = MesenSocket(args.socket)
    results: dict[str, dict] = {}

    def check(name: str, ok: bool, **detail) -> None:
        results[name] = {"pass": bool(ok), **detail}

    project = mesen.request("PROJECT")
    if args.yaze:
        check("project", project.get("loaded") and Path(project["project"]) == Path(args.yaze).resolve()
              and project.get("current_rom") == project.get("rom"), data=project)
    studio_project = load_project(project["project"]) if project.get("loaded") else load_project(project["current_rom"])

    mesen.request("RESUME")
    wait_frames(mesen, 60)

    full = out / "full.png"
    mesen.request("SCREENSHOT", path=full, overwrite=True)
    check("screenshot_full", full.exists() and png_size(full)[0] > 0, size=png_size(full))
    refused = False
    try:
        mesen.request("SCREENSHOT", path=full)
    except StudioError:
        refused = True
    check("screenshot_no_overwrite", refused)

    mesen.request("CAMERA", action="reset")
    mesen.request("CAMERA", action="set", **studio_project.camera_params("trailer"))
    cam = out / "camera.png"
    shot = mesen.request("SCREENSHOT", path=cam, camera=True, overwrite=True)
    check("screenshot_camera", png_size(cam) == (1280, 720), size=png_size(cam), data=shot)

    follow = mesen.request("CAMERA", action="set", **studio_project.camera_params("closeup"))
    wait_frames(mesen, 30)
    after = mesen.request("CAMERA", action="get")
    check("camera_follow", follow["mode"] == "follow" and after["view"]["w"] < after["game_width"], data=after)

    avi, wav = out / "clip.avi", out / "clip.wav"
    mesen.request("RECORD", action="start", path=avi, camera=True, overwrite=True)
    mesen.request("RECORD", action="start", path=wav, overwrite=True)
    status = mesen.request("RECORD", action="status")
    pinned = False
    try:
        mesen.request("CAMERA", action="set", width=640, height=360)
    except StudioError:
        pinned = True
    mesen.request("CAMERA", action="pan", x=128, y=112, zoom=1, frames=60)
    wait_frames(mesen, 180)
    mesen.request("RECORD", action="stop")
    check("record_status", status["video"]["active"] and status["video"]["camera"] and status["audio"]["active"], data=status)
    check("camera_size_pinned_while_recording", pinned)
    info = probe(avi)
    kinds = {s["codec_type"]: s for s in info["streams"]}
    check("record_avi", kinds.get("video", {}).get("width") == 1280 and "audio" in kinds
          and float(info["format"]["duration"]) >= 2.5, probe=info)
    wav_info = probe(wav)
    check("record_wav", float(wav_info["format"]["duration"]) >= 2.5, probe=wav_info)

    mp4 = out / "clip.mp4"
    export = subprocess.run([sys.executable, str(ROOT / "mesen_studio.py"), "export", str(avi), str(mp4), "--scale", "2"],
                            capture_output=True, text=True)
    check("export_mp4", export.returncode == 0 and probe(mp4)["streams"][0]["width"] == 2560, stderr=export.stderr[-400:])

    mesen.request("CAMERA", action="reset")
    port = 8779
    live = subprocess.Popen([sys.executable, str(ROOT / "mesen_studio.py"), "--socket", args.socket, "live",
                             "--port", str(port), "--preset", "trailer"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        time.sleep(args.live_seconds)
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=5) as r:
            live_status = json.load(r)
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/hls/live.m3u8", timeout=5) as r:
            playlist = r.read().decode()
        segment = next(line for line in playlist.splitlines() if line and not line.startswith("#"))
        seg_path = out / "live_segment.m4s"
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/hls/{segment}", timeout=5) as r:
            seg_path.write_bytes(r.read())
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/hls/init.mp4", timeout=5) as r:
            init = r.read()
        (out / "live_sample.mp4").write_bytes(init + seg_path.read_bytes())
        sample = probe(out / "live_sample.mp4")
        sample_kinds = {s["codec_type"] for s in sample["streams"]}
        stream = live_status["live"]
        video = stream["video"]
        drop_ratio = video["dropped"] / max(1, video["sent"] + video["dropped"])
        expected_audio = stream["sample_rate"] * 4 * args.live_seconds
        audio_ratio = stream["audio"]["bytes"] / expected_audio
        check("live", video["connected"] and stream["audio"]["connected"] and sample_kinds == {"video", "audio"}
              and audio_ratio >= 0.5,
              drop_ratio=round(drop_ratio, 3), audio_ratio=round(audio_ratio, 3), stream=stream, streams=sample["streams"])
    except Exception as exc:  # noqa: BLE001 - report any failure in the live chain
        check("live", False, error=repr(exc))
    finally:
        live.terminate()
        try:
            live_log = live.communicate(timeout=15)[0]
        except subprocess.TimeoutExpired:
            live.kill()
            live_log = live.communicate()[0]
    stopped = mesen.request("LIVE", action="status")
    check("live_stopped", not stopped["active"], log=live_log[-600:])
    mesen.request("CAMERA", action="reset")

    failed = [name for name, result in results.items() if not result["pass"]]
    print(json.dumps({"pass": not failed, "failed": failed, "results": results}, indent=2, default=str))
    return 1 if failed else 0


if __name__ == "__main__":
    if not shutil.which("ffprobe"):
        sys.exit("ffprobe not found")
    sys.exit(main())

"""Scripted multi-shot capture ("reels") for trailer footage.

Reel file (JSON):
{
  "shots": [
    {"name": "village", "state": "states/village.mss", "preset": "trailer",
     "camera": {"x": 128, "y": 100}, "seconds": 6,
     "pans": [{"at": 1.0, "x": 180, "y": 120, "zoom": 2.0, "seconds": 3, "ease": "smooth"}]}
  ]
}

Timing counts emulated frames, so clips have the same length at any emulation speed.
Gameplay input is not driven here: use savestates of scenes, or record around an
agent's own input with `record start/stop`.
"""

from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path
from typing import Any

from .client import MesenSocket, StudioError
from .media import export_command
from .project import StudioProject


def load_reel(path: Path) -> list[dict[str, Any]]:
    reel = json.loads(path.read_text(encoding="utf-8"))
    shots = reel.get("shots") if isinstance(reel, dict) else None
    if not isinstance(shots, list) or not shots:
        raise ValueError(f"{path}: expected an object with a non-empty 'shots' list")
    names = set()
    for index, shot in enumerate(shots):
        name = shot.get("name")
        if not name or not str(name).replace("-", "").replace("_", "").isalnum():
            raise ValueError(f"{path}: shot {index} needs a name made of letters, digits, '-' or '_'")
        if name in names:
            raise ValueError(f"{path}: duplicate shot name '{name}'")
        names.add(name)
        if float(shot.get("seconds", 0)) <= 0:
            raise ValueError(f"{path}: shot '{name}' needs seconds > 0")
    return shots


def _frame(mesen: MesenSocket) -> tuple[int, float]:
    state = mesen.request("STATE")
    fps = float(state.get("fps") or 0)
    return int(state["frame"]), fps if fps > 1 else 60.0988


def run_shot(mesen: MesenSocket, project: StudioProject, shot: dict[str, Any], reel_dir: Path,
             out_dir: Path, overwrite: bool) -> Path:
    mesen.request("PAUSE")
    if shot.get("state"):
        state_path = Path(shot["state"]).expanduser()
        if not state_path.is_absolute():
            state_path = reel_dir / state_path
        mesen.request("LOADSTATE", path=state_path, allow_external=True, pause=True)

    mesen.request("CAMERA", action="reset")
    camera = project.camera_params(shot.get("preset", "trailer"))
    camera.update(shot.get("camera", {}))
    mesen.request("CAMERA", action="set", **camera)

    clip = out_dir / f"{shot['name']}.avi"
    mesen.request("RECORD", action="start", path=clip, camera=True, overwrite=overwrite)
    try:
        start, fps = _frame(mesen)
        total = round(float(shot["seconds"]) * fps)
        pans = sorted(shot.get("pans", []), key=lambda pan: float(pan.get("at", 0)))
        mesen.request("RESUME")
        while True:
            elapsed = _frame(mesen)[0] - start
            while pans and float(pans[0].get("at", 0)) * fps <= elapsed:
                pan = pans.pop(0)
                mesen.request("CAMERA", action="pan", x=pan.get("x"), y=pan.get("y"), zoom=pan.get("zoom"),
                              frames=max(1, round(float(pan.get("seconds", 1)) * fps)),
                              ease=pan.get("ease", "smooth"))
            if elapsed >= total:
                break
            time.sleep(0.02)
    finally:
        mesen.request("PAUSE")
        mesen.request("RECORD", action="stop", kind="video")
    return clip


def run_reel(mesen: MesenSocket, project: StudioProject, reel_path: Path, out_dir: Path,
             overwrite: bool = False, export_scale: int = 0) -> list[dict[str, Any]]:
    shots = load_reel(reel_path)
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for shot in shots:
        clip = run_shot(mesen, project, shot, reel_path.parent, out_dir, overwrite)
        result: dict[str, Any] = {"shot": shot["name"], "avi": str(clip)}
        if export_scale:
            mp4 = clip.with_suffix(".mp4")
            proc = subprocess.run(export_command(clip, mp4, scale=export_scale), capture_output=True, text=True)
            if proc.returncode != 0:
                raise StudioError(f"export of {clip} failed: {proc.stderr.strip()}")
            result["mp4"] = str(mp4)
        results.append(result)
    return results

"""yaze project (.yaze) resolution, per-hack studio profiles and game adapters.

A profile is an optional sidecar next to the project: <Project>.mesen.json.
It never needs yaze to know about it. Without one, the adapter is inferred:
yaze only edits A Link to the Past, so any .yaze project gets the "alttp" adapter;
a bare ROM gets "alttp" when its internal title says Zelda, otherwise "generic".
"""

from __future__ import annotations

import configparser
import copy
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

PROFILE_SCHEMA = 1

# Camera follow sources are screen-space: target - scroll + offset (game pixels).
ADAPTERS: dict[str, dict[str, Any]] = {
    "alttp": {
        "label": "A Link to the Past and ALTTP-based hacks",
        "follow": {
            "player": {
                "memtype": "wram",
                "follow_x": "0x7E0022",
                "follow_y": "0x7E0020",
                "scroll_x": "0x7E00E2",
                "scroll_y": "0x7E00E8",
                "offset_x": 8,
                "offset_y": 16,
                "size": 2,
            }
        },
    },
    "generic": {
        "label": "Any game (no RAM knowledge; define follow targets in the profile)",
        "follow": {},
    },
}

BUILTIN_PRESETS: dict[str, dict[str, Any]] = {
    "full": {"mode": "full", "width": 0, "height": 0},
    "trailer": {"mode": "manual", "width": 1280, "height": 720, "zoom": 1.0, "filter": "nearest"},
    "closeup": {"mode": "follow", "follow": "player", "width": 1280, "height": 720, "zoom": 2.0,
                "smoothing": 0.12, "deadzone_x": 12, "deadzone_y": 8, "filter": "nearest"},
    "square": {"mode": "full", "width": 1024, "height": 896},
}


@dataclass
class StudioProject:
    project_path: Path | None = None
    name: str = ""
    rom_path: Path | None = None
    symbols_path: Path | None = None
    profile_path: Path | None = None
    profile: dict[str, Any] = field(default_factory=dict)
    adapter: str = "generic"

    @property
    def root(self) -> Path | None:
        return self.project_path.parent if self.project_path else None

    def follow_sources(self) -> dict[str, dict[str, Any]]:
        sources = copy.deepcopy(ADAPTERS.get(self.adapter, ADAPTERS["generic"])["follow"])
        sources.update(self.profile.get("camera", {}).get("follow", {}))
        return sources

    def presets(self) -> dict[str, dict[str, Any]]:
        presets = copy.deepcopy(BUILTIN_PRESETS)
        presets.update(self.profile.get("camera", {}).get("presets", {}))
        return presets

    def capture_dir(self) -> Path | None:
        value = self.profile.get("capture_dir")
        if not value:
            return None
        path = Path(value).expanduser()
        if not path.is_absolute() and self.root:
            path = self.root / path
        return path

    def camera_params(self, preset_name: str) -> dict[str, Any]:
        """Socket CAMERA set params for a preset, with its follow source expanded."""
        presets = self.presets()
        if preset_name not in presets:
            raise KeyError(f"Unknown camera preset '{preset_name}' (have: {', '.join(sorted(presets))})")
        params = dict(presets[preset_name])
        follow_name = params.pop("follow", None)
        if params.get("mode") == "follow":
            sources = self.follow_sources()
            if not follow_name or follow_name not in sources:
                raise KeyError(f"Preset '{preset_name}' follows '{follow_name}', which adapter "
                               f"'{self.adapter}' and the profile do not define")
            params.update(sources[follow_name])
        return params

    def describe(self) -> dict[str, Any]:
        return {
            "project": str(self.project_path) if self.project_path else None,
            "name": self.name,
            "rom": str(self.rom_path) if self.rom_path else None,
            "symbols": str(self.symbols_path) if self.symbols_path else None,
            "profile": str(self.profile_path) if self.profile_path else None,
            "adapter": self.adapter,
            "adapter_label": ADAPTERS.get(self.adapter, {}).get("label", ""),
            "follow_sources": sorted(self.follow_sources()),
            "presets": sorted(self.presets()),
            "capture_dir": str(self.capture_dir()) if self.capture_dir() else None,
        }


def _resolve(base: Path, value: str) -> Path | None:
    value = value.strip()
    if not value:
        return None
    path = Path(value).expanduser()
    return path if path.is_absolute() else base / path


def read_yaze(path: Path) -> dict[str, dict[str, str]]:
    parser = configparser.ConfigParser(interpolation=None, strict=False, comment_prefixes=("#", ";"))
    parser.optionxform = str
    parser.read_string(path.read_text(encoding="utf-8", errors="replace"))
    return {section: dict(parser.items(section)) for section in parser.sections()}


def load_profile(path: Path) -> dict[str, Any]:
    profile = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(profile, dict):
        raise ValueError(f"{path}: profile must be a JSON object")
    schema = profile.get("schema", PROFILE_SCHEMA)
    if schema != PROFILE_SCHEMA:
        raise ValueError(f"{path}: unsupported profile schema {schema}")
    adapter = profile.get("adapter")
    if adapter is not None and adapter not in ADAPTERS:
        raise ValueError(f"{path}: unknown adapter '{adapter}' (have: {', '.join(sorted(ADAPTERS))})")
    return profile


def rom_title(path: Path) -> str:
    """Internal SNES header title (LoROM or HiROM, with or without a copier header)."""
    try:
        data = path.read_bytes()
    except OSError:
        return ""
    skip = 512 if len(data) % 1024 == 512 else 0
    for base in (0x7FC0, 0xFFC0):
        start = skip + base
        title = data[start:start + 21]
        if len(title) == 21 and all(32 <= b < 127 for b in title):
            return title.decode("ascii").strip()
    return ""


def load_project(path: str | Path) -> StudioProject:
    path = Path(path).expanduser().resolve()
    project = StudioProject()

    if path.suffix.lower() == ".yaze":
        sections = read_yaze(path)
        root = path.parent
        project.project_path = path
        project.name = sections.get("project", {}).get("name", path.stem)
        build_target = _resolve(root, sections.get("build", {}).get("build_target", ""))
        base_rom = _resolve(root, sections.get("files", {}).get("rom_filename", ""))
        if build_target and build_target.exists():
            project.rom_path = build_target
        elif base_rom and base_rom.exists():
            project.rom_path = base_rom
        symbols = _resolve(root, sections.get("files", {}).get("symbols_filename", ""))
        project.symbols_path = symbols if symbols and symbols.exists() else None
        project.adapter = "alttp"
        profile_path = root / f"{path.stem}.mesen.json"
    else:
        project.rom_path = path
        project.name = path.stem
        project.adapter = "alttp" if "ZELDA" in rom_title(path).upper() else "generic"
        profile_path = path.with_suffix(".mesen.json")

    if profile_path.exists():
        project.profile_path = profile_path
        project.profile = load_profile(profile_path)
        project.adapter = project.profile.get("adapter", project.adapter)
    return project

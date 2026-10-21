"""ffmpeg command builders for exports and live streams."""

from __future__ import annotations

import functools
import shutil
import subprocess
from pathlib import Path

from .client import StudioError


def ffmpeg_path() -> str:
    path = shutil.which("ffmpeg")
    if not path:
        raise StudioError("ffmpeg not found on PATH (brew install ffmpeg)")
    return path


@functools.lru_cache(maxsize=1)
def has_videotoolbox() -> bool:
    try:
        out = subprocess.run([ffmpeg_path(), "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError, StudioError):
        return False
    return "h264_videotoolbox" in out.stdout


def scale_filter(scale: int, smooth: bool = False) -> list[str]:
    filters = []
    if scale > 1:
        filters.append(f"scale=iw*{scale}:ih*{scale}:flags={'bicubic' if smooth else 'neighbor'}")
    filters.append("format=yuv420p")
    return ["-vf", ",".join(filters)]


def export_command(src: Path, dst: Path, scale: int = 1, crf: int = 14, prores: bool = False) -> list[str]:
    """Trailer export: nearest-neighbor upscale; H.264 MP4 for sharing or ProRes MOV for editing."""
    cmd = [ffmpeg_path(), "-hide_banner", "-loglevel", "warning", "-nostdin", "-y", "-i", str(src)]
    if prores:
        vf = [f"scale=iw*{scale}:ih*{scale}:flags=neighbor"] if scale > 1 else []
        if vf:
            cmd += ["-vf", ",".join(vf)]
        cmd += ["-c:v", "prores_ks", "-profile:v", "3", "-pix_fmt", "yuv422p10le", "-c:a", "pcm_s16le", str(dst)]
    else:
        cmd += scale_filter(scale)
        cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", str(crf), "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart", str(dst)]
    return cmd


def live_command(video_fifo: Path, audio_fifo: Path | None, hls_dir: Path, width: int, height: int,
                 fps: float, sample_rate: int, scale: int = 1, output_fps: int = 60, smooth: bool = False) -> list[str]:
    """Raw BGRA + s16le pipes -> low-latency fMP4 HLS.
    Video uses wallclock timestamps so dropped frames keep real timing. Audio keeps sample-count
    timestamps: with wallclock timestamps ffmpeg stops reading the audio pipe after the first packets.
    smooth: upscale with bicubic instead of nearest (for frames the emulator already downscaled)."""
    raw_input = ["-probesize", "32", "-analyzeduration", "0"]
    cmd = [ffmpeg_path(), "-hide_banner", "-loglevel", "warning", "-nostdin",
           "-thread_queue_size", "64", "-use_wallclock_as_timestamps", "1", *raw_input,
           "-f", "rawvideo", "-pixel_format", "bgra", "-video_size", f"{width}x{height}",
           "-framerate", f"{fps:.4f}", "-i", str(video_fifo)]
    if audio_fifo:
        cmd += ["-thread_queue_size", "1024", *raw_input,
                "-f", "s16le", "-ar", str(sample_rate), "-ac", "2", "-i", str(audio_fifo),
                "-map", "0:v", "-map", "1:a"]
    cmd += scale_filter(scale, smooth)
    cmd += ["-fps_mode", "cfr", "-r", str(output_fps)]
    gop = str(output_fps)
    if has_videotoolbox():
        cmd += ["-c:v", "h264_videotoolbox", "-realtime", "1", "-b:v", "6M", "-profile:v", "high", "-g", gop]
    else:
        cmd += ["-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-crf", "23", "-g", gop]
    if audio_fifo:
        cmd += ["-c:a", "aac", "-b:a", "128k", "-af", "aresample=async=1"]
    cmd += ["-f", "hls", "-hls_time", "1", "-hls_list_size", "6",
            "-hls_flags", "delete_segments+independent_segments+omit_endlist",
            "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
            str(hls_dir / "live.m3u8")]
    return cmd

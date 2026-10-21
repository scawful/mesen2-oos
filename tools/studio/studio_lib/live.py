"""Live view: emulator LIVE pipes -> ffmpeg HLS -> localhost browser page with audio.

The emulator keeps running headless; closing the page or stopping this server only
stops the stream (LIVE stop), never the emulator.
"""

from __future__ import annotations

import json
import os
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

from .client import MesenSocket, StudioError
from .media import live_command

VIEWER_HTML = Path(__file__).with_name("viewer.html")

MIME_TYPES = {
    ".m3u8": "application/vnd.apple.mpegurl",
    ".m4s": "video/iso.segment",
    ".mp4": "video/mp4",
    ".html": "text/html; charset=utf-8",
}


class LiveHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args: Any, directory: str, status_provider: Callable[[], dict[str, Any]], **kwargs: Any):
        self.status_provider = status_provider
        super().__init__(*args, directory=directory, **kwargs)

    def log_message(self, format: str, *args: Any) -> None:
        pass

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def guess_type(self, path: str) -> str:
        return MIME_TYPES.get(Path(path).suffix.lower(), super().guess_type(path))

    def do_GET(self) -> None:
        if self.path in ("/", "/index.html"):
            body = VIEWER_HTML.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", MIME_TYPES[".html"])
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/status":
            body = json.dumps(self.status_provider()).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if not self.path.startswith("/hls/"):
            self.send_error(404)
            return
        super().do_GET()


DEFAULT_MAX_STREAM_PIXELS = 640 * 360


def plan_stream(camera_width: int, camera_height: int, scale: int | None,
                max_pixels: int = DEFAULT_MAX_STREAM_PIXELS) -> tuple[int, int]:
    """(downscale, encoder upscale) so the raw pipe carries at most max_pixels per frame and the
    viewer sees the camera size times scale (default: 2x for game-size frames, 1x otherwise)."""
    downscale = 1
    while downscale < 8 and (camera_width // downscale) * (camera_height // downscale) > max_pixels:
        downscale += 1
    if scale is None:
        scale = 2 if camera_width <= 512 else 1
    return downscale, max(1, downscale * scale)


class LiveSession:
    def __init__(self, mesen: MesenSocket, port: int, scale: int | None, every: int, audio: bool,
                 label: str = "", work_dir: Path | None = None, max_pixels: int = DEFAULT_MAX_STREAM_PIXELS):
        self.mesen = mesen
        self.port = port
        self.scale = scale
        self.max_pixels = max_pixels
        self.every = every
        self.audio = audio
        self.label = label
        self._owns_dir = work_dir is None
        self.work_dir = work_dir or Path(tempfile.mkdtemp(prefix="mesen-live-"))
        self.hls_dir = self.work_dir / "hls"
        self.video_fifo = self.work_dir / "video.fifo"
        self.audio_fifo = self.work_dir / "audio.fifo"
        self.ffmpeg: subprocess.Popen | None = None
        self.server: ThreadingHTTPServer | None = None
        self.stream: dict[str, Any] = {}
        self._stop = threading.Event()

    def status(self) -> dict[str, Any]:
        result: dict[str, Any] = {"label": self.label, "stream": self.stream, "ffmpeg_running": bool(self.ffmpeg and self.ffmpeg.poll() is None)}
        try:
            result["live"] = self.mesen.request("LIVE", action="status")
            result["emulator"] = self.mesen.request("STATE")
        except StudioError as exc:
            result["error"] = str(exc)
        return result

    def start(self, replace: bool = False) -> str:
        current = self.mesen.request("LIVE", action="status")
        if current.get("active"):
            if current["video"]["connected"] and not replace:
                raise StudioError("Another live viewer is reading this instance's stream (use --replace to take over)")
            self.mesen.request("LIVE", action="stop")
        self.hls_dir.mkdir(parents=True, exist_ok=True)
        os.mkfifo(self.video_fifo)
        if self.audio:
            os.mkfifo(self.audio_fifo)

        camera = self.mesen.request("CAMERA", action="get")
        downscale, upscale = plan_stream(camera["width"], camera["height"], self.scale, self.max_pixels)
        self.stream = self.mesen.request("LIVE", action="start", video=self.video_fifo,
                                         audio=self.audio_fifo if self.audio else None, every=self.every,
                                         downscale=downscale)
        cmd = live_command(self.video_fifo, self.audio_fifo if self.audio else None, self.hls_dir,
                           self.stream["width"], self.stream["height"], self.stream["fps"],
                           self.stream["sample_rate"], scale=upscale,
                           output_fps=60 if self.every == 1 else 30, smooth=downscale > 1)
        self.ffmpeg = subprocess.Popen(cmd, stdin=subprocess.DEVNULL)

        handler = partial(LiveHandler, directory=str(self.work_dir), status_provider=self.status)
        self.server = ThreadingHTTPServer(("127.0.0.1", self.port), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return f"http://127.0.0.1:{self.server.server_address[1]}/"

    def wait(self) -> int:
        """Block until Ctrl-C, SIGTERM/SIGHUP or ffmpeg exits. Returns 0 when stopped by a signal."""
        handled = (signal.SIGTERM, signal.SIGHUP)
        previous = {sig: signal.signal(sig, lambda *_: self._stop.set()) for sig in handled}
        try:
            while not self._stop.is_set():
                if self.ffmpeg and self.ffmpeg.poll() is not None:
                    return 0 if self._stop.is_set() else self.ffmpeg.returncode
                time.sleep(0.5)
        except KeyboardInterrupt:
            pass
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)
        return 0

    def stop(self) -> None:
        try:
            self.mesen.request("LIVE", action="stop")
        except StudioError:
            pass
        if self.ffmpeg and self.ffmpeg.poll() is None:
            self.ffmpeg.terminate()
            try:
                self.ffmpeg.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.ffmpeg.kill()
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        if self._owns_dir:
            shutil.rmtree(self.work_dir, ignore_errors=True)

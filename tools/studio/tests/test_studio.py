"""Unit tests for Mesen Studio (no emulator needed).

Run: python3 -m unittest discover -s tools/studio/tests -v
"""

from __future__ import annotations

import json
import os
import socket
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from functools import partial
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from studio_lib.client import MesenSocket, StudioError, resolve_socket  # noqa: E402
from studio_lib.live import LiveHandler, plan_stream  # noqa: E402
from studio_lib.media import export_command, live_command  # noqa: E402
from studio_lib.project import load_project, rom_title  # noqa: E402
from studio_lib import reel  # noqa: E402

YAZE = """# yaze project
[project]
name=Test Hack

[files]
rom_filename=Roms/base.sfc
symbols_filename=Roms/hack.sym

[build]
build_target=Roms/hack.sfc

[graphics_sheets]
# comment inside a section
reserved_sheets=0x7B
"""


def make_rom(path: Path, title: str) -> None:
    data = bytearray(0x8000)
    data[0x7FC0:0x7FC0 + 21] = title.ljust(21).encode("ascii")
    path.write_bytes(bytes(data))


class ProjectTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "Roms").mkdir()
        (self.root / "Test.yaze").write_text(YAZE)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_prefers_build_target_and_resolves_relative_paths(self) -> None:
        make_rom(self.root / "Roms/base.sfc", "BASE")
        make_rom(self.root / "Roms/hack.sfc", "HACK")
        (self.root / "Roms/hack.sym").write_text("")
        project = load_project(self.root / "Test.yaze")
        self.assertEqual(project.name, "Test Hack")
        self.assertEqual(project.rom_path, (self.root / "Roms/hack.sfc").resolve())
        self.assertEqual(project.symbols_path, (self.root / "Roms/hack.sym").resolve())
        self.assertEqual(project.adapter, "alttp")
        self.assertIsNone(project.profile_path)

    def test_falls_back_to_base_rom(self) -> None:
        make_rom(self.root / "Roms/base.sfc", "BASE")
        project = load_project(self.root / "Test.yaze")
        self.assertEqual(project.rom_path, (self.root / "Roms/base.sfc").resolve())
        self.assertIsNone(project.symbols_path)

    def test_profile_overrides_adapter_and_adds_presets(self) -> None:
        profile = {
            "schema": 1,
            "adapter": "generic",
            "capture_dir": "Captures",
            "camera": {
                "follow": {"hero": {"memtype": "wram", "follow_x": "0x10", "follow_y": "0x12"}},
                "presets": {"hero": {"mode": "follow", "follow": "hero", "width": 640, "height": 360, "zoom": 2}},
            },
        }
        (self.root / "Test.mesen.json").write_text(json.dumps(profile))
        project = load_project(self.root / "Test.yaze")
        self.assertEqual(project.adapter, "generic")
        self.assertEqual(project.capture_dir(), (self.root / "Captures").resolve())
        params = project.camera_params("hero")
        self.assertEqual(params["follow_x"], "0x10")
        self.assertNotIn("follow", params)
        with self.assertRaises(KeyError):
            project.camera_params("closeup")  # generic adapter has no "player"

    def test_alttp_closeup_expands_player_follow(self) -> None:
        params = load_project(self.root / "Test.yaze").camera_params("closeup")
        self.assertEqual(params["mode"], "follow")
        self.assertEqual(params["follow_x"], "0x7E0022")
        self.assertEqual(params["scroll_y"], "0x7E00E8")

    def test_bad_profile_rejected(self) -> None:
        (self.root / "Test.mesen.json").write_text(json.dumps({"adapter": "nes-zelda"}))
        with self.assertRaises(ValueError):
            load_project(self.root / "Test.yaze")

    def test_bare_rom_adapter_from_header(self) -> None:
        zelda = self.root / "zelda.sfc"
        other = self.root / "other.sfc"
        make_rom(zelda, "THE LEGEND OF ZELDA")
        make_rom(other, "SUPER METROID")
        self.assertEqual(rom_title(zelda), "THE LEGEND OF ZELDA")
        self.assertEqual(load_project(zelda).adapter, "alttp")
        self.assertEqual(load_project(other).adapter, "generic")


class MediaTests(unittest.TestCase):
    def test_export_scales_with_nearest_neighbor(self) -> None:
        cmd = export_command(Path("/in.avi"), Path("/out.mp4"), scale=4)
        self.assertIn("scale=iw*4:ih*4:flags=neighbor,format=yuv420p", cmd)
        self.assertEqual(cmd[-1], "/out.mp4")
        prores = export_command(Path("/in.avi"), Path("/out.mov"), scale=1, prores=True)
        self.assertIn("prores_ks", prores)
        self.assertNotIn("-vf", prores)

    def test_live_command_reads_both_pipes(self) -> None:
        cmd = live_command(Path("/t/video.fifo"), Path("/t/audio.fifo"), Path("/t/hls"), 256, 224, 60.0988, 48000, scale=2)
        self.assertIn("256x224", cmd)
        self.assertIn("/t/video.fifo", cmd)
        self.assertIn("/t/audio.fifo", cmd)
        self.assertEqual(cmd[-1], "/t/hls/live.m3u8")
        silent = live_command(Path("/t/video.fifo"), None, Path("/t/hls"), 256, 224, 60.0, 48000)
        self.assertNotIn("1:a", silent)


class StreamPlanTests(unittest.TestCase):
    def test_game_size_streams_raw_and_doubles(self) -> None:
        self.assertEqual(plan_stream(256, 224, None), (1, 2))

    def test_large_camera_is_downscaled_then_restored(self) -> None:
        self.assertEqual(plan_stream(1280, 720, None), (2, 2))
        self.assertEqual(plan_stream(1920, 1080, None), (3, 3))
        self.assertEqual(plan_stream(1280, 720, 2), (2, 4))
        self.assertEqual(plan_stream(1280, 720, None, max_pixels=1280 * 720), (1, 1))


class FakeServer:
    """Unix-socket server answering each request with a canned or computed response."""

    def __init__(self, handler) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "s.sock")
        self.handler = handler
        self.requests: list[dict] = []
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind(self.path)
        self.sock.listen(8)
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self) -> None:
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            with conn:
                data = b""
                while b"\n" not in data:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    data += chunk
                request = json.loads(data)
                self.requests.append(request)
                conn.sendall((json.dumps(self.handler(request)) + "\n").encode())

    def close(self) -> None:
        self.sock.close()
        self.dir.cleanup()


class ClientTests(unittest.TestCase):
    def test_request_stringifies_params_and_returns_data(self) -> None:
        server = FakeServer(lambda r: {"success": True, "data": {"echo": r}})
        try:
            data = MesenSocket(server.path).request("CAMERA", action="set", zoom=2.0, clamp=True, x=None)
            self.assertEqual(data["echo"], {"type": "CAMERA", "action": "set", "zoom": "2.0", "clamp": "true"})
        finally:
            server.close()

    def test_errors_raise(self) -> None:
        server = FakeServer(lambda r: {"success": False, "error": "nope"})
        try:
            with self.assertRaisesRegex(StudioError, "nope"):
                MesenSocket(server.path).request("LIVE")
        finally:
            server.close()

    def test_socket_must_be_explicit(self) -> None:
        old = os.environ.pop("MESEN2_SOCKET_PATH", None)
        try:
            with self.assertRaises(StudioError):
                resolve_socket(None, None)
            self.assertEqual(resolve_socket(None, "agent-a"), "/tmp/mesen2-agent-a.sock")
        finally:
            if old is not None:
                os.environ["MESEN2_SOCKET_PATH"] = old


class FakeMesen:
    """Simulates frame progress: every STATE call advances 10 frames while running."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        self.frame = 1000
        self.running = False

    def request(self, command: str, **params):
        self.calls.append((command, params))
        if command == "STATE":
            if self.running:
                self.frame += 10
            return {"frame": self.frame, "fps": 60.0}
        if command == "RESUME":
            self.running = True
        if command == "PAUSE":
            self.running = False
        return {}


class ReelTests(unittest.TestCase):
    def test_validation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "reel.json"
            path.write_text(json.dumps({"shots": [{"name": "a b", "seconds": 1}]}))
            with self.assertRaises(ValueError):
                reel.load_reel(path)
            path.write_text(json.dumps({"shots": [{"name": "a", "seconds": 0}]}))
            with self.assertRaises(ValueError):
                reel.load_reel(path)

    def test_shot_records_pans_and_stops_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Roms").mkdir()
            (root / "Test.yaze").write_text(YAZE)
            make_rom(root / "Roms/hack.sfc", "HACK")
            project = load_project(root / "Test.yaze")
            mesen = FakeMesen()
            shot = {"name": "intro", "state": "intro.mss", "preset": "trailer", "camera": {"x": 100},
                    "seconds": 2, "pans": [{"at": 1, "x": 200, "zoom": 2, "seconds": 0.5}]}
            clip = reel.run_shot(mesen, project, shot, root, root / "out", overwrite=True)

            commands = [c for c, _ in mesen.calls if c != "STATE"]
            self.assertEqual(commands, ["PAUSE", "LOADSTATE", "CAMERA", "CAMERA", "RECORD", "RESUME", "CAMERA", "PAUSE", "RECORD"])
            self.assertEqual(clip, root / "out/intro.avi")
            loadstate = next(p for c, p in mesen.calls if c == "LOADSTATE")
            self.assertEqual(loadstate["path"], root / "intro.mss")
            camera_set = [p for c, p in mesen.calls if c == "CAMERA"][1]
            self.assertEqual((camera_set["width"], camera_set["x"]), (1280, 100))
            pan = [p for c, p in mesen.calls if c == "CAMERA"][2]
            self.assertEqual((pan["action"], pan["x"], pan["frames"]), ("pan", 200, 30))
            self.assertGreaterEqual(mesen.frame - 1000, 120)


class LiveHandlerTests(unittest.TestCase):
    def test_serves_status_and_hls_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "hls").mkdir()
            (root / "hls/live.m3u8").write_text("#EXTM3U\n")
            (root / "secret.txt").write_text("no")
            handler = partial(LiveHandler, directory=str(root), status_provider=lambda: {"ok": True})
            server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            base = f"http://127.0.0.1:{server.server_address[1]}"
            try:
                with urllib.request.urlopen(base + "/status") as r:
                    self.assertEqual(json.load(r), {"ok": True})
                with urllib.request.urlopen(base + "/hls/live.m3u8") as r:
                    self.assertEqual(r.headers["Content-Type"], "application/vnd.apple.mpegurl")
                    self.assertEqual(r.headers["Cache-Control"], "no-store")
                with urllib.request.urlopen(base + "/") as r:
                    self.assertIn(b"Mesen Studio", r.read())
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    urllib.request.urlopen(base + "/secret.txt")
                self.assertEqual(denied.exception.code, 404)
                denied.exception.close()
            finally:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Passive, explicitly targeted Mesen observer. No gameplay control HTTP API.

Run: python3 agent_spectator.py --config /absolute/sessions.json --out /evidence
Config: {"sessions": [{"id": "intro", "label": "Codex / intro", "socket":
"/tmp/mesen2-owned.sock", "expected_rom_sha1": "...", "fixtures": [],
"evidence_file": "/optional/results.json"}], "passive_binary": {"path":
"/Applications/Mesen2 OOS.app/Contents/MacOS/Mesen", "sha256": "..."}}

GAMESTATE needs a tested binary attestation. Unknown binaries still get STATE
and SCREENSHOT; never probe debugger-affecting commands on someone else's run.
Config reloads on disk; never discovers or switches to an unconfigured socket.
"""
from __future__ import annotations
import argparse
import base64
from collections import deque
import hashlib
import glob
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import re
import signal
import subprocess
import threading
import time
import uuid
from urllib.parse import urlparse, parse_qs

from mesen2_client_lib.bridge import MesenBridge

ALLOWED = frozenset(('STATE', 'ROMINFO', 'SCREENSHOT', 'GAMESTATE'))
MODES = {0: 'Title', 2: 'File select', 7: 'Dungeon', 9: 'Overworld', 12: 'Arrival',
         13: 'Experiment', 14: 'Dialogue', 18: 'Death', 21: 'Cutscene'}


def passive_binary_matches(socket_path, attestation):
    """Attest the running process, not merely an installed file of the same name."""
    if not attestation:
        return False
    try:
        target = Path(socket_path).resolve()
        binary = Path(attestation['path']).resolve()
        for path in Path('/tmp').glob('mesen2-*.status'):
            status = json.loads(path.read_text())
            if Path(status.get('socketPath', '')).resolve() != target:
                continue
            cmd = subprocess.run(['ps', '-p', str(int(status['pid'])), '-o', 'comm='],
                                 capture_output=True, text=True, timeout=1, check=True)
            if Path(cmd.stdout.strip()).resolve() != binary:
                return False
            return hashlib.sha256(binary.read_bytes()).hexdigest() == attestation['sha256']
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        return False
    return False


class PassiveClient:
    def __init__(self, socket_path):
        if not socket_path or not Path(socket_path).is_absolute():
            raise ValueError('An explicit absolute Unix socket is required')
        self.bridge = MesenBridge(socket_path, auto_reconnect=False)

    def read(self, command):
        if command not in ALLOWED:
            raise ValueError('Spectator command forbidden: ' + command)
        response = self.bridge.send_command(command, timeout=0.75)
        if not response.get('success'):
            raise RuntimeError(response.get('error', 'Observation failed'))
        data = response.get('data')
        if isinstance(data, str) and command != 'SCREENSHOT':
            data = json.loads(data)
        return data


class Lane:
    def __init__(self, spec, out, attestation, fps=4, client_factory=PassiveClient,
                 binary_check=passive_binary_matches):
        self.spec = dict(spec)
        if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', spec['id']):
            raise ValueError('Invalid session id')
        if spec['id'] == 'oos-rc-play' or 'mesen2-oos-rc-play.sock' in spec['socket']:
            raise ValueError('User play window is not a bot session')
        self.client = client_factory(spec['socket'])
        self.attestation = attestation
        self.binary_check = binary_check
        self.fps = fps
        self.out = out / spec['id']; self.out.mkdir(parents=True, exist_ok=True)
        self.handler = RotatingFileHandler(self.out / 'events.jsonl', maxBytes=8*1024*1024,
                                          backupCount=3, encoding='utf-8')
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.events = deque(maxlen=120)
        self.state = {}; self.game = {}; self.rom = {}; self.png = None
        self.connected = False; self.error = ''; self.watching_until = 0
        self.last_sample = 0.; self.last_image = 0.; self.last_rom_check = 0.
        self.game_safe = False; self.previous = None; self.seq = 0; self.run_id = uuid.uuid4().hex
        self.thread = None; self.evidence_stamp = None
        self.driver = {}; self.driver_updated = None
        self.emit('recorder_start', config=spec, sampling='1 Hz, sequential non-atomic reads')

    def emit(self, kind, **data):
        with self.lock:
            self.seq += 1
            event = dict(seq=self.seq, run_id=self.run_id, t=time.time(), session=self.spec['id'], kind=kind, **data)
            self.handler.emit(logging.makeLogRecord({'msg': json.dumps(event), 'levelno': logging.INFO,
                                                     'levelname': 'INFO'}))
            if kind != 'sample':
                self.events.append(event)

    def watch(self):
        with self.lock:
            self.watching_until = time.monotonic() + 2.5

    def record_changes(self, status, game):
        current = {'paused': status.get('paused'), 'lastSave': status.get('lastSave'),
                   'lastLoad': status.get('lastLoad'), 'location': game.get('game'),
                   'health': game.get('health'), 'items': game.get('items')}
        if self.previous:
            gameplay = (7, 9, 14, 18)
            comparable = ((current.get('location') or {}).get('mode') in gameplay
                          and (self.previous.get('location') or {}).get('mode') in gameplay
                          and current['lastLoad'] == self.previous['lastLoad'])
            for key, value in current.items():
                if key in ('health', 'items') and not comparable:
                    continue
                if value != self.previous.get(key):
                    self.emit(key, before=self.previous.get(key), after=value)
            if game.get('game', {}).get('mode') == 18 and (self.previous.get('location') or {}).get('mode') != 18:
                self.emit('death', note='Observed death module; does not classify cause')
        self.previous = current

    def read_driver_evidence(self):
        path = self.spec.get('evidence_file')
        pattern = self.spec.get('evidence_glob')
        if pattern:
            candidates = [Path(p) for p in glob.glob(pattern)]
            try:
                if candidates: path = str(max(candidates, key=lambda p: p.stat().st_mtime_ns))
            except OSError:
                return
        if not path:
            return
        try:
            p = Path(path); st = p.stat(); stamp = (str(p), st.st_mtime_ns, st.st_size)
            if stamp == self.evidence_stamp or st.st_size > 8*1024*1024:
                return
            d = json.loads(p.read_text())
            commands = d.get('commands', [])
            last = commands[-1] if commands else {}
            driver = {'state': last.get('after', d.get('end', d.get('start', {}))),
                      'command': last.get('command'), 'command_count': len(commands),
                      'status': d.get('status', d.get('exit_code')), 'error': d.get('error'),
                      'note': (d.get('log') or d.get('notes') or [''])[-1]}
            if driver != self.driver:
                self.emit('driver', report=str(p), data=driver)
                with self.lock:
                    self.driver = driver; self.driver_updated = st.st_mtime
            self.evidence_stamp = stamp
        except (OSError, ValueError, TypeError, IndexError):
            # Existing writers may replace JSON non-atomically. Retry next sample.
            pass

    def tick(self, now=None):
        now = time.monotonic() if now is None else now
        try:
            if now-self.last_sample >= 1:
                self.last_sample = now
                status = self.client.read('STATE')
                if not self.connected or now-self.last_rom_check >= 15:
                    rom = self.client.read('ROMINFO')
                    expected = self.spec.get('expected_rom_sha1')
                    if expected and rom.get('sha1', '').lower() != expected.lower():
                        raise RuntimeError('ROM identity changed; waiting for the configured candidate')
                    self.last_rom_check = now
                    self.game_safe = self.binary_check(self.spec['socket'], self.attestation)
                    with self.lock:
                        self.rom = rom
                game = self.client.read('GAMESTATE') if self.game_safe else {}
                if not self.connected:
                    self.emit('connected', rom=self.rom, passive_game_state=self.game_safe,
                              fixtures=self.spec.get('fixtures', []))
                self.record_changes(status, game)
                self.emit('sample', emulator=status, game=game)
                with self.lock:
                    self.state = status; self.game = game; self.connected = True; self.error = ''
                self.read_driver_evidence()
            if self.connected and now < self.watching_until and now-self.last_image >= 1/self.fps:
                self.last_image = now
                data = self.client.read('SCREENSHOT')
                png = base64.b64decode(data.strip('"'), validate=True)
                if not png.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError('Invalid screenshot data')
                temp = self.out / 'latest.tmp'; temp.write_bytes(png); temp.replace(self.out/'latest.png')
                with self.lock:
                    self.png = png
        except Exception as exc:
            message = str(exc)
            if self.connected or message != self.error:
                self.emit('disconnected', reason=message)
            with self.lock:
                self.connected = False; self.error = message
                self.previous = None; self.game_safe = False

    def snapshot(self):
        with self.lock:
            return dict(id=self.spec['id'], label=self.spec.get('label', self.spec['id']),
                        fixtures=self.spec.get('fixtures', []), connected=self.connected,
                        error=self.error, emulator=self.state, game=self.game, rom=self.rom,
                        game_state_available=self.game_safe, events=list(self.events),
                        driver=self.driver, driver_updated=self.driver_updated,
                        evidence_file=self.spec.get('evidence_file'),
                        video_age_seconds=round(time.monotonic()-self.last_image, 1) if self.last_image else None,
                        log=str(self.out/'events.jsonl'))

    def start(self):
        def run():
            while not self.stop.is_set():
                self.tick(); self.stop.wait(0.08)
        self.thread = threading.Thread(target=run, daemon=True); self.thread.start()

    def close(self):
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=6)
        self.emit('recorder_stop', note='Observer only; emulator untouched')
        self.handler.close()


class Spectator:
    def __init__(self, config, out, fps=4):
        self.config = config; self.out = out; self.fps = fps
        self.lock = threading.RLock(); self.lanes = {}; self.stamp = None; self.error = ''

    def reload(self):
        try:
            stamp = self.config.stat().st_mtime_ns
            if stamp == self.stamp:
                return
            cfg = json.loads(self.config.read_text()); specs = cfg['sessions']
            ids = [s['id'] for s in specs]
            if len(ids) != len(set(ids)) or len(ids) > 8:
                raise ValueError('Use up to 8 distinct session ids')
            prepared = {}; created = []
            # Validate new lanes before changing running ones.
            with self.lock:
                try:
                    for spec in specs:
                        old = self.lanes.get(spec['id'])
                        if old and old.spec == spec and old.attestation == cfg.get('passive_binary'):
                            prepared[spec['id']] = old
                        else:
                            lane = Lane(spec, self.out, cfg.get('passive_binary'), self.fps)
                            prepared[spec['id']] = lane; created.append(lane)
                except Exception:
                    for lane in created: lane.close()
                    raise
                for key, old in self.lanes.items():
                    if prepared.get(key) is not old: old.close()
                self.lanes = prepared
                for lane in created: lane.start()
                self.stamp = stamp; self.error = ''
        except Exception as exc:
            self.error = str(exc)

    def snapshot(self):
        with self.lock:
            return {'sessions': [l.snapshot() for l in self.lanes.values()],
                    'config_error': self.error, 'video_fps_limit': self.fps}

    def close(self):
        with self.lock:
            for lane in self.lanes.values(): lane.close()


def handler_for(app):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self): self.send_error(405, 'Spectator has no emulator control API')
        def do_GET(self):
            # Avoid DNS-rebinding exposure of local evidence.
            host = self.headers.get('Host', '').split(':')[0]
            if host not in ('127.0.0.1', 'localhost'):
                self.send_error(403); return
            route = urlparse(self.path); query = parse_qs(route.query)
            if route.path == '/':
                self.reply(200, 'text/html; charset=utf-8', Path(__file__).with_suffix('.html').read_bytes())
            elif route.path == '/api/status':
                self.reply(200, 'application/json', json.dumps(app.snapshot()).encode())
            elif route.path == '/api/frame':
                with app.lock: lane = app.lanes.get(query.get('id', [''])[0])
                if not lane: self.send_error(404); return
                lane.watch()
                with lane.lock: png = lane.png
                self.reply(200 if png else 204, 'image/png', png or b'')
            else: self.send_error(404)
        def reply(self, status, mime, data):
            self.send_response(status); self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data))); self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
            self.end_headers()
            try: self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError): pass
    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', required=True, type=Path); p.add_argument('--out', required=True, type=Path)
    p.add_argument('--port', default=8769, type=int); p.add_argument('--fps', type=int, default=4, choices=range(1, 11))
    args = p.parse_args(); args.out.mkdir(parents=True, exist_ok=True)
    app = Spectator(args.config.resolve(), args.out.resolve(), args.fps); app.reload()
    if app.error: raise SystemExit(app.error)
    try:
        server = ThreadingHTTPServer(('127.0.0.1', args.port), handler_for(app)); server.timeout = .4
    except OSError:
        app.close()
        raise
    done = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT): signal.signal(sig, lambda *_: done.set())
    print(f'Bot spectator: http://127.0.0.1:{server.server_port} (observer only)', flush=True)
    try:
        while not done.is_set(): app.reload(); server.handle_request()
    finally: server.server_close(); app.close()


if __name__ == '__main__': main()

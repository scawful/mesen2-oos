#!/usr/bin/env python3
"""Passive, explicitly targeted Mesen observer and probe recorder. No gameplay control HTTP API.

Run:      python3 agent_spectator.py serve [--home DIR] [--port 8769]
Register: python3 agent_spectator.py register --id ID --socket /abs.sock --rom-sha1 SHA1 --label TEXT
Comments: python3 agent_spectator.py comments [--open] / resolve ID --note TEXT
Legacy:   python3 agent_spectator.py --config /absolute/sessions.json --out /evidence
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
import fcntl
import os
import shutil
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

import socket

ALLOWED = frozenset(('STATE', 'ROMINFO', 'SCREENSHOT', 'GAMESTATE', 'COMMAND_HISTORY'))
# Every observer read carries this param so COMMAND_HISTORY entries from the
# spectator itself can be dropped from the agent's command log.
OBSERVER_TAG = {'observer': 'spectator'}
DEFAULT_HOME = Path.home() / '.context/projects/oracle-of-secrets/scratchpad/probe_runs'
RUN_GAP_SECONDS = 30
MAX_SESSIONS = 8
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
        self.socket_path = socket_path

    def send(self, payload, timeout=0.75):
        """One newline-delimited JSON request per connection (same as mesen2_client_lib)."""
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            sock.connect(self.socket_path)
            sock.sendall((json.dumps(payload) + '\n').encode())
            buf = b''
            while b'\n' not in buf:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                if len(buf) > 16 * 1024 * 1024:
                    raise ValueError('Response too large')
        if not buf:
            raise TimeoutError('No response from Mesen2')
        return json.loads(buf.split(b'\n', 1)[0])

    def read(self, command, **params):
        if command not in ALLOWED:
            raise ValueError('Spectator command forbidden: ' + command)
        response = self.send(dict({'type': command}, **OBSERVER_TAG, **params))
        if not response.get('success'):
            raise RuntimeError(response.get('error', 'Observation failed'))
        data = response.get('data')
        if isinstance(data, str) and command != 'SCREENSHOT':
            data = json.loads(data)
        return data


class Lane:
    def __init__(self, spec, out, attestation, fps=4, client_factory=PassiveClient,
                 binary_check=passive_binary_matches, record_fps=0, keep_runs=40):
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
        # Replay recording: frames at record_fps, the agent's commands, events, comments.
        self.record_fps = record_fps; self.keep_runs = keep_runs
        self.run = None; self.last_record = 0.; self.last_png_hash = None; self.disconnected_at = None
        self.history_seq = None; self.command_log = 'unknown'; self.commands = deque(maxlen=200)
        self.emit('recorder_start', config=spec, sampling='1 Hz, sequential non-atomic reads')

    def emit(self, kind, **data):
        with self.lock:
            self.seq += 1
            event = dict(seq=self.seq, run_id=self.run_id, t=time.time(), session=self.spec['id'], kind=kind, **data)
            self.handler.emit(logging.makeLogRecord({'msg': json.dumps(event), 'levelno': logging.INFO,
                                                     'levelname': 'INFO'}))
            if kind != 'sample':
                self.events.append(event)
                if self.run:
                    self.append_run('events.jsonl', event)

    # ---- replay runs -------------------------------------------------------
    def runs_dir(self):
        return self.out / 'runs'

    def append_run(self, name, row):
        with open(self.run['dir'] / name, 'a', encoding='utf-8') as f:
            f.write(json.dumps(row) + '\n')

    def write_run_meta(self, **extra):
        meta = dict(self.run['meta'], **extra); self.run['meta'] = meta
        tmp = self.run['dir'] / 'run.tmp'; tmp.write_text(json.dumps(meta, indent=1)); tmp.replace(self.run['dir'] / 'run.json')

    def start_run(self):
        """A run is one connected stretch; reconnects within RUN_GAP_SECONDS continue it."""
        if self.record_fps <= 0:
            return
        stamp = time.strftime('%Y%m%d-%H%M%S', time.localtime())
        d = self.runs_dir() / stamp; n = 1
        while d.exists():
            n += 1; d = self.runs_dir() / f'{stamp}-{n}'
        (d / 'frames').mkdir(parents=True)
        with self.lock:
            self.run = {'dir': d, 'meta': {'id': d.name, 'session': self.spec['id'],
                        'label': self.spec.get('label', self.spec['id']), 'started': time.time(), 'ended': None,
                        'rom': self.rom, 'fixtures': self.spec.get('fixtures', []), 'frames': 0, 'commands': 0,
                        'record_fps': self.record_fps}}
            self.last_png_hash = None; self.history_seq = None
        self.write_run_meta()
        self.prune_runs()

    def end_run(self, reason):
        if self.run:
            self.write_run_meta(ended=time.time(), end_reason=reason)
            with self.lock:
                self.run = None

    def prune_runs(self):
        """Keep the newest keep_runs run folders for this session (own output only)."""
        try:
            runs = sorted(p for p in self.runs_dir().iterdir() if p.is_dir())
        except OSError:
            return
        for old in runs[:-self.keep_runs] if self.keep_runs > 0 else []:
            if self.run and old == self.run['dir']:
                continue
            shutil.rmtree(old, ignore_errors=True)

    def store_frame(self, png, frame):
        digest = hashlib.sha1(png).hexdigest()
        if digest == self.last_png_hash:
            return  # paused or static picture; scrubber shows the previous frame
        self.last_png_hash = digest
        n = self.run['meta']['frames'] + 1
        name = f'{n:06d}.png'
        (self.run['dir'] / 'frames' / name).write_bytes(png)
        self.append_run('frames.jsonl', {'t': time.time(), 'frame': frame, 'file': name})
        self.run['meta']['frames'] = n
        if n % 20 == 1:
            self.write_run_meta()

    def poll_commands(self):
        """Copy the agent's socket commands (COMMAND_HISTORY since=seq) into the run."""
        if self.command_log == 'unsupported':
            return
        since = self.history_seq if self.history_seq is not None else 0
        data = self.client.read('COMMAND_HISTORY', since=str(since), count='1000')
        if not isinstance(data, dict) or 'entries' not in data:
            self.command_log = 'unsupported'
            self.emit('command_log', status='unsupported',
                      note='This Mesen build has no COMMAND_HISTORY since=; showing driver reports only')
            return
        self.command_log = 'ok'
        oldest = int(data.get('oldestSeq', 0)); latest = int(data.get('latestSeq', 0))
        if self.history_seq is not None and oldest > since + 1:
            self.emit('command_gap', missed=oldest - since - 1)
        if latest < since:  # emulator restarted on the same socket
            self.history_seq = None; return
        rows = []
        for e in data['entries']:
            self.history_seq = max(self.history_seq or 0, int(e.get('seq', 0)))
            params = e.get('params') or {}
            if params.get('observer') == OBSERVER_TAG['observer']:
                continue
            rows.append({'seq': e.get('seq'), 't': (e.get('timeMs') or 0) / 1000, 'frame': e.get('frame'),
                         'command': e.get('command'), 'params': params, 'ok': e.get('success', e.get('errorCode') == 0),
                         'error_code': e.get('errorCode'), 'latency_us': e.get('latencyUs')})
        if self.history_seq is None:
            self.history_seq = latest
        if rows:
            with self.lock:
                self.commands.extend(rows)
            if self.run:
                for r in rows:
                    self.append_run('commands.jsonl', r)
                self.run['meta']['commands'] += len(rows)

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
                    if self.record_fps > 0 and self.run is None:
                        self.start_run()
                    self.emit('connected', rom=self.rom, passive_game_state=self.game_safe,
                              fixtures=self.spec.get('fixtures', []))
                self.record_changes(status, game)
                self.emit('sample', emulator=status, game=game)
                with self.lock:
                    self.state = status; self.game = game; self.connected = True; self.error = ''
                self.read_driver_evidence()
                if self.record_fps > 0:
                    self.poll_commands()
            live = self.connected and now < self.watching_until and now-self.last_image >= 1/self.fps
            record = self.connected and self.run is not None and now-self.last_record >= 1/max(self.record_fps, 0.01)
            if live or record:
                self.last_image = now if live else self.last_image
                self.last_record = now if record else self.last_record
                data = self.client.read('SCREENSHOT')
                png = base64.b64decode(data.strip('"'), validate=True)
                if not png.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError('Invalid screenshot data')
                temp = self.out / 'latest.tmp'; temp.write_bytes(png); temp.replace(self.out/'latest.png')
                with self.lock:
                    self.png = png
                if record:
                    self.store_frame(png, self.state.get('frame'))
        except Exception as exc:
            message = str(exc)
            if self.connected or message != self.error:
                self.emit('disconnected', reason=message)
            with self.lock:
                if self.connected or self.disconnected_at is None:
                    self.disconnected_at = now
                self.connected = False; self.error = message
                self.previous = None; self.game_safe = False
            if self.run and now - self.disconnected_at > RUN_GAP_SECONDS:
                self.end_run('emulator unreachable for %ds: %s' % (RUN_GAP_SECONDS, message[:120]))

    def snapshot(self):
        with self.lock:
            return dict(id=self.spec['id'], label=self.spec.get('label', self.spec['id']),
                        fixtures=self.spec.get('fixtures', []), connected=self.connected,
                        error=self.error, emulator=self.state, game=self.game, rom=self.rom,
                        game_state_available=self.game_safe, events=list(self.events),
                        driver=self.driver, driver_updated=self.driver_updated,
                        evidence_file=self.spec.get('evidence_file'),
                        video_age_seconds=round(time.monotonic()-self.last_image, 1) if self.last_image else None,
                        log=str(self.out/'events.jsonl'), commands=list(self.commands)[-40:],
                        command_log=self.command_log, run=self.run['meta'] if self.run else None)

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
        self.end_run('recorder stopped')
        self.handler.close()


class Spectator:
    def __init__(self, config, out, fps=4, record_fps=0, keep_runs=40):
        self.config = config; self.out = out; self.fps = fps
        self.record_fps = record_fps; self.keep_runs = keep_runs
        self.lock = threading.RLock(); self.lanes = {}; self.stamp = None; self.error = ''

    def reload(self):
        try:
            stamp = self.config.stat().st_mtime_ns
            if stamp == self.stamp:
                return
            cfg = json.loads(self.config.read_text()); specs = cfg['sessions']
            ids = [s['id'] for s in specs]
            if len(ids) != len(set(ids)) or len(ids) > MAX_SESSIONS:
                raise ValueError(f'Use up to {MAX_SESSIONS} distinct session ids')
            prepared = {}; created = []
            # Validate new lanes before changing running ones.
            with self.lock:
                try:
                    for spec in specs:
                        old = self.lanes.get(spec['id'])
                        if old and old.spec == spec and old.attestation == cfg.get('passive_binary'):
                            prepared[spec['id']] = old
                        else:
                            lane = Lane(spec, self.out, cfg.get('passive_binary'), self.fps,
                                        record_fps=self.record_fps, keep_runs=self.keep_runs)
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
                    'config_error': self.error, 'video_fps_limit': self.fps,
                    'record_fps': self.record_fps, 'home': str(self.out)}

    def close(self):
        with self.lock:
            for lane in self.lanes.values(): lane.close()


# ---- comments ----------------------------------------------------------------
SAFE_ID = re.compile(r'[a-zA-Z0-9_.-]{1,80}')


class Locked:
    """Exclusive fcntl lock on a sidecar file (works across agents and processes)."""
    def __init__(self, path):
        self.path = path
    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.f = open(self.path, 'a'); fcntl.flock(self.f, fcntl.LOCK_EX); return self
    def __exit__(self, *exc):
        fcntl.flock(self.f, fcntl.LOCK_UN); self.f.close()


def read_jsonl(path, limit=None):
    try:
        lines = path.read_text(encoding='utf-8').splitlines()
    except OSError:
        return []
    rows = []
    for line in lines[-limit:] if limit else lines:
        try: rows.append(json.loads(line))
        except ValueError: pass  # a writer may be mid-line
    return rows


class Comments:
    """scawful's notes on a run moment. One JSONL file all agents read before writing probes."""
    def __init__(self, home):
        self.home = Path(home); self.path = self.home / 'comments.jsonl'; self.lock = self.home / '.comments.lock'

    def all(self):
        return read_jsonl(self.path)

    def _rewrite(self, rows):
        tmp = self.path.with_suffix('.tmp')
        tmp.write_text(''.join(json.dumps(r) + '\n' for r in rows), encoding='utf-8'); tmp.replace(self.path)

    def context(self, session, run, t):
        d = self.home / session / 'runs' / run
        cmds = [c for c in read_jsonl(d / 'commands.jsonl') if (c.get('t') or 0) <= t][-6:]
        events = [e for e in read_jsonl(d / 'events.jsonl') if (e.get('t') or 0) <= t][-3:]
        return {'commands': [{k: c.get(k) for k in ('seq', 'command', 'params', 'frame', 'ok')} for c in cmds],
                'events': [{k: e.get(k) for k in ('kind', 'after', 'reason', 'data') if k in e} for e in events]}

    def add(self, body):
        session = str(body.get('session', '')); run = str(body.get('run', ''))
        text = str(body.get('text', '')).strip()
        if not SAFE_ID.fullmatch(session) or not SAFE_ID.fullmatch(run):
            raise ValueError('session and run are required')
        if not text or len(text) > 2000:
            raise ValueError('Comment text must be 1-2000 characters')
        if not (self.home / session / 'runs' / run).is_dir():
            raise ValueError('No such run')
        t = float(body.get('t') or time.time())
        row = {'id': uuid.uuid4().hex[:10], 'created': time.time(), 'author': str(body.get('author') or 'scawful')[:40],
               'session': session, 'run': run, 't': t, 'frame': body.get('frame'), 'text': text,
               'status': 'open', 'context': self.context(session, run, t)}
        with Locked(self.lock):
            with open(self.path, 'a', encoding='utf-8') as f:
                f.write(json.dumps(row) + '\n')
        return row

    def set_status(self, cid, status, note='', by=''):
        if status not in ('open', 'done'):
            raise ValueError('status must be open or done')
        with Locked(self.lock):
            rows = self.all(); hit = None
            for r in rows:
                if r.get('id') == cid:
                    r['status'] = status; r['resolved_note'] = note[:1000]; r['resolved_by'] = by[:60]
                    r['resolved'] = time.time() if status == 'done' else None; hit = r
            if not hit:
                raise KeyError(cid)
            self._rewrite(rows)
        return hit


# ---- run browsing ----------------------------------------------------------------
def list_runs(home, session=None):
    out = []
    for meta in Path(home).glob('*/runs/*/run.json'):
        if session and meta.parent.parent.parent.name != session:
            continue
        try: out.append(json.loads(meta.read_text()))
        except (OSError, ValueError): pass
    return sorted(out, key=lambda m: m.get('started') or 0, reverse=True)


def load_run(home, session, run, comments):
    d = Path(home) / session / 'runs' / run
    meta = json.loads((d / 'run.json').read_text())
    return {'meta': meta, 'frames': read_jsonl(d / 'frames.jsonl'), 'commands': read_jsonl(d / 'commands.jsonl'),
            'events': [e for e in read_jsonl(d / 'events.jsonl') if e.get('kind') != 'sample'],
            'comments': [c for c in comments.all() if c.get('session') == session and c.get('run') == run]}


def handler_for(app):
    comments = Comments(app.out)
    html = Path(__file__).with_suffix('.html')

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass

        def local_host(self):
            # Avoid DNS-rebinding exposure of local evidence. The planning board proxy sends Host 127.0.0.1.
            return self.headers.get('Host', '').split(':')[0] in ('127.0.0.1', 'localhost')

        def route(self):
            r = urlparse(self.path); path = r.path
            if path == '/probes' or path.startswith('/probes/'):
                path = path[len('/probes'):] or '/'
            return path, parse_qs(r.query)

        def do_POST(self):
            path, _ = self.route()
            if path not in ('/api/comment', '/api/comment_status'):
                self.send_error(405, 'Spectator has no emulator control API'); return
            origin = urlparse(self.headers.get('Origin', '')).hostname
            if not self.local_host() or (origin and origin not in ('127.0.0.1', 'localhost')) \
                    or 'application/json' not in self.headers.get('Content-Type', ''):
                self.send_error(403); return
            try:
                n = int(self.headers.get('Content-Length') or 0)
                if n > 20000: raise ValueError('Request too large')
                body = json.loads(self.rfile.read(n) or b'{}')
                if path == '/api/comment':
                    row = comments.add(body)
                else:
                    row = comments.set_status(str(body.get('id', '')), str(body.get('status', '')),
                                              str(body.get('note', '')), 'scawful')
                self.reply(201, 'application/json', json.dumps(row).encode())
            except KeyError:
                self.reply(404, 'application/json', b'{"error":"no such comment"}')
            except (ValueError, TypeError) as exc:
                self.reply(400, 'application/json', json.dumps({'error': str(exc)}).encode())

        def do_GET(self):
            if not self.local_host():
                self.send_error(403); return
            path, query = self.route()
            q = lambda k: query.get(k, [''])[0]
            try:
                if path == '/':
                    self.reply(200, 'text/html; charset=utf-8', html.read_bytes())
                elif path == '/api/status':
                    self.reply(200, 'application/json', json.dumps(app.snapshot()).encode())
                elif path == '/api/frame':
                    with app.lock: lane = app.lanes.get(q('id'))
                    if not lane: self.send_error(404); return
                    lane.watch()
                    with lane.lock: png = lane.png
                    self.reply(200 if png else 204, 'image/png', png or b'')
                elif path == '/api/runs':
                    sid = q('id')
                    if sid and not SAFE_ID.fullmatch(sid): self.send_error(400); return
                    self.reply(200, 'application/json', json.dumps(list_runs(app.out, sid or None)).encode())
                elif path in ('/api/run', '/api/run_frame'):
                    sid, run = q('id'), q('run')
                    if not SAFE_ID.fullmatch(sid) or not SAFE_ID.fullmatch(run): self.send_error(400); return
                    if path == '/api/run':
                        self.reply(200, 'application/json', json.dumps(load_run(app.out, sid, run, comments)).encode())
                    else:
                        name = q('f')
                        if not re.fullmatch(r'\d{6}\.png', name): self.send_error(400); return
                        data = (Path(app.out) / sid / 'runs' / run / 'frames' / name).read_bytes()
                        self.reply(200, 'image/png', data, cache='private, max-age=86400')
                elif path == '/api/comments':
                    rows = comments.all(); st = q('status')
                    self.reply(200, 'application/json', json.dumps([r for r in rows if not st or r.get('status') == st]).encode())
                else:
                    self.send_error(404)
            except (OSError, ValueError):
                self.send_error(404)

        def reply(self, status, mime, data, cache='no-store'):
            self.send_response(status); self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data))); self.send_header('Cache-Control', cache)
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; img-src 'self' blob: data:; "
                             "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                             "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'self'")
            self.end_headers()
            try: self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError): pass
    return Handler


# ---- registration (agents) ------------------------------------------------------
def register(home, spec):
    home = Path(home); cfg_path = home / 'sessions.json'
    if not SAFE_ID.fullmatch(spec['id']) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', spec['id']):
        raise SystemExit('id must be 1-64 of [a-zA-Z0-9_-]')
    if not Path(spec['socket']).is_absolute():
        raise SystemExit('socket must be an absolute path')
    if spec['id'] == 'oos-rc-play' or 'oos-rc-play' in spec['socket']:
        raise SystemExit("Refusing to register scawful's oos-rc-play session")
    with Locked(home / '.sessions.lock'):
        try: cfg = json.loads(cfg_path.read_text())
        except (OSError, ValueError): cfg = {'sessions': []}
        sessions = [s for s in cfg.get('sessions', []) if s.get('id') != spec['id']]
        if spec.get('remove'):
            removed = len(cfg.get('sessions', [])) - len(sessions)
            cfg['sessions'] = sessions
        else:
            sessions.append({k: v for k, v in spec.items() if v not in (None, '', []) and k != 'remove'})
            # Full: drop registrations whose emulator socket no longer exists (oldest first).
            while len(sessions) > MAX_SESSIONS:
                dead = [s for s in sessions[:-1] if not Path(s.get('socket', '')).exists()]
                if not dead:
                    raise SystemExit(f'{MAX_SESSIONS} live sessions already registered; unregister one first')
                sessions.remove(dead[0])
            cfg['sessions'] = sessions; removed = 0
        tmp = cfg_path.with_suffix('.tmp'); tmp.write_text(json.dumps(cfg, indent=1)); tmp.replace(cfg_path)
    return removed


def serve(config, out, port, fps, record_fps, keep_runs):
    out.mkdir(parents=True, exist_ok=True)
    if not config.exists():
        config.write_text('{"sessions": []}\n')
    app = Spectator(config.resolve(), out.resolve(), fps, record_fps=record_fps, keep_runs=keep_runs); app.reload()
    if app.error: raise SystemExit(app.error)
    try:
        server = ThreadingHTTPServer(('127.0.0.1', port), handler_for(app)); server.timeout = .4
    except OSError:
        app.close()
        raise
    done = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT): signal.signal(sig, lambda *_: done.set())
    print(f'Probe spectator: http://127.0.0.1:{server.server_port}/probes (observer only; recording {record_fps} fps)', flush=True)
    try:
        while not done.is_set(): app.reload(); server.handle_request()
    finally: server.server_close(); app.close()


def main(argv=None):
    import sys
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0].startswith('--') and argv[0] not in ('--help', '-h'):
        argv = ['serve-legacy'] + argv  # original --config/--out form
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    sv = sub.add_parser('serve', help='Run the recorder and viewer'); lg = sub.add_parser('serve-legacy')
    for a in (sv, lg):
        a.add_argument('--port', default=8769, type=int); a.add_argument('--fps', type=int, default=4, choices=range(1, 11))
        a.add_argument('--record-fps', type=float, default=2.0, help='Replay frames per second; 0 disables recording')
        a.add_argument('--keep-runs', type=int, default=40, help='Run folders kept per session')
    sv.add_argument('--home', type=Path, default=DEFAULT_HOME)
    lg.add_argument('--config', required=True, type=Path); lg.add_argument('--out', required=True, type=Path)
    rg = sub.add_parser('register', help="Add or replace this agent's session")
    rg.add_argument('--home', type=Path, default=DEFAULT_HOME); rg.add_argument('--id', required=True)
    rg.add_argument('--socket', required=True); rg.add_argument('--rom-sha1', default='')
    rg.add_argument('--label', default=''); rg.add_argument('--fixture', action='append', default=[])
    rg.add_argument('--evidence-glob', default=''); rg.add_argument('--evidence-file', default='')
    ur = sub.add_parser('unregister'); ur.add_argument('--home', type=Path, default=DEFAULT_HOME); ur.add_argument('--id', required=True)
    cm = sub.add_parser('comments', help="List scawful's run comments"); cm.add_argument('--home', type=Path, default=DEFAULT_HOME)
    cm.add_argument('--open', action='store_true', help='Only unresolved comments'); cm.add_argument('--json', action='store_true')
    rs = sub.add_parser('resolve', help='Mark a comment done with what changed'); rs.add_argument('id')
    rs.add_argument('--home', type=Path, default=DEFAULT_HOME); rs.add_argument('--note', required=True)
    rs.add_argument('--by', default=os.environ.get('USER', 'agent'))
    a = p.parse_args(argv)
    if a.cmd == 'serve':
        serve(a.home / 'sessions.json', a.home, a.port, a.fps, a.record_fps, a.keep_runs)
    elif a.cmd == 'serve-legacy':
        serve(a.config, a.out, a.port, a.fps, a.record_fps, a.keep_runs)
    elif a.cmd in ('register', 'unregister'):
        spec = {'id': a.id, 'socket': getattr(a, 'socket', '/'), 'remove': a.cmd == 'unregister'}
        if a.cmd == 'register':
            spec.update(label=a.label or a.id, expected_rom_sha1=a.rom_sha1, fixtures=a.fixture,
                        evidence_glob=a.evidence_glob, evidence_file=a.evidence_file)
        n = register(a.home, spec)
        print(f"{'Unregistered' if a.cmd == 'unregister' else 'Registered'} {a.id} in {a.home / 'sessions.json'}"
              + (' (was not registered)' if a.cmd == 'unregister' and not n else ''))
    elif a.cmd == 'comments':
        rows = [r for r in Comments(a.home).all() if not a.open or r.get('status') == 'open']
        if a.json:
            print(json.dumps(rows, indent=1)); return
        for r in rows:
            when = time.strftime('%Y-%m-%d %H:%M', time.localtime(r.get('t') or 0))
            last = (r.get('context') or {}).get('commands') or []
            print(f"[{r['status']}] {r['id']}  {r['session']}/{r['run']} @ {when} frame {r.get('frame')}\n    {r['text']}")
            if last: print('    last command: ' + str(last[-1].get('command')) + ' ' + json.dumps(last[-1].get('params') or {}))
            if r.get('resolved_note'): print('    resolved: ' + r['resolved_note'])
    elif a.cmd == 'resolve':
        r = Comments(a.home).set_status(a.id, 'done', a.note, a.by); print('Resolved ' + r['id'])


if __name__ == '__main__': main()

"""Replay recording, agent command log, comments and registration for the probe spectator."""
import base64
import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import socket
import socketserver
import sys
import tempfile
import threading
import time
import unittest
from urllib.request import urlopen, Request
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_spectator import Comments, Lane, Spectator, handler_for, list_runs, register

PNG_A = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII=')
PNG_B = b'\x89PNG\r\n\x1a\n' + b'second-frame'


class HistoryMesen(socketserver.ThreadingUnixStreamServer):
    """Fake Mesen that records a COMMAND_HISTORY like the patched SocketServer."""
    daemon_threads = True

    def __init__(self, path, legacy=False):
        self.history = []; self.png = PNG_A; self.legacy = legacy; self.frame = 100; self.lock = threading.Lock()
        outer = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                cmd = json.loads(self.rfile.readline()); t = cmd.pop('type')
                with outer.lock:
                    outer.frame += 1
                    if t == 'COMMAND_HISTORY':
                        if outer.legacy:
                            data = [{'command': h['command'], 'timestamp': 'x', 'errorCode': 0, 'latencyUs': 1} for h in outer.history]
                        else:
                            since = int(cmd.get('since', 0))
                            data = {'latestSeq': len(outer.history), 'oldestSeq': 1 if outer.history else 1,
                                    'entries': [h for h in outer.history if h['seq'] > since]}
                    else:
                        data = {'STATE': {'frame': outer.frame, 'paused': False}, 'ROMINFO': {'sha1': 'abc'},
                                'SCREENSHOT': base64.b64encode(outer.png).decode(), 'READ': '0x00', 'PAUSE': {}}.get(t)
                    outer.history.append({'seq': len(outer.history) + 1, 'command': t, 'frame': outer.frame,
                                          'timeMs': int(time.time() * 1000), 'success': True, 'errorCode': 0,
                                          'latencyUs': 5, 'params': {k: str(v) for k, v in cmd.items()}})
                self.wfile.write((json.dumps({'success': data is not None, 'data': data}) + '\n').encode())
        super().__init__(str(path), Handler)
        self.worker = threading.Thread(target=self.serve_forever, daemon=True); self.worker.start()

    def agent(self, command, **params):
        """What a probe driver does: its own connection, its own commands."""
        with socket.socket(socket.AF_UNIX) as s:
            s.connect(self.server_address); s.sendall((json.dumps(dict(type=command, **params)) + '\n').encode())
            s.makefile().readline()

    def close(self):
        self.shutdown(); self.server_close(); self.worker.join()


class RecordingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.mesen = HistoryMesen(self.root / 'm.sock')
        self.spec = {'id': 'bot', 'label': 'Bot', 'socket': str(self.root / 'm.sock'), 'expected_rom_sha1': 'abc'}
        self.lane = Lane(self.spec, self.root, {}, binary_check=lambda *_: False, record_fps=2)
        self.app = Spectator(self.root / 'sessions.json', self.root, record_fps=2); self.app.lanes = {'bot': self.lane}
        self.http = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(self.app))
        self.url = 'http://127.0.0.1:%d' % self.http.server_port
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def tearDown(self):
        self.http.shutdown(); self.http.server_close(); self.app.close(); self.mesen.close(); self.temp.cleanup()

    def get(self, path):
        with urlopen(self.url + path, timeout=3) as r:
            return r.status, r.read()

    def post(self, path, body, **headers):
        req = Request(self.url + path, data=json.dumps(body).encode(), method='POST',
                      headers=dict({'Content-Type': 'application/json'}, **headers))
        with urlopen(req, timeout=3) as r:
            return json.loads(r.read())

    def test_run_records_frames_agent_commands_and_skips_observer_reads(self):
        self.mesen.agent('READ', addr='0x7E0010')
        self.lane.tick(10)                        # connect, start run, poll history, first frame
        self.mesen.agent('PAUSE')
        self.lane.tick(10.6)                      # same picture: no new frame file
        self.mesen.png = PNG_B; self.lane.tick(11.2)
        run = self.lane.run['dir']
        frames = [json.loads(x) for x in (run / 'frames.jsonl').read_text().splitlines()]
        self.assertEqual([f['file'] for f in frames], ['000001.png', '000002.png'])
        cmds = [json.loads(x) for x in (run / 'commands.jsonl').read_text().splitlines()]
        self.assertEqual([c['command'] for c in cmds], ['READ', 'PAUSE'])
        self.assertEqual(cmds[0]['params'], {'addr': '0x7E0010'})
        # Observer itself sent only allowlisted reads, every one tagged.
        observer = [h for h in self.mesen.history if h['params'].get('observer') == 'spectator']
        self.assertTrue(observer and {h['command'] for h in observer} <= {'STATE', 'ROMINFO', 'SCREENSHOT', 'COMMAND_HISTORY'})
        # HTTP: run list, run detail, frame bytes; also under the board's /probes prefix.
        runs = json.loads(self.get('/probes/api/runs?id=bot')[1])
        self.assertEqual(runs[0]['id'], run.name)
        detail = json.loads(self.get('/api/run?id=bot&run=' + run.name)[1])
        self.assertEqual(len(detail['commands']), 2); self.assertEqual(len(detail['frames']), 2)
        self.assertEqual(self.get('/probes/api/run_frame?id=bot&run=%s&f=000002.png' % run.name)[1], PNG_B)
        with self.assertRaises(HTTPError) as ctx:
            self.get('/api/run_frame?id=bot&run=%s&f=../../sessions.json' % run.name)
        self.assertEqual(ctx.exception.code, 400); ctx.exception.close()

    def test_comment_captures_context_and_resolves(self):
        self.mesen.agent('READ', addr='0x7E0020'); self.lane.tick(10)
        run = self.lane.run['dir'].name
        row = self.post('/probes/api/comment', {'session': 'bot', 'run': run, 't': time.time() + 1, 'frame': 101,
                                                'text': 'Probe read $20 before the room loaded.'})
        self.assertEqual(row['status'], 'open')
        self.assertEqual(row['context']['commands'][-1]['command'], 'READ')
        self.assertEqual(len(json.loads(self.get('/api/comments?status=open')[1])), 1)
        Comments(self.root).set_status(row['id'], 'done', 'Probe now waits for module 07', 'agent')
        self.assertEqual(json.loads(self.get('/api/comments?status=open')[1]), [])
        self.assertEqual(json.loads(self.get('/api/run?id=bot&run=' + run)[1])['comments'][0]['status'], 'done')

    def test_post_guards(self):
        self.lane.tick(10); run = self.lane.run['dir'].name
        cases = [('/api/pause', {}, {}), ('/api/comment', {'session': 'bot', 'run': run, 'text': 'x'}, {'Origin': 'https://evil.example'}),
                 ('/api/comment', {'session': 'bot', 'run': '../x', 'text': 'x'}, {}),
                 ('/api/comment', {'session': 'bot', 'run': run, 'text': ''}, {})]
        for path, body, headers in cases:
            with self.assertRaises(HTTPError) as ctx:
                self.post(path, body, **headers)
            self.assertIn(ctx.exception.code, (400, 403, 405)); ctx.exception.close()
        before = len(self.mesen.history)
        self.assertFalse(any(h['command'] == 'PAUSE' for h in self.mesen.history[:before]))

    def test_legacy_binary_records_frames_without_command_log(self):
        self.mesen.legacy = True; self.lane.tick(10); self.lane.tick(11.1)
        self.assertEqual(self.lane.command_log, 'unsupported')
        self.assertFalse((self.lane.run['dir'] / 'commands.jsonl').exists())
        self.assertTrue((self.lane.run['dir'] / 'frames' / '000001.png').exists())

    def test_restarted_emulator_gets_new_run_and_rechecks_command_log(self):
        self.mesen.legacy = True; self.lane.tick(10); first = self.lane.run['dir']
        self.assertEqual(self.lane.command_log, 'unsupported')
        # Same socket, newer build, frame counter starts over.
        self.mesen.legacy = False; self.mesen.frame = 0; self.mesen.agent('READ', addr='0x7E0010')
        self.lane.tick(11.1)
        self.assertNotEqual(self.lane.run['dir'], first)
        self.assertIn('restarted', json.loads((first / 'run.json').read_text())['end_reason'])
        self.assertEqual(self.lane.command_log, 'ok')
        self.assertIn('READ', [c['command'] for c in self.lane.commands])

    def test_run_ends_after_gap_and_new_run_starts(self):
        self.lane.tick(10); first = self.lane.run['dir']
        self.mesen.close(); self.lane.tick(11.1); self.assertIsNotNone(self.lane.run)
        self.lane.tick(45); self.assertIsNone(self.lane.run)
        self.assertIsNotNone(json.loads((first / 'run.json').read_text())['ended'])
        (self.root / 'm.sock').unlink(); self.mesen = HistoryMesen(self.root / 'm.sock')
        self.lane.tick(46.2); self.assertNotEqual(self.lane.run['dir'], first)
        self.assertEqual(len(list_runs(self.root, 'bot')), 2)


class RegisterTest(unittest.TestCase):
    def test_register_replace_unregister_and_refuse_user_session(self):
        with tempfile.TemporaryDirectory() as d:
            home = Path(d); sock = home / 'a.sock'; sock.touch()
            register(home, {'id': 'a', 'socket': str(sock), 'label': 'A'})
            register(home, {'id': 'a', 'socket': str(sock), 'label': 'A2'})
            cfg = json.loads((home / 'sessions.json').read_text())
            self.assertEqual([s['label'] for s in cfg['sessions']], ['A2'])
            with self.assertRaises(SystemExit):
                register(home, {'id': 'oos-rc-play', 'socket': '/tmp/mesen2-oos-rc-play.sock'})
            for i in range(9):  # dead sockets are dropped once the list is full
                register(home, {'id': 'x%d' % i, 'socket': str(home / ('gone%d.sock' % i))})
            ids = [s['id'] for s in json.loads((home / 'sessions.json').read_text())['sessions']]
            self.assertIn('a', ids); self.assertLessEqual(len(ids), 8)
            self.assertEqual(register(home, {'id': 'a', 'socket': '/', 'remove': True}), 1)


if __name__ == '__main__':
    unittest.main()

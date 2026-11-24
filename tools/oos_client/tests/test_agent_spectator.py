"""Real Unix-socket + HTTP integration checks for passive spectating."""
import base64
from collections import Counter
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import socketserver
import sys
import tempfile
import threading
import time
import unittest
from urllib.request import urlopen, Request
from urllib.error import HTTPError

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from agent_spectator import Lane, PassiveClient, Spectator, handler_for

PNG=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII=')

class FakeMesen(socketserver.ThreadingUnixStreamServer):
    daemon_threads=True
    def __init__(self,path):
        self.commands=[];self.hp=24;self.room='0x0005';self.sha='abc';self.available=True;self.frame=0
        outer=self
        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                cmd=json.loads(self.rfile.readline());t=cmd['type'];outer.commands.append(t);outer.frame+=1
                if not outer.available:self.wfile.write(b'{"success":false,"error":"No ROM loaded"}\n');return
                data={'STATE':{'frame':outer.frame,'paused':False,'fps':60},'ROMINFO':{'sha1':outer.sha,'filename':'fixture.sfc'},'SCREENSHOT':base64.b64encode(PNG).decode(),'GAMESTATE':{'health':{'current':outer.hp,'max':24},'game':{'mode':7,'indoors':True,'room_id':outer.room},'items':{'rupees':0}}}.get(t)
                self.wfile.write((json.dumps({'success':data is not None,'data':data})+'\n').encode())
        super().__init__(str(path),Handler)
        self.worker=threading.Thread(target=self.serve_forever,daemon=True);self.worker.start()
    def close(self):self.shutdown();self.server_close();self.worker.join()

class SpectatorWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.mesen=FakeMesen(self.root/'owned.sock')
        self.spec={'id':'bot','label':'Test bot','socket':str(self.root/'owned.sock'),'expected_rom_sha1':'abc','fixtures':['controlled fixture']}
        self.lane=Lane(self.spec,self.root/'logs',{},binary_check=lambda *_:True)
        self.app=Spectator(self.root/'config.json',self.root/'logs');self.app.lanes={'bot':self.lane}
        self.http=ThreadingHTTPServer(('127.0.0.1',0),handler_for(self.app));self.url='http://127.0.0.1:'+str(self.http.server_port)
        self.worker=threading.Thread(target=self.http.serve_forever,daemon=True);self.worker.start()
    def tearDown(self):
        self.http.shutdown();self.http.server_close();self.worker.join();self.app.close();self.mesen.close();self.temp.cleanup()
    def read(self,path):
        with urlopen(self.url+path,timeout=3) as r:return r.status,r.read()
    def test_http_video_hide_log_reconnect_and_rom_identity(self):
        self.lane.tick(10);self.assertTrue(self.lane.snapshot()['connected'])
        self.assertEqual(self.read('/api/frame?id=bot')[0],204)
        self.lane.tick(10.3);self.assertEqual(self.read('/api/frame?id=bot')[1],PNG)
        self.mesen.hp=16;self.mesen.room='0x0006';self.lane.tick(11.4)
        status=json.loads(self.read('/api/status')[1])['sessions'][0]
        self.assertEqual(status['game']['health']['current'],16)
        self.assertTrue(any(e['kind']=='health' for e in status['events']))
        # Expire the viewer lease without touching emulator state. Logging continues.
        self.lane.watching_until=0;count=Counter(self.mesen.commands);self.lane.tick(12.5)
        self.assertEqual(Counter(self.mesen.commands)['SCREENSHOT'],count['SCREENSHOT'])
        self.assertGreater(Counter(self.mesen.commands)['STATE'],count['STATE'])
        self.mesen.available=False;self.lane.tick(13.6);self.assertFalse(self.lane.snapshot()['connected'])
        self.mesen.available=True;self.mesen.sha='wrong';before=Counter(self.mesen.commands);self.lane.tick(14.7)
        self.assertFalse(self.lane.snapshot()['connected']);self.assertEqual(Counter(self.mesen.commands)['GAMESTATE'],before['GAMESTATE'])
        self.mesen.sha='abc';self.lane.tick(15.8);self.assertTrue(self.lane.snapshot()['connected'])
        rows=[json.loads(x) for x in (self.lane.out/'events.jsonl').read_text().splitlines()]
        self.assertTrue(any(e['kind']=='disconnected' for e in rows))
        self.assertTrue(any(e['kind']=='location' and e['after']['room_id']=='0x0006' for e in rows))
        self.assertLessEqual(set(self.mesen.commands),{'STATE','ROMINFO','GAMESTATE','SCREENSHOT'})
    def test_unknown_binary_has_video_without_game_state(self):
        self.lane.binary_check=lambda *_:False;self.lane.tick(10);self.lane.watch();self.lane.tick(10.3)
        self.assertNotIn('GAMESTATE',self.mesen.commands);self.assertIn('SCREENSHOT',self.mesen.commands)
        self.assertFalse(self.lane.snapshot()['game_state_available'])
    def test_load_and_title_transitions_do_not_claim_health_or_item_changes(self):
        def observe(mode,hp,pearl,load):
            self.lane.record_changes({'paused':False,'lastLoad':load},
                {'game':{'mode':mode},'health':{'current':hp},'items':{'pearl':pearl}})
        observe(9,6,0,None)
        observe(9,24,1,{'path':'checkpoint.mss'})
        observe(0,0,0,{'path':'checkpoint.mss'})
        observe(9,24,1,{'path':'checkpoint.mss'})
        self.assertFalse(any(e['kind'] in ('health','items') for e in self.lane.events))
        observe(9,16,1,{'path':'checkpoint.mss'})
        self.assertEqual(sum(e['kind']=='health' for e in self.lane.events),1)
    def test_emulator_controls_and_cross_host_requests_refused(self):
        with self.assertRaises(ValueError):PassiveClient(self.spec['socket']).read('PAUSE')
        for req in [Request(self.url+'/api/pause',data=b'{}',method='POST'),Request(self.url+'/',headers={'Host':'attacker.example'})]:
            with self.assertRaises(HTTPError) as ctx:urlopen(req)
            self.assertIn(ctx.exception.code,(403,405));ctx.exception.close()
        self.assertEqual(self.mesen.commands,[])
    def test_partial_driver_file_then_valid_state_preserves_evidence(self):
        p=self.root/'runner.json';self.lane.spec['evidence_file']=str(p);p.write_text('{')
        self.lane.tick(10);self.assertEqual(self.lane.driver,{})
        p.write_text(json.dumps({'commands':[{'command':{'action':'press','button':'a'},'after':{'pearl':1,'hp':16}}]}))
        self.lane.tick(11.1);self.assertEqual(self.lane.driver['state']['pearl'],1)
        self.assertTrue(any(e['kind']=='driver' for e in self.lane.events))
    def test_bad_config_does_not_replace_running_lane(self):
        self.app.config.write_text(json.dumps({'sessions':[self.spec,self.spec]}));self.app.reload()
        self.assertIn('distinct',self.app.error);self.assertIs(self.app.lanes['bot'],self.lane)

if __name__=='__main__':unittest.main()

import unittest,threading,time,json,urllib.request,urllib.error
from types import SimpleNamespace
from tablet_bridge import ExternalGps,TabletBridge
from live_demo import Demo

class GpsTest(unittest.TestCase):
 def setUp(self):
  self.g=ExternalGps();self.gen=self.g.start();self.b=dict(generation=self.gen,latitude=37.5,longitude=127.,accuracy=5,ageMs=100,speed=1,sequence=1)
 def test_freshness_uses_fix_age_not_just_receipt(self):
  self.g.accept(self.b,100);self.assertIsNotNone(self.g.current(104));self.assertIsNone(self.g.current(105))
 def test_parked_fix_tolerates_slower_wifi_updates(self):
  self.g.accept(dict(self.b,speed=0),100);self.assertIsNotNone(self.g.current(114));self.assertIsNone(self.g.current(116))
  self.g.stop();self.assertIsNone(self.g.current(101))
 def test_invalid_fixes_do_not_change_position(self):
  for field,value in [('latitude',float('nan')),('longitude',181),('accuracy',51),('ageMs',3001),('speed',81),('sequence',True)]:
   with self.subTest(field=field),self.assertRaises(ValueError):self.g.accept(dict(self.b,**{field:value}),100)
  self.assertIsNone(self.g.fix)
 def test_replay_and_jump_rejected(self):
  self.g.accept(self.b,100)
  for b in [self.b,dict(self.b,sequence=2,latitude=38)]:
   with self.assertRaises(ValueError):self.g.accept(b,101)
  self.assertEqual(37.5,self.g.fix['latitude'])
 def test_reconnect_does_not_accept_previous_session(self):
  self.g.start()
  with self.assertRaises(ValueError):self.g.accept(self.b,100)
  self.g.stop();self.assertIsNone(self.g.current(100))
 def test_automatic_route_cannot_start_with_tablet_gps(self):
  d=object.__new__(Demo);d.action_lock=threading.Lock();d.external_gps=self.g
  with self.assertRaises(ValueError):d.act('start',{})
  self.assertTrue(self.g.enabled);self.assertFalse(d.busy)

class BridgeTest(unittest.TestCase):
 def setUp(self):
  self.demo=SimpleNamespace(state={},external_gps=ExternalGps(),lock=threading.RLock(),mode='test')
  self.bridge=TabletBridge(self.demo,0);self.bridge.serve();self.port=self.bridge.server.server_port
 def tearDown(self):self.bridge.close()
 def call(self,path,body=None,token=None,origin=None):
  headers={}
  if token:headers['Authorization']='Bearer '+token
  if origin:headers['Origin']=origin
  if body is not None:headers['Content-Type']='application/json'
  req=urllib.request.Request(f'http://127.0.0.1:{self.port}'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
  try:
   with urllib.request.urlopen(req,timeout=3) as r:return r.status,r.read()
  except urllib.error.HTTPError as e:return e.code,e.read()
 def test_no_token_cannot_read_or_control(self):
  for path,body in [('/state',None),('/video',None),('/touch',{}),('/gps',{})]:self.assertEqual(403,self.call(path,body)[0])
 def test_valid_token_but_wrong_session_is_denied(self):
  self.assertEqual(400,self.call('/touch',{'session':'wrong'},self.bridge.token)[0])
 def test_cross_origin_and_expired_credentials_denied(self):
  self.assertEqual(403,self.call('/state',token=self.bridge.token,origin='https://other.example')[0])
  self.bridge.expires=time.monotonic()-1
  self.assertEqual(403,self.call('/state',token=self.bridge.token)[0])
 def test_disconnect_invalidates_fix_but_does_not_restore_automatic_motion(self):
  g=self.demo.external_gps;gen=g.start();g.accept(dict(generation=gen,latitude=37,longitude=127,accuracy=3,ageMs=0,speed=5,sequence=1),time.monotonic())
  mirror=SimpleNamespace(close=lambda:None);self.bridge.mirror=mirror;self.bridge.disconnect(mirror)
  self.assertTrue(g.enabled);self.assertIsNone(g.current(time.monotonic()));self.assertIsNone(self.bridge.mirror)

if __name__=='__main__':unittest.main()

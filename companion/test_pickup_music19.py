import unittest,base64,json,threading,urllib.request,urllib.error
from server import Relay,ApiError,Server,Handler
import pickup,music_stream
class FakeMaps:
 def reverse(self,b):return dict(address='서울특별시 서초구 강남대로 221',road='강남대로 221')
 def directions(self,b):return dict(routes=[dict(duration=120,geometry=dict(coordinates=[b['start'],b['end']]))])
class PickupMusicTest(unittest.TestCase):
 def setUp(self):
  self.now=100.;self.relay=Relay(clock=lambda:self.now);self.relay.maps=FakeMaps();self.sid=self.relay.create()['id'];self.s=self.relay.sessions[self.sid];self.tokens=dict(self.s['tokens']);self.n=0
  self.state=dict(authorityActive=True,authorityEpoch='one',authorityMode='connection',parked=True,vehiclePosition=dict(point=[127.035,37.486],ageMs=0,accuracy=5))
  self.sync()
  for who in ['a','b']:
   d=self.call('family','pair-device');self.tokens[who]=d['token'];setattr(self,who,d['grant']['deviceId'])
 def call(self,who,action,**b):return self.relay.call(self.sid,self.tokens[who],action,b)
 def sync(self):self.call('car','sync',**self.state)
 def tick(self):self.now+=2;self.sync()
 def draft(self,who='a',**extra):
  self.tick();b=dict(provider='tmap',consent=True,point=[127.036,37.487],accuracy=8,ageMs=0);b.update(extra);return self.call(who,'pickup-location',**b)
 def send(self,who='a',memo='정문에서 아이와 기다려요'):
  d=self.draft(who);self.tick();self.n+=1;b=dict(id='p'+str(self.n),client='x',kind='pickup',title='ignored',options=dict(draft=d['draft'],name='가족',landmark='편의점 정문 앞',message=memo));return self.call(who,'request',**b),b
 def enable(self):self.call('owner','pickup-share',enabled=True)
 def test_opt_in_fresh_position_and_roles(self):
  self.assertIsNone(self.call('a','status')['pickup']['vehicle'])
  with self.assertRaises(ApiError):self.draft()
  with self.assertRaises(ApiError):self.call('a','pickup-share',enabled=True)
  self.enable();self.assertIsNotNone(self.call('a','status')['pickup']['vehicle']);self.assertIsNone(self.call('guest','status')['pickup']['vehicle'])
  self.state['vehiclePosition']['ageMs']=16000;self.sync();self.assertIsNone(self.call('a','status')['pickup']['vehicle'])
 def test_precise_gps_and_finite_input_required(self):
  self.enable()
  for v in [dict(accuracy=101),dict(ageMs=61000),dict(point=[float('nan'),37]),dict(accuracy=True)]:
   with self.assertRaises(ApiError):self.draft(**v)
 def test_landmark_memo_speech_and_idempotency_without_climate_grant(self):
  self.enable();r,b=self.send(memo='가'*2000);self.assertIn('강남대로',r['title']);self.assertIn('편의점 정문',r['title']);self.assertLessEqual(len(r['title']),120);self.assertNotIn('가'*100,r['title']);self.assertEqual('pending',r['status']);self.assertEqual(r['id'],self.call('a','request',**b)['id'])
  self.assertEqual(1,len(self.call('owner','status')['pickup']['waiting']))
  self.assertEqual(0,len(self.call('b','status')['pickup']['waiting']));self.assertFalse(any(x['kind']=='pickup' for x in self.call('b','status')['requests']))
 def test_ignition_transition_keeps_pickup_but_not_control_authority(self):
  self.enable();r,_=self.send()
  self.state.update(authorityEpoch='two',authorityActive=True,parked=False);self.tick()
  self.assertEqual('pending',self.s['requests'][r['id']]['status'])
  self.assertEqual(1,len(self.call('a','status')['pickup']['waiting']))
  self.assertFalse(self.call('a','status')['grant']['active'])
  self.state.update(authorityActive=False,authorityEpoch='');self.tick()
  self.assertEqual('pending',self.s['requests'][r['id']]['status'])
  self.now+=16;self.sync()
  self.assertEqual('rejected',self.s['requests'][r['id']]['status'])
 def test_missing_landmark_oversize_memo_stale_draft_rejected(self):
  self.enable();d=self.draft();self.tick()
  for o in [dict(landmark=''),dict(message='x'*2001),dict(draft='wrong')]:
   b=dict(draft=d['draft'],name='가족',landmark='가게 앞',message='');b.update(o)
   with self.assertRaises(ApiError):self.call('a','request',id='bad',kind='pickup',title='a',client='x',options=b)
  self.now+=121;self.sync()
  with self.assertRaises(ApiError):self.call('a','request',id='bad',kind='pickup',title='a',client='x',options=dict(draft=d['draft'],landmark='가게 앞'))
 def test_road_eta_uses_car_and_sent_gps(self):
  self.enable();r,_=self.send();self.tick();v=self.call('a','pickup-eta',provider='tmap',consent=True);self.assertEqual(120,v['seconds']);self.assertGreater(v['meters'],100)
  self.tick()
  with self.assertRaises(ApiError):self.call('b','pickup-eta',provider='tmap',consent=True)
 def test_cancel_boarding_and_share_stop_clear_waiting_and_pending(self):
  self.enable();r,_=self.send();self.call('a','pickup-cancel');self.assertEqual('rejected',self.s['requests'][r['id']]['status'])
  r,_=self.send();self.call('owner','pickup-boarded',deviceId=self.a);self.assertEqual([],self.call('a','status')['pickup']['waiting'])
  r,_=self.send();self.call('owner','pickup-share',enabled=False);self.assertEqual('rejected',self.s['requests'][r['id']]['status']);self.assertNotIn('pickupDraft',self.s['devices'][self.a]);self.assertIsNone(self.call('a','status')['pickup']['vehicle'])
 def test_share_expiry_and_slow_lookup_revocation(self):
  self.enable();r,_=self.send();self.now+=1801;self.sync();self.assertEqual([],self.call('a','status')['pickup']['waiting'])
  def reverse(b):self.call('owner','pickup-share',enabled=False);return dict(address='주소',road='도로')
  self.relay.maps.reverse=reverse
  with self.assertRaises(ApiError):self.draft()
 def start_music(self):return self.call('owner','music-start')['stream']
 def frame(self,stream,seq=0,**extra):
  b=dict(stream=stream,seq=seq,pcm=base64.b64encode(b'\x01\x00\x01\x00'*9600).decode(),peak=1);b.update(extra);return self.call('owner','music-frame',**b)
 def test_audio_roles_format_and_http_sized_frame(self):
  with self.assertRaises(ApiError):self.call('a','music-start')
  stream=self.start_music();self.frame(stream)
  with self.assertRaises(ApiError):self.call('a','music-pull')
  f=self.call('car','music-pull')['frames'];self.assertEqual(38400,len(base64.b64decode(f[0]['pcm'])));self.assertEqual([],self.call('car','music-pull',stream=stream,after=0)['frames'])
  for e in [dict(pcm='not base64!'),dict(seq=201),dict(peak=40000)]:
   with self.assertRaises(ApiError):self.frame(stream,**e)
 def test_pause_stop_replacement_stream_and_freshness(self):
  old=self.start_music();self.frame(old);new=self.start_music();self.call('owner','music-command',command='stop',stream=old);self.assertEqual(new,self.s['music']['id'])
  self.frame(new);self.call('car','music-command',command='pause');self.assertTrue(self.call('car','music-pull')['paused']);self.call('car','music-command',command='resume');self.assertFalse(self.call('car','music-pull')['paused'])
  self.now+=5;self.assertFalse(self.call('car','music-pull')['active']);self.assertEqual([],self.call('car','music-pull')['frames']);self.call('car','music-command',command='stop');self.assertNotIn('music',self.s)
 def test_queue_bounded_no_stale_replay_and_car_disconnect(self):
  s=self.start_music()
  for i in range(20):self.frame(s,i)
  self.assertEqual(6,len(self.s['music']['frames']));self.assertEqual(3,len(self.call('car','music-pull')['frames']));self.now+=2;self.assertEqual([],self.call('car','music-pull')['frames'])
  self.now+=15
  with self.assertRaises(ApiError):self.frame(s,20)
 def test_http_accepts_pcm_frame_and_rejects_unbounded_payload(self):
  server=Server(('127.0.0.1',0),Handler);server.relay=self.relay;thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  try:
   stream=self.start_music();b=dict(stream=stream,seq=0,peak=10,pcm=base64.b64encode(bytes(38400)).decode());url=f'http://127.0.0.1:{server.server_port}/api/sessions/{self.sid}/music-frame'
   req=urllib.request.Request(url,json.dumps(b).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+self.tokens['owner']})
   with urllib.request.urlopen(req) as r:self.assertEqual(200,r.status)
   req=urllib.request.Request(url,b'x'*65001,headers={'Content-Type':'application/json'})
   with self.assertRaises(urllib.error.HTTPError) as e:urllib.request.urlopen(req)
   self.assertEqual(413,e.exception.code)
  finally:server.shutdown();server.server_close()
class ReverseAddressTest(unittest.TestCase):
 def test_road_and_parcel_fallback_never_expand_to_distant_address(self):
  from tmap_maps import TmapMaps
  from unittest.mock import patch
  import tempfile,pathlib
  with tempfile.TemporaryDirectory() as d:
   c=pathlib.Path(d)/'settings.json';c.write_text(json.dumps(dict(enabled=True,app_key='A'*32)))
   observed=[]
   def transport(path,params,key,payload):
    observed.append(params)
    return dict(addressInfo=dict(fullAddress='서울특별시 강남구 도곡동 551',gu_gun='강남구',legalDong='도곡동',bunji='551',roadName='',buildingIndex=''))
   m=TmapMaps(c,transport);r=m.reverse(dict(point=[127.0356,37.4899]));self.assertEqual('강남구 도곡동 551',r['road']);self.assertEqual('N',observed[0]['newAddressExtend']);self.assertEqual('A10',observed[0]['addressType'])
   m.transport=lambda *args:dict(addressInfo=dict(fullAddress='서울 강남대로 221',roadName='강남대로',buildingIndex='221'))
   self.assertEqual('강남대로 221',m.reverse(dict(point=[127,37]))['road'])
if __name__=='__main__':unittest.main()

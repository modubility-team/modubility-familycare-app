import unittest, copy
from server import Relay,ApiError

class DelegationTest(unittest.TestCase):
 def setUp(self):
  self.now=100;self.r=Relay(clock=lambda:self.now);s=self.r.create();self.sid=s['id'];self.tokens=self.r.sessions[self.sid]['tokens'].copy()
  self.state=dict(ignitionOn=True,ignitionEpoch='cycle-one',parked=True,climateSupported=True,controls=[dict(id='hvac.ac',label='에어컨',group='공조',min=0,max=1,available=True,ownerOnly=False),dict(id='window.left',label='창문',group='문과 창문',min=0,max=100,available=True,ownerOnly=True)])
  self.sync();self.a=self.call('family','pair-device');self.b=self.call('family','pair-device')
  self.tokens['a']=self.a['token'];self.tokens['b']=self.b['token'];self.n=0
 def call(self,principal,action,**body):return self.r.call(self.sid,self.tokens[principal],action,body)
 def sync(self,**kw):self.state.update(kw);return self.call('car','sync',**self.state)
 def request(self,role='a',kind='climate',**kw):
  self.n+=1;self.now+=2
  return self.call(role,'request',**dict(dict(id=str(self.n),client='untrusted-id',kind=kind,title='테스트',**(dict(target=23) if kind=='climate' else {})),**kw))
 def approve(self,scopes=None):
  r=self.request(kind='permission',options=dict(name='동승자',scopes=scopes or ['climate']))
  self.call('car','claim',id=r['id']);self.call('car','result',id=r['id'],status='confirmed');return r
 def test_pairing_isolated_and_base_link_has_no_grant(self):
  for who in ['family','a','b']:
   with self.assertRaises(ApiError):self.request(who,delegated=True,originRole='owner',authorityEpoch='cycle-one')
  self.assertNotEqual(self.a['grant']['deviceId'],self.b['grant']['deviceId'])
  self.assertEqual(self.a['token'],self.call('a','pair-device')['token'])
 def test_one_approval_allows_repeated_scoped_commands(self):
  self.approve()
  for _ in range(2):
   r=self.request();self.assertTrue(r['delegated']);self.call('car','claim',id=r['id']);self.call('car','result',id=r['id'],status='confirmed')
  with self.assertRaises(ApiError):self.request(kind='control',options=dict(control='hvac.ac',value=1))
  with self.assertRaises(ApiError):self.request('b',client=self.a['grant']['deviceId'])
  with self.assertRaises(ApiError):self.call('a','result',id='1',status='confirmed')
 def test_off_restart_and_stale_connection_revoke(self):
  self.approve();self.sync(ignitionOn=False,ignitionEpoch='')
  self.assertFalse(self.call('a','status')['grant']['active'])
  self.sync(ignitionOn=True,ignitionEpoch='cycle-two')
  with self.assertRaises(ApiError):self.request()
  self.approve();self.now+=16;self.sync()
  with self.assertRaises(ApiError):self.request()
 def test_revoke_blocks_queued_command_and_result_replay(self):
  approved=self.approve();r=self.request()
  self.call('owner','revoke-grant',deviceId=self.a['grant']['deviceId'])
  with self.assertRaises(ApiError):self.call('car','claim',id=r['id'])
  self.call('car','result',id=approved['id'],status='confirmed')
  self.assertFalse(self.call('a','status')['grant']['active'])
 def test_role_revocation_destroys_device_credential(self):
  self.approve();self.call('owner','permissions',role='family',enabled=False);self.call('owner','permissions',role='family',enabled=True)
  with self.assertRaises(ApiError):self.call('a','status')
 def test_approval_needs_current_ignition_and_park(self):
  r=self.request(kind='permission',options=dict(name='동승자',scopes=['climate']))
  self.call('car','claim',id=r['id']);self.sync(parked=False)
  self.assertEqual('rejected',self.call('car','result',id=r['id'],status='confirmed')['status'])
  self.sync(parked=True);r=self.request(kind='permission',options=dict(name='동승자',scopes=['climate']));self.call('car','claim',id=r['id']);self.sync(ignitionEpoch='cycle-two')
  self.assertEqual('rejected',self.call('car','result',id=r['id'],status='confirmed')['status'])
 def test_only_scoped_comfort_works_while_driving(self):
  self.approve(['control:hvac.ac','control:window.left']);self.sync(parked=False)
  self.assertTrue(self.request(kind='control',options=dict(control='hvac.ac',value=1))['delegated'])
  with self.assertRaises(ApiError):self.request(kind='control',options=dict(control='window.left',value=1))
 def test_malformed_unknown_or_duplicate_scopes_rejected(self):
  for scopes in [[],['engine'],['climate','climate'],[None],[{}]]:
   with self.assertRaises(ApiError):self.request(kind='permission',options=dict(name='동승자',scopes=scopes))
  with self.assertRaises(ApiError):self.request(kind='control',options=['invalid'])
 def test_grant_catalog_does_not_include_unsupported_controls(self):
  self.state['controls'][0]['available']=False;self.sync()
  ids=[x['id'] for x in self.call('a','status')['permissionCatalog']]
  self.assertNotIn('control:hvac.ac',ids);self.assertIn('control:window.left',ids)
 def test_reapproval_replaces_instead_of_accumulating(self):
  self.approve(['climate','control:hvac.ac']);self.approve(['climate'])
  self.assertEqual(['climate'],self.call('a','status')['grant']['scopes'])
 def test_status_never_exposes_device_bearer(self):
  self.approve()
  for who in ['owner','car','a','b','guest','observer']:
   text=str(self.call(who,'status'));self.assertNotIn(self.a['token'],text);self.assertNotIn(self.b['token'],text)

 def test_unsupported_ignition_connection_mode_uses_fresh_session_epoch(self):
  self.sync(ignitionOn=None,ignitionEpoch='',ignitionSupported=False,authorityMode='connection',authorityActive=True,authorityEpoch='connection-one')
  self.approve();self.assertTrue(self.request()['delegated']);self.assertIsNone(self.call('a','status')['state']['ignitionOn'])
  self.sync(authorityActive=False,authorityEpoch='');self.assertFalse(self.call('a','status')['grant']['active'])
  self.sync(authorityActive=True,authorityEpoch='connection-two')
  with self.assertRaises(ApiError):self.request()

 def test_phone_disconnect_revokes_and_invalidates_its_token(self):
  self.approve();self.call('a','leave-device')
  with self.assertRaises(ApiError):self.call('a','status')
  self.assertEqual('family',self.call('b','status')['role'])

 def test_revoke_while_driver_approval_is_in_flight_cannot_regrant(self):
  r=self.request(kind='permission',options=dict(name='동승자',scopes=['climate']))
  self.call('car','claim',id=r['id']);self.call('owner','revoke-grant',deviceId=self.a['grant']['deviceId'])
  self.assertEqual('rejected',self.call('car','result',id=r['id'],status='confirmed')['status'])
  self.assertFalse(self.call('a','status')['grant']['active'])

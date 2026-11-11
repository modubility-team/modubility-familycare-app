import unittest,threading,http.client,json,time,sys
from pathlib import Path
from server import Server,Handler,Relay
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from tunnel_client import external_links,qr_image

class PublicGatewayTest(unittest.TestCase):
 def setUp(self):
  self.relay=Relay('http://192.168.0.2:8765');self.session=self.relay.create();self.sid=self.session['id'];self.tokens=self.relay.sessions[self.sid]['tokens']
  self.relay.call(self.sid,self.tokens['car'],'sync',dict(parked=True,vehiclePosition=dict(point=[127.03,37.48],ageMs=0,accuracy=5)))
  self.external=Server(('127.0.0.1',0),Handler);self.external.relay=self.relay;self.external.external_only=True;self.external.external_enabled=True;self.external.external_origin='https://test-family.trycloudflare.com';self.external.external_probe='probe'
  self.lan=Server(('127.0.0.1',0),Handler);self.lan.relay=self.relay;self.lan.control_token='local-control-test'
  for s in [self.external,self.lan]:s.daemon_threads=True;threading.Thread(target=s.serve_forever,daemon=True).start()
 def tearDown(self):
  for s in [self.external,self.lan]:s.shutdown();s.server_close()
 def req(self,path,role=None,body=None,external=True,headers=None,method='POST'):
  server=self.external if external else self.lan;c=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
  h={'Host':'test-family.trycloudflare.com' if external else f'127.0.0.1:{server.server_port}','Content-Type':'application/json'}
  if role:h['Authorization']='Bearer '+self.tokens.get(role,role)
  h.update(headers or {})
  try:
   c.request(method,path,json.dumps(body or {}) if method=='POST' else None,h);r=c.getresponse();raw=r.read();return r.status,json.loads(raw) if r.getheader('Content-Type','').startswith('application/json') else raw
  finally:c.close()
 def action(self,action,**kwargs):return self.req(f'/api/sessions/{self.sid}/{action}',**kwargs)
 def test_public_health_and_join_have_no_session_or_keys(self):
  status,data=self.req('/health',method='GET');self.assertEqual(200,status);self.assertEqual('probe',data['externalProbe']);self.assertNotIn('token',str(data));self.assertEqual(200,self.req('/join',method='GET')[0])
 def test_creation_is_blocked_even_with_local_proxy_and_spoof_headers(self):
  self.assertEqual(403,self.req('/api/sessions',headers={'X-Forwarded-For':'127.0.0.1','CF-Connecting-IP':'127.0.0.1'})[0]);self.assertEqual(1,len(self.relay.sessions))
 def test_car_credential_cannot_be_used_over_public_gateway(self):
  for action in ['sync','claim','result','end','voice-interpret']:
   with self.subTest(action=action):self.assertEqual(403,self.action(action,role='car')[0])
 def test_admin_control_is_never_public(self):
  self.assertEqual(403,self.req('/api/local-tunnel',body={'action':'start'},headers={'X-Relay-Control':'local-control-test'})[0])
  self.assertEqual(403,self.req('/api/local-tunnel',external=False,body={'action':'start'})[0])
 def test_invalid_token_and_wrong_origin_are_blocked(self):
  self.assertEqual(403,self.action('status',role='incorrect')[0]);self.assertEqual(403,self.action('status',role='family',headers={'Origin':'https://other.test'})[0]);self.assertEqual(403,self.req('/health',method='GET',headers={'Host':'localhost'})[0])
 def test_family_can_pair_read_position_and_send_note(self):
  self.assertEqual(200,self.action('pair-device',role='family')[0]);status,data=self.action('status',role='family');self.assertEqual(200,status);self.assertFalse(data['stale'])
  code,data=self.action('request',role='family',body=dict(id='tunnel-note',kind='note',title='편의점 앞에 있어요',client='external-test'));self.assertEqual(200,code)
  self.assertEqual('편의점 앞에 있어요',self.relay.call(self.sid,self.tokens['car'],'sync',{})['requests'][0]['title'])
 def test_owner_invites_use_https_but_lan_links_stay_the_same(self):
  status,data=self.action('status',role='owner');self.assertEqual(200,status)
  self.assertTrue(data['invites']['family'].startswith(self.external.external_origin+'/join#'))
  status,data=self.action('status',role='owner',external=False);self.assertEqual(200,status);self.assertTrue(data['invites']['family'].startswith(self.relay.public_url))
 def test_shutdown_gateway_does_not_revoke_lan(self):
  self.external.external_enabled=False
  self.assertEqual(503,self.action('status',role='family')[0]);self.assertEqual(200,self.action('status',role='family',external=False)[0])
 def test_expired_session_and_revoked_role_still_fail(self):
  self.relay.sessions[self.sid]['permissions']['family']=False;self.assertEqual(403,self.action('status',role='family')[0])
  self.relay.sessions[self.sid]['expires']=0;self.assertEqual(410,self.action('status',role='owner')[0])
 def test_qr_changes_origin_only_and_hides_on_disconnect(self):
  state=dict(active=True,url=self.external.external_origin);links=self.session['links']
  result=external_links(links,state);self.assertEqual(links['family'].split('#')[1],result['family'].split('#')[1]);self.assertEqual({'owner','family'},set(result))
  self.assertEqual({},external_links(links,state,False));self.assertEqual({},external_links(links,dict(active=False,url=state['url'])))
  self.assertEqual({},external_links(links,dict(active=True,url='https://test.trycloudflare.com.evil.test')))
  self.assertTrue(qr_image(result['family']).startswith(b'\x89PNG'))

if __name__=='__main__':unittest.main()

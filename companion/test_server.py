from permission_test_fixture import prepared_device
import unittest
import threading
import http.client
from server import Relay, ApiError, Server, Handler

class RelayTest(unittest.TestCase):
    def setUp(self):
        self.now = 100
        self.relay = Relay(clock=lambda: self.now)
        self.session = self.relay.create()
        self.sid = self.session['id']
        self.tokens = self.relay.sessions[self.sid]['tokens'].copy()
    def call(self, role, action, **body):
        if role=='family' and action=='request':prepared_device(self.relay,self.sid,self.tokens)
        if role=='car' and action=='sync' and getattr(self.relay,'fixture_paired',False):body.update(ignitionOn=True,ignitionEpoch='fixture-cycle')
        return self.relay.call(self.sid, self.tokens[role], action, body)
    def request(self, **overrides):
        return self.call('family', 'request', **dict(dict(id='r1', client='f1', kind='destination', title='서울', latitude=37.5, longitude=127), **overrides))
    def test_permissions_and_validation(self):
        with self.assertRaises(ApiError): self.call('guest','request',id='x',client='g',kind='destination',title='서울',latitude=37,longitude=127)
        with self.assertRaises(ApiError): self.call('family','sync',running=True)
        for bad in (float('nan'),float('inf'),91,True,None):
            with self.assertRaises(ApiError): self.request(latitude=bad)
    def test_idempotency_and_claim_prevent_double_navigation(self):
        self.request(); self.request()
        self.assertEqual(1,len(self.call('car','sync')['requests']))
        self.call('car','claim',id='r1')
        with self.assertRaises(ApiError): self.call('car','claim',id='r1')
        self.call('car','result',id='r1',status='confirmed',message='확인')
        self.call('car','result',id='r1',status='confirmed',message='확인')
    def test_uncertain_execution_is_never_replayed(self):
        self.request();self.call('car','claim',id='r1');self.now+=36
        r=self.call('car','sync')['requests'][0]
        self.assertEqual('needs_review',r['status'])
        with self.assertRaises(ApiError): self.call('car','claim',id='r1')
    def test_heartbeat_and_alert_escalation(self):
        self.assertTrue(self.call('family','status')['stale'])
        self.call('car','sync',temperature=31,alerts=[dict(code='temp',title='온도 확인',level=2)])
        self.assertFalse(self.call('family','status')['stale'])
        self.now+=61
        status=self.call('observer','status')
        self.assertTrue(status['stale']);self.assertEqual({},status['state'])
        self.assertTrue(status['alerts'][0]['escalated'])
        self.call('observer','ack',code='temp')
        self.assertFalse(self.call('family','status')['alerts'][0]['escalated'])
    def test_vote_once_per_browser_and_family_only(self):
        self.request()
        for _ in range(3): self.call('family','vote',id='r1',client='f1')
        self.assertEqual(1,self.call('family','status')['requests'][0]['votes'])
        with self.assertRaises(ApiError): self.call('guest','vote',id='r1',client='g')
        self.assertEqual([],self.call('guest','status')['requests'])
        self.assertEqual([],self.call('observer','status')['requests'])
    def test_end_and_expiration_revoke_all_roles(self):
        self.call('car','end')
        with self.assertRaises(ApiError): self.call('family','status')
        s=self.relay.create();self.now+=7201
        self.relay.sweep();self.assertNotIn(s['id'],self.relay.sessions)
        with self.assertRaises(ApiError):self.relay.call(s['id'],s['token'],'sync',{})
    def test_bounded_inbox(self):
        for i in range(20):
            self.now+=2;self.call('car','sync');self.request(id=f'r{i}')
        self.now+=2
        with self.assertRaises(ApiError):self.request(id='r21')

class HttpPolicyTest(unittest.TestCase):
    def test_http_origin_host_payload_and_auth_boundaries(self):
        server=Server(('127.0.0.1',0),Handler)
        server.relay=Relay(f'http://127.0.0.1:{server.server_port}')
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def request(path='/api/sessions',data=b'{}',headers=None):
            connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=3)
            try:
                connection.request('POST',path,data,{'Content-Type':'application/json',**(headers or {})})
                response=connection.getresponse();response.read();return response.status
            finally:connection.close()
        try:
            self.assertEqual(200,request())
            self.assertEqual(403,request(headers={'Origin':'https://untrusted.example'}))
            self.assertEqual(403,request(headers={'Host':'untrusted.example'}))
            self.assertEqual(400,request(data=b'{"x":NaN}'))
            self.assertEqual(415,request(headers={'Content-Type':'text/plain'}))
            self.assertEqual(413,request(data=b'x'*16385))
            session=server.relay.create()
            self.assertEqual(403,request('/api/sessions/'+session['id']+'/status',headers={'Authorization':'Bearer invalid'}))
        finally:server.shutdown();server.server_close();thread.join(timeout=3)

if __name__=='__main__':unittest.main()

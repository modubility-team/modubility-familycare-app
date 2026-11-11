from permission_test_fixture import prepared_device
import base64
import unittest
from server import Relay, ApiError

class RolesAndMemoriesTest(unittest.TestCase):
    def setUp(self):
        self.now=100
        self.relay=Relay(clock=lambda:self.now)
        self.session=self.relay.create();self.sid=self.session['id']
        self.tokens=self.relay.sessions[self.sid]['tokens'].copy()
    def call(self, principal, action, **body):
        if principal=='family' and action=='request':prepared_device(self.relay,self.sid,self.tokens)
        if principal=='car' and action=='sync' and getattr(self.relay,'fixture_paired',False):body.update(ignitionOn=True,ignitionEpoch='fixture-cycle')
        return self.relay.call(self.sid,self.tokens[principal],action,body)
    def climate(self,role='family',**changes):
        args=dict(id='c1',client='phone',kind='climate',title='온도 23도',target=23.0)
        args.update(changes)
        return self.call(role,'request',**args)
    def supported(self):
        self.call('car','sync',climateSupported=True,parked=True,climateTarget=22.0)
    def test_passenger_climate_needs_vehicle_claim_and_verified_result(self):
        self.supported();r=self.climate()
        self.assertEqual('pending',r['status']);self.assertEqual('family',r['originRole'])
        with self.assertRaises(ApiError):self.call('family','claim',id='c1')
        with self.assertRaises(ApiError):self.call('family','result',id='c1',status='confirmed')
        with self.assertRaises(ApiError):self.climate('guest',id='bad')
        self.call('car','claim',id='c1');self.call('car','result',id='c1',status='confirmed',message='설정값 검증')
    def test_temperature_bounds_freshness_and_idempotency(self):
        self.supported()
        for value in (float('nan'),True,17,29,23.2):
            with self.assertRaises(ApiError):self.climate(target=value)
        self.climate();self.climate()
        with self.assertRaises(ApiError):self.climate(target=24)
        self.now+=61
        with self.assertRaises(ApiError):self.call('car','claim',id='c1')
        with self.assertRaises(ApiError):self.climate(id='stale')
    def test_owner_and_passenger_tokens_cannot_escalate(self):
        self.supported()
        with self.assertRaises(ApiError):self.call('family','permissions',role='guest',enabled=False)
        self.assertNotIn('owner',self.call('owner','status')['invites'])
        self.assertEqual({},self.call('family','status')['invites'])
        self.climate(originRole='owner')
        self.assertEqual('family',self.call('car','sync')['requests'][0]['originRole'])
    def test_revoke_rotates_link_and_rejects_pending_commands(self):
        self.supported();self.climate();old=self.tokens['family']
        self.call('owner','permissions',role='family',enabled=False)
        with self.assertRaises(ApiError):self.relay.call(self.sid,old,'status',{})
        self.assertEqual('rejected',self.call('car','sync')['requests'][0]['status'])
        self.call('owner','permissions',role='family',enabled=True)
        with self.assertRaises(ApiError):self.relay.call(self.sid,old,'status',{})
    def test_owner_requires_parked_vehicle(self):
        self.call('car','sync',climateSupported=True,parked=False)
        with self.assertRaises(ApiError):self.climate('owner')
    def test_photo_access_metadata_payload_and_cleanup(self):
        image=base64.b64encode(b'\xff\xd8test\xff\xd9').decode()
        photo=dict(id='p1',client='phone',title='여행',captured='2026-10-27 10:00',latitude=37.48,longitude=127.03,image=image,thumbnail=image)
        with self.assertRaises(ApiError):self.call('guest','memories',**photo)
        self.call('family','memories',**photo)
        metadata=self.call('car','sync')['memories'][0]
        self.assertNotIn('image',metadata);self.assertIn('thumbnail',metadata)
        self.assertEqual(image,self.call('owner','memory',id='p1')['image'])
        with self.assertRaises(ApiError):self.call('observer','memory',id='p1')
        with self.assertRaises(ApiError):self.call('family','delete-memory',id='p1',client='different')
        with self.assertRaises(ApiError):self.call('family','memories',**dict(photo,latitude=99))
        with self.assertRaises(ApiError):self.call('family','memories',**dict(photo,image='not base64'))
        self.call('owner','delete-memory',id='p1')
        self.assertEqual([],self.call('family','status')['memories'])

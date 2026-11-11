from permission_test_fixture import prepared_device
import unittest
from server import Relay,ApiError

class CareFeaturesTest(unittest.TestCase):
    def setUp(self):
        self.now=100;self.r=Relay(clock=lambda:self.now);s=self.r.create();self.sid=s['id'];self.tokens=self.r.sessions[self.sid]['tokens'].copy()
        self.call('car','sync',parked=True,profile={'members':['CHILD'],'childBand':0})
    def call(self,principal,action,**body):
        if principal=='family' and action=='request':prepared_device(self.r,self.sid,self.tokens)
        if principal=='car' and action=='sync' and getattr(self.r,'fixture_paired',False):body.update(ignitionOn=True,ignitionEpoch='fixture-cycle')
        return self.r.call(self.sid,self.tokens[principal],action,body)
    def request(self,role='family',**kw):
        body=dict(id='timer-1',client='p',kind='timer',title='타이머',options={'action':'start','minutes':5});body.update(kw)
        return self.call(role,'request',**body)
    def test_timer_requires_driver_claim_and_exact_idempotency(self):
        a=self.request();self.assertEqual('pending',a['status']);self.request()
        with self.assertRaises(ApiError):self.request(options={'action':'start','minutes':10})
        with self.assertRaises(ApiError):self.call('family','claim',id='timer-1')
        self.call('car','claim',id='timer-1');self.call('car','result',id='timer-1',status='confirmed')
    def test_guest_and_invalid_options_are_rejected(self):
        for role,options in [('guest',{'action':'start','minutes':5}),('family',{'action':'start','minutes':True}),('family',{'action':'start','minutes':31})]:
            with self.assertRaises(ApiError):self.request(role,options=options)
    def test_profile_is_private_to_owner_and_family(self):
        self.assertNotIn('profile',self.call('guest','status')['state'])
        self.assertNotIn('profile',self.call('observer','status')['state'])
        self.assertIn('profile',self.call('family','status')['state'])
        self.request(kind='profile',options={'members':['PET','CHILD'],'childBand':0})
        with self.assertRaises(ApiError):self.request(id='bad',kind='profile',options={'members':['ADMIN'],'childBand':0})
    def test_alert_resolution_retains_acknowledged_history(self):
        self.call('car','sync',alerts=[dict(code='temperature',title='온도 확인',level=2)])
        self.call('family','ack',code='temperature')
        self.call('car','sync',alerts=[])
        history=self.call('family','status')['alertHistory']
        self.assertEqual(1,len(history));self.assertTrue(history[0]['acknowledged']);self.assertTrue(history[0]['resolved'])
    def test_revocation_also_cancels_pending_timer(self):
        self.call('family','subscribe',client='retired',name='가족')
        self.request();self.call('owner','permissions',role='family',enabled=False)
        self.assertEqual('rejected',self.call('car','sync')['requests'][0]['status'])
        self.assertEqual([],self.call('owner','status')['recipients'])
    def test_location_and_route_are_private_and_can_be_withdrawn(self):
        self.call('car','sync',journey={'id':'trip','points':[{'lat':37,'lon':127}]},routeSummary={'destination':'private'})
        for role in ('guest','observer'):
            self.assertNotIn('journey',self.call(role,'status')['state']);self.assertNotIn('routeSummary',self.call(role,'status')['state'])
        self.assertIn('journey',self.call('family','status')['state'])
        self.call('car','sync',journey=None,routeSummary=None)
        self.assertIsNone(self.call('family','status')['state']['journey'])
    def test_hold_approval_limits_expiry_and_guest_rejection(self):
        valid={'action':'start','target':23,'minutes':5}
        with self.assertRaises(ApiError):self.request('guest',kind='hold',options=valid)
        with self.assertRaises(ApiError):self.request(kind='hold',options=dict(valid,minutes=11))
        self.request(kind='hold',options=valid);self.now+=61
        with self.assertRaises(ApiError):self.call('car','claim',id='timer-1')
    def test_only_owner_can_manage_notification_recipients(self):
        self.call('observer','subscribe',client='backup',name='보조 보호자')
        self.assertEqual([],self.call('family','status')['recipients'])
        with self.assertRaises(ApiError):self.call('family','recipient',client='backup',arrival=False,care=True)
        self.call('owner','recipient',client='backup',arrival=False,care=True)
        p=self.call('observer','status',client='backup')['notificationPreferences'];self.assertFalse(p['arrival']);self.assertTrue(p['care'])
    def test_unsupported_seat_cannot_be_written(self):
        self.call('car','sync',climateSupported=True,climateAreas=[{'id':'ROW_1_RIGHT','target':22,'min':20,'max':26}])
        with self.assertRaises(ApiError):self.request(kind='climate',target=22,options={'area':'ROW_2_LEFT'})
        with self.assertRaises(ApiError):self.request(kind='climate',target=27,options={'area':'ROW_1_RIGHT'})
        next(iter(self.r.sessions[self.sid]['devices'].values()))['seat']='ROW_1_RIGHT'
        self.assertEqual('pending',self.request(kind='climate',target=23,options={'area':'ROW_1_RIGHT'})['status'])
    def test_climate_power_is_typed_supported_and_approved(self):
        self.call('car','sync',climateSupported=True,climatePowerSupported=False,climatePower=False,parked=True)
        with self.assertRaises(ApiError):self.request(kind='climate',target=23,options={'power':True})
        self.call('car','sync',climateSupported=True,climatePowerSupported=True,climatePower=False,parked=True)
        for invalid in (1,'true',None):
            with self.assertRaises(ApiError):self.request(kind='climate',target=23,options={'power':invalid})
        with self.assertRaises(ApiError):self.request('guest',kind='climate',target=23,options={'power':True})
        self.assertEqual('pending',self.request(kind='climate',target=23,options={'power':True})['status'])
        self.assertFalse(self.call('family','status')['state']['climatePower'])
        with self.assertRaises(ApiError):self.call('family','claim',id='timer-1')
        self.call('car','claim',id='timer-1')
        self.call('car','result',id='timer-1',status='confirmed')
        self.assertEqual('confirmed',self.call('family','status')['requests'][0]['status'])

if __name__=='__main__':unittest.main()

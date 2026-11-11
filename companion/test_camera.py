import unittest
from unittest.mock import patch
from camera import configuration, fetch_frame, CameraError
from server import Relay, ApiError


class CameraTests(unittest.TestCase):
    def setUp(self):
        self.now=100
        self.relay=Relay(clock=lambda:self.now,camera_fetch=lambda _:dict(image='/9j/2Q==',capturedAt=123))
        self.session=self.relay.create();self.sid=self.session['id']
        self.tokens=self.relay.sessions[self.sid]['tokens'].copy()
    def call(self,who,action,**body):return self.relay.call(self.sid,self.tokens[who],action,body)
    def configure(self):return self.call('owner','camera-configure',enabled=True,url='http://192.168.0.10:8080',consent=True,username='user',password='secret')
    def denied(self,status,role,action,**body):
        with self.assertRaises(ApiError) as error:self.call(role,action,**body)
        self.assertEqual(status,error.exception.status)
    def test_owner_config_family_view_and_no_password_disclosure(self):
        self.configure()
        status=self.call('family','camera-status')
        self.assertTrue(status['configured']);self.assertEqual('',status['url'])
        self.assertNotIn('secret',str(self.call('owner','camera-status')))
        self.assertEqual(123,self.call('family','camera-frame')['capturedAt'])
    def test_guests_and_observers_cannot_read_or_change_camera(self):
        self.configure()
        for role in ('guest','observer'):
            for action in ('camera-status','camera-frame','camera-configure','camera-check'):self.denied(403,role,action)
        self.denied(403,'family','camera-configure',enabled=False)
    def test_disconnect_removes_access(self):
        self.configure();self.call('owner','camera-configure',enabled=False)
        self.denied(409,'family','camera-frame')
        self.assertFalse(self.call('family','camera-status')['configured'])
    def test_revocation_during_frame_wins(self):
        self.configure()
        def slow(_):
            self.call('owner','permissions',role='family',enabled=False)
            return dict(image='secret')
        self.relay.camera_fetch=slow
        self.denied(403,'family','camera-frame')
    def test_reconfiguration_during_frame_wins(self):
        self.configure()
        def slow(_):self.call('owner','camera-configure',enabled=False);return dict(image='old')
        self.relay.camera_fetch=slow
        self.denied(409,'family','camera-frame')
    def test_expiry_during_frame_wins(self):
        self.configure()
        def slow(_):self.now+=7201;return dict(image='old')
        self.relay.camera_fetch=slow
        self.denied(410,'family','camera-frame')
    def test_vehicle_requires_fresh_parked_telemetry_before_and_after(self):
        self.configure();self.denied(409,'car','camera-frame')
        self.call('car','sync',parked=True)
        self.assertIn('image',self.call('car','camera-frame'))
        self.now+=1
        def slow(_):self.call('car','sync',parked=False);return dict(image='old')
        self.relay.camera_fetch=slow
        self.denied(409,'car','camera-frame')
    def test_frame_rate_limit_and_check_timestamp(self):
        self.configure();self.call('family','camera-frame');self.denied(429,'family','camera-frame')
        self.now+=1;self.assertIn('image',self.call('family','camera-frame'))
        checked=self.call('family','camera-check')['checkedAt']
        self.assertEqual(checked,self.call('owner','camera-status')['checkedAt'])
    def test_only_explicit_local_numeric_fixed_path_configuration(self):
        for url in ('http://127.0.0.1:8080','http://localhost:8080','http://169.254.169.254:8080','http://8.8.8.8:8080','http://192.168.0.1:22','http://192.168.0.1:8080/admin','http://u:p@192.168.0.1:8080','http://192.168.0.1:8080/?x=1','http://192.168.0.1:8080/#x','http://[::1]:8080','file:///tmp/a'):
            with self.subTest(url=url),self.assertRaises(CameraError):configuration(dict(url=url,consent=True))
        for url in ('http://192.168.0.10:8080','http://10.0.0.2:9090/shot.jpg','http://172.16.1.1:8080/'):
            self.assertTrue(configuration(dict(url=url,consent=True))['url'].endswith('/shot.jpg'))
        with self.assertRaises(CameraError):configuration(dict(url='http://192.168.0.1:8080'))
    def test_invalid_auth_is_rejected(self):
        for auth in (dict(username='x'),dict(username='a:b',password='x'),dict(username='x',password='\n')):
            with self.assertRaises(CameraError):configuration(dict(url='http://192.168.0.10:8080',consent=True,**auth))
    def test_bare_ip_and_changed_address(self):
        for value,expected in [(' 192.168.219.104:8080 ','http://192.168.219.104:8080/shot.jpg'),('10.0.0.20','http://10.0.0.20:8080/shot.jpg')]:
            self.assertEqual(expected,configuration(dict(url=value,consent=True))['url'])
        self.configure()
        self.call('owner','camera-configure',enabled=True,url='192.168.219.104:8080',consent=True)
        self.assertEqual('http://192.168.219.104:8080/shot.jpg',self.call('owner','camera-status')['url'])
        for value in ('127.0.0.1:8080','192.168.0.1:0','192.168.0.1:8080/admin','localhost:8080','//192.168.0.1:8080'):
            with self.subTest(value=value),self.assertRaises(CameraError):configuration(dict(url=value,consent=True))
    def test_transport_rejects_oversize_non_jpeg_and_redirect(self):
        class Reply:
            status=200
            def __init__(self,data,kind):self.data=data;self.headers=self;self.kind=kind
            def get_content_type(self):return self.kind
            def read(self,n):return self.data[:n]
            def __enter__(self):return self
            def __exit__(self,*_):pass
        config=configuration(dict(url='http://192.168.0.10:8080',consent=True))
        for data,kind in ((b'<html>','text/html'),(b'\xff\xd8'+b'x'*1_000_000+b'\xff\xd9','image/jpeg'),(b'no image','image/jpeg')):
            with patch('camera.build_opener') as opener:
                opener.return_value.open.return_value=Reply(data,kind)
                with self.assertRaises(CameraError):fetch_frame(config)
        from urllib.error import HTTPError
        with patch('camera.build_opener') as opener:
            opener.return_value.open.side_effect=HTTPError(config['url'],302,'redirect',{},None)
            with self.assertRaises(CameraError):fetch_frame(config)

if __name__=='__main__':unittest.main()

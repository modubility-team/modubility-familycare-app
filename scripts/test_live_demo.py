import unittest
from live_demo import connection_health, selected_point, at_position, Demo, cumulative, along
import threading,time


class HealthTest(unittest.TestCase):
    def test_old_state_is_not_live_even_when_last_poll_succeeded(self):
        self.assertFalse(connection_health(dict(at=1000, sharing=True), 0, 9000)['fresh'])

    def test_future_timestamp_and_missing_state_are_not_live(self):
        self.assertFalse(connection_health(dict(at=10000), 0, 9000)['fresh'])
        self.assertFalse(connection_health({}, 0, 9000)['fresh'])

    def test_vehicle_gps_and_family_are_independent(self):
        result=connection_health(dict(at=8000,gpsReady=False,sharing=False),0,9000)
        self.assertTrue(result['fresh'])
        self.assertEqual('신호 대기',result['gps'])
        self.assertEqual('새 QR 연결 필요',result['family'])


class LocationTest(unittest.TestCase):
    def test_invalid_coordinates(self):
        for value in (None,[],[1],[1,2,3],[True,2],[float('nan'),2],[1,float('inf')],[181,10],[128,91]):
            with self.subTest(value=value),self.assertRaises(ValueError):selected_point(value)
        self.assertEqual([129.1,35.2],selected_point([129.1,35.2]))
    def test_old_gps_cannot_confirm_new_position(self):
        self.assertFalse(at_position(dict(gpsReady=True,position=[127.03,37.48]),[129.1,35.2]))
        self.assertFalse(at_position(dict(gpsReady=False,position=[129.1,35.2]),[129.1,35.2]))
        self.assertTrue(at_position(dict(gpsReady=True,position=[129.1,35.2]),[129.1,35.2]))
    def demo(self):
        d=object.__new__(Demo);d.action_lock=threading.Lock();d.lock=threading.RLock();d.state={'parked':True,'gpsReady':True,'position':[129.1,35.2]};d.point=[129.1,35.2];d.route=[[1,2],[3,4]];d.lengths=[0,100];d.progress=50
        self.calls=[];d.park=lambda:self.calls.append('park');d.wait_parked=lambda:self.calls.append('parked');d.app=lambda action,**kwargs:self.calls.append((action,kwargs));d.wait_for=lambda predicate:self.assertTrue(predicate(d.state))
        return d
    def test_arbitrary_destination_reaches_production_navigation_command(self):
        d=self.demo();d.act('plan',dict(point=[129.16,35.16],name='부산에서 고른 목적지'))
        self.assertEqual(('plan',dict(point=[129.16,35.16],name='부산에서 고른 목적지')),self.calls[-1])
    def test_relocation_cancels_old_route_and_clears_progress(self):
        d=self.demo();d.act('position',dict(point=[129.1,35.2]))
        self.assertEqual(['park','parked',('relocate',{})],self.calls)
        self.assertEqual([],d.route);self.assertEqual([],d.lengths);self.assertEqual(0,d.progress);self.assertEqual([129.1,35.2],d.origin)
    def test_invalid_destination_does_not_modify_running_vehicle(self):
        d=self.demo()
        with self.assertRaises(ValueError):d.act('plan',dict(point=[float('nan'),2]))
        self.assertEqual([],self.calls);self.assertFalse(d.busy)
    def test_replay_geometry_stops_at_selected_end(self):
        points=[[129.1,35.2],[129.1001,35.2001],[129.1002,35.2002]];lengths=cumulative(points)
        self.assertEqual(points[-1],along(points,lengths,lengths[-1]+500))
        self.assertEqual(points[0],along(points,lengths,0))


class RecoveryTest(unittest.TestCase):
    def demo(self,fresh=False):
        d=object.__new__(Demo);d.action_lock=threading.Lock();d.lock=threading.RLock();d.errors=0
        d.state={'at':time.time()*1000 if fresh else 1,'parked':True,'gpsReady':True}
        d.point=[129.1,35.2];d.route=[[1,2],[3,4]];d.lengths=[0,100];d.progress=50
        self.calls=[];d.park=lambda:self.calls.append('park')
        def launch():
            self.calls.append('launch');d.state={'at':time.time()*1000,'parked':True,'gpsReady':True}
        def app(action):
            self.calls.append(action);d.state.update(sharing=True,links={'family':'test'})
        d.launch_session=launch;d.app=app
        d.wait_for=lambda predicate,timeout=20:self.assertTrue(predicate(d.state))
        return d
    def test_expired_session_recovers_in_park_before_preparing_new_sharing(self):
        d=self.demo();d.act('reconnect',{})
        self.assertEqual(['park','launch','prepare'],self.calls)
        self.assertEqual([129.1,35.2],d.origin);self.assertEqual([],d.route);self.assertEqual(0,d.progress)
        self.assertFalse(d.busy)
    def test_live_app_with_expired_sharing_does_not_need_process_restart(self):
        d=self.demo(True);d.act('reconnect',{})
        self.assertEqual(['park','prepare'],self.calls)
    def test_recovery_failure_does_not_claim_success_or_leave_busy(self):
        d=self.demo()
        def failed():raise RuntimeError('emulator unavailable')
        d.launch_session=failed
        with self.assertRaises(RuntimeError):d.act('reconnect',{})
        self.assertFalse(d.busy);self.assertNotIn('완료',d.message)

if __name__=='__main__':unittest.main()

class NavigationSyncTest(unittest.TestCase):
 def demo(self):
  d=object.__new__(Demo);d.lock=threading.RLock();d.navigation_start_id='';d.navigation_route_id='';d.route=[];d.progress=0
  return d
 def snapshot(self,**kw):return dict(dict(active=True,navigationStartId='start-one',navigationRouteId='route-one',destinationPoint=[127.01,37.01],destination='선택한 장소',route=[[127,37],[127.01,37.01]],routeProgress=40),**kw)
 def test_receives_after_start_but_not_preview(self):
  d=self.demo();d.receive_navigation(self.snapshot(active=False));self.assertEqual([],d.route)
  d.receive_navigation(self.snapshot());self.assertEqual(2,len(d.route));self.assertEqual(40,d.progress)
 def test_poll_does_not_reset_gps_and_reroute_updates_geometry(self):
  d=self.demo();d.receive_navigation(self.snapshot());d.progress=100;d.receive_navigation(self.snapshot());self.assertEqual(100,d.progress)
  d.receive_navigation(self.snapshot(navigationRouteId='rerouted',routeProgress=55));self.assertEqual(55,d.progress)
 def test_invalid_and_new_destination(self):
  d=self.demo();d.receive_navigation(self.snapshot(destinationPoint=[float('nan'),37]));self.assertEqual([],d.route)
  d.receive_navigation(self.snapshot());d.receive_navigation(self.snapshot(navigationStartId='next',routeProgress=0));self.assertEqual('next',d.navigation_start_id);self.assertEqual(0,d.progress)

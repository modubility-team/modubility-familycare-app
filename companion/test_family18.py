import unittest,copy,json
from server import Relay,ApiError
import seat_permissions,delegation,family_routes
from maps import MapError

class Family18Test(unittest.TestCase):
 def setUp(self):
  self.now=100;self.relay=Relay(clock=lambda:self.now);self.sid=self.relay.create()['id'];self.s=self.relay.sessions[self.sid];self.tokens=dict(self.s['tokens']);self.n=0
  self.fan='hvac.fan:ROW_1_LEFT+ROW_1_RIGHT';self.scope='control:'+self.fan
  self.state=dict(authorityActive=True,authorityEpoch='one',authorityMode='connection',parked=True,climateSupported=True,climatePowerSupported=True,climateAreas=[dict(id=s,min=18,max=28,target=22) for s in ('ROW_1_LEFT','ROW_1_RIGHT')],controls=[dict(id=self.fan,label='앞좌석 공용 풍량',min=1,max=8,step=1,available=True,boolean=False,ownerOnly=False)])
  self.sync()
  for who in ('a','b'):
   d=self.call('family','pair-device');self.tokens[who]=d['token'];setattr(self,who,d['grant']['deviceId'])
 def call(self,who,action,**body):return self.relay.call(self.sid,self.tokens[who],action,body)
 def sync(self):self.call('car','sync',**self.state)
 def req(self,who='a',kind='climate',**body):
  self.now+=2;self.sync();self.n+=1
  return self.call(who,'request',id=str(self.n),client='not-authority',kind=kind,title='기능 시험',**body)
 def approve(self,who='a',seat='ROW_1_RIGHT',scopes=None,limits=None,approval=None):
  o=dict(name=who,seat=seat,scopes=scopes or ['climate',self.scope,'care'],limits=limits or dict(climate=dict(min=21,max=25),**{self.scope:dict(min=2,max=6)}))
  r=self.req(who,'permission',options=o);self.call('car','claim',id=r['id']);return self.call('car','result',id=r['id'],status='confirmed',**(dict(approval=approval) if approval else {}))
 def done(self,r):self.call('car','claim',id=r['id']);return self.call('car','result',id=r['id'],status='confirmed')
 def fanreq(self,who,value):return self.req(who,'control',options=dict(control=self.fan,value=value))
 def test_own_seat_and_bounds_enforced_on_server_and_claim(self):
  self.approve()
  for target,area in [(26,'ROW_1_RIGHT'),(23,'ROW_1_LEFT')]:
   with self.assertRaises(ApiError):self.req(target=target,options=dict(area=area))
  r=self.req(target=24,options=dict(area='ROW_1_RIGHT'));self.s['devices'][self.a]['limits']['climate']['max']=23
  with self.assertRaises(ApiError):self.call('car','claim',id=r['id'])
 def test_rear_temperature_and_driver_other_seat_scope_rejected(self):
  with self.assertRaises(ApiError):self.approve(seat='ROW_2_LEFT')
  self.assertNotIn('climate',[c['id'] for c in seat_permissions.catalog(self.state,'ROW_2_LEFT')])
 def test_driver_can_narrow_not_widen_requested_limits(self):
  approval=dict(name='a',seat='ROW_1_RIGHT',scopes=['climate'],limits=dict(climate=dict(min=22,max=24)))
  self.assertEqual('confirmed',self.approve(approval=approval)['status']);self.assertEqual(24,self.s['devices'][self.a]['limits']['climate']['max'])
  approval['limits']['climate']['min']=18
  self.assertEqual('rejected',self.approve(approval=approval)['status'])
 def test_nan_bad_steps_and_ungranted_power_rejected(self):
  for v in [float('nan'),21.1,True]:
   with self.assertRaises(ApiError):self.approve(limits=dict(climate=dict(min=v,max=25),**{self.scope:dict(min=2,max=6)}))
  self.approve()
  with self.assertRaises(ApiError):self.req(target=23,options=dict(area='ROW_1_RIGHT',power=True))
 def test_shared_conflict_waits_for_both_then_executes_only_proposed_value(self):
  self.approve();self.approve('b','ROW_1_LEFT');first=self.fanreq('a',2);self.done(first)
  r=self.fanreq('b',6);self.assertEqual('awaiting_agreement',r['status']);self.assertEqual(4,r['agreement']['proposed'])
  with self.assertRaises(ApiError):self.call('car','claim',id=r['id'])
  self.call('a','agree',id=r['id'],accept=True);self.assertEqual('awaiting_agreement',self.s['requests'][r['id']]['status'])
  self.call('b','agree',id=r['id'],accept=True);self.assertEqual(4,self.s['requests'][r['id']]['options']['value']);self.done(r)
  retry=self.call('b','request',id=r['id'],client='not-authority',kind='control',title='기능 시험',options=dict(control=self.fan,value=6));self.assertEqual('confirmed',retry['status'])
 def test_fan_power_off_returns_actionable_reason(self):
  self.approve();self.state['climatePower']=False
  with self.assertRaises(ApiError) as cm:self.fanreq('a',3)
  self.assertIn('전원',cm.exception.message)
 def test_shared_write_race_does_not_bypass_consent(self):
  self.approve();self.approve('b','ROW_1_LEFT');first=self.fanreq('a',2)
  with self.assertRaises(ApiError):self.fanreq('b',6)
  self.done(first);self.assertEqual('awaiting_agreement',self.fanreq('b',6)['status'])
 def test_shared_decline_revoke_and_outsider(self):
  self.approve();self.approve('b','ROW_1_LEFT');self.done(self.fanreq('a',2));r=self.fanreq('b',6)
  with self.assertRaises(ApiError):self.call('owner','agree',id=r['id'],accept=True)
  self.call('a','agree',id=r['id'],accept=False);self.assertEqual('rejected',self.s['requests'][r['id']]['status'])
  r=self.fanreq('b',6);self.call('owner','revoke-grant',deviceId=self.a);self.assertEqual('rejected',self.s['requests'][r['id']]['status'])
 def test_shared_nonoverlap_and_expiry(self):
  self.approve();self.approve('b','ROW_1_LEFT');self.s['devices'][self.a]['limits'][self.scope]=dict(min=1,max=2);self.s['devices'][self.b]['limits'][self.scope]=dict(min=5,max=6);self.done(self.fanreq('a',2))
  with self.assertRaises(ApiError):self.fanreq('b',6)
  self.s['devices'][self.b]['limits'][self.scope]=dict(min=2,max=6);r=self.fanreq('b',6);self.now+=91;self.call('a','status');self.assertEqual('rejected',self.s['requests'][r['id']]['status'])
 def startcare(self):
  self.approve();self.call('a','status');r=self.req(kind='care',options=dict(action='start',mode='sleep',caregiver=self.a,minutes=15,temperature=23));self.done(r);return r
 def test_care_only_applies_after_result_and_scoped_temperature(self):
  self.approve();self.call('a','status')
  with self.assertRaises(ApiError):self.req(kind='care',options=dict(action='start',mode='sleep',caregiver=self.a,minutes=15,temperature=27))
  r=self.req(kind='care',options=dict(action='start',mode='sleep',caregiver=self.a,minutes=15,temperature=23));self.assertFalse(self.call('a','status')['situation']);self.done(r);self.assertEqual('sleep',self.call('a','status')['situation']['mode'])
 def test_care_routing_ack_fallback_and_urgent_safety_passthrough(self):
  self.startcare();r=self.req(kind='note');urgent=self.req(kind='note',options=dict(urgent=True));self.state['alerts']=[dict(code='hot',title='고온 확인',level=3)];self.sync()
  data=self.call('a','status');json.dumps(data);self.assertEqual([r['id']],data['careRoutedNoteIds']);self.assertEqual('hot',data['alerts'][0]['code'])
  with self.assertRaises(ApiError):self.call('b','care-ack',id=r['id'])
  self.call('a','care-ack',id=r['id']);self.assertEqual('confirmed',self.s['requests'][r['id']]['status'])
  r=self.req(kind='note');self.now+=16;self.s['heartbeat']=self.now;self.assertEqual([],self.call('car','sync',**self.state)['careRoutedNoteIds'])
  self.call('a','status');self.now+=15;self.s['heartbeat']=self.now;self.assertEqual([],self.call('car','sync',**self.state)['careRoutedNoteIds'])
 def test_care_result_idempotency_revoke_and_explicit_timer(self):
  r=self.startcare();due=self.s['situation']['due'];self.now+=2;self.call('car','result',id=r['id'],status='confirmed');self.assertEqual(due,self.s['situation']['due'])
  self.now=due;self.s['heartbeat']=self.now;self.sync();self.assertTrue(self.call('a','status')['situation']['restDue'])
  self.call('owner','revoke-grant',deviceId=self.a);self.assertEqual({},self.call('a','status')['situation'])
 def test_designated_rest_stop_becomes_a_waypoint_and_arrival_comes_from_the_car(self):
  stop=dict(name='덕평자연휴게소',latitude=37.2412,longitude=127.3988)
  self.approve();self.call('a','status')
  with self.assertRaises(ApiError):self.req(kind='care',options=dict(action='start',mode='sleep',caregiver=self.a,stop=dict(name='',latitude=37.2,longitude=127.3)))
  r=self.req(kind='care',options=dict(action='start',mode='sleep',caregiver=self.a,stop=stop));self.done(r)
  c=self.call('a','status')['situation'];self.assertEqual('덕평자연휴게소',c['stop']['name']);self.assertIsNone(c['remainingSeconds']);self.assertFalse(c['stopOnRoute']);self.assertFalse(c['restDue'])
  w=self.s['requests']['rest-'+r['id']];self.assertEqual(('waypoint','지정 휴게소 · 덕평자연휴게소','pending'),(w['kind'],w['title'],w['status']));self.assertFalse(w['delegated'])
  self.assertEqual(1,sum(x['kind']=='waypoint' for x in self.s['requests'].values()))
  self.state['restStop']=dict(latitude=37.2413,longitude=127.3989,onRoute=True,etaSeconds=1795.4,arrived=False);self.now+=2;self.sync()
  c=self.call('a','status')['situation'];self.assertTrue(c['stopOnRoute']);self.assertEqual(1795,c['stopEtaSeconds']);self.assertFalse(c['restDue'])
  self.state['restStop']=dict(latitude=37.5,longitude=127.0,onRoute=True,etaSeconds=60,arrived=True);self.now+=2;self.sync()
  self.assertFalse(self.call('a','status')['situation']['stopOnRoute'])
  self.state['restStop']=dict(latitude=37.2412,longitude=127.3988,onRoute=False,etaSeconds=None,arrived=True);self.now+=2;self.sync()
  self.assertTrue(self.call('a','status')['situation']['restDue'])
  rest=self.req(kind='care',options=dict(action='rest'));self.done(rest)
  c=self.call('a','status')['situation'];self.assertIsNone(c['stop']);self.assertEqual('덕평자연휴게소',c['restedAt']);self.assertFalse(c['restDue'])
 def test_rest_stop_from_a_member_allowed_to_change_the_route_is_delegated(self):
  self.approve(scopes=['climate',self.scope,'care','route']);self.call('a','status')
  r=self.req(kind='care',options=dict(action='start',mode='comfort',caregiver=self.a,stop=dict(name='화성휴게소',latitude=37.1,longitude=126.9)));self.done(r)
  w=self.s['requests']['rest-'+r['id']];self.assertTrue(w['delegated']);self.assertEqual('승인된 권한으로 차량에 전달 중',w['message'])
  self.assertTrue(any(x['id']=='rest-'+r['id'] for x in self.call('car','sync',**self.state)['requests']))
 def test_route_needs_require_paired_active_identity(self):
  with self.assertRaises(ApiError):self.call('a','route-needs',needs=['화장실'])
  self.approve();self.call('a','route-needs',needs=['방향 전환 적게','화장실']);self.assertEqual(['방향 전환 적게','화장실'],self.call('car','sync',**self.state)['routeNeeds'][0]['needs'])
  with self.assertRaises(ApiError):self.call('a','route-needs',needs=['invented'])
 def test_route_needs_event_tells_car_whether_to_reroute(self):
  self.approve();r=self.call('a','route-needs',needs=['방향 전환 적게']);self.assertFalse(r['auto'])
  e=self.call('car','sync',**self.state)['routeNeedsEvent'];self.assertFalse(e['auto']);self.assertEqual(['방향 전환 적게'],e['needs']);self.assertEqual(0,e['age'])
  self.approve('b','ROW_1_LEFT',scopes=['climate',self.scope,'care','route']);self.now+=3
  r=self.call('b','route-needs',needs=['빠른 도착','반려견 산책']);self.assertTrue(r['auto'])
  e2=self.call('car','sync',**self.state)['routeNeedsEvent'];self.assertTrue(e2['auto']);self.assertNotEqual(e['id'],e2['id']);self.assertEqual(['빠른 도착','반려견 산책'],e2['needs'])
  self.state['authorityEpoch']='two';self.now+=1;self.assertFalse(self.call('car','sync',**self.state)['routeNeedsEvent']['auto'])
 def test_car_compares_while_driving_only_to_reroute_by_family_criteria(self):
  self.relay.maps=RouteComparisonTest.Maps();self.state['parked']=False;self.now+=2;self.sync()
  body=dict(start=[127,37.5],end=[127.1,37.6],consent=True,provider='tmap')
  with self.assertRaises(ApiError) as cm:self.call('car','maps-compare',**body)
  self.assertEqual(409,cm.exception.status)
  self.now+=2;self.sync();self.assertTrue(self.call('car','maps-compare',auto=True,**body)['candidates'])
  self.now+=2;self.sync()
  with self.assertRaises(ApiError):self.call('a','maps-compare',auto=True,**body)

class RouteComparisonTest(unittest.TestCase):
 class Maps:
  def __init__(self,duplicate=False,fail=False):self.calls=[];self.duplicate=duplicate;self.fail=fail
  def directions(self,b):
   self.calls.append(b);o=b['routeOption']
   if self.fail and o==4:raise MapError(502,'일부 실패')
   v=bool(b.get('via'));n=0 if self.duplicate else o
   coords=[b['start'],[127+n*.001,37.51],b['end']]
   if v:coords.insert(1,b['via'])
   return dict(code='Ok',provider='TMAP',routes=[dict(geometry=dict(coordinates=coords),duration=100+n*10+int(v)*60,legs=[dict(steps=[dict(maneuver=dict(type='turn',modifier='left'))]*(5-n))])])
 def body(self):return dict(start=[127,37.5],end=[127.1,37.6])
 def test_real_metrics_dedup_and_candidate_labels(self):
  m=self.Maps(duplicate=True);r=family_routes.compare(m,self.body());self.assertEqual(3,len(m.calls));self.assertEqual(1,len(r['candidates']));self.assertEqual(2,len(r['candidates'][0]['labels']))
 def test_actual_detour_and_fewer_turns_sort(self):
  m=self.Maps();r=family_routes.compare(m,dict(self.body(),via=[127.05,37.55],stopName='선택한 곳',needs=['방향 전환 적게']));self.assertEqual(4,len(m.calls));self.assertEqual(1,r['candidates'][0]['turns']);via=next(c for c in r['candidates'] if c['via']);self.assertEqual(60,via['extraSeconds']);self.assertIn('시설',r['notice'])
 def test_partial_failure_keeps_successful_real_candidates(self):
  r=family_routes.compare(self.Maps(fail=True),self.body());self.assertEqual(2,len(r['candidates']));self.assertEqual(['일부 실패'],r['errors'])
 def test_invalid_points_no_provider_call(self):
  m=self.Maps()
  with self.assertRaises(MapError):family_routes.compare(m,dict(start=[float('nan'),37],end=[127,38]))
  self.assertEqual([],m.calls)
 def test_kept_stop_is_on_every_candidate(self):
  m=self.Maps();r=family_routes.compare(m,dict(self.body(),keep=[127.05,37.55],via=[127.02,37.52],stopName='무시'))
  self.assertEqual(3,len(m.calls));self.assertTrue(all(b['via']==[127.05,37.55] for b in m.calls))
  self.assertTrue(all(c['via']==[127.05,37.55] and not c['stop'] and not c['stopName'] for c in r['candidates']))
  self.assertIn('후보 중 가장 빠름',[l for c in r['candidates'] for l in c['labels']])

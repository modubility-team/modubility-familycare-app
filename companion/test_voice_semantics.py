import unittest,copy
import voice_semantics as voice
from server import Relay,ApiError

def candidate(id='one',title='양재시민의숲',kind='destination'):
 return dict(id=id,title=title,kind=kind,status='pending',latitude=37.49,longitude=127.036,options={})
def positive(_):return dict(intent='add_stop',score=.96,margin=.06)
class VoiceMeaningTest(unittest.TestCase):
 def resolve(self,text,items=None,**kw):return voice.interpret(text,items or [candidate()],classifier=positive,**kw)
 def test_single_reference_and_named_place(self):
  self.assertEqual('one',self.resolve('추천한 곳을 중간에 넣어줘')['targetId'])
  self.assertEqual('one',self.resolve('양재시민의숲 거쳐 가줘')['targetId'])
 def test_multiple_requires_choice_not_default_first(self):
  result=self.resolve('추천지를 경유해줘',[candidate(),candidate('two','서울숲')])
  self.assertEqual('clarify',result['intent']);self.assertNotIn('targetId',result)
 def test_numbered_follow_up_and_out_of_range(self):
  rows=[candidate(),candidate('two','서울숲')]
  self.assertEqual('two',self.resolve('두 번째',rows,choice_intent='add_stop')['targetId'])
  self.assertNotIn('targetId',self.resolve('다섯 번째',rows,choice_intent='add_stop'))
 def test_same_name_does_not_pick_arbitrarily(self):
  self.assertNotIn('targetId',self.resolve('양재시민의숲 들러줘',[candidate(),candidate('two')]))
 def test_known_name_precedes_selected_card(self):
  self.assertEqual('two',self.resolve('서울숲 들러줘',[candidate(),candidate('two','서울숲')],selected_id='one')['targetId'])
 def test_unknown_spoken_place_never_substitutes_only_candidate(self):
  self.assertNotIn('targetId',self.resolve('강남역을 들러 줘'))
  self.assertNotIn('targetId',self.resolve('추천한 해운대공원부터 들러줘'))
 def test_negation_reported_question_and_other_controls(self):
  for text in ['가지 마','추천지는 안 가도 돼','추천한 곳 들르지 말아줘','추천지를 경유해줘 아니 가지마','친구가 경유하라고 했어','추천지는 얼마나 걸려','추천지를 경유하면 몇 분 걸려','경유 요청 전부 승인해줘','추천지를 경유하고 창문 열어줘','만약 추천지 경유하면 좋을까']:
   with self.subTest(text=text):self.assertNotIn('targetId',self.resolve(text))
 def test_unrelated_request_not_a_route(self):
  self.assertNotIn('targetId',self.resolve('추천지 경유해줘',[candidate(kind='control')]))
 def test_completed_request_not_reused(self):
  r=candidate();r['status']='confirmed';self.assertNotIn('targetId',self.resolve('추천지 경유해줘',[r]))
 def test_low_confidence_does_not_act(self):
  r=voice.interpret('그곳에 가볼까',[candidate()],classifier=lambda _:dict(intent='add_stop',score=.91,margin=.001))
  self.assertNotIn('targetId',r)
 def test_pickup_uses_only_pickup(self):
  r=voice.interpret('동승자를 데리러 가자',[candidate()],classifier=lambda _:dict(intent='pickup',score=.96,margin=.05))
  self.assertNotIn('targetId',r)
class VoiceApiTest(unittest.TestCase):
 def setUp(self):
  self.now=100.;self.r=Relay(clock=lambda:self.now);s=self.r.create();self.sid=s['id'];self.s=self.r.sessions[self.sid];self.tokens=self.s['tokens'];self.r.voice_predict=positive
  self.r.call(self.sid,self.tokens['car'],'sync',dict(parked=False))
  self.s['requests']['one']=candidate();self.body=dict(text='추천지를 경유해줘',candidateIds=['one'])
 def call(self,role='car',**body):return self.r.call(self.sid,self.tokens[role],'voice-interpret',body or self.body)
 def test_only_authenticated_car_and_no_side_effect(self):
  for role in ['owner','family','guest','observer']:
   with self.assertRaises(ApiError):self.call(role)
  before=copy.deepcopy(self.s['requests']);self.assertEqual('one',self.call()['targetId']);self.assertEqual(before,self.s['requests'])
 def test_new_request_not_in_listening_snapshot_ignored(self):
  self.s['requests']['new']=candidate('new','서울숲');self.assertEqual('one',self.call()['targetId'])
 def test_request_change_during_inference_is_rejected(self):
  def change(_):self.s['requests']['one']['longitude']=126.;return positive(_)
  self.r.voice_predict=change;self.assertNotIn('targetId',self.call())
 def test_unknown_ids_and_stale_link(self):
  self.assertNotIn('targetId',self.call(candidateIds=['invented'],text='추천지 경유해줘'))
  self.now+=16
  with self.assertRaises(ApiError):self.call()
 def test_malformed_and_rate_limit(self):
  with self.assertRaises(ApiError):self.call(candidateIds=['x']*6,text='들러줘')
  self.call()
  with self.assertRaises(ApiError):self.call()
 def test_heartbeat_lock_not_held_during_model(self):
  def check(_):
   acquired=[]
   import threading
   def other():
    with self.r.lock:acquired.append(True)
   t=threading.Thread(target=other);t.start();t.join(1);self.assertEqual([True],acquired);return positive(_)
  self.r.voice_predict=check;self.call()
if __name__=='__main__':unittest.main()

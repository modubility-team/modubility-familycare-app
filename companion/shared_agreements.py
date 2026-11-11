"""Shared-area writes require both recent passenger users to agree; never infer consent."""
import copy,math
import delegation,seat_permissions
def resource(s,r):
 o=r.get('options',{});kind=r['kind']
 if kind=='control':
  key='control:'+o.get('control','');c=next((c for c in delegation.catalog(s['state']) if c['id']==key and c.get('shared')),None)
  return (key,o.get('value'),c) if c else None
 if kind=='climate' and 'power' in o:
  return 'climatePower',int(o['power']),dict(boolean=True,label='공조 전원',step=1,min=0,max=1)
 if kind=='climate':
  area=o.get('area','ROW_1_LEFT')
  if len(seat_permissions.members(area))>1:return 'climate:'+area,r.get('target'),dict(boolean=False,label='공용 구역 온도',step=.5,min=18,max=28)
 return None
def expire(s,now):
 for r in s['requests'].values():
  a=r.get('agreement')
  if r['status']=='awaiting_agreement' and (not a or now-a['at']>90 or s.get('heartbeat') is None or now-s['heartbeat']>15 or any(not delegation.active(s,s['devices'].get(d)) for d in a['participants'])):
   r.update(status='rejected',message='합의 시간이 지났거나 참여자의 권한이 종료됐어요')
def decorate(s,r,now):
 if not r.get('delegated'):return
 data=resource(s,r)
 if not data:return
 key,value,c=data
 if any(x['status'] in ('pending','executing','awaiting_agreement') and resource(s,x) and resource(s,x)[0]==key for x in s['requests'].values()):raise ValueError('이 공용 기능을 처리 중이에요. 완료 후 다시 조절하세요')
 previous=s.setdefault('sharedLast',{}).get(key)
 if not previous or previous['deviceId']==r.get('deviceId') or previous['value']==value:return
 peer=s['devices'].get(previous['deviceId']);requester=s['devices'].get(r['deviceId'])
 scope='climate' if key.startswith('climate:') else key
 if not delegation.active(s,peer) or scope not in peer['scopes']:return
 if any(x['status']=='awaiting_agreement' and x.get('agreement',{}).get('resource')==key for x in s['requests'].values()):
  raise ValueError('이 공용 기능의 합의가 진행 중이에요. 먼저 합의를 마쳐 주세요')
 proposed=value
 if not c.get('boolean'):
  first=peer.get('limits',{}).get(scope,{});second=requester.get('limits',{}).get(scope,{})
  lo=max(first.get('min',float('inf')),second.get('min',float('inf')),c['min']);hi=min(first.get('max',-float('inf')),second.get('max',-float('inf')),c['max'])
  if lo>hi:raise ValueError('두 사람의 승인 범위가 겹치지 않아요. 운전자에게 범위 변경을 요청하세요')
  step=c.get('step',1);proposed=c['min']+math.floor(((previous['value']+value)/2-c['min'])/step+.5)*step;proposed=min(hi,max(lo,proposed))
 r['_input']=copy.deepcopy({k:r[k] for k in ('options','target','latitude','longitude') if k in r})
 r['agreement']=dict(resource=key,label=c['label'],current=previous['value'],requested=value,proposed=proposed,participants=[peer['id'],requester['id']],names=[peer['name'],requester['name']],accepted=[],at=now,boolean=c.get('boolean',False))
 r.update(status='awaiting_agreement',message='공용 설정이 달라요. 두 사람이 제안 값에 동의하면 적용합니다')
def agree(s,device,r,accept,now):
 expire(s,now);a=r.get('agreement')
 if not device or not a or device['id'] not in a['participants'] or r['status']!='awaiting_agreement':raise ValueError('참여 중인 공용 설정 합의가 아니에요')
 if type(accept) is not bool:raise ValueError('동의 또는 취소를 선택하세요')
 if not accept:r.update(status='rejected',message='동승자가 합의를 취소했어요. 차량 설정은 바꾸지 않았습니다');return
 scope='climate' if a['resource'].startswith('climate:') else a['resource']
 if any(not delegation.active(s,s['devices'].get(id)) or scope not in s['devices'][id]['scopes'] for id in a['participants']):raise ValueError('권한이 바뀌었어요. 다시 요청하세요')
 if not a['boolean']:
  for id in a['participants']:
   b=s['devices'][id].get('limits',{}).get(scope,{})
   if not b.get('min',float('inf'))<=a['proposed']<=b.get('max',-float('inf')):raise ValueError('승인 범위가 바뀌었어요. 다시 요청하세요')
 if device['id'] not in a['accepted']:a['accepted'].append(device['id'])
 if len(a['accepted'])==2:
  if r['kind']=='control':r['options']=dict(r['options'],value=a['proposed'])
  elif a['resource']=='climatePower':r['options']=dict(r['options'],power=bool(a['proposed']))
  else:r['target']=a['proposed']
  r.update(status='pending',createdAt=now,message='두 사람의 합의 완료 · 제안 값을 차량에 전달 중')
def recorded(s,r,now):
 if r['status']!='confirmed':return
 data=resource(s,r)
 if not data:return
 key,value,_=data
 if r.get('originRole')=='owner':
  s.setdefault('sharedLast',{}).pop(key,None)
  for other in s['requests'].values():
   if other['status']=='awaiting_agreement' and other.get('agreement',{}).get('resource')==key:other.update(status='rejected',message='소유자가 공용 설정을 변경했어요. 새 상태를 확인하세요')
 elif r.get('deviceId'):s.setdefault('sharedLast',{})[key]=dict(deviceId=r['deviceId'],value=value,at=now)

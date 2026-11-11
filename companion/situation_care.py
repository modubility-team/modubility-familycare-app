"""Explicit passenger-selected care; no sleep/health inference or safety-alert suppression."""
import copy,math
import delegation,seat_permissions
MODES={'sleep':'아이가 잠들었어요','comfort':'편안하게 이동하고 싶어요','pet':'반려동물과 쉬어 가요'}
def distance(a,b):
 x=math.radians(b[1]-a[1])*math.cos(math.radians((a[0]+b[0])/2));y=math.radians(b[0]-a[0])
 return 6371000*math.hypot(x,y)
def stop_point(o):
 # The family's designated rest stop: a place they picked, added to the navigation as a waypoint.
 if not isinstance(o,dict) or set(o)-{'name','latitude','longitude'}:raise ValueError('지정 휴게소를 다시 선택하세요')
 name=o.get('name')
 if not isinstance(name,str) or not 1<=len(name.strip())<=80 or any(ord(c)<32 for c in name):raise ValueError('휴게소 이름을 확인하세요')
 for key,limit in (('latitude',90),('longitude',180)):
  n=o.get(key)
  if isinstance(n,bool) or not isinstance(n,(int,float)) or not math.isfinite(n) or abs(n)>limit:raise ValueError('휴게소 위치를 확인하세요')
 return dict(name=name.strip(),latitude=float(o['latitude']),longitude=float(o['longitude']))
def validate(s,role,device,o,now):
 if not isinstance(o,dict) or set(o)-{'action','mode','caregiver','minutes','temperature','stop'}:raise ValueError('상황별 케어 설정을 확인하세요')
 if o.get('action') not in ('start','stop','rest'):raise ValueError('시작·종료·휴식 완료를 선택하세요')
 old=s.get('situation',{})
 if old and role!='owner' and old['deviceId']!=device['id']:raise ValueError('현재 케어를 시작한 보호자 또는 소유자가 변경해 주세요')
 if o['action']!='start':return
 if o.get('mode') not in MODES:raise ValueError('상황을 선택하세요')
 # Older apps send a timed plan (minutes); new apps send the family's designated rest stop.
 if 'minutes' in o and (type(o['minutes']) is not int or not 5<=o['minutes']<=120):raise ValueError('5~120분 사이 휴식 계획을 선택하세요')
 if 'stop' in o:stop_point(o['stop'])
 caregiver=s['devices'].get(o.get('caregiver'))
 if not delegation.active(s,caregiver) or 'care' not in caregiver['scopes'] or now-s.get('deviceSeen',{}).get(caregiver['id'],-1000)>15:raise ValueError('상황별 케어 권한을 받은 보호자의 앱을 먼저 연결하세요')
 if 'temperature' in o:
  t=o['temperature']
  if not seat_permissions.finite(t) or t*2!=int(t*2):raise ValueError('온도는 0.5도 단위로 선택하세요')
  if not device or not seat_permissions.permits(s['state'],device,'care',o):raise ValueError('내 좌석 온도 권한과 승인 범위를 확인하세요')
def recorded(s,r,now):
 if r['kind']!='care' or r['status']!='confirmed':return
 o=r['options']
 if o['action']=='stop':s.pop('situation',None);return
 if o['action']=='rest':
  c=s.get('situation')
  if c and c.get('minutes'):c['due']=now+c['minutes']*60
  if c and c.get('stop'):c['restedAt']=c['stop']['name'];c['stop']=None
  return
 minutes=o.get('minutes')
 s['situation']=dict(mode=o['mode'],deviceId=r.get('deviceId','owner'),caregiver=o['caregiver'],seat=s['devices'].get(r.get('deviceId'),{}).get('seat','ROW_1_LEFT'),minutes=minutes,due=now+minutes*60 if minutes else None,stop=stop_point(o['stop']) if 'stop' in o else None,temperature=o.get('temperature'),startedAt=now)
def stop_request(s,r,now,permitted):
 """The designated rest stop goes to the navigation as an ordinary waypoint request from the same family member:
 applied at once in P by someone allowed to change the route, otherwise the driver adds it (by voice while driving)."""
 c=s.get('situation')
 if r['kind']!='care' or r['status']!='confirmed' or r['options'].get('action')!='start' or not c or not c.get('stop'):return None
 stop=c['stop'];device=s['devices'].get(r.get('deviceId'))
 w=dict(id=('rest-'+r['id'])[:80],kind='waypoint',latitude=stop['latitude'],longitude=stop['longitude'],client=r['client'],title=('지정 휴게소 · '+stop['name'])[:120],
  status='pending',message='정차 후 운전자가 확인해요',voters=set(),originRole=r.get('originRole','family'),createdAt=now,restStop=True)
 if device:
  delegated=bool(permitted(device))
  w.update(deviceId=device['id'],authorityEpoch=s['grantEpoch'],delegated=delegated)
  if delegated:w['message']='승인된 권한으로 차량에 전달 중'
 return w
def stop_status(s,c):
 """Arrival estimate the car reports for the waypoint at the designated stop (only while it is that stop)."""
 stop=c.get('stop')
 if not stop:return dict(stopOnRoute=False,stopEtaSeconds=None,stopArrived=False)
 t=s['state'].get('restStop')
 if not isinstance(t,dict) or any(isinstance(t.get(k),bool) or not isinstance(t.get(k),(int,float)) or not math.isfinite(t[k]) for k in ('latitude','longitude')) or distance((t['latitude'],t['longitude']),(stop['latitude'],stop['longitude']))>150:
  return dict(stopOnRoute=False,stopEtaSeconds=None,stopArrived=False)
 eta=t.get('etaSeconds')
 eta=max(0,int(eta)) if not isinstance(eta,bool) and isinstance(eta,(int,float)) and math.isfinite(eta) and 0<=eta<=86400 else None
 return dict(stopOnRoute=t.get('onRoute') is True,stopEtaSeconds=eta,stopArrived=t.get('arrived') is True)
def state(s,now):
 c=s.get('situation')
 if not c:return {}
 if s.get('heartbeat') is None or now-s['heartbeat']>15 or not s.get('grantEpoch') or c['deviceId']!='owner' and (not delegation.active(s,s['devices'].get(c['deviceId'])) or 'care' not in s['devices'][c['deviceId']]['scopes']):
  s.pop('situation',None);return {}
 caregiver=s['devices'].get(c['caregiver']);online=delegation.active(s,caregiver) and 'care' in caregiver['scopes'] and now-s.get('deviceSeen',{}).get(c['caregiver'],-1000)<=15
 status=stop_status(s,c)
 due=c.get('due')
 return dict(copy.deepcopy(c),**status,label=MODES[c['mode']],caregiverName=(caregiver or {}).get('name','보호자'),caregiverOnline=bool(online),
  remainingSeconds=max(0,math.ceil(due-now)) if due else None,restDue=status['stopArrived'] or bool(due and now>=due))
def routed(s,now):
 c=state(s,now)
 if not c.get('caregiverOnline'):return []
 return [r['id'] for r in s['requests'].values() if r['kind']=='note' and r['status']=='pending' and not r.get('options',{}).get('urgent') and now-r['createdAt']<30]
def needs_event(s,now):
 e=s.get('routeNeedsEvent')
 if not e:return {}
 d=s['devices'].get(e['deviceId'])
 auto=bool(e['auto'] and delegation.active(s,d) and 'route' in d.get('scopes',[]))
 return dict(id=e['id'],deviceId=e['deviceId'],name=(d or {}).get('name',e['name']),needs=list(e['needs']),auto=auto,age=round(max(0.,now-e['at']),1))
def public(s,device,now):
 c=state(s,now);ids=routed(s,now)
 return dict(routeNeedsEvent=needs_event(s,now),routeNeeds=[dict(deviceId=d['id'],name=d['name'],needs=d.get('routeNeeds',[])) for d in s['devices'].values() if delegation.active(s,d)],situation=c,caregivers=[dict(deviceId=d['id'],name=d['name'],seat=d['seat']) for d in s['devices'].values() if delegation.active(s,d) and 'care' in d['scopes'] and now-s.get('deviceSeen',{}).get(d['id'],-1000)<=15],careRoutedNoteIds=ids,careInbox=[dict(id=r['id'],title=r['title'],createdAt=r['createdAt']) for r in s['requests'].values() if r['id'] in ids] if device and device['id']==c.get('caregiver') else [])
def acknowledge(s,device,r,now):
 if not device or device['id']!=state(s,now).get('caregiver') or r['id'] not in routed(s,now):raise ValueError('현재 담당 보호자만 이 요청을 확인할 수 있어요')
 r.update(status='confirmed',message='담당 보호자가 확인했어요')

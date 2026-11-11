"""Opt-in pre-boarding location sharing with explicitly sent, expiring GPS fixes."""
import copy,math,secrets,time
from maps import point,distance,MapError
def enabled(s,now):return now<s.get('pickupUntil',0)
def clean_text(value,limit):
 if not isinstance(value,str) or not 2<=len(value.strip())<=limit or any(ord(c)<32 and c not in '\n\r\t' for c in value):raise ValueError('가게·상가 이름과 기다리는 위치를 입력하세요 (2~2000자)')
 return ' '.join(value.split())
def vehicle(s,now):
 p=s.get('state',{}).get('vehiclePosition')
 if not isinstance(p,dict) or s.get('heartbeat') is None or now-s['heartbeat']>10:return None
 if not isinstance(p.get('ageMs'),(int,float)) or p['ageMs']<0 or p['ageMs']+(now-s['heartbeat'])*1000>15000:return None
 try:point(p.get('point'))
 except MapError:return None
 return copy.deepcopy(p)
def expire(s,now):
 if not enabled(s,now):
  s.pop('pickups',None)
  for d in s['devices'].values():d.pop('pickupDraft',None)
 for id,r in list(s.get('pickups',{}).items()):
  if now-r['at']>1800 or id not in s['devices']:s['pickups'].pop(id,None)
 for r in s['requests'].values():
  if r['kind']=='pickup' and r['status']=='pending' and (not enabled(s,now) or r.get('deviceId') not in s.get('pickups',{}) or now-r['createdAt']>1800):r.update(status='rejected',message='픽업 요청이 끝났거나 만료됐어요')
def public(s,role,device,now):
 expire(s,now);on=enabled(s,now);car=vehicle(s,now) if on and (role in ('owner','car') or device) else None
 items=list(s.get('pickups',{}).values()) if role in ('owner','car') else [s['pickups'][device['id']]] if device and device['id'] in s.get('pickups',{}) else []
 result=[]
 for entry in items:
  r=copy.deepcopy(entry);r['ageSeconds']=int(now-r.pop('at'));r.pop('draft',None)
  if car:r['straightMeters']=distance(car['point'],r['point'])
  result.append(r)
 return dict(enabled=on,expiresIn=max(0,int(s.get('pickupUntil',0)-now)),vehicle=car,waiting=result,notice='차량 위치는 픽업 공유 중에만 표시합니다. GPS 오차·시설 운영·정차 가능 여부를 직접 확인하세요.')
def validate_fix(body):
 p=point(body.get('point'));accuracy=body.get('accuracy');age=body.get('ageMs')
 if type(accuracy) not in (int,float) or not math.isfinite(accuracy) or not 0<accuracy<=100 or type(age) not in (int,float) or not math.isfinite(age) or not 0<=age<=60000:raise MapError(400,'정확한 위치를 다시 받아 주세요. 위치 오차 100m 이하·1분 이내 GPS가 필요해요')
 return p,float(accuracy)
def draft(s,device,body,address,now):
 p,accuracy=validate_fix(body);d=dict(id=secrets.token_urlsafe(16),point=p,accuracy=accuracy,address=address['address'],road=address['road'],at=now)
 device['pickupDraft']=d
 return dict(draft=d['id'],point=p,accuracy=accuracy,address=d['address'],road=d['road'],notice='GPS 근처 주소입니다. 건물 입구와 도로 건너편 여부를 확인하고 대기 위치를 적어 주세요.')
def prepare_request(s,device,o,now):
 if not device or not enabled(s,now):raise ValueError('소유자가 픽업 위치 공유를 켠 뒤 연결하세요')
 if not isinstance(o,dict) or set(o)-{'draft','message','name','landmark'}:raise ValueError('픽업 내용을 확인하세요')
 d=device.get('pickupDraft',{})
 if o.get('draft')!=d.get('id') or now-d.get('at',-1000)>120:raise ValueError('위치가 오래됐어요. 현재 위치를 다시 확인하세요')
 landmark=clean_text(o.get('landmark'),80);memo=o.get('message','')
 if not isinstance(memo,str) or len(memo)>2000:raise ValueError('추가 메모는 2000자까지 입력하세요')
 memo=' '.join(memo.split());message=landmark+(' · '+memo if memo else '');name=clean_text(o.get('name','동승 예정자'),30)
 short=landmark[:55]
 speech=(d['road'][:30]+' 근처, '+short+'에서 동승 예정자가 기다리고 있습니다.')[:120]
 return dict(latitude=d['point'][1],longitude=d['point'][0],title=speech,options=dict(message=message,name=name,address=d['address'],accuracy=d['accuracy'],point=d['point'],draft=d['id'],landmark=landmark))
def record(s,device,r,now):
 for old in s['requests'].values():
  if old['kind']=='pickup' and old.get('deviceId')==device['id'] and old['status']=='pending':old.update(status='rejected',message='새 대기 위치로 업데이트됐어요')
 o=r['options'];s.setdefault('pickups',{})[device['id']]=dict(deviceId=device['id'],requestId=r['id'],name=o['name'],point=o['point'],accuracy=o['accuracy'],address=o['address'],message=o['message'],at=now,status='waiting')
def handle(s,role,device,action,body,now):
 if action=='pickup-share':
  if role not in ('owner','car') or type(body.get('enabled')) is not bool:raise ValueError('차량 소유자만 위치 공유를 설정할 수 있어요')
  s['pickupUntil']=now+7200 if body['enabled'] else 0;expire(s,now);return dict(enabled=enabled(s,now))
 if action in ('pickup-cancel','pickup-boarded'):
  id=body.get('deviceId') if role in ('car','owner') else device['id'] if device else None
  if not id or action=='pickup-boarded' and role not in ('car','owner'):raise ValueError('차량 소유자만 탑승 완료를 확인할 수 있어요')
  s.get('pickups',{}).pop(id,None)
  if id in s['devices']:s['devices'][id].pop('pickupDraft',None)
  for r in s['requests'].values():
   if r['kind']=='pickup' and r.get('deviceId')==id and r['status']=='pending':r.update(status='rejected',message='탑승 완료' if action=='pickup-boarded' else '대기를 취소했어요')
  return dict(ended=True)
 raise ValueError('없는 픽업 기능이에요')

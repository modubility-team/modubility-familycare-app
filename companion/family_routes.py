"""Bounded real-provider alternatives. Metrics describe fetched routes, not health benefits."""
import hashlib,json,math,time
from maps import MapError
from tmap_maps import point,distance
NEEDS=('빠른 도착','방향 전환 적게','화장실','반려견 산책','쉬어 갈 장소')
def compare(maps,body):
 start,end=point(body.get('start')),point(body.get('end'))
 # 'keep' is a stop already on the route (pickup, family place): every candidate passes it. One stop per route.
 keep=point(body['keep']) if body.get('keep') is not None else None
 via=point(body['via']) if body.get('via') is not None and keep is None else None
 if distance(start,end)<30:raise MapError(400,'목적지가 현재 위치와 너무 가까워요')
 options=[0,2,4];candidates=[];seen={};errors=[]
 for option,stop in [(o,None) for o in options]+([(0,via)] if via else []):
  request=dict(start=start,end=end,routeOption=option)
  if stop or keep:request['via']=stop or keep
  try:
   data=maps.directions(request);route=data['routes'][0];points=route['geometry']['coordinates']
   signature=hashlib.sha256(json.dumps([[round(x,6),round(y,6)] for x,y in points]).encode()).hexdigest()
   if signature in seen and not stop:
    seen[signature]['options'].append(option);continue
   steps=[s for leg in route.get('legs',[]) for s in leg.get('steps',[])]
   turns=sum(1 for s in steps if s.get('maneuver',{}).get('type') not in ('depart','arrive','service') and (s.get('maneuver',{}).get('modifier') in ('left','right','uturn') or s.get('maneuver',{}).get('type')=='roundabout'))
   seconds=route['duration']
   if not math.isfinite(seconds) or seconds<=0:raise ValueError('invalid time')
   c=dict(id=signature+('-via' if stop else ''),options=[option],seconds=seconds,meters=sum(distance(a,b) for a,b in zip(points,points[1:])),turns=turns,payload=data,via=stop or keep,stop=bool(stop),stopName=str(body.get('stopName','선택한 휴식 장소'))[:80] if stop else '',labels=[])
   candidates.append(c);seen[signature]=c
  except MapError as e:errors.append(e.message)
  except (KeyError,TypeError,ValueError):errors.append('일부 후보의 경로 정보를 읽지 못했어요')
 if not candidates:raise MapError(502,errors[0] if errors else '경로 후보가 없어요')
 direct=[c for c in candidates if not c['stop']]
 if direct:
  fastest=min(direct,key=lambda c:c['seconds']);fewest=min(direct,key=lambda c:(c['turns'],c['seconds']))
  fastest['labels'].append('후보 중 가장 빠름');fewest['labels'].append('후보 중 방향 전환 최소')
  for c in candidates:c['extraSeconds']=c['seconds']-fastest['seconds']
 needs=body.get('needs',[])
 if '방향 전환 적게' in needs:candidates.sort(key=lambda c:(c['turns'],c['seconds']))
 else:candidates.sort(key=lambda c:c['seconds'])
 for c in candidates:
  if c['stop']:c['labels'].append('선택한 휴식 장소 경유')
  elif not c['labels']:c['labels'].append('다른 경로 후보')
 return dict(candidates=candidates,errors=list(dict.fromkeys(errors)),generatedAt=int(time.time()*1000),notice='조회한 후보끼리 비교 · 검색 시점 교통 예상치 · 방향 전환 수는 멀미 예방 효과를 뜻하지 않습니다. 휴식 장소의 실제 시설·영업·진입 가능 여부는 방문 전 확인하세요.')

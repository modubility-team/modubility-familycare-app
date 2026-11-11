"""Seat and range authorization derived solely from authenticated vehicle capabilities."""
import math,copy
SEATS={'ROW_1_LEFT':'운전석','ROW_1_RIGHT':'조수석','ROW_2_LEFT':'뒷좌석 왼쪽','ROW_2_CENTER':'뒷좌석 가운데','ROW_2_RIGHT':'뒷좌석 오른쪽'}
def finite(n):return type(n) in (int,float) and math.isfinite(n)
def members(key):
    return [x for x in key.split(':')[-1].split('+') if x in SEATS]
def zones(state):
    found=[z for z in state.get('climateAreas',[]) if isinstance(z,dict) and z.get('target') is not None]
    if not found and state.get('climateSupported'):found=[dict(id='ROW_1_LEFT',min=18,max=28)]
    return found
def zone_for(state,seat):return next((z for z in zones(state) if seat in members(z.get('id',''))),None)
def catalog(state,seat=None):
    result=[];available=zones(state)
    if state.get('climateSupported') and available:
        z=zone_for(state,seat) if seat else None
        if z or seat is None:
            result.append(dict(id='climate',label='내 좌석 설정 온도',group='내 좌석',seats=list(dict.fromkeys(s for a in available for s in members(a['id']))),shared=bool(z and len(members(z['id']))>1),min=z.get('min',18) if z else 18,max=z.get('max',28) if z else 28,step=.5,boolean=False,parked=False))
    if state.get('climatePowerSupported'):result.append(dict(id='climatePower',label='공조 전원 · 차량 공용',group='공용 조절',seats=list(SEATS),shared=True,boolean=True,parked=False))
    for c in state.get('controls',[]):
        if not isinstance(c,dict) or not c.get('available'):continue
        seats=members(c['id']) or (['ROW_1_LEFT'] if c['id']=='wheel.heat' else list(SEATS))
        if seat and seat not in seats:continue
        result.append(dict(id='control:'+c['id'],label=c['label'],group='내 좌석' if len(seats)==1 else '공용 조절',seats=seats,shared=len(seats)>1,parked=c['id'].startswith(('door.','window.')),min=c.get('min',0),max=c.get('max',0),step=c.get('step',1),boolean=c.get('boolean',False)))
    for key,label in [('route','목적지·경유지 변경'),('timer','반려동물 확인 타이머'),('profile','가족 구성과 맞춤 모드'),('hold','공조 보조 · 운전석 온도 권한 필요'),('care','상황별 케어와 보호자 알림')]:
        if key=='hold' and seat not in (None,'ROW_1_LEFT'):continue
        result.append(dict(id=key,label=label,group='이동과 케어',seats=list(SEATS) if key!='hold' else ['ROW_1_LEFT'],shared=True,boolean=True,parked=key!='care'))
    return result
def validate(state,options,requested=None):
    if not isinstance(options,dict) or set(options)-{'scopes','name','seat','limits'}:raise ValueError('좌석과 요청 권한을 확인하세요')
    name=options.get('name');seat=options.get('seat','ROW_1_LEFT');scopes=options.get('scopes')
    if not isinstance(name,str) or not 1<=len(name.strip())<=30 or any(ord(x)<32 for x in name):raise ValueError('이름은 1~30자로 입력하세요')
    if seat not in SEATS:raise ValueError('탑승 좌석을 선택하세요')
    valid={c['id']:c for c in catalog(state,seat)}
    if not isinstance(scopes,list) or not 1<=len(scopes)<=50 or any(not isinstance(x,str) or x not in valid for x in scopes) or len(set(scopes))!=len(scopes):raise ValueError('해당 좌석에서 지원하는 기능만 선택하세요')
    limits=options.get('limits',{})
    if not isinstance(limits,dict) or any(k not in scopes or valid[k].get('boolean') for k in limits):raise ValueError('조절 범위를 확인하세요')
    normalized={}
    for key in scopes:
        c=valid[key]
        if c.get('boolean'):continue
        bounds=limits.get(key,dict(min=c['min'],max=c['max']))
        if not isinstance(bounds,dict) or set(bounds)!={'min','max'}:raise ValueError('최소·최대 범위를 선택하세요')
        lo,hi=bounds['min'],bounds['max'];step=c['step']
        if not finite(lo) or not finite(hi) or not c['min']<=lo<=hi<=c['max'] or any(abs((x-c['min'])/step-round((x-c['min'])/step))>1e-6 for x in (lo,hi)):raise ValueError('차량이 허용하는 조절 단위와 범위를 확인하세요')
        normalized[key]=dict(min=lo,max=hi)
    result=dict(name=name.strip(),seat=seat,scopes=list(scopes),limits=normalized)
    if requested:
        old=validate(state,requested)
        if result['seat']!=old['seat'] or not set(scopes)<=set(old['scopes']):raise ValueError('요청한 좌석·기능 안에서만 승인하세요')
        for key,b in normalized.items():
            if not old['limits'][key]['min']<=b['min']<=b['max']<=old['limits'][key]['max']:raise ValueError('요청 범위보다 넓게 승인할 수 없어요')
    return result
def required(kind,options):
    o=options if isinstance(options,dict) else {}
    if kind=='climate':return (['climate'] if o.get('power') is not False else [])+(['climatePower'] if 'power' in o else [])
    if kind=='control':return ['control:'+str(o.get('control',''))]
    if kind in ('destination','waypoint'):return ['route']
    return [kind] if kind in ('timer','profile','hold','care') else []
def permits(state,device,kind,options,target=None):
    o=options if isinstance(options,dict) else {};needed=required(kind,o);scopes=device.get('scopes',[])
    if not needed or not set(needed)<=set(scopes):return False
    seat=device.get('seat','ROW_1_LEFT');valid={c['id']:c for c in catalog(state,seat)}
    if any(k not in valid for k in needed):return False
    key=None;value=None
    if kind=='climate' and o.get('power') is not False:
        z=zone_for(state,seat)
        if not z or o.get('area','ROW_1_LEFT')!=z['id']:return False
        key='climate';value=target
    if kind=='control':key='control:'+str(o.get('control',''));value=o.get('value')
    if key and not valid[key].get('boolean'):
        bounds=device.get('limits',{}).get(key)
        if not bounds or not finite(value) or not bounds['min']<=value<=bounds['max']:return False
    if kind=='care' and 'temperature' in o:
        if not permits(state,device,'climate',{'area':(zone_for(state,seat) or {}).get('id','')},o['temperature']):return False
    if kind=='hold' and o.get('action')=='start':
        b=device.get('limits',{}).get('climate',{})
        if seat!='ROW_1_LEFT' or 'climate' not in scopes or not b.get('min',99)<=22<=24<=b.get('max',0):return False
    return True

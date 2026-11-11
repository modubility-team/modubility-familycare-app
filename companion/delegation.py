"""Ignition-bound grants, scoped to server-issued device credentials, never caller IDs."""
import secrets
import seat_permissions

def init(s):
    s.setdefault('devices',{})
    s.setdefault('grantEpoch','')

def auth(s,token):
    init(s)
    for role,credential in s['tokens'].items():
        if secrets.compare_digest(credential,token):return role,None
    for device in s['devices'].values():
        if secrets.compare_digest(device['token'],token):return 'family',device
    return None,None

def catalog(state,seat=None):return seat_permissions.catalog(state,seat)

def scope(kind,options):
    if kind=='control':return 'control:'+str(options.get('control','') if isinstance(options,dict) else '')
    if kind in ('destination','waypoint'):return 'route'
    return kind if kind in ('climate','timer','profile','hold','care') else None

def revoke(s,device,reason,preserve_pickup=False):
    device['scopes']=[];device['limits']={};device['epoch']='';device['reason']=reason
    for request in s['requests'].values():
        # Waiting for pickup is not a vehicle-control grant; engine transitions do not end it.
        if preserve_pickup and request['kind']=='pickup':continue
        if (request.get('deviceId')==device['id'] and (request['status'] in ('pending','awaiting_agreement') or request['kind']=='permission' and request['status']=='executing')) or (request['status']=='awaiting_agreement' and device['id'] in request.get('agreement',{}).get('participants',[])):
            request.update(status='rejected',message=reason,delegated=False)

def sync(s,state,now):
    init(s)
    epoch=state.get('authorityEpoch') if state.get('authorityActive') is True else state.get('ignitionEpoch') if state.get('ignitionOn') is True else ''
    if not isinstance(epoch,str) or len(epoch)>80:epoch=''
    lost=s.get('heartbeat') is not None and now-s['heartbeat']>15
    if epoch!=s['grantEpoch'] or lost:
        reason='시동이 꺼졌거나 연결이 종료되어 권한이 해제됐어요. 다시 요청하세요'
        for device in s['devices'].values():revoke(s,device,reason,preserve_pickup=not lost)
    s['grantEpoch']=epoch

def active(s,device):
    return bool(device and s['grantEpoch'] and (s['state'].get('authorityActive') is True or s['state'].get('ignitionOn') is True) and device.get('epoch')==s['grantEpoch'])

def public(s,device):
    return dict(deviceId=device['id'],name=device['name'],scopes=list(device['scopes']) if active(s,device) else [],
                epoch=s['grantEpoch'] if active(s,device) else '',active=active(s,device),reason=device.get('reason',''),seat=device.get('seat','ROW_1_LEFT'),limits=device.get('limits',{}) if active(s,device) else {})

def permitted(s,device,kind,options,target=None):
    return active(s,device) and seat_permissions.permits(s['state'],device,kind,options,target)

def approved_request(s,request):
    device=s['devices'].get(request.get('deviceId'))
    return bool(request.get('delegated') and request.get('authorityEpoch')==s['grantEpoch'] and permitted(s,device,request['kind'],request.get('options'),request.get('target')))

def approve(s,request,now,approval=None):
    device=s['devices'].get(request.get('deviceId'))
    requested=request.get('options',{}).get('scopes',[])
    valid={item['id'] for item in catalog(s['state'])}
    if s['heartbeat'] is None or now-s['heartbeat']>15 or not device or not s['grantEpoch'] or request.get('authorityEpoch')!=s['grantEpoch'] or not s['state'].get('parked') or not set(requested)<=valid:
        return False
    try:approved=seat_permissions.validate(s['state'],approval or request.get('options'),requested=request.get('options') if approval else None)
    except (ValueError,TypeError):return False
    device.update(seat=approved['seat'],limits=approved['limits'],scopes=approved['scopes'],epoch=s['grantEpoch'],reason=('이번 차량 연결이 종료될 때까지 사용할 수 있어요' if s['state'].get('authorityMode')=='connection' else '시동이 꺼질 때까지 사용할 수 있어요'))
    return True

def pair(s,device):
    init(s)
    if device:return device
    if len(s['devices'])>=20:raise ValueError('연결 기기가 너무 많아요. 차량에서 연결을 새로 시작하세요')
    ident=secrets.token_urlsafe(12)
    device=dict(id=ident,token=secrets.token_urlsafe(32),name='동승자 휴대폰 '+str(len(s['devices'])+1),scopes=[],seat='',limits={},epoch='',reason='사용할 권한을 선택해 운전자에게 요청하세요')
    s['devices'][ident]=device
    return device

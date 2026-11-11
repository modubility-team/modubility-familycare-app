"""WithME (Family Care project) local demo relay. Python 3.11+, standard library only.

No database, accounts, external messaging or telemetry. Sessions expire after two
hours and disappear on restart. Bind loopback unless explicitly running a LAN demo.
"""
import argparse
import os
from external_tunnel import TunnelService
import base64
import datetime
import copy
import json
import math
import secrets
import delegation
import shared_agreements
import situation_care
import family_routes
import pickup
import music_stream
import voice_semantics
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from maps import MapError
from tmap_maps import TmapMaps
from camera import configuration as camera_configuration, fetch_frame, CameraError


class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Relay:
    def __init__(self, public_url="http://127.0.0.1:8765", clock=time.monotonic, maps=None, camera_fetch=None):
        self.sessions = {}
        self.clock = clock
        self.public_url = public_url.rstrip("/")
        self.lock = threading.RLock()
        self.maps = maps or TmapMaps()
        self.camera_fetch = camera_fetch or fetch_frame

    def camera_call(self, sid, token, action, body):
        with self.lock:
            s, role = self._map_access(sid, token)
            camera = s.get('camera')
            if action == 'camera-status':
                return dict(configured=bool(camera), checkedAt=s.get('cameraCheckedAt'),
                            url=camera['url'] if camera and role in ('owner','car') else '')
            if role == 'car' and (s['heartbeat'] is None or self.clock()-s['heartbeat']>6 or s['state'].get('parked') is not True):
                raise ApiError(409, '카메라 화면은 P단 정차 후 확인하세요')
            if action == 'camera-configure':
                if role not in ('car','owner'):raise ApiError(403, '소유자만 카메라를 연결할 수 있어요')
                if type(body.get('enabled')) is not bool:raise ApiError(400, '연결 설정을 확인하세요')
                try:camera = camera_configuration(body) if body['enabled'] else None
                except CameraError as error:raise ApiError(400,str(error)) from None
                s['camera']=camera;s['cameraRevision']=s.get('cameraRevision',0)+1;s['cameraCheckedAt']=None
                return dict(configured=bool(camera))
            if not camera:raise ApiError(409, '소유자가 카메라 주소를 연결하면 화면을 볼 수 있어요')
            if action == 'camera-check':
                s['cameraCheckedAt']=int(time.time()*1000)
                return dict(checkedAt=s['cameraCheckedAt'])
            if action != 'camera-frame':raise ApiError(404,'없는 카메라 기능이에요')
            now=self.clock()
            if now-s.get('cameraLast',{}).get(role,-1000)<0.8 or s.get('cameraBusy',{}).get(role):
                raise ApiError(429,'화면 수신 중이에요. 잠시 후 다시 확인하세요')
            s.setdefault('cameraLast',{})[role]=now;s.setdefault('cameraBusy',{})[role]=True
            revision=s.get('cameraRevision',0)
        try:
            result=self.camera_fetch(camera)
            with self.lock:
                current,current_role=self._map_access(sid,token)
                if current is not s or current.get('cameraRevision')!=revision:raise ApiError(409,'카메라 연결이 변경됐어요')
                if current_role=='car' and (s['heartbeat'] is None or self.clock()-s['heartbeat']>6 or s['state'].get('parked') is not True):raise ApiError(409,'주행 중에는 카메라 화면을 표시하지 않아요')
                return result
        except CameraError as error:raise ApiError(502,str(error)) from None
        finally:
            with self.lock:s.get('cameraBusy',{}).pop(role,None)

    def _map_access(self, sid, token):
        s = self.sessions.get(sid)
        now = self.clock()
        if not s or now >= s['expires']:
            raise ApiError(410, '연결이 끝났어요. 차량에서 새 QR을 열어 주세요')
        role, device = delegation.auth(s,token)
        if role not in ('car', 'owner', 'family') or not s['permissions'].get(role, True):
            raise ApiError(403, '소유자 또는 가족 연결에서 장소를 검색하세요')
        return s, role

    def map_call(self, sid, token, action, body):
        with self.lock:
            s, role = self._map_access(sid, token)
            if action == 'maps-status':
                return self.maps.status()
            _,device=delegation.auth(s,token)
            if action in ('pickup-location','pickup-eta'):
                if role!='family' or not device or not pickup.enabled(s,self.clock()):raise ApiError(403,'픽업 위치 공유가 켜진 차량에 가족 링크로 연결하세요')
                if action=='pickup-location':
                    try:pickup.validate_fix(body)
                    except MapError as e:raise ApiError(e.status,e.message) from None
                else:
                    waiting=s.get('pickups',{}).get(device['id']);vehicle=pickup.vehicle(s,self.clock())
                    if not waiting or not vehicle:raise ApiError(409,'대기 위치를 보내고 차량 GPS 수신을 확인하세요')
                    body=dict(body,start=vehicle['point'],end=waiting['point']);pickup_id=waiting['requestId']
            if body.get('provider') != 'tmap':
                raise ApiError(409, '지도 제공자가 변경됐어요. 차량·휴대폰 앱을 최신 버전으로 업데이트하세요')
            if action in ('maps-route','maps-compare') and role != 'car':
                raise ApiError(403, '경로 변경은 차량에서 운전자 승인 후 진행하세요')
            # The comparison screen needs a parked car; 'auto' is the car re-routing by family criteria, like any reroute.
            if action=='maps-compare' and (s['heartbeat'] is None or self.clock()-s['heartbeat']>15 or not (s['state'].get('parked') or body.get('auto') is True)):raise ApiError(409,'P단 정차 후 경로를 비교하세요')
            if body.get('consent') is not True:
                raise ApiError(400, '검색어·위치의 지도 제공자 전송 안내를 확인하세요')
            if action == 'maps-sdk-config':
                try:
                    return self.maps.sdk_config()
                except MapError as error:
                    raise ApiError(error.status, error.message) from None
            now = self.clock()
            if now-s.get('mapLast', {}).get(role, -1000) < 1:
                raise ApiError(429, '잠시 후 다시 검색하세요')
            s.setdefault('mapLast', {})[role] = now
            if action=='maps-compare':body=dict(body,needs=list(dict.fromkeys(n for d in s['devices'].values() if delegation.active(s,d) for n in d.get('routeNeeds',[]))))
        # Network I/O must not block heartbeat, safety requests, or revocation.
        try:
            result = self.maps.memory_place(body) if action=='memory-place' else self.maps.reverse(body) if action=='pickup-location' else self.maps.directions(body) if action=='pickup-eta' else family_routes.compare(self.maps,body) if action == 'maps-compare' else self.maps.directions(body) if action == 'maps-route' else self.maps.preview(body) if action == 'maps-preview' else self.maps.search(body)
        except MapError as error:
            raise ApiError(error.status, error.message) from None
        with self.lock:
            self._map_access(sid, token)  # Revocation/expiry during a slow search wins.
            if action in ('pickup-location','pickup-eta'):
                _,device=delegation.auth(s,token)
                if not device or not pickup.enabled(s,self.clock()):raise ApiError(403,'픽업 공유가 종료됐어요')
                if action=='pickup-location':result=pickup.draft(s,device,body,result,self.clock())
                else:
                    waiting=s.get('pickups',{}).get(device['id'])
                    if not waiting or waiting['requestId']!=pickup_id:raise ApiError(409,'대기 위치가 바뀌었어요. 다시 조회하세요')
                    route=result['routes'][0];points=route['geometry']['coordinates']
                    result=dict(seconds=route['duration'],meters=sum(pickup.distance(a,b) for a,b in zip(points,points[1:])),queriedAt=int(time.time()*1000),notice='조회 시점의 자동차 경로 예상치 · 실제 정차 위치와 교통에 따라 달라져요')
        return result

    def sweep(self):
        with self.lock:
            now = self.clock()
            self.sessions = {k: v for k, v in self.sessions.items() if now < v["expires"]}

    def create(self):
        with self.lock:
            now = self.clock()
            self.sessions = {k: v for k, v in self.sessions.items() if now < v["expires"]}
            if len(self.sessions) >= 16:
                raise ApiError(429, "진행 중인 연결이 너무 많아요")
            sid = secrets.token_urlsafe(12)
            tokens = {role: secrets.token_urlsafe(32) for role in ("car", "owner", "family", "guest", "observer")}
            self.sessions[sid] = dict(tokens=tokens, expires=now + 7200, heartbeat=None,
                state={}, requests={}, clients={}, alerts={}, alert_history=[], arrivals=0, memories={}, recipients={},
                permissions={"family": True, "guest": True, "observer": True})
            return dict(id=sid, token=tokens["car"], expiresIn=7200,
                links={r: self.public_url + "/join#" + sid + "." + t for r, t in tokens.items() if r != "car"})

    def voice_call(self,sid,token,body):
        with self.lock:
            s,role=self._map_access(sid,token)
            if role!='car':raise ApiError(403,'운전자 차량 화면에서만 음성 요청을 해석할 수 있어요')
            ids=body.get('candidateIds');text=body.get('text')
            if not isinstance(ids,list) or len(ids)>5 or any(not isinstance(x,str) or len(x)>80 for x in ids) or len(ids)!=len(set(ids)) or not isinstance(text,str) or not 1<=len(text.strip())<=240:raise ApiError(400,'음성 요청을 다시 확인하세요')
            now=self.clock()
            if s.get('heartbeat') is None or now-s['heartbeat']>15:raise ApiError(409,'차량 연결을 확인한 뒤 다시 말해 주세요')
            if now-s.get('voiceAt',-1000)<1:raise ApiError(429,'앞선 음성을 처리 중이에요. 잠시 후 말해 주세요')
            s['voiceAt']=now;pickup.expire(s,now)
            candidates=[copy.deepcopy(s['requests'][id]) for id in ids if id in s['requests'] and s['requests'][id]['status']=='pending' and s['requests'][id]['kind'] in voice_semantics.ROUTE_KINDS]
            selected=body.get('selectedId','');choice=body.get('choiceIntent','')
        try:result=voice_semantics.interpret(text,candidates,selected,choice,classifier=getattr(self,'voice_predict',voice_semantics.predict))
        except ValueError as e:raise ApiError(400,str(e)) from None
        except RuntimeError as e:raise ApiError(503,str(e)) from None
        with self.lock:
            s,role=self._map_access(sid,token)
            for original in candidates:
                current=s['requests'].get(original['id'])
                if current is None or any(current.get(k)!=original.get(k) for k in ('status','kind','title','latitude','longitude','options','authorityEpoch')):
                    return dict(intent='clarify',message='장소 요청이 바뀌었어요. 다시 말해 주세요',candidates=[])
            return result

    def call(self, sid, token, action, body):
        if action=='voice-interpret':return self.voice_call(sid,token,body)
        if action in ('camera-status','camera-configure','camera-frame','camera-check'):
            return self.camera_call(sid,token,action,body)
        if action in ('maps-status', 'maps-search', 'maps-route', 'maps-preview', 'maps-sdk-config', 'maps-compare', 'pickup-location', 'pickup-eta', 'memory-place'):
            return self.map_call(sid, token, action, body)
        with self.lock:
            s = self.sessions.get(sid)
            now = self.clock()
            if not s or now >= s["expires"]:
                self.sessions.pop(sid, None)
                raise ApiError(410, "연결이 끝났어요. 차량에서 새 QR을 열어 주세요")
            role, device = delegation.auth(s,token)
            if not role:
                raise ApiError(403, "연결 권한이 없어요")
            if role in s["permissions"] and not s["permissions"][role]:
                raise ApiError(403, "소유자가 이 연결을 종료했어요")
            if device:
                body=dict(body,client=device['id'])
            if action in ('music-start','music-frame','music-pull','music-command'):
                try:return music_stream.handle(s,role,action,body,now)
                except ValueError as error:raise ApiError(409,str(error)) from None
            shared_agreements.expire(s,now)
            pickup.expire(s,now)
            if action in ('pickup-share','pickup-cancel','pickup-boarded'):
                try:return pickup.handle(s,role,device,action,body,now)
                except ValueError as error:raise ApiError(403,str(error)) from None
            if action=='pair-device':
                if role!='family':raise ApiError(403,'가족 연결에서만 권한을 요청할 수 있어요')
                try:device=delegation.pair(s,device)
                except ValueError as error:raise ApiError(429,str(error))
                return dict(token=device['token'],grant=delegation.public(s,device))
            if action=='leave-device':
                if role!='family' or not device:raise ApiError(403,'연결된 동승자 기기에서만 종료하세요')
                delegation.revoke(s,device,'휴대폰 연결을 종료했어요')
                s['devices'].pop(device['id'],None)
                s['recipients'].pop(device['id'],None)
                return dict(ended=True)
            if action=='revoke-grant':
                if role not in ('car','owner'):raise ApiError(403,'운전자만 권한을 회수할 수 있어요')
                target=s['devices'].get(body.get('deviceId'))
                if not target:raise ApiError(404,'연결된 기기를 찾을 수 없어요')
                delegation.revoke(s,target,'운전자가 권한을 회수했어요')
                return dict(revoked=True)
            if action == "permissions":
                if role not in ("car", "owner"):
                    raise ApiError(403, "소유자만 동승자 권한을 관리할 수 있어요")
                target, enabled = body.get("role"), body.get("enabled")
                if target not in s["permissions"] or not isinstance(enabled, bool):
                    raise ApiError(400, "권한 설정을 확인하세요")
                s["permissions"][target] = enabled
                if not enabled:
                    s["tokens"][target] = secrets.token_urlsafe(32)
                    if target=='family':
                        for paired in s['devices'].values():delegation.revoke(s,paired,'소유자가 가족 연결을 종료했어요')
                        s['devices'].clear()
                    s["recipients"]={k:v for k,v in s["recipients"].items() if v["role"]!=target}
                    for r in s["requests"].values():
                        if r.get("originRole") == target and r["status"] == "pending":
                            r.update(status="rejected", message="소유자가 권한을 회수했어요")
                return dict(permissions=copy.deepcopy(s["permissions"]))
            if action in ("memories", "memory", "delete-memory"):
                return self._memories(s, role, action, body)
            if action in ("subscribe","recipient"):
                client=body.get("client")
                if not isinstance(client,str) or not 1<=len(client)<=80:raise ApiError(400,"기기 연결을 확인하세요")
                if action=="subscribe":
                    if role not in ("owner","family","observer"):raise ApiError(403,"보호자 연결에서 설정하세요")
                    name=body.get("name","")
                    if not isinstance(name,str) or len(name)>40:raise ApiError(400,"보호자 별명을 확인하세요")
                    if len(s["recipients"])>=20 and client not in s["recipients"]:raise ApiError(429,"알림 기기 수를 초과했어요")
                    old=s["recipients"].get(client)
                    if old and old["role"]!=role:raise ApiError(403,"기기 역할이 바뀌었어요")
                    s["recipients"][client]=dict(client=client,name=name or "보호자 기기",role=role,arrival=old.get("arrival",True) if old else True,care=old.get("care",True) if old else True)
                else:
                    if role not in ("car","owner"):raise ApiError(403,"소유자만 수신 대상을 관리할 수 있어요")
                    r=s["recipients"].get(client)
                    if not r:raise ApiError(404,"알림 기기를 찾을 수 없어요")
                    for key in ("arrival","care"):
                        if type(body.get(key)) is not bool:raise ApiError(400,"알림 설정을 확인하세요")
                        r[key]=body[key]
                return {"saved":True}
            if action in ("sync", "claim", "result", "end") and role != "car":
                raise ApiError(403, "차량에서만 할 수 있어요")
            if action == "end":
                del self.sessions[sid]
                return {"ended": True}
            if action == "sync":
                delegation.sync(s,body,now)
                if body.get("ignitionOn") is True or body.get("authorityActive") is True:s["expires"]=now+7200
                # Whitelist telemetry. Profile and opt-in location records are filtered by role in status.
                s["state"] = {k: body[k] for k in ("running", "watching", "mode", "temperature", "battery",
                    "closures", "feedback", "drivingMinutes", "remainingMeters", "remainingSeconds", "arrived",
                    "watchRemainingSeconds", "breakRemainingMinutes", "climateSupported", "climateTarget", "parked",
                    "profile", "routeId", "routeTotalMeters", "updatedAt", "motionLoad", "journey", "routeSummary", "ignitionOn", "ignitionEpoch", "authorityActive", "authorityMode", "authorityEpoch", "ignitionSupported", "controls", "climateAreas", "climatePower", "climatePowerSupported", "climateHolding", "climateHoldSeconds", "gpsStatus", "locationSdk", "mapProvider", "vehiclePosition", "restStop") if k in body}
                s["heartbeat"] = now
                active = {}
                for a in body.get("alerts", [])[:12]:
                    if not isinstance(a, dict) or not isinstance(a.get("code"), str):
                        continue
                    code = a["code"][:40]
                    old = s["alerts"].get(code)
                    active[code] = old or {"code": code, "since": now, "startedAt":int(time.time()*1000), "acknowledged": False}
                    active[code]["title"] = str(a.get("title", "직접 확인해 주세요"))[:120]
                    active[code]["level"] = max(1, min(3, int(a.get("level", 1))))
                for code, alert in s["alerts"].items():
                    if code not in active:
                        s["alert_history"].append(dict(alert,resolved=True,resolvedAt=int(time.time()*1000)))
                s["alert_history"] = s["alert_history"][-100:]
                s["alerts"] = active
                for request in s["requests"].values():
                    if request["status"] == "pending" and request["kind"] in ("climate","timer","profile","hold","control","permission","care") and now - request["createdAt"] > (300 if request["kind"]=="permission" else 60):
                        request.update(status="rejected", message="조절 요청이 만료됐어요. 상태 확인 후 다시 요청하세요")
                    if request["status"] == "executing" and now - request.get("claimedAt", now) > 35:
                        request.update(status="needs_review", message="처리 결과가 확인되지 않았어요. 내비 화면을 확인하세요")
                return {"requests": self._requests(s), "expiresIn": int(s["expires"] - now), "memories": self._memory_list(s), "links": self._links(sid, s), "permissions": copy.deepcopy(s["permissions"]), "recipients":copy.deepcopy(list(s["recipients"].values())), "delegations":[delegation.public(s,d) for d in s["devices"].values()], "permissionCatalog":delegation.catalog(s["state"]), **situation_care.public(s,None,now), 'pickup':pickup.public(s,'car',None,now), 'music':music_stream.status(s,now)}
            if action == 'route-needs':
                needs=body.get('needs')
                if not device or not delegation.active(s,device):raise ApiError(403,'좌석 권한 승인 후 가족의 이동 기준을 선택하세요')
                if not isinstance(needs,list) or len(needs)>5 or any(not isinstance(x,str) or x not in family_routes.NEEDS for x in needs):raise ApiError(400,'이동 기준을 확인하세요')
                device['routeNeeds']=list(dict.fromkeys(needs))
                # Senders allowed to change the route ('route' scope) make the car re-route at once.
                auto='route' in device.get('scopes',[])
                s['routeNeedsEvent']=dict(id=secrets.token_hex(8),deviceId=device['id'],name=device['name'],needs=list(device['routeNeeds']),auto=auto,at=self.clock())
                return dict(saved=True,auto=auto)
            if action == "status":
                if device:s.setdefault('deviceSeen',{})[device['id']]=now
                stale = s["heartbeat"] is None or now - s["heartbeat"] > 15
                alerts = [dict(a, escalated=not a["acknowledged"] and now - a["since"] >= 60)
                          for a in s["alerts"].values()]
                requests = self._requests(s) if role in ("car", "owner", "family") else [self._public(r) for r in s["requests"].values() if role == "guest" and r["kind"] == "note"]
                if role not in ('car','owner'):requests=[r for r in requests if r['kind']!='pickup' or device and r.get('deviceId')==device['id']]
                visible_state={} if stale else copy.deepcopy(s["state"])
                visible_state.pop('vehiclePosition',None)
                if role not in ("car","owner","family"):
                    visible_state.pop("profile",None)
                    visible_state.pop("journey",None)
                    visible_state.pop("routeSummary",None)
                return dict(music=music_stream.status(s,now),pickup=pickup.public(s,role,device,now),**(situation_care.public(s,device,now) if role in ('car','owner','family') else {}),role=role, stale=stale, state=visible_state,
                    grant=delegation.public(s,device) if device and not stale else {},
                    permissionCatalog=delegation.catalog(s['state']) if not stale else [],
                    delegations=[delegation.public(s,d) for d in s['devices'].values()] if role in ('car','owner') else [],
                    recipients=copy.deepcopy(list(s["recipients"].values())) if role in ("car","owner") else [],
                    notificationPreferences=copy.deepcopy(s["recipients"].get(body.get("client"),{})) if isinstance(body.get("client"),str) and s["recipients"].get(body.get("client"),{}).get("role")==role else {},
                    alerts=alerts, alertHistory=copy.deepcopy(s["alert_history"]), requests=requests, expiresIn=int(s["expires"] - now),
                    memories=self._memory_list(s) if role in ("car", "owner", "family") else [],
                    permissions=copy.deepcopy(s["permissions"]) if role in ("car", "owner") else {},
                    invites={k:v for k,v in self._links(sid,s).items() if k != "owner"} if role == "owner" else {})
            if action == "request":
                if role not in ("owner", "family", "guest"):
                    raise ApiError(403, "상태 확인용 연결에서는 요청할 수 없어요")
                original_pickup=None
                if body.get('kind')=='pickup':
                    if role!='family' or not device:raise ApiError(403,'동승 예정자의 가족 연결에서만 보내세요')
                    original_pickup=copy.deepcopy(body)
                    prior=s['requests'].get(body.get('id'))
                    if prior:
                        if prior.get('deviceId')==device['id'] and prior.get('_pickupInput')==original_pickup:return self._public(prior)
                        raise ApiError(409,'요청 ID가 이미 사용됐어요')
                    try:body=dict(body,**pickup.prepare_request(s,device,body.get('options'),now))
                    except ValueError as error:raise ApiError(400,str(error)) from None
                kind, title, rid, client = (body.get(k) for k in ("kind", "title", "id", "client"))
                if not isinstance(rid, str) or not 1 <= len(rid) <= 80 or not isinstance(client, str) or not 1 <= len(client) <= 80:
                    raise ApiError(400, "요청 ID를 확인하세요")
                if kind not in ("note", "destination", "waypoint", "climate", "timer", "profile", "hold", "control", "permission", "care", "pickup") or (role == "guest" and kind != "note"):
                    raise ApiError(403, "이 연결에서 허용되지 않은 요청이에요")
                if not isinstance(title, str) or not 1 <= len(title.strip()) <= 120 or any(ord(c) < 32 for c in title):
                    raise ApiError(400, "요청 내용을 1~120자로 입력하세요")
                if kind=='permission':
                    if role!='family' or not device:raise ApiError(403,'앱의 권한 페이지에서 기기를 연결하세요')
                    if not s['grantEpoch'] or s['heartbeat'] is None or now-s['heartbeat']>15:raise ApiError(409,'차량 연결과 권한 사용 조건을 확인하세요')
                    try:permission=delegation.seat_permissions.validate(s['state'],body.get('options'))
                    except (ValueError,TypeError) as error:raise ApiError(400,str(error)) from None
                    device['name']=permission['name']
                delegated=False
                if role=='family' and delegation.scope(kind,body.get('options')):
                    if s['heartbeat'] is None or now-s['heartbeat']>15 or not delegation.permitted(s,device,kind,body.get('options'),body.get('target')):raise ApiError(403,'권한 페이지에서 먼저 운전자 승인을 받아 주세요')
                    delegated=True
                if kind=='care':
                    try:situation_care.validate(s,role,device,body.get('options'),now)
                    except ValueError as error:raise ApiError(400,str(error)) from None
                if kind=='note' and ('options' in body) and (not isinstance(body['options'],dict) or set(body['options'])-{'urgent'} or type(body['options'].get('urgent')) is not bool):raise ApiError(400,'알림 설정을 확인하세요')
                if kind in ("timer","profile"):
                    options=body.get("options")
                    if not isinstance(options,dict):raise ApiError(400,"설정 내용을 확인하세요")
                    if kind=="timer":
                        minutes=options.get("minutes")
                        if options.get("action") not in ("start","stop") or type(minutes) is not int or not 1<=minutes<=30 or set(options)-{"action","minutes"}:
                            raise ApiError(400,"타이머는 1~30분으로 설정하세요")
                    else:
                        members=options.get("members");band=options.get("childBand")
                        if not isinstance(members,list) or len(members)>5 or any(not isinstance(x,str) or x not in ("CHILD","ELDER","PREGNANT","PET","NOVICE") for x in members) or type(band) is not int or band not in (0,1,2) or type(options.get("sensitivity",1)) is not int or options.get("sensitivity",1) not in (0,1,2) or set(options)-{"members","childBand","sensitivity"}:
                            raise ApiError(400,"가족 구성을 확인하세요")
                    if s["heartbeat"] is None or now-s["heartbeat"]>15:raise ApiError(409,"차량 연결 후 다시 요청하세요")
                if kind == "climate":
                    target = body.get("target")
                    options=body.get("options",{})
                    if not isinstance(options,dict) or set(options)-{"area","power"}:raise ApiError(400,"공조 영역을 확인하세요")
                    if "power" in options:
                        if type(options["power"]) is not bool:raise ApiError(400,"공조 전원 설정을 확인하세요")
                        if not s["state"].get("climatePowerSupported"):raise ApiError(409,"이 차량의 공조 전원 제어를 확인할 수 없어요")
                    area=options.get("area","ROW_1_LEFT")
                    if not isinstance(area,str):raise ApiError(400,"공조 영역을 확인하세요")
                    zones=s["state"].get("climateAreas",[])
                    zone=next((z for z in zones if z.get("id")==area),None)
                    if area!="ROW_1_LEFT" and (zone is None or zone.get("target") is None):raise ApiError(409,"이 좌석의 독립 공조를 지원하지 않아요")
                    if isinstance(target, bool) or not isinstance(target, (int, float)) or not math.isfinite(target) or not 18 <= target <= 28 or target * 2 != int(target * 2):
                        raise ApiError(400, "설정 온도는 18~28도, 0.5도 단위로 선택하세요")
                    if zone and not zone.get("min",18)<=target<=zone.get("max",28):raise ApiError(400,"해당 좌석의 온도 범위를 벗어났어요")
                    if s["heartbeat"] is None or now - s["heartbeat"] > 15 or not s["state"].get("climateSupported"):
                        raise ApiError(409, "현재 차량에서 공조 제어를 확인할 수 없어요")
                    if role == "owner" and not s["state"].get("parked"):
                        raise ApiError(409, "P단 정차 중인 차량에만 공조 설정을 보낼 수 있어요")
                if kind == "control":
                    from vehicle_controls import validate_control
                    if s["heartbeat"] is None or now-s["heartbeat"]>15:raise ApiError(409,"차량 연결 후 요청하세요")
                    problem=validate_control(s["state"],role,body.get("options"),delegated=delegated)
                    if problem:raise ApiError(*problem)
                if kind == "hold":
                    o=body.get("options")
                    if not isinstance(o,dict) or set(o)-{"action","target","minutes"} or o.get("action") not in ("start","stop") or type(o.get("minutes")) is not int or not 1<=o["minutes"]<=10 or type(o.get("target")) not in (float,int) or not math.isfinite(o["target"]) or not 20<=o["target"]<=26:
                        raise ApiError(400,"공조 보조 설정을 확인하세요")
                    if s["heartbeat"] is None or now-s["heartbeat"]>15:raise ApiError(409,"차량 연결 후 요청하세요")
                if kind in ("destination", "waypoint", "pickup"):
                    for key, limit in (("latitude", 90), ("longitude", 180)):
                        n = body.get(key)
                        if isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n) or abs(n) > limit:
                            raise ApiError(400, "올바른 위도와 경도를 입력하세요")
                if rid in s["requests"]:
                    existing = s["requests"][rid]
                    original=existing.get("_input",existing)
                    if existing["client"] != client or existing["kind"] != kind or existing["title"] != title.strip() or existing.get("originRole") != role or any(original.get(k) != body.get(k) for k in ("target", "latitude", "longitude", "options")):
                        raise ApiError(409, "요청 ID가 이미 사용되었어요")
                    return self._public(existing)
                if len(s["requests"]) >= 100 or sum(r["status"] in ("pending","awaiting_agreement") for r in s["requests"].values()) >= 20:
                    raise ApiError(429, "차량에서 기존 요청을 확인한 뒤 다시 보내세요")
                last = s["clients"].get(role, -999)
                if now - last < 2:
                    raise ApiError(429, "잠시 후 다시 보내세요")
                s["clients"][role] = now
                request = {k: copy.deepcopy(body[k]) for k in ("id", "kind", "latitude", "longitude", "client", "target", "options") if k in body}
                request.update(title=title.strip(), status="pending", message="정차 후 운전자가 확인해요", voters=set(), originRole=role, createdAt=now)
                if device:
                    request.update(deviceId=device['id'],authorityEpoch=s['grantEpoch'],delegated=delegated)
                if kind=='pickup':
                    request['deviceId']=device['id'];request['_pickupInput']=original_pickup;pickup.record(s,device,request,now)
                if kind=='permission':request['message']='운전자가 승인하면 '+('이번 차량 연결 종료까지' if s['state'].get('authorityMode')=='connection' else '시동이 꺼질 때까지')+' 사용할 수 있어요'
                elif delegated:request['message']='승인된 권한으로 차량에 전달 중'
                try:shared_agreements.decorate(s,request,now)
                except ValueError as error:raise ApiError(409,str(error)) from None
                s["requests"][rid] = request
                return self._public(request)
            if action == "ack":
                if role not in ("owner", "family", "observer"):
                    raise ApiError(403, "가족 또는 상태 확인용 연결에서 확인하세요")
                alert = s["alerts"].get(body.get("code"))
                if alert:
                    alert["acknowledged"] = True
                    alert["acknowledgedAt"] = int(time.time()*1000)
                    alert["acknowledgedBy"] = role
                return {"acknowledged": bool(alert)}
            request = s["requests"].get(body.get("id"))
            if not request:
                raise ApiError(404, "요청을 찾을 수 없어요")
            if action == "agree":
                try:shared_agreements.agree(s,device,request,body.get("accept"),now)
                except ValueError as error:raise ApiError(409,str(error)) from None
            elif action == 'care-ack':
                try:situation_care.acknowledge(s,device,request,now)
                except ValueError as error:raise ApiError(403,str(error)) from None
                return self._public(request)
            elif action == "vote":
                if role not in ("owner", "family") or request["kind"] not in ("destination", "waypoint") or request["status"] != "pending":
                    raise ApiError(403, "대기 중인 장소에 가족만 투표할 수 있어요")
                client = body.get("client")
                if not isinstance(client, str) or not 1 <= len(client) <= 80:
                    raise ApiError(400, "연결 ID를 확인하세요")
                if len(request["voters"]) >= 20 and client not in request["voters"]:
                    raise ApiError(429, "투표 인원을 초과했어요")
                request["voters"].add(client)
            elif action == "claim":
                if request.get('delegated') and (s['heartbeat'] is None or now-s['heartbeat']>15 or not delegation.approved_request(s,request)):
                    request.update(status='rejected',message='권한이 만료되었어요. 다시 요청하세요')
                    raise ApiError(403,request['message'])
                if request["kind"] in ("climate","timer","profile","hold","control","permission","care") and (now - request["createdAt"] > (300 if request["kind"]=="permission" else 60) or not s["permissions"].get(request.get("originRole"), True)):
                    request.update(status="rejected", message="조절 요청이 만료되었거나 권한이 회수됐어요")
                    raise ApiError(409, request["message"])
                if request["status"] != "pending":
                    raise ApiError(409, "이미 처리 중이거나 완료한 요청이에요")
                request.update(status="executing", claimedAt=now, message="차량에서 확인 중")
            elif action == "result":
                status = body.get("status")
                if status not in ("confirmed", "needs_review", "rejected"):
                    raise ApiError(400, "처리 상태가 올바르지 않아요")
                if request['kind']=='permission' and request['status'] in ('confirmed','rejected'):return self._public(request)
                if request["status"] not in ("executing", "needs_review", status):
                    raise ApiError(409, "요청 상태가 바뀌었어요")
                if request['kind']=='permission' and status=='confirmed':
                    if not delegation.approve(s,request,now,body.get("approval")):status='rejected';body=dict(body,message='시동·정차 상태 또는 요청 권한이 바뀌었어요. 다시 요청하세요')
                    else:body=dict(body,message='권한 승인 완료 · '+('이번 차량 연결 종료까지 사용 가능' if s['state'].get('authorityMode')=='connection' else '시동이 꺼질 때까지 사용 가능'))
                if request['kind']=='care':
                    if request['status']==status:return self._public(request)
                    if status=='confirmed':
                        try:
                            if request.get('delegated') and not delegation.approved_request(s,request):raise ValueError('상황별 케어 권한이 종료됐어요')
                            situation_care.validate(s,request['originRole'],s['devices'].get(request.get('deviceId')),request.get('options'),now)
                        except ValueError as error:status='rejected';body=dict(body,message=str(error))
                request.update(status=status, message=str(body.get("message", ""))[:180])
                shared_agreements.recorded(s,request,now)
                situation_care.recorded(s,request,now)
                stop=situation_care.stop_request(s,request,now,lambda d:s['heartbeat'] is not None and now-s['heartbeat']<=15 and delegation.permitted(s,d,'waypoint',None,None))
                if stop and stop['id'] not in s['requests'] and len(s['requests'])<100:
                    try:shared_agreements.decorate(s,stop,now);s['requests'][stop['id']]=stop
                    except ValueError:pass
            else:
                raise ApiError(404, "없는 기능이에요")
            return self._public(request)

    def _links(self, sid, s):
        return {r:self.public_url + "/join#" + sid + "." + t for r,t in s["tokens"].items() if r != "car" and s["permissions"].get(r,True)}

    @staticmethod
    def _memory_list(s):
        return [{k:v for k,v in m.items() if k not in ("image", "client")} for m in s["memories"].values()]

    def _memories(self, s, role, action, body):
        if role not in ("car", "owner", "family"):
            raise ApiError(403, "추억은 소유자와 가족 연결에서만 공유해요")
        mid=body.get("id")
        if not isinstance(mid,str) or not 1 <= len(mid) <= 80:
            raise ApiError(400, "사진 ID를 확인하세요")
        if action == "memory":
            m=s["memories"].get(mid)
            if not m: raise ApiError(404, "공유가 끝난 사진이에요")
            return {k:v for k,v in m.items() if k != "client"}
        if action == "delete-memory":
            m=s["memories"].get(mid)
            if m and role not in ("car", "owner") and m["client"] != body.get("client"):
                raise ApiError(403, "직접 올린 사진만 삭제할 수 있어요")
            s["memories"].pop(mid,None)
            return {"deleted":True}
        client=body.get("client")
        if not isinstance(client,str) or not 1 <= len(client) <= 80: raise ApiError(400,"연결을 확인하세요")
        title=body.get("title")
        if not isinstance(title,str) or not 1 <= len(title.strip()) <= 80: raise ApiError(400,"사진 제목을 입력하세요")
        captured=body.get("captured", "")
        try: datetime.datetime.strptime(captured,"%Y-%m-%d %H:%M")
        except (ValueError,TypeError): raise ApiError(400,"촬영 날짜를 확인하세요")
        for key,limit in (("latitude",85),("longitude",180)):
            n=body.get(key)
            if isinstance(n,bool) or not isinstance(n,(int,float)) or not math.isfinite(n) or abs(n)>limit: raise ApiError(400,"촬영 장소를 확인하세요")
        try:
            raw=base64.b64decode(body.get("image", ""),validate=True)
            if not 4 <= len(raw) <= 256000 or not raw.startswith(b"\xff\xd8") or not raw.endswith(b"\xff\xd9"): raise ValueError()
        except (ValueError,TypeError): raise ApiError(400,"256KB 이하 JPEG 사진만 공유할 수 있어요")
        old=s["memories"].get(mid)
        if old and old["client"] != client: raise ApiError(409,"이미 사용한 사진 ID예요")
        if not old and len(s["memories"]) >= 12: raise ApiError(429,"한 이동에 사진은 12장까지 공유할 수 있어요")
        thumb=body.get("thumbnail", "")
        try:
            preview=base64.b64decode(thumb,validate=True)
            if not 4 <= len(preview) <= 6000 or not preview.startswith(b"\xff\xd8") or not preview.endswith(b"\xff\xd9"): raise ValueError()
        except (ValueError,TypeError): raise ApiError(400,"작은 JPEG 미리보기가 필요해요")
        s["memories"][mid]={k:body[k] for k in ("id","title","captured","latitude","longitude","image","thumbnail","client")}
        return {"saved":True,"id":mid}

    @staticmethod
    def _public(r):
        return {**{k: v for k, v in r.items() if k not in ("voters", "client", "claimedAt", "createdAt", "_input", "_pickupInput")}, "votes": len(r["voters"])}

    def _requests(self, s):
        return [self._public(r) for r in s["requests"].values()]


class Handler(BaseHTTPRequestHandler):
    server_version = "FamilyCare"
    def setup(self):
        super().setup()
        self.connection.settimeout(5)
    def log_message(self, *_):
        pass  # Never log join credentials, request bodies or passenger messages.

    def reply(self, status, data, content_type="application/json; charset=utf-8"):
        raw = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(raw)

    def guard_host(self):
        if getattr(self.server,'external_only',False):
            origin=getattr(self.server,'external_origin','')
            if not self.server.external_enabled or not origin:raise ApiError(503,'외부 연결이 종료됐거나 준비 중이에요')
            if self.headers.get('Host')!=urlsplit(origin).netloc:raise ApiError(403,'허용되지 않은 외부 주소예요')
            if self.headers.get('Origin') not in (None,origin):raise ApiError(403,'다른 사이트의 요청은 허용하지 않아요')
            return
        # Prevent DNS rebinding and unintended cross-site requests to a local relay.
        host = self.headers.get("Host", "")
        allowed = {urlsplit(self.server.relay.public_url).netloc, "127.0.0.1:" + str(self.server.server_port), "localhost:" + str(self.server.server_port)}
        if host not in allowed:
            raise ApiError(403, "허용되지 않은 호스트예요")
        origin = self.headers.get("Origin")
        if origin and origin not in ("http://" + host, "https://" + host):
            raise ApiError(403, "다른 사이트에서 보낸 요청은 허용하지 않아요")

    def do_GET(self):
        try:
            self.guard_host()
            names = {"/": ("index.html", "text/html"), "/join": ("index.html", "text/html"),
                     "/navigation": ("index.html", "text/html"), "/care": ("index.html", "text/html"),
                     "/launch-native.js": ("launch-native.js", "text/javascript"),
                     "/app.js": ("app.js", "text/javascript"), "/style.css": ("style.css", "text/css"),
                     "/vehicle-showcase.png": ("vehicle-showcase.png", "image/png")}
            downloads={'/install/owner.apk':'02-FamilyCare-navigation-debug.apk','/install/family.apk':'03-FamilyCare-care-debug.apk'}
            if self.path in downloads:
                file=Path(__file__).resolve().parents[1]/'dist'/'apk'/downloads[self.path]
                if not file.is_file():raise ApiError(404,'설치 파일을 준비 중이에요')
                self.send_response(200);self.send_header('Content-Type','application/vnd.android.package-archive');self.send_header('Content-Length',str(file.stat().st_size));self.send_header('Cache-Control','no-store');self.send_header('Content-Disposition','attachment; filename="'+downloads[self.path]+'"');self.end_headers()
                try:
                    with file.open('rb') as f:
                        while chunk:=f.read(65536):self.wfile.write(chunk)
                except (BrokenPipeError,ConnectionResetError):pass
                return
            if self.path == "/health":
                self.reply(200, {"service": "family-care-relay", "version": 2, "maps": "tmap-proxy", "externalConnections":True, **({"externalProbe":self.server.external_probe} if getattr(self.server,"external_only",False) else {})})
            elif self.path in names:
                name, mime = names[self.path]
                self.reply(200, (Path(__file__).parent / "web" / name).read_bytes(), mime + ("; charset=utf-8" if mime.startswith("text/") else ""))
            else:
                raise ApiError(404, "없는 페이지예요")
        except ApiError as e:
            self.reply(e.status, {"error": e.message})

    def do_POST(self):
        try:
            # Consume only bounded bodies before a rejection, so Windows does not reset
            # the connection with unread bytes and hide the intended 403/413 response.
            self.connection.settimeout(8)
            length=int(self.headers.get("Content-Length","0"))
            if not 0<length<=350000:raise ApiError(413,"요청 크기를 확인하세요")
            raw_body=self.rfile.read(length)
            if len(raw_body)!=length:raise ApiError(400,"요청 내용을 다시 확인하세요")
            self.guard_host()
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                raise ApiError(415, "JSON 요청만 허용돼요")
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= (350000 if self.path.endswith("/memories") else 200000 if self.path.endswith("/sync") else 65000 if self.path.endswith("/music-frame") else 16384):
                raise ApiError(413, "요청 크기를 확인하세요")
            body = json.loads(raw_body, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            if not isinstance(body, dict):
                raise ApiError(400, "잘못된 요청이에요")
            if self.path == "/api/local-tunnel":
                if getattr(self.server,'external_only',False) or self.client_address[0] not in ('127.0.0.1','::1') or self.headers.get('Host') not in (f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}'):
                    raise ApiError(403,'노트북 시연 보조 화면에서만 변경할 수 있어요')
                expected=getattr(self.server,'control_token','')
                if not expected or not secrets.compare_digest(self.headers.get('X-Relay-Control',''),expected):raise ApiError(403,'외부 연결 관리 권한을 확인하세요')
                action=body.get('action')
                if action not in ('start','stop','status'):raise ApiError(400,'지원하지 않는 외부 연결 동작이에요')
                try:result=getattr(self.server.tunnel,action)()
                except ValueError as e:raise ApiError(409,str(e)) from None
            elif self.path == "/api/sessions":
                # Only the locally attached emulator may create sessions.
                if getattr(self.server,"external_only",False) or self.client_address[0] not in ("127.0.0.1", "::1"):
                    raise ApiError(403, "차량에서 연결을 시작해 주세요")
                result = self.server.relay.create()
            else:
                parts = self.path.split("/")
                if len(parts) != 5 or parts[1:3] != ["api", "sessions"]:
                    raise ApiError(404, "없는 기능이에요")
                auth = self.headers.get("Authorization", "")
                token=auth.removeprefix("Bearer ")
                if getattr(self.server,'external_only',False):
                    with self.server.relay.lock:
                        session=self.server.relay.sessions.get(parts[3])
                        if not session or self.server.relay.clock()>=session['expires']:raise ApiError(410,'연결이 끝났어요. 새 QR로 연결하세요')
                        role,_=delegation.auth(session,token)
                        if role not in ('owner','family','guest','observer') or not session['permissions'].get(role,True):raise ApiError(403,'유효한 휴대폰 연결이 필요해요')
                result = self.server.relay.call(parts[3], token, parts[4], body)
                if getattr(self.server,'external_only',False) and isinstance(result,dict) and result.get('invites'):
                    result['invites']={role:self.server.external_origin+'/join#'+urlsplit(link).fragment for role,link in result['invites'].items()}

            self.reply(200, result)
        except ApiError as e:
            self.reply(e.status, {"error": e.message})
        except (ValueError, TypeError, KeyError):
            self.reply(400, {"error": "입력 내용을 확인하세요"})


class Server(ThreadingHTTPServer):
    def service_actions(self):
        self.relay.sweep()


def serve(host="127.0.0.1", port=8765, public_url=None):
    server = Server((host, port), Handler)
    server.daemon_threads = True
    server.relay = Relay(public_url or f"http://127.0.0.1:{port}")
    server.control_token=secrets.token_urlsafe(32)
    server.tunnel=TunnelService(server.relay,Server,Handler)
    control=Path(__file__).resolve().parents[1]/'dist/demo/relay-control.json'
    control.parent.mkdir(parents=True,exist_ok=True)
    temp=control.with_suffix('.tmp')
    temp.write_text(json.dumps(dict(token=server.control_token,pid=os.getpid(),port=port)),'utf-8');temp.replace(control)
    print(f"WithME relay: {server.relay.public_url} (memory only, Ctrl+C to stop)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.tunnel.close()
        server.server_close()
        try:control.unlink()
        except FileNotFoundError:pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--public-url")
    args = parser.parse_args()
    if args.host != "127.0.0.1" and not args.public_url:
        parser.error("LAN mode needs --public-url http://YOUR_PC_IP:8765; trusted Wi-Fi demo only")
    serve(args.host, args.port, args.public_url)

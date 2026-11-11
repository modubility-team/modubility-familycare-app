"""Local emulator rehearsal controller. Uses ADB + Pleos Virtual Console, never a physical car."""
from __future__ import annotations
import argparse,bisect,json,math,secrets,subprocess,threading,time,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.request import Request,urlopen
from urllib.parse import urlencode,urlsplit
from tablet_bridge import ExternalGps,TabletBridge
from tunnel_client import TunnelClient,external_links,qr_image

BASE=[127.03636666,37.48544722]
PACKAGE='com.familycare.navigation'

def selected_point(value):
    if not isinstance(value,list) or len(value)!=2 or any(type(x) not in (float,int) or not math.isfinite(x) for x in value):
        raise ValueError('지도에서 위치를 선택하세요')
    lon,lat=value
    if not -180<=lon<=180 or not -85<=lat<=85:raise ValueError('좌표 범위를 확인하세요')
    return [float(lon),float(lat)]

def at_position(state,point):
    fix=state.get('position')
    return bool(state.get('gpsReady') and isinstance(fix,list) and len(fix)==2 and distance(fix,point)<20)
def distance(a,b):
    x,y=map(math.radians,(a[1],b[1]));d=math.sin((y-x)/2)**2+math.cos(x)*math.cos(y)*math.sin(math.radians(b[0]-a[0])/2)**2
    return 12742000*math.asin(math.sqrt(min(1,d)))
def cumulative(points):
    values=[0.0]
    for a,b in zip(points,points[1:]):values.append(values[-1]+distance(a,b))
    return values
def along(points,values,meters):
    if meters>=values[-1]:return points[-1]
    i=max(0,bisect.bisect_right(values,meters)-1);span=values[i+1]-values[i]
    t=0 if span<=0 else (meters-values[i])/span
    return [points[i][j]+t*(points[i+1][j]-points[i][j]) for j in (0,1)]

def connection_health(state, errors, now_ms):
    at=state.get('at')
    fresh=isinstance(at,(int,float)) and 0<=now_ms-at<6000 and errors<4
    return dict(vehicle='응답 정상' if fresh else '앱 응답 중단 · 시연 다시 실행',
                gps=('수신 정상' if state.get('gpsReady') else '신호 대기') if fresh else '미확인',
                family=('연결됨' if state.get('sharing') else '새 QR 연결 필요') if fresh else '미확인',
                fresh=fresh)

class Demo:
    def __init__(self,adb,serial):
        if not serial.startswith('emulator-'):raise ValueError('Physical devices are not supported')
        self.adb,self.serial=adb,serial
        hardware=self.command('shell','getprop','ro.hardware').decode().strip()
        if not any(x in hardware for x in ('ranchu','goldfish')):raise ValueError('An Android emulator is required')
        # Route the laptop microphone to the emulator; the app opens it only on a tap.
        try:self.command('emu','avd','hostmicon')
        except RuntimeError:pass
        self.user=self.command('shell','am','get-current-user').decode().strip()
        if not self.user.isdigit():raise ValueError('Android user is not ready')
        self.state={};self.message='차량 앱 연결 중';self.mode='대기';self.point=BASE[:];self.origin=BASE[:]
        self.external_gps=ExternalGps();self.location_enabled=None
        self.location_available(True)
        try:
            saved=json.loads(self.command('exec-out','run-as',PACKAGE,'--user',self.user,'cat','files/live-demo-state.json'))
            self.point=selected_point(saved.get('position'));self.origin=self.point[:]
        except Exception:pass
        self.navigation_start_id=""
        self.navigation_route_id=""
        self.route=[];self.lengths=[];self.progress=0.;self.kph=80.;self.busy=False
        self.lock=threading.RLock();self.command_lock=threading.Lock();self.action_lock=threading.Lock();self.stop=threading.Event()
        self.token=secrets.token_urlsafe(32);self.last_browser=time.monotonic();self.errors=0
        self.tunnel=TunnelClient();self.tunnel_state=dict(state='off',active=False,url='',message='외부 연결을 켜면 다른 네트워크에서 접속할 수 있어요')
        self.client='family-care-demo-'+uuid.uuid4().hex;self.qr={}
        original=self.vehicle('/vehicle/state')
        self.original_power=original.get('hvac',{}).get('power_on',False)
        self.original_temp=original.get('hvac',{}).get('driver',{}).get('temp_c',22)
    def command(self,*args,data=None):
        r=subprocess.run([self.adb,'-s',self.serial,*args],input=data,capture_output=True,timeout=12)
        if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:240])
        return r.stdout
    def location_available(self,enabled):
        if self.location_enabled is not enabled:
            self.command('shell','cmd','location','set-location-enabled',str(enabled).lower(),'--user',self.user)
            self.location_enabled=enabled
    def vehicle(self,path,method='GET',**params):
        if params:path+='?'+urlencode(params)
        with urlopen(Request('http://127.0.0.1:8088'+path,method=method),timeout=4) as r:
            raw=r.read();return json.loads(raw) if raw else {}
    def park(self):
        with self.lock:
            self.mode='정차';self.vehicle('/vehicle/controls','PUT',speed=0,accelerator=0,brake=0,steering=0)
            self.vehicle('/vehicle/gear','PUT',gear='Park')
    def app(self,action,timeout=40,**params):
        with self.command_lock:
            ident=uuid.uuid4().hex;body=json.dumps(dict(params,id=ident,action=action)).encode()
            self.command('shell',f"run-as {PACKAGE} --user {self.user} sh -c 'cat > files/live-demo-command.tmp && mv files/live-demo-command.tmp files/live-demo-command.json'",data=body)
            if action=='stop-session':return {}
            deadline=time.monotonic()+timeout
            while time.monotonic()<deadline:
                state=self.state
                if state.get('id')==ident and not state.get('pending'):
                    if not state.get('ok'):raise RuntimeError(state.get('reply','시연 동작 실패'))
                    return state
                if self.stop.wait(.2):break
            raise RuntimeError('차량 앱 응답 대기 시간이 지났어요. 앱이 켜져 있는지 확인하세요.')
    def relay_call(self,action,body,role='family'):
        if not connection_health(self.state,self.errors,time.time()*1000)['fresh']:raise ValueError('차량 연결을 확인하세요')
        link=self.state.get('links',{}).get(role,'')
        parts=urlsplit(link).fragment.split('.',1)
        if len(parts)!=2:raise ValueError('먼저 차량에서 가족 연결을 시작하세요')
        req=Request(f'http://127.0.0.1:8765/api/sessions/{parts[0]}/{action}',
            data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+parts[1]})
        from urllib.error import HTTPError
        try:
            with urlopen(req,timeout=45) as response:return json.loads(response.read(4_000_001))
        except HTTPError as error:
            try:message=json.loads(error.read(4000)).get('error','요청을 처리하지 못했어요')
            except Exception:message='서버 연결을 확인하세요'
            raise ValueError(message) from None
    def wait_for(self,predicate,timeout=20):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if predicate(self.state):return
            if self.stop.wait(.2):break
        raise RuntimeError('차량 상태를 확인하지 못했어요. 정차·GPS·앱 화면을 확인하세요.')
    def wait_parked(self):
        # DDS and the open Virtual Console can finish earlier speed writes after the P request.
        # Require a continuously stopped state before announcing that approvals are available.
        end=time.monotonic()+25;stable=None
        while time.monotonic()<end:
            now=time.monotonic()
            if self.state.get('parked'):
                if stable is None:stable=now
                if now-stable>=3:return
            else:stable=None
            if self.stop.wait(.2):break
        raise RuntimeError('정차 신호가 안정되지 않았어요. 다른 가상 차량 콘솔의 조작을 멈춰 주세요.')
    def launch_session(self):
        # Explicit recovery only: the two-hour rehearsal and sharing limits stay intact.
        self.command('shell','am','force-stop','--user',self.user,PACKAGE)
        self.state={};self.qr={};self.errors=0
        log=Path(__file__).resolve().parents[1]/'dist'/'demo'/'session-reconnect.log'
        log.parent.mkdir(parents=True,exist_ok=True)
        with log.open('ab') as output:
            subprocess.Popen([self.adb,'-s',self.serial,'shell','am','instrument','--user',self.user,
                '-w','-r','-e','liveDemo','true','-e','class','com.familycare.navigation.LiveDemoSessionTest',
                PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=output,
                creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    def act(self,action,body):
        if not self.action_lock.acquire(blocking=False):raise RuntimeError('앞선 준비가 끝난 뒤 눌러 주세요')
        self.busy=True
        try:
            if getattr(self,"external_gps",None) and self.external_gps.enabled:
                if action not in ("pause","shutdown"):raise ValueError("탭에서 GPS 사용을 중지한 뒤 시연 위치·자동 주행을 변경하세요")
                self.external_gps.stop()
            if action=='reconnect':
                self.message='차량 앱 연결을 복구하는 중…'
                self.park()
                if not connection_health(self.state,self.errors,time.time()*1000)['fresh']:
                    self.launch_session()
                self.wait_for(lambda s:connection_health(s,self.errors,time.time()*1000)['fresh'] and s.get('parked') and s.get('gpsReady'),timeout=45)
                self.app('prepare')
                self.wait_for(lambda s:s.get('sharing') and bool(s.get('links',{}).get('family')))
                with self.lock:self.origin=self.point[:];self.progress=0;self.route=[];self.lengths=[]
                self.message='차량 연결 복구 완료 · 검색할 수 있어요. 휴대폰은 새 QR로 연결하세요.'
            elif action=='prepare':
                self.park()
                with self.lock:self.point=self.origin[:];self.progress=0;self.route=[];self.lengths=[]
                self.vehicle('/vehicle/hvac','PUT',power='true')
                self.command('shell','am','start','--user',self.user,'-n',PACKAGE+'/.MainActivity')
                self.wait_for(lambda s:s.get('parked') and s.get('gpsReady'))
                self.app('prepare');self.wait_for(lambda s:s.get('sharing'))
                self.message='발표 시작 준비 완료 · 가족 연결 QR을 확인하세요'
            elif action=='position':
                point=selected_point(body.get('point'))
                self.park();self.wait_parked();self.app('relocate')
                with self.lock:self.point=point;self.origin=point[:];self.route=[];self.lengths=[];self.progress=0
                self.wait_for(lambda s:at_position(s,point))
                self.message='선택한 현재 위치가 차량 GPS에 반영됐어요 · 목적지를 선택하세요'
            elif action=='plan':
                target=selected_point(body.get('point'))
                name=body.get('name','지도에서 선택한 목적지')
                if not isinstance(name,str) or not 1<=len(name.strip())<=120:raise ValueError('목적지 이름을 확인하세요')
                self.park();self.wait_parked();self.wait_for(lambda s:at_position(s,self.point))
                self.app('plan',point=target,name=name.strip());self.message='TMAP 경로 준비 완료 · GPS 주행 시작을 누르세요'
            elif action=='start':
                requested=float(body.get('speed',80))
                if requested not in (20,40,80,120):raise ValueError('지원하지 않는 재생 속도')
                self.wait_for(lambda s:s.get('gpsReady') and bool(s.get('route')))
                self.app('start');self.wait_for(lambda s:s.get('active'))
                points=self.state['route']
                with self.lock:
                    if points!=self.route:self.route=points;self.lengths=cumulative(points);self.progress=0
                    self.kph=requested;self.vehicle('/vehicle/gear','PUT',gear='Drive');self.mode='자동 주행'
                self.message='GPS 자동 주행 중 · 휴대폰에서 휴식 요청을 보내 보세요'
            elif action=='pause':
                self.park();self.app('pause');self.wait_parked();self.message='P단 정차 · 차량에서 요청을 승인할 수 있어요'
            elif action=='finish':
                self.park();self.wait_for(lambda s:s.get('parked'));self.app('finish');self.message='이동 종료 · 차량에서 뒷좌석 확인 알림을 확인하세요'
            elif action=='shutdown':
                self.park();self.wait_for(lambda s:s.get('parked'));self.app('stop-session',timeout=4)
                self.stop.set()
            else:raise ValueError('지원하지 않는 시연 동작')
        finally:self.busy=False;self.action_lock.release()
    def poll(self):
        while not self.stop.is_set():
            try:
                self.vehicle('/browser/session','POST',client=self.client)
                raw=self.command('exec-out','run-as',PACKAGE,'--user',self.user,'cat','files/live-demo-state.json')
                state=json.loads(raw)
                if abs(time.time()*1000-state['at'])>6000:raise RuntimeError('차량 시연 세션이 중단됐어요')
                for role,link in state.get('links',{}).items():
                    if role in ('owner','family') and self.qr.get(role,('',b''))[0]!=link:
                        png=self.command('exec-out','run-as',PACKAGE,'--user',self.user,'cat',f'files/live-demo-qr-{role}.png')
                        if png.startswith(b'\x89PNG'):self.qr[role]=(link,png)
                self.state=state;self.errors=0
                self.receive_navigation(state)
                self.qr={role:pair for role,pair in self.qr.items() if state.get('sharing') and role in state.get('links',{})}
            except Exception as error:
                self.errors+=1
                if self.errors>=4:
                    self.message='차량 시연 연결이 종료되었거나 응답이 없어요. 차량 연결 복구를 눌러 주세요.'
                    try:self.park()
                    except Exception:pass
            try:self.tunnel_state=self.tunnel.call('status')
            except Exception:self.tunnel_state=dict(state='error',active=False,url='',message='외부 연결 관리 서버를 확인해 주세요. 시연 프로그램을 다시 켜면 복구할 수 있어요')
            self.stop.wait(1)
    def receive_navigation(self,state):
        ident=state.get('navigationStartId')
        points=state.get('route')
        if not state.get('active') or not ident or ident==self.navigation_start_id and state.get("navigationRouteId")==getattr(self,"navigation_route_id",None) or not isinstance(points,list) or len(points)<2:return
        try:
            destination=selected_point(state.get('destinationPoint'))
            route=[selected_point(p) for p in points]
        except ValueError:return
        with self.lock:
            self.navigation_start_id=ident;self.navigation_route_id=state.get("navigationRouteId")
            self.route=route;self.lengths=cumulative(route)
            progress=state.get('routeProgress',0)
            self.progress=min(self.lengths[-1],max(0,float(progress))) if type(progress) in (float,int) and math.isfinite(progress) else 0
            self.message='차량 내비의 목적지와 경로를 받았어요 · GPS 주행 시작을 누르면 이 경로로 이동합니다.'
    def gps(self):
        previous=time.monotonic();controls_at=0.;last_speed=None;external_sequence=-1
        while not self.stop.is_set():
            try:
                moving=False;now=time.monotonic();dt=min(now-previous,2);previous=now
                with self.lock:
                    external=self.external_gps.enabled
                    if self.external_gps.enabled:
                        fix=self.external_gps.current(now)
                        if fix:
                            self.location_available(True)
                            self.mode='탭 GPS';self.message='탭의 실제 GPS 위치를 차량 내비에 전달하고 있어요'
                            self.point=[fix['longitude'],fix['latitude']]
                            if (self.external_gps.generation,self.external_gps.sequence)!=external_sequence:
                                external_sequence=(self.external_gps.generation,self.external_gps.sequence)
                                self.command('emu','geo','fix',f"{self.point[0]:.8f}",f"{self.point[1]:.8f}",'0','8',f"{fix['speed']*3.6/1.852:.3f}")
                            if now-controls_at>=1:
                                self.vehicle('/vehicle/controls','PUT',speed=round(fix['speed']*3.6),accelerator=0,brake=0)
                                self.vehicle('/vehicle/gear','PUT',gear='Drive' if fix['speed']>0.8 else 'Park');controls_at=now
                        else:
                            self.location_available(False)
                            self.mode='탭 GPS 대기';self.message='탭 GPS 신호를 기다리고 있어요 · 위치를 자동으로 움직이지 않습니다'
                            if now-controls_at>=1:
                                self.vehicle('/vehicle/controls','PUT',speed=0,accelerator=0,brake=0)
                                self.vehicle('/vehicle/gear','PUT',gear='Neutral');controls_at=now
                if external:
                    self.stop.wait(.2);continue
                with self.lock:
                    external_sequence=-1
                    self.location_available(True)
                    moving=self.mode=='자동 주행' 
                    if moving and self.state.get('arrived'):
                        self.park();moving=False;self.mode='도착';self.message='도착 확인 완료 · 이동 종료 장면을 진행하세요'
                    if moving and (now-self.last_browser>20 or not self.state.get('active') or self.errors>=4):
                        self.park();moving=False;self.message='안내 또는 시연 패널 연결이 멈춰 정차했어요'
                    if moving:
                        points=self.state.get('route',[])
                        if points and points!=self.route:self.route=points;self.lengths=cumulative(points);self.progress=0
                        self.progress=min(self.lengths[-1],self.progress+dt*self.kph/3.6)
                        self.point=along(self.route,self.lengths,self.progress)
                        if self.progress>=self.lengths[-1]:
                            self.park();self.mode='도착 대기';moving=False;self.message='목적지 도착 판정 대기 · 자동으로 P단 정차'
                    p=self.point[:];speed=self.kph if moving else 0
                    if speed!=last_speed or now-controls_at>=1:
                        self.vehicle('/vehicle/controls','PUT',speed=int(speed),accelerator=0,brake=0);controls_at=now;last_speed=speed
                self.command('emu','geo','fix',f'{p[0]:.8f}',f'{p[1]:.8f}','0','8',f'{speed/1.852:.3f}')
                if self.state.get('arrived') and self.mode=='도착 대기':self.mode='도착';self.message='도착 확인 완료 · 이동 종료 장면을 진행하세요'
            except Exception as error:
                self.message='GPS 입력 중단: '+str(error)[:160]
                try:self.park()
                except Exception:pass
            self.stop.wait(max(.02,(.2 if moving else 1)-(time.monotonic()-previous)))
    def public(self):
        self.last_browser=time.monotonic()
        state={k:v for k,v in self.state.items() if k not in ('route','id')}
        route=self.state.get('route',[])
        stride=max(1,math.ceil(len(route)/400))
        state['navigationRoute']=route[::stride]+([route[-1]] if route and (len(route)-1)%stride else [])
        health=connection_health(self.state,self.errors,time.time()*1000)
        if not health['fresh'] or not state.get('sharing'):
            state['links']={}
            self.qr={}
        state.update(gpsSource='tablet' if self.external_gps.enabled else 'rehearsal',tabletGpsFresh=self.external_gps.current(time.monotonic()) is not None,mode=self.mode,messagePanel=self.message,busy=self.busy,speed=(self.external_gps.current(time.monotonic()) or {}).get('speed',0)*3.6 if self.external_gps.enabled else self.kph if self.mode=='자동 주행' else 0,emulatorPosition=self.point[:],origin=self.origin[:],
                     progress=self.progress,total=self.lengths[-1] if self.lengths else 0,hasRoute=health['fresh'] and bool(self.state.get('route')),connected=health['fresh'],health=health)
        state['externalConnection']=getattr(self,'tunnel_state',dict(state='off',active=False,url=''))
        state['externalLinks']=external_links(state.get('links',{}),state['externalConnection'],health['fresh'] and state.get('sharing',False))
        return state

def serve(demo,port):
    tablet=TabletBridge(demo);tablet.serve()
    page=Path(__file__).with_name('live_demo.html').read_text(encoding='utf-8').replace('__TOKEN__',demo.token)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def allowed(self):return self.headers.get('Host') in (f'127.0.0.1:{port}',f'localhost:{port}')
        def reply(self,code,body,kind='application/json; charset=utf-8'):
            raw=body if isinstance(body,bytes) else (body if isinstance(body,str) else json.dumps(body,ensure_ascii=False)).encode()
            self.send_response(code);self.send_header('Content-Type',kind);self.send_header('Cache-Control','no-store');self.send_header('X-Frame-Options','DENY');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        def do_GET(self):
            if not self.allowed():return self.reply(403,{'error':'Host blocked'})
            path=urlsplit(self.path).path
            if path=='/':return self.reply(200,page,'text/html; charset=utf-8')
            if path=='/api/state':return self.reply(200,demo.public())
            if path in ('/external-qr/owner.png','/external-qr/family.png'):
                link=demo.public().get('externalLinks',{}).get(path.split('/')[-1].split('.')[0])
                return self.reply(200,qr_image(link),'image/png') if link else self.reply(404,{'error':'외부 연결 QR 준비 중'})
            if path=='/tablet-info':return self.reply(200,tablet.info())
            if path=='/tablet-qr.png':return self.reply(200,tablet.qr(),'image/png')
            if path in ('/leaflet.js','/leaflet.css'):
                return self.reply(200,(Path(__file__).parent/'vendor'/'leaflet'/path[1:]).read_bytes(),'text/javascript' if path.endswith('.js') else 'text/css')
            if path=='/live_demo.js':return self.reply(200,Path(__file__).with_suffix('.js').read_bytes(),'text/javascript; charset=utf-8')
            if path in ('/qr/owner.png','/qr/family.png'):
                data=demo.qr.get(path.split('/')[-1].split('.')[0])
                return self.reply(200,data[1],'image/png') if data else self.reply(404,{'error':'QR 준비 중'})
            self.reply(404,{'error':'Not found'})
        def do_POST(self):
            if not self.allowed() or self.headers.get('X-Demo-Key')!=demo.token:return self.reply(403,{'error':'요청 권한을 확인하세요'})
            if self.headers.get('Origin') not in (None,f'http://127.0.0.1:{port}',f'http://localhost:{port}'):return self.reply(403,{'error':'Origin blocked'})
            if self.path not in ('/api/action','/api/search','/api/camera','/api/tunnel'):return self.reply(404,{'error':'Not found'})
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=4096:raise ValueError('Request too large')
                data=json.loads(self.rfile.read(size))
                if not isinstance(data,dict):raise ValueError('입력 내용을 확인하세요')
                if self.path=='/api/tunnel':
                    if data.get('action') not in ('start','stop'):raise ValueError('지원하지 않는 외부 연결 동작이에요')
                    if data['action']=='start' and not demo.public().get('connected'):raise ValueError('차량 연결을 먼저 준비하세요')
                    demo.tunnel_state=demo.tunnel.call(data['action']);return self.reply(200,demo.tunnel_state)
                if self.path=='/api/search':
                    body=dict(query=data.get('query'),center=selected_point(data.get('center')),provider='tmap',consent=True)
                    return self.reply(200,demo.relay_call('maps-search',body))
                if self.path=='/api/camera':
                    if data.get('action') not in ('camera-status','camera-configure','camera-frame','camera-check'):raise ValueError('지원하지 않는 카메라 요청')
                    return self.reply(200,demo.relay_call(data['action'],data,'owner'))
                demo.act(data['action'],data)
                self.reply(200,{'ok':True})
            except Exception as error:
                demo.message=str(error)[:200];self.reply(400,{'error':demo.message})
    server=ThreadingHTTPServer(('127.0.0.1',port),Handler);server.daemon_threads=True
    threading.Thread(target=demo.poll,daemon=True).start();threading.Thread(target=demo.gps,daemon=True).start()
    def stop_server():
        demo.stop.wait();server.shutdown()
    threading.Thread(target=stop_server,daemon=True).start()
    print(f'Demo panel: http://127.0.0.1:{port}/',flush=True)
    try:server.serve_forever(poll_interval=.5)
    finally:
        demo.stop.set()
        try:
            demo.location_available(True);demo.park();demo.vehicle('/vehicle/hvac','PUT',power=str(demo.original_power).lower(),driver_temp=demo.original_temp)
            demo.vehicle('/browser/session','POST',client=demo.client,closing=1)
        except Exception:pass
        try:demo.tunnel.call('stop')
        except Exception:pass
        tablet.close()
        server.server_close()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--serial',default='emulator-5554');parser.add_argument('--port',type=int,default=8770)
    args=parser.parse_args();serve(Demo(args.adb,args.serial),args.port)

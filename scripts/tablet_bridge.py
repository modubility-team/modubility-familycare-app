"""Authenticated LAN screen/touch bridge to one emulator only. No desktop capture."""
import json,math,secrets,socket,struct,subprocess,threading,time,ipaddress,io
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlsplit

class ExternalGps:
 def __init__(self):self.enabled=False;self.fix=None;self.received=0.;self.sequence=-1;self.generation=''
 def start(self):self.enabled=True;self.fix=None;self.sequence=-1;self.generation=secrets.token_hex(12);return self.generation
 def stop(self):self.enabled=False;self.fix=None;self.generation=''
 def accept(self,b,now):
  if not self.enabled or b.get('generation')!=self.generation:raise ValueError('GPS 사용을 먼저 시작하세요')
  for k in ('latitude','longitude','accuracy','ageMs','speed','sequence'):
   if type(b.get(k)) not in (int,float) or not math.isfinite(b[k]):raise ValueError('GPS 값을 확인하세요')
  if not -85<=b['latitude']<=85 or not -180<=b['longitude']<=180 or not 0<b['accuracy']<=50 or not 0<=b['ageMs']<=3000 or not 0<=b['speed']<=80 or b['sequence']<=self.sequence:raise ValueError('새로운 정확한 GPS 신호를 기다리고 있어요')
  if self.fix and now-self.received<5:
   a=self.fix;dl=math.radians(b['latitude']-a['latitude']);dn=math.radians(b['longitude']-a['longitude']);h=math.sin(dl/2)**2+math.cos(math.radians(a['latitude']))*math.cos(math.radians(b['latitude']))*math.sin(dn/2)**2
   distance=12742000*math.asin(min(1,math.sqrt(h)))
   if distance>max(180,(now-self.received)*90+2*b['accuracy']):raise ValueError('GPS 위치가 크게 바뀌었어요. 수신 상태를 확인하세요')
  self.fix={k:b[k] for k in ('latitude','longitude','accuracy','speed')};self.received=now-b['ageMs']/1000;self.sequence=b['sequence']
 def current(self,now):
  # A moving fix must stay fresh (5 s). A parked/indoor tablet may get Wi-Fi fixes only every few seconds,
  # so a stopped position (under 0.5 m/s) stays valid for 15 s before the car is told GPS is lost.
  if not self.enabled or not self.fix:return None
  return self.fix if 0<=now-self.received<=(5 if self.fix['speed']>=0.5 else 15) else None

def exact(sock,n):
 result=bytearray()
 while len(result)<n:
  part=sock.recv(n-len(result))
  if not part:raise ConnectionError('화면 연결이 종료됐어요')
  result.extend(part)
 return bytes(result)

class Mirror:
 def __init__(self,bridge):
  self.bridge=bridge;self.sid=secrets.token_urlsafe(18);self.video=None;self.control=None;self.process=None;self.width=0;self.height=0;self.lock=threading.Lock();self.pointers={};self.closed=False;self.forward=None;self.heartbeat=time.monotonic()
 def start(self):
  d=self.bridge.demo;scid=secrets.randbits(30);self.forward=None
  server=self.bridge.server_file
  if not server.is_file():raise RuntimeError('차량 화면 전송 도구를 찾을 수 없어요')
  d.command('push',str(server),'/data/local/tmp/family-care-mirror.jar')
  self.forward=int(d.command('forward','tcp:0',f'localabstract:scrcpy_{scid:08x}').decode().strip())
  args=[d.adb,'-s',d.serial,'shell','CLASSPATH=/data/local/tmp/family-care-mirror.jar','app_process','/','com.genymobile.scrcpy.Server','3.3.4',f'scid={scid:08x}','log_level=warn','tunnel_forward=true','audio=false','control=true','max_size=1600','max_fps=30','video_bit_rate=4000000','send_device_meta=false','send_dummy_byte=true','send_codec_meta=true','send_frame_meta=true','clipboard_autosync=false','stay_awake=false','power_on=false','cleanup=false']
  self.process=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  for _ in range(60):
   try:
    candidate=socket.create_connection(('127.0.0.1',self.forward),timeout=.5)
    if exact(candidate,1)!=b'\x00':raise OSError('Waiting for screen server')
    self.video=candidate;break
   except OSError:
    try:candidate.close()
    except (NameError,OSError):pass
    time.sleep(.1)
  if not self.video:raise RuntimeError('차량 영상을 시작하지 못했어요')
  self.control=socket.create_connection(('127.0.0.1',self.forward),timeout=5)
  self.video.settimeout(15);self.control.settimeout(5);self.control.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
  header=exact(self.video,12);codec,self.width,self.height=struct.unpack('>III',header)
  if codec!=0x68323634 or not 0<self.width<=4096 or not 0<self.height<=4096:raise RuntimeError('지원되지 않는 영상 형식이에요')
  self.video.settimeout(None);self.heartbeat=time.monotonic()
  return header
 def touch(self,b):
  if type(b.get('action')) is not int or b['action'] not in (0,1,2,3):raise ValueError('터치 입력을 확인하세요')
  for k in ('x','y','pointer'):
   if type(b.get(k)) is not int:raise ValueError('터치 좌표를 확인하세요')
  x,y,pid=b['x'],b['y'],b['pointer'];a=b['action']
  if not 0<=pid<10 or not 0<=x<self.width or not 0<=y<self.height:raise ValueError('화면 안을 눌러 주세요')
  with self.lock:
   if self.closed or not self.control:raise ValueError('차량 화면을 다시 연결하세요')
   if a in (1,3) and pid not in self.pointers:return
   if a==2 and pid not in self.pointers:return
   self.control.sendall(struct.pack('>BBQiiHHHII',2,a,pid,x,y,self.width,self.height,0 if a in (1,3) else 65535,0,0))
   if a in (1,3):self.pointers.pop(pid,None)
   else:self.pointers[pid]=(x,y)
 def close(self):
  with self.lock:
   if self.closed:return
   self.closed=True
   for pid,(x,y) in self.pointers.items():
    try:self.control.sendall(struct.pack('>BBQiiHHHII',2,1,pid,x,y,self.width,self.height,0,0,0))
    except OSError:pass
   self.pointers.clear()
  for s in (self.video,self.control):
   if s:
    try:s.shutdown(socket.SHUT_RDWR)
    except OSError:pass
    s.close()
  if self.process:self.process.terminate()
  if self.forward:
   try:self.bridge.demo.command('forward','--remove',f'tcp:{self.forward}')
   except Exception:pass

class TabletBridge:
 def __init__(self,demo,port=8771):
  self.demo=demo;self.port=port;self.token=secrets.token_urlsafe(32);self.expires=time.monotonic()+8*3600;self.mirror=None;self.lock=threading.RLock();self.server=None
  self.stop=threading.Event()
  self.server_file=Path(__file__).parent/'vendor/scrcpy-3.3.4/scrcpy-server'
 def origin(self):
  links=self.demo.state.get('links',{});host=urlsplit(links.get('owner','')).hostname
  if not host or host in ('127.0.0.1','localhost'):host=socket.gethostbyname(socket.gethostname())
  return f'http://{host}:{self.port}'
 def link(self):return self.origin()+'/join#'+self.token
 def info(self):return dict(link=self.link(),install=self.origin()+'/install.apk',connected=self.mirror is not None,expiresIn=max(0,int(self.expires-time.monotonic())))
 def qr(self):
  import qrcode
  result=io.BytesIO();qrcode.make(self.link()).save(result,format='PNG');return result.getvalue()
 def authenticated(self,value):return time.monotonic()<self.expires and secrets.compare_digest(value,'Bearer '+self.token)
 def current(self,b):
  if not isinstance(b,dict) or not self.mirror or self.mirror.closed or b.get('session')!=self.mirror.sid:raise ValueError('탭의 차량 화면을 먼저 연결하세요')
  return self.mirror
 def disconnect(self,mirror):
  with self.lock:
   mirror.close()
   if self.mirror is mirror:
    self.mirror=None
    # Keep source enabled but stale: never invent GPS or claim the car is parked.
    with self.demo.lock:self.demo.external_gps.fix=None
 def serve(self):
  bridge=self
  class Handler(BaseHTTPRequestHandler):
   protocol_version='HTTP/1.1'
   def log_message(self,*args):pass
   def reply(self,status,body,mime='application/json'):
    data=body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode()
    self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Frame-Options','DENY');self.end_headers();self.wfile.write(data)
   def guard(self,auth=True):
    host=self.headers.get('Host','').split(':')[0]
    if host not in (urlsplit(bridge.origin()).hostname,'127.0.0.1','localhost'):raise PermissionError('Host blocked')
    if auth and not bridge.authenticated(self.headers.get('Authorization','')):raise PermissionError('새 탭 연결 QR을 스캔하세요')
    if self.headers.get('Origin') is not None:raise PermissionError('태블릿 앱에서 연결하세요')
   def do_GET(self):
    mirror=None;headers_sent=False
    try:
     self.guard(auth=self.path not in ('/install.apk','/join'))
     if self.path=='/install.apk':
      apk=Path(__file__).parents[1]/'dist/apk/04-FamilyCare-tablet-debug.apk'
      if not apk.is_file():return self.reply(404,{'error':'설치 파일 준비 중'})
      return self.reply(200,apk.read_bytes(),'application/vnd.android.package-archive')
     if self.path=='/join':
      return self.reply(200,JOIN_PAGE.encode(),'text/html; charset=utf-8')
     if self.path=='/state':return self.reply(200,dict(connected=bridge.mirror is not None,gpsEnabled=bridge.demo.external_gps.enabled,gpsFresh=bridge.demo.external_gps.current(time.monotonic()) is not None,mode=bridge.demo.mode))
     if self.path!='/video':return self.reply(404,{'error':'없는 기능이에요'})
     with bridge.lock:
      if bridge.mirror:return self.reply(409,{'error':'다른 탭이 연결돼 있어요. 먼저 연결을 종료하세요'})
      mirror=Mirror(bridge);bridge.mirror=mirror
     header=mirror.start()
     self.connection.settimeout(8);self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.send_header('X-Session',mirror.sid);self.end_headers();self.close_connection=True
     headers_sent=True;self.wfile.write(header);self.wfile.flush()
     while bridge.authenticated(self.headers.get('Authorization','')):
      meta=exact(mirror.video,12);_,size=struct.unpack('>QI',meta)
      if not 0<size<=4_000_000:raise ValueError('영상 크기 초과')
      self.wfile.write(meta+exact(mirror.video,size));self.wfile.flush()
    except PermissionError as e:self.reply(403,{'error':str(e)})
    except (OSError,ValueError,RuntimeError):
     if not headers_sent:
      try:self.reply(503,{'error':'차량 화면 연결 상태를 확인하세요'})
      except OSError:pass
    finally:
     if mirror:bridge.disconnect(mirror)
   def do_POST(self):
    try:
     self.guard();size=int(self.headers.get('Content-Length','0'))
     if self.headers.get('Content-Type','').split(';')[0]!='application/json' or not 0<size<=4096:raise ValueError('입력 크기를 확인하세요')
     b=json.loads(self.rfile.read(size));mirror=bridge.current(b)
     if self.path=='/heartbeat':
      mirror.heartbeat=time.monotonic();return self.reply(200,dict(gpsFresh=bridge.demo.external_gps.current(time.monotonic()) is not None))
     if self.path=='/touch':mirror.touch(b);return self.reply(200,{'ok':True})
     with bridge.demo.lock:
      if self.path=='/gps/start':
       if bridge.demo.busy:raise ValueError('시연 준비가 끝난 뒤 시작하세요')
       bridge.demo.park();generation=bridge.demo.external_gps.start();bridge.demo.mode='탭 GPS 대기';return self.reply(200,{'generation':generation})
      if self.path=='/gps/stop':bridge.demo.external_gps.stop();bridge.demo.park();return self.reply(200,{'ok':True})
      if self.path=='/gps':bridge.demo.external_gps.accept(b,time.monotonic());return self.reply(200,{'ok':True})
     if self.path=='/disconnect':bridge.disconnect(mirror);return self.reply(200,{'ok':True})
     self.reply(404,{'error':'없는 기능이에요'})
    except PermissionError as e:self.reply(403,{'error':str(e)})
    except (ValueError,TypeError,KeyError) as e:self.reply(400,{'error':str(e)})
    except OSError:pass
  self.server=ThreadingHTTPServer(('0.0.0.0',self.port),Handler);self.server.daemon_threads=True
  threading.Thread(target=self.server.serve_forever,daemon=True).start()
  def watchdog():
   while not self.stop.wait(1):
    mirror=self.mirror
    if mirror and (time.monotonic()-mirror.heartbeat>12 or time.monotonic()>self.expires):self.disconnect(mirror)
  threading.Thread(target=watchdog,daemon=True).start()
 def close(self):
  self.stop.set()
  if self.mirror:self.disconnect(self.mirror)
  if self.server:self.server.shutdown();self.server.server_close()

JOIN_PAGE="""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>차량 화면 연결</title><style>body{font:18px system-ui;padding:24px;background:#f5f7fa;color:#123}a,button{display:block;background:#003568;color:white;padding:18px;margin:18px 0;border:0;border-radius:8px;font:inherit}textarea{width:100%;height:100px}</style><h1>WithME · 차량 화면</h1><p>노트북과 같은 Wi-Fi에서 사용하세요.</p><a href="/install.apk">1. 태블릿용 앱 다운로드</a><a id="open">2. 설치한 앱에서 연결</a><p>앱이 열리지 않으면 아래 연결 링크를 복사해서 앱에 붙여 넣으세요.</p><textarea id="link" readonly></textarea><p>GPS는 앱의 ‘탭 GPS 시작’을 누르고 정확한 위치를 허용한 뒤 사용합니다. 차량 음성 입력·소리는 노트북에서 동작합니다.</p><script>const link=location.href;document.getElementById('link').value=link;document.getElementById('open').href='familycare-tablet://connect?url='+encodeURIComponent(link);</script>"""

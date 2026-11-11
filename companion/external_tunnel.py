"""Optional Quick Tunnel for the phone relay only; LAN sessions and origins stay unchanged."""
from pathlib import Path
import ctypes,hashlib,json,os,re,secrets,subprocess,threading,time
from urllib.request import urlopen

EXPECTED='2493dbc168923bb3e59e5e7716f71deced92992eea9acd6e9d74f84ac7751343'
URL_RE=re.compile(r'https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com\b')

class WindowsChildJob:
    """Closing the relay process also closes the connector, including abrupt termination."""
    def __init__(self,process):
        self.handle=None
        if os.name!='nt':return
        from ctypes import wintypes as w
        class Basic(ctypes.Structure):
            _fields_=[('processTime',ctypes.c_int64),('jobTime',ctypes.c_int64),('flags',w.DWORD),('minWs',ctypes.c_size_t),('maxWs',ctypes.c_size_t),('active',w.DWORD),('affinity',ctypes.c_size_t),('priority',w.DWORD),('scheduling',w.DWORD)]
        class IO(ctypes.Structure):_fields_=[(n,ctypes.c_uint64) for n in ('ro','wo','oo','rt','wt','ot')]
        class Limits(ctypes.Structure):_fields_=[('basic',Basic),('io',IO),('processMemory',ctypes.c_size_t),('jobMemory',ctypes.c_size_t),('peakProcess',ctypes.c_size_t),('peakJob',ctypes.c_size_t)]
        k=ctypes.WinDLL('kernel32',use_last_error=True);self.kernel=k
        k.CreateJobObjectW.argtypes=[ctypes.c_void_p,w.LPCWSTR];k.CreateJobObjectW.restype=w.HANDLE
        k.SetInformationJobObject.argtypes=[w.HANDLE,ctypes.c_int,ctypes.c_void_p,w.DWORD];k.SetInformationJobObject.restype=w.BOOL
        k.AssignProcessToJobObject.argtypes=[w.HANDLE,w.HANDLE];k.AssignProcessToJobObject.restype=w.BOOL
        k.CloseHandle.argtypes=[w.HANDLE];k.CloseHandle.restype=w.BOOL
        self.handle=k.CreateJobObjectW(None,None);limits=Limits();limits.basic.flags=0x2000
        if not self.handle or not k.SetInformationJobObject(self.handle,9,ctypes.byref(limits),ctypes.sizeof(limits)) or not k.AssignProcessToJobObject(self.handle,int(process._handle)):
            self.close();raise RuntimeError('연결 도구의 자동 종료 보호를 설정하지 못했어요')
    def close(self):
        if self.handle:self.kernel.CloseHandle(self.handle);self.handle=None

class TunnelService:
    def __init__(self,relay,server_type,handler_type,root=None,port=8766,lease_seconds=60):
        self.relay,self.server_type,self.handler_type=relay,server_type,handler_type
        self.root=Path(root) if root else Path(__file__).resolve().parents[1]
        self.port=port;self.lease_seconds=lease_seconds;self.lock=threading.RLock()
        self.state='off';self.message='같은 Wi-Fi 연결을 사용할 수 있어요';self.url='';self.lease=time.monotonic()
        self.cancel=threading.Event();self.worker=None;self.process=None;self.gateway=None;self.job=None;self.generation=0
    def status(self,touch=True):
        with self.lock:
            if touch:self.lease=time.monotonic()
            return dict(state=self.state,active=self.state=='ready',url=self.url if self.state=='ready' else '',message=self.message)
    def start(self):
        with self.lock:
            self.lease=time.monotonic()
            if self.state in ('starting','ready','stopping'):return self.status()
            executable=self.root/'dist/tools/cloudflared.exe'
            if not executable.is_file() or hashlib.sha256(executable.read_bytes()).hexdigest()!=EXPECTED:
                raise ValueError('외부 연결 도구 설치가 필요해요. Setup-CloudflareTunnel.ps1을 실행하세요')
            self.generation+=1;generation=self.generation;self.cancel=threading.Event()
            self.state='starting';self.message='외부 연결 주소를 준비하고 있어요';self.url=''
            self.worker=threading.Thread(target=self._run,args=(generation,executable,self.cancel),daemon=True);self.worker.start()
            return self.status()
    def stop(self):
        with self.lock:
            self.cancel.set();self.url=''
            if self.state in ('starting','ready'):self.state='stopping';self.message='외부 연결을 종료하고 있어요'
            if self.gateway:self.gateway.external_enabled=False
            if self.process and self.process.poll() is None:self.process.terminate()
            return self.status()
    def close(self):
        self.stop()
        worker=self.worker
        if worker and worker is not threading.current_thread():worker.join(8)
    def _run(self,generation,executable,cancel):
        gateway=None;process=None;job=None;failure=''
        runtime=self.root/'dist/demo';runtime.mkdir(parents=True,exist_ok=True)
        log=runtime/'cloudflare-tunnel.log'
        try:
            gateway=self.server_type(('127.0.0.1',self.port),self.handler_type)
            gateway.daemon_threads=True;gateway.relay=self.relay;gateway.external_only=True
            gateway.external_origin='';gateway.external_enabled=True;gateway.external_probe=secrets.token_urlsafe(18)
            with self.lock:self.gateway=gateway
            threading.Thread(target=gateway.serve_forever,daemon=True).start()
            config=runtime/'cloudflare-empty.yml';config.write_text('{}\n','utf-8')
            log.write_text('','utf-8')
            with log.open('ab') as output:
                process=subprocess.Popen([str(executable),'--no-autoupdate','--config',str(config),'tunnel','--url',f'http://127.0.0.1:{gateway.server_port}','--protocol','http2','--metrics','127.0.0.1:0'],stdout=output,stderr=output,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            job=WindowsChildJob(process)
            with self.lock:self.process=process;self.job=job
            deadline=time.monotonic()+65;origin='';ready=False
            while not cancel.wait(.5) and time.monotonic()<deadline:
                if process.poll() is not None:raise RuntimeError('외부 연결 도구가 중단됐어요. 인터넷 연결을 확인하고 다시 시작해 주세요')
                if not origin:
                    match=URL_RE.search(log.read_text('utf-8',errors='replace'))
                    if match:origin=match.group();gateway.external_origin=origin
                if origin:
                    try:
                        with urlopen(origin+'/health',timeout=4) as response:proof=json.load(response)
                        if proof.get('externalProbe')==gateway.external_probe:ready=True;break
                    except Exception:pass
            if not ready and not cancel.is_set():raise RuntimeError('외부 주소 연결을 확인하지 못했어요. 인터넷 연결을 확인한 뒤 다시 눌러 주세요')
            if ready and not cancel.is_set():
                with self.lock:self.state='ready';self.url=origin;self.message='외부 연결 준비 완료 · LTE·5G용 QR을 사용하세요'
                while not cancel.wait(1):
                    if process.poll() is not None:raise RuntimeError('외부 연결이 끊겼어요. 다시 시작한 뒤 새 QR로 연결하세요')
                    if time.monotonic()-self.lease>self.lease_seconds:raise RuntimeError('시연 보조 프로그램이 종료되어 외부 연결도 종료했어요')
        except Exception as e:failure=str(e) if isinstance(e,RuntimeError) else '외부 연결을 시작하지 못했어요. 연결 도구와 포트 사용 상태를 확인하세요'
        finally:
            if gateway:gateway.external_enabled=False
            if process and process.poll() is None:
                process.terminate()
                try:process.wait(5)
                except subprocess.TimeoutExpired:process.kill();process.wait(3)
            if job:job.close()
            if gateway:gateway.shutdown();gateway.server_close()
            with self.lock:
                if self.generation==generation:
                    self.gateway=None;self.process=None;self.job=None;self.url=''
                    self.state='error' if failure and not cancel.is_set() else 'off'
                    self.message=failure if self.state=='error' else '외부 연결 종료 · 같은 Wi-Fi 연결은 계속 사용할 수 있어요'

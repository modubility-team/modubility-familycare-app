import ctypes,io,json,os,subprocess,sys,tempfile,time,unittest
from pathlib import Path
from unittest.mock import patch
import external_tunnel
from external_tunnel import TunnelService
from server import Relay,Server,Handler

@unittest.skipUnless(os.name=='nt','Windows process ownership')
class TunnelLifecycleTests(unittest.TestCase):
 def test_parent_exit_closes_connector(self):
  code="from external_tunnel import WindowsChildJob;import subprocess,sys,time;p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(120)']);job=WindowsChildJob(p);print(p.pid,flush=True);time.sleep(120)"
  parent=subprocess.Popen([sys.executable,'-c',code],cwd=str(Path(external_tunnel.__file__).parent),stdout=subprocess.PIPE,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
  k=ctypes.WinDLL('kernel32',use_last_error=True)
  k.OpenProcess.argtypes=[ctypes.c_ulong,ctypes.c_bool,ctypes.c_ulong];k.OpenProcess.restype=ctypes.c_void_p
  k.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_ulong];k.WaitForSingleObject.restype=ctypes.c_ulong
  k.CloseHandle.argtypes=[ctypes.c_void_p]
  handle=None
  try:
   child=int(parent.stdout.readline().strip());handle=k.OpenProcess(0x100000,False,child);self.assertTrue(handle)
   parent.terminate();parent.wait(5)
   self.assertEqual(k.WaitForSingleObject(handle,5000),0)
  finally:
   if parent.poll() is None:parent.kill();parent.wait(5)
   if handle:k.CloseHandle(handle)
   parent.stdout.close()

 def test_missing_controller_expires_tunnel_and_keeps_session(self):
  root=Path(external_tunnel.__file__).resolve().parents[1]
  if not (root/'dist/tools/cloudflared.exe').is_file():self.skipTest('connector not installed')
  relay=Relay('http://127.0.0.1:8765');s=relay.create();service=TunnelService(relay,Server,Handler,port=0,lease_seconds=.3)
  original_popen=subprocess.Popen
  def start_stub(*args,**kwargs):
   return original_popen([sys.executable,'-u','-c',"import time;print('https://lifecycle-check.trycloudflare.com');time.sleep(120)"],**kwargs)
  def health(*args,**kwargs):return io.BytesIO(json.dumps({'externalProbe':service.gateway.external_probe}).encode())
  with tempfile.TemporaryDirectory() as runtime,patch.object(external_tunnel.subprocess,'Popen',start_stub),patch.object(external_tunnel,'urlopen',health):
   # Keep fixture output separate from the active demo's connector log.
   original_root=service.root
   try:
    with patch.object(external_tunnel.Path,'read_bytes',return_value=(original_root/'dist/tools/cloudflared.exe').read_bytes()):
     service.root=Path(runtime);service.root.joinpath('dist/tools').mkdir(parents=True);service.root.joinpath('dist/tools/cloudflared.exe').touch();service.start()
    deadline=time.monotonic()+8
    while time.monotonic()<deadline and service.status(touch=False)['state'] not in ('ready','error'):time.sleep(.1)
    self.assertEqual(service.status(touch=False)['state'],'ready')
    child=service.process
    deadline=time.monotonic()+5
    while time.monotonic()<deadline and service.status(touch=False)['state']!='error':time.sleep(.1)
    self.assertEqual(service.status(touch=False)['state'],'error');self.assertIsNotNone(child.poll());self.assertIsNone(service.gateway)
    self.assertIn(s['id'],relay.sessions)
   finally:service.close()

if __name__=='__main__':unittest.main()

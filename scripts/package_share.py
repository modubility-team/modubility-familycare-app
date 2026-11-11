"""Build and verify the private Windows handoff ZIP before atomically replacing it."""
import argparse,datetime,hashlib,json,os,re,tempfile,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
EXCLUDED_DIRS={'.git','.gradle','.idea','.kotlin','build','local-sdk','__pycache__','.pytest_cache','node_modules','venv','signing'}
EXCLUDED_NAMES={'local.properties','keystore.properties','familycare.local.json','pleos-stt.local.properties','.env'}
EXCLUDED_SUFFIXES={'.log','.pyc','.pyo','.jks','.keystore','.apk','.aab','.p12','.pem','.bak','.tmp'}

def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--archive',required=True,type=Path)
 parser.add_argument('--include-api-settings',action='store_true',help='Include private service settings explicitly authorized for this handoff')
 args=parser.parse_args();archive=args.archive.expanduser().resolve()
 if archive.suffix.lower()!='.zip' or not archive.parent.is_dir():raise ValueError('Choose an existing destination directory and a .zip file')
 previous=digest(archive) if archive.exists() else None
 files={}
 def add(src,relative=None):
  src=Path(src)
  if not src.is_file() or src.is_symlink():raise FileNotFoundError(src)
  files[relative or 'team-project/'+src.relative_to(ROOT).as_posix()]=src
 for folder in ('android','companion','docs','scripts'):
  for current,dirs,names in os.walk(ROOT/folder):
   dirs[:]=[d for d in dirs if d not in EXCLUDED_DIRS and not (Path(current)/d).is_symlink()]
   for name in names:
    src=Path(current)/name
    if src.is_symlink() or name in EXCLUDED_NAMES or (name.startswith('.env.') and name!='.env.example') or src.suffix.lower() in EXCLUDED_SUFFIXES:continue
    if name.endswith('.local.json'):continue
    add(src)
 for name in ('.gitignore','AGENTS.md','README.md','FamilyCare.cmd','familycare.local.example.json','kakao.local.example.json','tmap.local.example.json'):
  if name=='AGENTS.md' and not (ROOT/name).exists():continue  # Not part of the public GitHub copy.
  add(ROOT/name)
 api_names=['tmap.local.json','kakao.local.json'] if args.include_api_settings else []
 if args.include_api_settings and (ROOT/'android/pleos-stt.local.properties').is_file():api_names.append('android/pleos-stt.local.properties')
 for name in api_names:add(ROOT/name)
 for name in ('01-FamilyCare-vehicle-debug.apk','02-FamilyCare-navigation-debug.apk','03-FamilyCare-care-debug.apk','04-FamilyCare-tablet-debug.apk'):add(ROOT/'dist/apk'/name)
 for name in ('app-debug.apk','app-debug-androidTest.apk'):add(ROOT/'dist/demo'/name)
 for name in ('onnx/model_quantized.onnx','tokenizer.json','model-manifest.json'):add(ROOT/'dist/voice-runtime'/name)
 tool=ROOT/'dist/tools/cloudflared.exe'
 if tool.exists():
  expected=re.search(r"EXPECTED='([a-f0-9]{64})'",(ROOT/'companion/external_tunnel.py').read_text('utf-8')).group(1)
  if digest(tool)!=expected:raise ValueError('Cloudflare connector checksum mismatch')
  add(tool)
 versions={name:re.search(r'versionName\s*=\s*"([^"]+)"',(ROOT/f'android/{module}/build.gradle.kts').read_text('utf-8')).group(1) for name,module in [('vehicle','app'),('phones','phone'),('tablet','tablet')]}
 created=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat(timespec='seconds')
 guide=(ROOT/'docs/SHARED-PACKAGE-SETUP.txt').read_text('utf-8-sig')
 guide='WithME(위드미) 전체 프로젝트 최신 전달본 · '+created+'\n차량 '+versions['vehicle']+' / 휴대폰 '+versions['phones']+' / 태블릿 '+versions['tablet']+'\n\n'+guide
 generated={'00_먼저읽기_설치실행방법.txt':guide.encode('utf-8-sig')}
 report='전달본 갱신: '+created+'\n\n이 ZIP은 원래 파일명을 유지하며 최신 내용으로 교체됩니다. 파일명에 있는 과거 날짜·버전 대신 이 갱신일과 PACKAGE-MANIFEST.json을 확인하세요.\n코드·설치용 APK·실행 도구·사용법을 포함합니다. Cloudflare 외부 연결 사용법은 사용안내 폴더를 보세요.\nAPK는 재패키징 실행 도구에서 Gradle 내보내기 작업을 먼저 수행합니다.\n압축 CRC와 전체 파일 SHA256 검사를 통과해야 기존 ZIP을 교체합니다.\n실행 중인 서버 상태·QR 토큰·개인 사진·PC별 SDK 경로는 포함하지 않습니다.\n받는 사람의 새 PC 설치와 실제 휴대폰 LTE 시험을 완료했다는 의미는 아닙니다.\n'
 if api_names:report+='사용자가 허용한 API 설정이 포함되어 있습니다. 지정된 팀원에게 직접 전달하는 용도입니다.\n'
 generated['00_최신업데이트정보.txt']=report.encode('utf-8-sig')
 launchers={'00_한번에켜기.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\Start-All.ps1" -Launcher "%~f0"\r\npause','01_FirstSetup.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\Setup-SharedComputer.ps1"\r\npause','02_StartDemo.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\Start-LiveDemo.ps1"\r\npause','03_StopAll.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\Stop-SharedDemo.ps1"\r\npause','04_OpenApks.cmd':'start "" explorer.exe "%~dp0team-project\\dist\\apk"','05_DeveloperMenu.cmd':'call "%~dp0team-project\\FamilyCare.cmd"','06_UpdateSharePackage.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\Update-SharePackage.ps1"\r\npause','07_CreateReleaseKey.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\New-ReleaseKey.ps1"\r\npause','08_BuildReleaseApk.cmd':'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0team-project\\scripts\\windows\\Build-ReleaseApk.ps1"\r\npause'}
 for name,body in launchers.items():generated[name]=('@echo off\r\nsetlocal\r\ncd /d "%~dp0"\r\n'+body+'\r\n').encode('ascii')
 for src in sorted((ROOT/'docs').glob('EXTERNAL-CONNECTIONS-*.txt')):add(src,'사용안내/'+src.name)
 for src in sorted(ROOT.parent.glob('[0-9][0-9]_*.txt')):
  if src.name[:2]>='51':add(src,'사용안내/'+src.name)
 top='FamilyCare_공유패키지/'
 manifest={'created':created,**versions,'apiSettingsIncluded':api_names,'gitHistoryIncluded':False,'files':[]}
 fd,temp_name=tempfile.mkstemp(prefix='.FamilyCare-package-',suffix='.tmp',dir=archive.parent);os.close(fd);temporary=Path(temp_name)
 try:
  with zipfile.ZipFile(temporary,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=5,allowZip64=True) as z:
   for name,src in sorted(files.items()):
    before=src.stat();sha=hashlib.sha256();size=0
    with src.open('rb') as input_file,z.open(top+name,'w',force_zip64=True) as output:
     while block:=input_file.read(1024*1024):output.write(block);sha.update(block);size+=len(block)
    after=src.stat()
    if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise RuntimeError('Source changed while packaging: '+name)
    manifest['files'].append({'path':name,'bytes':size,'sha256':sha.hexdigest()})
   for name,data in sorted(generated.items()):
    z.writestr(top+name,data);manifest['files'].append({'path':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
   z.writestr(top+'PACKAGE-MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode('utf-8'))
  with zipfile.ZipFile(temporary) as z:
   if z.testzip() is not None:raise RuntimeError('ZIP integrity check failed')
   for item in manifest['files']:
    with z.open(top+item['path']) as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
    if actual!=item['sha256']:raise RuntimeError('ZIP hash mismatch: '+item['path'])
   for name in api_names:
    if z.read(top+'team-project/'+name)!=(ROOT/name).read_bytes():raise RuntimeError('API setting snapshot changed')
  if (digest(archive) if archive.exists() else None)!=previous:raise RuntimeError('Destination changed while packaging; keeping it untouched')
  new_hash=digest(temporary);temporary.replace(archive)
  result={'archive':str(archive),'updated':created,'files':len(manifest['files'])+1,'bytes':archive.stat().st_size,'sha256':new_hash,'zipIntegrity':'passed','allFileHashes':'passed','apiSettingsIncluded':api_names}
  output=ROOT/'dist/share-package-result.json';output.parent.mkdir(exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
  print(json.dumps(result,ensure_ascii=False,indent=2))
 finally:
  if temporary.exists():temporary.unlink()

if __name__=='__main__':main()

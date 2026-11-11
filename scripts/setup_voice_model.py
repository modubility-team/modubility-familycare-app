from pathlib import Path
import sys,subprocess,urllib.request,hashlib,json
p=Path(__file__).resolve().parents[1]
if not (3,11)<=sys.version_info[:2]<=(3,13):raise RuntimeError('Use Python 3.11, 3.12 or 3.13 for the private voice runtime')
root=p/'dist/voice-runtime';root.mkdir(parents=True,exist_ok=True)
venv=root/'venv';python=venv/'Scripts/python.exe'
if not python.exists():subprocess.run([sys.executable,'-m','venv',str(venv)],check=True)
subprocess.run([str(python),'-m','pip','install','--disable-pip-version-check','onnxruntime==1.23.2','tokenizers==0.22.1','numpy==2.3.4'],check=True)
revision='761b726dd34fb83930e26aab4e9ac3899aa1fa78'
files={'onnx/model_quantized.onnx':'f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193','tokenizer.json':'0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39'}
for name,digest in files.items():
 target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
 if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()==digest:continue
 print('Downloading '+name,flush=True)
 with urllib.request.urlopen(f'https://huggingface.co/Xenova/multilingual-e5-small/resolve/{revision}/{name}?download=true',timeout=60) as response,target.with_suffix('.download').open('wb') as out:
  while chunk:=response.read(1024*1024):out.write(chunk)
 tmp=target.with_suffix('.download');assert hashlib.sha256(tmp.read_bytes()).hexdigest()==digest;tmp.replace(target)
(root/'model-manifest.json').write_text(json.dumps(dict(model='Xenova/multilingual-e5-small',original='intfloat/multilingual-e5-small',revision=revision,sha256=files),indent=2),'utf-8')
print('Local semantic model installed and hashes verified',flush=True)

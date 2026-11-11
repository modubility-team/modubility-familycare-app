from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import urlsplit
import io,json,re

class TunnelClient:
    def __init__(self,root=None):self.root=Path(root) if root else Path(__file__).resolve().parents[1]
    def call(self,action):
        config=json.loads((self.root/'dist/demo/relay-control.json').read_text('utf-8'))
        if config.get('port')!=8765:raise ValueError('연결 서버 주소를 확인해 주세요')
        req=Request('http://127.0.0.1:8765/api/local-tunnel',data=json.dumps(dict(action=action)).encode(),headers={'Content-Type':'application/json','X-Relay-Control':config['token']})
        try:
            with urlopen(req,timeout=8) as r:return json.load(r)
        except HTTPError as e:
            try:message=json.load(e).get('error','외부 연결을 확인해 주세요')
            except Exception:message='외부 연결을 확인해 주세요'
            raise ValueError(message) from None

def external_links(links,state,connected=True):
    if not connected or not state.get('active'):return {}
    origin=state.get('url','')
    if not re.fullmatch(r'https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com',origin):return {}
    result={}
    for role in ('owner','family'):
        fragment=urlsplit(links.get(role,'')).fragment
        if re.fullmatch(r'[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{43}',fragment):result[role]=origin+'/join#'+fragment
    return result

def qr_image(link):
    import qrcode
    out=io.BytesIO();qrcode.make(link).save(out,format='PNG');return out.getvalue()

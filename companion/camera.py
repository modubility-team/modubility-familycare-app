"""Explicit, session-only LAN IP Webcam snapshot transport. No recording or cloud upload."""
import base64
import ipaddress
import time
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError, URLError
from maps import NoRedirect


class CameraError(Exception):
    pass


def configuration(body):
    if body.get('consent') is not True:
        raise CameraError('소유자·가족과 카메라 화면 공유 안내를 확인하세요')
    value = body.get('url', '')
    if not isinstance(value, str) or len(value) > 200:
        raise CameraError('카메라 주소를 확인하세요')
    try:
        value = value.strip()
        if '://' not in value:
            value = 'http://' + value
        u = urlsplit(value)
        ip = ipaddress.IPv4Address(u.hostname or '')
        private = any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
        port = 8080 if u.port is None else u.port
        if not private or u.scheme != 'http' or not 1024 <= port <= 65535 or u.username is not None or u.password is not None or u.query or u.fragment or u.path not in ('', '/', '/shot.jpg'):
            raise ValueError()
    except ValueError:
        raise CameraError('같은 Wi-Fi의 http://192.168.x.x:8080 형식 주소를 입력하세요. 기본 포트는 8080입니다.') from None
    username, password = body.get('username', ''), body.get('password', '')
    if not all(isinstance(v, str) and len(v) <= 128 and '\n' not in v and '\r' not in v for v in (username, password)) or ':' in username:
        raise CameraError('카메라 로그인 정보를 확인하세요')
    if bool(username) != bool(password):
        raise CameraError('로그인 이름과 비밀번호를 함께 입력하세요')
    return dict(url=f'http://{ip}:{port}/shot.jpg', username=username, password=password)


def fetch_frame(config):
    headers = {'Accept': 'image/jpeg', 'User-Agent': 'FamilyCare/0.13 local-camera'}
    if config['username']:
        credentials = (config['username'] + ':' + config['password']).encode()
        headers['Authorization'] = 'Basic ' + base64.b64encode(credentials).decode()
    # Numeric RFC1918 host, fixed path, no system proxy, redirects or arbitrary headers.
    request = Request(config['url'], headers=headers)
    try:
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=3) as response:
            if response.status != 200 or response.headers.get_content_type() != 'image/jpeg':
                raise CameraError('JPEG 화면을 받지 못했어요. IP Webcam 서버를 확인하세요')
            data = response.read(1_000_001)
            if not 4 <= len(data) <= 1_000_000 or not data.startswith(b'\xff\xd8') or not data.endswith(b'\xff\xd9'):
                raise CameraError('화면 크기·형식을 확인하세요. 카메라 해상도를 720p 이하로 낮춰 주세요')
            return dict(image=base64.b64encode(data).decode(), capturedAt=int(time.time()*1000))
    except HTTPError as error:
        raise CameraError('카메라 로그인 정보를 확인하세요' if error.code in (401,403) else '카메라 화면 경로를 확인하세요. IP Webcam 서버 시작이 필요합니다') from None
    except (URLError, TimeoutError, OSError):
        raise CameraError('카메라 연결 끊김 · 같은 Wi-Fi, 주소와 서버 시작 상태를 확인하세요') from None

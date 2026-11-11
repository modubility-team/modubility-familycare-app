"""Kakao REST adapter. Credentials stay on this PC; responses are never cached.

All coordinates use [longitude, latitude]. Outbound hosts are fixed, redirects
are refused, and upstream response/error bodies are never logged or forwarded.
"""
import datetime
import json
import math
import threading
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler


class MapError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


def point(value):
    if not isinstance(value, list) or len(value) != 2:
        raise MapError(400, "위치 좌표를 확인하세요")
    if any(isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n) for n in value):
        raise MapError(400, "위치 좌표를 확인하세요")
    if not (-180 <= value[0] <= 180 and -85 <= value[1] <= 85):
        raise MapError(400, "위치 좌표 범위를 확인하세요")
    return value


def distance(a, b):
    x, y = math.radians(a[1]), math.radians(b[1])
    d = math.sin((y-x)/2)**2 + math.cos(x)*math.cos(y)*math.sin(math.radians(b[0]-a[0])/2)**2
    return 12742000 * math.asin(math.sqrt(min(1, d)))


def project(p, route):
    """Distance along polyline, and lateral distance; NOT drivable detour."""
    best, total = None, 0.
    scale = math.cos(math.radians(p[1])) * 111195
    for a, b in zip(route, route[1:]):
        ax, ay = (a[0]-p[0])*scale, (a[1]-p[1])*111195
        dx, dy = (b[0]-a[0])*scale, (b[1]-a[1])*111195
        square = dx*dx+dy*dy
        t = max(0., min(1., -(ax*dx+ay*dy)/square)) if square else 0.
        length = distance(a, b)
        candidate = (math.hypot(ax+t*dx, ay+t*dy), total+t*length)
        if best is None or candidate[0] < best[0]:
            best = candidate
        total += length
    return (best[1], best[0]) if best else (0., float('inf'))


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Maps:
    def __init__(self, config=None, transport=None, clock=time.monotonic):
        self.config = Path(config) if config else Path(__file__).resolve().parents[1] / 'kakao.local.json'
        self.transport = transport or self._http
        self.clock = clock
        self.lock = threading.Lock()
        self.day = None
        self.count = 0
        self.minute = []

    def settings(self):
        try:
            data = json.loads(self.config.read_text(encoding='utf-8-sig'))
            if not isinstance(data, dict):
                raise ValueError()
            key = data.get('rest_api_key', '').strip()
            mobility = data.get('mobility_rest_api_key', '').strip() or key
            # Validate syntax only; never represent this as verified API access.
            valid = lambda k: len(k) == 32 and all(c in '0123456789abcdefABCDEF' for c in k)
            enabled = data.get('enabled') is True
            limit = max(1, min(9000, int(data.get('max_requests_per_day', 500))))
            return (key if enabled and valid(key) else '', mobility if enabled and valid(mobility) else '', limit)
        except (OSError, ValueError, TypeError, AttributeError):
            return '', '', 500

    def status(self):
        local, mobility, limit = self.settings()
        return dict(searchConfigured=bool(local), routingConfigured=bool(mobility),
                    provider='Kakao Local / Kakao Mobility', dailyLimit=limit,
                    verification='설정 감지 · 실제 사용 권한은 첫 호출에서 확인', storage='요청 처리 중에만 사용')

    def _http(self, url, key):
        req = Request(url, headers={'Authorization': 'KakaoAK '+key,
            'Accept': 'application/json', 'User-Agent': 'FamilyCare/0.12'})
        with build_opener(NoRedirect()).open(req, timeout=12) as response:
            raw = response.read(4_000_001)
            if len(raw) > 4_000_000:
                raise MapError(502, '경로가 너무 길어요. 가까운 목적지를 선택하세요')
            return json.loads(raw)

    def get(self, host, path, params, mobility=False):
        local, route, limit = self.settings()
        key = route if mobility else local
        if not key:
            raise MapError(503, 'PC의 kakao.local.json에 REST API 키를 설정해 주세요')
        with self.lock:
            today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date()
            if self.day != today:
                self.day, self.count = today, 0
            now = self.clock()
            self.minute = [t for t in self.minute if now-t < 60]
            if self.count >= limit or len(self.minute) >= 30:
                raise MapError(429, 'API 호출 한도에 도달했어요. 잠시 후 다시 시도하세요')
            self.count += 1
            self.minute.append(now)
        try:
            return self.transport(host+path+'?'+urlencode(params), key)
        except HTTPError as error:
            messages = {401: '카카오 REST API 키를 확인하세요', 403: '카카오 앱의 API 사용 설정·허용 IP를 확인하세요',
                        429: '카카오 호출 한도를 초과했어요. 제공자 쿼터를 확인하세요',
                        400: '카카오가 검색 조건을 처리하지 못했어요. 장소를 바꿔 주세요'}
            if error.code == 403 and 'OPEN_MAP_AND_LOCAL' in error.read(4096).decode('utf-8', errors='replace'):
                raise MapError(403, '카카오 앱의 제품 설정 → 카카오맵 → 사용 설정을 ON으로 켜 주세요') from None
            raise MapError(error.code if error.code in messages else 502,
                           messages.get(error.code, '카카오 서비스 응답 오류 · 잠시 후 다시 시도하세요')) from None
        except (URLError, TimeoutError, OSError):
            raise MapError(504, '카카오 연결 시간이 지났어요. 인터넷 연결을 확인하세요') from None
        except (ValueError, KeyError, TypeError):
            raise MapError(502, '카카오 응답을 읽지 못했어요') from None

    def search(self, body):
        center = point(body.get('center'))
        query, facility = body.get('query', ''), body.get('facility', '')
        allowed = ('', '휴게소', '화장실', '기저귀 교환', '수유', '반려견 공원', '무장애 화장실')
        if not isinstance(query, str) or len(query) > 120 or facility not in allowed or not (query.strip() or facility):
            raise MapError(400, '검색할 장소 이름이나 시설을 선택하세요')
        route = body.get('route', [])
        if not isinstance(route, list) or len(route) > 250:
            raise MapError(400, '검색 경로가 너무 길어요')
        route = [point(p) for p in route]
        if len(route) == 1:
            raise MapError(400, '검색 경로를 확인하세요')
        # Up to three samples ahead; the client supplies only the remaining route.
        centers = [center]
        if facility and route:
            for goal in (10000, 25000):
                total = 0.
                for a, b in zip(route, route[1:]):
                    total += distance(a, b)
                    if total >= goal:
                        centers.append(b)
                        break
        places = {}
        for origin in centers:
            params = dict(query=' '.join(x for x in (query.strip(), facility) if x), x=origin[0], y=origin[1], size=15, sort='distance')
            if facility:
                params['radius'] = 10000
            data = self.get('https://dapi.kakao.com', '/v2/local/search/keyword.json', params)
            for p in data.get('documents', []):
                try:
                    location = point([float(p['x']), float(p['y'])])
                    name = str(p['place_name'])[:120]
                    detail = str(p.get('road_address_name') or p.get('address_name') or '')[:200]
                    ahead, offset = project(location, route) if route else (0., 0.)
                    # Do not suggest a facility already behind the current position.
                    origin_along = project(center, route)[0] if route else 0.
                    if facility and route and (ahead < origin_along-150 or offset > 5000):
                        continue
                    score = max(0., ahead-origin_along)+offset*3 if facility and route else distance(center, location)
                    if facility and route:
                        detail += f' · 경로 전방 {max(0., ahead-origin_along)/1000:.1f} km · 경로 옆 {offset/1000:.1f} km'
                    if facility:
                        detail += ' · 편의시설·진입 방향은 확인 필요'
                    places[str(p.get('id') or location)] = dict(name=name, longitude=location[0], latitude=location[1], detail=detail, score=score)
                except (KeyError, ValueError, TypeError, MapError):
                    continue
        if not places and not facility:
            data = self.get('https://dapi.kakao.com', '/v2/local/search/address.json', dict(query=query.strip(), size=8))
            for p in data.get('documents', []):
                try:
                    location = point([float(p['x']), float(p['y'])])
                    name = str(p['address_name'])[:120]
                    places[name] = dict(name=name, longitude=location[0], latitude=location[1], detail='주소 검색 결과 · 차량 진입 위치 확인', score=distance(center, location))
                except (KeyError, ValueError, TypeError, MapError):
                    continue
        ordered = sorted(places.values(), key=lambda p: p.pop('score'))[:12]
        return dict(places=ordered, provider='Kakao Local', routeAware=bool(facility and route),
                    notice='경로까지의 직선 거리 순위이며 실제 우회 시간·시설 보유 여부를 보장하지 않습니다.')

    def directions(self, body):
        start, end = point(body.get('start')), point(body.get('end'))
        via = point(body['via']) if body.get('via') is not None else None
        params = dict(origin=','.join(map(str, start)), destination=','.join(map(str, end)),
                      priority='RECOMMEND', alternatives='false', summary='false', road_details='false')
        if via:
            params['waypoints'] = ','.join(map(str, via))
        data = self.get('https://apis-navi.kakaomobility.com', '/v1/directions', params, mobility=True)
        try:
            result = normalize_route(data)
            coords = result['routes'][0]['geometry']['coordinates']
            if distance(start, coords[0]) > 300 or distance(end, coords[-1]) > 300 or (via and project(via, coords)[1] > 300):
                raise MapError(422, '선택한 위치 근처의 자동차 진입로를 찾지 못했어요')
            return result
        except MapError:
            raise
        except (KeyError, ValueError, TypeError, IndexError):
            raise MapError(502, '길안내 정보를 읽지 못했어요. 다시 검색하세요') from None

    def preview(self, body):
        point(body.get('start')); point(body.get('end')); point(body.get('via'))
        base = self.directions(dict(start=body['start'], end=body['end']))
        via = self.directions(body)
        before, after = base['routes'][0], via['routes'][0]
        return dict(extraSeconds=after['duration']-before['duration'], totalSeconds=after['duration'],
                    provider='Kakao Mobility', notice='현재 조회 조건의 예상치 · 운전자 승인 시 다시 계산합니다')


def normalize_route(data):
    """Reduce provider data to the existing app's geometry/turn contract."""
    route = next((r for r in data.get('routes', []) if r.get('result_code') == 0), None)
    if not route:
        raise MapError(422, '두 지점을 잇는 자동차 경로가 없어요')
    coords, legs, times = [], [], []
    for section in route['sections']:
        for road in section['roads']:
            values = road['vertexes']
            if len(values) % 2:
                raise ValueError('Invalid geometry')
            for i in range(0, len(values), 2):
                p = point(values[i:i+2])
                if not coords or coords[-1] != p:
                    coords.append(p)
            duration = float(road['duration'])
            if not math.isfinite(duration) or duration < 0:
                raise ValueError('Invalid duration')
            times.append([len(coords)-1, duration])
        steps = []
        for guide in section['guides']:
            text = str(guide['guidance'])[:180]
            modifier = 'uturn' if '유턴' in text else 'left' if ('좌회전' in text or '왼쪽' in text) else 'right' if ('우회전' in text or '오른쪽' in text) else 'straight'
            kind = 'depart' if guide.get('type') == 100 else 'arrive' if guide.get('type') == 101 else 'turn'
            steps.append(dict(name='', instruction=text, maneuver=dict(type=kind, modifier=modifier, location=point([guide['x'], guide['y']]))))
        legs.append(dict(steps=steps))
    duration = float(route['summary']['duration'])
    if not 2 <= len(coords) <= 70000 or not math.isfinite(duration) or duration <= 0:
        raise ValueError('Invalid route')
    return dict(code='Ok', provider='Kakao Mobility', generatedAt=int(time.time()*1000),
                routes=[dict(duration=duration, geometry=dict(coordinates=coords), legs=legs, segments=times)])

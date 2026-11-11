"""TMAP POI + automobile REST adapter. No Navigation SDK subscription required.

Fixed HTTPS origin, runtime SDK credentials, no response cache or raw error forwarding.
Quota here is a conservative process-local guard; the provider's Free quota is
authoritative and must also be selected in SK open API before using the key.
"""
import datetime
import json
import math
import re
import threading
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener
from maps import MapError, NoRedirect, point, distance, project


def side_of(p, route):
    """Where a place lies against a route: distance along it, distance from it, whether it is right of the
    travel direction, and whether it lies before the route's first point (behind the car)."""
    best, total = None, 0.
    scale = math.cos(math.radians(p[1])) * 111195
    for i, (a, b) in enumerate(zip(route, route[1:])):
        ax, ay = (p[0]-a[0])*scale, (p[1]-a[1])*111195
        dx, dy = (b[0]-a[0])*scale, (b[1]-a[1])*111195
        square = dx*dx+dy*dy
        raw = (ax*dx+ay*dy)/square if square else 0.
        t = max(0., min(1., raw))
        ex, ey = ax-t*dx, ay-t*dy
        length = distance(a, b)
        candidate = (math.hypot(ex, ey), total+t*length, dx*ay-dy*ax < 0, i == 0 and raw < 0)
        if best is None or candidate[0] < best[0]:
            best = candidate
        total += length
    if best is None:
        return 0., float('inf'), False, True
    return best[1], best[0], best[2], best[3]


class TmapMaps:
    def __init__(self, config=None, transport=None, clock=time.monotonic):
        self.config = Path(config) if config else Path(__file__).resolve().parents[1] / 'tmap.local.json'
        self.transport = transport or self._http
        self.clock, self.lock = clock, threading.Lock()
        self.day, self.count, self.minute = None, 0, []

    def settings(self):
        try:
            data = json.loads(self.config.read_text(encoding='utf-8-sig'))
            key = data.get('app_key', '').strip()
            # Syntax only: access is verified by the provider on the first call.
            valid = bool(re.fullmatch(r'[A-Za-z0-9._-]{16,256}', key))
            limit = max(1, min(500, int(data.get('max_requests_per_day', 500))))
            return key if data.get('enabled') is True and valid else '', limit
        except (OSError, ValueError, TypeError, AttributeError):
            return '', 500

    def status(self):
        key, limit = self.settings()
        return dict(searchConfigured=bool(key), routingConfigured=bool(key), provider='TMAP',
                    dailyLimit=limit, verification='설정 감지 · 실제 사용 권한은 첫 호출에서 확인',
                    storage='요청 처리 중에만 사용 · API 응답 저장 없음')

    def sdk_config(self):
        key, _ = self.settings()
        try:
            enabled = json.loads(self.config.read_text(encoding='utf-8-sig')).get('map_sdk_enabled') is True
        except (OSError, ValueError, AttributeError):
            enabled = False
        if not key or not enabled:
            raise MapError(503, 'PC에서 TMAP 무료 지도 설정을 확인해 주세요')
        # Only the authenticated session endpoint may return this. Never log/cache it.
        # The official SDK requires a client credential; it is not an APK constant.
        return dict(provider='TMAP', appKey=key)

    def _http(self, path, params, key, payload):
        req = Request('https://apis.openapi.sk.com/tmap/'+path+'?'+urlencode(params),
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={'appKey': key, 'Accept': 'application/json',
                     'Content-Type': 'application/json', 'User-Agent': 'FamilyCare/0.12'})
        with build_opener(NoRedirect()).open(req, timeout=12) as response:
            if response.status == 204:
                if path == 'pois':
                    return dict(searchPoiInfo=dict(totalCount='0', pois=dict(poi=[])))
                raise MapError(422, '선택한 위치의 자동차 경로를 찾지 못했어요')
            raw = response.read(4_000_001)
            if len(raw) > 4_000_000:
                raise MapError(502, '경로가 너무 길어요. 가까운 목적지를 선택하세요')
            return json.loads(raw)

    def request(self, path, params, payload=None):
        if path not in ('pois', 'routes', 'geo/reversegeocoding', 'poi/findPoiRoute'):
            raise MapError(400, '지원하지 않는 지도 요청')
        key, limit = self.settings()
        if not key:
            raise MapError(503, 'PC의 tmap.local.json에 TMAP 앱 키를 설정해 주세요')
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
            result = self.transport(path, params, key, payload)
            if not isinstance(result, dict) or 'error' in result:
                raise MapError(502, 'TMAP 응답 오류 · 앱의 무료 상품 사용 설정을 확인하세요')
            return result
        except HTTPError as error:
            messages = {401: 'TMAP 앱 키·상품 연결을 확인하세요',
                        403: 'TMAP 앱의 상품 사용 설정·허용 IP를 확인하세요',
                        429: 'TMAP 호출 한도를 초과했어요. 무료 쿼터를 확인하세요',
                        400: 'TMAP이 검색 조건을 처리하지 못했어요. 장소를 바꿔 주세요'}
            raise MapError(error.code if error.code in messages else 502,
                           messages.get(error.code, 'TMAP 서비스 응답 오류 · 잠시 후 다시 시도하세요')) from None
        except (URLError, TimeoutError, OSError):
            raise MapError(504, 'TMAP 연결 시간이 지났어요. 인터넷 연결을 확인하세요') from None
        except (ValueError, KeyError, TypeError):
            raise MapError(502, 'TMAP 응답을 읽지 못했어요') from None

    def search(self, body):
        center = point(body.get('center'))
        query, facility = body.get('query', ''), body.get('facility', '')
        allowed = ('', '주차장', '주유소', '전기차 충전소', '휴게소', '화장실', '기저귀 교환', '수유', '반려견 공원', '무장애 화장실')
        if not isinstance(query, str) or len(query) > 120 or facility not in allowed or not (query.strip() or facility):
            raise MapError(400, '검색할 장소 이름이나 시설을 선택하세요')
        route = body.get('route', [])
        if not isinstance(route, list) or len(route) > 250 or len(route) == 1:
            raise MapError(400, '검색 경로를 확인하세요')
        route = [point(p) for p in route]
        if body.get('alongRoute') is True and facility == '휴게소' and route:
            return self._rest_stops_ahead(center, route, query.strip())
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
        origin_along = project(center, route)[0] if route else 0.
        for origin in centers:
            params = dict(version=1, searchKeyword=' '.join(x for x in (query.strip(), facility) if x),
                          searchType='all', reqCoordType='WGS84GEO', resCoordType='WGS84GEO',
                          count=20, page=1, searchtypCd='R' if facility else 'A',
                          centerLon=origin[0], centerLat=origin[1], radius=10 if facility else 0)
            data = self.request('pois', params)
            try:
                info = data.get('searchPoiInfo', {})
                rows = info.get('pois', {}).get('poi', [])
                if isinstance(rows, dict):
                    rows = [rows]
                if not isinstance(rows, list):
                    raise TypeError()
            except (TypeError, AttributeError):
                raise MapError(502, 'TMAP 장소 목록을 읽지 못했어요') from None
            for p in rows:
                try:
                    # Entrance coordinates are preferable to a building centroid.
                    location = point([float(p.get('frontLon') or p['noorLon']), float(p.get('frontLat') or p['noorLat'])])
                    if location == [0., 0.]:
                        location = point([float(p['noorLon']), float(p['noorLat'])])
                    name = str(p['name'])[:120]
                    detail = ' '.join(str(p.get(k) or '') for k in ('upperAddrName', 'middleAddrName', 'lowerAddrName', 'detailAddrName')).strip()[:200]
                    ahead, offset = project(location, route) if route else (0., 0.)
                    if facility and route and (ahead < origin_along-150 or offset > 5000):
                        continue
                    score = max(0., ahead-origin_along)+offset*3 if facility and route else distance(center, location)
                    if facility and route:
                        detail += f' · 경로 전방 {max(0., ahead-origin_along)/1000:.1f} km · 경로 옆 {offset/1000:.1f} km'
                    if facility:
                        detail += ' · 편의시설·진입 방향은 확인 필요'
                    places[str(p.get('id') or location)] = dict(name=name, longitude=location[0], latitude=location[1], detail=detail, score=score)
                except (KeyError, ValueError, TypeError, AttributeError, MapError):
                    continue
        ordered = sorted(places.values(), key=lambda p: p['score'])[:12]
        for p in ordered:
            p.pop('score')
        return dict(places=ordered, provider='TMAP', routeAware=bool(facility and route),
                    notice='경로까지의 직선 거리 순위이며 실제 우회 시간·시설 보유 여부를 보장하지 않습니다.')

    def reverse(self, body):
        lon,lat=point(body.get('point'))
        data=self.request('geo/reversegeocoding',dict(version=1,lat=lat,lon=lon,coordType='WGS84GEO',addressType='A10',newAddressExtend='N'))
        a=data.get('addressInfo',{});full=str(a.get('fullAddress') or '').strip()
        if not full:raise MapError(422,'이 위치의 도로명 주소를 찾지 못했어요. GPS를 다시 확인하세요')
        road_name=str(a.get('roadName') or '').strip()
        road=' '.join(str(a.get(k) or '').strip() for k in (('roadName','buildingIndex') if road_name else ('gu_gun','eup_myun','legalDong','ri','bunji'))).strip() or full.split(',')[-1]
        road=' '.join(road.split())
        if road_name:full=' '.join(str(a.get(k) or '').strip() for k in ('city_do','gu_gun','eup_myun','roadName','buildingIndex','buildingName')).strip();full=' '.join(full.split())
        else:full=full.split(',')[-1].strip()
        return dict(address=full[:200],road=road[:80],provider='TMAP')

    @staticmethod
    def _rows(data):
        try:
            rows = data.get('searchPoiInfo', {}).get('pois', {}).get('poi', [])
        except (TypeError, AttributeError):
            return []
        rows = [rows] if isinstance(rows, dict) else rows
        return rows if isinstance(rows, list) else []

    def _rest_stops_ahead(self, center, route, query=''):
        """Rest stops on the whole remaining route ahead, nearest first (situation care's designated stop).
        First TMAP's route-radius search for rest areas on the driving side; if that is unavailable or finds nothing,
        name searches at up to 8 points spread over the remaining route. Only places beside the route (1 km) ahead count."""
        cumulative = [0.]
        for a, b in zip(route, route[1:]):
            cumulative.append(cumulative[-1]+distance(a, b))
        total, origin = cumulative[-1], project(center, route)[0]
        def at(d):
            d = max(0., min(total, d))
            for i in range(1, len(route)):
                if cumulative[i] >= d:
                    seg = cumulative[i]-cumulative[i-1]
                    t = (d-cumulative[i-1])/seg if seg else 0.
                    a, b = route[i-1], route[i]
                    return [a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t]
            return route[-1]
        places = {}
        def add(rows):
            for p in rows:
                try:
                    location = point([float(p.get('frontLon') or p['noorLon']), float(p.get('frontLat') or p['noorLat'])])
                    if location == [0., 0.]:
                        location = point([float(p['noorLon']), float(p['noorLat'])])
                    name = ' '.join(str(p['name']).split())[:120]
                    along, offset, right, before = side_of(location, route)
                    # Ahead only (not behind the car or before the remaining route starts), beside the road (1 km),
                    # and on the driving side: a rest area for the other direction lies left of the route (Korea drives on the right).
                    if not name or before or along < origin+50 or offset > 1000 or (offset > 25 and not right):
                        continue
                    ahead = along-origin
                    where = ' '.join(str(p.get(k) or '') for k in ('upperAddrName', 'middleAddrName', 'lowerAddrName')).strip()[:120]
                    detail = ' · '.join(x for x in (f'남은 경로 앞 {ahead/1000:.1f} km', where, '진입 방향·영업 여부는 확인 필요') if x)
                    old = places.get(name)
                    if old is None or ahead < old['score']:
                        places[name] = dict(name=name, longitude=location[0], latitude=location[1], detail=detail, score=ahead)
                except (KeyError, ValueError, TypeError, AttributeError, MapError):
                    continue
        ahead_points = [p for p, c in zip(route, cumulative) if c >= origin-50] or route[-2:]
        if not query and len(ahead_points) >= 2:
            step = max(1, -(-len(ahead_points)//200))
            line = [p for i, p in enumerate(ahead_points) if i % step == 0 or i == len(ahead_points)-1]
            try:
                add(self._rows(self.request('poi/findPoiRoute', {'version': 1}, dict(
                    startX=line[0][0], startY=line[0][1], endX=line[-1][0], endY=line[-1][1], userX=center[0], userY=center[1],
                    reqCoordType='WGS84GEO', resCoordType='WGS84GEO', searchType='category', searchCategory='C01',
                    radius=1, direction='right', sort='distance', count=50, page=1,
                    lineString='_'.join(f'{p[0]:.6f},{p[1]:.6f}' for p in line)))))
            except MapError:
                pass  # Not in the app's TMAP products, or no result: fall back to name searches.
        if not places:
            span = max(0., total-origin)
            count = min(8, max(1, math.ceil(span/20000)))
            spacing = span/count if span else 0.
            radius = int(min(33, max(5, math.ceil(spacing/2000)+3)))
            for i in range(count):
                c = at(origin+spacing*(i+.5))
                try:
                    add(self._rows(self.request('pois', dict(version=1, searchKeyword=' '.join(x for x in (query, '휴게소') if x),
                        searchType='all', reqCoordType='WGS84GEO', resCoordType='WGS84GEO', count=30, page=1,
                        searchtypCd='R', centerLon=c[0], centerLat=c[1], radius=radius))))
                except MapError:
                    if not places:
                        raise
                    break  # Quota mid-way: keep what was found.
        ordered = sorted(places.values(), key=lambda p: p['score'])[:20]
        for p in ordered:
            p.pop('score')
        return dict(places=ordered, provider='TMAP', routeAware=True, alongRoute=True,
                    notice='남은 경로 앞쪽 순서입니다. 휴게소 진입 방향·영업 여부는 직접 확인하세요.')

    def memory_place(self, body):
        """Address and nearby place names where a memory photo was taken, so the memory composer can fill them in.
        Only the photo's coordinates go to TMAP: reverse geocoding, then at most two name searches within 1 km
        (the building name, then the road address). Places farther than 300 m from the photo are left out.
        A missing address or a failed name search still returns what was found; nothing is stored."""
        lon, lat = point(body.get('point'))
        try:
            info = self.request('geo/reversegeocoding', dict(version=1, lat=lat, lon=lon, coordType='WGS84GEO',
                                                              addressType='A10', newAddressExtend='N')).get('addressInfo') or {}
            if not isinstance(info, dict):
                raise TypeError()
        except MapError as error:
            if error.status != 422:
                raise
            info = {}
        except (TypeError, AttributeError):
            raise MapError(502, 'TMAP 주소 정보를 읽지 못했어요') from None
        def text(key):
            return ' '.join(str(info.get(key) or '').split())
        road_name, building = text('roadName'), text('buildingName')[:120]
        road = ' '.join(x for x in (road_name, text('buildingIndex')) if x) if road_name else ''
        jibun = ' '.join(x for x in (text('eup_myun'), text('legalDong'), text('ri'), text('bunji')) if x)
        if road_name:
            address = ' '.join(x for x in (text('city_do'), text('gu_gun'), text('eup_myun'), road) if x)
        else:
            address = ' '.join(text('fullAddress').split(',')[-1].split())
        places, used = {}, ''
        for query in list(dict.fromkeys(q for q in (building, road or jibun) if q))[:2]:
            params = dict(version=1, searchKeyword=query[:120], searchType='all', reqCoordType='WGS84GEO', resCoordType='WGS84GEO',
                          count=10, page=1, searchtypCd='R', centerLon=lon, centerLat=lat, radius=1)
            try:
                rows = self.request('pois', params).get('searchPoiInfo', {}).get('pois', {}).get('poi', [])
            except MapError:
                break  # Quota or network: keep the address.
            except (TypeError, AttributeError):
                rows = []
            if isinstance(rows, dict):
                rows = [rows]
            for p in rows if isinstance(rows, list) else []:
                try:
                    location = point([float(p.get('frontLon') or p['noorLon']), float(p.get('frontLat') or p['noorLat'])])
                    if location == [0., 0.]:
                        location = point([float(p['noorLon']), float(p['noorLat'])])
                    name = ' '.join(str(p['name']).split())[:120]
                    meters = distance([lon, lat], location)
                    # An address search can echo the address itself; that is not a place name.
                    if not name or meters > 300 or (query != building and name == query):
                        continue
                    where = ' '.join(str(p.get(k) or '') for k in ('upperAddrName', 'middleAddrName', 'lowerAddrName', 'detailAddrName')).strip()[:160]
                    places.setdefault(name, dict(name=name, longitude=location[0], latitude=location[1],
                                                 detail=' · '.join(x for x in (where, f'사진 위치에서 {round(meters)} m') if x), meters=meters))
                except (KeyError, ValueError, TypeError, AttributeError, MapError):
                    continue
            if places:
                used = query
                break
        ordered = sorted(places.values(), key=lambda p: p['meters'])[:6]
        for p in ordered:
            p.pop('meters')
        place = ordered[0] if ordered else None
        return dict(address=address[:200], building=building, place=place, places=ordered,
                    query=(place['name'] if place else used or road or jibun or address)[:120], provider='TMAP')

    def directions(self, body):
        start, end = point(body.get('start')), point(body.get('end'))
        via = point(body['via']) if body.get('via') is not None else None
        option = body.get('routeOption', 0)
        if type(option) is not int or option not in (0, 1, 2, 4, 10, 19):
            raise MapError(400, '지원하지 않는 경로 옵션입니다')
        params = dict(startX=start[0], startY=start[1], endX=end[0], endY=end[1],
                      reqCoordType='WGS84GEO', resCoordType='WGS84GEO', searchOption=option,
                      sort='index', totalValue=1, trafficInfo='Y', carType=1)
        if via:
            params['passList'] = ','.join(map(str, via))
        data = self.request('routes', {'version': 1}, params)
        try:
            result = normalize_tmap(data)
            result['routes'][0]['routeOption'] = option
            coords = result['routes'][0]['geometry']['coordinates']
            if distance(start, coords[0]) > 300 or distance(end, coords[-1]) > 300 or (via and project(via, coords)[1] > 300):
                raise MapError(422, '선택한 위치 근처의 자동차 진입로를 찾지 못했어요')
            return result
        except MapError:
            raise
        except (KeyError, ValueError, TypeError, IndexError, AttributeError):
            raise MapError(502, 'TMAP 길안내 정보를 읽지 못했어요') from None

    def preview(self, body):
        point(body.get('start')); point(body.get('end')); point(body.get('via'))
        base = self.directions(dict(start=body['start'], end=body['end'], routeOption=body.get('routeOption', 0)))
        via = self.directions(body)
        before, after = base['routes'][0], via['routes'][0]
        return dict(extraSeconds=after['duration']-before['duration'], totalSeconds=after['duration'],
                    provider='TMAP', notice='현재 조회 조건의 예상치 · 운전자 승인 시 다시 계산합니다')


def normalize_tmap(data):
    features = data['features']
    if not isinstance(features, list) or not features:
        raise MapError(422, '두 지점을 잇는 자동차 경로가 없어요')
    coords, steps, times, roads, traffic = [], [], [], [], []
    total, fare = None, None
    # Via routes also contain unindexed waypoint-marker/connector layers. These
    # have no road traversal time and must not become part of drivable geometry.
    road_features = [f for f in features if 'index' in f.get('properties', {})]
    for feature in sorted(road_features, key=lambda f: int(f['properties']['index'])):
        geom, props = feature['geometry'], feature['properties']
        if 'totalTime' in props:
            total = float(props['totalTime'])
        if 'totalFare' in props:
            value = float(props['totalFare'])
            if math.isfinite(value) and value >= 0:
                fare = int(value)
        if geom['type'] == 'LineString':
            indices = []
            for value in geom['coordinates']:
                p = point(value)
                if not coords or coords[-1] != p:
                    coords.append(p)
                indices.append(len(coords)-1)
            if len(indices) > 1:
                roads.append(dict(start=indices[0], end=indices[-1], name=str(props.get('name') or '')[:120]))
            # Traffic indices are local to this LineString, not the flattened route.
            for item in geom.get('traffic') or []:
                if not isinstance(item, list) or len(item) < 3:
                    continue
                a, b, state = item[:3]
                if all(type(x) is int for x in (a, b, state)) and 0 <= a < b < len(indices) and state in (1, 2, 3, 4):
                    traffic.append(dict(start=indices[a], end=indices[b], state=state))
            seconds = float(props['time'])
            if not math.isfinite(seconds) or seconds < 0:
                raise ValueError('Invalid duration')
            times.append([len(coords)-1, seconds])
        elif geom['type'] == 'Point':
            code = int(props.get('turnType', 0))
            if code in range(0, 8) or code in (191, 192, 193, 194):
                continue  # Do not speak shape/speed/camera points as turns.
            text = str(props.get('description') or props.get('name') or '')[:180]
            if not text:
                continue
            modifier = 'uturn' if code == 14 or '유턴' in text else 'left' if code in (12,16,17,44,52,75,76,102,105,112,115,118) or '좌회전' in text else 'right' if code in (13,18,19,43,53,73,74,101,104,111,114,117) or '우회전' in text else 'straight'
            kind = 'service' if code in (150,151) else 'depart' if code == 200 else 'arrive' if code in (201,203) else 'roundabout' if '회전교차로' in text or '로터리' in text else 'turn'
            display_name = (props.get('name') or text) if kind == 'service' else (props.get('nextRoadName') or props.get('name') or '')
            steps.append(dict(name=str(display_name)[:120], instruction=text,
                              maneuver=dict(type=kind, modifier=modifier, location=point(geom['coordinates']))))
    if total is None or not math.isfinite(total) or total <= 0 or not 2 <= len(coords) <= 70000:
        raise ValueError('Invalid route')
    return dict(code='Ok', provider='TMAP', generatedAt=int(time.time()*1000),
                routes=[dict(duration=total, tollFare=fare, roads=roads, traffic=traffic,
                             geometry=dict(coordinates=coords), legs=[dict(steps=steps)], segments=times)])

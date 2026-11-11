"""Situation care: the family picks a rest stop on the whole remaining route ahead (synthetic coordinates only)."""
import json
import tempfile
import unittest
from pathlib import Path
from maps import MapError
from tmap_maps import TmapMaps

# A straight 100 km road east along latitude 37 (about 88.8 km per degree of longitude here).
ROUTE = [[127.0 + i * 0.05, 37.0] for i in range(24)]  # 0 .. 1.15 degrees ≈ 102 km


def poi(name, lon, lat):
    return dict(id=name, name=name, noorLon=lon, noorLat=lat, upperAddrName='경기', middleAddrName='이천시', lowerAddrName='마장면')


class RestStopsAheadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        path = Path(self.temp.name) / 'key.json'
        path.write_text(json.dumps(dict(app_key='synthetic-test-key-only', enabled=True)))
        self.calls = []
        self.route_api = MapError(403, 'TMAP 앱의 상품 사용 설정·허용 IP를 확인하세요')
        self.near = {}

        def transport(path, params, key, payload):
            self.calls.append((path, dict(params), payload))
            if path == 'poi/findPoiRoute':
                if isinstance(self.route_api, Exception):
                    raise self.route_api
                return dict(searchPoiInfo=dict(pois=dict(poi=self.route_api)))
            # Name search: return the rest stops within the requested radius of the centre.
            c, r = (params['centerLon'], params['centerLat']), params['radius'] * 1000
            rows = [p for p in self.near.values() if abs(p['noorLon'] - c[0]) * 88800 <= r and abs(p['noorLat'] - c[1]) * 111195 <= r]
            return dict(searchPoiInfo=dict(pois=dict(poi=rows)))
        self.maps = TmapMaps(path, transport=transport)

    def search(self, **extra):
        return self.maps.search(dict(center=ROUTE[0], query='', facility='휴게소', route=ROUTE, alongRoute=True, **extra))

    def test_finds_rest_stops_over_the_whole_remaining_route_nearest_first(self):
        # Driving east: the right side is south (lower latitude).
        for name, lon, lat in [('먼 휴게소', 127.90, 36.998), ('가까운 휴게소', 127.10, 36.999), ('중간 휴게소', 127.50, 36.9985),
                               ('뒤쪽 휴게소', 126.95, 37.0), ('경로에서 먼 식당 휴게소', 127.50, 37.05)]:
            self.near[name] = poi(name, lon, lat)
        data = self.search()
        # 80 km ahead is found too (the old search only looked about 25 km ahead); behind and 5 km off the road are not.
        self.assertEqual(['가까운 휴게소', '중간 휴게소', '먼 휴게소'], [p['name'] for p in data['places']])
        self.assertIn('남은 경로 앞', data['places'][2]['detail'])
        self.assertTrue(data['alongRoute'])
        searches = [c for c in self.calls if c[0] == 'pois']
        self.assertTrue(2 <= len(searches) <= 8)
        self.assertTrue(all(s[1]['searchtypCd'] == 'R' and s[1]['radius'] <= 33 for s in searches))

    def test_uses_the_route_radius_search_when_available(self):
        self.route_api = [poi('덕평자연휴게소', 127.30, 36.9995), poi('뒤쪽', 126.9, 37.0)]
        data = self.search()
        self.assertEqual(['덕평자연휴게소'], [p['name'] for p in data['places']])
        self.assertEqual(['poi/findPoiRoute'], [c[0] for c in self.calls])
        body = self.calls[0][2]
        self.assertEqual(('category', 'C01', 'right'), (body['searchType'], body['searchCategory'], body['direction']))
        self.assertEqual(f'{ROUTE[0][0]:.6f},{ROUTE[0][1]:.6f}', body['lineString'].split('_')[0])

    def test_starts_from_where_the_car_is_and_keeps_the_typed_name(self):
        self.near['지나온 휴게소'] = poi('지나온 휴게소', 127.10, 37.0)
        self.near['앞 휴게소'] = poi('앞 휴게소', 127.70, 36.999)
        data = self.maps.search(dict(center=[127.40, 37.0], query='덕평', facility='휴게소', route=ROUTE, alongRoute=True))
        self.assertEqual(['앞 휴게소'], [p['name'] for p in data['places']])
        # A typed name skips the category search and is searched with '휴게소'.
        self.assertTrue(all(c[0] == 'pois' and c[1]['searchKeyword'] == '덕평 휴게소' for c in self.calls))

    def test_the_other_directions_rest_area_and_one_just_passed_are_left_out(self):
        self.near['덕평(강릉방향)'] = poi('덕평(강릉방향)', 127.30, 36.9985)   # right of the road: our side
        self.near['덕평(인천방향)'] = poi('덕평(인천방향)', 127.30, 37.0015)   # across the road: other direction
        self.near['방금 지난 휴게소'] = poi('방금 지난 휴게소', 126.995, 36.999)  # 450 m behind the car
        data = self.search()
        self.assertEqual(['덕평(강릉방향)'], [p['name'] for p in data['places']])

    def test_quota_before_anything_is_found_is_an_error(self):
        self.maps.transport = lambda *a: (_ for _ in ()).throw(MapError(429, 'TMAP 호출 한도를 초과했어요'))
        with self.assertRaises(MapError):
            self.search()

    def test_other_searches_are_unchanged(self):
        data = self.maps.search(dict(center=ROUTE[0], query='', facility='휴게소', route=ROUTE))
        self.assertNotIn('alongRoute', data)


if __name__ == '__main__':
    unittest.main()

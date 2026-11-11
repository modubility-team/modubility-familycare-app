"""Filling in a memory photo's address and place name from where it was taken (TMAP reverse geocoding + nearby name search).
Synthetic coordinates and responses only: no provider data is stored in the repository."""
import json
import tempfile
import unittest
from pathlib import Path
from maps import MapError
from tmap_maps import TmapMaps
from server import Relay, ApiError

PHOTO = [127.3900, 37.2400]  # lon, lat


def poi(name, lon, lat, **extra):
    return dict(id=name, name=name, noorLon=lon, noorLat=lat, frontLon=lon, frontLat=lat,
                upperAddrName='경기', middleAddrName='이천시', lowerAddrName='마장면', detailAddrName='덕평리', **extra)


class MemoryPlaceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        path = Path(self.temp.name) / 'key.json'
        path.write_text(json.dumps(dict(app_key='synthetic-test-key-only', enabled=True)))
        self.calls = []
        self.address = dict(fullAddress='경기도 이천시 마장면 덕평리 1,경기도 이천시 마장면 덕평리 1,경기도 이천시 마장면 덕평로 154',
                            city_do='경기도', gu_gun='이천시', eup_myun='마장면', legalDong='', ri='덕평리', bunji='1',
                            roadName='덕평로', buildingIndex='154', buildingName='덕평자연휴게소')
        self.pois = {}

        def transport(path, params, key, payload):
            self.calls.append((path, dict(params)))
            if path == 'geo/reversegeocoding':
                if isinstance(self.address, Exception):
                    raise self.address
                return dict(addressInfo=self.address)
            rows = self.pois.get(params['searchKeyword'])
            if isinstance(rows, Exception):
                raise rows
            return dict(searchPoiInfo=dict(pois=dict(poi=rows or [])))
        self.maps = TmapMaps(path, transport=transport)

    def test_photo_location_fills_address_and_the_nearest_place(self):
        self.pois['덕평자연휴게소'] = [
            poi('덕평자연휴게소 주유소', 127.3915, 37.2405),   # about 140 m
            poi('덕평자연휴게소', 127.3906, 37.2402),          # about 60 m
            poi('덕평자연휴게소', 127.3907, 37.2403),          # same name again: kept once
            poi('덕평자연휴게소 반대편', 127.4100, 37.2400)]   # about 1.8 km: not where the photo was taken
        data = self.maps.memory_place(dict(point=PHOTO))
        self.assertEqual('경기도 이천시 마장면 덕평로 154', data['address'])
        self.assertEqual('덕평자연휴게소', data['building'])
        self.assertEqual('덕평자연휴게소', data['place']['name'])
        self.assertEqual(['덕평자연휴게소', '덕평자연휴게소 주유소'], [p['name'] for p in data['places']])
        self.assertIn('사진 위치에서', data['place']['detail'])
        self.assertEqual('덕평자연휴게소', data['query'])
        # One address lookup and one name search near the photo; nothing else leaves the PC.
        self.assertEqual(['geo/reversegeocoding', 'pois'], [c[0] for c in self.calls])
        reverse, search = self.calls[0][1], self.calls[1][1]
        self.assertEqual((37.24, 127.39), (reverse['lat'], reverse['lon']))
        self.assertEqual('N', reverse['newAddressExtend'])
        self.assertEqual(('R', 1, 37.24, 127.39), (search['searchtypCd'], search['radius'], search['centerLat'], search['centerLon']))

    def test_without_a_building_name_the_road_address_finds_places_and_an_address_echo_is_skipped(self):
        self.address = dict(self.address, buildingName='')
        self.pois['덕평로 154'] = [poi('덕평로 154', *PHOTO), poi('휴게소 카페', 127.3904, 37.2401)]
        data = self.maps.memory_place(dict(point=PHOTO))
        self.assertEqual('휴게소 카페', data['place']['name'])
        self.assertEqual(['휴게소 카페'], [p['name'] for p in data['places']])
        self.assertEqual('덕평로 154', self.calls[1][1]['searchKeyword'])

    def test_no_nearby_place_keeps_the_address_and_prefills_the_road(self):
        self.pois['덕평자연휴게소'] = []
        self.pois['덕평로 154'] = [poi('먼 곳', 127.4200, 37.2400)]
        data = self.maps.memory_place(dict(point=PHOTO))
        self.assertIsNone(data['place'])
        self.assertEqual([], data['places'])
        self.assertEqual('경기도 이천시 마장면 덕평로 154', data['address'])
        self.assertEqual('덕평로 154', data['query'])
        # At most two name searches per photo.
        self.assertEqual(['geo/reversegeocoding', 'pois', 'pois'], [c[0] for c in self.calls])

    def test_lot_number_address_when_there_is_no_road_name(self):
        self.address = dict(fullAddress='경기도 이천시 마장면 덕평리,경기도 이천시 마장면 덕평리 123-4', city_do='경기도', gu_gun='이천시',
                            eup_myun='마장면', legalDong='', ri='덕평리', bunji='123-4', roadName='', buildingIndex='', buildingName='')
        self.pois['마장면 덕평리 123-4'] = [poi('덕평 공원', 127.3902, 37.2401)]
        data = self.maps.memory_place(dict(point=PHOTO))
        self.assertEqual('경기도 이천시 마장면 덕평리 123-4', data['address'])
        self.assertEqual('덕평 공원', data['place']['name'])

    def test_no_address_or_a_failed_name_search_is_not_an_error(self):
        self.address = MapError(422, '주소 없음')
        data = self.maps.memory_place(dict(point=PHOTO))
        self.assertEqual(('', None, [], ''), (data['address'], data['place'], data['places'], data['query']))
        self.assertEqual(['geo/reversegeocoding'], [c[0] for c in self.calls])
        self.address = dict(fullAddress='x,경기도 이천시 마장면 덕평로 154', city_do='경기도', gu_gun='이천시', eup_myun='마장면',
                            roadName='덕평로', buildingIndex='154', buildingName='덕평자연휴게소')
        self.pois['덕평자연휴게소'] = MapError(429, 'TMAP 호출 한도를 초과했어요')
        data = self.maps.memory_place(dict(point=PHOTO))
        self.assertEqual('경기도 이천시 마장면 덕평로 154', data['address'])
        self.assertIsNone(data['place'])
        # Other provider errors still reach the phone.
        self.address = MapError(504, 'TMAP 연결 시간이 지났어요')
        with self.assertRaises(MapError) as e:
            self.maps.memory_place(dict(point=PHOTO))
        self.assertEqual(504, e.exception.status)
        with self.assertRaises(MapError):
            self.maps.memory_place(dict(point=[float('nan'), 37.24]))

    def test_relay_action_needs_consent_the_current_provider_and_a_connected_role(self):
        seen = []

        class Fake:
            def memory_place(self, body):
                seen.append(body)
                return dict(address='주소', building='', place=None, places=[], query='주소', provider='TMAP')
        relay = Relay(maps=Fake())
        session = relay.create()
        sid, tokens = session['id'], relay.sessions[session['id']]['tokens']
        body = dict(point=PHOTO, provider='tmap')
        with self.assertRaises(ApiError) as e:
            relay.call(sid, tokens['family'], 'memory-place', body)
        self.assertEqual(400, e.exception.status)
        with self.assertRaises(ApiError) as e:
            relay.call(sid, tokens['family'], 'memory-place', dict(point=PHOTO, consent=True))
        self.assertEqual(409, e.exception.status)
        with self.assertRaises(ApiError) as e:
            relay.call(sid, tokens['guest'], 'memory-place', dict(body, consent=True))
        self.assertEqual(403, e.exception.status)
        self.assertEqual([], seen)
        self.assertEqual('주소', relay.call(sid, tokens['family'], 'memory-place', dict(body, consent=True))['address'])
        self.assertEqual(PHOTO, seen[0]['point'])


if __name__ == '__main__':
    unittest.main()

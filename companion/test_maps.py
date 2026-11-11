import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from maps import Maps, MapError, normalize_route, project
from server import Relay, ApiError


def route_fixture():
    # Invented coordinates and durations; no stored provider response.
    return {'routes': [{'result_code': 0, 'summary': {'duration': 120}, 'sections': [{
        'roads': [{'vertexes': [127., 37., 127.01, 37.], 'duration': 90},
                  {'vertexes': [127.01, 37., 127.02, 37.], 'duration': 30}],
        'guides': [{'x': 127.01, 'y': 37., 'guidance': '오른쪽 도로', 'type': 2}]}]}]}


class MapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)/'config.json'
        self.path.write_text(json.dumps(dict(rest_api_key='a'*32, enabled=True, max_requests_per_day=500)))
        self.calls = []
        def transport(url, key):
            self.calls.append((url, key))
            return route_fixture()
        self.maps = Maps(self.path, transport=transport)

    def test_key_not_in_status_or_url(self):
        self.maps.directions(dict(start=[127, 37], end=[127.02, 37]))
        self.assertNotIn('a'*32, self.calls[0][0])
        self.assertNotIn('a'*32, json.dumps(self.maps.status()))
        self.assertTrue(self.maps.status()['searchConfigured'])

    def test_missing_key_blocks_without_network(self):
        self.path.write_text('{}')
        with self.assertRaises(MapError) as e:
            self.maps.directions(dict(start=[127, 37], end=[127.02, 37]))
        self.assertEqual(503, e.exception.status)
        self.assertEqual([], self.calls)

    def test_geometry_guide_and_duration(self):
        route = normalize_route(route_fixture())['routes'][0]
        self.assertEqual(3, len(route['geometry']['coordinates']))
        self.assertEqual([[1, 90.], [2, 30.]], route['segments'])
        self.assertEqual('right', route['legs'][0]['steps'][0]['maneuver']['modifier'])
        self.assertEqual('오른쪽 도로', route['legs'][0]['steps'][0]['instruction'])

    def test_no_route_and_road_snap_failure(self):
        with self.assertRaises(MapError) as e:
            normalize_route({'routes': [{'result_code': 101}]})
        self.assertEqual(422, e.exception.status)
        with self.assertRaises(MapError):
            self.maps.directions(dict(start=[128, 37], end=[127.02, 37]))

    def test_invalid_input_does_not_consume_quota(self):
        for p in ([float('nan'), 37], [True, 37], [181, 37], '127,37'):
            with self.assertRaises(MapError):
                self.maps.directions(dict(start=p, end=[127.02, 37]))
        self.assertEqual([], self.calls)

    def test_quota_and_safe_errors(self):
        self.path.write_text(json.dumps(dict(rest_api_key='a'*32, enabled=True, max_requests_per_day=1)))
        self.maps.directions(dict(start=[127, 37], end=[127.02, 37]))
        with self.assertRaises(MapError) as e:
            self.maps.directions(dict(start=[127, 37], end=[127.02, 37]))
        self.assertEqual(429, e.exception.status)
        for status in (401, 403, 429, 500):
            def fail(*args):
                raise HTTPError('https://example.test', status, 'secret body', {}, None)
            maps = Maps(self.path, transport=fail)
            with self.assertRaises(MapError) as e:
                maps.directions(dict(start=[127, 37], end=[127.02, 37]))
            self.assertNotIn('secret', str(e.exception))
            self.assertNotIn('a'*32, str(e.exception))

    def test_route_candidates_exclude_behind_and_far(self):
        def transport(url, key):
            return {'documents': [dict(id=str(i), x=x, y=y, place_name=name) for i, (x,y,name) in enumerate([
                (127.005,37,'behind'), (127.015,37.001,'ahead'), (127.02,37.1,'far')])]}
        maps = Maps(self.path, transport=transport)
        result = maps.search(dict(center=[127.01,37], query='', facility='휴게소', route=[[127,37],[127.03,37]]))
        self.assertEqual(['ahead'], [p['name'] for p in result['places']])
        self.assertIn('확인 필요', result['places'][0]['detail'])
        self.assertNotIn('score', result['places'][0])

    def test_preview_is_readonly_and_returns_no_geometry(self):
        result=self.maps.preview(dict(start=[127,37],end=[127.02,37],via=[127.01,37]))
        self.assertEqual(0,result['extraSeconds'])
        self.assertEqual(2,len(self.calls))
        self.assertNotIn('routes',result)

    def test_departure_and_arrival_codes(self):
        fixture=route_fixture()
        fixture['routes'][0]['sections'][0]['guides']=[
            dict(x=127,y=37,type=100,guidance='출발지'),
            dict(x=127.02,y=37,type=101,guidance='목적지')]
        steps=normalize_route(fixture)['routes'][0]['legs'][0]['steps']
        self.assertEqual(['depart','arrive'],[s['maneuver']['type'] for s in steps])

    def test_auth_consent_and_role_before_provider(self):
        relay = Relay(maps=self.maps)
        session = relay.create(); sid = session['id']; token = session['token']
        body = dict(start=[127,37], end=[127.02,37], consent=True, provider="tmap")
        with self.assertRaises(ApiError):relay.call(sid, 'bad', 'maps-route', body)
        with self.assertRaises(ApiError):relay.call(sid, token, 'maps-route', dict(body, consent=False))
        family = relay.sessions[sid]['tokens']['family']
        with self.assertRaises(ApiError):relay.call(sid, family, 'maps-route', body)
        self.assertEqual([], self.calls)
        self.assertEqual('Ok', relay.call(sid, token, 'maps-route', body)['code'])

    def test_slow_search_does_not_block_revoke_and_discards_result(self):
        entered, resume = threading.Event(), threading.Event()
        def transport(url, key):
            entered.set(); resume.wait(3); return {'documents': []}
        relay = Relay(maps=Maps(self.path, transport=transport))
        session = relay.create(); sid = session['id']; family = relay.sessions[sid]['tokens']['family']
        errors=[]
        def work():
            try:relay.call(sid, family, 'maps-search', dict(center=[127,37],query='park',consent=True, provider="tmap"))
            except ApiError as e:errors.append(e.status)
        t=threading.Thread(target=work);t.start();self.assertTrue(entered.wait(1))
        relay.call(sid, session['token'], 'permissions', dict(role='family',enabled=False))
        resume.set();t.join(2)
        self.assertFalse(t.is_alive());self.assertEqual([403],errors)


if __name__ == '__main__':unittest.main()

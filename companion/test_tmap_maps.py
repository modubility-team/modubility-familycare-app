import copy
import json
import tempfile
import unittest
from unittest.mock import patch, MagicMock
from pathlib import Path
from urllib.error import HTTPError
from maps import MapError
from tmap_maps import TmapMaps, normalize_tmap
from server import Relay, ApiError


def route_fixture(extra=0):
    # Synthetic coordinates: no provider dataset or cached API response.
    def feature(i, kind, coords, **props):
        return dict(geometry=dict(type=kind, coordinates=coords), properties=dict(index=i, **props))
    return dict(features=[
        feature(0,'Point',[127.,37.],totalTime=120+extra,turnType=200,description='출발지'),
        feature(1,'LineString',[[127.,37.],[127.01,37.]],time=90+extra),
        feature(2,'Point',[127.01,37.],turnType=13,description='우회전하세요'),
        feature(3,'LineString',[[127.01,37.],[127.02,37.]],time=30),
        feature(4,'Point',[127.02,37.],turnType=201,description='목적지')])


class TmapTests(unittest.TestCase):
    def test_no_content_search_is_empty_but_route_is_unavailable(self):
        response=MagicMock();response.status=204
        response.__enter__.return_value=response
        with patch('tmap_maps.build_opener') as opener:
            opener.return_value.open.return_value=response
            self.maps.transport=self.maps._http
            self.assertEqual([],self.maps.search(dict(center=[127,37],query='기저귀 교환'))['places'])
            with self.assertRaises(MapError) as error:self.maps.directions(dict(start=[127,37],end=[127.02,37]))
            self.assertEqual(422,error.exception.status)
            response.read.assert_not_called()

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'key.json'
        self.path.write_text(json.dumps(dict(app_key='synthetic-test-key-only',enabled=True)))
        self.calls=[]
        def transport(path,params,key,payload):
            self.calls.append((path,params,key,payload))
            return route_fixture(60 if payload and 'passList' in payload else 0)
        self.maps=TmapMaps(self.path,transport=transport)

    def test_sdk_requires_explicit_enable_and_authenticated_unrevoked_role(self):
        relay=Relay(maps=self.maps);session=relay.create();sid=session['id']
        tokens=relay.sessions[sid]['tokens'].copy()
        body=dict(provider='tmap',consent=True)
        with self.assertRaises(ApiError) as error:
            relay.call(sid,tokens['car'],'maps-sdk-config',body)
        self.assertEqual(503,error.exception.status)
        self.path.write_text(json.dumps(dict(app_key='synthetic-test-key-only',enabled=True,map_sdk_enabled=True)))
        for role in ('car','owner','family'):
            self.assertEqual('synthetic-test-key-only',relay.call(sid,tokens[role],'maps-sdk-config',body)['appKey'])
        self.assertNotIn('appKey',relay.call(sid,tokens['car'],'maps-status',{}))
        for token in (tokens['guest'],tokens['observer'],'invalid'):
            with self.assertRaises(ApiError) as error:relay.call(sid,token,'maps-sdk-config',body)
            self.assertEqual(403,error.exception.status)
        with self.assertRaises(ApiError):relay.call(sid,tokens['car'],'maps-sdk-config',dict(provider='tmap'))
        relay.call(sid,tokens['car'],'permissions',dict(role='family',enabled=False))
        with self.assertRaises(ApiError):relay.call(sid,tokens['family'],'maps-sdk-config',body)
        relay.call(sid,tokens['car'],'end',{})
        with self.assertRaises(ApiError):relay.call(sid,tokens['owner'],'maps-sdk-config',body)

    def test_car_route_turns_and_nonuniform_eta(self):
        data=self.maps.directions(dict(start=[127,37],end=[127.02,37]))
        self.assertEqual('TMAP',data['provider'])
        route=data['routes'][0]
        self.assertEqual([[1,90],[2,30]],route['segments'])
        self.assertEqual(3,len(route['geometry']['coordinates']))
        self.assertEqual(['depart','turn','arrive'],[s['maneuver']['type'] for s in route['legs'][0]['steps']])
        self.assertEqual('right',route['legs'][0]['steps'][1]['maneuver']['modifier'])
        self.assertEqual(0,self.calls[0][3]['searchOption'])

    def test_options_are_allowlisted_and_traffic_requested(self):
        for option in (0,1,2,4,10,19):
            data=self.maps.directions(dict(start=[127,37],end=[127.02,37],routeOption=option))
            self.assertEqual(option,data['routes'][0]['routeOption'])
            self.assertEqual(option,self.calls[-1][3]['searchOption'])
            self.assertEqual('Y',self.calls[-1][3]['trafficInfo'])
        count=len(self.calls)
        for option in (True,'1',12,999,None):
            with self.assertRaises(MapError):self.maps.directions(dict(start=[127,37],end=[127.02,37],routeOption=option))
        self.assertEqual(count,len(self.calls))

    def test_rest_area_name_is_not_replaced_by_the_next_highway(self):
        fixture=route_fixture()
        props=fixture['features'][2]['properties']
        props.update(turnType=150,name='테스트 졸음쉼터',nextRoadName='다음 고속도로',description='졸음쉼터 안내')
        step=normalize_tmap(fixture)['routes'][0]['legs'][0]['steps'][1]
        self.assertEqual('service',step['maneuver']['type'])
        self.assertEqual('테스트 졸음쉼터',step['name'])

    def test_local_traffic_indices_and_unknown_fare_are_not_fabricated(self):
        fixture=route_fixture()
        fixture['features'][0]['properties']['totalFare']=1500
        fixture['features'][1]['properties']['name']='첫 도로'
        fixture['features'][1]['geometry']['traffic']=[[0,1,1,45],[0,5,4,3],[0,1,255,0]]
        fixture['features'][2]['properties']['nextRoadName']='다음 도로'
        fixture['features'][3]['geometry']['traffic']=[[0,1,4,5]]
        route=normalize_tmap(fixture)['routes'][0]
        self.assertEqual(1500,route['tollFare'])
        self.assertEqual([dict(start=0,end=1,state=1),dict(start=1,end=2,state=4)],route['traffic'])
        self.assertEqual('첫 도로',route['roads'][0]['name'])
        self.assertEqual('다음 도로',route['legs'][0]['steps'][1]['name'])
        self.assertIsNone(normalize_tmap(route_fixture())['routes'][0]['tollFare'])
        self.assertEqual([],normalize_tmap(route_fixture())['routes'][0]['traffic'])

    def test_preview_compares_two_routes_without_mutation(self):
        body=dict(start=[127,37],end=[127.02,37],via=[127.01,37]);old=copy.deepcopy(body)
        value=self.maps.preview(body)
        self.assertEqual(60,value['extraSeconds']);self.assertEqual(180,value['totalSeconds'])
        self.assertEqual(old,body);self.assertEqual(2,len(self.calls))
        self.assertNotIn('routes',value)

    def test_no_key_and_malformed_coords_do_not_call_provider(self):
        for p in ([float('nan'),37],[True,37],[181,37]):
            with self.assertRaises(MapError):self.maps.directions(dict(start=p,end=[127.02,37]))
        self.path.write_text('{}')
        with self.assertRaises(MapError) as e:self.maps.directions(dict(start=[127,37],end=[127.02,37]))
        self.assertEqual(503,e.exception.status);self.assertEqual([],self.calls)

    def test_quota_and_key_stay_private(self):
        self.path.write_text(json.dumps(dict(app_key='synthetic-test-key-only',enabled=True,max_requests_per_day=1)))
        self.maps.directions(dict(start=[127,37],end=[127.02,37]))
        with self.assertRaises(MapError) as e:self.maps.directions(dict(start=[127,37],end=[127.02,37]))
        self.assertEqual(429,e.exception.status)
        self.assertNotIn('synthetic-test-key-only',json.dumps(self.maps.status()))
        self.assertNotIn('synthetic-test-key-only',json.dumps(self.calls[0][1]))
        self.assertNotIn('synthetic-test-key-only',json.dumps(self.calls[0][3]))

    def test_safe_upstream_errors(self):
        for status in (400,401,403,429,500):
            def fail(*args):raise HTTPError('https://example.test',status,'private upstream body',{},None)
            maps=TmapMaps(self.path,transport=fail)
            with self.assertRaises(MapError) as e:maps.directions(dict(start=[127,37],end=[127.02,37]))
            self.assertNotIn('private',str(e.exception))

    def test_entrance_and_forward_candidates(self):
        def search(*args):
            return dict(searchPoiInfo=dict(pois=dict(poi=[
                dict(id=name,name=name,noorLon=128,noorLat=37,frontLon=x,frontLat=y)
                for name,x,y in [('behind',127.005,37),('ahead',127.015,37.001),('far',127.02,37.1)]])))
        self.maps.transport=search
        data=self.maps.search(dict(center=[127.01,37],query='',facility='휴게소',route=[[127,37],[127.03,37]]))
        self.assertEqual(['ahead'],[p['name'] for p in data['places']])
        self.assertIn('확인 필요',data['places'][0]['detail'])

    def test_bad_geometry_and_malformed_results_fail(self):
        bad=route_fixture();bad['features'][1]['geometry']['coordinates'][1]=[float('nan'),37]
        self.maps.transport=lambda *args:bad
        with self.assertRaises(MapError):self.maps.directions(dict(start=[127,37],end=[127.02,37]))
        self.maps.transport=lambda *args:dict(searchPoiInfo=None)
        with self.assertRaises(MapError):self.maps.search(dict(center=[127,37],query='역'))

    def test_no_route_and_road_snap(self):
        with self.assertRaises(MapError):normalize_tmap(dict(features=[]))
        with self.assertRaises(MapError) as e:self.maps.directions(dict(start=[128,37],end=[127.02,37]))
        self.assertEqual(422,e.exception.status)

    def test_via_annotation_layers_do_not_corrupt_drivable_route(self):
        data=route_fixture()
        data['features'] += [
            dict(geometry=dict(type='Point',coordinates=[128,38]),properties=dict(name='via',pointType='via')),
            dict(geometry=dict(type='LineString',coordinates=[[128,38],[128.1,38]]),properties=dict(name='connector',description='annotation'))]
        route=normalize_tmap(data)['routes'][0]
        self.assertEqual([[127.,37.],[127.01,37.],[127.02,37.]],route['geometry']['coordinates'])
        self.assertEqual(120,route['duration'])

    def test_old_app_cannot_silently_send_to_new_provider(self):
        relay=Relay(maps=self.maps);session=relay.create()
        body=dict(start=[127,37],end=[127.02,37],consent=True)
        with self.assertRaises(ApiError) as e:relay.call(session['id'],session['token'],'maps-route',body)
        self.assertEqual(409,e.exception.status);self.assertEqual([],self.calls)
        data=relay.call(session['id'],session['token'],'maps-route',dict(body,provider='tmap'))
        self.assertEqual('TMAP',data['provider'])


if __name__=='__main__':unittest.main()

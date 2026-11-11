"""Safe local TMAP diagnostic. --live uses public example coordinates, never GPS."""
import argparse
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'companion'))
from tmap_maps import TmapMaps
from maps import MapError


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--live',action='store_true',help='Query public Seoul example coordinates')
    args=parser.parse_args()
    maps=TmapMaps();status=maps.status()
    print('TMAP 앱 키 설정:', '있음 (실제 권한 검증 전)' if status['searchConfigured'] else '필요')
    if not args.live:return 0 if status['searchConfigured'] else 1
    if not status['searchConfigured']:return 1
    errors=0
    for label,run in [('장소 검색',lambda:maps.search(dict(center=[127.03636666,37.48544722],query='양재시민의숲',facility=''))),
                      ('자동차 길찾기',lambda:maps.directions(dict(start=[127.03636666,37.48544722],end=[127.0354,37.4703])))]:
        try:
            result=run()
            if label=='장소 검색' and not result['places']:
                errors+=1;print(label+': 응답 수신했으나 검색 결과 없음')
            else:print(label+': 성공 · '+result['provider'])
        except MapError as error:
            errors+=1;print(label+f': HTTP {error.status} · '+error.message)
    return 1 if errors else 0


if __name__=='__main__':sys.exit(main())

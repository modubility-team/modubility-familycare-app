"""Read-only configuration/live API diagnostic. Never print credentials or responses."""
import argparse
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'companion'))
from maps import Maps, MapError


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--live',action='store_true',help='Query public Seoul example coordinates')
    args=parser.parse_args()
    maps=Maps();status=maps.status()
    print('Local 검색 키 설정:', '있음' if status['searchConfigured'] else '필요')
    print('Mobility 길찾기 키 설정:', '있음' if status['routingConfigured'] else '필요')
    if not args.live:return 0 if status['searchConfigured'] and status['routingConfigured'] else 1
    errors=0
    for label,run in [('장소 검색',lambda:maps.search(dict(center=[127.03636666,37.48544722],query='양재시민의숲',facility=''))),
                      ('자동차 길찾기',lambda:maps.directions(dict(start=[127.03636666,37.48544722],end=[127.0354,37.4703])))]:
        try:
            result=run()
            print(label+': 성공 · '+result['provider'])
        except MapError as error:
            errors+=1;print(label+f': HTTP {error.status} · '+error.message)
    return 1 if errors else 0


if __name__=='__main__':sys.exit(main())

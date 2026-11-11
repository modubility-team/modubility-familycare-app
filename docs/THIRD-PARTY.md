# 외부 코드·SDK·자료 출처

확인일: 2026-10-26. 이 목록은 출처와 확인 상태를 기록하며 모든 자료의 재배포 허가가 완료되었다는 뜻이 아닙니다.

## 앱과 참고 샘플의 경계

2026-11-08: 제출용 GitHub 저장소(팀 모두빌리티, 패밀리케어앱)에는 `samples/` 폴더를 넣지 않았습니다. 공식 원본은 아래 링크에서 확인합니다.

`android/`는 Family Care 앱입니다. `samples/`의 두 프로젝트는 Pleos 공식 참고 자료를 복사한 별도 시험 프로젝트이며 Family Care APK의 모듈에 포함하지 않습니다. 출품작의 독자적인 구현으로 소개하면 안 됩니다.

- Vehicle 원본: https://github.com/Pleos-Playground/pleos-sample-vehicle-app
- Navi 원본: https://github.com/Pleos-Playground/pleos-sample-navi-app
- 로컬 변경: Vehicle 의존성 2.3.2, 시험용 SupportProbe/NaviSupportProbe와 관련 debug 권한·설정.
- 확인한 원본 루트와 로컬 사본에는 명시적인 LICENSE 파일이 없습니다. 샘플의 복제·배포 조건을 공급자 문서/약관으로 추가 확인해야 합니다. 임의로 MIT/Apache 라이선스를 붙이지 않았습니다.
- SDK에서 요구하는 초기화·capability 조회·구독·해제 순서는 공식 도어 예제를 참조했습니다. 앱 화면과 상태 처리는 이 저장소의 구현입니다.

## 런타임 라이브러리

0.10은 OSRM/FOSSGIS 공개 경로 API를 HTTPS로 호출합니다. OSRM 서버 바이너리를 APK에 포함한 것이 아니며, 경로 제공자와 OpenStreetMap 출처는 지도 도움말에 표시합니다. 현재 위치·목적지·경유지 전송, 공개 서버의 사용량/가용성 제한과 배포 전 필요한 변경은 [GPS·길안내 안내](NAVIGATION-010.txt)에 기록했습니다.

`DEPENDENCIES.txt`에 Gradle이 해석한 release 런타임 의존성과 캐시된 공급자 POM의 라이선스 정보를 기록했습니다. BOM·제약 메타데이터도 포함하므로 목록의 항목 모두가 APK에 들어가는 별개 코드라는 뜻은 아닙니다.

- AndroidX/Compose, Kotlin/Coroutines, Dagger, Gson 등: 각 POM에 기재된 라이선스와 출처 확인.
- Pleos Vehicle 2.3.2, vehicle-aidl 1.1.1, vehicle-data 1.1.11: POM에 라이선스 항목이 없어 Pleos SDK 약관에 따른 별도 확인 필요. 오픈소스로 단정하지 않음.
- `javax.inject:1`, `listenablefuture:1.0`: 직접 POM의 누락을 공급자 저장소/상위 메타데이터의 Apache-2.0 근거로 보완했습니다.
- 라이선스 전문과 제공된 고지 파일을 APK에 포함했습니다. 라이브러리 변경 시 다시 생성하고, 최종 외부 배포 전 공급자 조건과 고지 범위를 검토합니다.

0.7.0 지도는 MapLibre Native Android 12.2.0(BSD-2-Clause), OpenFreeMap Liberty 스타일·타일·글꼴·스프라이트를 사용합니다. 지도에 OpenFreeMap / © OpenMapTiles / © OpenStreetMap contributors 링크를 표시합니다. 타일을 APK에 대량 내장하지 않으며 API 키를 사용하지 않습니다. 공급자 조건과 개인정보 흐름은 MAPS-070.txt에 기록했습니다. 외부 사진·음원·웹 UI 폰트는 추가하지 않았습니다. 하트 아이콘은 프로젝트 내 벡터 리소스이며 플랫폼 기본 글꼴을 사용합니다. 에뮬레이터의 차량 이미지와 시스템 UI는 앱이 제작한 자산으로 주장하지 않습니다.

기획안에 있는 자동 감지·공조 제어 등을 이미 구현했다는 표현과, 출처를 모르는 그래픽·데이터·시장 수치를 제출 자료에 넣지 않습니다. 개인 기획 PDF·서명 동의서는 Git에 포함하지 않습니다.


## 0.2.0 고지 보완

Gradle 최종 런타임 메타데이터 100개(BOM/제약 포함)를 다시 수집했습니다. 공개 라이브러리의 AAR/JAR LICENSE·NOTICE, Apache-2.0 전문, ZXing 3.5.3 LICENSE/NOTICE와 JSR-330 공급자 소스의 라이선스 헤더를 `THIRD-PARTY-NOTICES.txt`에 포함했습니다. 같은 파일은 APK assets에도 포함하고 정차 중 ‘기록 → 오픈소스 고지’에서 볼 수 있습니다. javax.inject, listenablefuture, ZXing의 상위/공급자 Apache-2.0 근거를 보완했습니다.

NaviHelper 3.0.5 / navi-aidl 3.0.1 / navi-data 3.0.4를 추가했습니다. Pleos SDK 구성 6개는 공급자 SDK 약관 적용 대상입니다. 사용자가 동의한 로컬 SDK 약관(2024-01-02 표기)의 3.1은 호환 플랫폼 앱 개발 사용 범위를 설명하며 3.4는 SDK 자체 재배포 등을 제한합니다. 이 문서가 별도 재배포 허가를 부여하지 않습니다. SDK 설치 파일·소스·키를 저장소에 추가하지 않으며, 공식 샘플과 최종 외부 배포의 구체적 이용 범위는 제출 전 공급자 조건과 대조해야 합니다.


Family Care 0.12 external services (2026-10-28)
Place/address search: TMAP POI API. https://tmapapi.tmapmobility.com/main.html
Car routing/traffic-aware estimated time: TMAP automobile route API.
Address of a pickup point or of where a memory photo was taken: TMAP reverse geocoding API (through the PC relay, not stored).
Rest stops on the remaining route: TMAP POI route-radius search when the app's products allow it, otherwise TMAP POI search at points along the route.
https://tmapapi.tmapmobility.com/terms.html
These proprietary services are governed by provider terms, not an open-source license.
REST API credentials are held on the PC relay; the native map SDK receives its credential at runtime through an explicitly approved authenticated session.
No response database or offline route bundle is distributed. TMAP Android Vector Map SDK 3.7 and VSM 2.0.14 are included under provider terms.
Since 0.12.1 the basemap is the official TMAP Android Vector Map SDK 3.7, with its logo and attribution visible. Previous MapLibre/OpenFreeMap entries describe older releases.
Vehicle only: Pleos FusedLocation 1.1.3 (provider SDK terms); capability/receipt diagnostics.
Android GPS remains the guidance position source. No conversion of SDK confidence into metre accuracy.


0.12.1 TMAP Vector Map SDK 3.7: https://tmapapi.tmapmobility.com/main.html / 이용약관 https://tmapapi.tmapmobility.com/terms.html
FlatBuffers Java 24.3.25 (Apache-2.0): https://github.com/google/flatbuffers/blob/v24.3.25/LICENSE
Material Components Android 1.6.1 (Apache-2.0): https://github.com/material-components/material-components-android/blob/1.6.1/LICENSE


0.13.0 PC 위치 선택 지도: Leaflet 1.9.4 (BSD-2-Clause), scripts/vendor/leaflet/LICENSE 포함.
https://leafletjs.com/ / https://github.com/Leaflet/Leaflet/tree/v1.9.4
OpenStreetMap 표준 타일: https://tile.openstreetmap.org/{z}/{x}/{y}.png
출처 표시, 브라우저 캐시·Referer 유지, 사전/대량 다운로드 없음.
타일 정책: https://operations.osmfoundation.org/policies/tiles/
선택형 외부 IP Webcam 앱은 별도 Google Play 설치이며 APK를 재배포하지 않음.
https://play.google.com/store/apps/details?id=com.pas.webcam


## 0.19 ~ 0.20.6 추가 구성요소 (2026-11-08 정리)

차량 APK에 들어가는 것
- Pleos SpeechToText 3.3.1: 공급자 SDK 약관 적용(오픈소스 아님). 운전자가 음성 버튼을 누른 뒤 한국어를 기기 안에서 인식합니다. 앱 내 고지 `android/app/src/main/assets/third_party_notices.txt` 끝에 추가했습니다.

APK에 들어가지 않고 시연용 PC에서만 쓰는 것 (설치 스크립트가 공식 배포처에서 받음)
- multilingual E5 small (MIT) 및 Xenova ONNX 변환본: 음성 문장 뜻 분류. `docs/E5-MIT-LICENSE.txt`, `docs/VOICE-UNDERSTANDING.txt`
- onnxruntime 1.23.2 (MIT), tokenizers 0.22.1 (Apache-2.0), numpy 2.3.4 (BSD-3-Clause): 음성 모델 실행환경 `scripts/requirements-voice.txt`
- qrcode 8.2 (BSD) / Pillow (MIT-CMU): 시연 보조 화면의 QR 이미지 `scripts/requirements-tablet.txt`
- cloudflared 2026.10.7 (Apache-2.0): 외부 연결 임시 주소. `docs/CLOUDFLARED-LICENSE.txt`

저장소에 함께 들어 있는 외부 파일
- scrcpy 3.3.4 서버 (Apache-2.0): 태블릿 차량 화면 전송. `scripts/vendor/scrcpy-3.3.4/LICENSE.txt`
- Leaflet 1.9.4 (BSD-2-Clause): 시연 보조 화면 지도. `scripts/vendor/leaflet/LICENSE`

서명 키, API 키(`tmap.local.json`), TMAP SDK AAR(`android/local-sdk/`), 빌드 결과(`dist/`)는 저장소에 넣지 않습니다.

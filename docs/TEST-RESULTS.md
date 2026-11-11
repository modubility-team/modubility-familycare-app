> 아래는 기존 환경·샘플 시험 기록입니다. 최신 Family Care 0.2.0 결과는 [CORE-TEST-RESULTS.txt](CORE-TEST-RESULTS.txt), 사용법은 [CORE-DEMO.md](CORE-DEMO.md)를 보세요.

# 0.1.1 공모전 기준 반영 검증 (2026-10-26)

- Pleos Connect 2.1.0 / API 34 x86_64 / Vehicle 2.3.2 / debug 0.1.1.
- `testDebugUnitTest`: 12개 통과, 실패/오류 0. 도어 6개, UX 정책·서비스 상실·늦은 콜백·문자열 제한 6개.
- 에뮬레이터 instrumentation: 3개 통과. 제한 중 하차 문구/조작 숨김, 미확인 상태, 320dp 배치와 항목 접근, 실제 차량 연결 상태에서 두 화면 크기와 foreground 복귀.
- `assembleDebug`, `assembleRelease`, `lintDebug`, `lintRelease` 성공. release는 키가 없는 **unsigned** 빌드이며 제출용/설치용이 아님.
- lint 오류 0, 경고 27: 고정된 도구·의존성보다 새 버전이 있다는 안내. 동작 중인 조합을 무조건 최신으로 변경하지 않음.
- 실제 조종판 가상 주행에서 Car service `DO=true, UxR=80`; Family Care는 '운전에 집중해 주세요'만 표시. 문이 열린 상태에서도 하차 안내는 숨겨짐.
- P단·0km/h 복원 후 `DO=false, UxR=0`; 문 상태 안내 복귀 및 문 닫힘 반영 확인. 실제 차량 시험은 아님.
- 세로 표시는 에뮬레이터의 좌우 여백 안에서 동작. 두 앱을 나란히 사용하는 플랫폼 창 관리 동작은 별도 검증 필요.
- 짧은 주행 표본: `top` 2초 간격 4회 모두 앱 CPU 0.0%; PSS 61,679KiB(60.2MiB), RSS 153,616KiB(150.0MiB). 프로세스가 떠 있는 안정 구간의 debug 표본이며 최대 사용량·장시간·실차·release 성능 보장이 아님.
- `packageSubmission`을 서명 설정 없이 실행하면 명시적으로 실패하고 apk 폴더에 제출본을 만들지 않는 것을 확인.
- 서비스 연결 실패/종료 뒤 콜백은 단위·UI 테스트의 모의 상황으로 확인. 실제 car service를 강제 종료해 검증하지 않음.

다음은 이전 SDK 범위 시험 기록입니다. 샘플에서 지원을 확인한 것이 현재 Family Care 앱의 구현 완료를 뜻하지 않습니다.

# 에뮬레이터 기능 확인

2026-10-26, Pleos Connect Emulator 2.1.0, Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5 기준입니다.
실제 차량의 지원 여부나 공모전 권한 승인을 의미하지는 않습니다.

| 항목 | 실측 결과 |
|---|---|
| 도어 | 조회·열림/닫힘 알림 정상 |
| 브레이크 | 조회·눌림/해제 알림 정상 |
| 주차 브레이크 | Android 표준 API로 false 조회 성공. true 전환은 미검증 |
| 핸들 | 각도 조회·변경 알림 정상. 조종판과 SDK 부호가 반대 |
| 배터리 | 잔량 100→80% 변경 알림 정상 |
| 실내 온도 | 현재 온도 19.1°C 조회. 열적 변화는 미검증 |
| 공조 설정 | SDK로 23°C 변경 후 재조회 성공, 22°C 복원 |
| 창문 | ROW_1_LEFT로 조회·변경 알림 정상. SDK 5가 조종판 50%에 해당 |
| 속도 | Android 표준 API로 변화 확인. m/s를 km/h로 바꿀 때 ×3.6 |
| 기어 | GEAR_SELECTION P=4, D=8 확인. CURRENT_GEAR는 4로 남음 |
| 시동 | IGNITION_STATE가 현재 이미지의 지원 속성에 없음 |
| 내비 | 목적지·경유지 추가와 조회 반영. 응답 좌표 0,0 또는 요청 좌표 불일치에 주의 |
| 주행 제한 | 주행 시 CarUxRestrictions 콜백 정상. 가이드의 hmg_car_service 강제 명령은 서비스 없음 오류 |
| 유틸리티 모드 | 공식 명령이 사용하는 /dev/vcs_simulator_rx 장치가 현재 이미지에 없음 |

Vehicle 샘플의 `app/src/androidTest`와 Navi 샘플의 `app/src/androidTest`에 별도 계측 코드가 있습니다.
계측 실행은 시험용 에뮬레이터에서만 하세요. live 시험은 약 4분 동안 구독하며 Console 입력이 필요합니다.

첫 기능은 운전석 문이 열릴 때 소지품 확인 문구를 띄우는 작은 앱으로 시작할 수 있습니다.
이는 실제 물건의 남아 있음이나 시동 OFF를 감지하는 기능과는 구분해야 합니다.

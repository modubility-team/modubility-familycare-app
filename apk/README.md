# 제출용 APK

이 폴더에는 공모전 제출용 release APK가 들어갑니다.

| 항목 | 내용 |
| --- | --- |
| 파일 | `모두빌리티_WithME_release.apk` (규칙: `팀명_앱이름_release.apk`) |
| 앱 | WithME(위드미) 차량용 앱 · 패키지 `com.familycare.navigation` · 버전 0.21.0 |
| 서명 | 팀 모두빌리티의 release 키 (Android debug 키 아님) |
| 실행 기기 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14 / API 34) · 화면 2560×1440 (가로) |

설치: `adb install 모두빌리티_WithME_release.apk`
실행 방법과 필요한 설정은 저장소 맨 위 [README](../README.md)를 보세요.

- 휴대폰 보조 앱 2종(WithME 내 차, WithME 가족)과 태블릿 앱은 제출 대상이 아닙니다.
  `01_FirstSetup.cmd`를 실행하면 `dist/apk`에 개발용으로 만들어집니다.
- 다시 만드는 방법: `07_CreateReleaseKey.cmd`(팀에서 한 번) → `08_BuildReleaseApk.cmd`.
  자세한 절차는 [docs/RELEASE.md](../docs/RELEASE.md)에 있습니다.
- 서명 키(.jks)와 `keystore.properties`는 이 저장소에 올리지 않습니다.

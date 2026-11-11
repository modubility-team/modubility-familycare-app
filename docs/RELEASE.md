# 제출용 release APK 만들기

팀명 **모두빌리티**, 앱명 **WithME(위드미)**로 확정했습니다 (2026-11-11 변경, 이전 이름 패밀리케어앱).
제출 파일은 `apk/모두빌리티_WithME_release.apk`입니다. 공모전 규칙은 `apk/팀명_앱이름_release.apk`입니다.
제출 대상은 Pleos 차량 앱(`com.familycare.navigation`, 0.20.15)입니다. 휴대폰·태블릿 보조 앱은 제출 대상이 아닙니다.

팀명·앱명은 `android/gradle.properties`의 `teamName`, `appName`에 들어 있습니다.
한글이 깨지지 않도록 `\uXXXX` 형식으로 적었습니다. 바꿀 때는 같은 형식으로 쓰거나 `-PteamName=… -PappName=…`으로 덮어씁니다.

## 가장 쉬운 방법 (Windows)

준비: `01_FirstSetup.cmd`가 성공한 PC여야 합니다.

1. **`07_CreateReleaseKey.cmd`** — 팀 서명 키 만들기 (팀 전체에서 한 번만)
   - 영문·숫자 8~32자 비밀번호를 두 번 입력합니다.
   - 키 파일은 프로젝트 밖 `%USERPROFILE%\FamilyCareReleaseKey\modubility-familycare-release.jks`에 만들어집니다.
   - 서명 설정 `android/keystore.properties`도 자동으로 만들어집니다. Git이 이 파일을 무시합니다.
   - **키 파일과 비밀번호를 바로 백업하세요.** 잃어버리면 같은 앱으로 업데이트할 수 없습니다.
   - 팀원이 이미 키를 만들었다면 새로 만들지 말고 그 `.jks`와 `keystore.properties`를 받아서 씁니다.
     이때 `storeFile` 경로는 내 PC에 맞게 고칩니다.
2. **`08_BuildReleaseApk.cmd`** — 단위 테스트 → release 빌드 → 서명 확인
   - 성공하면 `apk/모두빌리티_WithME_release.apk`가 생기고, 서명 인증서 정보와 SHA-256이 표시됩니다.
   - 이미 같은 파일이 있으면 `apk/old/`로 옮길지 묻습니다. `apk/old/`는 Git에 올라가지 않습니다.
3. 확인 후 `apk/` 폴더를 GitHub에 올립니다.

## 직접 명령으로 할 때

1. 키 만들기: Android Studio → Build → Generate Signed App Bundle or APK → APK → Create new… 로 만들거나 JDK 17의 `keytool`을 씁니다.
2. `android/keystore.properties.example`을 같은 폴더의 `keystore.properties`로 복사하고 값을 채웁니다.
   - 경로는 `/`로 씁니다.
   - 실제 파일은 Git에서 제외됩니다.
   - 다른 위치의 설정 파일을 쓰려면 `FAMILYCARE_SIGNING_PROPERTIES` 환경 변수로 지정합니다.
3. `android` 폴더에서 JDK 17로 실행합니다.
   ```
   gradlew.bat :app:testDebugUnitTest :app:packageSubmission
   ```
4. 서명 확인: `%LOCALAPPDATA%\Android\Sdk\build-tools\35.0.0\apksigner.bat verify --print-certs apk\모두빌리티_WithME_release.apk`

`packageSubmission`은 다음 경우 실패합니다.
- 서명 설정이 없을 때
- Android debug 키를 쓸 때
- 팀명·앱명이 없을 때
- 같은 이름의 파일이 이미 있을 때

단순 `assembleRelease` 결과는 서명되지 않은 파일일 수 있으므로 제출물로 쓰지 않습니다.

## 제출 전 확인

1. 새로 켠 Pleos 에뮬레이터에 설치합니다: `adb install apk\모두빌리티_WithME_release.apk`
   - 같은 에뮬레이터에 개발용 앱이 있으면 서명이 달라 설치가 거절됩니다.
   - 먼저 `adb uninstall com.familycare.navigation`을 실행합니다.
2. 연결 서버를 켜고(`05_DeveloperMenu.cmd` → 2, 다시 열고 → 6) 다음을 확인합니다.
   - 지도 표시
   - 동승자 연결 QR
   - 정차·주행 전환 시 화면 제한
   - 요청 승인
3. 확인을 마치면 `02_StartDemo.cmd`로 돌아가기 전에 release 앱을 지웁니다.
   - 02는 개발용 앱과 시연용 테스트 앱을 설치하기 때문입니다.
4. 기록할 것: APK SHA-256, 확인한 기능, 확인 날짜.
5. 비공개 저장소라면 공지된 심사용 계정을 초대하고 접근을 확인합니다.
   - 개인 계정 저장소의 Collaborator는 쓰기 권한도 갖습니다.
   - 읽기 권한만 주려면 Organization 저장소를 씁니다.

원본 기획서, 동의서, 개인정보, 서명 키, 비밀번호, `tmap.local.json`은 Git에 넣지 않습니다.

# 두 사람이 함께 작업하는 방법

GitHub는 팀의 코드 보관소입니다. Android Studio는 각자의 컴퓨터에서 사용합니다.

Windows 신규 팀원은 `WINDOWS-TEAM-SETUP.txt`의 설치 순서와 저장소 루트 `FamilyCare.cmd`를 사용하세요. 개인 경로는 `familycare.local.json`에 지정하고 공유하지 않습니다.

## 처음 참여하기

1. 저장소 주인이 GitHub 저장소의 Settings → Collaborators에서 친구의 GitHub 아이디를 초대합니다.
2. 친구가 초대를 수락합니다.
3. 친구는 Android Studio의 Get from VCS 또는 Clone Repository에서 이 저장소 주소를 입력합니다.
4. 각자 Git 커밋 작성자 이름과 이메일을 설정합니다. 이메일을 공개하고 싶지 않으면 GitHub Settings → Emails의 noreply 주소를 사용합니다.
5. Android SDK 경로와 JDK 17은 각 PC에서 따로 설정합니다.

## 작업할 때마다

1. 시작 전에 `main`의 최신 코드를 받습니다(Pull).
2. 작업할 기능의 새 브랜치를 만듭니다. 예: `feature/door-reminder`.
3. 코드를 수정하고 자신의 PC에서 실행해 봅니다.
4. 변경 내용을 설명하며 Commit한 뒤 자신의 브랜치를 Push합니다.
5. GitHub에서 Pull request를 열어 친구가 확인하게 합니다.
6. 확인 후 `main`으로 Merge하고, 다른 사람은 다시 Pull합니다.

처음에는 한 사람은 화면, 다른 사람은 차량 데이터 연결처럼 담당을 나누면 편합니다.
같은 파일의 같은 부분을 동시에 고치면 합칠 때 충돌이 생길 수 있습니다.

GitHub는 실시간 공동 편집 화면이 아닙니다. 함께 볼 때는 화면 공유를 병행할 수 있습니다.
지금은 `main` 보호 규칙을 별도로 설정하지 않았습니다.

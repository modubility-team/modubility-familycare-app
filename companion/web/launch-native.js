"use strict";
// Browser QR landing page -> two separately installed Android apps. No new privileges.
function renderNativeApps() {
  const nativeSection = document.getElementById("nativeApps");
  const details = document.createElement("details");
  const heading = document.createElement("summary");
  heading.textContent = "설치된 휴대폰 앱으로 열기";
  const explanation = document.createElement("p");
  explanation.className = "small native-description";
  explanation.textContent = "소유자 QR은 내 차 앱에서만, 가족·손님 QR은 WithME 가족 앱에서만 열립니다. 이 페이지는 간단한 웹 동승자 화면이며 가족 설정·타이머·공조 보조·기록·여행 앨범·백그라운드 알림은 설치 앱을 사용합니다.";
  details.append(heading,explanation);
  if (/^#[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{43}$/.test(location.hash)) {
    const links = document.createElement("div"); links.className = "quick";
    // Only the app that matches this link: an owner link never opens the family (care) app and vice versa.
    const known = typeof role === "string" ? role : "";
    const apps = known === "owner" ? [["navigation","소유자 · 내 차 앱 열기"]]
      : ["family","guest","observer"].includes(known) ? [["care","동승자 · WithME 가족 열기"]] : [];
    if (!apps.length) {
      const wait = document.createElement("p"); wait.className = "small";
      wait.textContent = "연결 정보를 확인하고 있어요. 이 QR에 맞는 앱 버튼이 곧 나타납니다.";
      links.append(wait);
    }
    for (const [product,label] of apps) {
      const anchor = document.createElement("a");
      anchor.textContent = label; anchor.className = "native-launch";
      anchor.href = `familycare-${product}://join?url=${encodeURIComponent(location.href)}`;
      links.append(anchor);
    }
    details.append(links);
  } else {
    const hint = document.createElement("p"); hint.className = "small";
    hint.textContent = "휴대폰에 APK를 설치한 뒤 차량 QR을 열면 앱 연결 버튼이 나타납니다.";
    details.append(hint);
  }
  nativeSection.replaceChildren(details);
}
window.addEventListener("familycare:connection",renderNativeApps);
renderNativeApps();

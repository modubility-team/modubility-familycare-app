"use strict";
const $ = id => document.getElementById(id);
let sid = "", token = "", role = "", stopped = false, busy = false, baseline = null, lastDistance = null;
let client = sessionStorage.getItem("familyCareClient");
if (!client) { client = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(36).slice(2); sessionStorage.setItem("familyCareClient", client); }
const notified = new Set();
let pendingRequest = null;
let liveConnection = false;
function updateConnectionLabel() { $("connectionLabel").textContent = document.body.dataset.view === "connection" ? "돌아가기" : !sid ? "연결" : liveConnection ? "연결됨" : "연결 확인"; }
function connectHash(hash) {
  const m = hash.match(/^#([A-Za-z0-9_-]{16})\.([A-Za-z0-9_-]{43})$/);
  if (!m) return false;
  [,sid,token] = m; stopped = false; $("join").hidden = true; $("connected").hidden = false; $("bottomNav").hidden = false; setView("home");
  return true;
}
async function api(action, body = {}) {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(`/api/sessions/${sid}/${action}`, {method:"POST", headers:{"Content-Type":"application/json",Authorization:`Bearer ${token}`},body:JSON.stringify(body),signal:controller.signal});
    const data = await response.json();
    if (!response.ok) { if (response.status === 410 || response.status === 403 && action === "status") stopped = true; const error = new Error(data.error || "연결 상태를 확인하세요"); error.status = response.status; throw error; }
    return data;
  } finally { clearTimeout(timer); }
}
function text(tag, value, className) { const e = document.createElement(tag); e.textContent = value; if(className)e.className = className; return e; }
function statusLabel(s) { return ({pending:"정차 후 확인 대기",executing:"차량에서 확인 중",confirmed:"확인 완료",needs_review:"내비에서 확인 필요",rejected:"이번에는 보류"})[s] || s; }
function stale(message) { liveConnection=false; updateConnectionLabel(); $("statusDot").classList.remove("online"); $("batteryLevel").value=0; $("connection").textContent = message; $("mode").textContent = "연결 확인 필요"; ["temperature","battery","driving"].forEach(id => $(id).textContent = "미확인"); $("closures").textContent = "차량의 현재 상태를 확인할 수 없어요. 직접 확인해 주세요."; $("arrival").textContent = "현재 도착 정보 미확인"; $("remaining").textContent = "연결이 복구되면 다시 표시합니다."; $("progress").hidden = true; }
function render(data) {
  const roleChanged = role !== data.role;
  role = data.role;
  // The native-app buttons depend on the link's role (owner -> 내 차, others -> Family Care).
  if (roleChanged) window.dispatchEvent(new Event("familycare:connection")); $("role").textContent = ({family:"가족 연결",guest:"손님 연결",observer:"상태 확인 연결"})[role] || "차량";
  $("requestNav").hidden = role === "observer";
  if(role === "observer" && document.body.dataset.view === "request") setView("home");
  $("requestSection").hidden = role === "observer";
  $("quickSection").hidden = role === "observer";
  $("requestsSection").hidden = role === "observer";
  $("requestsTitle").textContent = "요청 내역";
  $("votingHint").hidden = role !== "family";
  Array.from($("kind").options).forEach(o => o.disabled = role === "guest" && o.value !== "note");
  if (role === "guest") { $("kind").value = "note"; $("coordinates").hidden = true; }
  const s = data.state;
  document.querySelectorAll('[data-climate-delta],[data-climate-power]').forEach(b => {
    b.disabled=busy||data.stale||!['owner','family'].includes(role)||!s.climateSupported||(b.dataset.climatePower!==undefined&&!s.climatePowerSupported);
  });
  $('climateSummary').textContent=data.stale?'공조 상태 미확인':`공조 ${s.climatePower==null?'미확인':s.climatePower?'켜짐':'꺼짐'} · 설정 ${s.climateTarget==null?'미확인':s.climateTarget+'°C'}`;
  if (data.stale) stale("차량 연결이 끊겼거나 응답을 기다리고 있어요");
  else {
    liveConnection=true; updateConnectionLabel();
    $("connection").textContent = `차량과 연결됐어요 · 약 ${Math.ceil(data.expiresIn / 60)}분 후 연결 종료`;
    $("mode").textContent = s.mode || "이동 준비";
    $("statusDot").classList.add("online");
    $("batteryLevel").value = typeof s.battery === "number" ? Math.max(0,Math.min(100,s.battery)) : 0;
    $("temperature").textContent = s.temperature == null ? "미확인" : `${Number(s.temperature).toFixed(1)}°C`;
    $("battery").textContent = s.battery == null ? "미확인" : `${s.battery}%`;
    $("driving").textContent = `${s.drivingMinutes || 0}분`;
    $("closures").textContent = (s.closures || "문·창문 상태 미확인") + (s.updatedAt ? " · 갱신 " + new Date(s.updatedAt).toLocaleTimeString("ko-KR") : "");
    $("arrival").textContent = s.arrived ? "목적지 도착을 확인했어요" : s.running ? "함께 가고 있어요" : "다음 이동을 기다려요";
    const d = s.remainingMeters;
    if (typeof d === "number" && d >= 0) {
      if (typeof s.routeTotalMeters === "number" && s.routeTotalMeters > 0) baseline = s.routeTotalMeters; else if (baseline == null || lastDistance != null && d > lastDistance * 1.2) baseline = d;
      lastDistance = d;
      $("remaining").textContent = `남은 거리 ${(d/1000).toFixed(1)}km` + (s.remainingSeconds == null ? "" : ` · 약 ${Math.ceil(s.remainingSeconds/60)}분`);
      $("progress").hidden = !baseline; $("progress").value = baseline ? Math.max(0,Math.min(100,100*(1-d/baseline))) : 0;
    } else { baseline = null; lastDistance = null; $("remaining").textContent = "내비에서 확인한 남은 거리·시간이 아직 없어요."; $("progress").hidden = true; }
  }
  $("alertCount").textContent = data.alerts.length; $("alertCount").hidden = !data.alerts.length;
  $("noAlerts").hidden = !!(data.alerts.length || data.stale);
  const alertBox = $("alerts"); alertBox.replaceChildren();
  $("alertSection").hidden = !(data.alerts.length || data.stale);
  if(data.stale) alertBox.append(text("p","연결이 끊긴 동안에는 차량 상태 알림도 갱신되지 않아요.","error"));
  data.alerts.forEach(a => {
    const card = text("article","",`alert${a.escalated ? " escalated" : ""}`);
    card.append(text("span",data.stale ? "마지막 수신 알림 · 현재 상태 미확인" : a.escalated ? "아직 확인되지 않았어요 · 함께 확인해 주세요" : "차량에서 온 확인 요청","badge"),text("strong",a.title));
    if(!a.acknowledged && role !== "guest") { const b = text("button","직접 확인했어요","secondary"); b.onclick = () => action(b,()=>api("ack",{code:a.code}),"직접 확인한 내용을 차량에 전달했어요."); card.append(b); }
    else if(a.acknowledged) card.append(text("p","가족이 확인했어요. 차량 상태가 해소되면 알림이 사라집니다."));
    alertBox.append(card);
    const key = a.code + ":" + a.since + ":" + a.escalated;
    if(!data.stale && !a.acknowledged && !notified.has(key) && "Notification" in window && Notification.permission === "granted") {
      try { new Notification("WithME",{body:a.title}); notified.add(key); }
      catch { $("notifications").textContent="이 브라우저에서는 화면 알림을 확인해 주세요"; }
    }
  });
  const list = $("requests"); list.replaceChildren();
  if(!data.requests.length) list.append(text("p","아직 요청이 없어요."));
  [...data.requests].reverse().forEach(r => {
    const card = text("article","","request");
    card.append(text("span",statusLabel(r.status),"badge"),text("strong",r.title),text("p",r.message || ""));
    if(["destination","waypoint"].includes(r.kind)) { card.append(text("p",`함께 가고 싶어요 ${r.votes}표`)); if(role === "family" && r.status === "pending") { const b = text("button","나도 좋아요","secondary"); b.onclick = () => action(b,()=>api("vote",{id:r.id,client})); card.append(b); } }
    list.append(card);
  });
}
async function poll() { if(!sid || stopped) return; try {render(await api("status"));} catch(e) {stale(stopped ? e.message : "차량 연결 확인 중 · 현재 상태 미확인");} if(!stopped)setTimeout(poll,2500); }
async function action(button, fn, successMessage = "전달했어요. 정차 후 운전자가 확인합니다.") { if(busy)return; busy = true; button.disabled = true; try {await fn(); render(await api("status")); $("message").textContent = successMessage;} catch(e){$("message").textContent = e.message;} finally{busy=false;button.disabled=false;} }
async function request(kind,title,latitude,longitude,extra={}) {
  const signature = JSON.stringify([kind,title,latitude,longitude,extra]);
  if(!pendingRequest || pendingRequest.signature !== signature) pendingRequest = {signature,body:{id:crypto.randomUUID ? crypto.randomUUID() : String(Date.now())+Math.random(),client,kind,title,latitude,longitude,...extra}};
  try {
    const body=pendingRequest.body; let result;
    try { result=await api("request",body); }
    catch(e) {
      if(e.status!==429 || !e.message.includes('잠시 후 다시 보내세요')) throw e;
      await new Promise(resolve=>setTimeout(resolve,2100));
      if(stopped || !sid) throw Error('차량 연결이 종료됐어요');
      result=await api("request",body);
    }
    pendingRequest = null; return result;
  }
  catch(e) { if(e.status) pendingRequest=null; else e.message="전달 여부를 확인하지 못했어요. 같은 요청을 다시 누르면 중복 없이 확인합니다."; throw e; }
}
document.querySelectorAll("[data-note]").forEach(b=>b.onclick=()=>action(b,()=>request("note",b.dataset.note)));
document.querySelectorAll('[data-climate-delta],[data-climate-power]').forEach(b=>b.onclick=()=>action(b,async()=>{
  const data=await api('status'),s=data.state;
  if(data.stale||!['owner','family'].includes(data.role)||!s.climateSupported)throw Error('차량의 공조 제어 권한과 연결 상태를 확인하세요');
  const z=(s.climateAreas||[]).find(z=>typeof z.target==='number'),current=z?.target??s.climateTarget;
  if(typeof current!=='number')throw Error('현재 설정 온도를 확인할 수 없어요');
  const delta=Number(b.dataset.climateDelta||0),target=Math.max(z?.min??18,Math.min(z?.max??28,Math.round((current+delta)*2)/2));
  const options={area:z?.id??'ROW_1_LEFT'};
  if(s.climatePowerSupported)options.power=b.dataset.climatePower==='off'?false:true;
  if(b.dataset.climatePower!==undefined&&!s.climatePowerSupported)throw Error('이 차량은 공조 전원 제어를 지원하지 않아요');
  return request('climate',options.power===false?'공조 끄기 요청':`설정 온도 ${target}°C 요청`,undefined,undefined,{target,options});
}));
$("kind").onchange=()=>$("coordinates").hidden=$("kind").value==="note";
$("samplePlace").onclick=()=>{$("title").value="42dot";$("latitude").value="37.48544722";$("longitude").value="127.03636666";};
$("requestForm").onsubmit=e=>{e.preventDefault();const k=$("kind").value;if(k!=="note"&&(!$("latitude").value||!$("longitude").value)){$("message").textContent="위도와 경도를 모두 입력하세요";return;}action($("send"),async()=>{await request(k,$("title").value.trim(),k==="note"?undefined:Number($("latitude").value),k==="note"?undefined:Number($("longitude").value));$("title").value="";});};
$("connect").onclick=()=>{try{const u=new URL($("joinLink").value);if(u.origin!==location.origin)throw Error();if(!connectHash(u.hash))throw Error();history.replaceState(null,"",u.hash);window.dispatchEvent(new Event("familycare:connection"));poll();}catch{$("connection").textContent="이 서버의 차량 연결 링크를 정확히 붙여 넣어 주세요.";}};
$("notifications").onclick=async()=>{try{if(!("Notification" in window))throw Error();const p=await Notification.requestPermission();$("notifications").textContent=p==="granted"?"페이지 알림이 켜졌어요":"브라우저 알림이 허용되지 않았어요";}catch{$("notifications").textContent="이 브라우저에서는 화면 알림을 확인해 주세요";}};
$("leave").onclick=()=>{stopped=true;token="";sid="";role="";baseline=null;lastDistance=null;pendingRequest=null;notified.clear();history.replaceState(null,"",location.pathname);$("connected").hidden=true;$("join").hidden=false;$("bottomNav").hidden=true;$("quickSection").hidden=true;$("message").textContent="";setView("home");$("mode").textContent="연결 전";$("role").textContent="동승자";$("statusDot").classList.remove("online");["temperature","battery","driving"].forEach(id=>$(id).textContent="—");$("batteryLevel").value=0;$("connection").textContent="이 페이지의 연결을 해제했어요. 전체 연결 종료는 차량에서 할 수 있어요.";window.dispatchEvent(new Event("familycare:connection"));};
function setView(view) {
  if(role === "observer" && view === "request") view = "home";
  document.body.dataset.view = view;
  $("appTitle").textContent = ({home:"케어",request:"요청",alerts:"알림",connection:"연결"})[view];
  updateConnectionLabel();
  document.querySelectorAll("#bottomNav [data-view]").forEach(b => {
    if(b.dataset.view === view) b.setAttribute("aria-current","page"); else b.removeAttribute("aria-current");
  });
  window.scrollTo({top:0,behavior:"instant"});
}
document.querySelectorAll("button[data-view]").forEach(b=>b.onclick=()=>setView(b.dataset.view));
$("connectionToggle").onclick=()=>setView(document.body.dataset.view === "connection" ? "home" : "connection");
if(connectHash(location.hash))poll();
window.addEventListener("hashchange",()=>location.reload());

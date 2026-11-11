'use strict';
const $=id=>document.getElementById(id);
let working=false,oldLinks='',state={},selection='origin',origin=null,destination=null,originMarker,destinationMarker,carMarker,centered=false,cameraViewing=false,cameraBusy=false;
let cameraGeneration=0,cameraSaving=false,cameraAddressDirty=false;
let syncedNavigation='',syncedRoute='',navigationLine;
const map=typeof L!=='undefined'?L.map('map').setView([36.3,127.7],7):null;
if(map){
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}).on('tileerror',()=>{$('mapNotice').textContent='지도 연결을 확인하세요. 검색 결과의 위치 선택은 계속 사용할 수 있어요.'}).addTo(map);
  map.on('click',e=>choose([e.latlng.lng,e.latlng.lat],'지도에서 선택한 곳'));
}else $('mapNotice').textContent='지도 파일을 읽지 못했어요. 서버를 다시 실행하세요.';
function coords(p){return p[1].toFixed(6)+', '+p[0].toFixed(6)}
function marker(old,p,color){if(!map)return; if(old)return old.setLatLng([p[1],p[0]]);return L.circleMarker([p[1],p[0]],{radius:9,color,fillColor:color,fillOpacity:.8,weight:3}).addTo(map)}
function choose(p,name){
  if(selection==='origin'){origin={point:p,name};originMarker=marker(originMarker,p,'#008659');$('originLabel').textContent='선택한 현재 위치: '+name+' · '+coords(p)}
  else {destination={point:p,name};destinationMarker=marker(destinationMarker,p,'#d86500');$('destinationLabel').textContent='선택한 목적지: '+name+' · '+coords(p)}
  controls();
}
function controls(){
  const blocked=working||state.busy||!state.connected;
  const tabletGps=state.gpsSource==='tablet';
  $('tabletStatus').textContent=tabletGps?(state.tabletGpsFresh?'탭 GPS 수신 중 · 실제 위치로 안내합니다':'탭 GPS 대기 · 탭의 위치와 Wi-Fi를 확인하세요'):'실내 GPS 재생 모드 · 탭에서 GPS 시작을 누르면 전환됩니다';
  document.querySelectorAll('[data-action]').forEach(b=>b.disabled=blocked||(tabletGps&&b.dataset.action!=='pause')||(b.dataset.action==='start'&&!state.hasRoute));
  const unapplied=origin&&JSON.stringify(origin.point)!==JSON.stringify(state.origin);
  const searchReady=state.connected&&state.sharing;
  $('applyPosition').disabled=blocked||tabletGps||!origin;$('planRoute').disabled=blocked||tabletGps||!destination||unapplied;$('searchButton').disabled=working||state.busy||!searchReady;
  $('connectionRecovery').hidden=Boolean(searchReady);$('reconnect').disabled=Boolean(working||state.busy);
  $('reconnect').textContent=working||state.busy?'연결 복구 / 처리 중…':'차량 연결 복구';
  $('searchStatus').textContent=searchReady?'':state.connected?'가족 연결이 종료되어 검색할 수 없어요. 차량 연결 복구 후 새 QR로 연결하세요.':'차량 연결이 종료되었거나 응답이 없어 검색 버튼을 잠시 사용할 수 없어요.';
  $('searchButton').title=searchReady?'장소 검색':$('searchStatus').textContent;
  $('planRoute').title=unapplied?'선택한 현재 위치를 먼저 적용하세요':'';
}
async function post(path,body){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Demo-Key':key},body:JSON.stringify(body)});const result=await r.json();if(!r.ok)throw Error(result.error||'요청에 실패했어요');return result}
async function act(body){if(working)return;working=true;controls();$('error').textContent='처리 중…';try{await post('/api/action',body);$('error').textContent='완료했어요.'}catch(e){$('error').textContent=e.message}finally{working=false;await refresh()}}
for(const mode of ['origin','destination'])$('select'+(mode==='origin'?'Origin':'Destination')).onclick=()=>{selection=mode;$('selectOrigin').setAttribute('aria-pressed',mode==='origin');$('selectDestination').setAttribute('aria-pressed',mode==='destination')};
$('centerCar').onclick=()=>{const p=state.position||state.emulatorPosition;if(p&&map)map.setView([p[1],p[0]],16)};
$('reconnect').onclick=()=>act({action:'reconnect'});
$('applyPosition').onclick=()=>{if(origin)act({action:'position',point:origin.point})};
$('planRoute').onclick=()=>{if(destination)act({action:'plan',point:destination.point,name:destination.name})};
document.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>act({action:b.dataset.action,speed:Number($('speedChoice').value)}));
$('searchForm').onsubmit=async e=>{e.preventDefault();const query=$('query').value.trim();if(!query||working)return;working=true;controls();$('error').textContent='TMAP에서 찾는 중…';$('searchResults').replaceChildren();try{
  const c=map?.getCenter(),center=c?[c.lng,c.lat]:(state.emulatorPosition||[127.7,36.3]);const result=await post('/api/search',{query,center});
  for(const p of result.places||[]){const b=document.createElement('button');b.type='button';b.className='result';const n=document.createElement('b');n.textContent=p.name;const d=document.createElement('small');d.textContent=p.detail;b.append(n,d);b.onclick=()=>{choose([p.longitude,p.latitude],p.name);map?.setView([p.latitude,p.longitude],16);$('searchResults').replaceChildren();$('error').textContent=selection==='origin'?'선택한 현재 위치 적용을 눌러 주세요.':'선택한 목적지로 길찾기를 눌러 주세요.'};$('searchResults').append(b)}
  $('error').textContent=result.places?.length?'결과를 누르면 현재 선택 모드의 위치로 지정돼요.':'검색 결과가 없어요. 다른 장소명·주소를 입력하세요.';
}catch(e){$('error').textContent=e.message}finally{working=false;controls()}};
function clearLinks(){oldLinks='';for(const role of ['owner','family']){$(role+'Link').removeAttribute('href');$(role+'Qr').removeAttribute('src')}}
function syncNavigation(s){
  const p=s.destinationPoint,id=s.navigationStartId;
  if(!s.connected||!s.active||!id||!Array.isArray(p)||p.length!==2||!p.every(Number.isFinite))return;
  if(id!==syncedNavigation){
    syncedNavigation=id;destination={point:p.slice(),name:s.destination||'차량 내비 목적지'};origin=null;
    if(originMarker&&map){map.removeLayer(originMarker);originMarker=null}
    destinationMarker=marker(destinationMarker,p,'#d86500');
    $('destinationLabel').textContent='내비에서 받은 목적지: '+destination.name+' · '+coords(p);
    $('originLabel').textContent='현재 위치: 차량 GPS와 연결됨';
  }
  const routeId=s.navigationRouteId||id;
  if(routeId!==syncedRoute&&map&&Array.isArray(s.navigationRoute)&&s.navigationRoute.length>1){
    syncedRoute=routeId;
    if(navigationLine)map.removeLayer(navigationLine);
    navigationLine=L.polyline(s.navigationRoute.map(p=>[p[1],p[0]]),{color:'#006ce0',weight:5}).addTo(map);
    map.fitBounds(navigationLine.getBounds(),{padding:[25,25],maxZoom:16});
  }
}
async function refresh(){try{
  state=await(await fetch('/api/state',{cache:'no-store'})).json();const s=state;syncNavigation(s);renderExternal(s);
  $('mode').textContent=s.connected?s.mode:'차량 앱 응답 중단';$('message').textContent=s.messagePanel;
  $('speed').textContent=s.connected?s.speed+' km/h':'—';$('gps').textContent=s.connected?(s.gps||'대기'):'미확인';$('climate').textContent=s.connected&&s.climateTarget!=null?s.climateTarget+' °C':'미확인';$('sharing').textContent=s.connected&&s.sharing?'연결 준비 완료':'연결 대기';
  $('navStatus').textContent=(s.destination?'목적지: '+s.destination+' · ':'')+(s.message||'')+' · '+(s.mapProvider||'')+' · '+(s.parked?'P단 정차':'주행 / 상태 확인 중');$('healthStatus').textContent=s.health?'차량 '+s.health.vehicle+' · GPS '+s.health.gps:'';$('progress').max=s.total||1;$('progress').value=s.progress||0;
  const p=s.position;if(s.connected&&s.gpsReady&&p){carMarker=marker(carMarker,p,'#006ce0');if(!centered&&map){map.setView([p[1],p[0]],15);centered=true}}else if(carMarker){map.removeLayer(carMarker);carMarker=null}
  const links=JSON.stringify(s.links||{});if(links!==oldLinks){clearLinks();oldLinks=links;for(const role of ['owner','family'])if(s.links?.[role]){const url=new URL(s.links[role]);if(!['http:','https:'].includes(url.protocol))continue;$(role+'Link').href=url.href;$(role+'Qr').src='/qr/'+role+'.png?v='+Date.now()}setCameraViewing(false);if(s.links?.owner)loadCameraSettings(false)}
}catch(e){state={};renderExternal({});$('mode').textContent='서버 응답 중단';$('message').textContent='시연 서버 연결을 확인하세요';for(const id of ['gps','sharing','climate','speed'])$(id).textContent='미확인';clearLinks();setCameraViewing(false)}controls()}
$('cameraUrl').oninput=()=>{cameraAddressDirty=true};
function cameraButtons(){for(const id of ['cameraSave','cameraLoad','cameraDisconnect','cameraUrl','cameraUser','cameraPassword'])$(id).disabled=cameraSaving}
function hideCamera(){$('cameraPreview').hidden=true;$('cameraPreview').removeAttribute('src')}
function setCameraViewing(value){cameraGeneration++;cameraViewing=value;hideCamera();$('cameraView').textContent=value?'화면 닫기':'화면 보기'}
async function loadCameraSettings(replaceDraft=true){
  const session=oldLinks;
  try{const r=await post('/api/camera',{action:'camera-status'});if(session!==oldLinks)return;
    const address=(r.url||'').replace(/\/shot\.jpg$/,'');$('cameraAddress').textContent=r.configured?'설정된 카메라 주소: '+address:'설정된 카메라 주소: 없음';
    if(replaceDraft||!cameraAddressDirty){$('cameraUrl').value=address;cameraAddressDirty=false}
    if(!cameraViewing)$('cameraStatus').textContent=r.configured?'주소가 준비됐어요. 화면 보기를 눌러 수신을 확인하세요.':'주소를 입력하고 연결하면 바로 화면을 확인합니다.';
  }catch(e){$('cameraStatus').textContent=e.message}
}
$('cameraLoad').onclick=()=>loadCameraSettings(true);
$('cameraForm').onsubmit=async e=>{e.preventDefault();if(cameraSaving)return;cameraSaving=true;cameraButtons();setCameraViewing(false);$('cameraStatus').textContent='연결 주소를 적용하는 중…';
  try{await post('/api/camera',{action:'camera-configure',enabled:true,url:$('cameraUrl').value.trim(),username:$('cameraUser').value,password:$('cameraPassword').value,consent:true});$('cameraPassword').value='';cameraAddressDirty=false;await loadCameraSettings();setCameraViewing(true);$('cameraStatus').textContent='주소 적용 완료 · 실제 화면 수신 확인 중…';await cameraFrame()}
  catch(e){$('cameraStatus').textContent=e.message}finally{cameraSaving=false;cameraButtons()}
};
$('cameraView').onclick=()=>{setCameraViewing(!cameraViewing);if(cameraViewing)cameraFrame()};
$('cameraDisconnect').onclick=async()=>{if(cameraSaving)return;cameraSaving=true;cameraButtons();setCameraViewing(false);try{await post('/api/camera',{action:'camera-configure',enabled:false});$('cameraAddress').textContent='설정된 카메라 주소: 없음';$('cameraStatus').textContent='연결 해제됨 · 모든 앱에서 영상 수신이 중단됐어요.'}catch(e){$('cameraStatus').textContent=e.message}finally{cameraSaving=false;cameraButtons()}};
async function cameraFrame(){if(!cameraViewing||cameraBusy||document.hidden)return;cameraBusy=true;const generation=cameraGeneration;
  try{const r=await post('/api/camera',{action:'camera-frame'});if(generation===cameraGeneration&&cameraViewing&&!document.hidden){$('cameraPreview').src='data:image/jpeg;base64,'+r.image;$('cameraPreview').hidden=false;$('cameraStatus').textContent='화면 수신 '+new Date(r.capturedAt).toLocaleTimeString()+' · 저장 안 함'}}
  catch(e){if(generation===cameraGeneration){hideCamera();$('cameraStatus').textContent=e.message}}
  finally{cameraBusy=false}
}
document.addEventListener('visibilitychange',()=>{if(document.hidden)setCameraViewing(false)});
refresh();setInterval(refresh,1000);setInterval(cameraFrame,1000);

let externalWorking=false,externalLinksKey='',externalError='';
function renderExternal(s){
 const e=s.externalConnection||{};
 $('externalStatus').textContent=externalError||e.message||'외부 연결 상태를 확인할 수 없어요';
 $('externalAddress').textContent=e.active?e.url:'';
 $('externalStart').disabled=externalWorking||!s.connected||!s.sharing||['starting','ready','stopping'].includes(e.state);
 $('externalStop').disabled=externalWorking||!['starting','ready'].includes(e.state);
 const links=s.externalLinks||{},encoded=JSON.stringify(links);$('externalQrs').hidden=!e.active||(!links.family&&!links.owner);
 if(encoded!==externalLinksKey){externalLinksKey=encoded;
  for(const role of ['Owner','Family']){const link=links[role.toLowerCase()],img=$('external'+role+'Qr'),a=$('external'+role+'Link');
   if(link){a.href=link;img.src='/external-qr/'+role.toLowerCase()+'.png?v='+Date.now()}else{img.removeAttribute('src');a.removeAttribute('href')}
  }
 }
}
async function changeExternal(action){if(externalWorking)return;externalError='';externalWorking=true;renderExternal(state);
 try{await post('/api/tunnel',{action});await refresh()}catch(e){externalError=e.message}
 finally{externalWorking=false;renderExternal(state)}
}
$('externalStart').onclick=()=>changeExternal('start');$('externalStop').onclick=()=>changeExternal('stop');

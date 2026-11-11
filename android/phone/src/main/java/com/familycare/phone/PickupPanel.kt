package com.familycare.phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import com.familycare.ui.*
@Composable fun PickupPanel(m:PhoneModel){
 val context=LocalContext.current;val gps=remember{PhoneLocation(context)};val camera=remember{MapViewportController()}
 var locating by remember{mutableStateOf(false)};var name by remember{mutableStateOf("동승 예정자")};var landmark by remember{mutableStateOf("")};var memo by remember{mutableStateOf("")};var nearby by remember{mutableStateOf(false)}
 DisposableEffect(Unit){onDispose{gps.cancel()}}
 fun locate(){locating=true;gps.once{l,message->locating=false;if(l!=null)m.pickupLocation(l)else m.explain(message)}}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){p->if(p[Manifest.permission.ACCESS_FINE_LOCATION]==true)locate()else m.explain("정확한 위치를 허용해야 도로명과 대기 위치를 확인할 수 있어요")}
 val state=m.pickup;val car=state.optJSONObject("vehicle");val carPoint=car?.optJSONArray("point");val waiting=state.optJSONArray("waiting")?.optJSONObject(0);val point=m.pickupDraft.optJSONArray("point")?:waiting?.optJSONArray("point")
 // The app decides the screen: 내 차 always shows the owner side, Family Care the waiting-passenger side.
 val ownerApp=BuildConfig.PRODUCT=="navigation"
 val availability=when{
  !m.connected->if(ownerApp)"차량에서 ‘소유자’ QR로 연결하면 픽업용 차량 위치 공유를 켤 수 있어요." else "차량 연결이 끊겨 있어요. 새 연결 QR로 다시 연결하면 픽업 버튼을 사용할 수 있어요."
  m.stale->"차량 서버 응답을 기다리는 중이에요. 시연 보조 프로그램을 켜고 연결 상태가 ‘연결됨’으로 바뀌는지 확인해 주세요."
  m.busy->"차량에 다른 요청을 전달하고 있어요. 완료되면 픽업 버튼이 다시 켜집니다."
  ownerApp&&!m.isOwner->"소유자 연결이 아니에요. ‘연결’에서 차량의 소유자 QR로 다시 연결해 주세요."
  ownerApp&&!state.optBoolean("enabled")->"차량 연결됨 · 아래에서 픽업용 차량 위치 공유를 켜면 동승 예정자가 위치를 보낼 수 있어요."
  !state.optBoolean("enabled")->"동승자 버튼 대기 중 · 소유자가 내 차 앱에서 픽업용 차량 위치 공유를 켜야 해요."
  else->"픽업 연결 사용 가능 · 차량 위치 공유가 켜져 있습니다."
 }
 Text("타기 전 픽업",style=MaterialTheme.typography.headlineMedium)
 Text(availability,modifier=Modifier.fillMaxWidth().testTag("pickup-availability"),style=MaterialTheme.typography.bodyMedium)
 if(ownerApp){
  Text("픽업 공유를 켜면 가족 링크로 연결한 동승 예정자가 현재 차량 위치를 볼 수 있어요. 최대 2시간, 이 차량 연결에서만 공유합니다.")
  Button(onClick={m.pickupShare(!state.optBoolean("enabled"))},enabled=m.isOwner&&m.canRequest,modifier=Modifier.testTag("pickup-share")){Text(if(state.optBoolean("enabled"))"차량 위치 공유 끄기" else "픽업용 차량 위치 공유 켜기")}
  Text("동승 예정자는 WithME 가족 앱 → 홈 → 타기 전 픽업에서 대기 위치를 보냅니다. 내 차 앱에서는 권한을 요청하지 않아요.",style=MaterialTheme.typography.bodySmall)
  val entries=state.optJSONArray("waiting")
  if(entries!=null)(0 until entries.length()).forEach{i->val e=entries.getJSONObject(i);Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(e.optString("name"));Text(e.optString("address"));Text(e.optString("message"));Text("위치 받은 지 ${e.optInt("ageSeconds")}초 · 오차 약 ${e.optInt("accuracy")}m");Button(onClick={m.endPickup(e.optString("deviceId"),true)},enabled=m.isOwner&&m.canRequest){Text("탑승 완료")}}}}
  return
 }
 Text(if(state.optBoolean("enabled"))"차량 위치 공유 중 · 좌석 권한을 받기 전에도 대기 위치를 보낼 수 있어요" else if(!m.connected||m.stale||m.busy)"연결 상태가 확인된 뒤 차량 위치 공유 상태를 확인할 수 있어요" else "먼저 소유자가 내 차 앱 → 가족 → 타기 전 픽업에서 차량 위치 공유를 켜 주세요")
 if(carPoint!=null||point!=null){
  ProvideMapGateway(m.mapGateway()) {PlaceMap(latitude=point?.optDouble(1),longitude=point?.optDouble(0),modifier=Modifier.fillMaxWidth().height(320.dp),position=carPoint?.let{MapPosition(it.optDouble(1),it.optDouble(0),null,true)},controller=camera,onPick=null,interactive=false)}
  Text("파란 위치 표시: 차량 · 선택 핀: 내 대기 위치",style=MaterialTheme.typography.bodySmall)
  if(carPoint!=null&&point!=null)TextButton(onClick={camera.overview(listOf(MapRoutePoint(carPoint.getDouble(1),carPoint.getDouble(0)),MapRoutePoint(point.getDouble(1),point.getDouble(0))))}){Text("차량과 대기 위치 함께 보기")}
 }
 if(carPoint==null)Text("차량 GPS 갱신 대기 · 오래된 위치는 표시하지 않아요")
 waiting?.let{e->
  Text("전달한 위치: "+e.optString("address"));Text("GPS 오차 약 ${e.optInt("accuracy")}m · 받은 지 ${e.optInt("ageSeconds")}초")
  if(e.has("straightMeters"))Text("차량과 직선거리 약 ${e.optDouble("straightMeters").toInt()}m")
  Button(onClick=m::pickupArrival,enabled=m.canRequest&&carPoint!=null){Text("도로 기준 도착 예상 갱신")}
  if(m.pickupEta.has("seconds"))Text("조회 시점 예상 ${(m.pickupEta.optDouble("seconds")/60).toInt()}분 · ${"%.1f".format(m.pickupEta.optDouble("meters")/1000)}km")
  TextButton(onClick={m.endPickup()},enabled=m.canRequest){Text("대기 취소")}
 }
 Text("현재 위치 확인을 누르면 휴대폰 GPS 좌표를 TMAP에 보내 근처 도로명 주소를 찾습니다. 가게·상가 이름과 메모는 차량 연결에만 전달합니다.",style=MaterialTheme.typography.bodySmall)
 Button(onClick={if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},enabled=m.canRequest&&state.optBoolean("enabled")&&!locating,modifier=Modifier.testTag("pickup-current-location")){Text(if(locating)"GPS 확인 중…" else "내 현재 위치 확인")}
 if(m.pickupDraft.optString("draft").isNotBlank()){
  Text("GPS 근처 주소: "+m.pickupDraft.optString("address"));Text("오차 약 ${m.pickupDraft.optInt("accuracy")}m · 도로 건너편과 건물 입구를 확인하세요")
  OutlinedTextField(name,{name=it.take(30)},label={Text("내 이름")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(landmark,{landmark=it.take(80)},label={Text("가게·상가와 출입구 (필수)")},placeholder={Text("예: GS25 양재점 정문 앞")},modifier=Modifier.fillMaxWidth().testTag("pickup-landmark"))
  OutlinedTextField(memo,{memo=it.take(2000)},label={Text("추가 설명 · 최대 2000자")},minLines=3,modifier=Modifier.fillMaxWidth())
  Text("차에서는 도로명과 가게·출입구를 짧게 읽고, 긴 메모는 정차 후 확인합니다.")
  Button(onClick={m.sendPickup(name,landmark,memo)},enabled=m.canRequest&&landmark.trim().length>=2&&name.trim().length>=2,modifier=Modifier.testTag("send-pickup")){Text("이 위치에서 기다린다고 보내기")}
 }
 TextButton(onClick={nearby=!nearby},enabled=point!=null){Text("잠깐 만날 주차장·승하차 시설 찾기")}
 if(nearby&&point!=null){
  Text("주차장 등 시설 후보입니다. 길가의 정차 가능 여부·입구·요금·운영 시간은 확인이 필요해요. 교차로나 횡단보도를 승차 지점으로 추천하지 않습니다.")
  PlaceSearch(point.getDouble(1),point.getDouble(0),gateway=m.mapGateway(),initialFacility="주차장"){p->landmark=(p.name+" 입구").take(80);m.invalidatePickupDraft();nearby=false;m.explain("${p.name}을 대기 장소 이름에 넣었어요. 실제로 이동한 뒤 GPS를 다시 받아 보내 주세요.")}
 }
}

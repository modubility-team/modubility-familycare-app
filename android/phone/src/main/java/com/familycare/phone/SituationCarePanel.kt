package com.familycare.phone
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import com.familycare.ui.RestStopText
@Composable fun SituationCarePanel(m:PhoneModel){
 var mode by remember{mutableStateOf("sleep")};var stop by remember{mutableStateOf<com.familycare.ui.FoundPlace?>(null)}
 var caregiver by remember{mutableStateOf("")};var adjust by remember{mutableStateOf(false)};var temperature by remember{mutableStateOf(23f)}
 LaunchedEffect(m.grant.optString("deviceId")){caregiver=m.grant.optString("deviceId")}
 val bounds=m.climateBounds(m.preferredClimateArea);val c=m.situation
 Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("상황별 케어",style=MaterialTheme.typography.titleLarge)
  if(c.optString("mode").isNotBlank()){
   Text(c.optString("label"));Text("담당: "+c.optString("caregiverName")+if(c.optBoolean("caregiverOnline"))" · 연결됨" else " · 연결 끊김, 운전자 알림으로 전환")
   Text(RestStopText.phone(c),modifier=Modifier.testTag("situation-rest-stop"))
   Row{TextButton(onClick={m.careCommand("care","휴식 완료",JSONObject().put("action","rest"))},enabled=m.canRequest&&m.hasScope("care")){Text("휴식 완료")};TextButton(onClick={m.careCommand("care","상황별 케어 종료",JSONObject().put("action","stop"))},enabled=m.canRequest&&m.hasScope("care")){Text("케어 종료")}}
  }
  listOf("sleep" to "아이가 잠들었어요","comfort" to "편안하게 이동하고 싶어요","pet" to "반려동물과 쉬어 가요").forEach{(id,label)->FilterChip(selected=mode==id,onClick={mode=id},label={Text(label)})}
  Text("일반 가족 요청을 받을 보호자")
  m.caregivers.forEach{g->FilterChip(selected=caregiver==g.optString("deviceId"),onClick={caregiver=g.optString("deviceId")},label={Text(g.optString("name"))})}
  if(m.caregivers.isEmpty())Text("먼저 권한 탭에서 ‘상황별 케어’를 승인받으세요")
  // The rest stop is the family's choice; it reaches the car's navigation as a waypoint.
  Text("쉬어 갈 휴게소 지정")
  // Only rest stops on the car's remaining route ahead, found over the whole route (nearest first).
  val remaining=m.state.optJSONObject("routeSummary")?.optJSONArray("remainingRoute")?.takeIf{it.length()>1};val start=remaining?.optJSONArray(0)
  if(remaining!=null&&start!=null){
   Text("남은 경로에 있는 휴게소를 가까운 순서로 보여 줘요. 이름을 입력하면 그 휴게소를 찾아요.",style=MaterialTheme.typography.bodySmall)
   com.familycare.ui.PlaceSearch(start.optDouble(1),start.optDouble(0),gateway=m.mapGateway(),route=remaining,initialFacility="휴게소",
    showFacilities=false,alongRoute=true,autoSearch=true){p->stop=p}
  }else Text("차량에서 목적지 안내를 시작하면 남은 경로에 있는 휴게소를 찾을 수 있어요.",modifier=Modifier.testTag("rest-stop-needs-route"))
  Text(stop?.let{"지정 휴게소: ${it.name}"}?:"휴게소를 고르지 않으면 휴게소 없이 케어만 시작해요",modifier=Modifier.testTag("chosen-rest-stop"))
  Text("지정한 휴게소는 운전자에게 경유지 요청으로 가요. 운전자가 승인하면 최종 목적지는 그대로 두고 경유지로 추가돼요. ‘목적지·경유지 변경’ 권한이 있으면 P단 정차 중 바로 반영되고, 주행 중에는 운전자가 음성으로 승인해요.",style=MaterialTheme.typography.bodySmall)
  if(m.hasScope("climate")){
   Row{Checkbox(checked=adjust,onCheckedChange={adjust=it;temperature=temperature.coerceIn(bounds.first.toFloat(),bounds.second.toFloat())});Text("내 좌석 온도도 함께 설정")}
   if(adjust){Text("${temperature}°C · 승인 범위 안에서만 설정");if(bounds.second>bounds.first)Slider(value=temperature.coerceIn(bounds.first.toFloat(),bounds.second.toFloat()),onValueChange={temperature=(kotlin.math.round(it*2)/2).coerceIn(bounds.first.toFloat(),bounds.second.toFloat())},valueRange=bounds.first.toFloat()..bounds.second.toFloat())}
  }
  Text("화면 밖에서도 받으려면 연결 → 알림·보호자 설정 → 이 휴대폰에서 백그라운드 알림 켜기를 사용하세요.",style=MaterialTheme.typography.bodySmall)
  Text("선택한 상황을 직접 적용합니다. 일반 요청은 보호자에게 먼저 전달하고, 30초 미확인·연결 끊김이면 운전자에게 알립니다. 길안내·안전 알림은 계속 유지됩니다.",style=MaterialTheme.typography.bodySmall)
  Button(onClick={val o=JSONObject().put("action","start").put("mode",mode).put("caregiver",caregiver);stop?.let{o.put("stop",JSONObject().put("name",it.name.take(80)).put("latitude",it.latitude).put("longitude",it.longitude))};if(adjust)o.put("temperature",temperature.toDouble());m.careCommand("care","상황별 케어 시작",o)},enabled=m.canRequest&&m.hasScope("care")&&caregiver.isNotBlank(),modifier=Modifier.testTag("start-situation-care")){Text("이 설정으로 케어 시작")}
 }}
}
@Composable fun CareInboxPanel(m:PhoneModel){
 m.careInbox.forEach{r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("보호자에게 도착한 가족 요청");Text(r.optString("title"));Button(onClick={m.acknowledgeCare(r.optString("id"))},enabled=m.canRequest){Text("내가 확인했어요")}}}}
 if(m.situation.optBoolean("restDue"))Text(RestStopText.phone(m.situation))
}

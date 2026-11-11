package com.familycare.phone
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable fun FamilyRouteNeedsPanel(m:PhoneModel){
 var needs by remember{mutableStateOf(emptySet<String>())}
 LaunchedEffect(m.grant.optString("deviceId"),m.routeNeeds.toString()){val a=m.routeNeeds.firstOrNull{it.optString("deviceId")==m.grant.optString("deviceId")}?.optJSONArray("needs");needs=if(a==null)emptySet()else (0 until a.length()).map{a.optString(it)}.toSet()}
 Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){
  Text("우리 가족의 이동 기준",style=MaterialTheme.typography.titleLarge)
  listOf("빠른 도착","방향 전환 적게","화장실","반려견 산책","쉬어 갈 장소").forEach{n->Row{Checkbox(n in needs,{needs=if(it)needs+n else needs-n});Text(n)}}
  Button(onClick={m.setRouteNeeds(needs)},enabled=m.canRequest&&m.grant.optBoolean("active")){Text("차량의 경로 비교에 전달")}
  Text(if(m.hasScope("route"))"전달하면 차량이 이 기준으로 안내 경로를 바로 다시 골라요. 빠른 도착·방향 전환 적게는 주행 중에도 바로 바뀌고, 화장실·반려견 산책·쉬어 갈 장소 경유는 P단 정차 중에 추가돼요(주행 중에는 운전자가 음성으로 추가)."
   else "차량에서 목적지 선택 → 가족 기준으로 경로 비교를 누르세요. 실제 후보를 비교한 뒤 운전자가 경로를 선택합니다. ‘목적지·경유지 변경’ 권한이 있으면 차량 경로에 바로 반영돼요.",style=MaterialTheme.typography.bodySmall)
  OutlinedButton(onClick=m::urgentNote,enabled=m.canRequest){Text("운전자에게 긴급 확인 요청")}
 }}
}

package com.familycare.navigation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.familycare.ui.PlaceSearch
@Composable fun FamilyRouteDialog(care:CareRuntime,onClose:()->Unit){
 val nav=care.navigation
 var stop by remember{mutableStateOf<GeoPoint?>(null)};var stopName by remember{mutableStateOf("")};var showSearch by remember{mutableStateOf(false)}
 val need=care.relay.routeNeeds.flatMap{d->val a=d.optJSONArray("needs");if(a==null)emptyList()else (0 until a.length()).map{a.optString(it)}}.distinct()
 MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF00356B),surface=Color.White,onSurface=Color(0xFF142336)),typography=Typography(bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=19.sp),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=19.sp),bodySmall=androidx.compose.ui.text.TextStyle(fontSize=16.sp),titleMedium=androidx.compose.ui.text.TextStyle(fontSize=23.sp),headlineSmall=androidx.compose.ui.text.TextStyle(fontSize=29.sp),labelLarge=androidx.compose.ui.text.TextStyle(fontSize=19.sp))){
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth(.92f).fillMaxHeight(.91f).testTag("family-route-content"),shape=MaterialTheme.shapes.large){Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("가족 기준 경로 비교",style=MaterialTheme.typography.headlineSmall);Text(nav.comparisonName)
   if(need.isNotEmpty())Text("가족이 원하는 것: "+need.joinToString(" · "))
   Text("검색된 후보의 시간·거리·방향 전환 수를 비교합니다. 같은 길은 하나로 표시합니다.")
   if(nav.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
   if(nav.familyCandidates.size==1&&!nav.loading)Text("조회 조건별 결과가 같은 경로예요. 휴식 장소를 선택하면 경유 전후를 비교할 수 있어요.")
   nav.familyCandidates.forEach{c->
    OutlinedCard(Modifier.fillMaxWidth().testTag("family-route-"+c.optString("id"))){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     val labels=c.getJSONArray("labels");Text((0 until labels.length()).joinToString(" · "){labels.getString(it)},style=MaterialTheme.typography.titleMedium)
     Text("${kotlin.math.ceil(c.getDouble("seconds")/60).toInt()}분 · ${"%.1f".format(c.getDouble("meters")/1000)} km · 방향 전환 ${c.getInt("turns")}회")
     if(c.has("extraSeconds"))Text("가장 빠른 후보 대비 ${"%+.1f".format(c.getDouble("extraSeconds")/60)}분")
     if(c.optString("stopName").isNotBlank())Text("경유: "+c.optString("stopName")+" · 실제 시설·운영 여부 확인 필요")
     Button(onClick={if(care.parked&&nav.chooseFamilyRoute(c.getString("id")))onClose()},enabled=care.parked&&!nav.loading,modifier=Modifier.heightIn(min=60.dp).testTag("choose-family-route")){Text("이 경로 선택")}
    }}
   }
   Text(nav.comparisonMessage,style=MaterialTheme.typography.bodySmall)
   TextButton(onClick={showSearch=!showSearch},enabled=!nav.loading){Text(if(showSearch)"휴식 장소 검색 접기" else "필요한 휴식 장소를 찾아 경유 비교")}
   if(showSearch){
    PlaceSearch(nav.fix?.point?.latitude?:37.5,nav.fix?.point?.longitude?:127.0,gateway=care.relay.mapGateway(),route=nav.searchRoute(),initialFacility=need.firstOrNull{it in listOf("화장실","반려견 산책") }?.let{if(it=="반려견 산책")"반려견 공원" else it}?:"휴게소"){p->stop=GeoPoint(p.latitude,p.longitude);stopName=p.name}
    if(stop!=null){Text("선택: $stopName");Button(onClick={nav.comparisonTarget?.let{nav.compareFamily(nav.comparisonName,it,stop,stopName,need)}},enabled=!nav.loading&&nav.familyCandidates.isNotEmpty()&&care.parked){Text("이 장소를 포함해서 다시 비교")}}
   }
   TextButton(onClick=onClose,modifier=Modifier.heightIn(min=56.dp)){Text("닫기")}
  }}
 }
}

}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.familycare.ui.MapViewportController

@Composable internal fun NavigationMenu(nav:LiveNavigation,camera:MapViewportController,onClose:()->Unit,onSearch:()->Unit,onPick:(NavigationPlace)->Unit){
    var tab by remember{mutableStateOf("경로")}
    var refresh by remember{mutableIntStateOf(0)}
    var option by remember{mutableStateOf(nav.route?.option?:nav.option)}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.9f).widthIn(max=1000.dp).fillMaxHeight(.88f),color=Color(0xFFF7F9FB)){
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("내비게이션",Modifier.weight(1f),fontSize=28.sp,fontWeight=FontWeight.Bold)
                    TextButton(onClick=onClose,modifier=Modifier.heightIn(min=56.dp)){Text("닫기",fontSize=20.sp)}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    listOf("경로","장소","지도·음성").forEach{value->FilterChip(tab==value,{tab=value},label={Text(value,fontSize=20.sp)},modifier=Modifier.heightIn(min=56.dp).testTag("navigation-menu-$value"))}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    when(tab){
                        "경로"->{
                            nav.route?.let{route->
                                Text(route.destination,fontSize=26.sp,fontWeight=FontWeight.Bold)
                                Text("${guidanceDistance(nav.remainingMeters?:0.0)} · 약 ${kotlin.math.ceil((nav.remainingSeconds?:0.0)/60).toInt()}분",fontSize=23.sp)
                                Text("${route.option.label} · 승용차 통행료 ${route.tollFare?.let{"%,d원".format(it)}?:"정보 없음"}",fontSize=20.sp)
                                if(route.provider=="TMAP")Text("교통 조회 ${java.time.Instant.ofEpochMilli(route.generatedAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))} · 감면 전 예상 요금",fontSize=16.sp)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                    Button(onClick={camera.overview(route.points.map{com.familycare.ui.MapRoutePoint(it.latitude,it.longitude)});onClose()}){Text("경로 전체")}
                                    Button(onClick={nav.recalculate();onClose()},enabled=!nav.loading&&nav.locationUsable,modifier=Modifier.testTag("route-refresh")){Text("현재 위치에서 재탐색")}
                                    OutlinedButton(onClick={if(nav.active)nav.pause()else nav.start();onClose()},enabled=!nav.loading){Text(if(nav.active)"안내 일시정지" else "안내 재개")}
                                    OutlinedButton(onClick={nav.cancel();onClose()}){Text("안내 종료")}
                                    if(nav.hasWaypoint)OutlinedButton(onClick={nav.recalculate(removeWaypoint=true);onClose()},enabled=!nav.loading){Text("경유지 삭제")}
                                }
                            }?:Text("목적지를 선택하면 경로를 확인할 수 있어요",fontSize=22.sp)
                            Button(onClick={onClose();onSearch()},modifier=Modifier.heightIn(min=56.dp)){Text(if(nav.route!=null)"목적지 변경 · 경유지 추가" else "목적지 검색")}
                            if(nav.useTmap){
                                Text("경로 탐색 옵션",fontSize=22.sp,fontWeight=FontWeight.Bold)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    RouteOption.entries.forEach{item->FilterChip(option==item,{option=item},label={Text(item.label)},modifier=Modifier.heightIn(min=52.dp))}
                                }
                                Text("한 가지 옵션을 선택해 탐색합니다. 무료 우선에도 유료 도로가 포함될 수 있습니다.",fontSize=16.sp)
                                Button(onClick={if(nav.route!=null)nav.recalculate(option)else nav.setOption(option);onClose()},enabled=!nav.loading,modifier=Modifier.testTag("route-option-apply")){Text(if(nav.route!=null)"선택한 옵션으로 재탐색" else "옵션 저장")}
                            }
                            nav.route?.let{route->
                                HorizontalDivider();Text("남은 경로 상세",fontSize=22.sp,fontWeight=FontWeight.Bold)
                                route.turns.filter{it.along>=nav.progress-7}.take(120).forEach{turn->
                                    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                                        TurnArrow(turn.symbol,Modifier.size(32.dp),Color(0xFF007FA3))
                                        Column(Modifier.weight(1f)){Text("${guidanceDistance((turn.along-nav.progress).coerceAtLeast(0.0))} · ${maneuverLabel(turn)}",fontSize=21.sp,fontWeight=FontWeight.Medium);Text(turn.instruction,fontSize=17.sp)}
                                    };HorizontalDivider()
                                }
                            }
                        }
                        "장소"->{
                            @Suppress("UNUSED_VARIABLE") val revision=refresh
                            Text("저장 장소",fontSize=23.sp,fontWeight=FontWeight.Bold)
                            Text("지도에서 장소를 선택한 다음 집·회사·즐겨찾기에 등록할 수 있어요.",fontSize=17.sp)
                            nav.places.saved().forEach{place->Row(verticalAlignment=Alignment.CenterVertically){
                                Button(onClick={onPick(place);onClose()},modifier=Modifier.weight(1f).heightIn(min=58.dp)){Text(place.name,fontSize=20.sp)}
                                TextButton(onClick={nav.places.remove(place.name);refresh++}){Text("삭제")}
                            }}
                            HorizontalDivider();Text("최근 목적지",fontSize=23.sp,fontWeight=FontWeight.Bold)
                            Text("현재 실행 중에 선택한 목적지만 표시하며 하루 이내 자동으로 지워집니다.",fontSize=16.sp)
                            nav.places.recents().forEach{place->OutlinedButton(onClick={onPick(place);onClose()},modifier=Modifier.fillMaxWidth().heightIn(min=58.dp).testTag("navigation-recent")){Text(place.name,fontSize=20.sp)}}
                            TextButton(onClick={nav.places.clearHistory();refresh++}){Text("최근 목적지 지우기")}
                        }
                        else->{
                            Text("지도 보기",fontSize=23.sp,fontWeight=FontWeight.Bold)
                            Button(onClick={camera.toggleOrientation()},modifier=Modifier.heightIn(min=56.dp)){Text(if(camera.headingUp)"진행 방향 ↑ · 눌러 북쪽 고정" else "북쪽 고정 N · 눌러 진행 방향")}
                            Button(onClick={camera.toggleAutoZoom()},modifier=Modifier.heightIn(min=56.dp)){Text(if(camera.autoZoom)"자동 확대 켜짐" else "자동 확대 꺼짐")}
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                OutlinedButton(onClick=camera::zoomOut){Text("축소 −")};OutlinedButton(onClick=camera::zoomIn){Text("확대 +")}
                                OutlinedButton(onClick={camera.follow();onClose()}){Text("현재 위치 추적")}
                            }
                            HorizontalDivider();Text("길안내 음성",fontSize=23.sp,fontWeight=FontWeight.Bold)
                            Button(onClick=nav::toggleVoice,modifier=Modifier.heightIn(min=56.dp)){Text(if(nav.voiceEnabled)"길안내 음성 켜짐" else "길안내 음성 꺼짐")}
                            Text("WithME 알림 소리는 차량 설정에서 별도로 조절합니다.",fontSize=17.sp)
                            OutlinedButton(onClick=nav::repeatInstruction,enabled=nav.guiding&&nav.voiceEnabled){Text("이번 안내 다시 듣기")}
                            Text("차로 추천·단속카메라·제한속도·신호등 시간·3D 교차로는 현재 연결한 데이터에서 제공하지 않습니다.",fontSize=16.sp)
                        }
                    }
                }
            }
        }
    }
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.phone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.round

@Composable fun VehicleControlPanel(m:PhoneModel,selectedGroup:String?=null,onGroup:((String)->Unit)?=null){
    val raw=m.state.optJSONArray("controls")
    val controls=if(raw==null)emptyList()else(0 until raw.length()).map{raw.getJSONObject(it)}
    var localGroup by remember{mutableStateOf("공조")}
    val group=selectedGroup?:localGroup
    var confirmation by remember{mutableStateOf<Pair<JSONObject,Double>?>(null)}
    Card(Modifier.fillMaxWidth().testTag("vehicle-controls"),colors=CardDefaults.cardColors(containerColor=Color.White)){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(if(m.isOwner)"차량 조절" else "차량 조절",style=MaterialTheme.typography.titleLarge)
            Text(if(m.stale)"차량 연결을 확인하세요" else if(!m.state.optBoolean("parked")){if(m.isOwner)"주행 중에는 조절할 수 없어요 · P단 정차 후 사용하세요" else "승인받은 공조·좌석 기능은 바로 조절할 수 있어요"}else if(m.isOwner)"차량이 응답한 실제 상태를 표시합니다" else "승인된 권한은 ${m.grantLifetime} 유지돼요. 잠긴 기능은 권한 탭에서 요청하세요")
            val groups=listOf("공조","문과 창문","시트와 열선")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){groups.forEach{g->FilterChip(selected=group==g,onClick={localGroup=g;onGroup?.invoke(g)},label={Text(if(g=="문과 창문")"문·창문" else g)},modifier=Modifier.testTag("control-group-$g"),colors=FilterChipDefaults.filterChipColors(containerColor=Color.White,selectedContainerColor=Color(0xFF00305E),selectedLabelColor=Color.White))}}
            if(controls.isNotEmpty()&&controls.none{it.optString("group")==group})Text("이 차량에서 지원하는 $group 조절 항목이 없습니다.")
            if(controls.isEmpty())Text("이 차량의 제어 가능 항목을 확인 중이에요. 지원하지 않는 기능은 표시되지 않습니다.")
            controls.filter{it.optString("group")==group}.sortedBy{c->
                val id=c.optString("id");when{ id.startsWith("door.lock:ROW_")->0;id.startsWith("window.position:")->1;id.startsWith("window.lock:")->2;id.contains("HOOD")->4;else->3 }
            }.forEach{c->
                key(c.optString("id")){
                    val current=c.optDouble("value",Double.NaN)
                    var chosen by remember{mutableStateOf(current.takeIf{it.isFinite()}?:c.optDouble("min"))}
                    var editing by remember{mutableStateOf(false)}
                    LaunchedEffect(current){if(!editing&&current.isFinite())chosen=current}
                    val available=c.optBoolean("available")&&!m.stale
                    val enabled=available&&m.canControl(c)
                    val isLock=c.optString("id").contains("lock")
                    HorizontalDivider()
                    Text(c.optString("label"),style=MaterialTheme.typography.titleMedium)
                    if(c.optString("id").startsWith("hvac.fan:")&&m.state.opt("climatePower")==false)Text("공조 전원을 먼저 켜 주세요. 전원 권한이 없으면 소유자에게 요청하세요.")
                    Text("현재 상태 · "+if(!available||!current.isFinite())"확인 중" else if(c.optString("id").startsWith("window.position:")){if(current==c.optDouble("min"))"닫힘" else "%.0f%% 열림".format(100*(current-c.optDouble("min"))/(c.optDouble("max")-c.optDouble("min")))}else if(c.optBoolean("boolean")){if(isLock){if(current==1.0)"잠김" else "해제됨"}else if(current==1.0)"켜짐" else "꺼짐"}else current.toInt().toString())
                    if(c.optBoolean("boolean")){
                        Button(onClick={val value=if(current==1.0)0.0 else 1.0;if(!m.isOwner&&c.optString("group")!="문과 창문")m.vehicleControl(c,value)else confirmation=c to value},enabled=enabled,modifier=Modifier.testTag("control-"+c.optString("id"))){Text(if(isLock){if(current==1.0)"잠금 해제" else "잠그기"}else if(current==1.0)"끄기" else "켜기")}
                    }else{
                        val allowed=if(m.isOwner)null else m.grant.optJSONObject("limits")?.optJSONObject("control:"+c.optString("id"))
                        val low=(allowed?.optDouble("min")?:c.optDouble("min")).toFloat();val high=(allowed?.optDouble("max")?:c.optDouble("max")).toFloat()
                        if(c.optString("id").startsWith("window.position:"))FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            listOf("닫기" to c.optDouble("min"),"절반 열기" to round((c.optDouble("min")+c.optDouble("max"))/2),"열기" to c.optDouble("max")).forEach{(label,value)->
                                OutlinedButton(onClick={confirmation=c to value;editing=false},enabled=enabled&&value in low.toDouble()..high.toDouble(),modifier=Modifier.testTag("window-$label-"+c.optString("id"))){Text(label)}
                            }
                        }
                        if(high>low)Slider(value=chosen.toFloat().coerceIn(low,high),onValueChange={editing=true;chosen=round(it.toDouble())},valueRange=low..high,enabled=enabled,steps=if(high-low<=20)(high-low).toInt().minus(1).coerceAtLeast(0)else 0)
                        LaunchedEffect(low,high){chosen=chosen.coerceIn(low.toDouble(),high.toDouble())}
                        Text("선택 ${chosen.toInt()} · 허용 ${low.toInt()}–${high.toInt()}")
                        Button(onClick={if(!m.isOwner&&c.optString("group")!="문과 창문")m.vehicleControl(c,chosen)else confirmation=c to chosen;editing=false},enabled=enabled,modifier=Modifier.testTag("control-"+c.optString("id"))){Text("적용")}
                    }
                }
            }
            m.managementRequests.filter{it.optString("kind") in listOf("climate","control")}.takeLast(3).reversed().forEach{request->
                Text(request.optString("title")+" · "+when(request.optString("status")){"pending"->"차량 전달 대기";"executing"->"차량 응답 확인 중";else->request.optString("message")},modifier=Modifier.testTag("control-request-result"))
            }
        }
    }
    confirmation?.let{(c,value)->AlertDialog(onDismissRequest={confirmation=null},title={Text(c.optString("label"))},text={Text(if(c.optString("group")=="문과 창문")"주변 사람과 물건을 확인한 후 실행하세요. 변경 결과는 차량 응답으로 확인합니다." else if(m.isOwner)"선택한 설정을 차량에 적용합니다." else "승인된 권한으로 차량 설정을 적용합니다.")},confirmButton={TextButton(onClick={confirmation=null;m.vehicleControl(c,value)}){Text("실행")}},dismissButton={TextButton(onClick={confirmation=null}){Text("취소")}})}
}


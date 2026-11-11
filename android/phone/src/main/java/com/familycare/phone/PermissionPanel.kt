@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.familycare.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familycare.ui.FamilyGlyph
import com.familycare.ui.SeatAccess
import com.familycare.ui.PermissionRange
import org.json.JSONArray
import org.json.JSONObject

private data class PermissionCategory(val id:String,val title:String,val hint:String,val glyph:String)
private val permissionCategories=listOf(
    PermissionCategory("climate","온도·바람","설정 온도, 공조 전원, 에어컨, 풍량","cool"),
    PermissionCategory("seat","시트·열선","내 좌석과 열선 조절","warm"),
    PermissionCategory("doors","문·창문","잠금과 창문 열림 정도","lock"),
    PermissionCategory("route","길안내","목적지와 경유지 변경","route"),
    PermissionCategory("care","가족·반려동물 케어","상황별 케어, 확인 타이머, 가족 설정","people"),
    PermissionCategory("other","기타 차량 기능","차량에서 제공하는 추가 기능","car")
)
private fun categoryOf(id:String)=when {
    id=="climate"||id=="climatePower"||id.startsWith("control:hvac.")->"climate"
    id.startsWith("control:seat.")||id=="control:wheel.heat"->"seat"
    id.startsWith("control:door.")||id.startsWith("control:window.")->"doors"
    id=="route"->"route"
    id in setOf("care","timer","profile","hold")->"care"
    else->"other"
}
private fun boundsText(c:JSONObject,limits:JSONObject):String {
    val b=limits.optJSONObject(c.optString("id"))
    fun number(v:Double)=if(v==v.toInt().toDouble())v.toInt().toString()else "%.1f".format(v)
    val unit=if(c.optString("id")=="climate")"°C" else ""
    return "${number(b?.optDouble("min")?:c.optDouble("min"))} ~ ${number(b?.optDouble("max")?:c.optDouble("max"))}$unit"
}

@Composable fun PermissionPanel(m:PhoneModel){
    val device=m.grant.optString("deviceId")
    var name by rememberSaveable{mutableStateOf("동승자")}
    var seat by rememberSaveable(device){mutableStateOf(m.grant.optString("seat").takeIf{it in SeatAccess.seats}?:"ROW_1_RIGHT")}
    var selectionJson by rememberSaveable(device){mutableStateOf("[]")}
    var limitsJson by rememberSaveable(device){mutableStateOf("{}")}
    var step by rememberSaveable(device){mutableStateOf(0)}
    var expandedGroup by rememberSaveable(device){mutableStateOf("")}
    var expandedRange by rememberSaveable(device){mutableStateOf("")}
    var loadedGrant by rememberSaveable(device){mutableStateOf("")}
    val heading=remember{BringIntoViewRequester()}
    val selected=JSONArray(selectionJson).let{a->(0 until a.length()).map{a.getString(it)}.toSet()}
    val limits=JSONObject(limitsJson)
    fun select(ids:Set<String>){selectionJson=JSONArray(ids.sorted()).toString()}
    val grantSignature=listOf(device,m.grant.optString("epoch"),m.grant.optString("seat"),m.grant.optJSONArray("scopes"),m.grant.optJSONObject("limits")).joinToString("|")
    LaunchedEffect(grantSignature){
        if(loadedGrant!=grantSignature){
            selectionJson=(m.grant.optJSONArray("scopes")?:JSONArray()).toString()
            limitsJson=(m.grant.optJSONObject("limits")?:JSONObject()).toString()
            m.grant.optString("seat").takeIf{it in SeatAccess.seats}?.let{seat=it}
            loadedGrant=grantSignature
        }
    }
    LaunchedEffect(step){withFrameNanos{};heading.bringIntoView()}
    Text("내 좌석 조절 권한",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.bringIntoViewRequester(heading))
    if(m.role!="family"){Text("가족 연결 링크로 연결해 주세요.");return}
    Text(if(m.grant.optBoolean("active"))"승인 완료 · ${SeatAccess.seats[m.grant.optString("seat")]}" else m.grant.optString("reason"),modifier=Modifier.testTag("grant-status"),style=MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("좌석","기능","확인").forEachIndexed{i,label->
            Surface(modifier=Modifier.weight(1f),shape=RoundedCornerShape(6.dp),color=if(step==i)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant){
                Text("${i+1} $label",Modifier.padding(vertical=10.dp,horizontal=8.dp),style=MaterialTheme.typography.labelLarge)
            }
        }
    }
    val items=m.permissionCatalog.filter{c->val seats=c.optJSONArray("seats");seats!=null&&(0 until seats.length()).any{seats.optString(it)==seat}}.map{original->
        JSONObject(original.toString()).apply{
            if(optString("id")=="climate")SeatAccess.objects(m.state.optJSONArray("climateAreas")).firstOrNull{seat in SeatAccess.members(it.optString("id"))}?.let{
                put("min",it.optDouble("min"));put("max",it.optDouble("max"));put("shared",SeatAccess.members(it.optString("id")).size>1)
            }
        }
    }
    val chosen=items.filter{it.optString("id") in selected}
    val groups=permissionCategories.map{it to items.filter{c->categoryOf(c.optString("id"))==it.id}}.filter{it.second.isNotEmpty()}
    when(step){
        0->{
            Text("어느 좌석에 타시나요?",style=MaterialTheme.typography.titleLarge)
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                SeatAccess.seats.entries.toList().chunked(2).forEach{row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        row.forEach{(id,label)->
                            OutlinedButton(onClick={if(seat!=id){seat=id;select(emptySet());limitsJson="{}";expandedGroup="";expandedRange=""}},modifier=Modifier.weight(1f).heightIn(min=52.dp).testTag("seat-$id"),shape=RoundedCornerShape(8.dp),
                                colors=ButtonDefaults.outlinedButtonColors(containerColor=if(seat==id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)){
                                if(seat==id){FamilyGlyph("check",MaterialTheme.colorScheme.primary,Modifier.size(18.dp));Spacer(Modifier.width(5.dp))};Text(label)
                            }
                        }
                        if(row.size==1)Spacer(Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(name,{name=it.take(30)},label={Text("운전자에게 표시할 이름")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("permission-name"))
            Text("선택한 좌석에서 사용할 수 있는 기능만 다음 화면에 보여요.",style=MaterialTheme.typography.bodyMedium)
            Button(onClick={step=1},enabled=name.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("permission-next")){Text("다음 · 기능 선택")}
        }
        1->{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("필요한 종류만 열어보세요",style=MaterialTheme.typography.titleLarge);Text("${SeatAccess.seats[seat]} · ${chosen.size}개 선택",modifier=Modifier.testTag("permission-selection-count"),style=MaterialTheme.typography.bodyMedium)}
                TextButton(onClick={step=0},modifier=Modifier.testTag("permission-edit-seat")){Text("좌석 변경")}
            }
            OutlinedButton(onClick={select(items.map{it.optString("id")}.toSet())},enabled=items.isNotEmpty()&&chosen.size<items.size,modifier=Modifier.fillMaxWidth().testTag("permission-add-all")){Text("모두 추가 · 이 좌석의 ${items.size}개")}
            Text("모두 추가해도 운전자 승인 전에는 권한이 생기지 않아요.",style=MaterialTheme.typography.bodySmall)
            if(items.isEmpty())Text(if(m.stale)"차량 연결을 확인하면 선택 가능한 기능이 표시돼요." else "이 좌석에서 요청할 수 있는 기능이 아직 없어요.")
            groups.forEach{(category,controls)->
                val open=expandedGroup==category.id
                val count=controls.count{it.optString("id") in selected}
                Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                    Column{
                        Surface(onClick={expandedGroup=if(open)"" else category.id;expandedRange=""},modifier=Modifier.fillMaxWidth().testTag("permission-category-${category.id}"),color=MaterialTheme.colorScheme.surface){
                            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                FamilyGlyph(category.glyph,MaterialTheme.colorScheme.primary,Modifier.size(25.dp))
                                Column(Modifier.weight(1f)){
                                    Text(category.title,style=MaterialTheme.typography.titleMedium)
                                    Text(if(count>0)"${count}개 선택 · 전체 ${controls.size}개" else category.hint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if(open)"접기" else "선택",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                            }
                        }
                        if(open)Column(Modifier.padding(start=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            TextButton(onClick={select(selected+controls.map{it.optString("id")})},enabled=count<controls.size,modifier=Modifier.testTag("permission-add-category-${category.id}")){Text("이 분류 모두 추가")}
                            controls.forEach{c->
                                val id=c.optString("id");val checked=id in selected
                                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth().toggleable(value=checked,role=Role.Checkbox,onValueChange={select(if(it)selected+id else selected-id);if(!it&&expandedRange==id)expandedRange=""}).testTag("grant-scope-$id").padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                                    Checkbox(checked=checked,onCheckedChange=null)
                                    Column(Modifier.weight(1f)){
                                        Text(c.optString("label"),style=MaterialTheme.typography.bodyLarge)
                                        val tags=listOfNotNull(if(c.optBoolean("shared"))"차량 공용" else null,if(c.optBoolean("parked"))"P단 정차 중 사용" else null)
                                        if(tags.isNotEmpty())Text(tags.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if(checked&&!c.optBoolean("boolean")){
                                    TextButton(onClick={expandedRange=if(expandedRange==id)"" else id},modifier=Modifier.testTag("permission-range-$id")){Text("허용 범위 ${boundsText(c,limits)} · "+if(expandedRange==id)"접기" else "변경")}
                                    if(expandedRange==id){
                                        val bounds=limits.optJSONObject(id)
                                        PermissionRange(bounds?.optDouble("min")?:c.optDouble("min"),bounds?.optDouble("max")?:c.optDouble("max"),c.optDouble("min"),c.optDouble("max"),c.optDouble("step",1.0)){lo,hi->
                                            limitsJson=JSONObject(limitsJson).put(id,JSONObject().put("min",lo).put("max",hi)).toString()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if(chosen.isNotEmpty())TextButton(onClick={select(emptySet());limitsJson="{}";expandedRange=""},modifier=Modifier.testTag("permission-clear")){Text("선택 모두 해제")}
            Button(onClick={step=2;expandedGroup="";expandedRange=""},enabled=chosen.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("permission-review")){Text("다음 · 선택한 ${chosen.size}개 확인")}
        }
        else->{
            Text("이 권한만 요청할게요",style=MaterialTheme.typography.titleLarge)
            Text("$name · ${SeatAccess.seats[seat]} · ${chosen.size}개",modifier=Modifier.testTag("permission-review-summary"),fontWeight=FontWeight.Bold)
            groups.forEach{(category,controls)->
                val included=controls.filter{it.optString("id") in selected}
                if(included.isNotEmpty())Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surface){
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(category.title,style=MaterialTheme.typography.titleMedium)
                        included.forEach{c->
                            Text(c.optString("label"))
                            if(!c.optBoolean("boolean"))Text("허용 범위 ${boundsText(c,limits)}",style=MaterialTheme.typography.bodySmall)
                            if(c.optBoolean("shared"))Text("차량 공용 · 다른 좌석에도 영향",style=MaterialTheme.typography.bodySmall)
                            if(c.optBoolean("parked"))Text("P단 정차 중 사용",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("운전자가 승인한 뒤 ${m.grantLifetime} 사용할 수 있어요. 다시 승인받으면 이전 권한을 이 목록으로 바꿔요.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick={step=1},modifier=Modifier.fillMaxWidth().testTag("permission-edit-selection")){Text("선택 수정")}
            Button(onClick={
                val ranges=JSONObject()
                chosen.filterNot{it.optBoolean("boolean")}.forEach{c->val id=c.optString("id");ranges.put(id,limits.optJSONObject(id)?:JSONObject().put("min",c.optDouble("min")).put("max",c.optDouble("max")))}
                m.requestPermissions(name,chosen.map{it.optString("id")}.toSet(),seat,ranges)
            },enabled=m.canRequest&&m.state.optBoolean("authorityActive")&&chosen.isNotEmpty()&&name.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("request-permissions")){Text("운전자에게 ${chosen.size}개 권한 요청")}
        }
    }
    m.requests.filter{it.optString("kind")=="permission"&&it.optString("deviceId")==device}.takeLast(1).forEach{
        Text("최근 요청 · "+it.optString("message"),style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun GrantManagement(m:PhoneModel){
    m.delegations.filter{it.optBoolean("active")}.forEach{g->
        Text(g.optString("name")+" · "+SeatAccess.seats[g.optString("seat")]+" · 승인 "+(g.optJSONArray("scopes")?.length()?:0)+"개")
        OutlinedButton(onClick={m.revokeGrant(g.optString("deviceId"))},enabled=m.isOwner&&!m.stale&&!m.busy){Text("조절 권한 회수")}
    }
}

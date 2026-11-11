@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONArray

@Composable fun ClimateZones(zones:JSONArray?,selected:String,onSelect:(String)->Unit){
    if(zones==null||zones.length()==0){Text("차량 공조 영역 확인 중");return}
    val ids=(0 until zones.length()).map{zones.getJSONObject(it).optString("id")}
    LaunchedEffect(ids,selected){if(selected !in ids)ids.firstOrNull()?.let(onSelect)}
    FlowRow{(0 until zones.length()).forEach{i->val z=zones.getJSONObject(i);FilterChip(selected==z.optString("id"),{onSelect(z.optString("id"))},enabled=!z.isNull("target"),label={Text(z.optString("label"))})}}
    Text("차량이 지원하는 영역만 표시합니다. 조수석·뒷좌석 항목이 없으면 독립 조절을 사용할 수 없어요.",style=MaterialTheme.typography.bodySmall)
}

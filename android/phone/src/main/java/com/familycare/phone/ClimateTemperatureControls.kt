package com.familycare.phone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun ClimateTemperatureControls(m:PhoneModel,area:String,onArea:(String)->Unit){
    com.familycare.ui.ClimateZones(m.visibleClimateAreas(),area,onArea)
    val current=m.climateTarget(area)
    val bounds=m.climateBounds(area)
    var draft by rememberSaveable(area){mutableStateOf<Double?>(null)}
    // Polls update the actual value, but never overwrite an unsent selection.
    LaunchedEffect(current){if(draft==current)draft=null}
    LaunchedEffect(bounds){draft=draft?.coerceIn(bounds.first,bounds.second)}
    val selected=draft?:current?.coerceIn(bounds.first,bounds.second)?:23.0.coerceIn(bounds.first,bounds.second)
    Text(m.climateLabel(area)+" · 차량 설정 "+(current?.let{"%.1f°C".format(it)}?:"확인 중"),fontWeight=FontWeight.Bold,modifier=Modifier.testTag("climate-actual"))
    Text("바꿀 온도")
    TemperatureSelector(selected,bounds.first,bounds.second){draft=it}
    Button(onClick={m.climate(selected,area)},enabled=m.canClimate,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag(if(BuildConfig.PRODUCT=="navigation")"owner-set-climate" else "request-climate")){
        Text(if(BuildConfig.PRODUCT=="navigation")"설정 온도 적용" else "온도 적용")
    }
    val latest=m.requests.lastOrNull{it.optString("kind")=="climate"&&it.optJSONObject("options")?.optString("area")==area}
    if(latest!=null)Text(when(latest.optString("status")){
        "pending"->"차량에 전달 대기 중"
        "executing"->"설정 온도를 차량에서 확인 중"
        "awaiting_agreement"->"함께 사용하는 공조 설정에 대한 동의 대기 중"
        else->latest.optString("message")
    },modifier=Modifier.testTag("climate-apply-result"))
    ClimatePowerControls(m,area)
    val ac=com.familycare.ui.SeatAccess.objects(m.state.optJSONArray("controls")).firstOrNull{it.optString("id").startsWith("hvac.ac:")}
    ac?.let{c->
        val known=c.optBoolean("available")&&!c.isNull("value")&&!m.stale
        val on=c.optDouble("value")==1.0
        Text("에어컨 A/C · "+if(!known)"확인 중" else if(on)"켜짐" else "꺼짐")
        OutlinedButton(onClick={m.vehicleControl(c,if(on)0.0 else 1.0)},enabled=known&&m.canControl(c),modifier=Modifier.fillMaxWidth().testTag("climate-ac-toggle")){Text(if(on)"에어컨 A/C 끄기" else "에어컨 A/C 켜기")}
    }
    Text("설정 온도는 공조 목표값이며, 실내 측정 온도와 다릅니다. 냉방은 공조 전원과 에어컨 A/C를 함께 켜 주세요.",style=MaterialTheme.typography.bodySmall)
}

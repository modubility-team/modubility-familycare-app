package com.familycare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun journeyTime(value:Long)=if(value<=0)"미확인" else SimpleDateFormat("MM-dd HH:mm",Locale.KOREA).format(Date(value))
@Composable fun JourneyPanel(journey:JSONObject,route:JSONObject?=null){
    var details by remember(journey.optString("id")){mutableStateOf(false)}
    val events=journey.optJSONArray("events")
    val raw=journey.optJSONArray("points")
    val points=if(raw==null)emptyList()else (0 until raw.length()).map{raw.getJSONObject(it)}
    Text(journeyTime(journey.optLong("startedAt"))+" · "+journey.optString("mode"),style=MaterialTheme.typography.titleMedium)
    val end=journey.optLong("endedAt").takeIf{it>0}?:System.currentTimeMillis()
    Text("%.1f km · %d분 · %d점".format(journey.optDouble("meters",0.0)/1000,((end-journey.optLong("startedAt"))/60000).coerceAtLeast(0),journey.optInt("score",100)))
    Text("점수 = 100 − 기록한 급조작 1회당 3점. 의학적 멀미·안전성 평가가 아닙니다.",style=MaterialTheme.typography.bodySmall)
    Text("급가속 ${journey.optInt("accel")} · 급감속 ${journey.optInt("brake")} · 급조향 ${journey.optInt("steer")}")
    if(route!=null){Text("목적지 · "+route.optString("destination"));Text(route.optString("change"));route.optJSONObject("waypoint")?.let{Text("경유지 · "+if(it.optBoolean("visited"))"방문 확인" else "방문 예정")}}
    TextButton(onClick={details=!details}){Text(if(details)"상세 접기" else "경로·정차·이벤트 보기")}
    if(details){
        if(points.isNotEmpty())PlaceMap(latitude=points.first().optDouble("lat"),longitude=points.first().optDouble("lon"),modifier=Modifier.fillMaxWidth().height(250.dp),route=points.map{MapRoutePoint(it.getDouble("lat"),it.getDouble("lon"))},showSelectionMarker=false)
        else Text("수신한 GPS 기록이 없어요. 정확한 위치 권한과 GPS 상태를 확인하세요.")
        if(events!=null)(0 until events.length()).reversed().take(100).forEach{index->val e=events.getJSONObject(index)
            Text(journeyTime(e.optLong("at"))+" · "+e.optString("text"))
            if(!e.isNull("lat"))Text("%.5f, %.5f".format(e.optDouble("lat"),e.optDouble("lon")),style=MaterialTheme.typography.bodySmall)
        }
    }
}

package com.familycare.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OemInk=Color(0xFF1F2D3B)
private val OemAccent=Color(0xFF39C3ED)
private val OemMuted=Color(0xFFBFCBD6)

/** Original layout informed by ccNC's right-hand maneuver stack and lower route bar. */
@Composable internal fun NavigationOemGuidance(nav:LiveNavigation,compact:Boolean,modifier:Modifier=Modifier){
    val turn=nav.nextTurn
    val status=when{!nav.locationUsable->nav.locationStatus;nav.loading||nav.outside->nav.message;else->null}
    Column(modifier.background(OemInk).padding(if(compact)18.dp else 24.dp).testTag("guidance-panel"),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("경로 안내",color=OemMuted,fontSize=18.sp)
            Text(if(nav.voiceEnabled)"음성 ON" else "음성 OFF",color=OemMuted,fontSize=16.sp)
        }
        if(status==null&&turn!=null){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)){
                TurnArrow(turn.symbol,Modifier.size(if(compact)64.dp else 84.dp),OemAccent)
                Column(Modifier.weight(1f)){
                    Text(guidanceDistance((turn.along-nav.progress).coerceAtLeast(0.0)),color=Color.White,fontSize=if(compact)42.sp else 54.sp,fontWeight=FontWeight.Bold,maxLines=1,modifier=Modifier.testTag("turn-distance"))
                    Text(maneuverLabel(turn),color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Medium,modifier=Modifier.testTag("turn-action"))
                }
            }
            Text(maneuverRoad(turn),color=Color.White,fontSize=23.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("turn-road"))
            nav.followingTurn?.let{next->
                HorizontalDivider(color=Color(0xFF435362))
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)){
                    TurnArrow(next.symbol,Modifier.size(34.dp),OemMuted)
                    Column{Text("이어서 ${guidanceDistance(next.along-turn.along)}",color=Color.White,fontSize=23.sp,modifier=Modifier.testTag("following-turn"));Text(maneuverLabel(next),color=OemMuted,fontSize=18.sp)}
                }
            }
            nav.route?.restStops?.firstOrNull{it.along>nav.progress}?.let{stop->
                HorizontalDivider(color=Color(0xFF435362))
                Text("휴게 시설 · ${guidanceDistance(stop.along-nav.progress)}",color=OemAccent,fontSize=22.sp)
                Text(stop.road.ifBlank{stop.instruction},color=Color.White,fontSize=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }else{
            Text(status?:"경로 확인 중",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("gps-status"))
            if(nav.loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=OemAccent)
        }
    }
}

@Composable internal fun NavigationOemFooter(nav:LiveNavigation,compact:Boolean,modifier:Modifier=Modifier){
    val seconds=nav.remainingSeconds?:0.0
    val eta=java.time.Instant.ofEpochMilli(System.currentTimeMillis()+(seconds*1000).toLong()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    Column(modifier.background(OemInk).testTag("navigation-arrival")){
        LinearProgressIndicator(progress={(nav.progress/(nav.route?.meters?:1.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f,1f)},color=OemAccent,trackColor=Color(0xFF425563),modifier=Modifier.fillMaxWidth().height(4.dp))
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal=if(compact)16.dp else 28.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(22.dp)){
            Column(Modifier.weight(1f)){
                Text(nav.route?.destination.orEmpty(),color=Color.White,fontSize=if(compact)19.sp else 24.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(nav.currentRoad.ifBlank{if(nav.locationUsable)"현재 위치" else "GPS 수신 대기"},color=OemMuted,fontSize=if(compact)16.sp else 20.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("current-road"))
            }
            Metric("도착",if(nav.locationUsable)eta else "--:--","arrival-time",compact)
            Metric("남은 시간","${kotlin.math.ceil(seconds/60).toInt()}분","remaining-time",compact)
            if(!compact)Metric("남은 거리",guidanceDistance(nav.remainingMeters?:0.0),"route-remaining",compact)
        }
    }
}
@Composable private fun Metric(label:String,value:String,tag:String,compact:Boolean){
    Column(horizontalAlignment=Alignment.End){Text(label,color=OemMuted,fontSize=16.sp);Text(value,color=Color.White,fontSize=if(compact)27.sp else 36.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag(tag))}
}

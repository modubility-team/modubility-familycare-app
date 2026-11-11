package com.familycare.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycare.ui.FamilyGlyph

private val NaviBlue=Color(0xFF0068DC)
private val NaviDeepBlue=Color(0xFF003E88)
private val NaviInk=Color(0xFF122239)
private val NaviGrey=Color(0xFF566476)

/** Fixed navigation chrome leaves a separate native map viewport between header and footer. */
@Composable internal fun NavigationDrivingHeader(turn:RouteTurn?,following:RouteTurn?,distance:Double,
    status:String?,compact:Boolean,audio:Boolean,readOnly:Boolean,onAudio:()->Unit,modifier:Modifier=Modifier){
    Column(modifier.background(NaviBlue).testTag("guidance-panel")){
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal=if(compact)18.dp else 32.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(compact)18.dp else 30.dp)){
            TurnArrow(if(status==null)turn?.symbol?:"↑" else "↑",Modifier.size(if(compact)68.dp else 104.dp)
                .semantics{contentDescription=if(status==null&&turn!=null)maneuverLabel(turn)else "경로 안내"})
            Column(Modifier.weight(1f)){
                if(status==null&&turn!=null){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)){
                        Text(guidanceDistance(distance),fontSize=if(compact)46.sp else 76.sp,lineHeight=if(compact)50.sp else 80.sp,
                            fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,modifier=Modifier.testTag("turn-distance"))
                        if(!compact)Text(maneuverLabel(turn),fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.testTag("turn-action"))
                    }
                    Text(if(compact)"${maneuverLabel(turn)} · ${maneuverRoad(turn)}" else maneuverRoad(turn),
                        fontSize=if(compact)22.sp else 30.sp,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("turn-road"))
                }else Text(status?:"경로 안내 준비",fontSize=if(compact)24.sp else 34.sp,fontWeight=FontWeight.Bold,color=Color.White,
                    modifier=Modifier.testTag("gps-status"),maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(!compact)Column(horizontalAlignment=Alignment.CenterHorizontally){
                IconButton(onClick=onAudio,enabled=!readOnly,modifier=Modifier.size(64.dp).testTag("navigation-sound")){
                    FamilyGlyph("volume",Color.White,Modifier.size(38.dp))
                }
                Text(if(audio)"음성 안내" else "음성 꺼짐",color=Color.White,fontSize=16.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(if(compact)44.dp else 50.dp).background(NaviDeepBlue).padding(horizontal=if(compact)20.dp else 36.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
            if(following!=null&&turn!=null&&status==null){
                Text("이어서",color=Color(0xFFC3DBFA),fontSize=if(compact)17.sp else 21.sp)
                TurnArrow(following.symbol,Modifier.size(if(compact)26.dp else 32.dp))
                Text("${guidanceDistance(following.along-turn.along)}  ${maneuverLabel(following)}",color=Color.White,
                    fontSize=if(compact)20.sp else 25.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f).testTag("following-turn"),maxLines=1)
            }else Text(if(status==null)"목적지까지 경로를 따라 이동하세요" else "위치와 경로를 확인하고 있어요",color=Color.White,fontSize=if(compact)17.sp else 22.sp,modifier=Modifier.weight(1f))
            if(!compact)Text("TMAP 길안내",color=Color(0xFFC3DBFA),fontSize=17.sp)
        }
    }
}

@Composable internal fun NavigationDrivingFooter(destination:String,arrival:String,minutes:Int,meters:Double,
    progress:Float,traffic:String,compact:Boolean,modifier:Modifier=Modifier){
    Column(modifier.background(Color.White).testTag("navigation-arrival")){
        LinearProgressIndicator(progress={progress.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp),
            color=NaviBlue,trackColor=Color(0xFFDAE5F2))
        Row(Modifier.fillMaxWidth().padding(horizontal=if(compact)18.dp else 32.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)){
            FamilyGlyph("pin",NaviBlue,Modifier.size(23.dp))
            Text(destination,color=NaviInk,fontSize=if(compact)18.sp else 25.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!compact)Text(traffic,color=NaviGrey,fontSize=15.sp)
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal=if(compact)8.dp else 22.dp),verticalAlignment=Alignment.CenterVertically){
            NavigationMetric("도착 예정",arrival,"arrival-time",compact,Modifier.weight(1f))
            VerticalDivider(Modifier.height(48.dp),color=Color(0xFFDCE2EA))
            NavigationMetric("남은 시간","${minutes}분","remaining-time",compact,Modifier.weight(1f))
            VerticalDivider(Modifier.height(48.dp),color=Color(0xFFDCE2EA))
            NavigationMetric("남은 거리",if(meters<10)"도착 지점" else guidanceDistance(meters),"route-remaining",compact,Modifier.weight(1f))
        }
    }
}

@Composable private fun NavigationMetric(label:String,value:String,tag:String,compact:Boolean,modifier:Modifier){
    Column(modifier.padding(bottom=12.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Text(label,color=NaviGrey,fontSize=if(compact)15.sp else 19.sp)
        Text(value,color=NaviInk,fontSize=if(compact)28.sp else 45.sp,fontWeight=FontWeight.Bold,maxLines=1,modifier=Modifier.testTag(tag))
    }
}

@Composable internal fun NavigationSpeed(speed:Double?,fresh:Boolean,compact:Boolean,modifier:Modifier=Modifier){
    Surface(color=Color.White,contentColor=NaviInk,shape=RoundedCornerShape(12.dp),shadowElevation=5.dp,
        border=BorderStroke(1.dp,Color(0xFFE0E6ED)),modifier=modifier.width(if(compact)106.dp else 152.dp).testTag("navigation-speed")){
        Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text(speed?.let{(kotlin.math.abs(it)*3.6).toInt().toString()}?:"—",fontSize=if(compact)58.sp else 88.sp,
                lineHeight=if(compact)62.sp else 92.sp,fontWeight=FontWeight.Bold)
            Text("km/h",fontSize=if(compact)17.sp else 23.sp,color=NaviGrey)
            HorizontalDivider(Modifier.padding(top=10.dp,bottom=8.dp),color=Color(0xFFE5EAF0))
            Text(if(fresh)"GPS 정상" else "GPS 대기",fontSize=if(compact)14.sp else 18.sp,color=if(fresh)Color(0xFF087F61)else Color(0xFF9E4A10))
        }
    }
}

/** Maneuver sequence, not a lane diagram: never invent lane counts or signal information. */
@Composable internal fun NavigationApproach(turn:RouteTurn,distance:Double,following:RouteTurn?,modifier:Modifier=Modifier){
    Surface(color=Color.White,contentColor=NaviInk,shape=RoundedCornerShape(14.dp),shadowElevation=5.dp,modifier=modifier.testTag("approach-strip")){
        Row(Modifier.padding(horizontal=22.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)){
            TurnArrow(turn.symbol,Modifier.size(48.dp),NaviBlue)
            Column{Text("이번 회전",fontSize=15.sp,color=NaviGrey);Text(guidanceDistance(distance),fontSize=31.sp,fontWeight=FontWeight.Bold)}
            if(following!=null){
                VerticalDivider(Modifier.height(44.dp),color=Color(0xFFDCE2EA))
                TurnArrow(following.symbol,Modifier.size(34.dp),NaviGrey)
                Column{Text("다음 회전",fontSize=15.sp,color=NaviGrey);Text(guidanceDistance(following.along-turn.along),fontSize=25.sp,fontWeight=FontWeight.Medium)}
            }
        }
    }
}

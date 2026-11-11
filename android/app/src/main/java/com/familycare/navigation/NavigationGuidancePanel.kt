package com.familycare.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val GuidanceBlue=Color(0xFF006CE5)
private val GuidanceInk=Color(0xFF101C2D)
internal fun guidanceDistance(meters:Double)=when{
    meters<10->"잠시 후"
    meters<1000->"${(meters/10).toInt()*10} m"
    else->"%.1f km".format(meters/1000)
}
internal fun maneuverLabel(turn:RouteTurn)=when(turn.symbol){
    "←"->if(turn.instruction.contains("좌측"))"왼쪽 방향" else "좌회전"
    "→"->if(turn.instruction.contains("우측"))"오른쪽 방향" else "우회전"
    "↶"->"유턴";"↻"->"회전교차로";"↗"->"합류";"●"->"목적지 도착";else->"직진"
}
internal fun maneuverRoad(turn:RouteTurn):String=
    Regex("후\\s+(.+?)(?:을|를)\\s+따라").find(turn.instruction)?.groupValues?.get(1)
        ?:turn.road.ifBlank{turn.instruction}

/** Original, high-contrast glyphs. Only maneuvers returned by the route provider are shown. */
@Composable internal fun TurnArrow(symbol:String,modifier:Modifier=Modifier,color:Color=Color.White){
    Canvas(modifier){
        val w=size.width;val h=size.height
        val stroke=Stroke(width=w*.115f,cap=StrokeCap.Round,join=StrokeJoin.Round)
        fun arrow(vararg xy:Float){
            val p=Path();xy.asList().chunked(2).forEachIndexed{i,v->
                val x=if(symbol=="←")1-v[0] else v[0]
                if(i==0)p.moveTo(x*w,v[1]*h)else p.lineTo(x*w,v[1]*h)
            };drawPath(p,color,style=stroke)
        }
        when(symbol){
            "→","←"->{arrow(.25f,.88f,.25f,.28f,.84f,.28f);arrow(.65f,.08f,.86f,.28f,.65f,.48f)}
            "↶"->{val p=Path().apply{moveTo(.78f*w,.88f*h);lineTo(.78f*w,.32f*h);cubicTo(.78f*w,.02f*h,.25f*w,.02f*h,.25f*w,.32f*h);lineTo(.25f*w,.66f*h)};drawPath(p,color,style=stroke);arrow(.08f,.50f,.25f,.69f,.42f,.50f)}
            "↻"->{drawArc(color,45f,290f,false,Offset(.18f*w,.17f*h),androidx.compose.ui.geometry.Size(.64f*w,.64f*h),style=stroke);arrow(.66f,.11f,.86f,.30f,.61f,.35f)}
            "●"->{drawCircle(color,w*.25f,Offset(w*.5f,h*.35f));arrow(.5f,.57f,.5f,.9f)}
            "↗"->{arrow(.25f,.88f,.25f,.6f,.8f,.18f);arrow(.54f,.18f,.8f,.18f,.8f,.44f)}
            else->{arrow(.5f,.88f,.5f,.15f);arrow(.25f,.4f,.5f,.15f,.75f,.4f)}
        }
    }
}

@Composable internal fun NavigationGuidancePanel(nav:LiveNavigation,readOnly:Boolean,compact:Boolean,
    modifier:Modifier=Modifier,overview:()->Unit){
    val turn=nav.nextTurn
    Surface(color=GuidanceInk,contentColor=Color.White,shape=RoundedCornerShape(14.dp),shadowElevation=5.dp,
        modifier=modifier.testTag("guidance-panel")){
        Column{
            if(nav.guiding&&turn!=null&&!nav.outside&&!nav.loading){
                Surface(color=GuidanceBlue,contentColor=Color.White){
                    Row(Modifier.fillMaxWidth().padding(if(compact)16.dp else 22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(22.dp)){
                        TurnArrow(turn.symbol,Modifier.size(if(compact)58.dp else 76.dp).semantics{contentDescription=maneuverLabel(turn)})
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(guidanceDistance((turn.along-nav.progress).coerceAtLeast(0.0)),fontSize=if(compact)38.sp else 46.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("turn-distance"))
                            Text(maneuverLabel(turn),fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("turn-action"))
                            Text(maneuverRoad(turn),fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                nav.followingTurn?.let{next->
                    Row(Modifier.padding(horizontal=22.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Text("이어서",color=Color(0xFFB5C7DF),fontSize=15.sp)
                        TurnArrow(next.symbol,Modifier.size(27.dp))
                        Text("${guidanceDistance(next.along-turn.along)}  ${maneuverLabel(next)}",fontSize=19.sp,fontWeight=FontWeight.Medium,modifier=Modifier.testTag("following-turn"))
                    }
                }
            }else Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(when{nav.active&&!nav.locationUsable->nav.locationStatus;nav.loading||nav.active&&nav.outside->nav.message;nav.arrived->"목적지에 도착했어요";else->nav.route?.destination.orEmpty()},fontSize=23.sp,fontWeight=FontWeight.Bold,
                    modifier=if(nav.active&&!nav.locationUsable)Modifier.testTag("gps-status")else Modifier)
                if(nav.loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Color.White)
                if(!nav.active&&nav.route!=null){
                    Text("${guidanceDistance(nav.remainingMeters?:0.0)} · 약 ${ceil((nav.remainingSeconds?:0.0)/60).toInt()}분",fontSize=22.sp)
                    Text("${nav.route!!.option.label} · 통행료 ${nav.route!!.tollFare?.let{"%,d원".format(it)}?:"정보 없음"}",fontSize=18.sp)
                    if(nav.routeChange.isNotBlank())Text(nav.routeChange,fontSize=15.sp)
                }
                if(!nav.active&&!nav.loading&&!nav.arrived&&!readOnly)Button(onClick={nav.start()},enabled=nav.locationUsable,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("route-start")){Text("안내 시작 / 재개",fontSize=20.sp)}
                if(!nav.guiding&&!nav.loading&&!nav.arrived)Text(nav.message,fontSize=14.sp)
            }
            // Keep the moving view focused on guidance; ending guidance is available when parked.
            if(!readOnly)Row(Modifier.padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                TextButton(onClick=overview,modifier=Modifier.heightIn(min=48.dp)){com.familycare.ui.FamilyGlyph("expand",Color.White,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("경로 전체",color=Color.White)}
                TextButton(onClick={nav.cancel()},modifier=Modifier.heightIn(min=48.dp).testTag("route-stop")){com.familycare.ui.FamilyGlyph("stop",Color.White,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("안내 종료",color=Color.White)}
            }
        }
    }
}

@Composable internal fun NavigationArrivalPanel(nav:LiveNavigation,modifier:Modifier=Modifier){
    val seconds=(nav.remainingSeconds?:0.0).coerceAtLeast(0.0)
    val eta=Instant.ofEpochMilli(System.currentTimeMillis()+(seconds*1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    Surface(color=GuidanceInk,contentColor=Color.White,shape=RoundedCornerShape(14.dp),shadowElevation=4.dp,modifier=modifier.testTag("navigation-arrival")){
        Column(Modifier.padding(horizontal=22.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text(if(nav.locationUsable)eta else "--:--",fontSize=34.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("arrival-time"))
                Text("도착 예정",color=Color(0xFFCAD7E8),fontSize=15.sp,modifier=Modifier.padding(bottom=5.dp))
            }
            Text("약 ${ceil(seconds/60).toInt()}분  ·  ${guidanceDistance(nav.remainingMeters?:0.0)}",fontSize=23.sp,fontWeight=FontWeight.Medium,modifier=Modifier.testTag("route-remaining"))
            Text(nav.route?.destination.orEmpty(),fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(if(!nav.locationUsable)"GPS 수신 후 도착 시간 갱신" else if(nav.route?.provider=="TMAP")"검색 시점 교통 반영" else "교통 정보 미반영",fontSize=12.sp,color=Color(0xFFB5C7DF))
        }
    }
}

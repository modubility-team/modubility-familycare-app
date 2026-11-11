@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycare.ui.FamilyGlyph
import kotlin.math.abs

@Composable internal fun CareNavigation(selected:String,choose:(String)->Unit,wide:Boolean){
    val items=listOf("이동","동승자","차량","요청","기록")
    val icons=listOf("route","people","car","chat","clock")
    val button:@Composable (Int,Modifier)->Unit={i,modifier->
        val active=items[i]==selected
        Surface(onClick={choose(items[i])},color=if(active)CareAccent else Color.Transparent,shape=RoundedCornerShape(8.dp),
            modifier=modifier.heightIn(min=72.dp).testTag("tab-"+items[i])){
            Column(Modifier.padding(horizontal=4.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)){
                FamilyGlyph(icons[i],if(active)Color.White else CareMuted,Modifier.size(if(wide)28.dp else 23.dp))
                Text(if(items[i]=="이동")"내비" else if(items[i]=="동승자")"가족" else if(items[i]=="기록")"추억" else items[i],fontSize=if(wide)17.sp else 14.sp,fontWeight=FontWeight.Bold,color=if(active)Color.White else CareMuted)
            }
        }
    }
    if(wide)Column(Modifier.width(112.dp).fillMaxHeight().background(Color.White).padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Text("WithME",color=CareAccent,fontSize=17.sp,fontWeight=FontWeight.Black,maxLines=1,modifier=Modifier.padding(vertical=18.dp))
        items.indices.forEach{button(it,Modifier.fillMaxWidth())}
    }else Row(Modifier.fillMaxWidth().background(Color.White).padding(6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
        items.indices.forEach{button(it,Modifier.weight(1f))}
    }
}

@Composable internal fun NavigationOverview(care:CareRuntime,wide:Boolean,start:()->Unit,openMap:()->Unit,requests:()->Unit,join:()->Unit){
    // Runtime keeps one identity; observe updates here so Compose cannot skip this child.
    @Suppress("UNUSED_VARIABLE") val revision=care.revision
    val e=care.engine
    val pending=care.relay.requests.count{it.status=="pending"}
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
        VehicleMapScreen(care,wide,{},openMap,false)
        Row(Modifier.fillMaxWidth().testTag("home-actions"),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            DriveTile(if(e.running)"이동 종료" else "이동 시작","휴식 · 가족 주행 기록",if(e.running)"stop" else "play",Modifier.weight(1f),false,"home-trip"){if(e.running)care.endTrip()else start()}
            DriveTile("WithME 가족",if(pending==0)"동승자 요청" else "${pending}건 승인 대기","chat",Modifier.weight(1f),true,"home-requests",requests)
            DriveTile("휴대폰 연결","소유자 · 동승자 QR","qr",Modifier.weight(1f),false,"home-connect",join)
        }
        Text(e.profile.mode+" · "+e.profile.members.joinToString(" · "){it.label},color=CareMuted)
        if(e.running)OutlinedButton(onClick=care::takeBreak,modifier=Modifier.heightIn(min=64.dp)){Text("안전한 곳에서 휴식했어요",color=CareInk)}
    }
}
@Composable private fun DriveTile(title:String,subtitle:String,icon:String,modifier:Modifier,primary:Boolean,tag:String,click:()->Unit){
    Surface(onClick=click,color=if(primary)CareAccent else CarePanel,shape=RoundedCornerShape(10.dp),border=if(primary)null else BorderStroke(1.dp,Color(0xFF373A43)),modifier=modifier.testTag(tag)){
        Column(Modifier.fillMaxWidth().heightIn(min=142.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            FamilyGlyph(icon,Color.White,Modifier.size(30.dp))
            Text(title,color=CareInk,fontSize=22.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=if(primary)Color.White else CareMuted,fontSize=15.sp)
        }
    }
}
@Composable private fun JourneyPanel(care:CareRuntime,modifier:Modifier){
    Surface(color=CarePanel,shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,Color(0xFF373A43)),modifier=modifier){
        Column(Modifier.fillMaxWidth().heightIn(min=296.dp).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            Text(if(care.arrived)"목적지 도착" else "이동 현황",fontSize=21.sp,color=CareInk,fontWeight=FontWeight.Bold)
            val seconds=care.remainingSeconds
            Row(verticalAlignment=Alignment.Bottom){
                Text(seconds?.let{((it+59)/60).toString()}?:"—",fontSize=58.sp,fontWeight=FontWeight.Bold,color=CareInk)
                Text("분",fontSize=22.sp,color=CareMuted,modifier=Modifier.padding(bottom=10.dp,start=8.dp))
            }
            Text(care.remainingMeters?.let{"남은 거리 "+"%.1f".format(it/1000.0)+" km"}?:"확인된 경로가 없어요",color=CareMuted,fontSize=19.sp)
            HorizontalDivider(color=Color(0xFF373A43))
            Text("상세 지도는 차량 내비게이션에서 확인하세요.",fontSize=16.sp,color=CareMuted)
        }
    }
}
@Composable private fun MetricTile(label:String,value:String,unit:String,modifier:Modifier){
    Column(modifier.heightIn(min=132.dp).background(CarePanel,RoundedCornerShape(10.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Text(label,fontSize=15.sp,color=CareMuted)
        Row(verticalAlignment=Alignment.Bottom){Text(value,fontSize=30.sp,color=CareInk,fontWeight=FontWeight.Bold);Text(" $unit",fontSize=15.sp,color=CareMuted,modifier=Modifier.padding(bottom=4.dp))}
        if(value=="—")Text("미확인",fontSize=13.sp,color=CareMuted)
    }
}

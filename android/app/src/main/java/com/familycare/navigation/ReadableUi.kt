@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycare.ui.FamilyGlyph
import org.json.JSONObject

/*
 * WithME readability pass: one idea per line, an icon in front of each group,
 * colour-coded status chips, and handled requests that the driver can clear.
 */

private val CardLine=Color(0xFFD8DDE3)
private val SoftLine=Color(0xFFE3E7EC)
internal val SoftFill=Color(0xFFF3F5F8)

@Composable internal fun StatusChip(status:String){
    val (bg,fg)=when(status){
        "pending"->Color(0xFFFFF0D2) to Color(0xFF7A4B00)
        "executing"->Color(0xFFE3EEFF) to Color(0xFF1F4FCC)
        "confirmed"->Color(0xFFE2F5EC) to Color(0xFF0B6B48)
        "rejected"->Color(0xFFECEEF1) to Color(0xFF4A5563)
        else->Color(0xFFFFF0D2) to Color(0xFF7A4B00)
    }
    Surface(color=bg,shape=RoundedCornerShape(50)){
        Text(statusLabel(status),color=fg,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=1,modifier=Modifier.padding(horizontal=12.dp,vertical=5.dp))
    }
}

@Composable internal fun GlyphBadge(icon:String,tint:Color=CareAccent,size:Dp=48.dp){
    Box(Modifier.size(size).background(tint.copy(alpha=.10f),CircleShape),contentAlignment=Alignment.Center){
        FamilyGlyph(icon,tint,Modifier.size(size*0.5f))
    }
}

@Composable internal fun InfoLine(icon:String,text:String,color:Color=CareMuted,size:TextUnit=15.sp){
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        FamilyGlyph(icon,color,Modifier.size(18.dp))
        Text(text,color=color,fontSize=size,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

/** Icon + short label button, at least 60dp tall for in-car touch. */
@Composable internal fun IconAction(label:String,icon:String,onClick:()->Unit,modifier:Modifier=Modifier,primary:Boolean=false,enabled:Boolean=true){
    if(primary)Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=60.dp),shape=RoundedCornerShape(10.dp),
        colors=ButtonDefaults.buttonColors(containerColor=CareAccent,contentColor=Color.White)){
        FamilyGlyph(icon,Color.White,Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text(label,fontSize=18.sp,maxLines=1)
    }
    else OutlinedButton(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=60.dp),shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,Color(0xFFB9C0C9))){
        FamilyGlyph(icon,if(enabled)CareInk else CareMuted,Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text(label,color=if(enabled)CareInk else CareMuted,fontSize=18.sp,maxLines=1)
    }
}

/** Square tool button: icon over a one-word label. Used for secondary actions in a grid. */
@Composable internal fun ToolTile(label:String,icon:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit){
    Surface(onClick=onClick,enabled=enabled,color=SoftFill,contentColor=CareInk,shape=RoundedCornerShape(10.dp),
        modifier=modifier.heightIn(min=72.dp).semantics{contentDescription=label}){
        Column(Modifier.padding(vertical=10.dp,horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(6.dp,Alignment.CenterVertically)){
            FamilyGlyph(icon,if(enabled)CareInk else CareMuted.copy(alpha=.5f),Modifier.size(24.dp))
            Text(label,fontSize=14.sp,color=if(enabled)CareInk else CareMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun ReadableCard(title:String,icon:String,content:@Composable ColumnScope.()->Unit){
    Surface(color=CarePanel,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,CardLine),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                FamilyGlyph(icon,CareAccent,Modifier.size(26.dp))
                Text(title,color=CareInk,fontSize=22.sp,fontWeight=FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable private fun SectionHeader(title:String,count:Int,trailing:@Composable RowScope.()->Unit={}){
    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,color=CareInk,fontSize=20.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Surface(color=CareAccent.copy(alpha=.10f),shape=RoundedCornerShape(50)){
            Text("$count",color=CareAccent,fontSize=15.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=10.dp,vertical=2.dp))
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable private fun RequestHeader(r:PassengerRequest,active:Boolean){
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        GlyphBadge(requestIcon(r),if(active)CareAccent else CareMuted,if(active)52.dp else 40.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(r.title,color=CareInk,fontSize=if(active)21.sp else 18.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Medium,
                maxLines=if(active)2 else 1,overflow=TextOverflow.Ellipsis)
            Text(if(active||r.message.isBlank())requestKindLabel(r) else r.message,color=CareMuted,fontSize=if(active)15.sp else 14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        StatusChip(r.status)
    }
}

/** Requests tab: waiting items as full cards, handled items as one-line rows the driver can clear. */
@Composable internal fun RequestsTab(care:CareRuntime,openRest:()->Unit){
    val list=care.relay.visibleRequests.reversed()
    val waiting=list.filterNot{handledRequest(it.status)}
    val handled=list.filter{handledRequest(it.status)}
    Text("받은 요청",fontSize=28.sp,fontWeight=FontWeight.Bold,color=CareInk)
    if(list.isEmpty())Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        GlyphBadge("chat",CareMuted,48.dp)
        Text("아직 도착한 요청이 없어요.",fontSize=19.sp,color=CareMuted)
    }
    if(waiting.isNotEmpty()){
        SectionHeader("확인 대기",waiting.size)
        waiting.forEach{PendingRequestCard(care,it)}
    }
    if(handled.isNotEmpty()){
        SectionHeader("처리됨",handled.size){
            if(handled.any{care.relay.canDismiss(it)})TextButton(onClick=care.relay::dismissHandled,modifier=Modifier.heightIn(min=56.dp).testTag("dismiss-handled")){
                FamilyGlyph("trash",CareInk,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("모두 지우기",color=CareInk,fontSize=17.sp)
            }
        }
        handled.forEach{HandledRequestRow(care,it,openRest)}
    }
}

@Composable private fun PendingRequestCard(care:CareRuntime,r:PassengerRequest){
    Surface(color=CarePanel,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,CardLine),modifier=Modifier.fillMaxWidth().testTag("request-"+r.id)){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            RequestHeader(r,true)
            if(r.message.isNotBlank())Text(r.message,color=CareInk,fontSize=17.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
            if(r.kind=="pickup")PickupDetails(care,r)
            if(r.latitude!=null&&r.status=="pending")InfoLine("route",if(r.kind=="pickup")"승인하면 ${care.navigation.providerLabel}로 경유 경로를 계산해요" else "승인하면 ${care.navigation.providerLabel}로 경로를 계산해요")
            if(r.kind=="permission"&&r.status=="pending")SeatPermissionReview(care,r)
            if(r.status=="pending"&&!r.delegated&&r.kind!="permission")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                IconAction(approveLabel(r.kind,care.navigation.target!=null,care.isRestStop(r)),"check",{care.decide(r,true)},Modifier.weight(1f),primary=true)
                IconAction("보류","close",{care.decide(r,false)})
            }
            if(r.status=="executing")InfoLine("refresh","차량에 반영하는 중이에요")
        }
    }
}

@Composable private fun PickupDetails(care:CareRuntime,r:PassengerRequest){
    val o=remember(r.options){runCatching{JSONObject(r.options)}.getOrDefault(JSONObject())}
    Surface(color=SoftFill,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(o.optString("address").isNotBlank())InfoLine("pin",o.optString("address"),CareInk,17.sp)
            if(o.optString("message").isNotBlank())InfoLine("chat",o.optString("message"),CareInk,17.sp)
            InfoLine("search","GPS 오차 약 ${o.optInt("accuracy")}m · 정차 가능 여부를 확인하세요")
        }
    }
    if(r.status!="rejected")IconAction("탑승 완료","people",{care.relay.pickupAction("pickup-boarded",JSONObject().put("deviceId",r.deviceId))})
}

@Composable private fun HandledRequestRow(care:CareRuntime,r:PassengerRequest,openRest:()->Unit){
    Surface(color=CarePanel,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,SoftLine),modifier=Modifier.fillMaxWidth().testTag("request-"+r.id)){
        Column(Modifier.padding(start=16.dp,end=6.dp,top=10.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.weight(1f)){RequestHeader(r,false)}
                if(care.relay.canDismiss(r))IconButton(onClick={care.relay.dismiss(r.id)},
                    modifier=Modifier.size(56.dp).semantics{contentDescription="요청 지우기"}.testTag("dismiss-request-"+r.id)){
                    FamilyGlyph("trash",CareMuted,Modifier.size(24.dp))
                }
            }
            val boarded=r.kind=="pickup"&&r.status=="confirmed"
            val rest=r.kind=="note"&&(r.title.contains("쉬")||r.title.contains("휴식"))&&r.status=="confirmed"
            if(boarded||rest)Row(Modifier.padding(start=54.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(boarded)TextButton(onClick={care.relay.pickupAction("pickup-boarded",JSONObject().put("deviceId",r.deviceId))},modifier=Modifier.heightIn(min=48.dp)){Text("탑승 완료",fontSize=16.sp)}
                if(rest)TextButton(onClick=openRest,modifier=Modifier.heightIn(min=48.dp)){Text("쉬어 갈 곳 찾기",fontSize=16.sp)}
            }
        }
    }
}

/** Passenger connection: one status line and two icon buttons instead of a paragraph. */
@Composable internal fun PassengerConnectionCard(care:CareRuntime,showQr:Boolean,toggleQr:()->Unit,connect:()->Unit,qr:@Composable ColumnScope.()->Unit){
    ReadableCard("동승자 연결","link"){
        InfoLine(if(care.relay.connected)"check" else "refresh",care.relay.message,CareInk,17.sp)
        if(!care.relay.sharing)IconAction("동승자 연결","qr",connect,primary=true)
        else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            IconAction(if(showQr)"QR 접기" else "QR 보기","qr",toggleQr,Modifier.weight(1f))
            IconAction("모두 연결 종료","close",care::stopSharing,Modifier.weight(1f))
        }
        if(showQr&&care.relay.links.isNotEmpty())qr()
    }
}

/** Who may join, which phones get alerts, and active seat grants — as chips rather than switch lists. */
@Composable internal fun PassengerAccessCard(care:CareRuntime){
    ReadableCard("동승자 권한 관리","lock"){
        Text("연결 허용",color=CareMuted,fontSize=15.sp)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("family" to "가족","guest" to "손님","observer" to "상태 확인").forEach{(role,label)->
                val on=care.relay.permissions.optBoolean(role)
                FilterChip(selected=on,onClick={care.relay.permission(role,!on)},label={Text(label,fontSize=17.sp)},
                    leadingIcon={FamilyGlyph(if(on)"check" else "close",if(on)CareAccent else CareMuted,Modifier.size(18.dp))},
                    modifier=Modifier.heightIn(min=48.dp).testTag("role-$role"))
            }
        }
        if(care.relay.recipients.isNotEmpty()){
            HorizontalDivider(color=SoftLine)
            Text("알림 받는 기기",color=CareMuted,fontSize=15.sp)
        }
        care.relay.recipients.forEach{r->
            val arrival=r.optBoolean("arrival",true)
            val careOn=r.optBoolean("care",true)
            val client=r.optString("client")
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                FamilyGlyph("bell",CareInk,Modifier.size(22.dp))
                Text(r.optString("name"),Modifier.weight(1f),color=CareInk,fontSize=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                FilterChip(selected=arrival,onClick={care.relay.recipient(client,!arrival,careOn)},label={Text("도착 알림")},modifier=Modifier.heightIn(min=48.dp))
                FilterChip(selected=careOn,onClick={care.relay.recipient(client,arrival,!careOn)},label={Text("케어 알림")},modifier=Modifier.heightIn(min=48.dp))
            }
        }
        val grants=care.relay.delegations.filter{it.optBoolean("active")}
        if(grants.isNotEmpty()){
            HorizontalDivider(color=SoftLine)
            Text("승인한 조절 권한 · ${care.grantLifetime} 유지",color=CareMuted,fontSize=15.sp)
        }
        grants.forEach{g->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                FamilyGlyph("lock",CareInk,Modifier.size(22.dp))
                Text(g.optString("name")+" · 기능 "+(g.optJSONArray("scopes")?.length()?:0)+"개",Modifier.weight(1f),color=CareInk,fontSize=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                OutlinedButton(onClick={care.relay.revokeGrant(g.optString("deviceId"))},modifier=Modifier.heightIn(min=48.dp)){Text("회수",color=CareInk)}
            }
        }
    }
}

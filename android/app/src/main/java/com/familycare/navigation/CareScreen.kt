@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.navigation

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.familycare.ui.FamilyGlyph
import com.familycare.ui.MemoryComposer
import com.familycare.ui.MemoryGallery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val CareInk=Color(0xFF172332)
internal val CarePaper=Color(0xFFF5F6F7)
internal val CarePanel=Color.White
internal val CareAccent=Color(0xFF002C5F)
internal val CareMuted=Color(0xFF65717E)

@Composable
fun CareScreen(care:CareRuntime,onResize:()->Unit={},onNotification:()->Unit={},onStartTrip:()->Unit={care.startTrip()},onSpeedPermission:()->Unit={},onOpenNavigation:()->Unit={},onLocationPermission:()->Unit={}){
    val revision=care.revision
    com.familycare.ui.ProvideMapGateway(care.relay.mapGateway().takeIf{it.connected}){
        Box(Modifier.fillMaxSize()){
            CareScreenContent(care,onResize,onNotification,onStartTrip,onSpeedPermission,onOpenNavigation,onLocationPermission)
            DriverVoiceOverlay(care)
        }
    }
}

@Composable
private fun CareScreenContent(care:CareRuntime,onResize:()->Unit={},onNotification:()->Unit={},onStartTrip:()->Unit={care.startTrip()},onSpeedPermission:()->Unit={},onOpenNavigation:()->Unit={},onLocationPermission:()->Unit={}){
    val revision=care.revision
    val e=care.engine
    var tab by rememberSaveable{mutableStateOf("이동")}
    var consent by remember{mutableStateOf("")}
    var departureReview by remember{mutableStateOf(false)}
    val reviewDeparture:()->Unit={departureReview=true}
    var showQr by rememberSaveable{mutableStateOf(false)}
    var showSignals by rememberSaveable{mutableStateOf(false)}
    val scroll=rememberScrollState()
    LaunchedEffect(tab){scroll.scrollTo(0)}
    LaunchedEffect(care.navigation.route){if(care.navigation.route!=null)tab="이동"}
    BackHandler(tab!="이동"&&e.ux.showDetails){tab="이동"}
    MaterialTheme(colorScheme=lightColorScheme(primary=CareAccent,onPrimary=Color.White,surface=CarePanel,
        onSurface=CareInk,background=CarePaper,onBackground=CareInk,secondary=CareInk,
        secondaryContainer=CareAccent,onSecondaryContainer=Color.White,surfaceVariant=CarePanel,onSurfaceVariant=CareMuted,outline=CareMuted)){
    Surface(color=CarePaper,modifier=Modifier.fillMaxSize()){
        if(!e.ux.showDetails){
            if(e.ux.available){
                VehicleNavigationHome(care,{},{},{},{},{},readOnly=true)
            }else{
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("restricted"),contentAlignment=Alignment.Center){
                Text(e.ux.shortText(if(!e.ux.available)"주행 상태를 확인하고 있어요" else care.driverNotice.ifBlank{e.lastFeedback.ifBlank{"운전에 집중해 주세요"}}),color=CareInk,fontSize=28.sp,fontWeight=FontWeight.Bold)
            }
            }
        }else{
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().testTag("details")){
                val wide=maxWidth>=760.dp&&maxWidth>maxHeight
                if(tab=="이동"||tab=="지도")Box(Modifier.fillMaxSize().testTag(if(wide)"wide" else "narrow")){
                    VehicleNavigationHome(care,{tab=it},{if(care.relay.sharing){showQr=true;tab="요청"}else consent="share"},reviewDeparture,onOpenNavigation,onResize,onLocationPermission)
                }else Row(Modifier.fillMaxSize()){
                    if(wide)CareNavigation(tab,{tab=it},true)
                    Column(Modifier.weight(1f).fillMaxHeight()){
                        Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text("WithME",fontSize=18.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp,color=CareInk)
                                Text(when(tab){"이동"->"길안내 · 가족과 함께";"기록"->"추억";"동승자"->"가족";else->tab},fontSize=14.sp,color=CareMuted)
                            }
                            if(wide){Text(SimpleDateFormat("HH:mm",Locale.KOREA).format(Date()),fontSize=22.sp,color=CareInk);Spacer(Modifier.width(18.dp))}
                            IconButton(onClick=onResize,modifier=Modifier.size(64.dp).testTag("resize").semantics{contentDescription=if(wide)"화면 줄이기" else "화면 넓히기"}){FamilyGlyph("expand",CareInk)}
                        }
                        if(!wide)CareNavigation(tab,{tab=it},false)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(if(wide)24.dp else 16.dp)
                            .testTag(if(wide)"wide" else "narrow"),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    if(care.notice.isNotBlank())Text(care.notice,color=CareInk,fontSize=16.sp)
                    e.alerts(care.now()).forEach{alert->
                        CareCard(alert.title,Color(0xFFFFF0D2)){
                            if(alert.code=="rear")Action("직접 확인했어요",care::confirmRear)
                            if(alert.code=="break")Action("안전한 곳에서 휴식했어요",care::takeBreak)
                        }
                    }
                    when(tab){
                        "이동"->{
                            NavigationOverview(care,wide,reviewDeparture,onOpenNavigation,{showQr=false;tab="요청"},{if(care.relay.sharing){showQr=true;tab="요청"}else consent="share"})
                            CareCard("내리기 전 확인"){
                                Text("휴대폰 · 지갑",fontSize=20.sp);Text("가방",fontSize=20.sp)
                                Text("아이·반려동물과 뒷좌석을 직접 확인하세요. 이동 종료 시 확인을 도와드려요.",color=CareMuted)
                            }
                        }
                        "지도"->VehicleMapScreen(care,wide,{tab="이동"},onOpenNavigation)
                        "동승자"->{
                            CareCard("이번 이동의 가족 구성"){
                                InfoLine("lock","이름·연락처 없이 구성만 저장 · 이동 중에는 변경 불가")
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    Passenger.entries.forEach{p->FilterChip(selected=p in e.profile.members,onClick={care.togglePassenger(p)},enabled=!e.running&&!e.watching,label={Text(p.label,fontSize=19.sp)},modifier=Modifier.heightIn(min=64.dp).testTag("passenger-${p.name}"))}
                                }
                                if(Passenger.CHILD in e.profile.members)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    listOf("0~3세","4~7세","8세 이상").forEachIndexed{i,label->FilterChip(selected=e.profile.childBand==i,onClick={care.childBand(i)},enabled=!e.running,label={Text(label)},modifier=Modifier.heightIn(min=64.dp))}
                                }
                                Text("추천: ${e.profile.mode}",fontSize=21.sp,fontWeight=FontWeight.Bold)
                                FlowRow{listOf("낮은 민감도","기본 민감도","높은 민감도").forEachIndexed{i,label->FilterChip(e.profile.sensitivity==i,{care.sensitivity(i)},enabled=!e.running&&!e.watching,label={Text(label)},modifier=Modifier.heightIn(min=64.dp))}}
                                Text("급가감속 ${"%.1f".format(e.profile.accelerationLimit)} m/s² · 급조향 ${e.profile.steeringLimit.toInt()}°/s · 휴식 ${e.profile.breakMinutes}분")
                                Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=care.recordJourney,onCheckedChange=care::recording,enabled=!e.running);Text("이동 경로 기록",fontSize=18.sp);Text("  GPS·정차·이벤트",color=CareMuted,fontSize=14.sp)}
                                Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=care.shareJourney,onCheckedChange=care::sharingJourney);Text("가족과 기록 공유",fontSize=18.sp)}
                                InfoLine("save","최근 20개는 이 차량에 저장 · 공유하면 가족 휴대폰에도 저장할 수 있어요")
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){(0..1).forEach{slot->IconAction("구성 ${slot+1} 저장","save",{care.saveProfile(slot)});IconAction("구성 ${slot+1} 불러오기","refresh",{care.loadProfile(slot)})}}
                            }
                        }
                        "차량"->{
                            CareCard("공조 설정"){
                                var setting by rememberSaveable{mutableStateOf(23.0)}
                                var area by rememberSaveable{mutableStateOf("ROW_1_LEFT")}
                                com.familycare.ui.ClimateZones(care.climateZones,area){area=it}
                                val power=e.value(Signal.CLIMATE_POWER,care.now())
                                Text(if(power==null)"공조 전원 미확인" else if(power==1.0)"공조 켜짐" else "공조 꺼짐")
                                Button(onClick={care.applyClimate(e.value(Signal.CLIMATE_TARGET,care.now())?:setting,area,power=power!=1.0)},enabled=care.parked&&care.climatePowerSupported&&!care.climatePending,modifier=Modifier.testTag("vehicle-climate-power")){Text(if(power==1.0)"공조 끄기" else "공조 켜기")}
                                Text("현재 설정 "+formatReading(Signal.CLIMATE_TARGET,e.value(Signal.CLIMATE_TARGET,care.now())))
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)){
                                    Action("−",{setting=(setting-0.5).coerceAtLeast(18.0)})
                                    Text("${setting}°C",fontSize=34.sp,fontWeight=FontWeight.Bold)
                                    Action("+",{setting=(setting+0.5).coerceAtMost(28.0)})
                                }
                                Button(onClick={care.applyClimate(setting,zone=area,power=if(care.climatePowerSupported)true else null)},enabled=care.parked&&care.climateSupported&&!care.climatePending,modifier=Modifier.heightIn(min=64.dp).testTag("vehicle-climate")){Text("설정 온도 적용")}
                                InfoLine("lock","P단 정차 중에만 설정 · 실내 온도와 설정 온도는 달라요")
                            }

                            CareCard("주행 알림"){
                                Text(if(care.audioEnabled)"알림 소리 켜짐" else "알림 소리 꺼짐",fontSize=21.sp)
                                Text(care.audioStatus)
                                Text("요청과 상태를 한국어로 안내해요. 상세 확인·승인은 정차 후에 합니다.",color=CareMuted,fontSize=17.sp)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    Action(if(care.audioEnabled)"알림 소리 끄기" else "알림 소리 켜기",care::toggleDriverAudio)
                                    Action("알림 소리 시험",care::testDriverAudio)
                                }
                                if(care.audioResult.isNotBlank())Text(care.audioResult,fontSize=15.sp)
                                Text("최소 15초 간격 · 음성 실패 시 알림음 · 통화·음소거 설정에 따라 들리지 않을 수 있어요.",color=CareMuted,fontSize=15.sp)
                            }
                            CareCard("차량에서 직접 받은 상태"){
                                Text(care.closures(),fontSize=21.sp)
                                listOf(Signal.TEMPERATURE,Signal.BATTERY,Signal.SPEED).forEach{s->val v=e.value(s,care.now())
                                    Text("${s.label}   ${formatReading(s,v)}",fontSize=19.sp)
                                }
                                Action(if(showSignals)"상세 상태 접기" else "상세 상태 보기",{showSignals=!showSignals})
                                if(showSignals)Signal.entries.filter{it !in listOf(Signal.TEMPERATURE,Signal.BATTERY,Signal.SPEED)}.forEach{s->Text("${s.label}   ${formatReading(s,e.value(s,care.now()))}",fontSize=19.sp)}
                                Text("30초 이상 갱신되지 않은 값은 미확인으로 표시해요. 온도는 운전석 센서 값입니다. 뒷좌석·반려동물의 체감 온도를 보장하지 않아요.")
                                if(e.value(Signal.SPEED,care.now())==null)Action("속도 읽기 권한 확인",onSpeedPermission)
                            }
                            // Indoor camera view stays in the owner/family (WithME) phone apps only: the Pleos
                            // navigation-app review rejects video elements in APP_MAPS apps (2026-11-08).
                            CareCard("반려동물과 함께 확인"){
                                Text("온도 ${e.temperatureLimit.toInt()}°C 이상 / 배터리 ${e.batteryLimit.toInt()}% 이하를 확인 기준으로 사용합니다. 휴대폰에서 공조 보조 권한을 요청하고 승인받으면 사용할 수 있어요.")
                                if(care.holding){Text("공조 보조 ${((care.holdUntil-care.now())/1000).coerceAtLeast(0)}초 남음");Action("공조 보조 중단",care::stopHold)}
                                if(Passenger.PET !in e.profile.members)Text("먼저 동승자 탭에서 반려동물을 선택하세요.")
                                else{
                                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,5,10,20).forEach{n->FilterChip(selected=e.checkMinutes==n,onClick={care.setTimer(n)},enabled=!e.watching,label={Text("${n}분")},modifier=Modifier.heightIn(min=64.dp))}}
                                    if(e.watching){Text("확인까지 ${((e.checkMinutes*60000L-(care.now()-e.watchStartedAt!!)).coerceAtLeast(0)/1000)}초");Action("상태 확인 종료",care::stopWatch)}
                                    else Action("상태 확인 시작",{consent="watch"})
                                }
                                Text("차량 전원이 꺼지거나 앱·서버 연결이 끊기면 확인이 중단됩니다. 차에 혼자 두어도 된다는 뜻이 아닙니다.")
                            }
                        }
                        "요청"->{
                            RequestsTab(care){care.restSearchRequested=true;tab="지도"}
                            PassengerConnectionCard(care,showQr,{showQr=!showQr},{consent="share"}){JoinCodes(care.relay.links)}
                            if(care.relay.connected)PassengerAccessCard(care)
                        }
                        "고지"->{
                            val context=LocalContext.current
                            val notices=remember{context.assets.open("third_party_notices.txt").bufferedReader().use{it.readText()}}
                            CareCard("오픈소스·외부 라이브러리 고지"){Text(notices,fontSize=14.sp)}
                        }
                        "기록"->{
                            CareCard("경로와 주행 리포트"){
                                if(care.journey.active)com.familycare.ui.JourneyPanel(care.journey.json(),care.navigation.summary())
                                care.journeys.forEach{com.familycare.ui.JourneyPanel(it);HorizontalDivider()}
                                if(care.journeys.isEmpty()&&!care.journey.active)Text("가족 탭에서 위치 기록을 켜고 이동을 시작하세요.")
                                if(!e.running)Action("위치 기록 삭제",care::clearJourneys)
                            }
                            CareCard("우리의 여행 지도"){
                                com.familycare.ui.LocalAlbum(care.relay.memoryDetail,care.journeys+if(care.journey.active)listOf(care.journey.json())else emptyList())
                                MemoryGallery(care.relay.memories,if(care.relay.connected)care.relay::deleteMemory else null,care.relay.memoryDetail,care.relay::openMemory)
                                if(!care.relay.connected)Text("휴대폰 연결을 시작하면 가족과 사진 지도를 공유할 수 있어요.")
                                MemoryComposer(care.relay.connected,care.relay::shareMemory,care.relay.mapGateway())
                                Text(care.relay.message,color=CareMuted)
                            }

                            CareCard("우리의 이동 기록"){
                                Text("최근 20개 요약을 저장합니다. GPS 경로는 가족 탭에서 기록을 켠 이동에만 남아요.")
                                if(care.history.isEmpty())Text("이동을 종료하면 여기에 기록돼요.")
                                care.history.forEach{trip->
                                    Text(SimpleDateFormat("MM월 dd일 HH:mm",Locale.KOREA).format(Date(trip.startedAt))+" · "+trip.mode,fontWeight=FontWeight.Bold)
                                    Text("이동 ${trip.durationSeconds/60}분 / 주행 ${trip.drivingSeconds/60}분 · 가속 ${trip.feedback.acceleration} / 감속 ${trip.feedback.braking} / 조향 ${trip.feedback.steering}")
                                    HorizontalDivider()
                                }
                                Action("연결 상태 알림 허용",onNotification)
                                Action("오픈소스 고지",{tab="고지"})
                                if(!care.retained)Action("프로필·기록 삭제",{consent="delete"})
                            }
                            CareCard("연결과 지원 기능"){
                                Text("차량이 제공하지 않는 신호는 미확인으로 표시합니다. 시동·문 잠금은 이 연결에서 제어할 수 없어요. 휴대폰 앱은 연결 화면에서 백그라운드 알림을 켤 수 있습니다. 웹 알림은 페이지가 열려 있어야 합니다.")
                            }
                        }
                    }
                    Text("WithME ${BuildConfig.VERSION_NAME} · 정차 중 설정 · 미수신 정보는 미확인",color=CareInk.copy(alpha=.65f),fontSize=14.sp)
                        }
                    }
                }
            }
            if(departureReview)AlertDialog(onDismissRequest={departureReview=false},title={Text("이번 이동 추천 · ${e.profile.mode}")},text={Text("탑승 유형: "+e.profile.members.joinToString{it.label}.ifBlank{"선택 없음"}+"\n휴식 간격 ${e.profile.breakMinutes}분\nGPS 기록 ${if(care.recordJourney)"켜짐" else "꺼짐"} · 가족 경로 공유 ${if(care.shareJourney)"켜짐" else "꺼짐"}")},confirmButton={TextButton(onClick={departureReview=false;onStartTrip()}){Text("확인하고 이동 시작")}},dismissButton={TextButton(onClick={departureReview=false;tab="동승자"}){Text("가족 설정 변경")}})
            if(consent.isNotEmpty())AlertDialog(onDismissRequest={consent=""},title={Text(when(consent){"share"->"동승자와 연결할까요?";"watch"->"직접 확인을 도와드릴게요";else->"저장된 구성을 지울까요?"})},text={Text(when(consent){
                "share"->"차량 상태·이동 시간·도착 정보·요청을 연결한 사람에게 공유합니다. 선택한 탑승 유형과 아이 나이대는 소유자·가족에게만 공유합니다. 서버 메모리에 최대 2시간 보관하고 연결 종료 시 삭제합니다. 링크를 가진 사람은 해당 역할로 참여할 수 있어요."
                "watch"->"앱이 실행되는 동안 차량 신호와 타이머를 확인합니다. 차량 전원과 통신이 유지되어야 합니다. 차에 아이나 반려동물을 혼자 두어도 안전하다는 뜻이 아닙니다. 알림이 없어도 직접 확인하세요."
                else->"프로필 2개와 최근 이동 기록이 삭제됩니다. 진행 중인 뒷좌석 확인은 유지됩니다."
            })},confirmButton={TextButton(onClick={when(consent){"share"->{care.share();showQr=true;tab="요청"};"watch"->care.startWatch();else->care.clearLocal()};consent=""}){Text(if(consent=="delete")"삭제" else "확인하고 시작")}},dismissButton={TextButton(onClick={consent=""}){Text("취소")}})
        }
    }
    }
}

@Composable private fun CareCard(title:String,color:Color=CarePanel,dark:Boolean=false,content:@Composable ColumnScope.()->Unit){
    Surface(color=color,shape=RoundedCornerShape(4.dp),border=BorderStroke(1.dp,Color(0xFFD8DDE3))){Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text(title,color=if(dark)Color.White else CareInk,fontSize=23.sp,fontWeight=FontWeight.Bold)
        CompositionLocalProvider(LocalContentColor provides CareInk){content()}
    }}
}
@Composable private fun Action(label:String,action:()->Unit){OutlinedButton(onClick=action,modifier=Modifier.heightIn(min=64.dp),border=BorderStroke(1.dp,Color(0xFF6C727D)),colors=ButtonDefaults.outlinedButtonColors(containerColor=CarePaper,contentColor=CareInk),shape=RoundedCornerShape(8.dp)){Text(label,fontSize=19.sp,fontWeight=FontWeight.Medium)}}
@Composable private fun JoinCodes(links:Map<String,String>){
    var role by remember{mutableStateOf("family")}
    val clipboard=LocalClipboardManager.current
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("owner" to "소유자","family" to "가족","guest" to "손님","observer" to "상태 확인").forEach{(key,label)->FilterChip(selected=role==key,onClick={role=key},label={Text(label)},modifier=Modifier.heightIn(min=64.dp))}}
    InfoLine("lock",if(role=="owner")"소유자 QR → ② WithME 내 차 앱 전용 · 동승자에게 공유 금지" else "가족·손님 QR → ③ WithME 가족 앱",CareInk,16.sp)
    val url=links[role].orEmpty()
    val qr=remember(url){runCatching{val m=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360);Bitmap.createBitmap(360,360,Bitmap.Config.ARGB_8888).apply{for(y in 0 until 360)for(x in 0 until 360)setPixel(x,y,if(m[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}.getOrNull()}
    qr?.let{Image(it.asImageBitmap(),"동승자 연결 QR",Modifier.sizeIn(maxWidth=280.dp,maxHeight=280.dp).aspectRatio(1f).testTag("join-qr"))}
    Text(when(role){"owner"->"차량 공조 관리와 동승자 권한 관리 · 소유자에게만 제공";"family"->"장소·한마디 요청, 투표, 상태 확인";"guest"->"한마디 요청과 상태 확인";else->"상태·알림 확인 전용 · 미확인 알림은 1분 뒤 강조"})
    if(url.startsWith("http://127.0.0.1"))Text("현재 PC에서만 연결할 수 있어요. 휴대폰 연결 서버를 켜고 같은 Wi-Fi에서 새 QR을 사용하세요.")
    Action("연결 링크 복사",{clipboard.setText(AnnotatedString(url))})
}
private fun formatReading(s:Signal,v:Double?):String{
    if(v==null)return "미확인"
    return when(s){Signal.TEMPERATURE,Signal.CLIMATE_TARGET->"%.1f°C".format(v);Signal.BATTERY->"${v.toInt()}%";Signal.SPEED->"%.1f km/h".format(kotlin.math.abs(v)*3.6)
        Signal.CLIMATE_POWER->if(v==1.0)"켜짐" else "꺼짐"
        Signal.FL_DOOR,Signal.FR_DOOR,Signal.RL_DOOR,Signal.RR_DOOR->if(v==0.0)"닫힘" else "열림"
        Signal.FL_WINDOW,Signal.FR_WINDOW,Signal.RL_WINDOW,Signal.RR_WINDOW->"${v.toInt()}% 열림"
        Signal.BRAKE->if(v==1.0)"밟음" else "놓음";Signal.STEERING->"%.1f°".format(v)
        Signal.GEAR->when(v.toInt()){4->"P";8->"D";2->"R";1->"N";else->"${v.toInt()}"}
        Signal.IGNITION->when(v.toInt()){1->"LOCK";2->"OFF";3->"ACC";4->"ON";5->"START";else->"확인 필요 (${v.toInt()})"}}
}

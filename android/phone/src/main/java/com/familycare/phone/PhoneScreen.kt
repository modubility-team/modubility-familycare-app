@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.phone

import androidx.activity.compose.BackHandler
import com.familycare.ui.PlaceMap
import com.familycare.ui.MemoryComposer
import com.familycare.ui.MemoryGallery
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycare.ui.FamilyGlyph
import org.json.JSONObject

private val Ink=Color(0xFF142435)
private val Muted=Color(0xFF65717D)
private val Back=Color(0xFFF6F7F8)
private val Accent=Color(0xFF002C5F)
private val Line=Color(0xFFE4E8EC)
private val Shape=RoundedCornerShape(6.dp)

@Composable fun PhoneScreen(m:PhoneModel,openOtherApp:()->Unit={},openMap:(String,Double?,Double?)->Unit){
    val connected=m.connected
    val stale=m.stale
    com.familycare.ui.ProvideMapGateway(if(connected&&!stale)m.mapGateway()else null){
        PhoneScreenContent(m,openOtherApp,openMap)
    }
}

@Composable private fun PhoneScreenContent(m:PhoneModel,openOtherApp:()->Unit={},openMap:(String,Double?,Double?)->Unit){
    val owner=BuildConfig.PRODUCT=="navigation"
    val tabs=if(owner)listOf("내 차","관리","가족","추억")else listOf("홈","요청","반려동물","추억","권한")
    var tab by rememberSaveable{mutableStateOf(tabs.first())}
    var lastTab by rememberSaveable{mutableStateOf(tabs.first())}
    var vehicleName by rememberSaveable{mutableStateOf(m.vehicleName)}
    var vehicleModel by rememberSaveable{mutableStateOf(m.vehicleModel)}
    var plate by rememberSaveable{mutableStateOf(m.vehiclePlate)}
    var editingCar by rememberSaveable{mutableStateOf(false)}
    var climateArea by rememberSaveable{mutableStateOf("ROW_1_LEFT")}
    var name by remember{mutableStateOf("")}
    var lat by remember{mutableStateOf<Double?>(null)}
    var lon by remember{mutableStateOf<Double?>(null)}
    var note by rememberSaveable{mutableStateOf("")}
    var kind by rememberSaveable{mutableStateOf("destination")}
    var notices by remember{mutableStateOf(false)}
    var removeCar by remember{mutableStateOf(false)}
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val scroll=rememberScrollState()
    var jumpTo by remember{mutableStateOf("")}
    var controlGroup by rememberSaveable{mutableStateOf("공조")}
    val anchors=remember{mutableStateMapOf<String,Int>()}
    fun choose(next:String,section:String=""){
        jumpTo=section;if(section=="doors")controlGroup="문과 창문"
        tab=next;if(next in tabs)lastTab=next
    }
    LaunchedEffect(tab){if(jumpTo.isEmpty())scroll.scrollTo(0)}
    LaunchedEffect(tab,jumpTo,anchors[jumpTo]){
        if(tab in listOf("관리","요청")&&jumpTo.isNotEmpty())anchors[jumpTo]?.let{y->
            withFrameNanos{};scroll.scrollTo(y.coerceAtMost(scroll.maxValue));jumpTo=""
        }
    }
    LaunchedEffect(m.grant.optString("epoch"),m.preferredClimateArea){climateArea=m.preferredClimateArea}
    LaunchedEffect(m.permissionPrompt){if(!owner&&m.permissionPrompt>0&&m.role=="family")tab=if(m.pickup.optBoolean("enabled"))"픽업" else "권한"}
    LaunchedEffect(m.joinText){if(m.joinText.isNotBlank())tab="연결"}
    LaunchedEffect(m.handoffLink){if(m.handoffLink.isNotBlank())tab="연결"}
    BackHandler(tab!=tabs.first()){tab=if(tab=="연결")lastTab else tabs.first()}
    MaterialTheme(colorScheme=lightColorScheme(primary=Accent,onPrimary=Color.White,background=Back,onBackground=Ink,surface=Color.White,onSurface=Ink,surfaceVariant=Back,onSurfaceVariant=Muted,outline=Muted,outlineVariant=Line)){
        Surface(color=Back,modifier=Modifier.fillMaxSize()){
            Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter){Column(Modifier.widthIn(max=620.dp).fillMaxSize()){
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text("WithME",fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,color=Accent)
                        Text(if(owner)"내 차" else "가족",fontSize=27.sp,fontWeight=FontWeight.Bold)
                    }
                    OutlinedButton(onClick={choose(if(tab=="연결")lastTab else "연결")},modifier=Modifier.heightIn(min=48.dp).testTag("phone-tab-연결")){
                        FamilyGlyph("link",Accent,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(m.connected){if(m.stale)"확인 중" else "연결됨"}else "연결")
                    }
                }
                HorizontalDivider(color=Line)
                if(m.resultMessage.isNotBlank())Surface(color=Ink,modifier=Modifier.fillMaxWidth()){
                    Row(Modifier.padding(start=20.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(m.resultMessage,color=Color.White,modifier=Modifier.weight(1f).testTag("result-message"),style=MaterialTheme.typography.bodyMedium)
                        TextButton(onClick=m::dismissResult){Text("닫기",color=Color.White)}
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                    if(tab!="연결"&&(!m.connected||m.stale))RowLink(if(owner)"차량에서 소유자 QR을 열어 주세요" else "같이 탈 차량을 연결해 주세요",if(m.connected)"차량 응답 확인 중" else "차량 QR → 연결 확인","link"){choose("연결")}
                    if(tab=="홈")RowLink("타기 전 픽업","차량 위치 · 내가 기다리는 곳 전달","pin"){choose("픽업")}
                    SharedAgreementPanel(m)
                CareInboxPanel(m)
                    if(!m.isOwner&&tab=="홈")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        QuickTile("상황별 케어","people",Modifier.weight(1f),tag="open-situation-care"){choose("상황 케어")}
                        QuickTile("가족 경로 기준","pin",Modifier.weight(1f),tag="open-family-needs"){choose("이동 기준")}
                    }
                when(tab){
                        "음악 공유"->PhoneMusicPanel(m)
                        "픽업"->PickupPanel(m)
                        "상황 케어"->SituationCarePanel(m)
                        "이동 기준"->FamilyRouteNeedsPanel(m)
                        "내 차"->{
                            VehicleShowcase(m,true)
                            RowLink("휴대폰 음악 공유","같은 Wi-Fi로 차량에서 음악 듣기","volume"){choose("음악 공유")}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                QuickTile("공조","cool",Modifier.weight(1f),tag="owner-climate"){choose("관리","climate")}
                                QuickTile("가족","people",Modifier.weight(1f)){choose("가족")}
                                QuickTile("시동","power",Modifier.weight(1f)){m.explain("현재 Pleos 차량 연결에는 원격 시동 기능이 제공되지 않아요. 공조·문·창문은 관리 화면에서 조절할 수 있어요.")}
                                QuickTile("문·창문","lock",Modifier.weight(1f),tag="owner-doors"){choose("관리","doors")}
                            }
                            Text("공조·문·창문은 관리에서 조절할 수 있어요. 원격 시동은 현재 차량에서 지원하지 않습니다.",color=Muted,fontSize=12.sp)
                            if(!editingCar)RowLink(if(m.vehicleName.isBlank())"내 자동차 등록" else "차량 정보 수정","내 차의 별명과 모델을 관리하세요","car"){editingCar=true}
                            if(editingCar)CardBlock("내 자동차 등록"){
                                Text("차량 정보를 이 휴대폰에 저장합니다. 실제 제어 권한은 차량에서 소유자 QR로 연결해야 활성화됩니다.",color=Muted)
                                OutlinedTextField(vehicleName,{vehicleName=it.take(30)},label={Text("차량 별명")},modifier=Modifier.fillMaxWidth().testTag("vehicle-name"))
                                OutlinedTextField(vehicleModel,{vehicleModel=it.take(40)},label={Text("차량 모델")},modifier=Modifier.fillMaxWidth().testTag("vehicle-model"))
                                OutlinedTextField(plate,{plate=it.take(20)},label={Text("차량 번호 · 선택")},modifier=Modifier.fillMaxWidth())
                                Primary("내 차 등록",{m.registerVehicle(vehicleName,vehicleModel,plate);if(vehicleName.isNotBlank()&&vehicleModel.isNotBlank())editingCar=false},tag="register-vehicle")
                            }
                            ArrivalCard(m)
                            if(m.vehicleName.isNotBlank())TextButton(onClick={removeCar=true}){Text("이 휴대폰의 차량 등록 삭제")}
                        }
                        "관리"->{
                            Text("내 차를 쾌적하게",fontSize=28.sp,fontWeight=FontWeight.Bold)
                            Column(Modifier.onGloballyPositioned{anchors["climate"]=it.positionInParent().y.toInt()}){
                                CardBlock("실내 공조"){ClimateTemperatureControls(m,climateArea){climateArea=it}}
                            }
                            Column(Modifier.onGloballyPositioned{anchors["doors"]=it.positionInParent().y.toInt()}){
                                VehicleControlPanel(m,controlGroup,{controlGroup=it})
                            }
                            CardBlock("최근 관리 결과"){
                                TextButton(onClick=m::clearManagementResults,enabled=m.isOwner&&m.managementRequests.any(m::canDeleteResult),modifier=Modifier.testTag("clear-management-results")){Text("전부 지우기")}
                                Text("이 휴대폰의 완료 기록을 지웁니다. 처리 중인 요청은 유지됩니다.",color=Muted,fontSize=12.sp)
                                RequestCards(m,false,management=true)
                            }
                            CareAlerts(m)
                            CardBlock("실내 확인"){com.familycare.ui.CameraPanel(m.mapGateway().takeIf{!m.stale&&m.isOwner},m.isOwner)}
                            ArrivalCard(m)
                        }
                        "가족"->{
                            Text("함께 탈 사람 관리",fontSize=28.sp,fontWeight=FontWeight.Bold)
                            RowLink("타기 전 픽업","차량 위치 공유와 동승 예정자 대기 현황","pin"){choose("픽업")}
                            if(m.situation.optString("mode").isNotBlank())CardBlock("현재 가족 케어"){
                                Text(m.situation.optString("label"));Text("담당: "+m.situation.optString("caregiverName"))
                                Text(if(m.situation.optBoolean("caregiverOnline"))"일반 요청을 보호자에게 먼저 전달 중" else "보호자 연결 끊김 · 운전자 알림으로 전환")
                                Text(com.familycare.ui.RestStopText.phone(m.situation))
                            }
                            m.routeNeeds.filter{it.optJSONArray("needs")?.length()?.let{n->n>0}==true}.forEach{d->val a=d.getJSONArray("needs");Text(d.optString("name")+": "+(0 until a.length()).joinToString(" · "){a.getString(it)})}
                            FamilyProfilePanel(m)
                            Text("동승자가 요청한 권한은 차량에서 승인합니다. 승인 범위는 ${m.grantLifetime} 유지됩니다.",color=Muted)
                            GrantManagement(m)
                            listOf("family" to "가족", "guest" to "손님", "observer" to "상태 확인").forEach{(role,label)->CardBlock(label){
                                Text(when(role){"family"->"장소·온도 요청, 가족 투표, 추억 사진";"guest"->"한마디 요청과 차량 상태 확인";else->"상태와 확인 알림만 보기"},color=Muted)
                                Row(verticalAlignment=Alignment.CenterVertically){Text("연결 허용",Modifier.weight(1f));Switch(checked=m.permissions.optBoolean(role),onCheckedChange={m.permission(role,it)},enabled=m.isOwner&&!m.stale&&!m.busy,modifier=Modifier.testTag("permission-$role"))}
                                val link=m.invites.optString(role)
                                if(link.isNotBlank())TextButton(onClick={clipboard.setText(AnnotatedString(link));m.explain("${label} 연결 링크를 복사했어요")}){Text("초대 링크 복사")}
                            }}
                            Text("연결을 끄면 기존 링크와 대기 중인 요청이 취소됩니다. 다시 켜면 새 링크를 전달하세요.",color=Muted,fontSize=13.sp)
                            Primary("WithME 가족 앱 열기",openOtherApp,m.isOwner&&m.canShareConnection,"open-other-app")
                        }
                        "홈"->{
                            VehicleShowcase(m,false)
                            RowLink("차량 조절 권한",if(m.grant.optBoolean("active"))"승인 완료 · ${m.grantLifetime} 사용" else "사용할 기능을 선택하고 운전자에게 요청","people"){choose("권한")}
                            RowLink("가족 구성과 맞춤 모드","탑승 유형·아이 나이대 · 승인된 권한으로 변경","people"){choose("가족설정")}
                            SectionTitle("운전자에게 요청")
                            RequestGrid(m)
                            Text("운전자 승인 또는 허용된 권한에 따라 처리돼요.",color=Muted,fontSize=12.sp)
                            RowLink("반려동물 케어",if(m.state.optBoolean("watching"))"확인 타이머가 진행 중이에요" else "온도·배터리·복귀 타이머 확인","pet"){choose("반려동물")}
                            RowLink("사진 한 장, 오늘의 추억","촬영 위치와 시간을 여행 지도에","pin"){choose("추억")}
                            RowLink("쉬어 갈 곳 찾기","앞쪽 휴게소 검색 → 승인된 권한으로 경유지 반영","rest"){kind="waypoint";choose("요청","places")}
                            RowLink("장소 제안","목적지와 경유지를 검색해서 제안해요","route"){kind="destination";choose("요청","places")}
                            RowLink("가족 투표","가족이 제안한 장소에 투표해요","people"){choose("요청","votes")}
                            ArrivalCard(m)
                        }
                        "권한"->{PermissionPanel(m)}
                        "가족설정"->{FamilyProfilePanel(m);RequestCards(m,false)}
                        "요청"->{
                            CardBlock("온도 조절"){
                                ClimateTemperatureControls(m,climateArea){climateArea=it}
                            }
                            VehicleControlPanel(m)
                            CardBlock("한마디 전하기"){
                                OutlinedTextField(note,{note=it.take(120)},label={Text("운전자에게 보낼 내용")},modifier=Modifier.fillMaxWidth().testTag("request-note"))
                                Primary("요청 보내기",{m.request("note",note)},m.canRequest&&note.isNotBlank(),"send-note")
                            }
                            Column(Modifier.onGloballyPositioned{anchors["places"]=it.positionInParent().y.toInt()}.testTag("request-places")){CardBlock("목적지·경유지 제안"){
                                val remaining=m.state.optJSONObject("routeSummary")?.optJSONArray("remainingRoute")
                                val start=remaining?.optJSONArray(0)
                                com.familycare.ui.PlaceSearch(start?.optDouble(1)?:lat?:com.familycare.ui.MapPlaces.LATITUDE,start?.optDouble(0)?:lon?:com.familycare.ui.MapPlaces.LONGITUDE,
                                    gateway=m.mapGateway(),route=remaining,initialFacility=if(kind=="waypoint")"휴게소" else ""){p->lat=p.latitude;lon=p.longitude;name=p.name}
                                PlaceMap(lat,lon,{a,b->lat=a;lon=b;name="지도에서 선택한 곳"},Modifier.fillMaxWidth().height(270.dp))
                                OutlinedTextField(name,{name=it.take(120)},label={Text("장소 이름")},modifier=Modifier.fillMaxWidth().testTag("place-name"))
                                Row{FilterChip(selected=kind=="destination",onClick={kind="destination"},label={Text("목적지")});Spacer(Modifier.width(8.dp));FilterChip(selected=kind=="waypoint",onClick={kind="waypoint"},label={Text("경유지")})}
                                Text(if(lat==null)"이름을 검색하거나 지도에서 장소를 선택하세요" else "%.5f, %.5f".format(lat,lon),color=Muted)
                                Primary("차량에 장소 제안",{m.request(kind,name,lat,lon)},m.canPlan&&lat!=null&&name.isNotBlank(),"send-place")
                                TextButton(onClick={lat=37.48544722;lon=127.03636666;name="42dot"},modifier=Modifier.testTag("sample-place")){Text("42dot 선택")}
                            }
                            }
                            Column(Modifier.onGloballyPositioned{anchors["votes"]=it.positionInParent().y.toInt()}.testTag("request-votes"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text("함께 정하는 목적지 · 요청 결과",fontWeight=FontWeight.Bold,fontSize=20.sp)
                                if(m.requests.none{it.optString("kind") in listOf("destination","waypoint")})Text("아직 투표할 장소가 없어요. 장소를 먼저 제안해 주세요.")
                                RequestCards(m,true)
                            }
                        }
                        "반려동물"->{
                            Text("작은 가족도 함께",fontSize=28.sp,fontWeight=FontWeight.Bold)
                            CareAlerts(m)
                            CardBlock("차 안 상태"){
                                Row{Reading("실내 온도",m.state.reading("temperature"),"°C",Modifier.weight(1f));Reading("배터리",m.state.reading("battery"),"%",Modifier.weight(1f))}
                                Text(if(m.stale)"현재 차량 상태 미확인" else m.state.optString("closures","상태 미확인"))
                                Text(if(m.state.optBoolean("watching"))"다음 직접 확인까지 ${m.state.optInt("watchRemainingSeconds").coerceAtLeast(0)/60}분" else "차량에서 반려동물 확인 타이머를 시작하세요",fontWeight=FontWeight.Bold)
                                Primary("반려동물 확인 요청",{m.request("note","반려동물과 뒷좌석 상태를 직접 확인해 주세요")},m.canRequest,"pet-check-request")
                            }
                            PetTimerPanel(m)
                            CardBlock("실내 확인"){com.familycare.ui.CameraPanel(m.mapGateway().takeIf{!m.stale&&m.role in listOf("owner","family")},m.isOwner)}
                            RowLink("알림·보호자 설정","화면 밖 알림과 가족 수신 기기 관리","people"){choose("알림설정")}
                            Text("온도는 차량 센서 값입니다. 반려동물을 차에 혼자 두어도 된다는 의미가 아니며, 차량 전원·연결이 끊기면 상태 확인도 중단돼요.",color=Muted,fontSize=13.sp)
                        }
                        "추억"->{
                            CardBlock("이동 기록 · 리포트"){
                                m.state.optJSONObject("journey")?.let{com.familycare.ui.JourneyPanel(it,m.state.optJSONObject("routeSummary"));Primary("이 휴대폰에 기록 저장",m::archiveJourney)}?:Text("차량 가족 탭에서 GPS 기록과 가족 공유를 켜면 경로·정차·급조작 기록을 볼 수 있어요.")
                                m.archivedJourneys.forEach{com.familycare.ui.JourneyPanel(it);HorizontalDivider()}
                                if(m.archivedJourneys.isNotEmpty())TextButton(onClick=m::clearArchive){Text("휴대폰 이동 기록 삭제")}
                            }
                            Text("우리의 여행 지도",fontSize=28.sp,fontWeight=FontWeight.Bold)
                            val live=m.connected&&!m.stale
                            MemoryGallery(if(live)m.memories else m.localMemories,if(m.canMemories)m::deleteMemory else null,m.memoryDetail,if(live)m::openMemory else null)
                            CardBlock("이 휴대폰에 보관 · 다시 연결 시 자동 공유"){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Text(if(m.keepMemories)"보관 중 ${m.localMemories.size}장" else "보관 안 함",modifier=Modifier.weight(1f))
                                    Switch(m.keepMemories,m::changeKeepMemories,modifier=Modifier.testTag("keep-memories"))
                                }
                                Text("가족·소유자가 공유한 추억 사진을 이 휴대폰에도 최근 30장까지 보관해요. 노트북을 다시 켜거나 연결이 끊겼다가 다시 연결되면 보관한 사진을 차량과 다른 휴대폰에 자동으로 다시 공유해요(한 번에 12장). 끄면 보관한 사진을 지워요.",color=Muted,fontSize=13.sp)
                                if(!live&&m.localMemories.isNotEmpty())Text("지금은 차량에 연결되어 있지 않아 이 휴대폰에 보관한 사진만 보여요.",color=Muted,fontSize=13.sp)
                            }
                            CardBlock("추억 더하기"){MemoryComposer(m.canMemories,m::shareMemory,m.mapGateway(),m.memoryShareBlocker)}
                            com.familycare.ui.LocalAlbum(m.memoryDetail,m.archivedJourneys+listOfNotNull(m.state.optJSONObject("journey")))
                        }
                        "알림설정"->{NotificationPanel(m)}
                        "연결"->{
                            Text(m.message,color=Muted,modifier=Modifier.testTag("connection-status"))
                            Text("연결 후 앱을 나가거나 다시 열어도 같은 차량 연결을 유지합니다. 연결을 끝내려면 ‘연결 해제’를 누르세요.",color=Muted,fontSize=13.sp)
                            if(!m.stale){Text("GPS · "+m.state.optString("gpsStatus","확인 중"));Text("길찾기 · "+m.state.optString("mapProvider","확인 중"))}
                            if(m.handoffLink.isNotBlank())CardBlock(if(owner)"WithME 가족 앱에서 열 연결" else "내 차 앱에서 열 연결"){
                                Text(if(owner)"방금 연 QR은 가족·손님용이에요. 권한 요청·픽업 대기 위치 보내기 같은 동승자 화면은 WithME 가족 앱에서 사용합니다." else "방금 연 QR은 소유자용이에요. 차량 관리·권한 승인·픽업 공유는 내 차 앱에서 사용합니다.",color=Muted)
                                Primary(if(owner)"WithME 가족 앱에서 열기" else "내 차 앱에서 열기",openOtherApp,true,"open-handoff")
                                TextButton(onClick=m::clearHandoff){Text("닫기")}
                            }
                            CardBlock(if(owner)"소유자 연결" else "동승자 연결"){
                                Text(if(owner)"차량에서 소유자 QR을 선택하세요. 차량 등록 정보만으로 조절 권한이 생기지 않습니다. 가족·손님 QR은 WithME 가족 앱에서 여세요." else "차량이나 소유자가 전달한 가족·손님 링크로 연결하세요. 소유자 QR은 내 차 앱에서 여세요.")
                                OutlinedTextField(m.joinText,{m.joinText=it},label={Text("연결 링크")},modifier=Modifier.fillMaxWidth().testTag("join-link"),maxLines=3)
                                TextButton(onClick={m.joinText=clipboard.getText()?.text.orEmpty()}){Text("복사한 링크 붙여넣기")}
                                Text("연결한 차량의 상태·요청을 공유합니다. 사진은 따로 선택해 공유하며 서버에는 연결 종료 또는 최대 2시간까지 보관합니다.",fontSize=13.sp,color=Muted)
                                Primary("정보 확인하고 연결",m::connect,m.canConnect,"connect")
                                if(m.connected){Text("현재 권한 · "+when(m.role){"owner"->"소유자";"family"->"가족";"guest"->"손님";"observer"->"상태 확인";else->"확인 중"});OutlinedButton(onClick=m::disconnect){Text("연결 해제")}}
                            }
                            Text("같은 Wi-Fi의 개발용 연결입니다. 제조사 계정 인증·실차 원격 시동 서비스는 연결되지 않았습니다.",fontSize=13.sp,color=Muted)
                            RowLink("알림·보호자 설정","백그라운드 알림, 연락처, 도착 수신 대상","people"){choose("알림설정")}
                            TextButton(onClick={notices=true}){Text("오픈소스 고지 · "+BuildConfig.VERSION_NAME)}
                        }
                    }
                }
                HorizontalDivider(color=Line)
                Row(Modifier.fillMaxWidth().background(Color.White),horizontalArrangement=Arrangement.SpaceEvenly){
                    tabs.forEachIndexed{i,t->TextButton(onClick={choose(t)},modifier=Modifier.weight(1f).heightIn(min=72.dp).testTag("phone-tab-$t")){
                        Column(horizontalAlignment=Alignment.CenterHorizontally){FamilyGlyph((if(owner)listOf("car","cool","people","pin")else listOf("home","chat","pet","pin","people"))[i],if(tab==t)Accent else Muted,Modifier.size(23.dp));Text(t,color=if(tab==t)Accent else Muted,fontSize=12.sp)}
                    }}
                }
            }}
        }
        if(notices){val text=remember{context.assets.open("third_party_notices.txt").bufferedReader().use{it.readText()}};AlertDialog(onDismissRequest={notices=false},title={Text("오픈소스 고지")},text={Text(text,Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={TextButton(onClick={notices=false}){Text("닫기")}})}
        if(removeCar)AlertDialog(onDismissRequest={removeCar=false},title={Text("내 차 등록을 삭제할까요?")},text={Text("이 휴대폰의 차량 정보와 현재 연결을 지웁니다. 제조사의 실제 차량 등록에는 영향을 주지 않습니다.")},confirmButton={TextButton(onClick={m.removeVehicle();removeCar=false}){Text("삭제")}},dismissButton={TextButton(onClick={removeCar=false}){Text("취소")}})
    }
}

@Composable internal fun TemperatureSelector(value:Double,minimum:Double=18.0,maximum:Double=28.0,change:(Double)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
        OutlinedButton(onClick={change((value-0.5).coerceAtLeast(minimum))},modifier=Modifier.size(56.dp).testTag("temperature-down"),contentPadding=PaddingValues(0.dp)){Text("−",fontSize=26.sp)}
        Text("%.1f°C".format(value),fontSize=38.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("temperature-selected"))
        OutlinedButton(onClick={change((value+0.5).coerceAtMost(maximum))},modifier=Modifier.size(56.dp).testTag("temperature-up"),contentPadding=PaddingValues(0.dp)){Text("+",fontSize=26.sp)}
    }
}
@Composable private fun CareAlerts(m:PhoneModel){
    if(m.alerts.isEmpty())CardBlock("확인 알림"){Text(if(m.stale)"현재 상태 미확인" else "받은 확인 알림이 없어요")}
    m.alerts.forEach{a->CardBlock(a.optString("title","직접 확인해 주세요")){
        Text(if(m.stale)"마지막 수신 정보 · 현재 미확인" else if(a.optBoolean("escalated"))"아직 확인하지 않았어요. 직접 확인해 주세요." else "차량에서 온 확인 요청")
        Primary(if(a.optBoolean("acknowledged"))"확인 전달됨" else "직접 확인했어요",{m.acknowledge(a.getString("code"))},!m.stale&&!a.optBoolean("acknowledged")&&m.role in listOf("owner","family","observer"))
    }}
    Text("연결 → 알림·보호자 설정에서 백그라운드 알림을 켤 수 있어요. 같은 Wi-Fi와 차량 전원이 필요합니다. 자동 문자·전화는 보내지 않아요.",fontSize=12.sp,color=Muted)
    if(m.alertHistory.isNotEmpty())CardBlock("지난 확인 알림"){
        m.alertHistory.asReversed().take(30).forEach{a->
            Text(a.optString("title")+" · "+if(a.optBoolean("acknowledged"))"직접 확인 전달됨" else "상태 해제됨")
            Text("발생 "+com.familycare.ui.journeyTime(a.optLong("startedAt"))+" / 해제 "+com.familycare.ui.journeyTime(a.optLong("resolvedAt")),fontSize=12.sp,color=Muted)
            if(a.optBoolean("acknowledged"))Text("확인 "+com.familycare.ui.journeyTime(a.optLong("acknowledgedAt")),fontSize=12.sp,color=Muted)
        }
    }
}

@Composable private fun CardBlock(title:String,content:@Composable ColumnScope.()->Unit){
    Surface(color=Color.White,shape=Shape,border=BorderStroke(1.dp,Line)){
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink);content()
        }
    }
}
@Composable private fun SectionTitle(title:String,action:String="",click:()->Unit={}){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        if(action.isNotBlank())TextButton(onClick=click,modifier=Modifier.heightIn(min=48.dp)){Text(action,fontSize=13.sp)}
    }
}
@Composable private fun Primary(label:String,click:()->Unit,enabled:Boolean=true,tag:String?=null){
    Button(onClick=click,enabled=enabled,shape=Shape,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).then(if(tag==null)Modifier else Modifier.testTag(tag))){Text(label,fontSize=16.sp,fontWeight=FontWeight.Bold)}
}
@Composable private fun QuickTile(label:String,glyph:String,modifier:Modifier=Modifier,enabled:Boolean=true,tag:String?=null,click:()->Unit){
    Surface(onClick=click,enabled=enabled,color=Color.White,shape=Shape,border=BorderStroke(1.dp,Line),modifier=modifier.then(if(tag==null)Modifier else Modifier.testTag(tag))){
        Column(Modifier.fillMaxWidth().heightIn(min=94.dp).padding(horizontal=8.dp,vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){
            FamilyGlyph(glyph,if(enabled)Accent else Muted,Modifier.size(28.dp));Text(label,color=if(enabled)Ink else Muted,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
        }
    }
}
@Composable private fun VehicleShowcase(m:PhoneModel,owner:Boolean){
    Surface(color=Color.White,shape=Shape,modifier=Modifier.fillMaxWidth().testTag("vehicle-showcase")){
        Column(Modifier.padding(horizontal=18.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(if(owner)m.vehicleName.ifBlank{"나의 자동차"} else "함께 타는 자동차",fontSize=25.sp,fontWeight=FontWeight.Bold)
                    Text(if(owner)listOf(m.vehicleModel,m.vehiclePlate).filter{it.isNotBlank()}.joinToString(" · ").ifBlank{"차량을 등록해 주세요"}else "동승자를 위한 WithME 가족",fontSize=13.sp,color=Muted)
                }
                Surface(color=Accent,shape=RoundedCornerShape(4.dp)){Text(if(owner)"내 차" else "동승자",Modifier.padding(horizontal=10.dp,vertical=6.dp),color=Color.White,fontSize=11.sp)}
            }
            CarArtwork(Modifier.fillMaxWidth().height(if(owner)196.dp else 120.dp))
            Text("차량 이미지는 예시입니다",fontSize=10.sp,color=Muted,modifier=Modifier.align(Alignment.End))
            HorizontalDivider(color=Line)
            Row(Modifier.fillMaxWidth().padding(top=12.dp)){
                if(owner){
                    Reading("배터리",m.state.reading("battery"),"%",Modifier.weight(1f))
                    Reading("실내 온도",m.state.reading("temperature"),"°C",Modifier.weight(1f))
                }else{
                    Text("배터리  "+m.state.reading("battery")+"%",Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                    Text("실내 측정  "+m.state.reading("temperature")+"°C",Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                }
            }
            if(!m.stale)m.climateTarget(m.preferredClimateArea)?.let{Text(m.climateLabel(m.preferredClimateArea)+" 설정  %.1f°C".format(it),color=Accent,fontWeight=FontWeight.SemiBold)}
            Text(if(m.stale)"연결 후 차량 상태를 확인할 수 있어요" else m.state.optString("closures","상태 미확인"),color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
        }
    }
}
@Composable internal fun ClimatePowerControls(m:PhoneModel,area:String){
    if(!m.stale&&!m.state.optBoolean("parked"))Text(if(BuildConfig.PRODUCT=="navigation")"주행 중 · P단 정차 후 조절할 수 있어요" else "주행 중에도 승인받은 공조 기능은 조절할 수 있어요.",color=Accent,modifier=Modifier.testTag("control-driving-reason"))
    val known=!m.state.isNull("climatePower")
    val on=m.state.optBoolean("climatePower")
    Text(if(!known)"공조 전원 확인 중" else if(on)"공조 켜짐" else "공조 꺼짐",fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("climate-power-state"))
    Primary(if(on)"공조 끄기" else "공조 켜기",{
        m.toggleClimatePower(area)
    },m.canClimate&&m.hasScope("climatePower")&&known&&m.climateTarget(area)!=null&&m.state.optBoolean("climatePowerSupported"),"climate-power-toggle")
    if(BuildConfig.PRODUCT!="navigation")Text("공조 권한을 한 번 승인받으면 ${m.grantLifetime} 바로 조절합니다.",color=Muted,fontSize=13.sp)
}
@Composable private fun RequestGrid(m:PhoneModel){
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            QuickTile("시원하게","cool",Modifier.weight(1f),m.canClimate,"send-cool"){m.adjustClimate(-0.5)}
            QuickTile("따뜻하게","warm",Modifier.weight(1f),m.canClimate,"send-warm"){m.adjustClimate(0.5)}
        }
        Text("현재 설정에서 0.5°C 낮추기·높이기 · 공조 권한 필요",color=Muted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            QuickTile("음악 줄이기","volume",Modifier.weight(1f),m.canRequest,"send-quiet"){m.request("note","음악 소리를 줄여 주세요")}
            QuickTile("휴식 요청","rest",Modifier.weight(1f),m.canRequest,"send-rest"){m.request("note","다음 안전한 장소에서 쉬고 싶어요")}
        }
    }
}
@Composable private fun Reading(label:String,value:String,unit:String,modifier:Modifier=Modifier,dark:Boolean=false){
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(label,color=if(dark)Color(0xFFBDC3CE) else Muted,fontSize=13.sp)
        Row(verticalAlignment=Alignment.Bottom){Text(value,fontSize=34.sp,fontWeight=FontWeight.Bold,color=if(dark)Color.White else Ink);Text(" $unit",color=if(dark)Color(0xFFBDC3CE) else Muted,fontSize=15.sp,modifier=Modifier.padding(bottom=5.dp))}
        if(value=="—")Text("미확인",color=if(dark)Color(0xFFBDC3CE) else Muted,fontSize=12.sp)
    }
}
@Composable private fun ArrivalCard(m:PhoneModel){
    CardBlock(when{m.stale->"이동 정보";m.state.optBoolean("arrived")->"목적지 도착";m.state.optBoolean("running")->"함께 이동 중";else->"이동 준비"}){
        Row(Modifier.fillMaxWidth()){
            Reading("남은 시간",m.state.number("remainingSeconds")?.let{kotlin.math.ceil(it/60).toInt().toString()}?:"—","분",Modifier.weight(1f))
            Reading("남은 거리",m.state.number("remainingMeters")?.let{"%.1f".format(it/1000)}?:"—","km",Modifier.weight(1f))
        }
        HorizontalDivider(color=Line)
        val remaining=m.state.number("remainingMeters")
        val total=m.state.number("routeTotalMeters")
        if(!m.stale&&remaining!=null&&total!=null&&total>0){
            val progress=(1-remaining/total).coerceIn(0.0,1.0).toFloat()
            LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(10.dp).testTag("arrival-progress"))
            Text(if(m.state.optBoolean("arrived"))"도착했어요!" else "목적지까지 ${(progress*100).toInt()}% 왔어요",color=Accent)
        }
        Text(m.state.number("breakRemainingMinutes")?.let{"다음 휴식 제안까지 주행 약 "+it.toInt()+"분"}?:"차량이 확인한 이동 정보를 표시합니다.",color=Muted,fontSize=13.sp)
        m.state.number("motionLoad")?.let{Text("멀미 케어 참고지수 ${it.toInt()}/100 · 최근 3분 급조작 빈도 기준이며 실제 멀미를 진단하지 않아요.",color=Muted,fontSize=12.sp)}
        m.state.optJSONObject("routeSummary")?.let{route->if(route.optString("change").isNotBlank())Text(route.optString("change"));route.optJSONObject("waypoint")?.let{Text("경유지 · "+if(it.optBoolean("visited"))"방문 확인" else "방문 예정")}}
        m.lastVehicleAt?.let{Text("마지막 차량 데이터 갱신 · "+java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.KOREA).format(java.util.Date(it)),color=Muted,fontSize=12.sp)}
    }
}
@Composable private fun TimerSummary(m:PhoneModel,click:()->Unit){
    val seconds=m.state.number("watchRemainingSeconds")
    val summary=when{m.stale->"차량 연결 후 확인";m.state.optBoolean("watching")->if(seconds==null)"타이머 갱신 대기" else if(seconds<=0)"직접 확인할 시간" else "직접 확인까지 "+seconds.toInt()/60+"분 "+seconds.toInt()%60+"초";else->"차량에서 타이머를 시작하세요"}
    RowLink("뒷좌석 · 반려동물 확인",summary,"clock",click)
    Text("아이·반려동물은 보호자가 직접 확인하세요. 알림이 없어도 안전을 보장하지 않습니다.",color=Muted,fontSize=12.sp)
}
@Composable private fun RowLink(title:String,subtitle:String,glyph:String,click:()->Unit){
    Surface(onClick=click,color=Color.White,shape=Shape,border=BorderStroke(1.dp,Line)){
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            FamilyGlyph(glyph,Accent);Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=12.sp)}
            Spacer(Modifier.width(8.dp));FamilyGlyph("arrow",Muted,Modifier.size(18.dp))
        }
    }
}
@Composable private fun RequestCards(m:PhoneModel,navigation:Boolean,management:Boolean=false){
    val list=(if(management)m.managementRequests else m.requests).reversed()
    if(list.isEmpty())Text("요청하면 처리 상태가 여기에 표시됩니다.",color=Muted)
    list.forEach{r->CardBlock(r.getString("title")){
        if(management&&m.isOwner&&m.canDeleteResult(r))TextButton(onClick={m.deleteManagementResult(r.getString("id"))},modifier=Modifier.testTag("delete-result-"+r.getString("id"))){Text("지우기")}
        Text(when(r.getString("status")){"pending"->"정차 후 확인 대기";"executing"->"차량에서 확인 중";"confirmed"->if(r.optString("kind")=="climate")"차량 설정 확인" else "확인 완료";"rejected"->"보류 또는 만료";else->"차량에서 직접 확인 필요"},color=Accent,fontWeight=FontWeight.Bold)
        if(r.optString("message").isNotBlank())Text(r.optString("message"),color=Muted,fontSize=13.sp)
        if(navigation&&r.optString("kind") in listOf("destination","waypoint")){Text("함께 가고 싶어요 "+r.optInt("votes")+"표");if(r.getString("status")=="pending")Primary("나도 좋아요",{m.vote(r.getString("id"))},m.canPlan)}
    }}
}
private fun JSONObject.number(key:String):Double?=if(has(key)&&!isNull(key))optDouble(key).takeIf{it.isFinite()} else null
private fun JSONObject.reading(key:String):String=number(key)?.let{if(it%1.0==0.0)it.toInt().toString()else "%.1f".format(it)}?:"—"

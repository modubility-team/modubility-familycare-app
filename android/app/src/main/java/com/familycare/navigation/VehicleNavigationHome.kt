@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.familycare.ui.*

private val MapInk=Color(0xFF10151C)
private val MapLine=Color(0xFFD3D8DE)
private val MapBlue=Color(0xFF0079D5)

/** Navigation occupies the platform's content region; system climate/status bars stay native. */
@Composable internal fun VehicleNavigationHome(care:CareRuntime,choose:(String)->Unit,join:()->Unit,start:()->Unit,openNavigation:()->Unit,resize:()->Unit,onLocationPermission:()->Unit={},readOnly:Boolean=false){
    @Suppress("UNUSED_VARIABLE") val revision=care.revision
    val camera=remember{MapViewportController()}
    val nav=care.navigation
    val trafficFresh=nav.route?.let{System.currentTimeMillis()-it.generatedAt in 0..600_000}==true
    val routePoints=remember(nav.route,trafficFresh){nav.route?.let{r->r.points.mapIndexed{i,p->MapRoutePoint(p.latitude,p.longitude,if(trafficFresh)r.trafficAt(i)else null)}}?:emptyList()}
    val position=nav.displayPosition
    var selecting by remember{mutableStateOf(false)}
    var latitude by remember{mutableStateOf<Double?>(null)}
    var longitude by remember{mutableStateOf<Double?>(null)}
    var name by remember{mutableStateOf("")}
    var photoList by remember{mutableStateOf(false)}
    var photoId by rememberSaveable{mutableStateOf("")}
    var help by remember{mutableStateOf(false)}
    var familyCompare by remember{mutableStateOf(false)}
    var menu by rememberSaveable{mutableStateOf(false)}
    var placeSaved by remember{mutableStateOf("")}
    var routeOptions by remember{mutableStateOf(false)}
    LaunchedEffect(care.restSearchRequested){if(care.restSearchRequested&&!readOnly){selecting=true;care.restSearchRequested=false}}
    LaunchedEffect(readOnly){if(readOnly){familyCompare=false;selecting=false;photoId="";photoList=false;help=false;menu=false}}
    LaunchedEffect(nav.route){if(nav.active)camera.guidance(true)else if(routePoints.isNotEmpty())camera.overview(routePoints)}
    LaunchedEffect(nav.active){camera.guidance(nav.active)}
    val photos=care.relay.memories
    // Big photos (map cards and the photo viewer) only while parked in P; leaving P closes the viewer.
    val parked=care.parked
    LaunchedEffect(parked){if(!parked)photoId=""}
    // Large photo cards use the full photo once the car has fetched it; until then the small preview.
    val memoryPins=photos.mapNotNull(MemoryPhotos::pin).map{p->care.relay.memoryImages[p.id]?.let{p.copy(image=it)}?:p}
    LaunchedEffect(photos){if(photoId.isNotBlank()&&photos.none{it.optString("id")==photoId})photoId=""}
    val pending=care.relay.requests.count{it.status=="pending"}
    val alerts=care.engine.alerts(care.now())
    BackHandler(selecting||photoId.isNotBlank()){selecting=false;photoId=""}
    if(familyCompare&&!readOnly)FamilyRouteDialog(care){familyCompare=false;selecting=false}
    MaterialTheme(colorScheme=lightColorScheme(primary=MapBlue,onPrimary=Color.White,surface=Color.White,onSurface=MapInk,outline=MapLine)){
        BoxWithConstraints(Modifier.fillMaxSize().testTag("vehicle-navigation")){
            val compact=maxWidth<600.dp
            val large=maxWidth>1300.dp
            val edge=if(compact)12.dp else 24.dp
            val button=if(large)100.dp else if(compact)58.dp else 72.dp
            val panel=if(compact)maxWidth-edge*2 else if(large)600.dp else 420.dp
            // Leave the native SDK's bottom logo and attribution unobscured.
            val bottom=if(compact)72.dp else 80.dp
            val cockpit=nav.active&&!selecting
            val dense=compact||maxHeight<620.dp
            val headerHeight=if(compact)164.dp else 0.dp
            val footerHeight=if(compact)142.dp else 118.dp
            val sideWidth=if(compact)0.dp else (maxWidth*.32f).coerceIn(340.dp,440.dp)
            val searchHeight=(maxHeight-edge*2).coerceAtLeast(160.dp)
            PlaceMap(latitude,longitude,if(selecting&&!readOnly)({lat,lon->latitude=lat;longitude=lon;name="지도에서 선택한 곳";photoId=""})else null,
                Modifier.fillMaxSize().padding(top=if(cockpit)headerHeight else 0.dp,bottom=if(cockpit)footerHeight else 0.dp,end=0.dp),memories=memoryPins,onMemory={if(parked){photoId=it;selecting=false}},
                controller=camera,showControls=false,showStatus=false,position=position,route=routePoints,interactive=!readOnly,
                // Big photo cards whenever parked in P (also with guidance on screen); small round photos otherwise or while picking a place.
                memoryStyle=MemoryPinStyle.forCar(readOnly,parked,selecting))
            if(care.relay.connected&&care.relay.situation.optString("mode").isNotBlank()&&!selecting&&!cockpit)Surface(color=Color.White,shape=RoundedCornerShape(12.dp),shadowElevation=2.dp,border=BorderStroke(1.dp,MapLine),modifier=Modifier.align(Alignment.TopEnd).padding(edge).widthIn(max=320.dp)){
                val situation=care.relay.situation
                Row(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    FamilyGlyph(if(situation.optBoolean("restDue"))"rest" else "people",MapInk,Modifier.size(24.dp))
                    Column{
                        Text(situation.optString("label"),fontWeight=FontWeight.Bold,fontSize=16.sp,maxLines=1)
                        Text(com.familycare.ui.RestStopText.car(situation)+" · "+(if(situation.optBoolean("caregiverOnline"))"보호자 연결" else "보호자 끊김"),fontSize=13.sp,maxLines=2)
                    }
                }
            }
            if(!cockpit&&!selecting&&(care.musicStatus.contains("재생 중")||care.musicStatus.contains("일시정지")))Surface(color=Color.White,modifier=Modifier.align(Alignment.TopCenter).padding(top=edge)){
                Row(verticalAlignment=Alignment.CenterVertically){Text(care.musicStatus,modifier=Modifier.padding(10.dp));TextButton(onClick={care.musicCommand(if(care.musicStatus.contains("일시정지"))"resume" else "pause")}){Text(if(care.musicStatus.contains("일시정지"))"재생" else "일시정지")};TextButton(onClick={care.musicCommand("stop")}){Text("종료")}}
            }
            if(!readOnly&&!selecting&&!nav.active)Row(Modifier.align(Alignment.TopStart).padding(edge).testTag("home-actions"),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                MapButton(if(pending>0)"WithME 가족  $pending" else "WithME 가족","people",Modifier.testTag("home-requests"),button){choose("요청")}
                MapButton("연결","qr",Modifier.testTag("home-connect"),button,compact=true,click=join)
            }
            if(!compact&&!readOnly&&!nav.active)Column(Modifier.align(Alignment.TopEnd).padding(edge),verticalArrangement=Arrangement.spacedBy(10.dp)){
                MapButton(if(camera.headingUp)"진행" else "북쪽","route",Modifier.width(button).testTag("map-angle"),button,stacked=true){camera.toggleOrientation()}
                MapButton("+",null,Modifier.width(button).testTag("map-zoom-in"),button){camera.zoomIn()}
                MapButton("−",null,Modifier.width(button).testTag("map-zoom-out"),button){camera.zoomOut()}
            }
            if(!readOnly&&(!nav.active||selecting))Column(Modifier.align(if(selecting)Alignment.TopStart else Alignment.BottomStart).padding(start=edge,top=if(selecting)edge else 0.dp,bottom=if(selecting)16.dp else bottom).imePadding().width(panel),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(selecting){
                    Surface(color=Color.White,shadowElevation=8.dp,border=BorderStroke(1.dp,MapLine),modifier=Modifier.testTag("destination-picker")){
                        Column(Modifier.fillMaxWidth().heightIn(max=searchHeight).verticalScroll(rememberScrollState()).padding(if(large)26.dp else 18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Text("목적지 선택",Modifier.weight(1f),fontSize=if(large)28.sp else 21.sp,fontWeight=FontWeight.Bold)
                                TextButton(onClick={selecting=false},modifier=Modifier.testTag("close-destination-picker")){Text("닫기")}
                            }
                            PlaceSearch(nav.fix?.point?.latitude?:MapPlaces.LATITUDE,nav.fix?.point?.longitude?:MapPlaces.LONGITUDE,
                                gateway=care.relay.mapGateway(),route=nav.searchRoute(),initialFacility=if(nav.route!=null)"휴게소" else ""){p->latitude=p.latitude;longitude=p.longitude;name=p.name}
                            val members=care.engine.profile.members
                            Surface(color=SoftFill,shape=RoundedCornerShape(50)){
                                Row(Modifier.padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    FamilyGlyph(if(Passenger.PET in members)"pet" else "people",MapInk,Modifier.size(18.dp))
                                    Text(when{Passenger.CHILD in members->"가족 추천 · 화장실·수유실";Passenger.PET in members->"가족 추천 · 반려견 쉼터";Passenger.ELDER in members->"가족 추천 · 무장애 화장실";else->"추천 · 휴게소·화장실"},fontSize=15.sp,color=MapInk)
                                }
                            }
                            Text("또는 지도를 눌러 선택하세요",fontSize=if(large)19.sp else 14.sp,color=Color(0xFF646D77))
                            OutlinedTextField(name,{name=it.take(120)},label={Text("선택한 장소 이름")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("map-place-name"))
                            if(MapPlaces.valid(latitude,longitude)){
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    listOf("집" to "home","회사" to "car","즐겨찾기 1" to "save","즐겨찾기 2" to "save","즐겨찾기 3" to "save").forEach{(slot,icon)->
                                        AssistChip(onClick={nav.places.save(slot,GeoPoint(latitude!!,longitude!!));placeSaved="$slot 등록 완료"},label={Text(slot.replace("즐겨찾기 ","★ "))},
                                            leadingIcon={FamilyGlyph(icon,MapInk,Modifier.size(18.dp))},modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription="$slot 등록"})
                                    }
                                }
                                if(placeSaved.isNotBlank())InfoLine("check",placeSaved,MapInk)
                            }
                            Surface(onClick={routeOptions=!routeOptions},color=SoftFill,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().testTag("route-options")){
                                Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    FamilyGlyph("route",MapBlue,Modifier.size(22.dp))
                                    Text("경로 옵션 · "+if(nav.useTmap)"TMAP · ${nav.option.label}" else "공개 OSRM",Modifier.weight(1f),fontSize=16.sp,color=MapInk,maxLines=1)
                                    Text(if(routeOptions)"접기" else "변경",fontSize=15.sp,color=MapBlue)
                                }
                            }
                            if(routeOptions){
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    FilterChip(nav.useTmap,{nav.selectProvider(true)},label={Text("TMAP 길찾기")})
                                    FilterChip(!nav.useTmap,{nav.selectProvider(false)},label={Text("공개 OSRM")})
                                }
                                if(nav.useTmap)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    RouteOption.entries.forEach{option->FilterChip(nav.option==option,{nav.setOption(option)},label={Text(option.label)})}
                                }
                            }
                            InfoLine("lock",if(nav.useTmap)"경로 검색 시 위치·목적지를 TMAP에 전송 · 검색 시점 교통 반영" else "경로 검색 시 위치·목적지를 OSRM에 전송 · 실시간 교통 미반영",Color(0xFF646D77),13.sp)
                            if(!nav.gps.permitted)Button(onClick=onLocationPermission,modifier=Modifier.testTag("location-permission")){Text("정확한 위치 허용")}
                            Button(onClick={nav.plan(name.trim(),GeoPoint(latitude!!,longitude!!));if(nav.loading)selecting=false},enabled=MapPlaces.valid(latitude,longitude)&&name.isNotBlank()&&!nav.loading&&nav.locationUsable,
                                shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().heightIn(min=if(large)82.dp else 60.dp).testTag("route-search")){
                                FamilyGlyph("route",Color.White,Modifier.size(if(large)30.dp else 24.dp));Spacer(Modifier.width(12.dp))
                                Text(if(nav.loading)"경로 찾는 중…" else "경로 찾기",fontSize=if(large)25.sp else 19.sp)
                            }
                            OutlinedButton(onClick={nav.compareFamily(name.trim(),GeoPoint(latitude!!,longitude!!));familyCompare=true},enabled=care.parked&&nav.useTmap&&MapPlaces.valid(latitude,longitude)&&name.isNotBlank()&&!nav.loading&&nav.locationUsable,
                                shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("compare-family-routes")){
                                FamilyGlyph("people",MapInk,Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text("가족 기준으로 경로 비교",color=MapInk)
                            }
                            if(nav.route!=null)OutlinedButton(onClick={nav.planWaypoint(GeoPoint(latitude!!,longitude!!)){_,_->};if(nav.loading)selecting=false},enabled=MapPlaces.valid(latitude,longitude)&&!nav.loading&&nav.locationUsable,
                                shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){
                                FamilyGlyph("rest",MapInk,Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text("쉬어 갈 경유지로 반영",color=MapInk)
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                ToolTile("저장·최근","save",Modifier.weight(1f)){menu=true}
                                ToolTile("기준 위치","pin",Modifier.weight(1f).testTag("map-sample")){latitude=MapPlaces.LATITUDE;longitude=MapPlaces.LONGITUDE;name="42dot"}
                                ToolTile("차량 검색","search",Modifier.weight(1f).testTag("open-system-navigation"),onClick=openNavigation)
                                ToolTile("기본 내비로","arrow",Modifier.weight(1f).testTag("map-send-route"),enabled=MapPlaces.valid(latitude,longitude)&&name.isNotBlank()&&!care.mapRequestPending){care.navigation.pause();care.navigateTo(name.trim(),latitude!!,longitude!!)}
                            }
                        }
                    }
                }
                if(!selecting)Surface(onClick={selecting=!selecting},color=MapInk,contentColor=Color.White,modifier=Modifier.fillMaxWidth().height(if(large)112.dp else 76.dp).testTag("navigation-search")){
                    Row(Modifier.padding(horizontal=if(large)28.dp else 20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)){
                        FamilyGlyph("search",Color.White,Modifier.size(if(large)40.dp else 29.dp))
                        Text(if(name.isNotBlank())name else "어디로 갈까요?",fontSize=if(large)30.sp else 22.sp,maxLines=1)
                    }
                }
                if(!selecting)Surface(color=Color.White,border=BorderStroke(1.dp,MapLine),modifier=Modifier.fillMaxWidth()){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                        listOf(Triple("동승자","people","가족"),Triple("기록","pin","추억"),Triple("차량","car","차량"),Triple("이동","play",if(care.engine.running)"이동 종료" else "이동 시작")).forEach{(tab,icon,label)->
                            TextButton(onClick={if(tab=="이동"){if(care.engine.running)care.endTrip()else start()}else choose(tab)},modifier=Modifier.weight(1f).height(if(large)116.dp else 80.dp).testTag(if(tab=="이동")"home-trip" else "tab-$tab")){
                                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    FamilyGlyph(if(tab=="이동"&&care.engine.running)"stop" else icon,MapInk,Modifier.size(if(large)38.dp else 26.dp))
                                    Text(label,color=MapInk,fontSize=if(large)22.sp else 14.sp,maxLines=1)
                                }
                            }
                        }
                    }
                }
            }
            if(!compact&&!readOnly&&!nav.active)Column(Modifier.align(Alignment.BottomEnd).padding(end=edge,bottom=bottom),verticalArrangement=Arrangement.spacedBy(1.dp)){
                if(photos.isNotEmpty())MapButton("사진 ${photos.size}장","pin",Modifier.width(button+24.dp).testTag("navigation-photos"),button,stacked=true){photoList=true}
                MapButton(if(care.audioEnabled)"알림 켜짐" else "알림 꺼짐","volume",Modifier.width(button+24.dp).testTag("navigation-sound"),button,stacked=true){care.toggleDriverAudio()}
                MapButton("설정","car",Modifier.width(button+24.dp).testTag("navigation-settings"),button,stacked=true){choose("차량")}
            }
            if(!compact&&!readOnly&&!nav.active)MapButton(if(nav.gps.permitted)"내 위치" else "위치 허용","pin",Modifier.align(Alignment.BottomCenter).padding(bottom=bottom).testTag("map-reset"),button){if(nav.gps.permitted)camera.follow()else onLocationPermission()}
            if(!readOnly&&!nav.active)Row(Modifier.align(if(compact)Alignment.TopEnd else Alignment.TopCenter).padding(top=if(compact)88.dp else edge),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                MapButton("화면","expand",Modifier.testTag("resize"),if(compact)48.dp else button,compact=true,click=resize)
                MapButton("경로·설정",null,Modifier.testTag("navigation-menu"),if(compact)48.dp else button,compact=true){menu=true}
            }
            val popup=driverPopup(alerts,if(care.relay.connected)care.relay.visibleRequests.filterNot{it.id in care.relay.careRoutedNoteIds} else emptyList(),care.driverNotice)
            if(!selecting&&popup!=null)Surface(color=if(popup.urgent)Color(0xFFFFF0D2)else Color.White,
                shape=RoundedCornerShape(12.dp),shadowElevation=6.dp,border=BorderStroke(1.dp,MapLine),
                modifier=Modifier.zIndex(2f).align(Alignment.BottomEnd).padding(end=edge,start=if(compact)edge else maxWidth*0.58f,bottom=if(cockpit)footerHeight+20.dp else if(readOnly)bottom else bottom+button*2+20.dp).widthIn(max=360.dp).testTag("driving-notification")){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(care.engine.ux.shortText(popup.title),color=MapInk,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=2)
                    if(popup.pending>0)Text("확인 대기 ${popup.pending}건 · 정차 후 확인",color=MapInk,fontSize=14.sp,maxLines=1)
                    if(!readOnly){
                        if(popup.pending>0)TextButton(onClick={choose("요청")}){Text("요청 확인")}
                        if(popup.code=="rear")TextButton(onClick=care::confirmRear){Text("직접 확인했어요")}
                        if(popup.code=="break")TextButton(onClick=care::takeBreak){Text("안전한 곳에서 휴식했어요")}
                        if(popup.code in listOf("break","motion"))TextButton(onClick={selecting=true}){Text("쉬어 갈 장소 찾기")}
                    }
                }
            }
            if(care.remainingSeconds!=null&&nav.route==null&&!selecting)Surface(color=MapInk,contentColor=Color.White,modifier=Modifier.align(Alignment.CenterEnd).padding(end=edge).widthIn(max=280.dp)){
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(if(care.arrived)"목적지 도착" else "안내 중",fontSize=20.sp)
                    Text("${((care.remainingSeconds!!+59)/60)}분",fontSize=38.sp,fontWeight=FontWeight.Bold)
                    care.remainingMeters?.let{Text("%.1f km".format(it/1000.0),fontSize=24.sp)}
                    TextButton(onClick=openNavigation){Text("길안내 화면",color=Color.White)}
                }
            }
            if(!selecting&&!nav.active)Surface(color=Color.White,modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=if(readOnly)42.dp else if(compact)235.dp else bottom+button+12.dp).widthIn(max=480.dp)){
                Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(nav.locationStatus,fontSize=if(large)19.sp else 13.sp,color=if(nav.locationUsable)MapInk else Color(0xFF9E4A10),modifier=Modifier.testTag("gps-status"))
                    if(nav.route==null&&!nav.loading&&nav.message.isNotBlank())Text(nav.message,fontSize=12.sp,color=MapInk)
                    if(compact&&!readOnly)TextButton(onClick={if(nav.gps.permitted)camera.follow()else onLocationPermission()}){Text(if(nav.gps.permitted)"내 위치" else "위치 허용")}
                }
            }
            if(!selecting&&!nav.active&&(nav.route!=null||nav.loading))NavigationGuidancePanel(nav,readOnly,compact,
                Modifier.align(Alignment.TopStart).padding(start=edge,top=if(readOnly)edge else button+edge+14.dp,end=edge)
                    .width(if(compact)maxWidth-edge*2 else if(large)500.dp else 430.dp),
                overview={camera.overview(routePoints)})
            if(cockpit){
                val status=when{!nav.locationUsable->nav.locationStatus;nav.loading||nav.outside->nav.message;else->null}
                if(compact)NavigationDrivingHeader(nav.nextTurn,nav.followingTurn,(nav.nextTurn?.along?.minus(nav.progress)?:0.0).coerceAtLeast(0.0),
                    status,true,nav.voiceEnabled,readOnly,{nav.toggleVoice()},Modifier.align(Alignment.TopCenter).fillMaxWidth().height(headerHeight))
                val seconds=(nav.remainingSeconds?:0.0).coerceAtLeast(0.0)
                val arrival=java.time.Instant.ofEpochMilli(System.currentTimeMillis()+(seconds*1000).toLong()).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                if(compact)NavigationDrivingFooter(nav.route?.destination.orEmpty(),if(nav.locationUsable)arrival else "--:--",
                    kotlin.math.ceil(seconds/60).toInt(),nav.remainingMeters?:0.0,(nav.progress/(nav.route?.meters?:1.0).coerceAtLeast(1.0)).toFloat(),
                    if(!nav.locationUsable)"GPS 수신 대기" else if(nav.route?.provider=="TMAP")"검색 시점 교통 반영" else "교통 정보 미반영",dense,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(footerHeight))
                else NavigationOemFooter(nav,false,Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(footerHeight))
                val speed=care.engine.value(Signal.SPEED,care.now())?:nav.fix?.speed?.toDouble()?.takeIf{nav.locationUsable}
                NavigationSpeed(speed,nav.locationUsable,dense,Modifier.align(Alignment.TopStart).padding(start=edge,top=headerHeight+20.dp))
                if(!compact)Surface(color=Color.White,shadowElevation=4.dp,modifier=Modifier.align(Alignment.TopEnd).padding(top=12.dp,end=12.dp).width(sideWidth).heightIn(max=maxHeight-footerHeight-120.dp).testTag("guidance-floating-card")){Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    NavigationOemGuidance(nav,false,Modifier.fillMaxWidth())
                    Column(Modifier.padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text(if(nav.route?.provider=="TMAP")"${nav.route?.option?.label} · 교통 ${if(trafficFresh)"조회 반영" else "재탐색으로 갱신"}" else "OSRM · 교통 미반영",fontSize=18.sp)
                        if(trafficFresh&&nav.route?.traffic?.isNotEmpty()==true)Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            Text("● 원활",color=Color(0xFF009B7B),fontSize=16.sp);Text("● 서행",color=Color(0xFFAF7211),fontSize=16.sp);Text("● 정체",color=Color(0xFFDC3D3D),fontSize=16.sp)
                        }
                        val situation=care.relay.situation
                        if(situation.optString("mode").isNotBlank()){
                            Text(situation.optString("label")+" · "+com.familycare.ui.RestStopText.car(situation),fontSize=18.sp)
                            Text(if(situation.optBoolean("caregiverOnline"))"일반 요청 → "+situation.optString("caregiverName") else "보호자 연결 끊김 · 운전자에게 알림",fontSize=16.sp)
                        }
                        if(care.musicStatus.contains("재생 중")||care.musicStatus.contains("일시정지")){
                            Text(care.musicStatus,fontSize=18.sp)
                            if(!readOnly)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                MapButton(if(care.musicStatus.contains("일시정지"))"음악 재생" else "음악 일시정지",null,Modifier.weight(1f),56.dp){care.musicCommand(if(care.musicStatus.contains("일시정지"))"resume" else "pause")}
                                MapButton("공유 종료",null,Modifier.weight(1f),56.dp){care.musicCommand("stop")}
                            }
                        }
                        if(!readOnly){
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                MapButton("경로·설정",null,Modifier.weight(1f).testTag("navigation-menu"),56.dp){menu=true}
                                MapButton("안내 종료",null,Modifier.weight(1f).testTag("route-stop"),56.dp){nav.cancel()}
                            }
                        }
                    }
                }
                }
                if(!readOnly)Column(Modifier.align(Alignment.BottomStart).padding(start=edge,bottom=footerHeight+48.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    MapButton(if(camera.following)"내 위치" else "추적 재개","route",Modifier.testTag("guidance-follow"),56.dp){camera.follow()}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        MapButton(if(camera.headingUp)"진행 ↑" else "북쪽 N",null,Modifier.testTag("map-angle"),52.dp){camera.toggleOrientation()}
                        MapButton("+",null,Modifier.testTag("map-zoom-in"),52.dp){camera.zoomIn()}
                        MapButton("−",null,Modifier.testTag("map-zoom-out"),52.dp){camera.zoomOut()}
                    }
                }
            }
        }
        if(photoList&&!readOnly)AlertDialog(onDismissRequest={photoList=false},title={Text("공유한 사진 위치")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("이번 연결에 공유한 사진 전체입니다. 날짜와 관계없이 촬영 위치에 표시해요.")
            photos.forEach{photo->OutlinedButton(onClick={photoList=false;camera.showPoint(photo.getDouble("latitude"),photo.getDouble("longitude"))},modifier=Modifier.fillMaxWidth().testTag("locate-photo-"+photo.optString("id"))){
                val bitmap=remember(photo.optString("thumbnail")){MemoryPhotos.bitmap(photo.optString("thumbnail"))}
                bitmap?.let{Image(it.asImageBitmap(),null,Modifier.size(96.dp));Spacer(Modifier.width(14.dp))}
                Column(Modifier.weight(1f)){Text(photo.optString("title"));Text(photo.optString("captured"));Text("지도에서 보기")}
            }}
        }},confirmButton={TextButton(onClick={photoList=false}){Text("닫기")}})
        if(photoId.isNotBlank()&&!readOnly&&parked)MemoryPhotoViewer(photos,care.relay.memoryImages,photoId,{photoId=it},care.relay::preferMemoryImage,
            {p->photoId="";camera.showPoint(p.getDouble("latitude"),p.getDouble("longitude"))},{photoId="";choose("기록")},{photoId=""})
        if(help)AlertDialog(onDismissRequest={help=false},title={Text("지도와 길안내")},text={Text("위치 허용 → GPS 수신 → 지도에서 목적지 선택 → 경로 찾기 → 안내 시작 순서로 사용합니다. GPS가 끊기면 안내를 대기하고, 경로 이탈 시 다시 탐색합니다. 다른 앱으로 나가면 안내가 일시정지됩니다.\n\n선택한 경로 제공자: ${nav.providerLabel}. 검색·이탈 재탐색 시 현재 위치·목적지·경유지가 제공자에 전송되고 서버 로그에 남을 수 있습니다. TMAP 지도는 연결된 기기에서 불러옵니다. 가족 탭에서 위치 기록·가족 공유를 각각 선택할 수 있습니다. TMAP는 검색 시점 교통을 예상 시간에 반영합니다. 차로·단속 안내는 제공하지 않습니다.\nhttps://routing.openstreetmap.de/about.html")},confirmButton={TextButton(onClick={help=false}){Text("확인")}})
        if(menu&&!readOnly)NavigationMenu(nav,camera,{menu=false},{selecting=true},{place->latitude=place.point.latitude;longitude=place.point.longitude;name=place.name;selecting=true})
    }
}

private fun distanceLabel(meters:Double)=if(meters>=1000)"%.1f km".format(meters/1000)else "${(meters/10).toInt()*10} m"

@Composable private fun MapButton(label:String,icon:String?,modifier:Modifier=Modifier,height:Dp=72.dp,compact:Boolean=false,stacked:Boolean=false,click:()->Unit){
    Surface(onClick=click,color=Color.White,contentColor=MapInk,shape=RoundedCornerShape(2.dp),border=BorderStroke(1.dp,MapLine),shadowElevation=2.dp,
        modifier=modifier.heightIn(min=height).semantics{contentDescription=label}){
        if(stacked)Column(Modifier.padding(horizontal=6.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            if(icon!=null)FamilyGlyph(icon,if(icon=="route")MapBlue else MapInk,Modifier.size(if(height>=100.dp)32.dp else 24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label,fontSize=if(height>=100.dp)20.sp else 14.sp,fontWeight=FontWeight.Medium,maxLines=1)
        }else Row(Modifier.padding(horizontal=if(compact)14.dp else 20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
            if(icon!=null){FamilyGlyph(icon,if(icon=="route")MapBlue else MapInk,Modifier.size(if(height>=100.dp)34.dp else 25.dp));if(!compact)Spacer(Modifier.width(10.dp))}
            if(!compact||icon==null)Text(label,fontSize=if(height>=100.dp)26.sp else 18.sp,fontWeight=FontWeight.Medium,maxLines=1)
        }
    }
}

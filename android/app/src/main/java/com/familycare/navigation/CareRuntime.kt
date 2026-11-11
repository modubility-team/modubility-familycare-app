package com.familycare.navigation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

class CareRuntime internal constructor(private val context: Context) {
    companion object {
        fun get(context:Context):CareRuntime=(context.applicationContext as CareApplication).care
    }
    val engine=CareEngine()
    val ignition=IgnitionSession(null)
    val grantLifetime get()=if(ignition.mode=="connection")"이번 차량 연결이 종료될 때까지" else "시동이 꺼질 때까지"
    private val main=Handler(Looper.getMainLooper())
    private val prefs=context.getSharedPreferences("family-care",Context.MODE_PRIVATE)
    val journey=JourneyRecorder()
    var recordJourney=prefs.getBoolean("record-journey",false);private set
    var shareJourney=prefs.getBoolean("share-journey",false);private set
    var journeys=emptyList<JSONObject>();private set
    private var lastAlertCodes=emptySet<String>()
    private var arrivedLogged=false
    var holdUntil=0L;private set
    private var holdBase=23.0
    private var holdLastTarget:Double?=null
    private var holdAt=0L
    private var holdPermission:PassengerRequest?=null
    val holding get()=holdUntil>now()
    fun stopHold(){holdUntil=0;holdLastTarget=null;holdPermission=null;publish()}
    private fun updateHold(){
        if(holdUntil==0L)return
        if(holdPermission?.let{!authorized(it)}==true){notice="공조 보조 권한이 종료됐어요";stopHold();return}
        val reason=ClimateHoldPolicy.stopReason(parked,engine.watching,engine.value(Signal.CLIMATE_POWER,now()),engine.value(Signal.TEMPERATURE,now()),engine.value(Signal.BATTERY,now()),holdUntil-now())
        if(reason!=null||(relay.sharing&&!relay.connected)){notice=reason?:"차량 공유 연결이 끊겨 공조 보조를 종료했어요";stopHold();return}
        if(climatePending||now()-holdAt<30000)return
        val current=engine.value(Signal.CLIMATE_TARGET,now())
        if(current==null||(holdLastTarget!=null&&kotlin.math.abs(current-holdLastTarget!!)>0.05)){notice="수동 설정 변경 또는 설정값 미확인으로 공조 보조를 종료했어요";stopHold();return}
        val target=ClimateHoldPolicy.target(holdBase,engine.value(Signal.TEMPERATURE,now())!!)
        holdAt=now()
        if(kotlin.math.abs(current-target)<0.05){holdLastTarget=current;return}
        applyClimate(target,fromHold=true){status,_->if(status=="confirmed")holdLastTarget=target else stopHold()}
    }
    fun startHold(base:Double,minutes:Int):Boolean{
        if(base !in 20.0..26.0||!base.isFinite()||minutes !in 1..10||!climateSupported||ClimateHoldPolicy.stopReason(parked,engine.watching,engine.value(Signal.CLIMATE_POWER,now()),engine.value(Signal.TEMPERATURE,now()),engine.value(Signal.BATTERY,now()),1)!=null)return false
        holdBase=base;holdUntil=now()+minutes*60000;holdLastTarget=engine.value(Signal.CLIMATE_TARGET,now());holdAt=now()-30000;updateHold();publish();return holding
    }
    private var sensors:VehicleSensors?=null
    private var controls:VehicleControls?=null
    val vehicleControls get()=controls?.snapshot()?:JSONArray()
    private var uxSession:UxSession?=null
    private var navi:NaviBridge?=null
    private var views=0
    private var service=false
    private val driverPolicy=DriverAlertPolicy()
    private var driverAudio:DriverAudio?=null
    private var musicReceiver:PhoneMusicReceiver?=null
    val musicStatus get()=musicReceiver?.status?:"휴대폰 음악 연결 대기"
    val musicFrames get()=musicReceiver?.playedFrames?:0
    fun musicCommand(value:String){musicReceiver?.command(value)}
    var audioEnabled=prefs.getBoolean("driver-audio",true);private set
    var driverNotice="";private set
    private var driverNoticeAt=0L
    val audioStatus get()=driverAudio?.outputStatus?:"이동·동승자 연결 중 짧은 소리로 알려드려요"
    val audioResult get()=driverAudio?.lastResult.orEmpty()
    var revision by mutableStateOf(0);private set
    var notice="";private set
    var history=emptyList<TripSummary>();private set
    var remainingMeters:Int?=null;private set
    var remainingSeconds:Int?=null;private set
    var arrived=false;private set
    var mapRequestPending=false;private set
    var climatePending=false;private set
    val climateSupported get()=sensors?.climateSupported==true
    val climatePowerSupported get()=sensors?.climatePowerSupported==true&&engine.value(Signal.CLIMATE_POWER,now())!=null
    val climateZones get()=sensors?.climateZones()?:JSONArray()
    val parked get()=engine.ux.showDetails&&engine.value(Signal.GEAR,now())==4.0&&engine.value(Signal.SPEED,now())?.let{kotlin.math.abs(it)<0.1}==true
    private var routeAt=0L
    val relay=RelayClient({telemetry()},{familyCriteria();publish();stopServiceIfIdle()})
    private var familyEventId=""
    /** Family criteria sent by a passenger allowed to change the route re-route the car at once (each send once, while fresh). */
    private fun familyCriteria(){
        val e=relay.routeNeedsEvent;val id=e.optString("id")
        if(!FamilyReroute.fresh(id,familyEventId,e.optDouble("age",999.0))){if(id.isNotBlank())familyEventId=id;return}
        familyEventId=id
        val sent=e.optJSONArray("needs")
        if(!e.optBoolean("auto")||sent==null||sent.length()==0)return
        // The whole family's criteria, as shown in the comparison and used by the server to order candidates.
        navigation.familyNeedsArrived(relay.routeNeeds.flatMap{d->val a=d.optJSONArray("needs");if(a==null)emptyList()else (0 until a.length()).map{a.optString(it)}}.distinct())
    }
    var restSearchRequested=false
    var voiceListening=false;private set
    fun voiceCapture(active:Boolean){voiceListening=active;if(active)driverAudio?.stop()}
    fun voiceFeedback(text:String){if(engine.ux.available){driverAudio?.stop();audio().play(text)}}
    val navigation=LiveNavigation(context,{publish()},{text->!voiceListening&&engine.ux.available&&audio().play(text,navigationGuidance=true)},{driverAudio?.stopNavigation()},{relay.mapGateway()},{parked})
    val retained get()=engine.running||engine.watching||relay.sharing||mapRequestPending||climatePending
    init{
        engine.onDrivingEvent={kind,text->journey.event(System.currentTimeMillis(),kind,text)}
        runCatching{val a=JSONArray(prefs.getString("journeys","[]"));journeys=(0 until a.length()).map{a.getJSONObject(it)}}
        loadProfile(0);engine.exitCheckRequired=prefs.getBoolean("rear-check",false)
        runCatching{
            val list=JSONArray(prefs.getString("history","[]"))
            history=(0 until list.length()).map{i->val o=list.getJSONObject(i)
                TripSummary(o.getLong("at"),o.getLong("duration"),o.getLong("driving"),
                    Feedback(o.getInt("accel"),o.getInt("brake"),o.getInt("steer")),o.getString("mode"))}
        }
    }
    fun now()=SystemClock.elapsedRealtime()
    fun publish(){revision++}
    fun speedPermissionChanged(granted:Boolean){
        if(granted){sensors?.refreshSpeedPermission();notice="속도 읽기를 허용했어요"}
        else notice="속도 권한이 없어 주행 시간과 가속·감속 피드백을 확인할 수 없어요"
        publish()
    }
    fun attach(){views++;connect();navigation.foreground()}
    fun detach(){views=(views-1).coerceAtLeast(0);if(views==0)navigation.background();disconnectIfIdle()}
    fun attachService(){service=true;connect()}
    fun detachService(){service=false;disconnectIfIdle()}
    private fun connect(){
        if(sensors!=null)return
        audio()
        if(musicReceiver==null)musicReceiver=PhoneMusicReceiver(context,{relay.mapGateway()},{relay.connected},{publish()})
        engine.ux=UxPolicy()
        try{
            uxSession=UxSession(CarUxGateway(context),{block->main.post{block()}}){engine.ux=it;publish()}.also{it.start()}
            sensors=VehicleSensors(context){s,v,at,error->
                engine.update(s,v,at,error)
                if(s==Signal.IGNITION)ignition.update(v,relay.sharing)
                if(engine.running&&engine.profile.rearCheck&&s==Signal.IGNITION&&v==2.0)engine.exitCheckRequired=true
            }.also{it.start()}
            controls=VehicleControls(context){publish()}.also{it.start()}
            navi=NaviBridge(context,{engine.ux.showDetails}){meters,seconds,reached->
                remainingMeters=meters;remainingSeconds=seconds;arrived=reached;routeAt=now();publish()
            }.also{it.start()}
        }catch(_:Exception){notice="차량 연결 일부를 확인하지 못했어요. 미확인 항목은 직접 확인하세요"}
        catch(_:LinkageError){notice="차량 API가 없는 기기입니다"}
        main.removeCallbacks(tick);main.post(tick)
    }
    private fun disconnectIfIdle(){
        if(views>0||service||mapRequestPending||climatePending)return
        main.removeCallbacks(tick);controls?.close();controls=null;sensors?.close();sensors=null;uxSession?.close();uxSession=null;navi?.close();navi=null
        driverAudio?.close();driverAudio=null;musicReceiver?.close();musicReceiver=null
        engine.ux=UxPolicy();engine.signals.clear();ignition.update(null);publish()
    }
    private var ticks=0
    private val tick=object:Runnable{
        override fun run(){
            engine.tick(now());ticks++
            ignition.configure(sensors?.ignitionSupported)
            ignition.update(engine.value(Signal.IGNITION,now()),relay.sharing)
            updateHold()
            val wall=System.currentTimeMillis()
            journey.sample(navigation.fix,now(),wall,engine.value(Signal.SPEED,now()))
            val activeAlerts=engine.alerts(now())
            activeAlerts.filter{it.code !in lastAlertCodes}.forEach{journey.event(wall,"alert",it.title)}
            lastAlertCodes=activeAlerts.map{it.code}.toSet()
            if(navigation.arrived&&!arrivedLogged){journey.event(wall,"arrival","목적지 도착");arrivedLogged=true}
            if(!navigation.arrived)arrivedLogged=false
            if(!navigation.arrived)engine.destinationChanged()
            if(navigation.arrived)engine.destinationArrived(parked)
            if(ticks%10==0)navi?.refresh()
            if(now()-routeAt>30000){remainingMeters=null;remainingSeconds=null;arrived=false}
            if(prefs.getBoolean("rear-check",false)!=engine.exitCheckRequired)prefs.edit().putBoolean("rear-check",engine.exitCheckRequired).apply()
            updateDriverAudio()
            if(!climatePending&&relay.connected){
                relay.requests.firstOrNull{it.status=="pending"&&((it.kind in listOf("climate","control")&&it.originRole=="owner"&&parked)||it.delegated&&authorized(it))}?.let{decide(it,true)}
            }
            publish();main.postDelayed(this,1000)
        }
    }
    private fun audio()=driverAudio?:DriverAudio(context){publish()}.also{driverAudio=it}
    private fun updateDriverAudio(){
        if(now()-driverNoticeAt>8000)driverNotice=""
        if(!engine.ux.available){driverAudio?.stop();return}
        if(voiceListening||driverAudio?.busy==true)return
        val audible=relay.requests.filterNot{it.delegated||it.id in relay.careRoutedNoteIds&&relay.connected}
        val requests=if(relay.connected)audible else audible.map{it.copy(status="offline")}
        val next=driverPolicy.next(now(),engine.alerts(now()),requests,engine.feedback.total,engine.lastFeedback)?:return
        driverNotice=next.text;driverNoticeAt=now()
        if(!audioEnabled){driverPolicy.delivered(next,now());return}
        if(audio().play(next.text))driverPolicy.delivered(next,now())else driverPolicy.deferred(now())
    }
    fun toggleDriverAudio()=guard{
        audioEnabled=!audioEnabled;prefs.edit().putBoolean("driver-audio",audioEnabled).apply()
        if(!audioEnabled)driverAudio?.stop()
    }
    fun testDriverAudio()=guard{
        if(!audioEnabled){notice="알림 소리를 먼저 켜 주세요";return@guard}
        audio().play("패밀리 케어 알림 시험입니다. 주행 중에도 짧은 안내로 알려드려요.")
    }
    private fun guard(action:()->Unit){if(engine.ux.showDetails){action();publish()}}
    private fun retain():Boolean=try{context.startForegroundService(Intent(context,CareService::class.java));true}
        catch(_:Exception){notice="백그라운드 실행을 시작하지 못했어요. 앱을 다시 열어 주세요";publish();false}
    private fun stopServiceIfIdle(){if(!retained)context.stopService(Intent(context,CareService::class.java))}
    fun togglePassenger(p:Passenger)=guard{if(!engine.running&&!engine.watching)engine.profile=engine.profile.copy(members=if(p in engine.profile.members)engine.profile.members-p else engine.profile.members+p)}
    fun childBand(band:Int)=guard{if(!engine.running)engine.profile=engine.profile.copy(childBand=band.coerceIn(0,2))}
    fun sensitivity(value:Int)=guard{if(!engine.running&&!engine.watching)engine.profile=engine.profile.copy(sensitivity=value.coerceIn(0,2))}
    fun saveProfile(slot:Int)=guard{prefs.edit().putString("profile-$slot",engine.profile.members.joinToString(","){it.name}).putInt("band-$slot",engine.profile.childBand).putInt("sensitivity-$slot",engine.profile.sensitivity).apply();notice="구성 ${slot+1}을 저장했어요"}
    fun loadProfile(slot:Int){
        if(sensors!=null&&(!engine.ux.showDetails||engine.running||engine.watching))return
        engine.profile=FamilyProfile(prefs.getString("profile-$slot","")!!.split(",").mapNotNull{runCatching{Passenger.valueOf(it)}.getOrNull()}.toSet(),prefs.getInt("band-$slot",1),prefs.getInt("sensitivity-$slot",1));publish()
    }
    fun clearLocal()=guard{if(!retained){prefs.edit().clear().putBoolean("rear-check",engine.exitCheckRequired).apply();engine.profile=FamilyProfile();history=emptyList();journey.clear();journeys=emptyList();recordJourney=false;shareJourney=false;notice="프로필과 이동 기록을 삭제했어요"}}
    fun recording(value:Boolean)=guard{if(!engine.running){recordJourney=value;prefs.edit().putBoolean("record-journey",value).apply()}}
    fun sharingJourney(value:Boolean)=guard{shareJourney=value;prefs.edit().putBoolean("share-journey",value).apply()}
    fun clearJourneys()=guard{if(!engine.running){journey.clear();journeys=emptyList();prefs.edit().remove("journeys").apply()}}
    fun startTrip()=guard{if(!engine.running&&retain()){engine.startTrip(now(),System.currentTimeMillis());if(recordJourney)journey.start(System.currentTimeMillis(),engine.profile.mode)else journey.clear();lastAlertCodes=emptySet()}}
    fun endTrip()=guard{engine.endTrip(now())?.let{history=(listOf(it)+history).take(20);saveHistory();if(journey.active){journey.finish(System.currentTimeMillis());journeys=(listOf(journey.json())+journeys).take(20);prefs.edit().putString("journeys",JSONArray(journeys).toString()).apply()}};prefs.edit().putBoolean("rear-check",engine.exitCheckRequired).apply();stopServiceIfIdle()}
    fun confirmRear()=guard{engine.confirmRearCheck();prefs.edit().putBoolean("rear-check",false).apply()}
    fun takeBreak()=guard{engine.takeBreak();journey.event(System.currentTimeMillis(),"rest","직접 휴식 확인");notice="휴식을 기록했어요"}
    fun startWatch()=guard{if(Passenger.PET in engine.profile.members&&retain())engine.startWatch(now())}
    fun stopWatch()=guard{engine.stopWatch();stopServiceIfIdle()}
    fun setTimer(minutes:Int)=guard{if(!engine.watching)engine.checkMinutes=minutes.coerceIn(1,30)}
    fun share()=guard{if(retain())relay.create()}
    fun stopSharing()=guard{relay.end()}
    fun navigateTo(name:String,latitude:Double,longitude:Double)=guard{
        val r=PassengerRequest(java.util.UUID.randomUUID().toString(),"destination",name,latitude,longitude,"pending","",0)
        if(!r.valid()||mapRequestPending||climatePending)return@guard
        val nav=navi
        if(nav==null){notice="내비 연결을 확인해 주세요";return@guard}
        mapRequestPending=true;notice="차량 내비에 목적지를 전달하고 결과를 확인하고 있어요"
        // Pleos can bring its navigation activity forward while the route is calculated.
        // Keep the SDK connection alive only until the verified response or timeout.
        if(!retain()){mapRequestPending=false;return@guard}
        nav.submit(r){_,message->
            mapRequestPending=false;notice=message;publish();stopServiceIfIdle()
        }
    }
    private fun authorized(r:PassengerRequest):Boolean {
        if(!r.delegated||r.originRole!="family"||!relay.connected||!ignition.on||r.authorityEpoch!=ignition.epoch)return false
        val o=runCatching{JSONObject(r.options)}.getOrDefault(JSONObject())
        val scope=when(r.kind){"control"->"control:"+o.optString("control");"destination","waypoint"->"route";"climate","timer","profile","hold","care"->r.kind;else->return false}
        val g=relay.delegations.firstOrNull{it.optString("deviceId")==r.deviceId&&it.optBoolean("active")&&it.optString("epoch")==ignition.epoch}?:return false
        val scopes=g.optJSONArray("scopes")?:return false
        if(!com.familycare.ui.SeatAccess.allows(g,r.kind,o,r.target,climateZones,vehicleControls))return false
        val comfort=r.kind=="care"||r.kind=="climate"||r.kind=="control"&&!o.optString("control").startsWith("door.")&&!o.optString("control").startsWith("window.")
        return comfort||parked
    }
    internal fun canVoiceDecide(r:PassengerRequest):Boolean {
        if(!relay.connected||!engine.ux.available)return false
        if(r.delegated&&r.kind !in setOf("destination","waypoint","pickup"))return authorized(r)
        if(parked)return r.kind!="permission"||ignition.on&&r.authorityEpoch==ignition.epoch
        val speed=engine.value(Signal.SPEED,now())?:return false
        val gear=engine.value(Signal.GEAR,now())?:return false
        val control=runCatching{JSONObject(r.options).optString("control")}.getOrDefault("")
        return DriverVoicePolicy.allowedMoving(r.kind,control,speed.isFinite()&&gear.isFinite())
    }
    /** The family's designated rest stop (situation care): approving it adds a stop and keeps the destination and guidance. */
    fun isRestStop(r:PassengerRequest)=r.kind=="waypoint"&&r.id.startsWith("rest-")
    fun decide(r:PassengerRequest,accept:Boolean,approval:JSONObject?=null,voice:Boolean=false,routeAsStop:Boolean=false){
      val allowed={if(voice)canVoiceDecide(r) else if(r.delegated)authorized(r) else parked&&(r.kind!="permission"||ignition.on&&r.authorityEpoch==ignition.epoch)}
      relay.decide(r,allowed,accept,approval){done->
        if(r.kind=="permission"){
            done("confirmed","선택한 권한을 승인했어요. $grantLifetime 사용할 수 있어요")
        }else if(r.kind=="care"){
            val o=runCatching{JSONObject(r.options)}.getOrDefault(JSONObject())
            if(o.optString("action")=="start"&&o.has("temperature")){
                val g=relay.delegations.firstOrNull{it.optString("deviceId")==r.deviceId}
                val zone=com.familycare.ui.SeatAccess.objects(climateZones).firstOrNull{g?.optString("seat") in com.familycare.ui.SeatAccess.members(it.optString("id"))}
                if(zone==null){done("rejected","내 좌석 온도 권한을 확인하세요");return@decide}
                applyClimate(o.optDouble("temperature"),zone=zone.optString("id"),allowed=allowed){status,text->done(status,if(status=="confirmed")"내 좌석 온도 확인 완료 · 상황별 케어 시작" else text)}
            }else done("confirmed",if(o.optString("action")=="stop")"상황별 케어를 종료했어요. 설정 온도는 유지합니다" else "상황별 케어를 반영했어요")
        }else if(r.kind=="hold"){
            val o=runCatching{JSONObject(r.options)}.getOrNull()
            if(o?.optString("action")=="stop"){stopHold();done("confirmed","공조 보조를 종료했어요. 기존 공조 전원은 그대로입니다")}
            else if(o?.optString("action")=="start"&&startHold(o.optDouble("target"),o.optInt("minutes"))){holdPermission=r.takeIf{it.delegated};done("confirmed","승인한 범위에서 공조 보조를 시작했어요. 센서·전원·정차 상태가 바뀌면 중단됩니다")}
            else done("rejected","P단 정차·반려동물 타이머·공조 전원·온도·배터리 25% 초과를 먼저 확인하세요")
        }else if(r.kind=="timer"){
            if(!parked){done("rejected","정차 후 확인 타이머를 설정하세요");return@decide}
            val options=runCatching{JSONObject(r.options)}.getOrNull()
            val minutes=options?.optInt("minutes",-1)?:-1
            val action=options?.optString("action").orEmpty()
            if(action !in listOf("start","stop")||minutes !in 1..30){done("rejected","타이머 설정을 확인하세요");return@decide}
            if(action=="stop"){stopWatch();done("confirmed","확인 타이머를 종료했어요")}
            else if(Passenger.PET !in engine.profile.members)done("rejected","가족 구성에서 반려동물을 먼저 선택하세요")
            else{stopWatch();setTimer(minutes);startWatch();done(if(engine.watching)"confirmed" else "needs_review",if(engine.watching)"${minutes}분 확인 타이머를 시작했어요" else "타이머 시작을 확인하지 못했어요")}
        }else if(r.kind=="profile"){
            if(!parked||engine.running||engine.watching){done("rejected","이동·타이머 종료 후 가족 구성을 변경하세요");return@decide}
            val profile=runCatching{JSONObject(r.options)}.getOrNull()
            val members=profile?.optJSONArray("members")
            val names=if(members==null)emptyList()else (0 until members.length()).map{members.optString(it)}
            val band=profile?.optInt("childBand",-1)?:-1
            if(members==null||names.size>5||names.any{name->Passenger.entries.none{it.name==name}}||band !in 0..2){done("rejected","가족 구성을 확인하세요");return@decide}
            val sensitivity=profile.optInt("sensitivity",1)
            if(sensitivity !in 0..2){done("rejected","민감도 설정을 확인하세요");return@decide}
            engine.profile=FamilyProfile(names.map{Passenger.valueOf(it)}.toSet(),band,sensitivity)
            saveProfile(0);done("confirmed","가족 구성과 맞춤 모드를 반영했어요")
        }else if(r.kind=="control"){
            val o=runCatching{JSONObject(r.options)}.getOrDefault(JSONObject())
            applyControl(o.optString("control"),o.optDouble("value",Double.NaN),r.originRole=="owner"||r.delegated,allowed,done)
        }else if(r.kind=="climate"){
            if(!allowed()||!climateSupported){done("rejected","P단 정차와 공조 지원 여부를 확인해 주세요");return@decide}
            val options=runCatching{JSONObject(r.options)}.getOrDefault(JSONObject())
            val zone=options.optString("area","ROW_1_LEFT")
            if(options.has("power")&&options.opt("power") !is Boolean){done("rejected","공조 전원 설정을 확인하세요");return@decide}
            applyClimate(r.target!!,zone=zone,power=if(options.has("power"))options.getBoolean("power")else null,allowed=allowed,complete=done)
        }else if(r.kind=="destination"||r.kind=="waypoint"||r.kind=="pickup"){
            val asStop=routeAsStop||isRestStop(r)
            val complete:(Boolean,String)->Unit={ok,text->done(if(ok)"confirmed" else "needs_review",if(ok&&r.kind!="pickup"&&!asStop)"경로 반영 완료 · ${navigation.routeChange} · 차량에서 안내 시작" else text)}
            val point=GeoPoint(r.latitude!!,r.longitude!!)
            if(asStop&&r.kind!="pickup")navigation.planSuggestedStop(r.title.removePrefix("지정 휴게소 · "),point,voice,complete) else when(r.kind){
                "pickup"->navigation.planPickup(runCatching{JSONObject(r.options).optString("landmark",r.title)}.getOrDefault(r.title),point,voice,complete)
                "destination"->navigation.plan(r.title,point,complete)
                else->navigation.planWaypoint(point,complete)
            }
        }else{val nav=navi;if(nav==null)done("needs_review","내비 연결 미확인")else nav.submit(r,done)}
    }}
    fun applyControl(id:String,value:Double,owner:Boolean,allowed:()->Boolean={parked},complete:(String,String)->Unit){
        if(id.startsWith("hvac.fan:")&&engine.value(Signal.CLIMATE_POWER,now())==0.0){complete("rejected","공조 전원을 켠 뒤 풍량을 조절하세요");return}
        if(climatePending||!allowed()||controls==null){complete("rejected","조절 권한과 차량 상태를 확인하세요");return}
        stopHold();climatePending=true
        if(!retain()){climatePending=false;complete("needs_review",notice);return}
        controls!!.execute(id,value,owner,allowed){status,text->climatePending=false;notice=text;publish();stopServiceIfIdle();complete(status,text)}
    }
    fun applyClimate(target:Double,zone:String="ROW_1_LEFT",fromHold:Boolean=false,power:Boolean?=null,allowed:()->Boolean={parked},complete:(String,String)->Unit={_,_->}){
        if(!fromHold)stopHold()
        if(climatePending||!allowed()||!climateSupported){notice="조절 권한과 공조 연결을 확인하세요";complete("rejected",notice);return}
        climatePending=true
        if(!retain()){climatePending=false;complete("needs_review",notice);return}
        sensors!!.setClimate(target,{allowed()&&(!fromHold||holding&&(holdPermission?.let{authorized(it)}!=false)&&engine.watching&&engine.value(Signal.CLIMATE_POWER,now())==1.0)},{status,text->climatePending=false;notice=text;publish();stopServiceIfIdle();complete(status,text)},zone,power)
    }
    private fun saveHistory(){
        val a=JSONArray();history.forEach{a.put(JSONObject().put("at",it.startedAt).put("duration",it.durationSeconds)
            .put("driving",it.drivingSeconds).put("accel",it.feedback.acceleration).put("brake",it.feedback.braking).put("steer",it.feedback.steering).put("mode",it.mode))}
        prefs.edit().putString("history",a.toString()).apply()
    }
    fun closures():String{
        val v=listOf(Signal.FL_DOOR,Signal.FR_DOOR,Signal.RL_DOOR,Signal.RR_DOOR,Signal.FL_WINDOW,Signal.FR_WINDOW,Signal.RL_WINDOW,Signal.RR_WINDOW).map{engine.value(it,now())}
        return when{v.any{it==null}->"문·창문 일부 상태 미확인";v.any{it!!>0}->"열린 문 또는 창문이 있어요";else->"확인한 문·창문이 모두 닫혀 있어요"}
    }
    private fun telemetry():JSONObject{
        val alerts=JSONArray();engine.alerts(now()).forEach{alerts.put(JSONObject().put("code",it.code).put("title",it.title).put("level",it.level))}
        val liveRoute=navigation.route!=null
        val liveStatus=navigation.active&&navigation.locationUsable||navigation.arrived
        return JSONObject().put("ignitionOn",engine.value(Signal.IGNITION,now())?.let{it==4.0||it==5.0}?:JSONObject.NULL).put("ignitionSupported",sensors?.ignitionSupported?:JSONObject.NULL).put("authorityActive",ignition.on).put("authorityMode",ignition.mode).put("authorityEpoch",ignition.epoch).put("climateSupported",climateSupported).put("climateTarget",engine.value(Signal.CLIMATE_TARGET,now())?:JSONObject.NULL).put("parked",parked).put("running",engine.running).put("watching",engine.watching).put("mode",if(engine.running)"함께 이동 중" else "이동 준비")
            .put("profile",JSONObject().put("members",JSONArray(engine.profile.members.map{it.name})).put("childBand",engine.profile.childBand).put("sensitivity",engine.profile.sensitivity).put("mode",engine.profile.mode))
            .put("routeId",if(navigation.route!=null)navigation.routeIdentity else "")
            .put("routeTotalMeters",navigation.route?.meters?:JSONObject.NULL)
            .put("updatedAt",System.currentTimeMillis())
            .put("vehiclePosition",if(relay.pickup.optBoolean("enabled")&&navigation.locationUsable)navigation.fix?.let{JSONObject().put("point",JSONArray(listOf(it.point.longitude,it.point.latitude))).put("accuracy",it.accuracy).put("ageMs",(now()-it.elapsed).coerceAtLeast(0))}?:JSONObject.NULL else JSONObject.NULL)
            .put("gpsStatus",navigation.locationStatus).put("locationSdk",navigation.gps.sdkStatus).put("mapProvider",navigation.providerLabel)
            .put("motionLoad",engine.motionLoad(now()))
            .put("controls",vehicleControls).put("climateAreas",climateZones).put("climatePowerSupported",climatePowerSupported).put("climatePower",engine.value(Signal.CLIMATE_POWER,now())?.let{it==1.0}?:JSONObject.NULL)
            .put("climateHolding",holding).put("climateHoldSeconds",((holdUntil-now())/1000).coerceAtLeast(0))
            .put("journey",if(shareJourney&&journey.startedAt>0)journey.json() else JSONObject.NULL)
            .put("routeSummary",if(shareJourney)navigation.summary() else JSONObject.NULL)
            .put("restStop",relay.situation.optJSONObject("stop")?.let{GeoPoint(it.optDouble("latitude"),it.optDouble("longitude"))}?.takeIf{it.valid()}?.let(navigation::stopStatus)?:JSONObject.NULL)
            .put("temperature",engine.value(Signal.TEMPERATURE,now())?:JSONObject.NULL).put("battery",engine.value(Signal.BATTERY,now())?:JSONObject.NULL)
            .put("closures",closures()).put("feedback",engine.feedback.total).put("drivingMinutes",engine.drivingMillis/60000)
            .put("breakRemainingMinutes",if(engine.running)((engine.profile.breakMinutes*60000L-engine.drivingSinceBreakMillis).coerceAtLeast(0)+59999)/60000 else JSONObject.NULL)
            .put("watchRemainingSeconds",engine.watchStartedAt?.let{((engine.checkMinutes*60000L-(now()-it)).coerceAtLeast(0)+999)/1000}?:JSONObject.NULL)
            .put("remainingMeters",if(liveRoute){if(liveStatus)navigation.remainingMeters?.toInt()?:JSONObject.NULL else JSONObject.NULL}else remainingMeters?:JSONObject.NULL)
            .put("remainingSeconds",if(liveRoute){if(liveStatus)navigation.remainingSeconds?.toInt()?:JSONObject.NULL else JSONObject.NULL}else remainingSeconds?:JSONObject.NULL)
            .put("arrived",if(liveRoute)navigation.arrived else arrived).put("alerts",alerts)
    }
}

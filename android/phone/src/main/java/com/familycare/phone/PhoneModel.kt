package com.familycare.phone

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

data class SavedPlace(val name:String,val latitude:Double,val longitude:Double)

class PhoneModel(app:Application):AndroidViewModel(app){
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var link:JoinLink?=null
    private val connectionStore=ConnectionStore(app)
    private var rememberedConnection=false
    private var restoringConnection=false
    private var expiresIn=7200
    fun mapGateway():com.familycare.ui.MapGateway?=link?.let{com.familycare.ui.MapGateway(it.origin,it.session,it.token)}
    private var generation=0
    private var visible=false
    private var lastResponse=0L
    private var inFlight by mutableStateOf(false)
    private var pendingSignature=""
    private var pendingBody:JSONObject?=null
    private val client=app.getSharedPreferences("client",0).let{p->p.getString("id",null)?:UUID.randomUUID().toString().also{p.edit().putString("id",it).apply()}}
    private val prefs=app.getSharedPreferences("saved-places",0)
    var archivedJourneys by mutableStateOf(emptyList<JSONObject>());private set
    fun archiveJourney(){state.optJSONObject("journey")?.let{j->
        archivedJourneys=(listOf(JSONObject(j.toString()))+archivedJourneys.filterNot{it.optString("id")==j.optString("id")}).take(20)
        prefs.edit().putString("journeys",JSONArray(archivedJourneys).toString()).apply();resultMessage="이 휴대폰에 이동 기록을 저장했어요"
    }}
    fun clearArchive(){archivedJourneys=emptyList();prefs.edit().remove("journeys").apply()}
    var joinText by mutableStateOf("")
    /** A link opened in the wrong app: 내 차 runs only the owner QR, Family Care never runs it. Offered to the other app instead. */
    var handoffLink by mutableStateOf("");private set
    fun clearHandoff(){handoffLink=""}
    var role by mutableStateOf(""); private set
    var connected by mutableStateOf(false); private set
    var stale by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("차량에서 받은 연결 링크를 입력하세요"); private set
    var resultMessage by mutableStateOf(""); private set
    private var feedbackRequestId=""
    private var feedbackLabel=""
    private var feedbackStatus=""
    private fun updateActionFeedback(){
        val r=requests.firstOrNull{it.optString("id")==feedbackRequestId}?:return
        val status=r.optString("status")
        if(status==feedbackStatus)return
        feedbackStatus=status
        resultMessage=when(status){
            "confirmed"->"$feedbackLabel · 반영 완료"
            "rejected"->"$feedbackLabel · 적용 안 됨: "+r.optString("message").take(100)
            "needs_review"->"$feedbackLabel · 결과 미확인: "+r.optString("message").take(100)
            "awaiting_agreement"->"$feedbackLabel · 함께 사용하는 동승자 동의 대기"
            "executing"->"$feedbackLabel · 적용 중"
            else->"$feedbackLabel · "+if(r.optBoolean("delegated"))"적용 대기" else "운전자 확인 대기"
        }
        if(status in listOf("confirmed","rejected","needs_review"))feedbackRequestId=""
    }
    var state by mutableStateOf(JSONObject()); private set
    var lastVehicleAt by mutableStateOf<Long?>(null);private set
    var alerts by mutableStateOf(emptyList<JSONObject>()); private set
    var alertHistory by mutableStateOf(emptyList<JSONObject>()); private set
    var recipients by mutableStateOf(emptyList<JSONObject>());private set
    fun recipient(id:String,arrival:Boolean,care:Boolean){if(isOwner)submit("recipient",JSONObject().put("client",id).put("arrival",arrival).put("care",care))}
    var requests by mutableStateOf(emptyList<JSONObject>()); private set
    private val historyPrefs=app.getSharedPreferences("management-history",0)
    private var hiddenResults by mutableStateOf(emptySet<String>())
    val managementRequests get()=requests.filterNot{it.optString("id") in hiddenResults}
    fun canDeleteResult(r:JSONObject)=r.optString("status") in setOf("confirmed","rejected","needs_review")
    fun deleteManagementResult(id:String){
        if(!isOwner||requests.none{it.optString("id")==id&&canDeleteResult(it)})return
        hiddenResults=hiddenResults+id;saveHiddenResults()
    }
    fun clearManagementResults(){
        if(!isOwner)return
        hiddenResults=hiddenResults+requests.filter(::canDeleteResult).map{it.optString("id")}
        saveHiddenResults()
    }
    private fun saveHiddenResults(){
        historyPrefs.edit().putString("session",link?.session).putStringSet("hidden",hiddenResults).apply()
    }
    fun climateTarget(area:String):Double? {
        val zone=com.familycare.ui.SeatAccess.objects(state.optJSONArray("climateAreas")).firstOrNull{it.optString("id")==area}
        // Never substitute the driver's setting for another seat or an unknown zone.
        return if(zone!=null)zone.optDouble("target",Double.NaN).takeIf{it.isFinite()}
        else if(area=="ROW_1_LEFT")state.optDouble("climateTarget",Double.NaN).takeIf{it.isFinite()} else null
    }
    fun climateLabel(area:String)=com.familycare.ui.SeatAccess.objects(state.optJSONArray("climateAreas")).firstOrNull{it.optString("id")==area}?.optString("label")?:"좌석"
    fun toggleClimatePower(area:String){
        val current=climateTarget(area)
        if(current==null){resultMessage="선택한 좌석의 설정 온도를 확인한 뒤 다시 눌러 주세요";return}
        climate(current,area,!state.optBoolean("climatePower"))
    }
    var places by mutableStateOf(emptyList<SavedPlace>()); private set
    var vehicleName by mutableStateOf(prefs.getString("vehicle-name","").orEmpty());private set
    var vehicleModel by mutableStateOf(prefs.getString("vehicle-model","").orEmpty());private set
    var vehiclePlate by mutableStateOf(prefs.getString("vehicle-plate","").orEmpty());private set
    var memories by mutableStateOf(emptyList<JSONObject>());private set
    var memoryDetail by mutableStateOf<JSONObject?>(null);private set
    fun openMemory(id:String){if(canMemories)io("memory",JSONObject().put("id",id)){r->r.onSuccess{memoryDetail=it}.onFailure{resultMessage="사진을 다시 눌러 주세요"}}}
    // Copies of the session's photos on this phone, shared again after the PC server restarts (see MemorySyncPlan).
    private val memoryStore=MemoryStore(app)
    var keepMemories by mutableStateOf(memoryStore.enabled);private set
    var localMemories by mutableStateOf(memoryStore.entries());private set
    private var memorySyncing=false
    private val refusedMemories=mutableSetOf<String>()
    fun changeKeepMemories(on:Boolean){
        memoryStore.enabled=on;keepMemories=on
        if(!on){memoryStore.clear();localMemories=emptyList();resultMessage="이 휴대폰에 보관한 추억 사진을 지웠어요"}
        else{resultMessage="공유된 추억 사진을 이 휴대폰에 보관하고, 다시 연결되면 자동으로 공유해요";syncMemories()}
    }
    private fun syncMemories(){
        if(memorySyncing||!keepMemories||!connected||stale||role !in listOf("family","owner"))return
        val session=link?.session?:return;val version=generation
        val server=memories.map{it.optString("id")}.filter{it.isNotBlank()}
        val step=MemorySyncPlan.next(server,memoryStore.locals(),memoryStore.seen(session,server),refusedMemories)
        if(step.forget.isNotEmpty()){step.forget.forEach(memoryStore::remove);localMemories=memoryStore.entries()}
        val fetch=step.fetch;val upload=step.upload?.let{id->memoryStore.full(id)}
        if(fetch!=null){
            memorySyncing=true
            io("memory",JSONObject().put("id",fetch)){r->if(version!=generation)return@io;memorySyncing=false
                r.onSuccess{if(memoryStore.save(it))localMemories=memoryStore.entries()}}
        }else if(upload!=null){
            val id=upload.optString("id");memorySyncing=true
            io("memories",upload.put("client",client)){r->if(version!=generation)return@io;memorySyncing=false
                // Another phone may share the same copy first (409); a refused photo is not retried this session.
                r.onSuccess{resultMessage="보관한 추억 사진을 다시 공유했어요"}.onFailure{e->if(e is Failure&&e.status!=409)refusedMemories+=id}}
        }else if(step.upload!=null)memoryStore.remove(step.upload).also{localMemories=memoryStore.entries()}
    }
    var permissions by mutableStateOf(JSONObject());private set
    var invites by mutableStateOf(JSONObject());private set
    val grantLifetime get()=if(state.optString("authorityMode")=="connection")"이번 차량 연결이 종료될 때까지" else "시동이 꺼질 때까지"
    var music by mutableStateOf(JSONObject());private set
    fun musicLink():String?=if(isOwner&&connected&&!stale)link?.url() else null
    fun musicCommand(command:String){if(isOwner&&connected)submit("music-command",JSONObject().put("command",command))}
    var pickup by mutableStateOf(JSONObject());private set
    var pickupDraft by mutableStateOf(JSONObject());private set
    var pickupEta by mutableStateOf(JSONObject());private set
    fun pickupShare(enabled:Boolean){if(isOwner&&connected&&!stale)submit("pickup-share",JSONObject().put("enabled",enabled))}
    fun endPickup(deviceId:String="",boarded:Boolean=false){if(connected&&!stale)submit(if(boarded)"pickup-boarded" else "pickup-cancel",JSONObject().put("deviceId",deviceId))}
    fun invalidatePickupDraft(){pickupDraft=JSONObject();pickupEta=JSONObject()}
    fun pickupLocation(l:android.location.Location){
        if(busy||!connected||stale)return
        busy=true;pickupDraft=JSONObject();pickupEta=JSONObject()
        io("pickup-location",JSONObject().put("provider","tmap").put("consent",true).put("point",JSONArray(listOf(l.longitude,l.latitude))).put("accuracy",l.accuracy).put("ageMs",((SystemClock.elapsedRealtimeNanos()-l.elapsedRealtimeNanos)/1_000_000).coerceAtLeast(0))){r->busy=false;r.onSuccess{pickupDraft=it;resultMessage=it.optString("notice")}.onFailure{resultMessage=it.message?:"주소를 확인하지 못했어요"}}
    }
    fun sendPickup(name:String,landmark:String,memo:String){
        if(!canRequest||pickupDraft.optString("draft").isBlank())return
        val o=JSONObject().put("draft",pickupDraft.getString("draft")).put("name",name).put("landmark",landmark).put("message",memo)
        val signature="pickup"+o.toString()
        if(pendingBody==null||pendingSignature!=signature){pendingSignature=signature;pendingBody=JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind","pickup").put("title","픽업 대기 위치").put("options",o)}
        submit("request",pendingBody!!,true)
    }
    fun pickupArrival(){if(!canRequest)return;busy=true;io("pickup-eta",JSONObject().put("provider","tmap").put("consent",true)){r->busy=false;r.onSuccess{pickupEta=it}.onFailure{resultMessage=it.message?:"도착 예상을 조회하지 못했어요"}}}
    var routeNeeds by mutableStateOf(emptyList<JSONObject>());private set
    fun setRouteNeeds(needs:Set<String>){if(connected&&!stale)submit("route-needs",JSONObject().put("needs",JSONArray(needs.toList())))}
    var situation by mutableStateOf(JSONObject());private set
    var caregivers by mutableStateOf(emptyList<JSONObject>());private set
    var careInbox by mutableStateOf(emptyList<JSONObject>());private set
    fun acknowledgeCare(id:String){submit("care-ack",JSONObject().put("id",id))}
    fun urgentNote(){submit("request",JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind","note").put("title","동승자가 긴급 확인을 요청했어요. 안전한 곳에서 확인해 주세요").put("options",JSONObject().put("urgent",true)),true)}
    var grant by mutableStateOf(JSONObject());private set
    var permissionCatalog by mutableStateOf(emptyList<JSONObject>());private set
    var delegations by mutableStateOf(emptyList<JSONObject>());private set
    var permissionPrompt by mutableStateOf(0);private set
    private var paired=false
    fun hasScope(scope:String):Boolean {
        if(!connected||stale)return false
        if(isOwner)return true
        if(!grant.optBoolean("active")||!state.optBoolean("authorityActive")||grant.optString("epoch")!=state.optString("authorityEpoch"))return false
        val scopes=grant.optJSONArray("scopes")?:return false
        return (0 until scopes.length()).any{scopes.optString(it)==scope}
    }
    fun canControl(c:JSONObject)=canRequest&&!(c.optString("id").startsWith("hvac.fan:")&&state.opt("climatePower")==false)&&c.optBoolean("available")&&hasScope("control:"+c.optString("id"))&&(!(isOwner||c.optString("id").startsWith("door.")||c.optString("id").startsWith("window."))||state.optBoolean("parked"))
    fun requestPermissions(name:String,scopes:Set<String>,seat:String="ROW_1_LEFT",limits:JSONObject=JSONObject()){
        if(!canRequest||role!="family"||!paired)return
        if(name.isBlank()||scopes.isEmpty()){resultMessage="이름과 사용할 권한을 선택하세요";return}
        submit("request",JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind","permission").put("title",name.trim().take(30)+" · 조절 권한 요청").put("options",JSONObject().put("name",name.trim().take(30)).put("scopes",JSONArray(scopes.sorted())).put("seat",seat).put("limits",limits)),request=true)
    }
    fun agree(id:String,accept:Boolean){if(canRequest)submit("agree",JSONObject().put("id",id).put("accept",accept))}
    fun revokeGrant(deviceId:String){if(isOwner)submit("revoke-grant",JSONObject().put("deviceId",deviceId))}
    val isOwner get()=BuildConfig.PRODUCT=="navigation"&&role=="owner"
    val canClimate get()=canRequest&&role in listOf("family","owner")&&hasScope("climate")&&state.optBoolean("climateSupported")&&(!isOwner||state.optBoolean("parked"))
    val canMemories get()=connected&&!stale&&role in listOf("family","owner")&&!busy
    fun registerVehicle(name:String,model:String,plate:String){
        if(name.isBlank()||model.isBlank()){resultMessage="차량 별명과 모델을 입력하세요";return}
        vehicleName=name.trim().take(30);vehicleModel=model.trim().take(40);vehiclePlate=plate.trim().take(20)
        prefs.edit().putString("vehicle-name",vehicleName).putString("vehicle-model",vehicleModel).putString("vehicle-plate",vehiclePlate).apply()
        resultMessage="내 차 정보를 이 휴대폰에 등록했어요. 차량 연결로 관리 기능을 활성화하세요."
    }
    fun removeVehicle(){disconnect();vehicleName="";vehicleModel="";vehiclePlate="";prefs.edit().remove("vehicle-name").remove("vehicle-model").remove("vehicle-plate").apply()}
    fun explain(text:String){resultMessage=text}
    fun permission(role:String,enabled:Boolean){if(isOwner&&!stale&&!busy)submit("permissions",JSONObject().put("role",role).put("enabled",enabled))}
    fun shareMemory(body:JSONObject){if(canMemories){
        val photo=JSONObject(body.toString())
        // The phone that shared a photo always keeps its own copy, so it can share it again after the server restarts.
        submit("memories",JSONObject(body.toString()).put("client",client)){if(keepMemories&&memoryStore.save(photo))localMemories=memoryStore.entries()}
    }}
    /** Why the share button is off, shown under it (empty when sharing is possible). */
    val memoryShareBlocker get()=when{
        !connected->"차량에 연결하면 공유할 수 있어요. 차량의 QR로 연결해 주세요"
        stale->"차량 연결을 확인하는 중이에요. 노트북을 다시 켰다면 차량의 새 QR로 다시 연결해 주세요"
        role !in listOf("family","owner")->"손님 연결에서는 사진을 공유할 수 없어요"
        busy->"앞의 요청을 보내는 중이에요. 잠시 후 다시 눌러 주세요"
        else->""
    }
    fun deleteMemory(id:String){if(canMemories){memoryStore.remove(id);localMemories=memoryStore.entries();submit("delete-memory",JSONObject().put("id",id).put("client",client))}}
    fun vehicleControl(control:JSONObject,value:Double){
        if(!canControl(control)||!value.isFinite()||!isOwner&&!com.familycare.ui.SeatAccess.allows(grant,"control",JSONObject().put("control",control.optString("id")).put("value",value),null,state.optJSONArray("climateAreas"),state.optJSONArray("controls"))){resultMessage="권한 탭에서 승인 범위와 정차 조건을 확인하세요";return}
        if(isOwner&&!state.optBoolean("parked")){resultMessage="P단 정차 후 조절할 수 있어요";return}
        val id=control.optString("id")
        val signature="control|$id|$value"
        if(pendingBody==null||pendingSignature!=signature){pendingSignature=signature;pendingBody=JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind","control").put("title",control.optString("label").take(70)+" · "+if(control.optBoolean("boolean")){if(value==1.0)"켜기 / 잠금" else "끄기 / 해제"}else value.toInt().toString()).put("options",JSONObject().put("control",id).put("value",value))}
        submit("request",pendingBody!!,request=true)
    }
    fun climateBounds(area:String):Pair<Double,Double>{
        val z=com.familycare.ui.SeatAccess.objects(state.optJSONArray("climateAreas")).firstOrNull{it.optString("id")==area}
        val b=if(isOwner)null else grant.optJSONObject("limits")?.optJSONObject("climate")
        return (b?.optDouble("min")?:z?.optDouble("min",18.0)?:18.0) to (b?.optDouble("max")?:z?.optDouble("max",28.0)?:28.0)
    }
    fun visibleClimateAreas():JSONArray=JSONArray(com.familycare.ui.SeatAccess.objects(state.optJSONArray("climateAreas")).filter{isOwner||grant.optString("seat") in com.familycare.ui.SeatAccess.members(it.optString("id"))})
    val preferredClimateArea get()=visibleClimateAreas().optJSONObject(0)?.optString("id")?:"ROW_1_LEFT"
    fun climate(target:Double,area:String=preferredClimateArea,power:Boolean?=if(isOwner&&state.optBoolean("climatePowerSupported"))true else null){
        if(!canClimate){resultMessage="차량 연결·공조 권한·정차 조건을 확인해 주세요";return}
        val bounds=climateBounds(area)
        if(!target.isFinite()||target !in bounds.first..bounds.second||target*2!=kotlin.math.floor(target*2)){resultMessage="허용된 온도 범위에서 0.5°C 단위로 선택하세요";return}
        val authorization=JSONObject().put("area",area).apply{if(power!=null)put("power",power)}
        if(!isOwner&&!com.familycare.ui.SeatAccess.allows(grant,"climate",authorization,target,state.optJSONArray("climateAreas"),state.optJSONArray("controls"))){resultMessage="승인된 좌석·온도 범위와 공조 전원 권한을 확인하세요";return}
        val signature="climate|$target|$area|$power"
        val zones=state.optJSONArray("climateAreas")
        val label=if(zones==null)"운전석" else (0 until zones.length()).map{zones.getJSONObject(it)}.firstOrNull{it.optString("id")==area}?.optString("label")?:"운전석"
        if(pendingBody==null||pendingSignature!=signature){pendingSignature=signature;pendingBody=JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind","climate").put("title",if(power==false)"공조 끄기 요청" else "$label 설정 온도 ${target}°C 요청").put("target",target).put("options",JSONObject().put("area",area).apply{if(power!=null)put("power",power)})}
        submit("request",pendingBody!!,request=true)
    }
    fun adjustClimate(delta:Double){
        if(!canClimate){resultMessage="차량의 공조 제어 권한과 연결 상태를 확인해 주세요";return}
        val zones=visibleClimateAreas()
        val zone=zones?.let{(0 until it.length()).map(it::getJSONObject).firstOrNull{z->!z.isNull("target")}}
        val current=zone?.optDouble("target")?:state.optDouble("climateTarget",Double.NaN)
        if(!current.isFinite()){resultMessage="현재 공조 설정을 확인하고 있어요. 잠시 후 다시 눌러 주세요";return}
        val bounds=climateBounds(zone?.optString("id")?:preferredClimateArea)
        val next=(kotlin.math.round((current+delta)*2)/2).coerceIn(bounds.first,bounds.second)
        if(next==current){resultMessage="이미 조절 가능한 온도 범위의 끝이에요";return}
        climate(next,zone?.optString("id")?:"ROW_1_LEFT")
    }
    val canRequest get()=connected&&!stale&&role in listOf("owner","family","guest")&&!busy
    val canPlan get()=canRequest&&role in listOf("owner","family")
    val canShareConnection get()=connected&&!stale&&role.isNotEmpty()
    val canConnect get()=!busy&&!inFlight
    fun dismissResult(){resultMessage=""}
    fun connectionForOtherApp():String?=handoffLink.takeIf{it.isNotBlank()}?:if(!canShareConnection)null else if(isOwner)invites.optString("family").takeIf{it.isNotBlank()} else if(role=="owner")null else link?.url()
    fun receiveLink(raw:String):Boolean{
        val parsed=runCatching{JoinLink.parse(raw,BuildConfig.DEBUG)}.getOrNull()?:return false
        // A newly received link never replaces a live connection without confirmation.
        joinText=parsed.url()
        return true
    }
    init{runCatching{
        archivedJourneys=JSONArray(prefs.getString("journeys","[]")).objects()
        val a=JSONArray(prefs.getString("places","[]"));places=(0 until a.length()).map{i->a.getJSONObject(i).let{SavedPlace(it.getString("name"),it.getDouble("lat"),it.getDouble("lon"))}}
    };restoreConnection()}
    private fun restoreConnection(){
        val saved=connectionStore.read()?:return
        link=saved.link;paired=saved.paired;rememberedConnection=true;restoringConnection=true
        connected=true;stale=true;message="이전에 연결한 차량을 다시 확인하고 있어요"
        hiddenResults=if(historyPrefs.getString("session",null)==saved.link.session)historyPrefs.getStringSet("hidden",emptySet())!!.toSet()else emptySet()
    }
    private fun rememberConnection(){
        val current=link?:return
        if(role.isBlank()||role=="family"&&!paired)return
        val saved=runCatching{connectionStore.saveVerified(current,paired,expiresIn,replace=!rememberedConnection)}.getOrDefault(false)
        if(!saved){
            if(rememberedConnection){disconnect();message="차량 연결이 종료됐어요. 새 QR로 연결하세요"}
            else resultMessage="연결을 저장하지 못했어요. 앱을 다시 열어 연결해 주세요"
            return
        }
        rememberedConnection=true
        if(visible)runCatching{PhoneCareService.ensure(getApplication(),current)}
    }
    fun resume(){
        visible=true
        if(rememberedConnection&&connectionStore.read()?.link!=link){disconnect();message="차량 연결이 종료됐어요. 새 QR로 연결하세요"}
        handler.removeCallbacks(tick);handler.post(tick)
    }
    fun pause(){visible=false;handler.removeCallbacks(tick)}
    private val tick=object:Runnable{override fun run(){
        if(!visible)return
        if(connected&&SystemClock.elapsedRealtime()-lastResponse>15000)markStale("연결 확인 중 · 현재 차량 상태 미확인")
        if(connected&&!inFlight&&!busy)poll()
        handler.postDelayed(this,2500)
    }}
    fun connect(){
        if(inFlight)return
        val parsed=runCatching{JoinLink.parse(joinText,BuildConfig.DEBUG)}.getOrElse{message=it.message?:"링크를 확인하세요";return}
        if(parsed==link&&connected){joinText="";refresh();return}
        disconnect();handoffLink="";link=parsed;restoringConnection=false
        hiddenResults=if(historyPrefs.getString("session",null)==parsed.session)historyPrefs.getStringSet("hidden",emptySet())!!.toSet()else emptySet()
        saveHiddenResults();connected=true;message="차량 연결 확인 중";poll()
    }
    fun disconnect(){
        val previous=link
        if(previous!=null){connectionStore.clearIfMatches(previous);if(paired)worker.execute{runCatching{http(previous,"leave-device",JSONObject())}}}
        rememberedConnection=false;restoringConnection=false
        getApplication<Application>().stopService(android.content.Intent(getApplication(),PhoneCareService::class.java))
        lastVehicleAt=null
        stopMusic()
        paired=false;pickup=JSONObject();pickupDraft=JSONObject();pickupEta=JSONObject();situation=JSONObject();careInbox=emptyList();caregivers=emptyList();routeNeeds=emptyList();grant=JSONObject();permissionCatalog=emptyList();delegations=emptyList()
        recipients=emptyList()
        memorySyncing=false;refusedMemories.clear()
        generation++;link=null;connected=false;role="";stale=true;state=JSONObject();alerts=emptyList();alertHistory=emptyList();requests=emptyList();memories=emptyList();memoryDetail=null;permissions=JSONObject();invites=JSONObject()
        pendingBody=null;pendingSignature="";feedbackRequestId="";feedbackLabel="";feedbackStatus="";busy=false;inFlight=false;lastResponse=0;resultMessage="";joinText="";message="연결을 해제했어요. 차량에서 모든 연결을 종료할 수 있어요."
    }
    private class Failure(val status:Int,val detail:String):Exception(detail)
    private fun http(c:JoinLink,action:String,body:JSONObject):JSONObject{
        val connection=URL("${c.origin}/api/sessions/${c.session}/$action").openConnection() as HttpURLConnection
        try{
            connection.connectTimeout=4000;connection.readTimeout=if(action in listOf("pickup-location","pickup-eta"))45000 else 5000;connection.instanceFollowRedirects=false
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer ${c.token}")
            connection.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            val code=connection.responseCode
            val bytes=(if(code in 200..299)connection.inputStream else connection.errorStream)?.use{stream->
                val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val count=stream.read(buffer);if(count<0)break;check(out.size()+count<=524288);out.write(buffer,0,count)}
                out.toByteArray()
            }?:byteArrayOf()
            val data=JSONObject(bytes.toString(Charsets.UTF_8))
            if(code !in 200..299)throw Failure(code,data.optString("error","연결을 확인하세요"))
            return data
        }finally{connection.disconnect()}
    }
    private fun io(action:String,body:JSONObject,complete:(Result<JSONObject>)->Unit){
        val c=link?:return;val version=generation
        if(inFlight){
            val deadline=SystemClock.elapsedRealtime()+10000
            fun waitForPoll(){
                if(version!=generation)return
                if(!inFlight){io(action,body,complete);return}
                if(SystemClock.elapsedRealtime()>=deadline){complete(Result.failure(IllegalStateException("연결 응답이 늦어요. 다시 시도해 주세요")));return}
                handler.postDelayed({waitForPoll()},100)
            }
            handler.postDelayed({waitForPoll()},100);return
        }
        inFlight=true
        worker.execute{
            val result=runCatching{http(c,action,body)}
            handler.post{if(version==generation){inFlight=false;complete(result)}}
        }
    }
    fun refresh(){if(!inFlight&&connected)poll()}
    fun startAlerts(){
        if(role=="family"&&!paired){resultMessage="휴대폰 권한 연결이 끝난 뒤 알림을 켜 주세요";return}
        val connection=link?:return
        if(!connected||stale){resultMessage="먼저 차량에 연결하세요";return}
        if(!getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()){
            resultMessage="알림 권한을 허용해야 화면 밖에서도 받을 수 있어요. 알림·보호자 설정에서 다시 켜 주세요";return
        }
        val name=getApplication<Application>().getSharedPreferences("care-notifications",0).getString("guardian","")
        io("subscribe",JSONObject().put("client",client).put("name",name)){r->r.onSuccess{
            getApplication<Application>().getSharedPreferences("care-notifications",0).edit().putBoolean("enabled",true).apply()
            runCatching{PhoneCareService.ensure(getApplication(),connection)}
                .onSuccess{resultMessage="앱을 닫아도 연결 알림을 유지합니다. 상태 표시줄에서 실행 여부를 확인하세요."}
                .onFailure{resultMessage="알림 연결을 시작하지 못했어요. 앱을 다시 열어 주세요."}
        }.onFailure{resultMessage="알림 기기를 등록하지 못했어요. 연결 후 다시 시도하세요"}}
    }
    fun stopMusic(){getApplication<Application>().stopService(android.content.Intent(getApplication(),PhoneMusicService::class.java));music=JSONObject()}
    fun stopAlerts(){
        val app=getApplication<Application>();app.getSharedPreferences("care-notifications",0).edit().putBoolean("enabled",false).apply()
        val nm=app.getSystemService(android.app.NotificationManager::class.java)
        nm.activeNotifications.filter{it.id!=100}.forEach{nm.cancel(it.id)}
    }
    private fun poll(){io("status",JSONObject().put("client",client)){r->
        r.onSuccess{
            role=it.getString("role");expiresIn=it.optInt("expiresIn",0)
            val ownerApp=BuildConfig.PRODUCT=="navigation"
            if(ownerApp!=(role=="owner")){
                // Owner screens never run on a passenger link (and passenger screens never on the owner link).
                val handoff=if(paired)"" else link?.url().orEmpty()
                disconnect();handoffLink=handoff
                message=if(!ownerApp)"소유자용 연결이에요. WithME 가족 앱은 가족·손님 QR로만 연결돼요. 아래 ‘내 차 앱에서 열기’를 누르세요."
                    else if(handoff.isNotBlank())"가족·손님용 연결이에요. 내 차 앱은 차량의 ‘소유자’ QR로만 연결돼요. 아래 ‘WithME 가족 앱에서 열기’를 누르세요."
                    else "가족·손님용 연결이라 해제했어요. 내 차 앱은 차량의 ‘소유자’ QR로만 연결돼요. 동승자는 WithME 가족 앱에서 가족 QR을 스캔하세요."
                return@onSuccess
            }
            memories=(it.optJSONArray("memories")?:JSONArray()).objects();permissions=it.optJSONObject("permissions")?:JSONObject();invites=it.optJSONObject("invites")?:JSONObject()
            lastResponse=SystemClock.elapsedRealtime();stale=it.getBoolean("stale")
            state=if(stale)JSONObject() else it.getJSONObject("state")
            state.optLong("updatedAt").takeIf{time->time>0}?.let{time->lastVehicleAt=time}
            alerts=it.getJSONArray("alerts").objects();alertHistory=(it.optJSONArray("alertHistory")?:JSONArray()).objects();requests=it.getJSONArray("requests").objects()
            updateActionFeedback()
            recipients=(it.optJSONArray("recipients")?:JSONArray()).objects()
            music=it.optJSONObject("music")?:JSONObject()
            pickup=it.optJSONObject("pickup")?:JSONObject()
            if(!pickup.optBoolean("enabled")){pickupDraft=JSONObject();pickupEta=JSONObject()}
            routeNeeds=(it.optJSONArray("routeNeeds")?:JSONArray()).objects()
            situation=it.optJSONObject("situation")?:JSONObject();caregivers=(it.optJSONArray("caregivers")?:JSONArray()).objects();careInbox=(it.optJSONArray("careInbox")?:JSONArray()).objects()
            grant=it.optJSONObject("grant")?:JSONObject()
            permissionCatalog=(it.optJSONArray("permissionCatalog")?:JSONArray()).objects()
            delegations=(it.optJSONArray("delegations")?:JSONArray()).objects()
            if(role=="family"&&!paired){
                if(grant.optString("deviceId").isNotBlank()){paired=true;if(!restoringConnection)permissionPrompt++}
                else io("pair-device",JSONObject()){result->result.onSuccess{data->
                    link=link?.copy(token=data.getString("token"));grant=data.getJSONObject("grant");paired=true;permissionPrompt++;rememberConnection();poll()
                }.onFailure{resultMessage="휴대폰 권한 연결을 다시 확인하고 있어요"}}
            }
            if(!stale){rememberConnection();restoringConnection=false}
            syncMemories()
            message=if(!connected)"차량 연결이 종료됐어요. 새 QR로 연결하세요" else if(stale)"차량 응답 확인 중 · 연결 정보는 유지하고 있어요" else "차량 연결됨 · 앱을 나가도 연결 유지"
        }.onFailure{e->
            markStale(if(e is Failure)e.detail else "서버 연결 확인 중 · 현재 차량 상태 미확인")
            if(e is Failure&&e.status in listOf(403,410)){disconnect();message=e.detail}
        }
    }}
    private fun markStale(text:String){stale=true;state=JSONObject();grant=JSONObject();pickup=JSONObject();pickupDraft=JSONObject();pickupEta=JSONObject();situation=JSONObject();careInbox=emptyList();caregivers=emptyList();routeNeeds=emptyList();message=text}
    fun request(kind:String,title:String,lat:Double?=null,lon:Double?=null){
        if(!canRequest||kind!="note"&&!canPlan)return
        if(kind!="note"&&!hasScope("route")){resultMessage="권한 탭에서 목적지·경유지 변경을 요청하세요";return}
        if(title.isBlank()||title.length>120){resultMessage="내용을 1~120자로 입력하세요";return}
        if(kind!="note"&&(lat==null||lon==null||!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0)){resultMessage="정확한 위도와 경도를 입력하세요";return}
        val signature=listOf(kind,title,lat,lon).joinToString("|")
        if(pendingBody==null||pendingSignature!=signature){
            pendingSignature=signature
            pendingBody=JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind",kind).put("title",title)
            if(kind!="note")pendingBody!!.put("latitude",lat).put("longitude",lon)
        }
        submit("request",pendingBody!!,request=true)
    }
    fun vote(id:String){if(canPlan)submit("vote",JSONObject().put("id",id).put("client",client))}
    fun acknowledge(code:String){if(connected&&!stale&&role in listOf("owner","family","observer")&&!busy)submit("ack",JSONObject().put("code",code))}
    fun careCommand(kind:String,title:String,options:JSONObject){
        if((if(kind=="care")!canRequest else !canPlan)||kind !in listOf("timer","profile","hold","care"))return
        if(!hasScope(kind)){resultMessage="권한 탭에서 이 기능의 권한을 요청하세요";return}
        val signature=kind+options.toString()
        if(pendingBody==null||pendingSignature!=signature){pendingSignature=signature;pendingBody=JSONObject().put("id",UUID.randomUUID().toString()).put("client",client).put("kind",kind).put("title",title).put("options",options)}
        submit("request",pendingBody!!,request=true)
    }
    private fun submit(action:String,body:JSONObject,request:Boolean=false,done:(()->Unit)?=null){
        if(busy)return
        busy=true
        val version=generation
        fun send(retry:Boolean){io(action,body){r->
            val failure=r.exceptionOrNull()
            if(retry&&request&&failure is Failure&&failure.status==429&&failure.detail=="잠시 후 다시 보내세요"){
                resultMessage="요청을 순서대로 전달하고 있어요"
                handler.postDelayed({if(version==generation)send(false)},2100);return@io
            }
            busy=false;r.onSuccess{
            done?.invoke()
            if(request){pendingBody=null;pendingSignature=""}
            val actionLabel=if(request)ActionFeedback.label(body,state) else null
            if(actionLabel!=null){feedbackRequestId=body.optString("id");feedbackLabel=actionLabel;feedbackStatus=""}
            resultMessage=if(actionLabel!=null)"$actionLabel · 처리 확인 중" else when(action){"pickup-share"->"픽업 위치 공유 설정을 반영했어요";"pickup-cancel","pickup-boarded"->"픽업 대기를 종료했어요";"route-needs"->if(it.optBoolean("auto"))"가족 기준을 보냈어요 · 차량 경로에 바로 반영돼요" else "가족 기준을 보냈어요 · 운전자가 차량에서 비교해 선택해요";"care-ack"->"보호자가 확인한 요청으로 표시했어요";"agree"->"공용 설정에 대한 선택을 전달했어요";"permissions"->"동승자 권한을 변경했어요";"memories"->"사진을 가족 지도에 공유했어요";"delete-memory"->"사진 공유를 삭제했어요";"ack"->"가족의 직접 확인을 전달했어요";"vote"->"투표를 전달했어요";else->if(isOwner&&body.optString("kind") in listOf("climate","control"))"P단 정차 중인 차량에 조절을 전달했어요. 결과를 확인해 주세요." else if(body.optString("kind")=="pickup")"대기 위치를 전달했어요. 차량에서 도로명·가게 이름을 짧게 안내합니다." else if(body.optString("kind")=="permission")"권한을 요청했어요. 차량의 받은 요청에서 승인해 주세요." else if(role=="family"&&body.optString("kind")!="note")"승인된 권한으로 차량에 전달했어요. 변경 결과를 확인해 주세요." else "요청을 보냈어요. 운전자가 정차 후 확인합니다."};poll()
        }.onFailure{e->
            if(e is Failure){if(request){pendingBody=null;pendingSignature=""};resultMessage=e.detail}
            else resultMessage=if(request)"전달 여부 미확인. 같은 요청을 다시 누르면 중복 없이 확인합니다." else "연결을 확인한 후 다시 시도하세요"
        }}}
        send(true)
    }
    fun savePlace(name:String,lat:Double?,lon:Double?){
        if(name.isBlank()||name.length>120||lat==null||lon==null||!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0){resultMessage="이름과 정확한 좌표를 입력하세요";return}
        places=(listOf(SavedPlace(name,lat,lon))+places.filterNot{it.name==name}).take(10)
        persist();resultMessage="이 휴대폰에 장소를 저장했어요"
    }
    fun deletePlace(name:String){places=places.filterNot{it.name==name};persist()}
    private fun persist(){val a=JSONArray();places.forEach{a.put(JSONObject().put("name",it.name).put("lat",it.latitude).put("lon",it.longitude))};prefs.edit().putString("places",a.toString()).apply()}
    override fun onCleared(){pause();generation++;worker.shutdownNow();super.onCleared()}
}
private fun JSONArray.objects()=(0 until length()).map{getJSONObject(it)}

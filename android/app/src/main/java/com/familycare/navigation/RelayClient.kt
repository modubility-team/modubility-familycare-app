package com.familycare.navigation

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Single-flight I/O; claim before side effects, retry only idempotent results. */
class RelayClient(private val state: () -> JSONObject, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var session = ""
    @Volatile private var token = ""
    fun mapGateway()=com.familycare.ui.MapGateway("http://127.0.0.1:8765",session,token)
    private var busy = false
    private var ending = false
    private var failures = 0
    private val outbox = mutableMapOf<String, JSONObject>()
    private val decisions = mutableSetOf<String>()
    internal fun decisionPending(id:String)=id in outbox||id in decisions||requests.any{it.id==id&&it.status=="executing"}
    var links: Map<String, String> = emptyMap(); private set
    var requests: List<PassengerRequest> = emptyList(); private set
    private var dismissed=emptySet<String>()
    /** Requests still shown on the vehicle. Clearing a handled request only tidies this screen; phones and the relay keep their copy. */
    val visibleRequests:List<PassengerRequest> get()=requests.filterNot{it.id in dismissed}
    fun canDismiss(r:PassengerRequest)=handledRequest(r.status)&&!decisionPending(r.id)
    fun dismiss(id:String){if(requests.any{it.id==id&&canDismiss(it)}){dismissed=dismissed+id;changed()}}
    fun dismissHandled(){val ids=requests.filter{it.id !in dismissed&&canDismiss(it)}.map{it.id};if(ids.isNotEmpty()){dismissed=dismissed+ids;changed()}}
    var memories:List<JSONObject> = emptyList();private set
    var memoryDetail:JSONObject?=null;private set
    /**
     * Full shared photos (id → base64 JPEG, at most 12 × 256 KB) for the large photo cards and the photo viewer.
     * Fetched one at a time on their own thread, so requests and approvals never wait behind a photo.
     */
    var memoryImages:Map<String,String> = emptyMap();private set
    private val imageWorker=Executors.newSingleThreadExecutor()
    private var imageLoading=false
    private var imagePreferred=""
    private val imageMisses=mutableMapOf<String,Int>()
    /** The viewer asks for the photo on screen first. */
    fun preferMemoryImage(id:String){imagePreferred=id;loadMemoryImages()}
    private fun loadMemoryImages(){
        if(imageLoading||!connected||ending||session.isEmpty())return
        val id=MemoryImageQueue.next(memories.map{it.optString("id")},memoryImages.keys,imageMisses,imagePreferred)?:return
        imageLoading=true
        val current=session;val credential=token
        imageWorker.execute{
            val result=runCatching{http("/api/sessions/$current/memory",JSONObject().put("id",id),credential).getString("image")}
            main.post{
                imageLoading=false
                if(session==current){
                    // After a failure the next sync tries again (at most three times per photo).
                    result.onSuccess{image->
                        if(memories.any{it.optString("id")==id}){memoryImages=memoryImages+(id to image);changed()}
                        loadMemoryImages()
                    }.onFailure{imageMisses[id]=(imageMisses[id]?:0)+1}
                }
            }
        }
    }
    var permissions=JSONObject();private set
    var recipients=emptyList<JSONObject>();private set
    var delegations=emptyList<JSONObject>();private set
    var pickup=JSONObject();private set
    fun pickupAction(action:String,body:JSONObject){if(connected&&!ending)io("/api/sessions/$session/$action",body){r->message=if(r.isSuccess)"픽업 설정을 반영했어요" else "픽업 설정을 다시 확인해 주세요";changed()}}
    var routeNeeds=emptyList<JSONObject>();private set
    /** The latest family criteria send: id, sender, needs, age and whether the sender may change the route ('auto'). */
    var routeNeedsEvent=JSONObject();private set
    var situation=JSONObject();private set
    var careRoutedNoteIds=emptySet<String>();private set
    var permissionCatalog=emptyList<JSONObject>();private set
    fun revokeGrant(deviceId:String){if(connected&&!ending)io("/api/sessions/$session/revoke-grant",JSONObject().put("deviceId",deviceId)){r->
        r.onSuccess{delegations=delegations.filterNot{it.optString("deviceId")==deviceId};message="조절 권한을 회수했어요"}.onFailure{message="권한 회수를 다시 눌러 주세요"}
    }}
    fun recipient(client:String,arrival:Boolean,care:Boolean){if(connected&&!ending)io("/api/sessions/$session/recipient",JSONObject().put("client",client).put("arrival",arrival).put("care",care)){r->message=if(r.isSuccess)"알림 수신 대상을 변경했어요" else "알림 설정을 다시 확인하세요"}}
    fun permission(role:String,enabled:Boolean){
        if(connected&&!ending)io("/api/sessions/$session/permissions",JSONObject().put("role",role).put("enabled",enabled)){r->
            r.onSuccess{permissions=it.getJSONObject("permissions");message="동승자 권한을 변경했어요"}.onFailure{message="권한 변경을 확인하지 못했어요"}
        }
    }
    fun openMemory(id:String){
        val deadline=android.os.SystemClock.elapsedRealtime()+4000
        fun attempt(){
            if(!connected||ending||memories.none{it.optString("id")==id})return
            if(busy&&android.os.SystemClock.elapsedRealtime()<deadline){main.postDelayed({attempt()},200);return}
            if(!io("/api/sessions/$session/memory",JSONObject().put("id",id)){r->r.onSuccess{memoryDetail=it}.onFailure{message="사진을 다시 눌러 불러와 주세요"}}){message="사진을 다시 눌러 불러와 주세요";changed()}
        }
        attempt()
    }
    var message = "동승자 연결을 시작하면 QR이 표시돼요"; private set
    var connected = false; private set
    val sharing get() = session.isNotEmpty() || busy
    private class RelayFailure(val code: Int, message: String) : Exception(message)
    private fun http(path: String, body: JSONObject, credential: String): JSONObject {
        val c = URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout=2500; c.readTimeout=3000; c.instanceFollowRedirects=false
            c.requestMethod="POST"; c.doOutput=true; c.setRequestProperty("Content-Type","application/json")
            if(credential.isNotBlank()) c.setRequestProperty("Authorization","Bearer $credential")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code=c.responseCode
            val bytes=(if(code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
            check(bytes.size<=524288)
            val data=JSONObject(bytes.toString(Charsets.UTF_8))
            if(code !in 200..299) throw RelayFailure(code,data.optString("error","연결 오류"))
            return data
        } finally { c.disconnect() }
    }
    private fun io(path:String, body:JSONObject, done:(Result<JSONObject>)->Unit):Boolean {
        if(busy)return false
        busy=true; val credential=token
        worker.execute { val result=runCatching { http(path,body,credential) }; main.post { busy=false;done(result);changed() } }
        return true
    }
    fun create() {
        if(sharing||ending)return
        message="동승자 연결 준비 중"
        io("/api/sessions",JSONObject()) { result ->
            result.onSuccess { data ->
                session=data.getString("id");token=data.getString("token")
                val raw=data.getJSONObject("links")
                links=if(ending)emptyMap() else listOf("owner","family","guest","observer").filter{raw.has(it)}.associateWith { raw.getString(it) }
                connected=true;failures=0;message="함께 이동하는 사람에게만 링크를 알려 주세요";main.post(loop)
            }.onFailure { message="동승자 서버를 먼저 켜 주세요 (12번 실행 파일)";connected=false }
        };changed()
    }
    private val loop=object:Runnable {
        override fun run() {
            if(session.isEmpty())return
            if(busy){main.postDelayed(this,1000);return}
            val pending=outbox.entries.firstOrNull()
            val action=if(ending)"end" else if(pending!=null)"result" else "sync"
            io("/api/sessions/$session/$action",if(ending)JSONObject() else pending?.value?:state()) { result ->
                result.onSuccess { data ->
                    failures=0;connected=true
                    if(ending){clear();message="모든 동승자 연결을 종료했어요"}
                    else if(pending!=null){
                        outbox.remove(pending.key)
                        requests=requests.map{if(it.id==pending.key)it.copy(status=data.optString("status",it.status),message=data.optString("message",it.message))else it}
                    }
                    else{
                        pickup=data.optJSONObject("pickup")?:JSONObject()
                        val needs=data.optJSONArray("routeNeeds")?:JSONArray();routeNeeds=(0 until needs.length()).map{needs.getJSONObject(it)}
                        routeNeedsEvent=data.optJSONObject("routeNeedsEvent")?:JSONObject()
                        situation=data.optJSONObject("situation")?:JSONObject()
                        val routed=data.optJSONArray("careRoutedNoteIds")?:JSONArray();careRoutedNoteIds=(0 until routed.length()).map{routed.optString(it)}.toSet()
                        message="차량과 휴대폰 연결됨";requests=parseRequests(data.optJSONArray("requests")?:JSONArray())
                        val photos=data.optJSONArray("memories")?:JSONArray();memories=(0 until photos.length()).map{photos.getJSONObject(it)}
                        val photoIds=memories.map{it.optString("id")}.toSet()
                        if(memoryImages.keys.any{it !in photoIds})memoryImages=memoryImages.filterKeys{it in photoIds}
                        imageMisses.keys.retainAll(photoIds);loadMemoryImages()
                        permissions=data.optJSONObject("permissions")?:JSONObject()
                        permissionCatalog=com.familycare.ui.SeatAccess.objects(data.optJSONArray("permissionCatalog"))
                        val grants=data.optJSONArray("delegations")?:JSONArray();delegations=(0 until grants.length()).map{grants.getJSONObject(it)}
                        val receivers=data.optJSONArray("recipients")?:JSONArray();recipients=(0 until receivers.length()).map{receivers.getJSONObject(it)}
                        data.optJSONObject("links")?.let{raw->links=listOf("owner","family","guest","observer").filter{raw.has(it)}.associateWith{raw.getString(it)}}
                    }
                }.onFailure { e ->
                    connected=false;failures++
                    message=if(ending)"연결 종료 재시도 중 · 서버 연결을 확인하세요" else "동승자 서버 연결 끊김 · 자동 재연결 중"
                    if(e is RelayFailure&&e.code in listOf(403,410)){clear();message="연결이 만료됐어요. 새 연결을 시작하세요"}
                }
                if(session.isNotEmpty())main.postDelayed(this,if(failures>0)(2000L*(1L shl failures.coerceAtMost(4))).coerceAtMost(30000) else 2000)
            }
        }
    }
    private fun clear(){dismissed=emptySet();pickup=JSONObject();situation=JSONObject();careRoutedNoteIds=emptySet();routeNeeds=emptyList();routeNeedsEvent=JSONObject();permissionCatalog=emptyList();session="";token="";links=emptyMap();requests=emptyList();memories=emptyList();memoryDetail=null;memoryImages=emptyMap();imageMisses.clear();imagePreferred="";permissions=JSONObject();recipients=emptyList();delegations=emptyList();decisions.clear();outbox.clear();ending=false;connected=false;main.removeCallbacks(loop)}
    fun end(){if(session.isEmpty()&&!busy)return;ending=true;links=emptyMap();message="모든 동승자 연결 종료 중";main.removeCallbacks(loop);main.post(loop);changed()}
    fun decide(request:PassengerRequest,allowed:()->Boolean,accept:Boolean,approval:JSONObject?=null,execute:((String,String)->Unit)->Unit){
        if(ending||!connected||!allowed()||!request.valid()||request.status!="pending")return
        if(!decisions.add(request.id))return
        val deadline=android.os.SystemClock.elapsedRealtime()+4000
        fun attempt(){
            if(ending||!connected||!allowed()||requests.none{it.id==request.id&&it.status=="pending"}){decisions.remove(request.id);return}
            if(busy&&android.os.SystemClock.elapsedRealtime()<deadline){main.postDelayed({attempt()},200);return}
            decisions.remove(request.id)
            claimDecision(request,allowed,accept,approval,execute)
        }
        attempt()
    }
    private fun claimDecision(request:PassengerRequest,allowed:()->Boolean,accept:Boolean,approval:JSONObject?=null,execute:((String,String)->Unit)->Unit){
        if(!io("/api/sessions/$session/claim",JSONObject().put("id",request.id)){result->
            result.onSuccess{
                requests=requests.map{if(it.id==request.id)it.copy(status="executing")else it}
                val done:(String,String)->Unit={status,text->
                    outbox[request.id]=JSONObject().put("id",request.id).put("status",status).put("message",text).apply{if(approval!=null)put("approval",approval)}
                    requests=requests.map{if(it.id==request.id)it.copy(status=status,message=text)else it};changed()
                }
                if(!allowed())done("rejected","주행 상태가 바뀌어 보류했어요. 정차 후 다시 요청해 주세요")
                else if(!accept)done("rejected","운전자가 이번 요청을 보류했어요")
                else if(request.kind=="note")done("confirmed","운전자가 요청을 확인했어요. 차량 기능은 자동으로 바뀌지 않습니다")
                else execute(done)
            }.onFailure{message="요청 처리 결과 미확인 · 연결 후 목록을 다시 확인하세요"}
        }){message="연결 확인 중이에요. 잠시 후 다시 눌러 주세요";changed()}
    }
    fun shareMemory(body:JSONObject){
        if(!connected||ending)return
        if(!io("/api/sessions/$session/memories",JSONObject(body.toString()).put("client","vehicle")){r->
            message=if(r.isSuccess)"추억 사진을 가족 지도에 공유했어요" else "사진 공유를 확인하지 못했어요. 다시 시도하세요"
        }){message="연결 확인 중이에요. 잠시 후 공유를 눌러 주세요";changed()}
    }
    fun deleteMemory(id:String){
        if(connected&&!ending)io("/api/sessions/$session/delete-memory",JSONObject().put("id",id)){r->
            if(r.isSuccess)memories=memories.filterNot{it.optString("id")==id}
            message=if(r.isSuccess)"사진 공유를 삭제했어요" else "삭제 결과를 확인하지 못했어요"
        }
    }
    private fun parseRequests(a:JSONArray)=(0 until a.length()).mapNotNull{i->runCatching{
        val o=a.getJSONObject(i)
        PassengerRequest(o.getString("id"),o.getString("kind"),o.getString("title"),
            if(o.has("latitude"))o.getDouble("latitude")else null,if(o.has("longitude"))o.getDouble("longitude")else null,
            o.getString("status"),o.optString("message"),o.optInt("votes"),if(o.has("target"))o.getDouble("target")else null,o.optString("originRole","family"),o.optJSONObject("options")?.toString()?:"{}",o.optString("deviceId"),o.optString("authorityEpoch"),o.optBoolean("delegated"))
    }.getOrNull()?.takeIf{it.valid()}}
}

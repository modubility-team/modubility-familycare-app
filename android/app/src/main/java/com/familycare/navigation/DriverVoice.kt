package com.familycare.navigation

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import androidx.compose.runtime.*
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.*

/** Button-triggered Korean recognition; no recordings, remote speech server, or wake-word capture. */
class DriverVoice(private val context:Context,private val care:CareRuntime){
    private val main=Handler(Looper.getMainLooper())
    private val audio=context.getSystemService(AudioManager::class.java)
    private var focus:AudioFocusRequest?=null
    private var engine:SpeechToText?=null
    private var listener:ResultListener?=null
    private var generation=0
    private var timeout:Runnable?=null
    var open by mutableStateOf(false);private set
    var listening by mutableStateOf(false);private set
    var ready by mutableStateOf(false);private set
    var text by mutableStateOf("");private set
    var status by mutableStateOf("");private set
    var level by mutableStateOf(0f);private set
    var selected by mutableStateOf<PassengerRequest?>(null);private set
    var lastDecisionId="";private set
    private var decisionAt=0L
    private val routeKinds=setOf("pickup","destination","waypoint")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var interpretation:Job?=null
    private var interpretSequence=0
    private var explicitSelection=false
    var interpreting by mutableStateOf(false);private set
    var choices by mutableStateOf(emptyList<PassengerRequest>());private set
    /** Search results for a spoken destination, read out with numbers when the match is not unambiguous. */
    var places by mutableStateOf(emptyList<com.familycare.ui.FoundPlace>());private set
    private var placesAt=0L
    private var placesAsStop=false
    private var choiceIntent=""
    private var choiceAt=0L
    private var choiceRoute=""
    val pending get()=care.relay.requests.filter{it.status=="pending"&&(!it.delegated||it.kind in routeKinds)}
    private val routeContext get()=if(choices.isNotEmpty())choices else ((if(explicitSelection)listOfNotNull(selected) else emptyList())+pending).filter{it.kind in routeKinds}.distinctBy{it.id}.take(5)
    private fun cancelInterpretation(){interpretSequence++;interpretation?.cancel();interpretation=null;interpreting=false}
    fun dispose(){close();scope.cancel()}
    fun show(){cancelCapture();cancelInterpretation();choices=emptyList();choiceIntent="";places=emptyList();explicitSelection=false;selected=pending.firstOrNull{it.kind=="pickup"}?:pending.firstOrNull{it.kind in routeKinds}?:pending.firstOrNull();text="";status="";open=true}
    fun select(r:PassengerRequest){cancelCapture();cancelInterpretation();choices=emptyList();choiceIntent="";places=emptyList();explicitSelection=true;selected=r;text="";status=""}
    fun close(){cancelCapture();cancelInterpretation();choices=emptyList();choiceIntent="";places=emptyList();open=false}
    private fun cancelCapture(){
        generation++;listening=false;ready=false;level=0f;timeout?.let(main::removeCallbacks);timeout=null
        val e=engine;engine=null;listener?.let{runCatching{e?.removeListener(it)}};listener=null
        runCatching{e?.stop()};runCatching{e?.release()};care.voiceCapture(false)
        focus?.let{audio.abandonAudioFocusRequest(it)};focus=null
    }
    private fun finish(message:String){cancelCapture();status=message}
    private fun performDecision(snapshot:PassengerRequest?,accept:Boolean,routeVersion:String=care.navigation.routeIdentity){
        val current=pending.firstOrNull{it.id==snapshot?.id}
        if(snapshot==null){status="처리할 요청을 먼저 선택해 주세요";return}
        if(!DriverVoicePolicy.unchanged(snapshot,current)){status="요청이 변경되거나 이미 처리됐어요. 다시 선택하세요";return}
        if(snapshot.kind in routeKinds&&routeVersion!=care.navigation.routeIdentity){status="안내 경로가 바뀌었어요. 새 경로에서 다시 요청해 주세요";care.voiceFeedback(status);return}
        if(!care.canVoiceDecide(current!!)){status="차량 연결과 이 요청의 정차 조건을 확인해 주세요";care.voiceFeedback(status);return}
        val approval=if(accept&&current.kind=="permission")JSONObject(current.options).let{
            JSONObject().put("name",it.optString("name")).put("seat",it.optString("seat")).put("scopes",it.getJSONArray("scopes")).put("limits",it.optJSONObject("limits")?:JSONObject())
        }else null
        choices=emptyList();choiceIntent="";selected=current
        lastDecisionId=current.id;decisionAt=android.os.SystemClock.elapsedRealtime()
        care.decide(current,accept,approval,voice=true,routeAsStop=accept&&current.kind in routeKinds)
        status=if(!accept)"거절 처리 결과를 확인하고 있어요" else if(current.kind in routeKinds)"${placeName(current)} 경유 경로를 계산하고 있어요" else "차량 응답을 확인하고 있어요"
        if(accept&&current.kind in routeKinds)care.voiceFeedback(status)
    }
    private fun placeName(r:PassengerRequest)=if(r.kind=="pickup")runCatching{JSONObject(r.options).optString("landmark",r.title)}.getOrDefault(r.title) else r.title
    private fun interpretRoute(spoken:String,snapshot:PassengerRequest?,context:List<PassengerRequest>,routeVersion:String){
        if(choices.isNotEmpty()&&(android.os.SystemClock.elapsedRealtime()-choiceAt>60000||choiceRoute!=routeVersion)){
            choices=emptyList();choiceIntent="";status="장소 선택 시간이 지났거나 경로가 바뀌었어요. 경유 요청부터 다시 말해 주세요";care.voiceFeedback(status);return
        }
        val sequence=++interpretSequence
        val body=JSONObject().put("text",spoken).put("candidateIds",org.json.JSONArray(context.map{it.id}))
            .put("selectedId",if(explicitSelection&&choices.isEmpty())snapshot?.id?:"" else "").put("choiceIntent",choiceIntent)
        interpreting=true;status="확인하고 있어요"
        interpretation=scope.launch{
            val result=withContext(Dispatchers.IO){runCatching{care.relay.mapGateway().call("voice-interpret",body)}}
            if(sequence!=interpretSequence||!open)return@launch
            interpreting=false
            if(routeVersion!=care.navigation.routeIdentity){choices=emptyList();choiceIntent="";status="안내 경로가 바뀌었어요. 다시 말해 주세요";care.voiceFeedback(status);return@launch}
            result.onSuccess{data->
                val intent=data.optString("intent")
                if(intent in listOf("add_stop","pickup")){
                    val request=context.firstOrNull{it.id==data.optString("targetId")}
                    if(request==null||intent=="pickup"&&request.kind!="pickup"){status="경유할 장소를 확인하지 못했어요";return@onSuccess}
                    performDecision(request,true,routeVersion)
                }else{
                    val ids=data.optJSONArray("candidates")?:org.json.JSONArray()
                    choices=(0 until ids.length()).mapNotNull{i->context.firstOrNull{it.id==ids.optString(i)}}
                    choiceIntent=if(choices.isNotEmpty())data.optString("choiceIntent") else ""
                    choiceAt=android.os.SystemClock.elapsedRealtime();choiceRoute=routeVersion;explicitSelection=false
                    status=data.optString("message","장소와 행동을 다시 말해 주세요")
                    val names=choices.mapIndexed{i,r->"${i+1}번 ${placeName(r)}"}.joinToString(", ")
                    care.voiceFeedback(status+if(names.isNotBlank())" $names. 마이크를 누르고 번호로 말해 주세요." else "")
                }
            }.onFailure{status=it.message?:"노트북의 음성 이해 연결을 확인해 주세요";care.voiceFeedback(status)}
        }
    }
    internal fun submitFinal(spoken:String,snapshot:PassengerRequest?=selected,context:List<PassengerRequest> = routeContext,routeVersion:String=care.navigation.routeIdentity){
        cancelCapture();text=spoken.take(240);observeResult()
        if(lastDecisionId.isNotBlank()){status="앞선 요청의 처리 결과를 확인 중이에요";return}
        val command=DriverVoicePolicy.command(spoken)
        if(places.isNotEmpty()&&command!=VoiceCommand.CANCEL&&command!=VoiceCommand.REPEAT){answerPlace(spoken,command);return}
        when(command){
            VoiceCommand.APPROVE,VoiceCommand.REJECT->if(choices.isNotEmpty()){
                status="어느 장소인지 번호나 이름을 먼저 말해 주세요";care.voiceFeedback(status)
            }else performDecision(snapshot,command==VoiceCommand.APPROVE,routeVersion)
            VoiceCommand.PICKUP->if(context.count{it.kind=="pickup"}==1&&snapshot?.kind=="pickup")performDecision(snapshot,true,routeVersion)else interpretRoute(spoken,snapshot,context,routeVersion)
            VoiceCommand.CANCEL->{cancelInterpretation();choices=emptyList();choiceIntent="";places=emptyList();status="음성 입력을 취소했어요";open=false}
            VoiceCommand.READ->{status=selected?.let{summary(it)}?:"대기 중인 요청이 없어요";care.voiceFeedback(status)}
            VoiceCommand.NEXT->{val list=pending;selected=list.getOrNull((list.indexOfFirst{it.id==selected?.id}+1).mod(list.size.coerceAtLeast(1)));explicitSelection=true;choices=emptyList();choiceIntent="";status=selected?.let{summary(it)}?:"대기 중인 요청이 없어요";care.voiceFeedback(status)}
            VoiceCommand.REPEAT->{care.navigation.repeatInstruction();status="이번 길안내를 다시 읽어드릴게요"}
            VoiceCommand.UNKNOWN->{
                // "강남역으로 안내해 줘": the driver's own destination. Family stops and pickups keep the semantic interpreter.
                val destination=if(choices.isEmpty())VoiceDestination.parse(spoken) else null
                when{
                    destination!=null->findDestination(destination)
                    // No family place is waiting, so the stop interpreter could only ask again.
                    context.isEmpty()->{status="알아듣지 못했어요. 다시 말해 주세요";care.voiceFeedback(status)}
                    else->interpretRoute(spoken,snapshot,context,routeVersion)
                }
            }
        }
    }
    private fun findDestination(request:VoiceDestination.Request){
        cancelInterpretation();choices=emptyList();choiceIntent="";places=emptyList()
        val nav=care.navigation
        // A spoken search during guidance adds a stop and keeps the final destination; "가자/안내해 줘" replaces it.
        val asStop=request.searchOnly&&nav.target!=null
        VoiceDestination.savedSlot(request.query)?.let{slot->
            val saved=nav.places.saved().firstOrNull{it.name==slot}
            if(saved==null){status="$slot 위치가 아직 저장돼 있지 않아요. 목적지 선택 화면에서 ‘$slot 등록’을 먼저 해 주세요";care.voiceFeedback(status)}
            else guideTo(slot,saved.point,asStop)
            return
        }
        val center=nav.fix?.point?.takeIf{it.valid()}?:GeoPoint(com.familycare.ui.MapPlaces.LATITUDE,com.familycare.ui.MapPlaces.LONGITUDE)
        val sequence=++interpretSequence
        interpreting=true;status="‘${request.query}’ 장소를 찾고 있어요"
        interpretation=scope.launch{
            val result=withContext(Dispatchers.IO){runCatching{
                val body=JSONObject().put("query",request.query).put("facility","").put("consent",true)
                    .put("center",org.json.JSONArray(listOf(center.longitude,center.latitude)))
                val list=care.relay.mapGateway().call("maps-search",body).getJSONArray("places")
                (0 until list.length()).map{i->list.getJSONObject(i).let{p->com.familycare.ui.FoundPlace(p.getString("name"),p.getDouble("latitude"),p.getDouble("longitude"),p.optString("detail"))}}
                    .filter{com.familycare.ui.MapPlaces.valid(it.latitude,it.longitude)}
            }}
            if(sequence!=interpretSequence||!open)return@launch
            interpreting=false
            result.onSuccess{found->
                val ranked=VoiceDestination.rank(request.query,found){it.name}
                val direct=if(request.searchOnly)null else VoiceDestination.direct(request.query,ranked){it.name}
                when{
                    ranked.isEmpty()->{status="‘${request.query}’ 검색 결과가 없어요. 장소 이름을 다시 말해 주세요";care.voiceFeedback(status)}
                    direct!=null->guideTo(direct.name,GeoPoint(direct.latitude,direct.longitude),asStop)
                    else->{
                        places=ranked.take(3);placesAt=android.os.SystemClock.elapsedRealtime();placesAsStop=asStop
                        status="‘${request.query}’ 검색 결과예요. "+(if(asStop)"경유할" else "갈")+" 곳의 번호를 말해 주세요"
                        readPlaces()
                    }
                }
            }.onFailure{status=it.message?:"장소 검색 연결을 확인해 주세요";care.voiceFeedback(status)}
        }
    }
    private fun readPlaces(){care.voiceFeedback("후보 ${places.size}곳이에요. "+places.mapIndexed{i,p->"${i+1}번 ${p.name}"}.joinToString(", ")+". 마이크를 누르고 "+(if(placesAsStop)"경유할" else "갈")+" 곳의 번호를 말해 주세요.")}
    private fun answerPlace(spoken:String,command:VoiceCommand){
        val expired=android.os.SystemClock.elapsedRealtime()-placesAt>60000
        val index=if(expired)null else VoiceDestination.choice(spoken,places.map{it.name})
        val place=index?.let{places.getOrNull(it)}
        val again=if(place==null&&command==VoiceCommand.UNKNOWN)VoiceDestination.parse(spoken) else null
        when{
            place!=null->guideTo(place.name,GeoPoint(place.latitude,place.longitude),placesAsStop)
            again!=null->findDestination(again)
            expired->{places=emptyList();status="장소를 고를 시간이 지났어요. 목적지를 다시 말해 주세요";care.voiceFeedback(status)}
            command==VoiceCommand.READ->readPlaces()
            else->{status=if(index!=null)"그 번호의 장소는 없어요. 1번부터 ${places.size}번 중에서 말해 주세요" else "번호나 장소 이름으로 골라 주세요";care.voiceFeedback(status)}
        }
    }
    /** Parked drivers may also tap a listed result. */
    fun pickPlace(index:Int){places.getOrNull(index)?.let{cancelCapture();guideTo(it.name,GeoPoint(it.latitude,it.longitude),placesAsStop)}}
    private fun guideTo(name:String,point:GeoPoint,asStop:Boolean){
        places=emptyList()
        val nav=care.navigation
        if(asStop&&nav.target!=null){
            status="$name 경유 경로를 찾고 있어요"
            nav.planSpokenStop(name,point){_,detail->status=detail;care.voiceFeedback(status)}
            return
        }
        status="$name${VoiceDestination.ro(name)} 가는 경로를 찾고 있어요"
        nav.plan(name,point){ok,detail->
            if(ok){nav.start();status=if(nav.startRequested)"$name${VoiceDestination.ro(name)} 안내를 시작합니다" else nav.message}else status=detail
            care.voiceFeedback(status)
        }
    }
    fun summary(r:PassengerRequest):String=buildString{
        append(r.title.take(120))
        if(r.kind=="pickup")append(if(care.navigation.target!=null)" · 경유지로 추가" else " · 픽업 장소로 안내")
        if(r.kind in setOf("destination","waypoint"))append(if(care.navigation.target!=null)" · 경유지로 추가" else " · 이 장소로 안내")
        if(r.kind=="climate")append(" · ${r.target}도")
        if(r.kind=="permission")runCatching{
            val o=JSONObject(r.options);append(" · "+(com.familycare.ui.SeatAccess.seats[o.optString("seat")]?:o.optString("seat")))
            val scopes=o.optJSONArray("scopes")
            if(scopes!=null)for(i in 0 until scopes.length()){
                val key=scopes.getString(i);append("\n"+(care.relay.permissionCatalog.firstOrNull{it.optString("id")==key}?.optString("label")?:key))
                o.optJSONObject("limits")?.optJSONObject(key)?.let{append(" ${it.optDouble("min")}~${it.optDouble("max")}")}
        }
    }}
    private val hybridConfigured get()=BuildConfig.PLEOS_STT_CLIENT_ID.isNotBlank()&&BuildConfig.PLEOS_STT_CLIENT_SECRET.isNotBlank()
    /** Voice-data notice for the docs and a privacy screen; the voice window itself shows no notices. */
    val privacyNotice:String get()=if(hybridConfigured)
        "HYBRID 설정 사용 중: ‘말하기’를 누른 동안 음성이 Pleos Speech로 전송될 수 있어요. 서버 연결이 안 되면 기기 내 인식으로 전환합니다. 앱은 녹음을 저장하지 않습니다. 인식된 문장은 연결된 노트북에서 요청 뜻을 확인합니다."
    else "Pleos Client ID/Secret을 설정하면 HYBRID 음성 인식을 사용할 수 있어요. 현재는 기기 내 한국어 인식만 사용합니다. 앱은 녹음을 저장하지 않으며, 인식된 문장은 연결된 노트북에서 요청 뜻을 확인합니다."

    private fun postCurrent(serial:Int,action:()->Unit){main.post{if(generation==serial&&listening)action()}}
    private fun initializeSdk(sdk:SpeechToText,serial:Int,snapshot:PassengerRequest?,contextSnapshot:List<PassengerRequest>,routeVersion:String,hybrid:Boolean,pcm:ByteArray?){
        engine=sdk
        sdk.initialize(object:OnInitializeListener{
            override fun onSuccess(){postCurrent(serial){
                try{
                    sdk.setLocale(Locale.KOREA)
                    if(hybrid){
                        status="준비 중…"
                        sdk.registerApp(BuildConfig.PLEOS_STT_CLIENT_ID,BuildConfig.PLEOS_STT_CLIENT_SECRET,object:OnServerConnectionListener{
                            override fun onConnected(){postCurrent(serial){if(engine===sdk)startRecognition(sdk,serial,snapshot,contextSnapshot,routeVersion,true,pcm)}}
                            override fun onFailed(errorType:ErrorType){postCurrent(serial){fallbackToOnDevice(serial,snapshot,contextSnapshot,routeVersion,pcm,"Pleos Speech 서버 연결 실패 ($errorType)")}}
                        })
                    }else startRecognition(sdk,serial,snapshot,contextSnapshot,routeVersion,false,pcm)
                }catch(_:Exception){if(hybrid)fallbackToOnDevice(serial,snapshot,contextSnapshot,routeVersion,pcm,"HYBRID 초기화 실패")else finish("음성 인식을 시작하지 못했어요")}
            }}
            override fun onFailure(errorType:ErrorType){postCurrent(serial){if(hybrid)fallbackToOnDevice(serial,snapshot,contextSnapshot,routeVersion,pcm,"HYBRID 초기화 실패 ($errorType)")else{android.util.Log.w("DriverVoice","On-device init failed: $errorType");finish("음성 인식을 시작하지 못했어요")}}}
        })
    }
    private fun fallbackToOnDevice(serial:Int,snapshot:PassengerRequest?,contextSnapshot:List<PassengerRequest>,routeVersion:String,pcm:ByteArray?,reason:String){
        if(generation!=serial||!listening)return
        val old=engine;val oldListener=listener;listener=null;engine=null
        if(oldListener!=null)runCatching{old?.removeListener(oldListener)}
        runCatching{old?.stop()};runCatching{old?.release()}
        ready=false;level=0f;status="준비 중…";android.util.Log.i("DriverVoice","$reason; retrying on-device")
        try{initializeSdk(SpeechToText(context.applicationContext,Mode.ON_DEVICE),serial,snapshot,contextSnapshot,routeVersion,false,pcm)}
        catch(_:Exception){finish("음성 인식을 시작하지 못했어요")}
    }
    private fun startRecognition(sdk:SpeechToText,serial:Int,snapshot:PassengerRequest?,contextSnapshot:List<PassengerRequest>,routeVersion:String,hybrid:Boolean,pcm:ByteArray?){
        val events=object:ResultListener{
            override fun onReady(){postCurrent(serial){ready=true;status="듣고 있어요";android.util.Log.i("DriverVoice",if(hybrid)"Listening (HYBRID)" else "Listening (ON_DEVICE)")}}
            override fun onStartedRecognition(){postCurrent(serial){ready=true;status="듣고 있어요"}}
            override fun onUpdated(stt:String,completed:Boolean){postCurrent(serial){if(completed)submitFinal(stt,snapshot,contextSnapshot,routeVersion)else text=stt.take(160)}}
            override fun onUpdatedEpdData(on:Long,off:Long){}
            override fun onUpdatedRms(rms:Double){postCurrent(serial){level=(rms/3000).toFloat().coerceIn(0f,1f)}}
            override fun onEndedRecognition(){postCurrent(serial){main.postDelayed({if(generation==serial&&listening)finish("말씀을 듣지 못했어요")},500)}}
            override fun onError(errorType:ErrorType){postCurrent(serial){if(hybrid)fallbackToOnDevice(serial,snapshot,contextSnapshot,routeVersion,null,"Pleos Speech 인식 오류 ($errorType)")else{android.util.Log.w("DriverVoice","On-device recognition error: $errorType");finish("음성 인식에 실패했어요")}}}
        }
        try{listener=events;sdk.addListener(events);if(pcm==null)sdk.request()else{sdk.sendAudio(pcm);sdk.completeAudioSend()}}
        catch(_:Exception){if(hybrid)fallbackToOnDevice(serial,snapshot,contextSnapshot,routeVersion,pcm,"Pleos Speech 요청 실패")else finish("음성 인식을 시작하지 못했어요")}
    }
    /** Button-triggered Korean recognition; HYBRID is optional and falls back to the device engine. */
    internal fun start(pcm:ByteArray?=null){
        if(interpreting){status="확인하고 있어요";return}
        cancelCapture()
        if(!care.engine.ux.available){status="차량 연결 상태를 확인해 주세요";return}
        open=true;text="";status="준비 중…";listening=true
        care.voiceCapture(true)
        val serial=generation;val snapshot=selected;val contextSnapshot=routeContext.toList();val routeVersion=care.navigation.routeIdentity
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({change->if(change<0&&generation==serial)finish("다른 차량 소리가 우선되어 음성 입력을 중지했어요")},main).build()
        if(audio.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){finish("마이크를 사용할 수 없어요. 잠시 후 다시 시도하세요");return};focus=request
        timeout=Runnable{if(generation==serial)finish("음성이 들리지 않았어요")}.also{main.postDelayed(it,15000)}
        val useHybrid=hybridConfigured&&pcm==null
        try{
            status="준비 중…"
            initializeSdk(SpeechToText(context.applicationContext,if(useHybrid)Mode.HYBRID else Mode.ON_DEVICE),serial,snapshot,contextSnapshot,routeVersion,useHybrid,pcm)
        }catch(_:Exception){if(useHybrid)fallbackToOnDevice(serial,snapshot,contextSnapshot,routeVersion,pcm,"Pleos Speech 서비스를 사용할 수 없음")else finish("차량 음성 서비스를 시작하지 못했어요")}
    }
    fun toggle(){if(listening)finish("음성 입력을 중지했어요")else start()}
    fun observeResult(){
        if(lastDecisionId.isBlank())return
        if(android.os.SystemClock.elapsedRealtime()-decisionAt>65000){lastDecisionId="";status="처리 결과가 아직 확인되지 않았어요. 연결 상태와 요청 목록을 확인하세요";return}
        if(!care.relay.connected||care.relay.decisionPending(lastDecisionId))return
        val r=care.relay.requests.firstOrNull{it.id==lastDecisionId}?:return
        if(r.status in listOf("confirmed","rejected","needs_review")){
            lastDecisionId="";status=r.message.ifBlank{if(r.status=="confirmed")"요청을 처리했어요" else "요청을 처리하지 못했어요"};care.voiceFeedback(status)
        }
    }
}

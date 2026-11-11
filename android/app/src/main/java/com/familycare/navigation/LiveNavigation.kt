package com.familycare.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.max

/** Foreground-only route ownership and guidance. Route sharing is separately opt-in through CareRuntime. */
class LiveNavigation(context:Context,private val changed:()->Unit,private val speak:(String)->Boolean,private val stopVoice:()->Unit,
    private val gateway:(()->com.familycare.ui.MapGateway)?=null,private val parked:()->Boolean={false}){
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val follower=RouteFollower()
    @Volatile private var generation=0
    private var lastNetworkRequest=-30_000L
    var guidanceSession="";private set
    private var foreground=false
    private var pendingStart=false
    private var lastFetch=-30_000L
    private var routeReceivedElapsed=-1L
    private var spoken=""
    var target:GeoPoint?=null;private set
    private var targetName=""
    private var waypoint:GeoPoint?=null
    private var lastWaypoint:GeoPoint?=null
    private var waypointVisited=false
    var routeChange="";private set
    var routeIdentity="";private set
    var useTmap=gateway!=null;private set
    fun selectProvider(tmap:Boolean){if(useTmap!=tmap){cancel();useTmap=tmap;changed()}}
    val providerLabel get()=if(useTmap)"TMAP" else "OSRM / OpenStreetMap"
    fun searchRoute():org.json.JSONArray{
        val r=route?:return org.json.JSONArray()
        val first=r.cumulative.indexOfFirst{it>=progress}.coerceAtLeast(0)
        val points=r.points.drop(first)
        if(points.size<2)return org.json.JSONArray()
        val step=kotlin.math.ceil(points.size/200.0).toInt().coerceAtLeast(1)
        return org.json.JSONArray(points.filterIndexed{i,_->i%step==0||i==points.lastIndex}.map{listOf(it.longitude,it.latitude)})
    }
    fun summary():org.json.JSONObject=org.json.JSONObject().put("destination",route?.destination?:"").put("change",routeChange)
        .put("provider",route?.provider?:providerLabel).put("remainingRoute",searchRoute())
        .put("waypoint",lastWaypoint?.let{org.json.JSONObject().put("lat",it.latitude).put("lon",it.longitude).put("visited",waypointVisited)})
        .put("points",org.json.JSONArray(route?.points?.let{p->val step=(p.size/200).coerceAtLeast(1);p.filterIndexed{i,_->i%step==0||i==p.lastIndex}.map{org.json.JSONObject().put("lat",it.latitude).put("lon",it.longitude)}}?:emptyList<Any>()))
    private var pendingCompletion:((Boolean,String)->Unit)?=null
    private var lastLocationElapsed=-1L
    private var focus:NavigationFocus?=null
    private val context=context.applicationContext
    private val preferences=context.getSharedPreferences("navigation-settings",Context.MODE_PRIVATE)
    val places=NavigationPlaces(context)
    var option=RouteOption.from(preferences.getInt("route-option",0));private set
    var voiceEnabled=preferences.getBoolean("voice",true);private set
    val hasWaypoint get()=waypoint!=null
    val currentRoad get()=if(locationUsable&&!outside)route?.roadAt(progress).orEmpty()else ""
    fun toggleVoice(){voiceEnabled=!voiceEnabled;preferences.edit().putBoolean("voice",voiceEnabled).apply();if(!voiceEnabled)stopVoice();changed()}
    private fun announce(text:String)=if(voiceEnabled)speak(text)else true
    fun repeatInstruction(){
        val turn=nextTurn
        if(!guiding||outside||loading||turn==null)return
        if(!voiceEnabled){message="음성 안내를 켜 주세요";changed();return}
        speak("${guidanceDistance((turn.along-progress).coerceAtLeast(0.0))} 앞에서 ${turn.instruction}")
    }
    fun setOption(value:RouteOption){option=value;preferences.edit().putInt("route-option",value.code).apply();changed()}
    fun recalculate(value:RouteOption=option,removeWaypoint:Boolean=false){
        if(!foreground||!locationUsable||target==null||loading||now()-lastFetch<2000){message="현재 위치를 확인하고 잠시 후 다시 시도하세요";changed();return}
        setOption(value)
        fetch(active,{ok,_->if(ok&&removeWaypoint){lastWaypoint=null;waypointVisited=false;changed()}},if(removeWaypoint)null else waypoint)
    }
    var fix:PositionFix?=null;private set
    var route:RoadRoute?=null;private set
    var active=false;private set
    /** True once guidance is running or waiting only for the navigation focus callback. */
    val startRequested get()=pendingStart||active
    var loading=false;private set
    var arrived=false;private set
    var message="현재 위치를 확인해 주세요";private set
    private var locationMessage="정확한 위치 권한이 필요해요"
    val gps=LocationTracker(context){position,status->
        if(position==null||position.elapsed>=lastLocationElapsed){fix=position;if(position!=null)lastLocationElapsed=position.elapsed}
        locationMessage=status;changed()
    }
    val locationUsable get()=fix?.usable(now())==true
    val locationStatus get()=when{
        !gps.permitted->"정확한 위치 권한 필요"
        fix==null->locationMessage
        !locationUsable->if(fix!!.accuracy>60)"GPS 정확도 낮음 · 안내 대기" else "GPS 신호 끊김 · 안내 대기"
        else->locationMessage
    }
    val guiding get()=active&&foreground&&locationUsable&&!arrived
    val progress get()=follower.along
    val outside get()=follower.offRoute
    val remainingMeters get()=route?.let{(it.meters-follower.along).coerceAtLeast(0.0)}
    val remainingSeconds get()=route?.remainingSeconds(follower.along)
    /**
     * The family's designated rest stop as the navigation sees it: on the route while it is the current waypoint
     * (with the estimated time to reach it), arrived once the waypoint was passed.
     */
    fun stopStatus(stop:GeoPoint):org.json.JSONObject{
        val r=route;val w=waypoint
        val onRoute=r!=null&&w!=null&&w.distance(stop)<=150
        val eta=if(onRoute&&locationUsable)(r!!.remainingSeconds(follower.along)-r.remainingSeconds(r.project(w!!).along)).coerceAtLeast(0.0) else null
        val arrived=!onRoute&&waypointVisited&&lastWaypoint?.let{it.distance(stop)<=150}==true
        return org.json.JSONObject().put("latitude",stop.latitude).put("longitude",stop.longitude).put("onRoute",onRoute)
            .put("etaSeconds",eta?:org.json.JSONObject.NULL).put("arrived",arrived)
    }
    val nextTurn get()=route?.instruction(follower.along)
    val followingTurn get()=route?.followingTurn(follower.along)
    /** Snap only a fresh, nearby fix; retain raw GPS when leaving the route. */
    val displayPosition:com.familycare.ui.MapPosition? get(){
        val current=fix?:return null
        val path=route
        val projection=path?.project(current.point,progress.takeIf{it>0})
        val onPath=active&&locationUsable&&!outside&&projection!=null&&projection.offRoute<=kotlin.math.min(30.0,kotlin.math.max(12.0,current.accuracy.toDouble()))
        val point=if(onPath)path!!.pointAt(projection!!.along)else current.point
        val heading=if(onPath)path!!.headingAt(projection!!.along)else current.bearing?.takeIf{it.isFinite()}
        return com.familycare.ui.MapPosition(point.latitude,point.longitude,heading,locationUsable,
            navigation=active,fixElapsedMillis=current.elapsed,routeProgressMeters=if(onPath)projection!!.along else null,speedMetersPerSecond=current.speed,turnDistance=nextTurn?.let{(it.along-progress).coerceAtLeast(0.0)})
    }
    private fun now()=SystemClock.elapsedRealtime()
    fun foreground(){
        foreground=true;gps.start()
        if(focus==null)focus=NavigationFocus(context){owned->
            if(owned&&pendingStart&&foreground&&route!=null&&locationUsable){active=true;guidanceSession=java.util.UUID.randomUUID().toString();pendingStart=false;message="길안내 중";announce("길안내를 시작합니다.")}
            else if(!owned){if(active){message="안내가 일시정지됐어요. 안내 재개를 눌러 주세요"};active=false;pendingStart=false;stopVoice()}
            changed()
        }
        main.removeCallbacks(tick);main.post(tick)
    }
    fun background(){
        foreground=false;gps.stop();main.removeCallbacks(tick);pause()
        if(loading){generation++;loading=false;message="화면을 벗어나 경로 검색을 취소했어요";finishRequest(false,message);changed()}
    }
    fun permissionChanged(){gps.stop();gps.start();changed()}
    fun plan(name:String,end:GeoPoint,complete:(Boolean,String)->Unit={_,_->}){
        if(!foreground||!locationUsable){message="새 GPS 위치를 받은 뒤 경로를 찾을 수 있어요";changed();complete(false,message);return}
        if(!end.valid()||loading){complete(false,"목적지 좌표를 확인하고 진행 중인 경로 검색이 끝난 뒤 다시 시도해 주세요");return}
        if(now()-lastFetch<2000){message="잠시 뒤 다시 검색해 주세요";changed();complete(false,message);return}
        if(fix!!.point.distance(end)<30){message="목적지가 현재 위치와 너무 가까워요";changed();complete(false,message);return}
        pause();fetch(false,complete,via=null,newTarget=end,newName=name)
    }
    fun planWaypoint(point:GeoPoint,complete:(Boolean,String)->Unit){
        if(!point.valid()){complete(false,"경유지 좌표가 올바르지 않아요");return}
        if(route==null||target==null||!locationUsable||loading||!foreground){complete(false,"목적지 경로와 현재 GPS를 먼저 확인해 주세요");return}
        if(now()-lastFetch<2000){complete(false,"잠시 뒤 다시 승인해 주세요");return}
        pause();fetch(false,complete,point)
    }
    /** A pickup never replaces the final destination or an existing stop implicitly. */
    fun planPickup(name:String,point:GeoPoint,voice:Boolean=false,complete:(Boolean,String)->Unit)=planStop(name,point,voice,"픽업",complete)
    fun planSuggestedStop(name:String,point:GeoPoint,voice:Boolean=false,complete:(Boolean,String)->Unit)=planStop(name,point,voice,"추천 장소",complete)
    /** A place the driver searched by voice during guidance: added as the stop, final destination kept. */
    fun planSpokenStop(name:String,point:GeoPoint,complete:(Boolean,String)->Unit)=planStop(name,point,true,"검색한 장소",complete)
    private fun planStop(name:String,point:GeoPoint,voice:Boolean,label:String,complete:(Boolean,String)->Unit){
        if(!point.valid()){complete(false,"$label 위치를 다시 확인해 주세요");return}
        val retaining=target!=null&&route!=null
        if(retaining&&waypoint!=null&&waypoint!!.distance(point)>30){
            complete(false,"이미 경유지가 있어요. 기존 경유지를 마친 뒤 $label 요청을 다시 승인해 주세요");return
        }
        val completed:(Boolean,String)->Unit={ok,detail->
            if(ok){
                val continued=active
                if(voice&&!active)start()
                complete(true,(if(retaining)"최종 목적지를 유지하고 $name 경유지를 추가했어요" else "$name 장소로 경로를 설정했어요")+
                    if(continued)" · 안내를 계속합니다" else if(voice)" · 안내 시작을 요청했어요" else " · 안내 시작을 눌러 주세요")
            }else complete(false,detail)
        }
        if(!retaining){plan(name,point,completed);return}
        if(!locationUsable||loading||!foreground){complete(false,"현재 GPS와 경로 검색 상태를 확인해 주세요");return}
        if(now()-lastFetch<2000){complete(false,"잠시 뒤 다시 승인해 주세요");return}
        // Keep current guidance and destination intact while a replacement route is calculated.
        fetch(active,completed,point)
    }
    private fun fetch(reroute:Boolean,complete:(Boolean,String)->Unit={_,_->},via:GeoPoint?=waypoint,newTarget:GeoPoint?=null,newName:String?=null){
        val start=fix?.takeIf{it.usable(now())}?.point
        val end=newTarget?:target
        if(start==null||end==null){complete(false,"현재 위치와 목적지를 다시 확인해 주세요");return}
        finishRequest(false,"새 경로 검색으로 이전 요청을 취소했어요")
        pendingCompletion=complete
        loading=true;lastFetch=now();val request=++generation
        val requestedName=newName?:targetName;val requestedVia=via
        val requestedTmap=useTmap
        val requestedOption=option
        message=if(reroute)"경로를 다시 찾고 있어요" else "자동차 경로를 찾고 있어요";changed()
        worker.execute{
            if(request!=generation)return@execute
            // Enforce the provider's request ceiling even after cancelling a slow queued request.
            val delay=1000-(now()-lastNetworkRequest)
            if(delay>0)SystemClock.sleep(delay)
            if(request!=generation)return@execute
            lastNetworkRequest=now()
            val result=runCatching{
                if(!requestedTmap)RoutingClient.fetch(start,end,requestedName,requestedVia)
                else{
                    val client=checkNotNull(gateway){"TMAP 서버 연결이 필요해요"}.invoke()
                    val body=org.json.JSONObject().put("consent",true)
                        .put("routeOption",requestedOption.code)
                        .put("start",org.json.JSONArray(listOf(start.longitude,start.latitude)))
                        .put("end",org.json.JSONArray(listOf(end.longitude,end.latitude)))
                    if(requestedVia!=null)body.put("via",org.json.JSONArray(listOf(requestedVia.longitude,requestedVia.latitude)))
                    RoutingClient.parse(client.call("maps-route",body).toString(),requestedName)
                }
            }
            main.post{
                if(request!=generation||!foreground)return@post
                loading=false
                result.fold(onSuccess={path->
                    routeIdentity=java.util.UUID.randomUUID().toString()
                    target=end;targetName=requestedName
                    if(newTarget!=null)places.recent(requestedName,end)
                    if(newTarget!=null){lastWaypoint=null;waypointVisited=false}
                    route?.let{old->routeChange="경로 변경: %+.1f km / %+.0f분 (예상)".format((path.meters-(remainingMeters?:old.meters))/1000,(path.seconds-(remainingSeconds?:old.seconds))/60)}
                    if(requestedVia!=null&&requestedVia!=lastWaypoint){lastWaypoint=requestedVia;waypointVisited=false}
                    route=path;routeReceivedElapsed=now();waypoint=requestedVia;follower.reset();spoken="";arrived=false
                    message=if(reroute&&active)"새 경로로 안내합니다" else "경로를 확인한 뒤 안내 시작을 눌러 주세요"
                    finishRequest(true,message)
                },onFailure={error->
                    // A failed new destination must never leave an unrelated old route ready to start.
                    // Failed waypoint additions/re-routing retain the same destination instead.
                    if(newTarget!=null){
                        pause();route=null;target=null;waypoint=null;lastWaypoint=null;waypointVisited=false
                        routeChange="";routeIdentity="";follower.reset();arrived=false;spoken=""
                    }
                    message=if(reroute)"재탐색 실패 · 인터넷을 확인하세요. 15초 후 다시 시도합니다" else "경로 검색 실패 · ${error.message?.take(100)?:"인터넷을 확인하세요"}"
                    finishRequest(false,message)
                });changed()
            }
        }
    }
    var familyCandidates=emptyList<org.json.JSONObject>();private set
    var comparisonMessage="";private set
    var comparisonName="";private set
    var comparisonTarget:GeoPoint?=null;private set
    private var comparisonStart:GeoPoint?=null
    private var comparedAt=0L
    fun compareFamily(name:String,end:GeoPoint,via:GeoPoint?=null,stopName:String="",needs:List<String> = emptyList()){
        if(!foreground||!locationUsable||loading||!end.valid()||!useTmap){comparisonMessage="TMAP 연결과 새 GPS 위치를 확인하세요";changed();return}
        if(now()-lastFetch<2000){comparisonMessage="잠시 후 다시 비교하세요";changed();return}
        pause();dropFamilyNeeds();familyCandidates=emptyList();comparisonName=name;comparisonTarget=end;comparisonStart=fix!!.point
        val origin=comparisonStart!!;val request=++generation;loading=true;lastFetch=now();comparisonMessage="실제 경로 후보를 비교 중이에요";changed()
        worker.execute{
            val result=runCatching{
                val body=org.json.JSONObject().put("consent",true).put("needs",org.json.JSONArray(needs)).put("start",org.json.JSONArray(listOf(origin.longitude,origin.latitude))).put("end",org.json.JSONArray(listOf(end.longitude,end.latitude)))
                if(via!=null)body.put("via",org.json.JSONArray(listOf(via.longitude,via.latitude))).put("stopName",stopName)
                checkNotNull(gateway).invoke().call("maps-compare",body)
            }
            main.post{
                if(request!=generation||!foreground)return@post
                loading=false
                result.fold(onSuccess={data->
                    val a=data.getJSONArray("candidates");familyCandidates=(0 until a.length()).map{a.getJSONObject(it)};comparedAt=now()
                    comparisonMessage=data.optString("notice")+(data.optJSONArray("errors")?.takeIf{it.length()>0}?.let{" · 일부 후보 조회 실패: "+it.optString(0)}?:"")
                },onFailure={comparisonMessage="경로 비교 실패 · "+it.message.orEmpty().take(120)})
                changed()
            }
        }
    }
    fun chooseFamilyRoute(id:String):Boolean{
        val candidate=familyCandidates.firstOrNull{it.optString("id")==id}?:return false
        if(loading||!locationUsable||now()-comparedAt>600_000||fix!!.point.distance(comparisonStart?:return false)>150){comparisonMessage="위치 또는 조회 시간이 바뀌었어요. 다시 비교해 주세요";changed();return false}
        val path=runCatching{RoutingClient.parse(candidate.getJSONObject("payload").toString(),comparisonName)}.getOrElse{comparisonMessage="선택한 경로를 읽지 못했어요. 다시 비교해 주세요";changed();return false}
        pause();generation++;dropFamilyNeeds();target=comparisonTarget;targetName=comparisonName;places.recent(targetName,target!!)
        val via=candidate.optJSONArray("via");waypoint=via?.let{GeoPoint(it.getDouble(1),it.getDouble(0))};lastWaypoint=waypoint;waypointVisited=false
        route=path;routeIdentity=java.util.UUID.randomUUID().toString();routeReceivedElapsed=now();follower.reset();spoken="";arrived=false;setOption(path.option)
        routeChange="가족 기준으로 선택 · "+candidate.getJSONArray("labels").optString(0);message="선택한 경로를 확인한 뒤 안내 시작을 눌러 주세요";changed();return true
    }
    // Family criteria from a passenger the driver allowed to change the route ('목적지·경유지 변경'):
    // applied to the route being guided at once, without pausing guidance or changing the destination.
    private var familyNeeds:List<String>?=null
    private var familyAt=0L
    private var familyBusy=false
    private var familyDropped=0
    /** The driver compared or chose a route, or ended guidance: queued family criteria no longer apply. */
    private fun dropFamilyNeeds(){familyNeeds=null;familyDropped++}
    private class FamilyOutcome(val path:RoadRoute?,val via:GeoPoint?,val stopName:String?,val same:Boolean,val note:String?)
    /** Criteria that arrive before a destination wait up to 10 minutes and are applied to the next route. */
    fun familyNeedsArrived(needs:List<String>){
        if(needs.isEmpty())return
        familyNeeds=needs;familyAt=now()
        if(route==null){message="가족 기준을 받았어요 · 목적지를 정하면 반영해요";changed()}
        tryFamilyReroute()
    }
    private fun tryFamilyReroute(){
        val needs=familyNeeds?:return
        if(now()-familyAt>600_000){familyNeeds=null;return}
        val current=route;val end=target;val origin=fix?.takeIf{it.usable(now())}?.point;val client=gateway
        if(familyBusy||loading||!foreground||arrived||current==null||end==null||origin==null||now()-lastFetch<2000)return
        familyNeeds=null
        val label=FamilyReroute.label(needs);val facility=FamilyReroute.stopFacility(needs);val shape=FamilyReroute.shapesRoute(needs)
        if(!useTmap||client==null){routeChange="가족 기준($label) · TMAP 경로에서만 반영";message="가족 기준 경로는 TMAP으로 안내할 때 반영돼요";changed();return}
        val keep=waypoint
        // One stop per route. A stop is added for the driver only in P, like other route permissions;
        // while driving the driver hears the request and adds the stop by voice.
        val addStop=facility!=null&&keep==null&&parked()
        val suggest=if(facility!=null&&keep==null&&!addStop)facility else null
        val skipped=if(facility!=null&&keep!=null)"경유지가 이미 있어 $facility 경유는 넣지 않았어요" else null
        if(!shape&&!addStop){
            routeChange="가족 기준($label) · "+(skipped?:"$suggest 경유는 음성 명령으로 추가")
            message=skipped?:"가족이 $suggest 경유를 원해요 · 음성 명령으로 추가할 수 있어요"
            suggest?.let{announce(FamilyReroute.suggestion(it))}
            changed();return
        }
        val queuedAt=familyAt;val dropped=familyDropped;val name=targetName;val from=progress;val ahead=searchRoute()
        val request=++generation;loading=true;familyBusy=true;lastFetch=now()
        message="가족 기준으로 경로를 고르는 중이에요";changed()
        worker.execute{
            val outcome=if(request!=generation)null else runCatching{
                val stop:Pair<String,GeoPoint>?=if(!addStop)null else{
                    val body=org.json.JSONObject().put("consent",true).put("query","").put("facility",facility)
                        .put("center",org.json.JSONArray(listOf(origin.longitude,origin.latitude))).put("route",ahead)
                    val found=mapCall(client(),"maps-search",body,request).optJSONArray("places")?:org.json.JSONArray()
                    // Far enough ahead to reach safely, and not the destination itself.
                    (0 until found.length()).map{found.getJSONObject(it)}.map{it.optString("name").take(80) to GeoPoint(it.optDouble("latitude"),it.optDouble("longitude"))}
                        .firstOrNull{(_,p)->p.valid()&&current.project(p).along-from>=500&&p.distance(origin)>=500&&p.distance(end)>=500}
                }
                var note=skipped?:if(addStop&&stop==null)"경로 앞쪽 $facility 검색 결과가 없어요" else null
                if(!shape&&stop==null)return@runCatching FamilyOutcome(null,null,null,false,note)
                val body=org.json.JSONObject().put("consent",true).put("auto",true).put("needs",org.json.JSONArray(needs))
                    .put("start",org.json.JSONArray(listOf(origin.longitude,origin.latitude))).put("end",org.json.JSONArray(listOf(end.longitude,end.latitude)))
                if(keep!=null)body.put("keep",org.json.JSONArray(listOf(keep.longitude,keep.latitude)))
                if(stop!=null)body.put("via",org.json.JSONArray(listOf(stop.second.longitude,stop.second.latitude))).put("stopName",stop.first)
                val list=mapCall(client(),"maps-compare",body,request).getJSONArray("candidates")
                val candidates=(0 until list.length()).map{list.getJSONObject(it)}
                val options=candidates.map{FamilyReroute.Option(it.getString("id"),it.getDouble("seconds"),it.getInt("turns"),it.optBoolean("stop"),it.optDouble("extraSeconds",0.0))}
                val chosen=checkNotNull(FamilyReroute.pick(options,stop!=null)){"경로 후보가 없어요"}
                if(stop!=null&&!chosen.stop)note="${stop.first} 경유는 약 ${kotlin.math.ceil((options.firstOrNull{it.stop}?.extraSeconds?:0.0)/60).toInt()}분 더 걸려 넣지 않았어요"
                if(!shape&&!chosen.stop)return@runCatching FamilyOutcome(null,null,null,false,note)
                val path=RoutingClient.parse(candidates.first{it.getString("id")==chosen.id}.getJSONObject("payload").toString(),name)
                val via=if(chosen.stop&&stop!=null)stop.second else keep
                FamilyOutcome(path,via,if(chosen.stop)stop?.first else null,via==keep&&FamilyReroute.sameRoad(path,current,from),note)
            }
            main.post{
                familyBusy=false
                // Superseded (screen left): the criteria stay queued unless guidance ended or newer criteria arrived.
                if(outcome==null||request!=generation||!foreground){if(route!=null&&familyNeeds==null&&familyDropped==dropped){familyNeeds=needs;familyAt=queuedAt};return@post}
                loading=false
                outcome.fold(onSuccess={applyFamilyChoice(it,needs,suggest)},
                    onFailure={message="가족 기준 반영 실패 · "+it.message.orEmpty().take(100);routeChange="가족 기준($label) · 경로 반영 실패, 지금 경로로 안내"})
                changed()
            }
        }
    }
    private fun applyFamilyChoice(o:FamilyOutcome,needs:List<String>,suggest:String?){
        val label=FamilyReroute.label(needs);val note=(o.note?:suggest?.let{"$it 경유는 음성 명령으로 추가"})?.let{" · $it"}.orEmpty()
        val extra=suggest?.let{" "+FamilyReroute.suggestion(it)}.orEmpty()
        val path=o.path
        if(path==null){routeChange="가족 기준($label)$note";message=o.note?:"가족 기준을 확인했어요";o.note?.let{announce("$it.")};return}
        if(o.same){routeChange="가족 기준($label) · 지금 경로 유지$note";message="지금 경로가 가족 기준에 맞아요$note";announce("지금 경로가 가족 기준에 맞아요.$extra");return}
        routeChange="가족 기준($label) · %+.1f km / %+.0f분 (예상)".format((path.meters-(remainingMeters?:path.meters))/1000,(path.seconds-(remainingSeconds?:path.seconds))/60)+note
        val via=o.via
        if(via!=null&&via!=lastWaypoint){lastWaypoint=via;waypointVisited=false}
        route=path;routeIdentity=java.util.UUID.randomUUID().toString();routeReceivedElapsed=now();waypoint=via
        follower.reset();spoken="";arrived=false;setOption(path.option)
        message=(if(active)"가족 기준 경로로 안내합니다" else "가족 기준 경로예요 · 안내 시작을 눌러 주세요")+note
        announce(FamilyReroute.announcement(needs,o.stopName)+extra)
    }
    /** The PC server allows one map call per second per role; a voice search may have just used that second. */
    private fun mapCall(client:com.familycare.ui.MapGateway,action:String,body:org.json.JSONObject,request:Int):org.json.JSONObject{
        repeat(2){attempt->
            val delay=1000-(now()-lastNetworkRequest)
            if(delay>0)SystemClock.sleep(delay)
            check(request==generation){"다른 경로 요청으로 취소됐어요"}
            lastNetworkRequest=now()
            try{return client.call(action,body)}catch(e:IllegalStateException){if(attempt==1||e.message!="잠시 후 다시 검색하세요")throw e}
            SystemClock.sleep(1100)
        }
        error("잠시 후 다시 시도해 주세요")
    }
    private fun finishRequest(ok:Boolean,detail:String){val completion=pendingCompletion;pendingCompletion=null;completion?.invoke(ok,detail)}
    fun start(){
        if(!foreground||route==null||loading||!locationUsable){message="경로와 새 GPS 위치를 먼저 확인해 주세요";changed();return}
        pendingStart=true
        if(focus?.request()!=true){pendingStart=false;active=false;message="안내 권한을 받지 못했어요. 잠시 뒤 안내 재개를 눌러 주세요"}
        changed()
    }
    fun pause(){pendingStart=false;active=false;focus?.release();stopVoice();changed()}
    fun cancel(){generation++;loading=false;dropFamilyNeeds();pause();route=null;target=null;waypoint=null;lastWaypoint=null;waypointVisited=false;routeChange="";routeIdentity="";follower.reset();arrived=false;spoken="";message="길안내를 종료했어요";finishRequest(false,message);changed()}
    private val tick=object:Runnable{
        override fun run(){
            // Expire by monotonic receipt age: PC and emulator wall clocks may differ.
            route?.takeIf{it.provider=="TMAP" && (routeReceivedElapsed<0 || now()-routeReceivedElapsed>=82_800_000L)}?.let{
                cancel();message="경로 유효 시간이 지났어요. 목적지를 다시 검색해 주세요"
            }
            val path=route;val position=fix
            if(active&&foreground&&path!=null&&position!=null&&position.usable(now())){
                follower.update(path,position,now())
                waypoint?.let{if(position.point.distance(it)<60){waypointVisited=true;waypoint=null}}
                if(follower.arrived){
                    active=false;pendingStart=false
                    arrived=true;message="목적지에 도착했어요";announce("목적지에 도착했습니다. 주변을 확인하고 안전하게 정차하세요.")
                    val finished=generation
                    main.postDelayed({if(arrived&&generation==finished)focus?.release()},12000)
                }else if(follower.offRoute){
                    message=if(loading)"경로를 다시 찾고 있어요" else "경로를 벗어났어요"
                    if(!loading&&now()-lastFetch>=15_000)fetch(true)
                }else if(!loading){
                    message="길안내 중"
                    val turn=path.instruction(follower.along);val meters=(turn.along-follower.along).coerceAtLeast(0.0)
                    val band=when{meters<=35->0;meters<=120->100;meters<=350->300;else->-1}
                    val key="${turn.along}:$band"
                    if(band>=0&&key!=spoken){
                        val text=(if(band==0)"잠시 후 " else "약 ${band}미터 앞에서 ")+turn.instruction
                        if(announce(text))spoken=key
                    }
                }
            }else if(active&&!locationUsable){stopVoice()}
            if(familyNeeds!=null)tryFamilyReroute()
            changed();if(foreground)main.postDelayed(this,1000)
        }
    }
}

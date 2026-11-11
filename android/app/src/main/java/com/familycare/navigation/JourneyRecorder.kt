package com.familycare.navigation

import org.json.JSONArray
import org.json.JSONObject

data class JourneyPoint(val at:Long,val point:GeoPoint)
data class JourneyEvent(val at:Long,val kind:String,val text:String,val point:GeoPoint?)

/** Only records after the driver opts in. Gaps and impossible GPS jumps never add distance. */
class JourneyRecorder {
    var startedAt=0L;private set
    var endedAt=0L;private set
    var meters=0.0;private set
    var mode="";private set
    var feedback=Feedback();private set
    var simulated=false;private set
    val points=mutableListOf<JourneyPoint>()
    val events=mutableListOf<JourneyEvent>()
    private var previous:PositionFix?=null
    private var lastSampleWall=0L
    private var stoppedAt:Long?=null
    private var stopLogged=false
    val active get()=startedAt>0&&endedAt==0L
    val score get()=(100-feedback.total*3).coerceAtLeast(0)
    fun start(at:Long,profile:String){startedAt=at;endedAt=0;mode=profile;meters=0.0;feedback=Feedback();simulated=false;points.clear();events.clear();previous=null;stoppedAt=null;stopLogged=false;event(at,"start","이동 시작")}
    fun sample(fix:PositionFix?,elapsed:Long,wall:Long,speed:Double?){
        if(!active||fix==null||!fix.usable(elapsed))return
        simulated=simulated||fix.simulated
        val old=previous
        if(old!=null&&fix.elapsed<=old.elapsed)return
        if(old!=null){val dt=(fix.elapsed-old.elapsed)/1000.0;val distance=old.point.distance(fix.point)
            if(dt in 0.2..15.0&&distance/dt>60)return
            if(dt in 0.2..15.0&&distance>=3)meters+=distance
        }
        previous=fix
        lastSampleWall=wall
        val last=points.lastOrNull()
        if(last==null||wall-last.at>=15_000||last.point.distance(fix.point)>=30){
            if(points.size>=600){val keep=points.filterIndexed{i,_->i%2==0};points.clear();points.addAll(keep)}
            points.add(JourneyPoint(wall,fix.point))
        }
        if(speed!=null&&kotlin.math.abs(speed)<0.5){
            if(stoppedAt==null)stoppedAt=wall
            if(!stopLogged&&wall-stoppedAt!!>=60_000){event(wall,"stop","1분 이상 정차",fix.point);stopLogged=true}
        }else{stoppedAt=null;stopLogged=false}
    }
    fun event(at:Long,kind:String,text:String,point:GeoPoint?=previous?.point?.takeIf{at-lastSampleWall in 0..10000}){if(active){
        feedback=when(kind){"accel"->feedback.copy(acceleration=feedback.acceleration+1);"brake"->feedback.copy(braking=feedback.braking+1);"steer"->feedback.copy(steering=feedback.steering+1);else->feedback}
        events.add(JourneyEvent(at,kind,text,point));if(events.size>200)events.removeAt(0)}}
    fun finish(at:Long){if(active){event(at,"end","이동 종료");endedAt=at}}
    fun clear(){startedAt=0;endedAt=0;points.clear();events.clear();previous=null;meters=0.0}
    fun json():JSONObject=JSONObject().put("id",startedAt.toString()).put("startedAt",startedAt).put("endedAt",endedAt).put("mode",mode)
        .put("simulated",simulated)
        .put("accel",feedback.acceleration).put("brake",feedback.braking).put("steer",feedback.steering)
        .put("meters",meters.toInt()).put("score",score).put("points",JSONArray(points.map{JSONObject().put("at",it.at).put("lat",it.point.latitude).put("lon",it.point.longitude)}))
        .put("events",JSONArray(events.map{JSONObject().put("at",it.at).put("kind",it.kind).put("text",it.text).put("lat",it.point?.latitude).put("lon",it.point?.longitude)}))
}

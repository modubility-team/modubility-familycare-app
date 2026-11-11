package com.familycare.navigation

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit emulator-only rehearsal session. This code is in the test APK, never the release app. */
class LiveDemoSessionTest {
    // A live rehearsal must use Android's real frame clock, not Compose's test clock.
    @get:Rule val activityRule=ActivityScenarioRule(MainActivity::class.java)
    @Test fun controlledRehearsal(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDemo")=="true")
        assumeTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"))
        lateinit var ctx:MainActivity
        activityRule.scenario.onActivity{ctx=it}
        val care=CareRuntime.get(ctx);val nav=care.navigation
        val file=File(ctx.filesDir,"live-demo-command.json")
        val stateFile=File(ctx.filesDir,"live-demo-state.json")
        val scratch=File(ctx.filesDir,"live-demo-state.tmp")
        file.delete()
        var lastId="";var reply="시연 연결 준비";var replyOk=true;var done=false;var pending=false
        var links="";val original=care.engine.profile
        val originalRecord=care.recordJourney;val originalShare=care.shareJourney
        fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        val readyUntil=SystemClock.elapsedRealtime()+30000
        var ready=false
        while(!ready&&SystemClock.elapsedRealtime()<readyUntil){
            main{ready=care.engine.ux.showDetails}
            if(!ready)SystemClock.sleep(100)
        }
        check(ready){"P단 정차 상태에서 시연을 시작해 주세요"}
        val deadline=SystemClock.elapsedRealtime()+2*60*60*1000
        try{
            while(!done&&SystemClock.elapsedRealtime()<deadline){
                val command=runCatching{JSONObject(file.readText())}.getOrNull()
                if(command!=null&&command.optString("id").isNotEmpty()&&command.optString("id")!=lastId){
                    main{
                        lastId=command.getString("id");replyOk=true;pending=false
                        try{
                            when(command.getString("action")){
                                "prepare"->{
                                    check(care.parked){"P단·속도 0을 확인해 주세요"}
                                    nav.cancel();care.stopWatch()
                                    nav.selectProvider(true)
                                    if(care.engine.running)care.endTrip()
                                    care.confirmRear()
                                    if(!care.audioEnabled)care.toggleDriverAudio()
                                    care.recording(true);care.sharingJourney(true);care.startTrip();if(!care.relay.sharing)care.share()
                                    reply="이동 기록, 가족 연결 준비 완료 · TMAP 길찾기";care.publish()
                                }
                                "relocate"->{check(care.parked){"정차 후 위치를 변경하세요"};nav.cancel();reply="기존 경로 해제 · 새 위치 수신 대기"}
                                "plan"->{
                                    check(care.parked){"정차 후 경로를 준비해 주세요"}
                                    val p=command.getJSONArray("point")
                                    val target=GeoPoint(p.getDouble(1),p.getDouble(0))
                                    check(target.valid()){ "목적지 좌표를 확인하세요" }
                                    pending=true;reply="선택한 목적지까지 경로를 찾는 중"
                                    nav.plan(command.getString("name").take(120),target){ok,message->replyOk=ok;reply=message;pending=false}
                                }
                                "start"->{nav.start();reply="길안내 시작 요청"}
                                "pause"->{nav.pause();reply="길안내 일시정지"}
                                "finish"->{
                                    check(care.parked){"정차 후 이동을 종료해 주세요"}
                                    nav.cancel();care.stopWatch();if(care.engine.running)care.endTrip();reply="이동 종료 · 뒷좌석을 직접 확인해 주세요"
                                }
                                "stop-session"->{check(care.parked){"먼저 정차해 주세요"};done=true;reply="시연 도구 종료"}
                                else->error("지원하지 않는 시연 동작")
                            }
                        }catch(error:Exception){replyOk=false;pending=false;reply=error.message?:"시연 동작 실패"}
                    }
                }
                lateinit var snapshot:JSONObject
                main{
                    val points=JSONArray();nav.route?.points?.forEach{points.put(JSONArray().put(it.longitude).put(it.latitude))}
                    snapshot=JSONObject().put("at",System.currentTimeMillis()).put("id",lastId).put("reply",reply)
                        .put("ok",replyOk).put("pending",pending).put("parked",care.parked).put("uxDetails",care.engine.ux.showDetails)
                        .put("running",care.engine.running).put("sharing",care.relay.connected).put("links",JSONObject(care.relay.links))
                        .put("climateSupported",care.climateSupported).put("climateTarget",care.engine.value(Signal.CLIMATE_TARGET,care.now())?:JSONObject.NULL)
                        .put("climateAreas",care.climateZones).put("recording",care.recordJourney).put("holding",care.holding)
                        .put("speed",care.engine.value(Signal.SPEED,care.now())?:JSONObject.NULL)
                        .put("locationSdk",nav.gps.sdkStatus).put("mapProvider",nav.providerLabel).put("gps",nav.locationStatus).put("gpsReady",nav.locationUsable).put("active",nav.active)
                        .put("arrived",nav.arrived).put("loading",nav.loading).put("message",nav.message)
                        .put("position",nav.fix?.point?.let{JSONArray().put(it.longitude).put(it.latitude)}?:JSONObject.NULL)
                        .put("destination",nav.route?.destination?:"")
                        .put("destinationPoint",nav.target?.let{JSONArray().put(it.longitude).put(it.latitude)}?:JSONObject.NULL)
                        .put("navigationStartId",nav.guidanceSession).put("navigationRouteId",nav.routeIdentity).put("routeProgress",nav.progress)
                        .put("remaining",nav.remainingMeters?:JSONObject.NULL).put("route",points)
                        .put("guidanceHeading",nav.displayPosition?.heading?:JSONObject.NULL)
                        .put("turnInstruction",nav.nextTurn?.instruction?:"").put("turnSymbol",nav.nextTurn?.symbol?:"")
                        .put("turnDistance",nav.nextTurn?.let{(it.along-nav.progress).coerceAtLeast(0.0)}?:JSONObject.NULL)
                        .put("followingInstruction",nav.followingTurn?.instruction?:"").put("remainingSeconds",nav.remainingSeconds?:JSONObject.NULL)
                        .put("outside",nav.outside)
                        .put("memories",care.relay.memories.size).put("audio",care.audioStatus)
                }
                val linkObject=snapshot.getJSONObject("links")
                if(linkObject.toString()!=links){
                    links=linkObject.toString()
                    for(role in listOf("owner","family"))linkObject.optString(role).takeIf{it.isNotEmpty()}?.let{link->
                        val matrix=QRCodeWriter().encode(link,BarcodeFormat.QR_CODE,420,420)
                        val bitmap=Bitmap.createBitmap(420,420,Bitmap.Config.ARGB_8888)
                        for(y in 0 until 420)for(x in 0 until 420)bitmap.setPixel(x,y,if(matrix[x,y])Color.BLACK else Color.WHITE)
                        File(ctx.filesDir,"live-demo-qr-$role.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                        bitmap.recycle()
                    }
                }
                scratch.writeText(snapshot.toString());check(scratch.renameTo(stateFile))
                SystemClock.sleep(500)
            }
        }finally{
            main{
                nav.pause()
                if(care.engine.ux.showDetails){care.stopHold();care.stopWatch();if(care.engine.running)care.endTrip();care.recording(originalRecord);care.sharingJourney(originalShare);care.stopSharing();care.engine.profile=original;care.publish()}
            }
            file.delete();stateFile.delete();scratch.delete()
            listOf("owner","family").forEach{File(ctx.filesDir,"live-demo-qr-$it.png").delete()}
        }
    }
}

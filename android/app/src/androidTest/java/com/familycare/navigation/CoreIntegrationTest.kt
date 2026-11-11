package com.familycare.navigation

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Run with the local relay on 8765 and adb reverse tcp:8765 tcp:8765. */
class CoreIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val runtime get()=CareRuntime.get(compose.activity)
    private fun waitUntil(check:()->Boolean)=compose.waitUntil(20000,check)
    private fun main(action:()->Unit)=compose.runOnUiThread(action)
    private fun api(link:String,action:String,body:JSONObject=JSONObject()):JSONObject{
        val parts=link.substringAfter('#').split('.')
        val c=URL("http://127.0.0.1:8765/api/sessions/${parts[0]}/$action").openConnection() as HttpURLConnection
        try{
            c.connectTimeout=3000;c.readTimeout=5000;c.requestMethod="POST";c.doOutput=true
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${parts[1]}")
            c.outputStream.use{it.write(body.toString().toByteArray())}
            assertEquals(200,c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().readText())
        }finally{c.disconnect()}
    }
    private fun capture(name:String){
        compose.waitForIdle()
        val inst=InstrumentationRegistry.getInstrumentation()
        val bitmap=inst.uiAutomation.takeScreenshot()
        File(inst.targetContext.filesDir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun realSignalsProfileTripBackgroundAndCompanionRequest(){
        waitUntil{runtime.engine.ux.showDetails}
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission("com.familycare.navigation","android.car.permission.CAR_SPEED")
        waitUntil{runtime.engine.value(Signal.BATTERY,runtime.now())!=null&&runtime.engine.value(Signal.TEMPERATURE,runtime.now())!=null}
        main{runtime.speedPermissionChanged(true)}
        waitUntil{runtime.engine.value(Signal.SPEED,runtime.now())!=null}
        val before=runtime.engine.profile
        try{
            compose.onNodeWithTag("tab-동승자").performClick()
            if(Passenger.CHILD !in runtime.engine.profile.members)compose.onNodeWithTag("passenger-CHILD").performScrollTo().performClick()
            if(Passenger.PET !in runtime.engine.profile.members)compose.onNodeWithTag("passenger-PET").performScrollTo().performClick()
            main{runtime.startTrip();runtime.setTimer(1);runtime.startWatch()}
            assertTrue(runtime.engine.running);assertTrue(runtime.engine.watching)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val sampledAt=runtime.engine.signals[Signal.BATTERY]!!.at
            waitUntil{(runtime.engine.signals[Signal.BATTERY]?.at?:0)>sampledAt}
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitUntil{runtime.engine.ux.showDetails}
            main{runtime.share()};waitUntil{runtime.relay.links.isNotEmpty()}
            val link=runtime.relay.links.getValue("family")
            val id="instrumented-note-"+System.currentTimeMillis()
            api(link,"request",JSONObject().put("id",id).put("client","integration").put("kind","note").put("title","다음 안전한 곳에서 쉬고 싶어요"))
            waitUntil{runtime.relay.requests.any{it.id==id}}
            main{runtime.decide(runtime.relay.requests.first{it.id==id},true)}
            waitUntil{runtime.relay.requests.any{it.id==id&&it.status=="confirmed"}}
            var status=api(link,"status")
            val end=System.currentTimeMillis()+12000
            while(status.getJSONArray("requests").getJSONObject(0).getString("status")!="confirmed"&&System.currentTimeMillis()<end){Thread.sleep(500);status=api(link,"status")}
            assertEquals("confirmed",status.getJSONArray("requests").getJSONObject(0).getString("status"))
            assertFalse(status.getBoolean("stale"))
            assertTrue(status.getJSONObject("state").getInt("breakRemainingMinutes")>=0)
            assertTrue(status.getJSONObject("state").getInt("watchRemainingSeconds")>=0)
            compose.onNodeWithTag("tab-차량").performClick()
            capture("core-vehicle")
            val evidence=JSONObject().put("sdk",JSONObject().apply{Signal.entries.forEach{s->put(s.name,runtime.engine.value(s,runtime.now())?:JSONObject.NULL)}})
                .put("backgroundUpdates",true).put("noteConfirmed",true)
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"core-evidence.json").writeText(evidence.toString(2))
            main{runtime.stopWatch();runtime.endTrip()}
            assertTrue(runtime.engine.exitCheckRequired)
            main{runtime.confirmRear()}
            compose.onNodeWithTag("tab-기록").performClick();capture("core-history")
            assertTrue(runtime.history.isNotEmpty())
        }finally{
            main{if(runtime.engine.ux.showDetails){if(runtime.engine.running)runtime.endTrip();runtime.stopWatch();runtime.stopSharing();runtime.engine.profile=before;runtime.confirmRear()}}
            waitUntil{!runtime.relay.sharing}
        }
    }
}

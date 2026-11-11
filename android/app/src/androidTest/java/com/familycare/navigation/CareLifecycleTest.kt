package com.familycare.navigation
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Real emulator signals and a real elapsed minute; no fabricated production telemetry. */
class CareLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun phase(name:String){InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("care_phase",name)})}
    private fun api(link:String,action:String,body:JSONObject=JSONObject()):JSONObject{
        val credentials=android.net.Uri.parse(link).fragment!!.split('.',limit=2)
        val c=URL("http://127.0.0.1:8765/api/sessions/${credentials[0]}/$action").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${credentials[1]}");c.outputStream.use{it.write(body.toString().toByteArray())};assertEquals(200,c.responseCode);return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
    }
    @Test fun drivingSensorsPetTimerAcknowledgementAndRearCheck(){
        val c=CareRuntime.get(compose.activity);val profile=c.engine.profile;val record=c.recordJourney;val share=c.shareJourney
        try{
            compose.waitUntil(20000){c.parked}
            compose.runOnUiThread{if(c.engine.running)c.endTrip();c.stopWatch();c.confirmRear();c.engine.profile=FamilyProfile(setOf(Passenger.CHILD,Passenger.PET),0,2);c.recording(true);c.sharingJourney(true);c.startTrip();c.share()}
            compose.waitUntil(20000){c.relay.connected&&c.journey.points.isNotEmpty()}
            val family=c.relay.links.getValue("family")
            phase("driving-metrics")
            try{compose.waitUntil(30000){c.parked&&c.engine.feedback.acceleration>0&&c.engine.feedback.braking>0&&c.engine.feedback.steering>0}}
            catch(e:Throwable){throw AssertionError("Metrics parked=${c.parked}, feedback=${c.engine.feedback}, signals=${c.engine.signals}",e)}
            assertTrue(c.engine.motionLoad(c.now())>=60);assertTrue(c.journey.score<100)
            compose.runOnUiThread{c.setTimer(1);c.startWatch()}
            phase("door-open")
            compose.waitUntil(15000){c.engine.value(Signal.FR_DOOR,c.now())==1.0}
            assertTrue(c.engine.alerts(c.now()).any{it.level==1&&it.code=="closures"})
            phase("battery-low")
            compose.waitUntil(15000){c.engine.value(Signal.BATTERY,c.now())?.let{it<=20}==true}
            assertTrue(c.engine.alerts(c.now()).any{it.code=="battery"&&it.level==2})
            phase("sensors-restore")
            compose.waitUntil(75000){c.engine.alerts(c.now()).any{it.code=="timer"&&it.level==3}}
            compose.waitUntil(10000){api(family,"status").getJSONArray("alerts").let{a->(0 until a.length()).any{a.getJSONObject(it).optString("code")=="timer"}}}
            api(family,"ack",JSONObject().put("code","timer"))
            assertTrue(c.engine.watching)
            compose.runOnUiThread{c.stopWatch()}
            compose.waitUntil(10000){
                val history=api(family,"status").getJSONArray("alertHistory")
                (0 until history.length()).any{index->
                    val alert=history.getJSONObject(index)
                    alert.optString("code")=="timer"&&alert.optBoolean("acknowledged")&&alert.optBoolean("resolved")
                }
            }
            compose.waitUntil(10000){c.journey.events.any{it.kind=="stop"}}
            phase("driver-door")
            compose.waitUntil(15000){c.engine.exitCheckRequired}
            compose.runOnUiThread{c.confirmRear();c.endTrip()}
            assertTrue(c.engine.exitCheckRequired);assertTrue(c.journey.endedAt>0)
            assertTrue(c.journey.events.any{it.kind=="end"})
        }finally{
            phase("sensors-restore")
            compose.waitUntil(15000){c.parked}
            compose.runOnUiThread{c.stopWatch();c.stopHold();if(c.engine.running)c.endTrip();c.confirmRear();c.engine.profile=profile;c.recording(record);c.sharingJourney(share);c.stopSharing()}
        }
    }
}

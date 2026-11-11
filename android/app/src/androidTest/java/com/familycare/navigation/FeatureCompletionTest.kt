package com.familycare.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Emulator signals + actual relay. Every setting goes through a pending request and driver claim. */
class FeatureCompletionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun api(link:String,action:String,body:JSONObject=JSONObject()):JSONObject{
        val parts=link.substringAfter('#').split('.',limit=2)
        val c=URL("http://127.0.0.1:8765/api/sessions/${parts[0]}/$action").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${parts[1]}");c.outputStream.use{it.write(body.toString().toByteArray())};assertEquals(200,c.responseCode);return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
    }
    @Test fun profileTimerAndRecordedJourneyReachFamilyOnlyAfterApproval(){
        val c=CareRuntime.get(compose.activity);val original=c.engine.profile;val oldRecord=c.recordJourney;val oldShare=c.shareJourney
        try{
            compose.waitUntil(20000){c.parked}
            compose.runOnUiThread{if(c.engine.running)c.endTrip();c.stopWatch();c.engine.profile=FamilyProfile();c.share()}
            compose.waitUntil(20000){c.relay.connected&&c.relay.message.contains("휴대폰 연결됨")}
            val link=c.relay.links.getValue("family")
            fun command(id:String,kind:String,options:JSONObject,before:()->Unit){
                api(link,"request",JSONObject().put("id",id).put("client","feature-e2e").put("kind",kind).put("title","검증 $kind").put("options",options))
                compose.waitUntil(15000){c.relay.requests.any{it.id==id}}
                assertEquals("pending",c.relay.requests.first{it.id==id}.status);before()
                compose.runOnUiThread{c.decide(c.relay.requests.first{it.id==id},true)}
                compose.waitUntil(20000){c.relay.requests.any{it.id==id&&it.status=="confirmed"}}
            }
            command("feature-profile","profile",JSONObject().put("members",JSONArray(listOf("PET","CHILD"))).put("childBand",0).put("sensitivity",2)){assertTrue(c.engine.profile.members.isEmpty())}
            assertEquals(2,c.engine.profile.sensitivity);assertEquals(60,c.engine.profile.breakMinutes)
            command("feature-timer","timer",JSONObject().put("action","start").put("minutes",5)){assertFalse(c.engine.watching)}
            assertTrue(c.engine.watching)
            assertFalse(api(c.relay.links.getValue("guest"),"status").getJSONObject("state").has("profile"))
            compose.runOnUiThread{c.recording(true);c.sharingJourney(true);c.startTrip()}
            compose.waitUntil(20000){c.journey.points.isNotEmpty()}
            compose.waitUntil(15000){api(link,"status").getJSONObject("state").optJSONObject("journey")?.optJSONArray("points")?.length()?.let{it>0}==true}
            assertFalse(api(c.relay.links.getValue("observer"),"status").getJSONObject("state").has("journey"))
            compose.runOnUiThread{c.sharingJourney(false)}
            compose.waitUntil(15000){api(link,"status").getJSONObject("state").isNull("journey")}
            compose.runOnUiThread{c.endTrip()};assertTrue(c.engine.exitCheckRequired);assertTrue(c.journeys.isNotEmpty())
        }finally{
            compose.runOnUiThread{if(c.engine.running)c.endTrip();c.stopWatch();c.stopHold();c.recording(oldRecord);c.sharingJourney(oldShare);c.engine.profile=original;c.saveProfile(0);c.relay.end()}
            compose.waitUntil(15000){!c.relay.sharing}
        }
    }
}

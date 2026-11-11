package com.familycare.phone

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local relay fixtures, not real vehicle/phone or navigation SDK evidence. */
class PhoneFlowTest {
    @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
    private fun api(path:String,body:JSONObject=JSONObject(),token:String=""):JSONObject{
        val c=URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try{
            c.connectTimeout=3000;c.readTimeout=5000;c.requestMethod="POST";c.doOutput=true
            c.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token")
            c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            assertEquals(200,c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().readText())
        }finally{c.disconnect()}
    }
    private fun tab(name:String)=compose.onNodeWithTag("phone-tab-$name").performClick()
    private fun capture(name:String){
        compose.waitForIdle()
        val bitmap=compose.onNodeWithTag("phone-frame").captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir,name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun scopedConnectionRequestResultAndGuestRestrictions(){
        val m=compose.activity.model
        val owner=BuildConfig.PRODUCT=="navigation"
        val previous=Triple(m.vehicleName,m.vehicleModel,m.vehiclePlate)
        compose.activity.runOnUiThread { compose.activity.setContent {
            Box(Modifier.width(390.dp).height(820.dp).testTag("phone-frame")){PhoneScreen(m){_,_,_->}}
        } }
        val session=api("/api/sessions")
        val prefix="/api/sessions/"+session.getString("id")
        val token=session.getString("token")
        fun fixtureLink(role:String)=Uri.parse(session.getJSONObject("links").getString(role)).buildUpon().scheme("http").encodedAuthority("127.0.0.1:8765").build().toString()
        val heartbeat=Executors.newSingleThreadScheduledExecutor()
        try{
            val state=JSONObject().put("running",true).put("temperature",23.4).put("battery",82)
                .put("closures","확인한 문·창문이 모두 닫혀 있어요").put("drivingMinutes",12)
                .put("breakRemainingMinutes",78).put("watching",true).put("watchRemainingSeconds",123)
                .put("remainingMeters",12400).put("remainingSeconds",1800)
                .put("climateSupported",true).put("climateTarget",22.0).put("parked",true)
                .put("climatePowerSupported",true).put("climatePower",true)
            api("$prefix/sync",state,token)
            heartbeat.scheduleAtFixedRate({runCatching{api("$prefix/sync",state,token)}},2,2,TimeUnit.SECONDS)
            val role=if(owner)"owner" else "family"
            val join=fixtureLink(role)
            compose.activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("familycare-${BuildConfig.PRODUCT}://join?url="+Uri.encode(join))).setPackage(compose.activity.packageName))
            compose.waitUntil(15000){m.joinText==join}
            assertFalse(m.connected)
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            compose.waitUntil(15000){m.role==role&&!m.stale}
            assertEquals(if(owner)session.getJSONObject("links").getString("family") else fixtureLink("family"),m.connectionForOtherApp())
            assertEquals(78,m.state.getInt("breakRemainingMinutes"))
            if(owner){
                compose.runOnUiThread{m.registerVehicle("우리 가족 차","테스트 차량","");m.dismissResult()}
                tab("내 차");compose.onNodeWithText("우리 가족 차").assertExists()
                capture("owner-080")
                tab("관리");compose.waitUntil(15000){m.canClimate}
                compose.onNodeWithTag("owner-set-climate").performScrollTo().performClick()
            }else{
                tab("홈");capture("family-care-080")
                tab("요청");compose.waitUntil(15000){m.canClimate}
                compose.onNodeWithTag("request-climate").performScrollTo().performClick()
            }
            compose.waitUntil(15000){m.requests.any{it.optString("kind")=="climate"}}
            val request=m.requests.first{it.optString("kind")=="climate"}
            assertEquals("pending",request.getString("status"))
            assertEquals(role,request.getString("originRole"))
            assertEquals(22.0,request.getDouble("target"),0.001) // Starts from the current vehicle setting.
            // The fixture never performs a hardware effect; it only tests request/result flow.
            api("$prefix/claim",JSONObject().put("id",request.getString("id")),token)
            api("$prefix/result",JSONObject().put("id",request.getString("id")).put("status","confirmed").put("message","로컬 통합시험 응답"),token)
            compose.waitUntil(15000){m.requests.any{it.optString("status")=="confirmed"}}
            // A tap during the background status poll must queue, not vanish or be disabled.
            compose.runOnUiThread{m.refresh();m.adjustClimate(0.5)}
            try{compose.waitUntil(15000){m.requests.any{it.optString("kind")=="climate"&&it.optDouble("target")==22.5}}}
            catch(e:AssertionError){throw AssertionError("Quick climate: ${m.resultMessage}, connected=${m.connected}, stale=${m.stale}, busy=${m.busy}, requests=${m.requests}",e)}
            val quick=m.requests.first{it.optDouble("target")==22.5}
            assertTrue(quick.getJSONObject("options").getBoolean("power"))
            compose.runOnUiThread{m.disconnect();m.joinText=fixtureLink("guest");m.connect()}
            if(owner){
                // 내 차 never runs a passenger link: it disconnects and offers the link to Family Care.
                compose.waitUntil(15000){m.handoffLink==fixtureLink("guest")}
                assertFalse(m.connected);assertEquals("",m.role);assertFalse(m.canClimate);assertFalse(m.canPlan)
                assertEquals(fixtureLink("guest"),m.connectionForOtherApp())
                compose.onNodeWithTag("open-handoff").assertExists()
            }else{
                compose.waitUntil(15000){m.role=="guest"}
                assertFalse(m.canClimate);assertFalse(m.canPlan);assertFalse(m.canMemories)
                tab("요청");compose.onNodeWithTag("request-climate").performScrollTo().assertIsNotEnabled()
                compose.runOnUiThread{assertTrue(m.receiveLink(join))}
                assertEquals("guest",m.role)
                assertEquals(fixtureLink("guest"),m.connectionForOtherApp())
            }
        }finally{
            heartbeat.shutdownNow()
            compose.runOnUiThread{m.disconnect();if(owner){if(previous.first.isBlank())m.removeVehicle()else m.registerVehicle(previous.first,previous.second,previous.third)}}
            api("$prefix/end",token=token)
        }
    }
}

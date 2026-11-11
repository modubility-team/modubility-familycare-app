package com.familycare.navigation

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in: use Virtual Console to switch P -> D -> P when prompted. */
class DrivingAudioIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun stage(text:String){InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply{putString("stream","\n$text\n")})}
    @Test fun requestSoundsWhilePlatformRestrictsDrivingUi(){
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("drivingAudio")=="true")
        val care=CareRuntime.get(compose.activity)
        compose.waitUntil(15000){care.engine.ux.showDetails}
        val enabled=care.audioEnabled
        try{
            compose.runOnUiThread{if(!enabled)care.toggleDriverAudio();care.share()}
            compose.waitUntil(15000){care.relay.links.isNotEmpty()}
            compose.waitUntil(15000){care.audioStatus.startsWith("Pleos 한국어 음성 안내")}
            val parts=care.relay.links.getValue("family").substringAfter('#').split('.')
            stage("READY: switch Virtual Console to D")
            compose.waitUntil(60000){care.engine.ux.available&&!care.engine.ux.showDetails}
            val c=URL("http://127.0.0.1:8765/api/sessions/${parts[0]}/request").openConnection() as HttpURLConnection
            try{
                c.connectTimeout=3000;c.readTimeout=5000;c.requestMethod="POST";c.doOutput=true
                c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${parts[1]}")
                val body=JSONObject().put("id","driving-audio-test").put("client","driving-test").put("kind","note").put("title","다음 안전한 장소에서 쉬고 싶어요")
                c.outputStream.use{it.write(body.toString().toByteArray())};assertEquals(200,c.responseCode)
            }finally{c.disconnect()}
            compose.waitUntil(12000){care.driverNotice.contains("휴식을 요청")}
            compose.onNodeWithTag("driving-notification").assertIsDisplayed()
            compose.onNodeWithText("동승자가 휴식을 요청했어요").assertIsDisplayed()
            compose.onNodeWithTag("tab-요청").assertDoesNotExist()
            compose.waitUntil(12000){care.audioResult.endsWith("재생 완료")}
            assertEquals("한국어 음성 안내 재생 완료",care.audioResult)
            assertEquals("pending",care.relay.requests.single{it.id=="driving-audio-test"}.status)
            android.os.SystemClock.sleep(9000)
            compose.onNodeWithTag("driving-notification").assertIsDisplayed()
            stage("PASS: audio completed with actual UX restrictions and request still pending; switch to P")
        }finally{
            compose.waitUntil(60000){care.engine.ux.showDetails}
            compose.runOnUiThread{care.stopSharing();if(!enabled&&care.audioEnabled)care.toggleDriverAudio()}
            compose.waitUntil(15000){!care.relay.sharing}
        }
    }
}

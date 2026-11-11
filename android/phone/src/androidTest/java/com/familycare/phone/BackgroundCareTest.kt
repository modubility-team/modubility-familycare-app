package com.familycare.phone
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Local relay fixture: verifies OS notifications with the phone Activity in the background. */
class BackgroundCareTest {
    @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
    private fun api(path:String,token:String="",body:JSONObject=JSONObject()):JSONObject{
        val c=URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000;c.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token");c.outputStream.use{it.write(body.toString().toByteArray())};assertEquals(200,c.responseCode);return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
    }
    @Test fun arrivalAndRearCheckNotifyWhileActivityStopped(){
        // A family link runs only in Family Care; 내 차 hands it over instead of connecting.
        org.junit.Assume.assumeTrue(BuildConfig.PRODUCT=="care")
        val session=api("/api/sessions");val sid=session.getString("id");val token=session.getString("token");val activity=compose.activity;val m=activity.model
        val manager=activity.getSystemService(NotificationManager::class.java)
        if(android.os.Build.VERSION.SDK_INT>=33)androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(activity.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        try{
            api("/api/sessions/$sid/sync",token,JSONObject().put("arrived",false).put("alerts",JSONArray()))
            compose.runOnUiThread{m.receiveLink(session.getJSONObject("links").getString("family"));m.connect()}
            compose.waitUntil(15000){m.connected&&!m.stale&&!m.busy&&m.grant.optString("deviceId").isNotBlank()}
            compose.runOnUiThread{m.startAlerts()};compose.waitUntil(15000){PhoneCareService.running}
            assertTrue(manager.areNotificationsEnabled())
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            api("/api/sessions/$sid/sync",token,JSONObject().put("arrived",true).put("alerts",JSONArray().put(JSONObject().put("code","rear").put("title","뒷좌석을 직접 확인해 주세요").put("level",3))))
            val end=android.os.SystemClock.elapsedRealtime()+14000
            while(android.os.SystemClock.elapsedRealtime()<end&&manager.activeNotifications.none{it.id==102})Thread.sleep(300)
            assertTrue("Background arrival notification",manager.activeNotifications.any{it.id==102})
            assertTrue("Background care alert",manager.activeNotifications.any{it.id>=200})
        }finally{compose.runOnUiThread{m.stopAlerts();m.disconnect()};manager.cancelAll();api("/api/sessions/$sid/end",token);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
    }
}

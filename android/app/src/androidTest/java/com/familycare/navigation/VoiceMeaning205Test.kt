package com.familycare.navigation
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

class VoiceMeaning205Test {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun api(link:String,action:String,b:JSONObject=JSONObject()):JSONObject{
  val p=android.net.Uri.parse(link).fragment!!.split('.',limit=2);val c=URL("http://127.0.0.1:8765/api/sessions/${p[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=25000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${p[1]}");c.outputStream.use{it.write(b.toString().toByteArray())};val code=c.responseCode;val r=JSONObject((if(code==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});assertEquals("$action: $r",200,code);return r}finally{c.disconnect()}
 }
 private fun gear(value:String){val c=URL("http://127.0.0.1:8088/vehicle/gear?gear=$value").openConnection() as HttpURLConnection;try{c.requestMethod="PUT";assertEquals(200,c.responseCode)}finally{c.disconnect()}}
 @Test fun semanticFamilyStopsDuringGuidance(){
  val care=CareRuntime.get(compose.activity);val nav=care.navigation;val voice=DriverVoice(compose.activity,care);val proof=JSONObject()
  try{
   compose.waitUntil(30000){care.parked&&nav.locationUsable}
   compose.runOnUiThread{care.share()};compose.waitUntil(20000){care.relay.connected&&care.relay.links.containsKey("family")&&care.relay.permissionCatalog.isNotEmpty()}
   val owner=care.relay.links.getValue("owner");val root=care.relay.links.getValue("family");val pair=api(root,"pair-device");val phone=root.substringBeforeLast('.')+"."+pair.getString("token")
   compose.waitUntil(20000){care.ignition.on&&api(owner,"status").getJSONObject("state").optBoolean("authorityActive")}
   api(phone,"request",JSONObject().put("id","voice205-permission").put("kind","permission").put("title","길안내 권한").put("client","voice205").put("options",JSONObject().put("name","가족").put("seat","ROW_1_RIGHT").put("scopes",JSONArray().put("route"))))
   compose.waitUntil(15000){care.relay.requests.any{it.id=="voice205-permission"}}
   compose.runOnUiThread{care.decide(care.relay.requests.first{it.id=="voice205-permission"},true)}
   compose.waitUntil(20000){api(phone,"status").getJSONObject("grant").optBoolean("active")}
   val destination=GeoPoint(37.503,127.05)
   compose.runOnUiThread{nav.plan("가족 여행 최종 목적지",destination)};compose.waitUntil(60000){!nav.loading&&nav.route!=null}
   compose.runOnUiThread{nav.start()};compose.waitUntil(12000){nav.active}
   gear("Drive");compose.waitUntil(15000){!care.parked&&!api(owner,"status").getJSONObject("state").optBoolean("parked",true)}
   fun send(id:String,title:String,point:GeoPoint,kind:String="waypoint"){
    api(phone,"request",JSONObject().put("id",id).put("kind",kind).put("title",title).put("latitude",point.latitude).put("longitude",point.longitude).put("client","voice205"))
    compose.waitUntil(15000){care.relay.requests.any{it.id==id&&it.status=="pending"}}
   }
   send("voice205-a","양재시민의숲",GeoPoint(37.4899,127.0356),"destination");SystemClock.sleep(2100)
   send("voice205-b","가족 추천 공원",GeoPoint(37.492,127.044))
   val originalRoute=nav.routeIdentity
   compose.runOnUiThread{voice.show();voice.submitFinal("가족이 보내준 데 잠깐 찍고 가자")}
   compose.waitUntil(25000){!voice.interpreting}
   assertEquals("${voice.text} / ${voice.status}",2,voice.choices.size);assertEquals(originalRoute,nav.routeIdentity);assertEquals(destination,nav.target)
   proof.put("ambiguityAskedBeforeRouting",true)
   compose.runOnUiThread{voice.submitFinal("승인")}
   assertEquals(2,voice.choices.size);assertEquals(originalRoute,nav.routeIdentity);assertEquals("",voice.lastDecisionId)
   proof.put("ambiguousApprovalDidNotChooseFirst",true)
   val chosen=voice.choices[1]
   compose.runOnUiThread{voice.submitFinal("두 번째")}
   compose.waitUntil(70000){care.relay.requests.any{it.id==chosen.id&&it.status in listOf("confirmed","rejected","needs_review")}}
   assertEquals(voice.status,"confirmed",care.relay.requests.first{it.id==chosen.id}.status)
   assertEquals(destination,nav.target);assertTrue(nav.active);assertTrue(nav.hasWaypoint)
   assertEquals(chosen.latitude!!,nav.summary().getJSONObject("waypoint").getDouble("lat"),.00001)
   proof.put("spokenOrdinalResolved",true).put("realTmapRouteUpdated",true).put("finalDestinationPreserved",true).put("movingGuidanceContinues",true)
   compose.waitUntil(15000){!care.relay.decisionPending(chosen.id)}
   compose.runOnUiThread{voice.observeResult();voice.show();voice.submitFinal("추천지는 들르지마")}
   compose.waitUntil(20000){!voice.interpreting};assertTrue(care.relay.requests.any{it.id=="voice205-a"&&it.status=="pending"});proof.put("negationDidNotExecute",true)
   SystemClock.sleep(2200);compose.runOnUiThread{nav.recalculate(removeWaypoint=true)};compose.waitUntil(60000){!nav.loading&&!nav.hasWaypoint}
   // Actual offline Pleos recognition feeds the real local semantic model, not fixed phrase matching.
   val pcm=InstrumentationRegistry.getInstrumentation().context.assets.open("route205.pcm").use{it.readBytes()}
   compose.runOnUiThread{voice.show();voice.start(pcm)}
   compose.waitUntil(22000){!voice.listening};proof.put("actualRecognizedSpeech",voice.text)
   compose.waitUntil(80000){care.relay.requests.any{it.id=="voice205-a"&&it.status in listOf("confirmed","rejected","needs_review")}}
   assertEquals("${voice.text} / ${voice.status}","confirmed",care.relay.requests.first{it.id=="voice205-a"}.status)
   assertEquals(destination,nav.target);assertTrue(nav.active);proof.put("realSpeechThroughLocalAi",true)
   compose.waitUntil(15000){!care.relay.decisionPending("voice205-a")};compose.runOnUiThread{voice.observeResult()}
   SystemClock.sleep(2200);send("voice205-c","새 추천 장소",GeoPoint(37.49,127.042))
   val unchanged=nav.routeIdentity
   compose.runOnUiThread{voice.show();voice.submitFinal("추천해 준 장소를 일정 중간에 추가해줄래",routeVersion="obsolete-route")}
   compose.waitUntil(25000){!voice.interpreting};assertEquals(unchanged,nav.routeIdentity);assertTrue(voice.status.contains("바뀌"));assertEquals("pending",care.relay.requests.first{it.id=="voice205-c"}.status)
   proof.put("changedRouteBlocksDelayedInterpretation",true)
   compose.onNodeWithTag("driver-microphone").performClick()
   compose.onNodeWithTag("driver-voice-dialog").assertIsDisplayed()
   compose.onNodeWithTag("voice-listen").assertIsDisplayed()
   InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->
    File(compose.activity.filesDir,"voice205-dialog.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
   }
   proof.put("nativeVoiceDialogVisible",true)

  }catch(t:Throwable){proof.put("error",t.stackTraceToString());throw t}finally{
   gear("Park");compose.runOnUiThread{voice.dispose();nav.cancel();care.relay.end()}
   File(compose.activity.filesDir,"voice205-proof.json").writeText(proof.toString(2))
  }
 }
}

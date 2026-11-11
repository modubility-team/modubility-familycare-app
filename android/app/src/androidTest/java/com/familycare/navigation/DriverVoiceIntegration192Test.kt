package com.familycare.navigation
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URL
import java.net.HttpURLConnection
import java.io.File
class DriverVoiceIntegration192Test {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun api(link:String,action:String,b:JSONObject=JSONObject()):JSONObject{
  val p=android.net.Uri.parse(link).fragment!!.split('.',limit=2);val c=URL("http://127.0.0.1:8765/api/sessions/${p[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${p[1]}");c.readTimeout=10000;c.connectTimeout=4000;c.outputStream.use{it.write(b.toString().toByteArray())};val code=c.responseCode;val data=JSONObject((if(code==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});assertEquals(data.optString("error"),200,code);return data}finally{c.disconnect()}
 }
 private fun gear(value:String){val c=URL("http://127.0.0.1:8088/vehicle/gear?gear=$value").openConnection() as HttpURLConnection;try{c.requestMethod="PUT";assertEquals(200,c.responseCode)}finally{c.disconnect()}}
 private fun capture(name:String){InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{b->File(compose.activity.filesDir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}
 @Test fun speechApprovalReadbackAndFullWidthMap(){
  val care=CareRuntime.get(compose.activity);val voice=DriverVoice(compose.activity,care);val proof=JSONObject();var original:Double?=null
  try{
   compose.waitUntil(25000){care.parked&&care.navigation.locationUsable&&care.climateSupported&&care.engine.value(Signal.CLIMATE_TARGET,care.now())!=null}
   original=care.engine.value(Signal.CLIMATE_TARGET,care.now())!!
   compose.runOnUiThread{care.share()};compose.waitUntil(20000){care.relay.connected&&care.relay.links.containsKey("owner")}
   val owner=care.relay.links.getValue("owner")
   compose.onNodeWithTag("driver-microphone").performClick();compose.onNodeWithTag("driver-voice-dialog").assertExists();compose.onNodeWithText("닫기").performClick();proof.put("microphoneUi",true)
   // The actual offline Pleos recognizer, using a generated Korean PCM fixture.
   api(owner,"request",JSONObject().put("id","voice192-note").put("kind","note").put("client","voice-test").put("title","잠시 쉬고 싶어요"))
   compose.waitUntil(15000){care.relay.requests.any{it.id=="voice192-note"}}
   val pcm=InstrumentationRegistry.getInstrumentation().context.assets.open("approval192.pcm").use{it.readBytes()}
   compose.runOnUiThread{voice.show();voice.select(care.relay.requests.first{it.id=="voice192-note"});voice.start(pcm)}
   compose.waitUntil(20000){!voice.listening};proof.put("recognized",voice.text).put("recognitionStatus",voice.status)
   assertEquals("Actual recognizer: ${voice.text} / ${voice.status}",VoiceCommand.APPROVE,DriverVoicePolicy.command(voice.text))
   compose.waitUntil(18000){care.relay.requests.any{it.id=="voice192-note"&&it.status=="confirmed"}}
   proof.put("realSpeechApprovedRequest",true)
   SystemClock.sleep(2100)
   api(owner,"request",JSONObject().put("id","voice192-reject").put("kind","note").put("client","voice-test").put("title","다음 요청 거절 확인"))
   compose.waitUntil(15000){care.relay.requests.any{it.id=="voice192-reject"}}
   compose.runOnUiThread{voice.select(care.relay.requests.first{it.id=="voice192-reject"});voice.submitFinal("승인하지 마")}
   compose.waitUntil(18000){care.relay.requests.any{it.id=="voice192-reject"&&it.status=="rejected"}};proof.put("negationRejected",true)
   // A paired passenger's exact seat/range grant also works through voice approval.
   val family=care.relay.links.getValue("family");val paired=api(family,"pair-device")
   val pairedLink=family.substringBeforeLast('.')+"."+paired.getString("token")
   api(pairedLink,"request",JSONObject().put("id","voice192-permission").put("client","voice-test").put("kind","permission").put("title","조수석 온도 권한 요청").put("options",JSONObject().put("name","동승자").put("seat","ROW_1_RIGHT").put("scopes",org.json.JSONArray().put("climate")).put("limits",JSONObject().put("climate",JSONObject().put("min",21).put("max",25)))))
   compose.waitUntil(15000){care.relay.requests.any{it.id=="voice192-permission"}}
   compose.runOnUiThread{voice.select(care.relay.requests.first{it.id=="voice192-permission"});voice.submitFinal("승인")}
   compose.waitUntil(20000){api(pairedLink,"status").optJSONObject("grant")?.optBoolean("active")==true}
   val grant=api(pairedLink,"status").getJSONObject("grant");assertEquals("ROW_1_RIGHT",grant.getString("seat"));assertEquals(25.0,grant.getJSONObject("limits").getJSONObject("climate").getDouble("max"),0.01)
   proof.put("voiceSeatGrant",true)
   // A fresh moving-mode vehicle still permits an explicit driver's comfort approval.
   val target=if(original<25)original+.5 else original-.5
   // Queue a legitimate parked owner request, then receive it after D engages.
   // Only network delivery is delayed: gear and climate remain actual SDK signals.
   val workerField=RelayClient::class.java.getDeclaredField("worker").apply{isAccessible=true}
   val worker=workerField.get(care.relay) as java.util.concurrent.ExecutorService
   val held=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
   worker.execute{held.countDown();release.await(12,java.util.concurrent.TimeUnit.SECONDS)}
   assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS))
   try{
    api(owner,"request",JSONObject().put("id","voice192-climate").put("kind","climate").put("client","voice-test").put("title","온도 변경").put("target",target))
    gear("Drive");compose.waitUntil(10000){!care.parked&&care.engine.value(Signal.GEAR,care.now())!=4.0}
   }finally{release.countDown()}
   compose.waitUntil(15000){care.relay.requests.any{it.id=="voice192-climate"&&it.status=="pending"}}
   compose.runOnUiThread{voice.select(care.relay.requests.first{it.id=="voice192-climate"});voice.submitFinal("승인해 줘")}
   compose.waitUntil(18000){care.relay.requests.any{it.id=="voice192-climate"&&it.status=="confirmed"}&&care.engine.value(Signal.CLIMATE_TARGET,care.now())==target};proof.put("climateSdkReadback",target)
   compose.runOnUiThread{assertFalse(care.canVoiceDecide(PassengerRequest("blocked","control","창문",options="{\"control\":\"window.position:ROW_1_RIGHT\"}")))}
   proof.put("movingWindowDenied",true)
   // Lay out a real route with the production TMAP renderer.
   gear("Park");compose.waitUntil(15000){care.parked}
   compose.runOnUiThread{care.navigation.plan("경로 안내 확인",GeoPoint(37.495,127.045))}
   compose.waitUntil(40000){!care.navigation.loading&&care.navigation.route!=null}
   compose.runOnUiThread{care.navigation.start()};compose.waitUntil(10000){care.navigation.active}
   compose.waitUntil(60000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
   val map=compose.onNodeWithTag("live-map").fetchSemanticsNode().boundsInRoot
   val nav=compose.onNodeWithTag("vehicle-navigation").fetchSemanticsNode().boundsInRoot
   assertTrue("Map no longer reserves a blank sidebar",map.width>=nav.width-2)
   val card=compose.onNodeWithTag("guidance-floating-card").fetchSemanticsNode().boundsInRoot
   assertTrue(card.bottom<map.bottom);proof.put("mapWidth",map.width).put("navigationWidth",nav.width).put("spaceBelowCard",map.bottom-card.bottom)
   SystemClock.sleep(1800);capture("voice192-navigation.png")
   compose.onNodeWithTag("driver-microphone").performClick();compose.onNodeWithTag("voice-listen").performClick()
   compose.waitUntil(10000){compose.onAllNodesWithTag("voice-ready").fetchSemanticsNodes().isNotEmpty()};capture("voice192-microphone.png")
   compose.onNodeWithText("닫기").performClick();compose.onNodeWithTag("driver-voice-dialog").assertDoesNotExist();proof.put("micStartCancelUi",true)
  }catch(t:Throwable){proof.put("error",t.stackTraceToString());throw t}finally{
   compose.runOnUiThread{voice.close();care.navigation.cancel()};gear("Park");compose.waitUntil(15000){care.parked&&!care.climatePending}
   if(original!=null){var result="";compose.runOnUiThread{care.applyClimate(original!!){s,_->result=s}};compose.waitUntil(16000){result.isNotBlank()}}
   File(compose.activity.filesDir,"voice192-proof.json").writeText(proof.toString(2))
   compose.runOnUiThread{care.relay.end()}
  }
 }
}

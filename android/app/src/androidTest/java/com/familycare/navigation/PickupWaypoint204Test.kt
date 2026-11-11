package com.familycare.navigation
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

class PickupWaypoint204Test {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun api(link:String,action:String,b:JSONObject=JSONObject()):JSONObject{
  val p=android.net.Uri.parse(link).fragment!!.split('.',limit=2)
  val c=URL("http://127.0.0.1:8765/api/sessions/${p[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=60000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${p[1]}");c.outputStream.use{it.write(b.toString().toByteArray())};val code=c.responseCode;val r=JSONObject((if(code==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});assertEquals("$action: $r",200,code);return r}finally{c.disconnect()}
 }
 private fun gear(value:String){val c=URL("http://127.0.0.1:8088/vehicle/gear?gear=$value").openConnection() as HttpURLConnection;try{c.requestMethod="PUT";assertEquals(200,c.responseCode)}finally{c.disconnect()}}
 @Test fun pickupPreservesDestinationAndMovingGuidance(){
  val care=CareRuntime.get(compose.activity);val nav=care.navigation;val voice=DriverVoice(compose.activity,care);val proof=JSONObject()
  try{
   compose.waitUntil(30000){care.parked&&nav.locationUsable}
   compose.runOnUiThread{care.share()};compose.waitUntil(20000){care.relay.connected&&care.relay.links.containsKey("family")}
   val root=care.relay.links.getValue("family");val paired=api(root,"pair-device");val phone=root.substringBeforeLast('.')+"."+paired.getString("token")
   api(care.relay.links.getValue("owner"),"pickup-share",JSONObject().put("enabled",true))
   val destination=GeoPoint(37.503,127.05)
   compose.runOnUiThread{nav.plan("유지할 최종 목적지",destination)}
   compose.waitUntil(60000){!nav.loading&&nav.route!=null};assertEquals(destination,nav.target)
   compose.runOnUiThread{nav.start()};compose.waitUntil(12000){nav.active}
   gear("Drive");compose.waitUntil(15000){!care.parked&&care.engine.value(Signal.GEAR,care.now())!=4.0}
   val draft=api(phone,"pickup-location",JSONObject().put("provider","tmap").put("consent",true).put("point",JSONArray(listOf(127.0356,37.4899))).put("accuracy",8).put("ageMs",0))
   fun send(id:String){api(phone,"request",JSONObject().put("id",id).put("client","waypoint204").put("kind","pickup").put("title","위치 전달").put("options",JSONObject().put("draft",draft.getString("draft")).put("name","동승 가족").put("landmark","픽업 확인 건물 정문").put("message","정문 앞에서 기다립니다")))}
   send("pickup204-speech");compose.waitUntil(20000){care.relay.requests.any{it.id=="pickup204-speech"}}
   val before=nav.routeIdentity
   val pcm=InstrumentationRegistry.getInstrumentation().context.assets.open("approval192.pcm").use{it.readBytes()}
   compose.runOnUiThread{voice.show();assertEquals(care.relay.requests.joinToString{"${it.kind}:${it.status}:${it.message}:${it.delegated}"},"pickup204-speech",voice.selected?.id);voice.start(pcm)}
   compose.waitUntil(20000){!voice.listening};assertEquals("${voice.text} / ${voice.status}",VoiceCommand.APPROVE,DriverVoicePolicy.command(voice.text))
   compose.waitUntil(80000){care.relay.requests.any{it.id=="pickup204-speech"&&it.status in listOf("confirmed","needs_review","rejected")}}
   assertEquals("confirmed",care.relay.requests.first{it.id=="pickup204-speech"}.status)
   assertEquals(destination,nav.target);assertEquals("유지할 최종 목적지",nav.route!!.destination);assertTrue(nav.hasWaypoint);assertTrue(nav.active);assertNotEquals(before,nav.routeIdentity)
   val waypoint=nav.summary().getJSONObject("waypoint")
   assertEquals(37.4899,waypoint.getDouble("lat"),0.001);assertEquals(127.0356,waypoint.getDouble("lon"),0.001)
   proof.put("realTmapWaypoint",true).put("destinationPreserved",true).put("movingGuidanceContinued",true).put("actualPleosSpeech",voice.text)
   // Explicit pickup command acts on the selected, unchanged pickup only.
   SystemClock.sleep(2200);send("pickup204-explicit");compose.waitUntil(20000){care.relay.requests.any{it.id=="pickup204-explicit"}}
   compose.runOnUiThread{voice.select(care.relay.requests.first{it.id=="pickup204-explicit"});voice.submitFinal("픽업 경유해 줘")}
   compose.waitUntil(80000){care.relay.requests.any{it.id=="pickup204-explicit"&&it.status in listOf("confirmed","needs_review","rejected")}}
   assertEquals("confirmed",care.relay.requests.first{it.id=="pickup204-explicit"}.status);assertEquals(destination,nav.target);assertTrue(nav.active);proof.put("explicitPickupCommand",true)
   var blocked="";val saved=nav.routeIdentity
   compose.runOnUiThread{nav.planPickup("다른 경유지",GeoPoint(37.491,127.042),true){ok,msg->assertFalse(ok);blocked=msg}}
   assertTrue(blocked.contains("이미 경유지"));assertEquals(saved,nav.routeIdentity);assertEquals(destination,nav.target);assertTrue(nav.active);proof.put("existingStopNotOverwritten",true)
   gear("Park");compose.waitUntil(15000){care.parked};SystemClock.sleep(2200)
   var fallback=false;compose.runOnUiThread{nav.cancel();nav.planPickup("픽업 장소",GeoPoint(37.4899,127.0356)){ok,_->fallback=ok}}
   compose.waitUntil(60000){fallback};assertEquals("픽업 장소",nav.route!!.destination);assertFalse(nav.hasWaypoint);proof.put("noDestinationFallback",true)
  }catch(t:Throwable){proof.put("error",t.stackTraceToString());throw t}finally{
   gear("Park");compose.runOnUiThread{voice.close();nav.cancel();care.relay.end()}
   File(compose.activity.filesDir,"pickup204-proof.json").writeText(proof.toString(2))
  }
 }
}

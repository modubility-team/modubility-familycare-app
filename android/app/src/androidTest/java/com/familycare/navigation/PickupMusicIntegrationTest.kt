package com.familycare.navigation
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import kotlin.math.sin
class PickupMusicIntegrationTest{
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun api(link:String,action:String,b:JSONObject=JSONObject()):JSONObject{
  val p=android.net.Uri.parse(link).fragment!!.split('.',limit=2);val c=URL("http://127.0.0.1:8765/api/sessions/${p[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=60000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${p[1]}");c.outputStream.use{it.write(b.toString().toByteArray())};val code=c.responseCode;val result=JSONObject((if(code==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});assertEquals("$action: $result",200,code);return result}finally{c.disconnect()}
 }
 @Test fun actualTmapPickupSpeechAndPcmPlayback(){
  val care=CareRuntime.get(compose.activity);val proof=JSONObject()
  try{
   compose.waitUntil(30000){care.parked&&care.navigation.locationUsable}
   compose.runOnUiThread{care.share()};compose.waitUntil(20000){care.relay.links["family"]!=null&&care.relay.connected}
   val owner=care.relay.links.getValue("owner");val root=care.relay.links.getValue("family");val paired=api(root,"pair-device");val phone=root.substringBeforeLast('.')+"."+paired.getString("token")
   api(owner,"pickup-share",JSONObject().put("enabled",true));compose.waitUntil(15000){api(phone,"status").getJSONObject("pickup").optJSONObject("vehicle")!=null}
   proof.put("liveVehicleGpsShared",true)
   val draft=api(phone,"pickup-location",JSONObject().put("provider","tmap").put("consent",true).put("point",JSONArray(listOf(127.0356,37.4899))).put("accuracy",8).put("ageMs",0))
   assertTrue(draft.getString("address").isNotBlank());proof.put("tmapRoadAddress",draft.getString("address"))
   SystemClock.sleep(1200)
   val places=api(phone,"maps-search",JSONObject().put("provider","tmap").put("consent",true).put("query","주차장").put("center",JSONArray(listOf(127.0356,37.4899))))
   proof.put("parkingLookupResponse",places.has("results")||places.has("places"));proof.put("parkingResultKeys",JSONArray(places.keys().asSequence().toList()))
   val sent=api(phone,"request",JSONObject().put("id","pickup-19").put("client","test").put("kind","pickup").put("title","위치 전달").put("options",JSONObject().put("draft",draft.getString("draft")).put("name","동승 예정 가족").put("landmark","공개 시험 지점 건물 정문 앞").put("message","큰 여행 가방을 가지고 기다릴게요. 도착하면 확인해 주세요.")))
   assertTrue(sent.getString("title").contains("기다리고"));compose.waitUntil(20000){care.relay.requests.any{it.id=="pickup-19"}}
   compose.waitUntil(40000){care.driverNotice.contains("정문")};proof.put("spokenNotice",care.driverNotice)
   compose.waitUntil(40000){care.audioResult.contains("음성")&&care.audioResult.contains("완료")};proof.put("pleosAudioResult",care.audioResult)
   SystemClock.sleep(1200);val eta=api(phone,"pickup-eta",JSONObject().put("provider","tmap").put("consent",true));assertTrue(eta.getDouble("seconds")>0);proof.put("roadEtaSeconds",eta.getDouble("seconds"))
   compose.runOnUiThread{care.decide(care.relay.requests.first{it.id=="pickup-19"},true)}
   compose.waitUntil(80000){care.relay.requests.any{it.id=="pickup-19"&&it.status in listOf("confirmed","needs_review","rejected")}}
   assertEquals("confirmed",care.relay.requests.first{it.id=="pickup-19"}.status);assertNotNull(care.navigation.route);proof.put("pickupRouteReady",true)
   val stream=api(owner,"music-start").getString("stream")
   // Original test tone only, not a recording or a substitute for the user's physical-phone test.
   val data=ByteBuffer.allocate(38400).order(ByteOrder.LITTLE_ENDIAN)
   for(i in 0 until 9600){val value=(sin(2*Math.PI*440*i/48000)*1800).toInt().toShort();data.putShort(value);data.putShort(value)}
   val pcm=Base64.encodeToString(data.array(),Base64.NO_WRAP)
   val before=care.musicFrames
   for(i in 0 until 30){api(owner,"music-frame",JSONObject().put("stream",stream).put("seq",i).put("pcm",pcm).put("peak",1800));SystemClock.sleep(190)}
   proof.put("musicPlaybackStatus",care.musicStatus);assertTrue(care.musicStatus.contains("재생 중"))
   api(owner,"music-command",JSONObject().put("command","pause"));compose.waitUntil(10000){care.musicStatus.contains("일시정지")};assertTrue(care.musicFrames>before);proof.put("actualAudioTrackFrames",care.musicFrames-before)
   api(owner,"music-command",JSONObject().put("command","resume"))
   for(i in 30 until 40){api(owner,"music-frame",JSONObject().put("stream",stream).put("seq",i).put("pcm",pcm).put("peak",1800));SystemClock.sleep(190)}
   compose.waitUntil(5000){care.musicStatus.contains("재생 중")};api(owner,"music-command",JSONObject().put("command","stop"));compose.waitUntil(10000){care.musicStatus.contains("시작")};proof.put("pauseResumeStop",true)
   api(owner,"pickup-share",JSONObject().put("enabled",false));assertTrue(api(phone,"status").getJSONObject("pickup").isNull("vehicle"));proof.put("shareOffHidesVehicle",true)
   proof.put("physicalPhoneCaptureVerified",false)
  }catch(t:Throwable){proof.put("error",t.stackTraceToString());throw t}finally{
   File(compose.activity.filesDir,"family19-proof.json").writeText(proof.toString(2))
   compose.runOnUiThread{care.navigation.cancel();care.relay.end()}
  }
 }
}

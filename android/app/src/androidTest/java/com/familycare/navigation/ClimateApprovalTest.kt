package com.familycare.navigation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock

/** Real emulator signals and SDK readbacks; no injected ignition or grants. */
class ClimateApprovalTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun api(link:String,action:String,body:JSONObject=JSONObject()):Pair<Int,JSONObject>{
  val parts=android.net.Uri.parse(link).fragment!!.split('.',limit=2)
  val c=URL("http://127.0.0.1:8765/api/sessions/${parts[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000
   c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${parts[1]}")
   c.outputStream.use{it.write(body.toString().toByteArray())};val status=c.responseCode
   return status to JSONObject((if(status==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()})
  }finally{c.disconnect()}
 }
 private fun req(id:String,target:Double)=JSONObject().put("id",id).put("client","untrusted").put("kind","climate").put("title","동승자 온도 조절").put("target",target)
 private fun gear(value:String){
  val c=URL("http://127.0.0.1:8088/vehicle/gear?gear=$value").openConnection() as HttpURLConnection
  try{c.requestMethod="PUT";assertEquals(200,c.responseCode)}finally{c.disconnect()}
 }
 @Test fun passengerTemperatureOnlyChangesAfterDriverApproval(){
  val care=CareRuntime.get(compose.activity);var original:Double?=null
  try{
   compose.waitUntil(20000){care.parked&&care.climateSupported&&care.engine.value(Signal.CLIMATE_TARGET,care.now())!=null}
   assertEquals("connection",care.ignition.mode)
   original=care.engine.value(Signal.CLIMATE_TARGET,care.now())!!
   val target=if(original<25)original+0.5 else original-0.5
   compose.runOnUiThread{care.share()};compose.waitUntil(15000){care.relay.links["family"]!=null&&care.relay.message.contains("휴대폰 연결됨")}
   compose.waitUntil(6000){care.ignition.on}
   val root=care.relay.links.getValue("family")
   compose.waitUntil(10000){api(root,"status").second.optJSONObject("state")?.optBoolean("authorityActive")==true}
   val pair=api(root,"pair-device");assertEquals(200,pair.first)
   val link=root.substringBeforeLast('.')+"."+pair.second.getString("token")
   assertEquals(403,api(link,"request",req("denied",target)).first)
   val permission=JSONObject().put("id","permission-test").put("client","untrusted").put("kind","permission").put("title","가족 · 조절 권한 요청").put("options",JSONObject().put("name","가족").put("scopes",JSONArray().put("climate")))
   assertEquals(200,api(link,"request",permission).first)
   compose.waitUntil(15000){care.relay.requests.any{it.id=="permission-test"}}
   assertEquals(original,care.engine.value(Signal.CLIMATE_TARGET,care.now())!!,0.01)
   compose.onNodeWithTag("home-requests").performClick()
   compose.onNodeWithText("선택한 권한 승인").performScrollTo().performClick()
   compose.waitUntil(16000){care.relay.delegations.any{it.optString("deviceId")==pair.second.getJSONObject("grant").getString("deviceId")&&it.optBoolean("active")}}
   assertTrue(api(link,"status").second.getJSONObject("grant").getBoolean("active"))
   SystemClock.sleep(2100)
   assertEquals(200,api(link,"request",req("first",target)).first)
   compose.waitUntil(18000){care.relay.requests.any{it.id=="first"&&it.status=="confirmed"}&&care.engine.value(Signal.CLIMATE_TARGET,care.now())==target}
   // A second change in D is allowed by the same comfort grant, without a driver approval.
   gear("Drive");compose.waitUntil(10000){!care.parked};SystemClock.sleep(2100)
   assertEquals(200,api(link,"request",req("second",original)).first)
   compose.waitUntil(18000){care.relay.requests.any{it.id=="second"&&it.status=="confirmed"}&&care.engine.value(Signal.CLIMATE_TARGET,care.now())==original}
   val forbidden=JSONObject().put("id","forbidden").put("client","untrusted").put("kind","control").put("title","미승인 기능").put("options",JSONObject().put("control","window.position:ROW_1_LEFT").put("value",0))
   assertEquals(403,api(link,"request",forbidden).first)
   gear("Park");compose.waitUntil(10000){care.parked}
   assertEquals(200,api(care.relay.links.getValue("owner"),"revoke-grant",JSONObject().put("deviceId",pair.second.getJSONObject("grant").getString("deviceId"))).first)
   SystemClock.sleep(2100);assertEquals(403,api(link,"request",req("after-revoke",target)).first)
  }finally{
   gear("Park");compose.waitUntil(15000){care.parked&&!care.climatePending}
   if(original!=null){var result="";compose.runOnUiThread{care.applyClimate(original!!){s,_->result=s}};compose.waitUntil(18000){result.isNotBlank()};assertEquals("confirmed",result)}
   compose.runOnUiThread{care.relay.end()};compose.waitUntil(15000){!care.relay.sharing}
  }
 }
}

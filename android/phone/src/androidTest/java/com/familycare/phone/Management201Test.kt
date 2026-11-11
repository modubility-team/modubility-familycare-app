package com.familycare.phone

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.testTag
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated relay fixtures validate UI semantics. Live hardware proof is a separate test. */
class Management201Test {
 @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
 private fun api(path:String,body:JSONObject=JSONObject(),token:String=""):JSONObject {
  val c=URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000
   c.setRequestProperty("Content-Type","application/json");if(token.isNotBlank())c.setRequestProperty("Authorization","Bearer $token")
   c.outputStream.use{it.write(body.toString().toByteArray())};val code=c.responseCode
   val result=JSONObject((if(code==200)c.inputStream else c.errorStream).bufferedReader().readText())
   check(code==200){"$path: $code $result"};return result
  }finally{c.disconnect()}
 }
 @Test fun selectedSeatPowerAndHistoryRemainCorrectAfterPollingAndReconnection(){
  val owner=BuildConfig.PRODUCT=="navigation";val m=compose.activity.model
  val s=api("/api/sessions");val prefix="/api/sessions/"+s.getString("id");val car=s.getString("token")
  val link="http://127.0.0.1:8765/join#"+s.getJSONObject("links").getString(if(owner)"owner" else "family").substringAfter('#')
  val ownerToken=s.getJSONObject("links").getString("owner").substringAfter('#').substringAfter('.')
  val zones=JSONArray("""[{"id":"ROW_1_LEFT","label":"운전석","min":18,"max":28,"target":24.5},{"id":"ROW_1_RIGHT","label":"조수석","min":18,"max":28,"target":20.5}]""")
  val state=JSONObject().put("climateSupported",true).put("climatePowerSupported",true).put("climatePower",true).put("climateTarget",24.5).put("climateAreas",zones).put("parked",true).put("authorityActive",true).put("authorityEpoch","ui201").put("authorityMode","connection")
  val executor=Executors.newSingleThreadScheduledExecutor()
  fun finish(id:String){api("$prefix/claim",JSONObject().put("id",id),car);api("$prefix/result",JSONObject().put("id",id).put("status","confirmed").put("message","UI fixture completion"),car)}
  try{
   api("$prefix/sync",state,car)
   executor.scheduleAtFixedRate({runCatching{api("$prefix/sync",state,car)}},2,2,TimeUnit.SECONDS)
   compose.runOnUiThread{m.joinText=link;m.connect();compose.activity.setContent{Box(Modifier.width(390.dp).height(820.dp)){PhoneScreen(m){_,_,_->}}}}
   compose.waitUntil(18000){m.connected&&!m.stale&&(owner||m.permissionPrompt>0)}
   if(!owner){
    compose.runOnUiThread{m.requestPermissions("UI 확인",setOf("climate","climatePower"),"ROW_1_RIGHT",JSONObject().put("climate",JSONObject().put("min",18).put("max",28)))}
    compose.waitUntil(12000){m.requests.any{it.optString("kind")=="permission"}}
    finish(m.requests.last{it.optString("kind")=="permission"}.getString("id"))
    compose.waitUntil(12000){m.canClimate}
   }
   compose.onNodeWithTag("phone-tab-"+if(owner)"관리" else "요청").performClick()
   if(owner)compose.onNodeWithText("조수석",useUnmergedTree=true).performScrollTo().performClick()
   compose.onNodeWithTag("climate-actual").assertTextEquals("조수석 · 차량 설정 20.5°C")
   compose.onNodeWithTag("temperature-selected").assertTextEquals("20.5°C")
   compose.onNodeWithTag("temperature-up").performScrollTo().performClick()
   compose.runOnUiThread{m.refresh()};compose.waitUntil(6000){!m.busy}
   compose.onNodeWithTag("temperature-selected").assertTextEquals("21.0°C")
   compose.onNodeWithTag(if(owner)"owner-set-climate" else "request-climate").performScrollTo().performClick()
   compose.waitUntil(12000){m.requests.any{it.optString("kind")=="climate"}}
   val temp=m.requests.last{it.optString("kind")=="climate"}
   assertEquals("ROW_1_RIGHT",temp.getJSONObject("options").getString("area"));assertEquals(21.0,temp.getDouble("target"),0.01)
   finish(temp.getString("id"));zones.getJSONObject(1).put("target",21.0);api("$prefix/sync",state,car)
   compose.waitUntil(12000){m.climateTarget("ROW_1_RIGHT")==21.0}
   compose.onNodeWithTag("climate-actual").assertTextEquals("조수석 · 차량 설정 21.0°C")
   compose.onNodeWithTag("climate-power-toggle").performScrollTo().performClick()
   compose.waitUntil(12000){m.requests.any{it.optJSONObject("options")?.opt("power")==false}}
   val off=m.requests.last{it.optJSONObject("options")?.opt("power")==false}
   assertEquals(21.0,off.getDouble("target"),0.01);assertEquals("ROW_1_RIGHT",off.getJSONObject("options").getString("area"))
   finish(off.getString("id"));state.put("climatePower",false);api("$prefix/sync",state,car)
   compose.waitUntil(12000){m.state.opt("climatePower")==false}
   compose.onNodeWithTag("climate-power-toggle").performScrollTo().performClick()
   compose.waitUntil(12000){m.requests.any{it.getString("id")!=temp.getString("id")&&it.optJSONObject("options")?.opt("power")==true}}
   val on=m.requests.last{it.optJSONObject("options")?.opt("power")==true}
   assertEquals(21.0,on.getDouble("target"),0.01)
   if(owner){
    finish(on.getString("id"));compose.waitUntil(12000){m.requests.any{it.getString("id")==on.getString("id")&&it.optString("status")=="confirmed"}}
    compose.onNodeWithTag("delete-result-"+temp.getString("id")).performScrollTo().performClick()
    assertFalse(m.managementRequests.any{it.getString("id")==temp.getString("id")})
    assertTrue(m.requests.any{it.getString("id")==temp.getString("id")})
    Thread.sleep(2100)
    api("$prefix/request",JSONObject().put("id","pending201").put("client","ui201").put("kind","note").put("title","처리 대기 기록"),ownerToken)
    compose.runOnUiThread{m.refresh()};compose.waitUntil(12000){m.requests.any{it.getString("id")=="pending201"}}
    compose.onNodeWithTag("clear-management-results").performScrollTo().performClick()
    assertEquals(listOf("pending201"),m.managementRequests.map{it.getString("id")})
    compose.runOnUiThread{m.joinText=link;m.connect()};compose.waitUntil(12000){!m.stale&&m.requests.size>=4}
    assertEquals(listOf("pending201"),m.managementRequests.map{it.getString("id")})
    // Selected zone with a missing reading must never display the driver's temperature.
    zones.getJSONObject(1).put("target",JSONObject.NULL);api("$prefix/sync",state,car)
    compose.waitUntil(12000){m.climateTarget("ROW_1_RIGHT")==null}
   }
  }finally{executor.shutdownNow();compose.runOnUiThread{m.disconnect()};api("$prefix/end",token=car)}
 }
}

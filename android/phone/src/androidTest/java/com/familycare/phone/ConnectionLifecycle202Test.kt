package com.familycare.phone

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import android.os.SystemClock

/** Host supplies an isolated, continuously heartbeating fixture and kills this app between phases. */
class ConnectionLifecycle202Test {
 @get:Rule val rule=ActivityScenarioRule(PhoneActivity::class.java)
 private lateinit var activity:PhoneActivity
 private lateinit var m:PhoneModel
 private lateinit var fixture:JSONObject
 private val owner get()=BuildConfig.PRODUCT=="navigation"
 private fun init(){rule.scenario.onActivity{activity=it;m=it.model};fixture=JSONObject(File(activity.filesDir,"connection202-fixture.json").readText())}
 private fun main(f:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(f)
 private fun waitFor(label:String,timeout:Long=18000,predicate:()->Boolean){val until=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<until){if(predicate())return;Thread.sleep(100)};fail(label+": "+m.message+" / "+m.resultMessage)}
 private fun api(action:String,body:JSONObject=JSONObject(),token:String=fixture.getString("car")):JSONObject {
  val c=URL("http://127.0.0.1:8765"+fixture.getString("prefix")+"/"+action).openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000;c.setRequestProperty("Authorization","Bearer $token");c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())};val status=c.responseCode;val j=JSONObject((if(status==200)c.inputStream else c.errorStream).bufferedReader().readText());check(status==200){"$action $status $j"};return j}finally{c.disconnect()}
 }
 private fun approve(){
  if(owner)return
  waitFor("paired"){m.permissionPrompt>0&&m.canRequest}
  main{m.requestPermissions("연결 유지 확인",setOf("climate","climatePower"),"ROW_1_RIGHT",JSONObject().put("climate",JSONObject().put("min",18).put("max",28)))}
  waitFor("permission request"){m.requests.any{it.optString("kind")=="permission"&&it.optString("status")=="pending"}}
  val id=m.requests.last{it.optString("kind")=="permission"&&it.optString("status")=="pending"}.getString("id")
  api("claim",JSONObject().put("id",id));api("result",JSONObject().put("id",id).put("status","confirmed"));waitFor("approved"){m.canClimate}
 }
 @Test fun phase1ConnectAndBackground(){
  init();main{m.joinText=fixture.getString("join");m.connect()}
  waitFor("connected"){m.connected&&!m.stale&&(owner||m.permissionPrompt>0)};approve()
  waitFor("automatic foreground connection"){PhoneCareService.running&&PhoneCareService.lastStatusAt>0}
  val saved=ConnectionStore(activity).read()!!
  assertEquals(!owner,saved.paired)
  val sealed=activity.getSharedPreferences("vehicle-session",0).getString("sealed","").orEmpty()
  assertFalse("credential is not plaintext",sealed.contains(saved.link.token));assertFalse(sealed.contains(saved.link.origin))
  val device=m.grant.optString("deviceId");val last=PhoneCareService.lastStatusAt
  rule.scenario.moveToState(Lifecycle.State.CREATED)
  waitFor("background status continues",22000){PhoneCareService.lastStatusAt>last+15000}
  assertNotNull(ConnectionStore(activity).read())
  rule.scenario.moveToState(Lifecycle.State.RESUMED)
  waitFor("foreground resumes same grant"){!m.stale&&m.canClimate}
  assertEquals(device,m.grant.optString("deviceId"))
  File(activity.filesDir,"connection202-before.json").writeText(JSONObject().put("deviceId",device).put("backgroundKept",true).put("encrypted",true).toString())
 }
 @Test fun phase2RestoreRevokeAndDisconnect(){
  init();val before=JSONObject(File(activity.filesDir,"connection202-before.json").readText())
  // No QR or connect() call: the host force-stopped the process after phase 1.
  waitFor("automatic restoration after process death"){m.connected&&!m.stale&&m.canClimate}
  assertEquals(before.getString("deviceId"),m.grant.optString("deviceId"));assertEquals(0,m.permissionPrompt)
  waitFor("service restarted"){PhoneCareService.running}
  rule.scenario.recreate();rule.scenario.onActivity{activity=it;m=it.model}
  waitFor("activity recreation"){m.connected&&!m.stale&&m.canClimate}
  if(!owner){
   api("revoke-grant",JSONObject().put("deviceId",m.grant.getString("deviceId")),fixture.getString("owner"))
   main{m.refresh()};waitFor("revoked grant is not restored"){!m.grant.optBoolean("active")&&!m.canClimate}
   assertTrue(m.connected)
  }
  val saved=ConnectionStore(activity).read()!!
  main{m.disconnect()};waitFor("service stopped"){!PhoneCareService.running}
  assertNull(ConnectionStore(activity).read());assertFalse(m.connected)
  assertFalse("late service response cannot resurrect disconnected session",ConnectionStore(activity).saveVerified(saved.link,saved.paired,7200))
  File(activity.filesDir,"connection202-proof.json").writeText(before.put("processRestored",true).put("sameDeviceAndGrant",true).put("explicitDisconnectClears",true).put("lateResponseBlocked",true).put("revocationChecked",!owner).toString())
 }
 @Test fun phase3ExplicitDisconnectDoesNotRestore(){
  init();Thread.sleep(1000);assertFalse(m.connected);assertNull(ConnectionStore(activity).read());assertFalse(PhoneCareService.running)
  main{m.joinText=fixture.getString("join");m.connect()};waitFor("reconnected"){m.connected&&!m.stale&&(owner||m.permissionPrompt>0)}
  waitFor("saved connection"){ConnectionStore(activity).read()!=null}
  api("end")
  waitFor("server expiry clears saved connection",18000){!m.connected&&ConnectionStore(activity).read()==null}
  waitFor("ended service stopped"){!PhoneCareService.running}
  val proof=JSONObject(File(activity.filesDir,"connection202-proof.json").readText()).put("noRestoreAfterDisconnect",true).put("serverEndClears",true)
  File(activity.filesDir,"connection202-proof.json").writeText(proof.toString())
 }
}

package com.familycare.navigation
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock
import java.io.File
class Family18IntegrationTest{
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private var n=0
 private fun api(link:String,action:String,body:JSONObject=JSONObject()):Pair<Int,JSONObject>{
  val p=android.net.Uri.parse(link).fragment!!.split('.',limit=2);val c=URL("http://127.0.0.1:8765/api/sessions/${p[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=90000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${p[1]}");c.outputStream.use{it.write(body.toString().toByteArray())};val status=c.responseCode;return status to JSONObject((if(status==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()})}finally{c.disconnect()}
 }
 private fun request(link:String,kind:String,o:JSONObject=JSONObject(),target:Double?=null):Pair<Int,JSONObject>{SystemClock.sleep(2200);val b=JSONObject().put("id","f18-${++n}").put("client","unused").put("kind",kind).put("title","새 기능 통합 확인").put("options",o);if(target!=null)b.put("target",target);return api(link,"request",b)}
 private fun shot(name:String){val bitmap=compose.onAllNodes(isRoot())[compose.onAllNodes(isRoot()).fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap();File(compose.activity.filesDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
 @Test fun seatAgreementSituationAndRealRoutes(){
  val care=CareRuntime.get(compose.activity);val proof=JSONObject();var originalRight=22.0;var fan="";var originalFan=1.0;var primaryFailure:Throwable?=null;var originalPower:Boolean?=null;var originalLeft=22.0
  fun waitResult(id:String){compose.waitUntil(24000){care.relay.requests.any{it.id==id&&it.status in listOf("confirmed","rejected","needs_review")}};assertEquals(id,"confirmed",care.relay.requests.first{it.id==id}.status)}
  try{
   compose.waitUntil(25000){care.parked&&care.climateSupported&&com.familycare.ui.SeatAccess.objects(care.climateZones).count{!it.isNull("target")}>=2&&com.familycare.ui.SeatAccess.objects(care.vehicleControls).any{it.optString("id").startsWith("hvac.fan:")&&it.optBoolean("available")&&!it.isNull("value")}}
   originalRight=com.familycare.ui.SeatAccess.objects(care.climateZones).first{it.optString("id")=="ROW_1_RIGHT"}.getDouble("target")
   originalLeft=com.familycare.ui.SeatAccess.objects(care.climateZones).first{it.optString("id")=="ROW_1_LEFT"}.getDouble("target")
   val fc=com.familycare.ui.SeatAccess.objects(care.vehicleControls).first{it.optString("id").startsWith("hvac.fan:")};fan=fc.getString("id");originalFan=fc.getDouble("value")
   compose.runOnUiThread{care.share()};compose.waitUntil(18000){care.relay.links["family"]!=null&&care.ignition.on&&care.relay.permissionCatalog.isNotEmpty()}
   val root=care.relay.links.getValue("family");val pa=api(root,"pair-device").second;val pb=api(root,"pair-device").second
   val a=root.substringBeforeLast('.')+"."+pa.getString("token");val b=root.substringBeforeLast('.')+"."+pb.getString("token");val aid=pa.getJSONObject("grant").getString("deviceId")
   fun approve(link:String,seat:String,ui:Boolean){
    val limits=JSONObject().put("climate",JSONObject().put("min",21).put("max",25)).put("control:"+fan,JSONObject().put("min",2).put("max",6))
    val r=request(link,"permission",JSONObject().put("name",if(ui)"조수석 보호자" else "운전석 동승자 시험").put("seat",seat).put("scopes",JSONArray(listOf("climate","control:"+fan,"care","route"))).put("limits",limits));assertEquals(200,r.first);val id=r.second.getString("id")
    compose.waitUntil(12000){care.relay.requests.any{it.id==id}}
    if(ui){compose.onNodeWithTag("home-requests").performClick();compose.onNodeWithTag("approve-seat-permission").performScrollTo();shot("family18-permission.png");compose.onNodeWithTag("approve-seat-permission").performClick()}
    else compose.runOnUiThread{care.decide(care.relay.requests.first{it.id==id},true)}
    waitResult(id);compose.waitUntil(10000){api(link,"status").second.getJSONObject("grant").optBoolean("active")}
   }
   approve(a,"ROW_1_RIGHT",true);proof.put("firstGrantApproved",true);approve(b,"ROW_1_LEFT",false);proof.put("secondGrantApproved",true)
   assertEquals(403,request(a,"climate",JSONObject().put("area","ROW_1_LEFT"),23.0).first)
   assertEquals(403,request(a,"climate",JSONObject().put("area","ROW_1_RIGHT"),26.0).first)
   val target=if(originalRight==23.0)24.0 else 23.0;val change=request(a,"climate",JSONObject().put("area","ROW_1_RIGHT"),target);assertEquals(200,change.first);waitResult(change.second.getString("id"))
   compose.waitUntil(10000){com.familycare.ui.SeatAccess.objects(care.climateZones).first{it.optString("id")=="ROW_1_RIGHT"}.optDouble("target")==target}
   assertEquals(originalLeft,com.familycare.ui.SeatAccess.objects(care.climateZones).first{it.optString("id")=="ROW_1_LEFT"}.optDouble("target"),.01)
   proof.put("ownSeatReadback",target).put("otherSeatUnchanged",true).put("outOfRangeRejected",true)
   originalPower=care.engine.value(Signal.CLIMATE_POWER,care.now())==1.0
   var powered="";compose.runOnUiThread{care.applyClimate(originalLeft,power=true){status,_->powered=status}};compose.waitUntil(18000){powered.isNotBlank()};assertEquals("confirmed",powered);SystemClock.sleep(2500)
   proof.put("originalFan",originalFan).put("poweredForFanTest",true)
   val first=request(a,"control",JSONObject().put("control",fan).put("value",2));assertEquals(200,first.first);waitResult(first.second.getString("id"))
   val conflict=request(b,"control",JSONObject().put("control",fan).put("value",6));assertEquals(200,conflict.first);val rid=conflict.second.getString("id");assertEquals("awaiting_agreement",conflict.second.getString("status"))
   api(a,"agree",JSONObject().put("id",rid).put("accept",true));SystemClock.sleep(3500)
   assertEquals(2.0,com.familycare.ui.SeatAccess.objects(care.vehicleControls).first{it.optString("id")==fan}.getDouble("value"),.01)
   api(b,"agree",JSONObject().put("id",rid).put("accept",true));waitResult(rid)
   compose.waitUntil(10000){com.familycare.ui.SeatAccess.objects(care.vehicleControls).first{it.optString("id")==fan}.optDouble("value")==4.0};proof.put("sharedFanBeforeBoth",2).put("sharedFanAfterBoth",4)
   api(a,"status");val s=request(a,"care",JSONObject().put("action","start").put("mode","sleep").put("caregiver",aid).put("minutes",15).put("temperature",22.0));assertEquals(200,s.first);waitResult(s.second.getString("id"));api(a,"status")
   val note=request(b,"note",JSONObject().put("urgent",false));assertEquals(200,note.first);val status=api(a,"status").second;assertEquals("sleep",status.getJSONObject("situation").getString("mode"));assertTrue(status.getJSONArray("careInbox").length()>0);assertEquals(200,api(a,"care-ack",JSONObject().put("id",note.second.getString("id"))).first)
   proof.put("situation","sleep").put("caregiverInboxAcknowledged",true)
   val stop=request(a,"care",JSONObject().put("action","stop"));waitResult(stop.second.getString("id"))
   assertEquals(200,api(a,"route-needs",JSONObject().put("needs",JSONArray(listOf("방향 전환 적게","화장실")))).first)
   compose.waitUntil(15000){care.navigation.locationUsable}
   // A real nearby destination, fetched from the currently authorized TMAP service.
   compose.runOnUiThread{care.navigation.selectProvider(true);care.navigation.compareFamily("양재천 경로 확인",GeoPoint(37.488,127.042))}
   compose.waitUntil(100000){!care.navigation.loading&&care.navigation.familyCandidates.isNotEmpty()}
   SystemClock.sleep(2200)
   compose.runOnUiThread{care.navigation.compareFamily("양재천 경로 확인",GeoPoint(37.488,127.042),GeoPoint(37.489,127.037),"지도에서 고른 휴식 장소")}
   compose.waitUntil(100000){!care.navigation.loading&&care.navigation.familyCandidates.any{!it.isNull("via")}}
   proof.put("actualRestDetour",care.navigation.familyCandidates.first{!it.isNull("via")}.getDouble("extraSeconds"))
   val candidates=care.navigation.familyCandidates;proof.put("actualTmapCandidates",JSONArray(candidates.map{JSONObject().put("seconds",it.getDouble("seconds")).put("turns",it.getInt("turns")).put("meters",it.getDouble("meters"))}))
   compose.runOnUiThread{compose.activity.setContent{FamilyRouteDialog(care){}}};shot("family18-routes.png")
   compose.runOnUiThread{assertTrue(care.navigation.chooseFamilyRoute(candidates.first{!it.isNull("via")}.getString("id")))}
   assertNotNull(care.navigation.route);assertEquals("TMAP",care.navigation.route!!.provider);proof.put("selectedActualRoute",true)
   compose.runOnUiThread{compose.activity.setContent{CareScreen(care)}}
   compose.runOnUiThread{care.navigation.start()};compose.waitUntil(18000){care.navigation.active};shot("family18-guidance.png");proof.put("guidanceStarted",true)
  }catch(error:Throwable){primaryFailure=error;proof.put("error",error.stackTraceToString());proof.put("fanAtFailure",com.familycare.ui.SeatAccess.objects(care.vehicleControls).firstOrNull{it.optString("id")==fan});throw error}finally{
   File(compose.activity.filesDir,"family18-proof.json").writeText(proof.toString(2))
   compose.runOnUiThread{care.navigation.cancel()};compose.waitUntil(12000){care.parked&&!care.climatePending}
   var restored="";compose.runOnUiThread{care.applyClimate(originalRight,zone="ROW_1_RIGHT"){s,_->restored=s}};compose.waitUntil(18000){restored.isNotBlank()};println("Restore result: "+restored);if(primaryFailure==null)assertEquals("confirmed",restored)
   if(fan.isNotBlank()){restored="";compose.runOnUiThread{care.applyControl(fan,originalFan,true){s,_->restored=s}};compose.waitUntil(18000){restored.isNotBlank()};println("Restore result: "+restored);if(primaryFailure==null)assertEquals("confirmed",restored)}
   if(originalPower!=null){restored="";compose.runOnUiThread{care.applyClimate(originalLeft,power=originalPower){s,_->restored=s}};compose.waitUntil(18000){restored.isNotBlank()};if(primaryFailure==null)assertEquals("confirmed",restored)}
   compose.runOnUiThread{care.relay.end()};compose.waitUntil(15000){!care.relay.sharing};File(compose.activity.filesDir,"family18-proof.json").writeText(proof.toString(2))
  }
 }
}

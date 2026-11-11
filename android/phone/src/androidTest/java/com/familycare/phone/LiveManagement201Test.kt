package com.familycare.phone

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Connects to the existing parked emulator; restores every changed vehicle value. */
class LiveManagement201Test {
 @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
 @Test fun shortcutsAndPhoneActionsChangeRealPleosState(){
  check(BuildConfig.PRODUCT=="navigation")
  val m=compose.activity.model;val f=File(compose.activity.filesDir,"control-test-join.txt");val link=f.readText().trim();f.delete()
  compose.runOnUiThread{m.joinText=link;m.connect();compose.activity.setContent{Box(Modifier.width(390.dp).height(820.dp).testTag("management-frame")){PhoneScreen(m){_,_,_->}}}}
  compose.waitUntil(20000){m.isOwner&&!m.stale&&m.state.optBoolean("parked")&&m.climateTarget("ROW_1_RIGHT")!=null&&m.state.optJSONArray("controls")?.length()?.let{it>10}==true}
  fun control(prefix:String)=com.familycare.ui.SeatAccess.objects(m.state.optJSONArray("controls")).first{it.optString("id").startsWith(prefix)&&it.optBoolean("available")}
  fun waitValue(id:String,value:Double){compose.waitUntil(25000){control(id).optDouble("value")==value}}
  fun save(name:String){val b=compose.onNodeWithTag("management-frame").captureToImage().asAndroidBitmap();File(compose.activity.filesDir,name+".png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
  val driver=m.climateTarget("ROW_1_LEFT")!!;val passenger=m.climateTarget("ROW_1_RIGHT")!!;val power=m.state.getBoolean("climatePower")
  val door=control("door.lock:ROW_");val window=control("window.position:ROW_1_RIGHT");val ac=control("hvac.ac:")
  val originals=listOf(door,window,ac).associate{it.getString("id") to it.getDouble("value")}
  try{
   compose.onNodeWithTag("phone-tab-내 차").performClick()
   compose.onNodeWithTag("owner-doors").performScrollTo().performClick();compose.waitForIdle()
   compose.onNodeWithTag("control-group-문과 창문").assertIsSelected().assertIsDisplayed()
   compose.onNodeWithTag("control-"+door.getString("id")).assertIsDisplayed().performClick()
   compose.onNodeWithText("실행").performClick();waitValue(door.getString("id"),1.0-door.getDouble("value"))
   save("management201-doors")
   compose.onNodeWithTag("window-절반 열기-"+window.getString("id")).performScrollTo().performClick();compose.onNodeWithText("실행").performClick();waitValue(window.getString("id"),5.0)
   compose.onNodeWithTag("window-닫기-"+window.getString("id")).performScrollTo().performClick();compose.onNodeWithText("실행").performClick();waitValue(window.getString("id"),0.0)
   compose.onNodeWithTag("phone-tab-내 차").performClick();compose.onNodeWithTag("owner-climate").performScrollTo().performClick();compose.waitForIdle()
   compose.onNodeWithTag("climate-actual").assertIsDisplayed()
   compose.onNodeWithText("조수석",useUnmergedTree=true).performClick()
   val next=if(passenger<28&&passenger+.5!=driver)passenger+.5 else passenger-.5
   compose.onNodeWithTag(if(next>passenger)"temperature-up" else "temperature-down").performClick()
   compose.onNodeWithTag("owner-set-climate").performClick()
   compose.waitUntil(25000){m.climateTarget("ROW_1_RIGHT")==next}
   assertEquals(driver,m.climateTarget("ROW_1_LEFT")!!,0.01)
   compose.onNodeWithTag("climate-actual").assertTextEquals("조수석 · 차량 설정 %.1f°C".format(next))
   save("management201-climate")
   // Power round-trip must preserve the selected passenger setting, not copy the driver's.
   compose.onNodeWithTag("climate-power-toggle").performScrollTo().performClick()
   compose.waitUntil(25000){m.state.opt("climatePower")==false}
   compose.onNodeWithTag("climate-power-toggle").performScrollTo().performClick()
   compose.waitUntil(25000){m.state.opt("climatePower")==true&&m.climateTarget("ROW_1_RIGHT")==next}
   compose.onNodeWithTag("climate-ac-toggle").performScrollTo().performClick()
   waitValue(ac.getString("id"),1.0-ac.getDouble("value"))
   File(compose.activity.filesDir,"management201-proof.json").writeText(JSONObject().put("passengerBefore",passenger).put("passengerAfter",next).put("driverUnchanged",driver).put("doorToggled",true).put("windowHalfThenClosed",true).put("powerPreservedPassenger",true).put("acToggled",true).toString())
  }finally{
   originals.forEach{(id,value)->compose.waitUntil(12000){!m.busy};compose.runOnUiThread{m.vehicleControl(control(id),value)};waitValue(id,value)}
   compose.waitUntil(12000){!m.busy};compose.runOnUiThread{m.climate(passenger,"ROW_1_RIGHT",true)};compose.waitUntil(25000){m.climateTarget("ROW_1_RIGHT")==passenger}
   if(!power){compose.waitUntil(12000){!m.busy};compose.runOnUiThread{m.climate(passenger,"ROW_1_RIGHT",false)};compose.waitUntil(25000){m.state.opt("climatePower")==false}}
  }
 }
}

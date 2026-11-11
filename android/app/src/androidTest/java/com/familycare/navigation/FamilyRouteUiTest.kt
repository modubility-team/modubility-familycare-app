package com.familycare.navigation
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
class FamilyRouteUiTest{
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun shot(name:String){val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();checkNotNull(bitmap);File(compose.activity.filesDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
 @Test fun renderedComparisonSelectsRestRouteAndStartsGuidance(){
  val care=CareRuntime.get(compose.activity);val nav=care.navigation
  try{
   compose.waitUntil(20000){care.parked&&nav.locationUsable}
   compose.runOnUiThread{care.share()};compose.waitUntil(20000){care.relay.connected&&care.ignition.on}
   compose.runOnUiThread{nav.compareFamily("양재천 경로 확인",GeoPoint(37.488,127.042),GeoPoint(37.489,127.037),"지도에서 고른 휴식 장소")}
   compose.waitUntil(100000){!nav.loading};assertTrue(nav.comparisonMessage,nav.familyCandidates.any{!it.isNull("via")})
   compose.runOnUiThread{compose.activity.setContent{FamilyRouteDialog(care){}}}
   compose.onNodeWithTag("family-route-content").assertIsDisplayed();compose.onNodeWithText("가족 기준 경로 비교").assertIsDisplayed();SystemClock.sleep(1200);shot("family18-routes.png")
   val via=nav.familyCandidates.indexOfFirst{!it.isNull("via")}
   compose.onAllNodesWithTag("choose-family-route")[via].performScrollTo().performClick()
   assertTrue(nav.hasWaypoint);assertEquals("TMAP",nav.route!!.provider)
   compose.runOnUiThread{compose.activity.setContent{CareScreen(care)};nav.start()};compose.waitUntil(15000){nav.active};SystemClock.sleep(6500);shot("family18-guidance.png")
  }finally{compose.runOnUiThread{nav.cancel();care.relay.end()};compose.waitUntil(15000){!care.relay.sharing}}
 }
}

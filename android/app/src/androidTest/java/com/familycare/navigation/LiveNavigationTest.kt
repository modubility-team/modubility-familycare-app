package com.familycare.navigation

import android.os.Bundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses Android GPS updates injected through the emulator console, actual HTTPS routing and AAOS focus. */
class LiveNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun phase(value:String){InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("nav_phase",value)})}
    private fun routeFile(route:RoadRoute){
        val points=JSONArray();route.points.forEach{points.put(JSONArray().put(it.longitude).put(it.latitude))}
        File(compose.activity.filesDir,"navigation-e2e-route.json").writeText(points.toString())
    }
    private fun capture(name:String){
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun gpsRoutingRerouteFocusPauseAndArrival(){
        val care=CareRuntime.get(compose.activity);val nav=care.navigation
        try{
            compose.runOnUiThread{nav.selectProvider(true);care.share()}
            compose.waitUntil(20000){care.relay.connected}
            phase("gps")
            compose.waitUntil(20000){care.engine.ux.showDetails&&nav.locationUsable}
            assertTrue("Emulator fixes must be labelled as simulated",nav.fix!!.simulated)
            var canceled=0
            compose.runOnUiThread{
                nav.plan("취소 시험",GeoPoint(37.5010,127.0500)){ok,_->if(!ok)canceled++}
                nav.cancel()
            }
            assertEquals("Cancelled requests must finish exactly once",1,canceled)
            android.os.SystemClock.sleep(2200)
            compose.runOnUiThread{nav.plan("서울 시험 목적지",GeoPoint(37.5010,127.0500))}
            compose.waitUntil(40000){!nav.loading}
            assertNotNull(nav.message,nav.route)
            assertTrue(nav.route!!.points.size>5);assertTrue(nav.route!!.turns.size>1)
            assertTrue("TMAP must provide traversed road ranges",nav.route!!.roads.isNotEmpty())
            compose.onNodeWithTag("navigation-menu").performClick()
            compose.onNodeWithTag("navigation-menu-장소").performClick()
            compose.onNodeWithText("최근 목적지",useUnmergedTree=true).assertIsDisplayed()
            compose.onNode(hasTestTag("navigation-recent") and hasText("서울 시험 목적지")).assertIsDisplayed()
            compose.onNodeWithText("닫기").performClick()
            android.os.SystemClock.sleep(2200)
            compose.runOnUiThread{nav.recalculate(RouteOption.FREE)}
            compose.waitUntil(40000){!nav.loading}
            assertEquals(RouteOption.FREE,nav.route!!.option)
            android.os.SystemClock.sleep(2200)
            var viaResult:Boolean?=null
            val via=GeoPoint(37.4900,127.0420)
            compose.runOnUiThread{nav.planWaypoint(via){ok,_->viaResult=ok}}
            compose.waitUntil(40000){viaResult!=null}
            assertTrue("Waypoint route should be found: ${nav.message}",viaResult==true)
            assertTrue(nav.route!!.project(via).offRoute<200)
            assertTrue(nav.route!!.points.last().distance(GeoPoint(37.5010,127.0500))<200)
            val original=nav.route!!
            compose.runOnUiThread{nav.start()}
            compose.waitUntil(15000){nav.active}
            compose.waitUntil(45000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
            android.os.SystemClock.sleep(1000);capture("navigation-guidance-010.png")
            compose.runOnUiThread{care.engine.ux=UxPolicy(true,true,80);care.publish()}
            compose.onNodeWithTag("guidance-panel").assertIsDisplayed()
            compose.onNodeWithTag("turn-distance").assertIsDisplayed()
            compose.onNodeWithTag("navigation-arrival").assertIsDisplayed()
            compose.onNodeWithTag("navigation-speed").assertIsDisplayed()
            val frame=compose.onNodeWithTag("vehicle-navigation").fetchSemanticsNode().boundsInRoot
            val header=compose.onNodeWithTag("guidance-panel").fetchSemanticsNode().boundsInRoot
            val map=compose.onNodeWithTag("live-map").fetchSemanticsNode().boundsInRoot
            val footer=compose.onNodeWithTag("navigation-arrival").fetchSemanticsNode().boundsInRoot
            assertTrue("Wide guidance must stay beside the map, compact guidance above it",map.right<=header.left+2 || map.top>=header.bottom-2)
            assertTrue("Map must occupy at least half of the screen width",map.width>=frame.width*.5f)
            assertTrue("Arrival information must not cover the native map",map.bottom<=footer.top+2)
            compose.onNodeWithTag("navigation-search").assertDoesNotExist()
            compose.onNodeWithTag("home-actions").assertDoesNotExist()
            compose.runOnUiThread{care.engine.ux=UxPolicy(true,false);care.publish()}
            phase("stale")
            // The emulator repeats its last fix. Stop the receiver to exercise a real stale timestamp.
            compose.runOnUiThread{nav.gps.stop()}
            compose.waitUntil(18000){!nav.locationUsable}
            assertFalse("Stale GPS must suspend guidance",nav.guiding)
            compose.waitUntil(5000){compose.onAllNodesWithText("GPS 신호 끊김",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("gps-status").assertTextContains("GPS 신호 끊김",substring=true)
            phase("gps")
            compose.runOnUiThread{nav.gps.start()}
            try{compose.waitUntil(15000){nav.guiding}}
            catch(e:Throwable){throw AssertionError("GPS recovery active=${nav.active}, arrived=${nav.arrived}, usable=${nav.locationUsable}, gps=${nav.locationStatus}, message=${nav.message}, fix=${nav.fix}, elapsed=${android.os.SystemClock.elapsedRealtime()}",e)}
            phase("offroute")
            compose.waitUntil(60000){nav.route!==original&&!nav.loading}
            assertTrue(nav.message,nav.active);assertTrue(nav.route!!.points.first().distance(GeoPoint(37.4815,127.0300))<200)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(10000){!nav.active}
            assertFalse(nav.guiding)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(15000){nav.locationUsable}
            assertFalse("Foreground return must not steal route ownership",nav.active)
            compose.runOnUiThread{nav.start()};compose.waitUntil(15000){nav.active}
            routeFile(nav.route!!);phase("drive")
            val driveTimeout=(nav.route!!.points.size*1200L+15000).coerceIn(90000,240000)
            try{compose.waitUntil(driveTimeout){nav.arrived}}
            catch(error:AssertionError){
                File(compose.activity.filesDir,"navigation-failure.txt").writeText("${nav.message}\nactive=${nav.active}, guiding=${nav.guiding}, outside=${nav.outside}, progress=${nav.progress}, remaining=${nav.remainingMeters}, fix=${nav.fix}, end=${nav.route?.points?.last()}")
                capture("navigation-failure.png");throw error
            }
            assertFalse(nav.active);assertTrue(nav.remainingMeters!!<35)
            capture("navigation-arrived-010.png")
            phase("complete")
            assertEquals("Late network callbacks must not repeat cancellation",1,canceled)
        }finally{
            compose.runOnUiThread{nav.cancel();nav.setOption(RouteOption.RECOMMENDED);care.stopSharing()};phase("done")
        }
    }
}

package com.familycare.navigation

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.NaviMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Changes the emulator route to the two public SDK sample locations, then cancels it. */
class NavigationIntegrationTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun destinationAndWaypointAreNeverConfirmedFromMethodReturnAlone(){
        val runtime=CareRuntime.get(compose.activity)
        compose.waitUntil(15000){runtime.engine.ux.showDetails}
        compose.runOnUiThread{runtime.startTrip()}
        assertTrue("Trip service must be active",runtime.engine.running)
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val evidence=JSONObject()
        var bridge:NaviBridge?=null
        val helper=NaviHelper(ctx)
        val got=CountDownLatch(1)
        val cancelled=CountDownLatch(1)
        val cleanState=CountDownLatch(1)
        val cleaning=AtomicBoolean(false)
        val listener=object:NaviHelperEventListener{
            override fun onRouteCancelled(){if(cleaning.get())cancelled.countDown()}
            override fun onRouteStateInfo(routeStateInfo:RouteStateInfo){
                if(cleaning.get()){
                    synchronized(evidence){evidence.put("cleanupRouteMode",routeStateInfo.naviMode.name)}
                    if(routeStateInfo.naviMode !in listOf(NaviMode.ROUTE_GUIDING,NaviMode.SIMULATION_GUIDING))cleanState.countDown()
                }else synchronized(evidence){evidence.put("routeMode",routeStateInfo.naviMode.name).put("routeSearchInProgress",routeStateInfo.isRouting)}
            }
            override fun onDestinationInfo(destinationInfo:DestinationInfo){if(!cleaning.get()){synchronized(evidence){evidence.put("destinationName",destinationInfo.name).put("returnedLatitude",destinationInfo.latitude).put("returnedLongitude",destinationInfo.longitude)};got.countDown()}}
            override fun onWayPointInfo(waypointInfo:List<WaypointInfo>){if(!cleaning.get())synchronized(evidence){evidence.put("waypoints",waypointInfo.joinToString { "${it.name} (${it.latitude},${it.longitude})" })}}
        }
        try{
            helper.initialize();helper.addListener(listener)
            compose.runOnUiThread{bridge=NaviBridge(ctx,{runtime.engine.ux.showDetails}){_,_,_->}.also{it.start()}}
            for(r in listOf(PassengerRequest("n1","destination","42dot",37.48544722,127.03636666),
                PassengerRequest("n2","waypoint","소프트웨어드림센터",37.41275833,127.09481944))){
                val done=CountDownLatch(1);var status="";var message=""
                compose.runOnUiThread{bridge!!.submit(r){s,m->status=s;message=m;done.countDown()}}
                assertTrue("Navi completion deadline",done.await(32,TimeUnit.SECONDS))
                synchronized(evidence){evidence.put(r.kind,JSONObject().put("status",status).put("message",message))}
                if(r.kind=="destination")assertEquals("${r.kind}: $message","confirmed",status)
                else {
                    // Pleos 2.1 returns the waypoint name but zero coordinates.
                    // The regression guarantee is that this cannot appear as confirmed.
                    assertEquals("Unverified waypoint must not be confirmed","needs_review",status)
                    synchronized(evidence){assertTrue(evidence.optString("waypoints").contains("소프트웨어드림센터 (0.0,0.0)"))}
                }
            }
            helper.getDestinationInfo();helper.getWaypointInfo();got.await(5,TimeUnit.SECONDS)
            Thread.sleep(1500)
            synchronized(evidence){
                assertEquals("42dot",evidence.optString("destinationName"))
                assertEquals(37.48544722,evidence.getDouble("returnedLatitude"),0.0001)
                assertEquals(127.03636666,evidence.getDouble("returnedLongitude"),0.0001)
                assertEquals("ROUTE_GUIDING",evidence.optString("routeMode"))
                File(ctx.filesDir,"navigation-evidence.json").writeText(evidence.toString(2))
            }
        }finally{
            synchronized(evidence){File(ctx.filesDir,"navigation-evidence.json").writeText(evidence.toString(2))}
            cleaning.set(true);helper.cancelRoute();cancelled.await(5,TimeUnit.SECONDS)
            helper.getRouteStateInfo();val cleared=cleanState.await(5,TimeUnit.SECONDS)
            synchronized(evidence){evidence.put("cleanupVerified",cleared);File(ctx.filesDir,"navigation-evidence.json").writeText(evidence.toString(2))}
            // Helper instances share the SDK service; release only after cleanup callbacks.
            compose.runOnUiThread{bridge?.close()}
            helper.removeListener(listener);helper.release()
            compose.runOnUiThread{if(runtime.engine.ux.showDetails){runtime.endTrip();runtime.confirmRear()}}
            assertTrue("Test route must be cancelled before releasing the SDK",cleared)
        }
    }
}

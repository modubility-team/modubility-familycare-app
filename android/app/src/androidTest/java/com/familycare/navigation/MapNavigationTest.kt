package com.familycare.navigation

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.NaviMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public 42dot destination on the emulator only. Cancels its test route afterwards. */
class MapNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun mapDestinationIsVerifiedThroughPleos(){
        val care=CareRuntime.get(compose.activity)
        val cleanup=NaviHelper(compose.activity)
        val cancelled=CountDownLatch(1)
        val cleared=CountDownLatch(1)
        val listener=object:NaviHelperEventListener{
            override fun onRouteCancelled(){cancelled.countDown()}
            override fun onRouteStateInfo(routeStateInfo:RouteStateInfo){
                if(routeStateInfo.naviMode !in listOf(NaviMode.ROUTE_GUIDING,NaviMode.SIMULATION_GUIDING))cleared.countDown()
            }
        }
        cleanup.initialize();cleanup.addListener(listener)
        var requested=false
        try{
            compose.waitUntil(15000){care.engine.ux.showDetails}
            compose.waitUntil(45000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("navigation-search").performClick()
            compose.onNodeWithTag("map-sample").performScrollTo().performClick()
            compose.onNodeWithTag("map-place-name").assertTextContains("42dot")
            compose.onNodeWithTag("map-send-route").performScrollTo().performClick()
            requested=true
            compose.waitUntil(32000){!care.mapRequestPending}
            assertTrue(care.notice,care.notice.contains("내비의 활성 경로"))
        }finally{
            var verified=true
            if(requested){
                cleanup.cancelRoute()
                cancelled.await(5,TimeUnit.SECONDS)
                cleanup.getRouteStateInfo()
                verified=cleared.await(5,TimeUnit.SECONDS)
            }
            cleanup.removeListener(listener);cleanup.release()
            assertTrue("Test route must be cancelled",verified)
        }
    }
}

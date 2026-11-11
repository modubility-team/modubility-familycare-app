package com.familycare.navigation

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/** One request in flight. Never equate an SDK method return with successful routing. */
class NaviBridge(context: Context, private val allowed: () -> Boolean,
    private val progress: (Int?, Int?, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val helper = NaviHelper(context.applicationContext)
    private var active = true
    private var request: PassengerRequest? = null
    private var completion: ((String, String) -> Unit)? = null
    private var verifiedDestination: PassengerRequest? = null
    private var routeActive = false
    private var matched = false
    private fun dispatch(block: () -> Unit) { main.post { if(active) block() } }
    private fun matches(r: PassengerRequest, lat: Double, lon: Double, name: String?) =
        r.latitude != null && r.longitude != null && abs(lat - r.latitude) < .00001 &&
            abs(lon - r.longitude) < .00001 && name == r.title
    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() { dispatch { inspect() } }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { dispatch { inspect() } }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { dispatch { inspect() } }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { dispatch {
            val r = request
            if(r?.kind == "destination" && matches(r, destinationInfo.latitude, destinationInfo.longitude, destinationInfo.name)) {
                matched = true; confirmIfReady()
            }
            val verified = verifiedDestination
            if(verified != null && matches(verified, destinationInfo.latitude, destinationInfo.longitude, destinationInfo.name))
                progress(destinationInfo.remainDistance.takeIf { it >= 0 }, destinationInfo.remainTime.takeIf { it >= 0 }, false)
            else progress(null, null, false)
        } }
        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) { dispatch {
            val r = request
            if(r?.kind == "waypoint" && waypointInfo.any { matches(r, it.latitude, it.longitude, it.name) }) {
                matched = true; confirmIfReady()
            }
        } }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { dispatch {
            routeActive = routeStateInfo.naviMode in listOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
            confirmIfReady()
        } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { dispatch {
            if(verifiedDestination != null) progress(0, 0, true)
        } }
        override fun onDestinationChanged() { dispatch { verifiedDestination = null; progress(null, null, false); inspect() } }
        // Replacing a destination also emits cancellation for the previous route.
        // Wait for current route state and coordinates instead of treating that as failure.
        override fun onRouteCancelled() { dispatch { verifiedDestination = null; routeActive = false; matched = false; progress(null, null, false) } }
        override fun onError(error: NaviErrorCode) { dispatch { finish("needs_review", "내비 응답을 확인하지 못했어요. 내비 화면에서 확인하세요") } }
    }
    fun start() { helper.initialize(); helper.addListener(listener) }
    fun submit(r: PassengerRequest, done: (String, String) -> Unit) {
        if(!active || !allowed() || !r.valid() || request != null) { done("needs_review", "정차 상태와 내비 연결을 확인하세요"); return }
        request = r; completion = done; routeActive = false; matched = false
        try {
            if(r.kind == "destination") {
                verifiedDestination = null; progress(null, null, false)
                helper.requestRoute(RouteInfo(latitude=r.latitude!!, longitude=r.longitude!!, poiName=r.title,
                    poiId="", address="", poiSubId=""))
            } else helper.addWaypoint(RequestWaypointInfo(latitude=r.latitude!!, longitude=r.longitude!!,
                poiName=r.title, waypointIndex=WaypointIndex.FIRST, poiId="", address="", poiSubId=""))
            main.postDelayed(timeout, 25_000)
            main.postDelayed(inspector, 3000)
        } catch (_: Exception) { finish("needs_review", "내비 전달 결과 미확인 · 내비 화면에서 확인하세요") }
    }
    private val timeout = Runnable { finish("needs_review", "전달했지만 이름·좌표가 일치하는 결과를 받지 못했어요. 내비에서 확인하세요") }
    private val inspector = object : Runnable {
        override fun run() { if(active && request != null) { inspect(); main.postDelayed(this, 4000) } }
    }
    fun refresh() { if(active && verifiedDestination != null) runCatching { helper.getDestinationInfo() } }
    private fun inspect() { if(request != null) runCatching { helper.getRouteStateInfo(); helper.getDestinationInfo(); helper.getWaypointInfo() } }
    private fun confirmIfReady() {
        val r = request ?: return
        if(!routeActive || !matched) return
        if(r.kind == "destination") verifiedDestination = r
        finish("confirmed", "내비의 활성 경로와 ${if(r.kind == "destination") "목적지" else "경유지"} 이름·좌표를 확인했어요")
    }
    private fun finish(status: String, message: String) {
        main.removeCallbacks(timeout); main.removeCallbacks(inspector)
        val cb = completion; request = null; completion = null; cb?.invoke(status, message)
    }
    fun close() {
        if(!active) return
        active = false; finish("needs_review", "연결이 종료되었어요. 내비 화면에서 확인하세요")
        runCatching { helper.removeListener(listener) }; runCatching { helper.release() }
    }
}

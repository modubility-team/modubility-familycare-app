package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteTimingTest {
    @Test fun slowFirstRoadDoesNotUseSimpleDistanceRatio(){
        val route=RoadRoute(listOf(GeoPoint(37.0,127.0),GeoPoint(37.0,127.01),GeoPoint(37.0,127.02)),emptyList(),120.0,"synthetic",
            timings=listOf(RouteTime(1,90.0),RouteTime(2,30.0)))
        assertEquals(120.0,route.remainingSeconds(0.0),.01)
        assertEquals(30.0,route.remainingSeconds(route.cumulative[1]),.01)
        assertEquals(15.0,route.remainingSeconds((route.cumulative[1]+route.meters)/2),.01)
        assertEquals(0.0,route.remainingSeconds(route.meters+100),.01)
    }
    @Test fun untimedProviderRetainsEstimatedDistanceFallback(){
        val route=RoadRoute(listOf(GeoPoint(37.0,127.0),GeoPoint(37.0,127.01)),emptyList(),120.0,"synthetic")
        assertEquals(60.0,route.remainingSeconds(route.meters/2),.01)
    }
}

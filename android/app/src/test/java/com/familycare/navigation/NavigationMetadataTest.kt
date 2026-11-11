package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationMetadataTest{
    @Test fun currentRoadChangesOnlyWhenEnteringItsRange(){
        val route=RoadRoute(listOf(GeoPoint(37.0,127.0),GeoPoint(37.0,127.01),GeoPoint(37.0,127.02)),emptyList(),120.0,"도착",
            roads=listOf(RouteRoad(0,1,"첫 도로"),RouteRoad(1,2,"다음 도로")),traffic=listOf(RouteTraffic(0,1,1)))
        assertEquals("첫 도로",route.roadAt(route.cumulative[1]-1))
        assertEquals("다음 도로",route.roadAt(route.cumulative[1]))
        assertEquals(1,route.trafficAt(0));assertNull(route.trafficAt(1))
        assertEquals("",route.roadAt(route.meters+1))
        assertNull(route.tollFare)
    }
    @Test fun unsupportedPreferenceFallsBackToRecommended(){
        assertEquals(RouteOption.RECOMMENDED,RouteOption.from(-1))
        assertEquals(RouteOption.FREE,RouteOption.from(1))
        assertEquals(RouteOption.SHORT,RouteOption.from(10))
    }
}

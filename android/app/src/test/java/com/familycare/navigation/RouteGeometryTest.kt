package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteGeometryTest{
    private val a=GeoPoint(37.48,127.03)
    private val b=GeoPoint(37.49,127.03)
    private val c=GeoPoint(37.49,127.04)
    private val route=RoadRoute(listOf(a,b,c),listOf(RouteTurn(a.distance(b),"우회전하세요","→","다음 도로")),240.0,"시험 목적지")
    private fun fix(point:GeoPoint,at:Long=1000,accuracy:Float=5f)=PositionFix(point,accuracy,null,null,at,true)
    @Test fun projectionUsesSegmentsAndPreservesRemainingDistance(){
        val p=route.project(GeoPoint(37.485,127.03))
        assertEquals(a.distance(b)/2,p.along,1.0);assertEquals(0.0,p.offRoute,.1)
        assertEquals("우회전하세요",route.instruction(p.along).instruction)
        assertEquals("목적지에 도착합니다",route.instruction(route.meters-5).instruction)
    }
    @Test fun stalePoorAndFutureLocationsDoNotAdvance(){
        val follower=RouteFollower()
        assertFalse(follower.update(route,fix(b,1),12000))
        assertFalse(follower.update(route,fix(b,1000,120f),1000))
        assertFalse(follower.update(route,fix(b,2000),1000))
        assertEquals(0.0,follower.along,0.0)
    }
    @Test fun rerouteNeedsSustainedDeviationAndRecovers(){
        val follower=RouteFollower();val outside=GeoPoint(37.485,127.035)
        follower.update(route,fix(outside,1000),1000);assertFalse(follower.offRoute)
        follower.update(route,fix(outside,4500),4500);assertTrue(follower.offRoute)
        follower.update(route,fix(b,5500),5500);assertFalse(follower.offRoute)
    }
    @Test fun arrivalNeedsEndOfRouteAndConsecutiveFreshFixes(){
        val follower=RouteFollower()
        follower.update(route,fix(c,1000),1000);assertFalse(follower.arrived)
        follower.update(route,fix(c,3200),3200);assertTrue(follower.arrived)
        follower.reset();assertFalse(follower.arrived);assertEquals(0.0,follower.along,0.0)
    }
    @Test fun gpsJitterDoesNotMoveProgressBackwards(){
        val follower=RouteFollower();follower.update(route,fix(b),1000);val progress=follower.along
        follower.update(route,fix(GeoPoint(37.48998,127.03),2000),2000)
        assertEquals(progress,follower.along,.01)
    }
    @Test fun invalidCoordinatesRejected(){
        assertFalse(GeoPoint(Double.NaN,127.0).valid())
        assertFalse(GeoPoint(37.0,181.0).valid())
        assertThrows(IllegalArgumentException::class.java){RoadRoute(listOf(a),emptyList(),3.0,"bad")}
    }
    @Test fun drivingBackwardsOnSameRoadTriggersReroute(){
        val follower=RouteFollower();follower.update(route,fix(b,1000),1000)
        val reversed=GeoPoint(37.488,127.03)
        follower.update(route,fix(reversed,2000),2000);assertFalse(follower.offRoute)
        follower.update(route,fix(reversed,5500),5500);assertTrue(follower.offRoute)
    }
    @Test fun vehicleHeadingFollowsEachRoadRatherThanAssumingNorth(){
        assertEquals(0f,route.headingAt(100.0),.2f)
        assertEquals(90f,route.headingAt(a.distance(b)+100),.2f)
        assertEquals(90f,route.headingAt(route.meters),.2f)
        assertEquals(180f,b.bearingTo(a),.2f)
        assertEquals(270f,c.bearingTo(b),.2f)
    }
    @Test fun interpolationClampsEndsAndHandlesRepeatedRoutePoints(){
        val repeated=RoadRoute(listOf(a,a,b,b,c),emptyList(),240.0,"도착")
        assertEquals(a,repeated.pointAt(-100.0))
        assertEquals(c,repeated.pointAt(repeated.meters+100))
        assertEquals(37.485,repeated.pointAt(a.distance(b)/2).latitude,.000001)
        assertEquals(90f,repeated.headingAt(a.distance(b)+20),.2f)
    }
    @Test fun nextManeuverAdvancesAcrossAnIntersection(){
        val turns=listOf(RouteTurn(100.0,"우회전","→","첫 도로"),RouteTurn(250.0,"좌회전","←","둘째 도로"),RouteTurn(500.0,"도착","●","목적지"))
        val path=RoadRoute(listOf(a,b,c),turns,240.0,"목적지")
        assertEquals("→",path.instruction(20.0).symbol)
        assertEquals("←",path.followingTurn(20.0)?.symbol)
        assertEquals("←",path.instruction(120.0).symbol)
        assertEquals("●",path.followingTurn(120.0)?.symbol)
        assertNull(path.followingTurn(260.0))
    }
}

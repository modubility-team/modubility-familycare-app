package com.familycare.navigation
import com.familycare.ui.*
import org.junit.Assert.*
import org.junit.Test
class MapMotionTest{
 private fun p(lat:Double=37.0,lon:Double=127.0,heading:Float=0f,t:Long=0)=MapPosition(lat,lon,heading,fixElapsedMillis=t)
 @Test fun interpolatesIntermediateFramesWithoutOvershooting(){
  val m=MapMotion();m.offer(p(),emptyList(),0);m.offer(p(lat=37.0001,t=1000),emptyList(),1000)
  val samples=(0..30).map{m.sample(1000L+it*33)!!.latitude};assertEquals(31,samples.distinct().size);assertTrue(samples.zipWithNext().all{it.second>it.first});assertEquals(37.0001,m.sample(5000)!!.latitude,1e-10)
 }
 @Test fun headingCrossesNorthByShortestArc(){val m=MapMotion();m.offer(p(heading=350f),emptyList(),0);m.offer(p(heading=10f,t=1000),emptyList(),1000);assertEquals(0f,m.sample(1500)!!.heading!!,.01f)}
 @Test fun newFixStartsAtCurrentlyRenderedPointAndSameFixDoesNotRestart(){val m=MapMotion();m.offer(p(),emptyList(),0);val next=p(lat=37.0001,t=1000);m.offer(next,emptyList(),1000);val mid=m.sample(1500)!!;m.offer(next,emptyList(),1500);assertEquals(mid.latitude,m.sample(1500)!!.latitude,1e-10);m.offer(p(lat=37.0002,t=1500),emptyList(),1500);assertEquals(mid.latitude,m.sample(1500)!!.latitude,1e-10);assertEquals(37.0002,m.sample(2000)!!.latitude,1e-10)}
 @Test fun staleSignalTeleportAndDisappearDoNotInventTravel(){val m=MapMotion();m.offer(p(),emptyList(),0);m.offer(p(lat=38.0,t=1000),emptyList(),1000);assertEquals(38.0,m.sample(1000)!!.latitude,.0);val stale=p(lat=38.0001,t=2000).copy(fresh=false);m.offer(stale,emptyList(),2000);assertEquals(stale,m.sample(2050));m.offer(null,emptyList(),3000);assertNull(m.sample(4000))}
 @Test fun pathInterpolationFollowsCornerNotDiagonal(){
  val route=listOf(MapRoutePoint(37.0,127.0),MapRoutePoint(37.001,127.0),MapRoutePoint(37.001,127.001));val m=MapMotion()
  m.offer(p().copy(navigation=true,routeProgressMeters=0.0),route,0)
  val total=111.195+111.195*kotlin.math.cos(Math.toRadians(37.001))
  m.offer(p(37.001,127.001,t=1000).copy(navigation=true,routeProgressMeters=total),route,1000)
  val mid=m.sample(1500)!!;assertEquals(127.0,mid.longitude,1e-9);assertTrue(mid.latitude>37.0008);assertTrue(mid.latitude<37.001)
 }
 @Test fun realPauseKeepsLastReceivedPoint(){val m=MapMotion();m.offer(p(),emptyList(),0);m.offer(p(lat=37.0001,t=200),emptyList(),200);m.offer(p(lat=37.0001,t=400).copy(speedMetersPerSecond=0f),emptyList(),400);assertEquals(37.0001,m.sample(1500)!!.latitude,1e-10)}
}

package com.familycare.navigation
import org.junit.Test
import org.junit.Assert.*
class FamilyRerouteTest {
 private fun o(id:String,turns:Int,stop:Boolean=false,extra:Double=0.0)=FamilyReroute.Option(id,1000.0+extra,turns,stop,extra)
 private fun road(vararg p:Pair<Double,Double>)=RoadRoute(p.map{GeoPoint(it.first,it.second)},emptyList(),300.0,"목적지")
 @Test fun stopNeedsMapToFacilities(){
  assertEquals("반려견 공원",FamilyReroute.stopFacility(listOf("방향 전환 적게","화장실","반려견 산책")))
  assertEquals("화장실",FamilyReroute.stopFacility(listOf("화장실")))
  assertEquals("휴게소",FamilyReroute.stopFacility(listOf("쉬어 갈 장소")))
  assertNull(FamilyReroute.stopFacility(listOf("빠른 도착","방향 전환 적게")))
  assertTrue(FamilyReroute.shapesRoute(listOf("화장실","빠른 도착")));assertFalse(FamilyReroute.shapesRoute(listOf("화장실","쉬어 갈 장소")))
 }
 @Test fun eventsAreHandledOnceWhileFresh(){
  assertTrue(FamilyReroute.fresh("a","",2.0));assertFalse(FamilyReroute.fresh("a","a",2.0))
  assertFalse(FamilyReroute.fresh("b","a",45.0));assertFalse(FamilyReroute.fresh("","a",0.0))
 }
 @Test fun serverOrderWinsAndStopsAvoidLongDetours(){
  val options=listOf(o("few",11),o("stop",13,true,600.0),o("fast",15))
  assertEquals("stop",FamilyReroute.pick(options,true)!!.id)
  assertEquals("few",FamilyReroute.pick(options,false)!!.id)
  assertEquals("few",FamilyReroute.pick(listOf(o("few",11),o("stop",13,true,1500.0)),true)!!.id)
  assertNull(FamilyReroute.pick(emptyList(),true))
 }
 @Test fun announcementsSayWhatChanged(){
  assertEquals("가족 기준에 맞춰 덕평자연휴게소 경유로 경로를 바꿨어요.",FamilyReroute.announcement(listOf("반려견 산책"),"덕평자연휴게소"))
  assertEquals("가족 기준에 맞춰 방향 전환이 적은 길로 바꿨어요.",FamilyReroute.announcement(listOf("빠른 도착","방향 전환 적게"),null))
  assertEquals("가족 기준에 맞춰 가장 빠른 길로 바꿨어요.",FamilyReroute.announcement(listOf("빠른 도착"),null))
  assertEquals("가족이 원하는 화장실 경유는 음성 명령으로 추가할 수 있어요.",FamilyReroute.suggestion("화장실"))
  assertEquals("방향 전환 적게·화장실",FamilyReroute.label(listOf("방향 전환 적게","화장실")))
 }
 @Test fun sameRoadMeansNothingToSwitch(){
  val current=road(37.50 to 127.00,37.50 to 127.02,37.52 to 127.02)
  val from=current.project(GeoPoint(37.50,127.005)).along
  assertTrue(FamilyReroute.sameRoad(road(37.50 to 127.005,37.50 to 127.02,37.52 to 127.02),current,from))
  assertFalse(FamilyReroute.sameRoad(road(37.50 to 127.005,37.51 to 127.005,37.52 to 127.02),current,from))
  // The rest of the current route has a loop the candidate skips: that is a change.
  val loop=road(37.50 to 127.00,37.50 to 127.01,37.51 to 127.01,37.50 to 127.0102,37.50 to 127.02)
  assertFalse(FamilyReroute.sameRoad(road(37.50 to 127.005,37.50 to 127.02),loop,loop.project(GeoPoint(37.50,127.005)).along))
 }
}

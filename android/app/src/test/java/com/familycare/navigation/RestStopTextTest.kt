package com.familycare.navigation
import com.familycare.ui.RestStopText
import org.junit.Test
import org.junit.Assert.*
/** Plain-value tests (no org.json: Android's JSON classes are not available in local unit tests). */
class RestStopTextTest {
 private fun care(stop:Boolean=true)=RestStopText.Status(stopName=if(stop)"덕평자연휴게소" else "")
 @Test fun waitsUntilTheStopIsOnTheRoute(){
  val c=care().copy(onRoute=false,etaSeconds=null,restDue=false)
  assertEquals("지정 휴게소 · 경로 반영 대기",RestStopText.car(c))
  assertTrue(RestStopText.phone(c).startsWith("지정 휴게소(덕평자연휴게소) · 차량 경로 반영 대기"))
 }
 @Test fun showsTheNavigationEstimateToTheStop(){
  val c=care().copy(onRoute=true,etaSeconds=1795,restDue=false)
  assertEquals("지정 휴게소 도착까지 30분",RestStopText.car(c))
  assertEquals("지정 휴게소(덕평자연휴게소) 도착까지 약 30분",RestStopText.phone(c))
  assertEquals("지정 휴게소 경유 안내 중",RestStopText.car(care().copy(onRoute=true,etaSeconds=null)))
  // An estimate without the stop on the route is not shown.
  assertEquals("지정 휴게소 · 경로 반영 대기",RestStopText.car(care().copy(onRoute=false,etaSeconds=600)))
 }
 @Test fun arrivalAndAfterRest(){
  assertEquals("지정 휴게소 도착 · 쉬어 가세요",RestStopText.car(care().copy(onRoute=false,restDue=true)))
  val rested=care(false).copy(restedAt="덕평자연휴게소",remainingSeconds=null)
  assertEquals("지정 휴게소 휴식 완료",RestStopText.car(rested))
  assertEquals("지정 휴게소(덕평자연휴게소) 휴식 완료 · 다음 휴게소를 지정할 수 있어요",RestStopText.phone(rested))
  assertEquals("지정 휴게소 없음",RestStopText.car(care(false).copy(remainingSeconds=null)))
 }
 @Test fun olderTimedPlansKeepTheirWording(){
  assertEquals("휴식 계획까지 15분",RestStopText.car(care(false).copy(remainingSeconds=841)))
  assertEquals("계획한 휴식 시간이에요",RestStopText.car(care(false).copy(remainingSeconds=0,restDue=true)))
 }
 @Test fun arrivalKeyChangesWithTheStopOrTheTrip(){
  assertEquals("덕평자연휴게소|t1",RestStopText.arrivalKey(care().copy(startedAt="t1")))
  assertEquals("123.0|t1",RestStopText.arrivalKey(care(false).copy(due="123.0",startedAt="t1")))
 }
}

package com.familycare.phone
import org.junit.Test
import org.junit.Assert.*
class MemorySyncPlanTest {
 private fun local(vararg ids:String)=ids.mapIndexed{i,id->MemorySyncPlan.Local(id,"2026-11-09 1$i:00")}
 @Test fun aPhotoFromTheOtherAppIsKeptOnThisPhone(){
  // Family Care shared "a"; the 내 차 app has no copy yet, so it keeps one.
  val step=MemorySyncPlan.next(listOf("a"),emptyList(),emptySet())
  assertEquals("a",step.fetch);assertNull(step.upload);assertTrue(step.forget.isEmpty())
 }
 @Test fun afterTheServerRestartsThePhotosAreSharedAgainNewestFirst(){
  // New session: nothing on the server, nothing seen yet.
  val step=MemorySyncPlan.next(emptyList(),local("a","b","c"),emptySet())
  assertNull(step.fetch);assertEquals("c",step.upload)
  // Once "c" is back, the next newest follows; one already back is not sent twice.
  assertEquals("b",MemorySyncPlan.next(listOf("c"),local("a","b","c"),setOf("c")).upload)
  assertNull(MemorySyncPlan.next(listOf("a","b","c"),local("a","b","c"),setOf("a","b","c")).upload)
 }
 @Test fun aPhotoDeletedInThisSessionIsForgottenNotSharedAgain(){
  val step=MemorySyncPlan.next(listOf("b"),local("a","b"),setOf("a","b"))
  assertEquals(listOf("a"),step.forget);assertNull(step.upload);assertNull(step.fetch)
 }
 @Test fun respectsTheServerLimitAndRefusedUploads(){
  val full=(1..12).map{"s$it"}
  assertNull(MemorySyncPlan.next(full,local(*full.toTypedArray())+local("x"),full.toSet()).upload)
  assertEquals("a",MemorySyncPlan.next(emptyList(),local("a","b"),emptySet(),failed=setOf("b")).upload)
 }
 @Test fun seenResetsWithANewSessionAndLocalCopiesAreCapped(){
  assertEquals(emptySet<String>() to setOf("x"),MemorySyncPlan.seen("old",setOf("a"),"new",listOf("x")))
  assertEquals(setOf("a") to setOf("a","x"),MemorySyncPlan.seen("s",setOf("a"),"s",listOf("x")))
  val many=(10..45).map{MemorySyncPlan.Local("p$it","2026-11-09 $it:00")}
  val drop=MemorySyncPlan.overflow(many)
  assertEquals(6,drop.size);assertTrue("p10" in drop&&"p45" !in drop)
 }
}

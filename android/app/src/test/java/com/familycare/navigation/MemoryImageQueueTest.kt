package com.familycare.navigation
import org.junit.Test
import org.junit.Assert.*
class MemoryImageQueueTest {
 private val ids=listOf("a","b","c")
 @Test fun fetchesInSharedOrderAndSkipsWhatIsCached(){
  assertEquals("a",MemoryImageQueue.next(ids,emptySet(),emptyMap()))
  assertEquals("c",MemoryImageQueue.next(ids,setOf("a","b"),emptyMap()))
  assertNull(MemoryImageQueue.next(ids,setOf("a","b","c"),emptyMap()))
 }
 @Test fun thePhotoOnScreenGoesFirst(){
  assertEquals("c",MemoryImageQueue.next(ids,emptySet(),emptyMap(),preferred="c"))
  assertEquals("a",MemoryImageQueue.next(ids,setOf("c"),emptyMap(),preferred="c"))
  assertEquals("a",MemoryImageQueue.next(ids,emptySet(),emptyMap(),preferred="deleted"))
 }
 @Test fun givesUpOnAPhotoAfterThreeFailures(){
  assertEquals("b",MemoryImageQueue.next(ids,emptySet(),mapOf("a" to 3)))
  assertEquals("a",MemoryImageQueue.next(ids,emptySet(),mapOf("a" to 2)))
  assertNull(MemoryImageQueue.next(listOf("","a"),emptySet(),mapOf("a" to 3)))
 }
}

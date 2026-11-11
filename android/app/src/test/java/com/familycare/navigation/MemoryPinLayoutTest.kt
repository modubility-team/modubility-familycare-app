package com.familycare.navigation
import com.familycare.ui.MemoryPin
import com.familycare.ui.MemoryPinLayout
import com.familycare.ui.MemoryPinStyle
import org.junit.Test
import org.junit.Assert.*
class MemoryPinLayoutTest {
 @Test fun bigCardsOnlyWhileParkedInP(){
  // Parked in P: big cards, also with guidance on screen (the caller no longer passes guidance).
  assertEquals(MemoryPinStyle.Card,MemoryPinStyle.forCar(readOnly=false,parked=true,selecting=false))
  // Driving restrictions, not in P (moving, or stopped in D at a light) and picking a place: small round photos.
  assertEquals(MemoryPinStyle.Compact,MemoryPinStyle.forCar(readOnly=true,parked=true,selecting=false))
  assertEquals(MemoryPinStyle.Compact,MemoryPinStyle.forCar(readOnly=false,parked=false,selecting=false))
  assertEquals(MemoryPinStyle.Compact,MemoryPinStyle.forCar(readOnly=false,parked=true,selecting=true))
 }
 @Test fun cardPhotoIsMoreThanTwiceTheOldMarkerAndTheDrivingDotIsSmaller(){
  val card=MemoryPinLayout.box(MemoryPinStyle.Card,1024,768)
  val old=MemoryPinLayout.box(MemoryPinStyle.Thumbnail,96,72)
  val dot=MemoryPinLayout.box(MemoryPinStyle.Compact,96,72)
  assertEquals(140f,card.photoWidth,0f);assertEquals(105f,card.photoHeight,0f)
  assertEquals(68f,old.width,0f)
  assertTrue(card.photoWidth>=old.photoWidth*2.4f)
  assertTrue(dot.width<old.width)
  // The driving photo is the size of the destination mark (48 dp).
  assertEquals(48f,dot.width,0f)
 }
 @Test fun cardTipSitsOnThePlaceWithTapSpaceBelow(){
  val portrait=MemoryPinLayout.box(MemoryPinStyle.Card,768,1024)
  assertEquals(105f,portrait.photoWidth,0f);assertEquals(140f,portrait.photoHeight,0f)
  val tip=portrait.height*portrait.anchorY
  assertEquals(MemoryPinLayout.SHADOW+portrait.cardHeight+MemoryPinLayout.TAIL,tip,.001f)
  assertEquals(MemoryPinLayout.HIT,portrait.height-tip,.001f)
  assertEquals(.5f,portrait.anchorX,0f)
  assertEquals(124f,MemoryPinLayout.box(MemoryPinStyle.Card,500,480).photoWidth,0f)
 }
 @Test fun cardsFollowTheMapSize(){
  // About 22% of the map's width...
  assertEquals(1.9f,MemoryPinLayout.cardScale(1200f),.0001f)
  assertEquals(.95f,MemoryPinLayout.cardScale(600f),.0001f)
  assertEquals(.85f,MemoryPinLayout.cardScale(500f),.0001f)
  assertEquals(2f,MemoryPinLayout.cardScale(2400f),.0001f)
  assertEquals(1f,MemoryPinLayout.cardScale(0f),0f)
  // ...while a portrait photo's long side stays within 30% of the map's height (the demo car screen: about 1200 x 840 dp).
  assertEquals(1.8f,MemoryPinLayout.cardScale(1200f,840f),.0001f)
  assertEquals(1.8f,MemoryPinLayout.cardScale(2400f,840f),.0001f)
  assertEquals(1.9f,MemoryPinLayout.cardScale(1200f,0f),.0001f)
  val big=MemoryPinLayout.box(MemoryPinStyle.Card,1024,768,1.8f)
  assertEquals(252f,big.photoWidth,.001f);assertEquals(189f,big.photoHeight,.001f)
  assertEquals(264f,big.cardWidth,.001f)
  // The title line grows too, more slowly: 30 dp x 1.4.
  assertEquals(6f+189f+42f,big.cardHeight,.001f)
  val portrait=MemoryPinLayout.box(MemoryPinStyle.Card,768,1024,1.8f)
  assertTrue(portrait.photoHeight<=840f*.3f+.001f)
  // The photo is 3.7 times as wide as the old 68 dp marker, and the tip still sits on the place with tap space below.
  assertTrue(big.cardWidth>=68f*3.5f)
  assertEquals(MemoryPinLayout.HIT,big.height-big.height*big.anchorY,.001f)
  assertEquals(446L,MemoryPinLayout.decodeTarget(MemoryPinStyle.Card,2f,1.8f).toLong())
 }
 @Test fun titlesGrowHalfAsFastAsTheCard(){
  assertEquals(1f,MemoryPinLayout.textScale(1f),.0001f)
  assertEquals(1f,MemoryPinLayout.textScale(.85f),.0001f)
  assertEquals(1.2f,MemoryPinLayout.textScale(1.4f),.0001f)
  assertEquals(1.4f,MemoryPinLayout.textScale(1.8f),.0001f)
  assertEquals(1.4f,MemoryPinLayout.textScale(2f),.0001f)
 }
 @Test fun markersThatWouldCoverEachOtherAtThisZoomShareOne(){
  // Unknown scale or phone thumbnails: only photos taken at one place (50 m) share a marker.
  assertEquals(50.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,0.0),0.0)
  assertEquals(50.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Thumbnail,1f,10.0),0.0)
  assertEquals(50.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,Double.NaN),0.0)
  // A 264 dp card on a street-level map (1 m per dp): about 200 m; the same card on a route overview (10 m per dp): about 2.3 km.
  assertEquals(200.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,1.0),.001)
  assertEquals(50.0*Math.pow(2.0,5.5),MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,10.0),.001)
  // Small zoom changes keep the same distance, so the markers are not redrawn.
  assertEquals(200.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,1.1),.001)
  // Driving photos (48 dp) merge only once they touch.
  assertEquals(50.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Compact,1f,1.0),0.0)
  assertEquals(100.0,MemoryPinLayout.groupMeters(MemoryPinStyle.Compact,1f,2.4),.001)
  val a=MemoryPin("a","휴게소",37.2400,127.3900,captured="2026-11-08 14:20")
  val d=MemoryPin("d","1 km 옆 공원",37.2490,127.3900,captured="2026-11-08 15:00")
  assertEquals(2L,MemoryPinLayout.group(listOf(a,d),MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,1.0)).size.toLong())
  val overview=MemoryPinLayout.group(listOf(d,a),MemoryPinLayout.groupMeters(MemoryPinStyle.Card,1.8f,10.0))
  assertEquals(1L,overview.size.toLong())
  assertEquals("d",overview[0].last().id)
 }
 @Test fun decodesOnlyAsMuchAsTheCardNeeds(){
  // A shared 1024x768 photo on a density-2 screen: the card needs a 248 px short side.
  assertEquals(248L,MemoryPinLayout.decodeTarget(MemoryPinStyle.Card,2f).toLong())
  assertEquals(2L,MemoryPinLayout.sampleSize(1024,768,248).toLong())
  assertEquals(1L,MemoryPinLayout.sampleSize(96,72,248).toLong())
  assertEquals(4L,MemoryPinLayout.sampleSize(1024,768,124).toLong())
 }
 @Test fun cropKeepsTheCentre(){
  assertArrayEquals(intArrayOf(128,0,768,768),MemoryPinLayout.crop(1024,768,1f,1f))
  assertArrayEquals(intArrayOf(0,96,1024,576),MemoryPinLayout.crop(1024,768,16f,9f))
  assertArrayEquals(intArrayOf(0,0,1024,768),MemoryPinLayout.crop(1024,768,140f,105f))
 }
 @Test fun photosTakenAtOnePlaceShareOneMarkerShowingTheNewest(){
  val a=MemoryPin("a","휴게소 1",37.2400,127.3900,captured="2026-11-08 14:20")
  val b=MemoryPin("b","휴게소 2",37.2402,127.3901,captured="2026-11-08 14:25")
  val c=MemoryPin("c","바다",37.3500,126.6000,captured="2026-11-08 16:00")
  val groups=MemoryPinLayout.group(listOf(c,b,a))
  assertEquals(2L,groups.size.toLong())
  assertEquals(listOf("a","b"),groups[0].map{it.id})
  assertEquals("b",groups[0].last().id)
  assertEquals(listOf("c"),groups[1].map{it.id})
  assertTrue(MemoryPinLayout.distance(37.2400,127.3900,37.2402,127.3901) in 20.0..30.0)
 }
}

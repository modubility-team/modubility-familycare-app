package com.familycare.navigation
import com.familycare.ui.FoundPlace
import com.familycare.ui.MemoryPlaceFill
import com.familycare.ui.MemoryPlaceGuess
import org.junit.Test
import org.junit.Assert.*
/** Filling in a memory photo's place from its own GPS location (the server's TMAP lookup is tested in companion/test_memory_place.py). */
class MemoryPlaceFillTest {
 private val restStop=FoundPlace("덕평자연휴게소",37.2402,127.3906,"경기 이천시 마장면 덕평리 · 사진 위치에서 57 m")
 private val cafe=FoundPlace("휴게소 카페",37.2401,127.3904,"경기 이천시 마장면 덕평리 · 사진 위치에서 37 m")
 private fun guess(place:FoundPlace?=restStop,address:String="경기도 이천시 마장면 덕평로 154",building:String="덕평자연휴게소")=
  MemoryPlaceGuess(address,building,place,listOfNotNull(place,cafe.takeIf{place!=null}),place?.name?:"덕평로 154")
 @Test fun theNearbyPlaceBecomesTheChosenPlaceAndTheAutomaticTitle(){
  val g=guess()
  assertEquals("덕평자연휴게소",MemoryPlaceFill.label(g))
  assertEquals("덕평자연휴게소",MemoryPlaceFill.title(MemoryPlaceFill.DEFAULT_TITLE,true,g.place?.name))
  assertEquals("사진 위치 근처 2곳 · TMAP",MemoryPlaceFill.status(g))
  assertTrue(MemoryPlaceFill.info(g).startsWith("촬영 위치로 주소와 장소를 채웠어요"))
 }
 @Test fun aTitleTheUserTypedIsKept(){
  assertEquals("우리 강아지 첫 휴게소",MemoryPlaceFill.title("우리 강아지 첫 휴게소",false,"덕평자연휴게소"))
  // Placeholders from search are not place names.
  assertEquals(MemoryPlaceFill.DEFAULT_TITLE,MemoryPlaceFill.title(MemoryPlaceFill.DEFAULT_TITLE,true,"검색한 주소"))
  assertEquals(MemoryPlaceFill.DEFAULT_TITLE,MemoryPlaceFill.title(MemoryPlaceFill.DEFAULT_TITLE,true,null))
 }
 @Test fun withoutANearbyPlaceTheBuildingOrAddressIsShown(){
  assertEquals("덕평자연휴게소",MemoryPlaceFill.label(guess(place=null)))
  val noBuilding=guess(place=null,building="")
  assertEquals("경기도 이천시 마장면 덕평로 154",MemoryPlaceFill.label(noBuilding))
  assertEquals("",MemoryPlaceFill.status(noBuilding))
  assertTrue(MemoryPlaceFill.info(noBuilding).startsWith("촬영 위치의 주소를 채웠어요"))
  val nothing=guess(place=null,address="",building="")
  assertEquals("사진의 촬영 위치",MemoryPlaceFill.label(nothing))
  assertTrue(MemoryPlaceFill.info(nothing).startsWith("촬영 위치는 찾았지만"))
 }
 @Test fun pickingAnotherPlaceShowsItsAddressAndALaterLookupDoesNotOverrideIt(){
  assertEquals("경기 이천시 마장면 덕평리",MemoryPlaceFill.addressOf(cafe))
  assertEquals("서울 강남구 역삼동",MemoryPlaceFill.addressOf(FoundPlace("카페",37.5,127.0,"서울 강남구 역삼동")))
  assertTrue(MemoryPlaceFill.stillPhotoLocation(37.24,127.39,37.24,127.39))
  assertFalse(MemoryPlaceFill.stillPhotoLocation(cafe.latitude,cafe.longitude,37.24,127.39))
  assertFalse(MemoryPlaceFill.stillPhotoLocation(null,null,37.24,127.39))
 }
}

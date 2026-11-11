package com.familycare.navigation
import org.junit.Test
import org.junit.Assert.*
class VoiceDestinationTest {
 private fun query(text:String)=VoiceDestination.parse(text)?.query
 @Test fun goPhrasesBecomeDestinationSearches(){
  mapOf("강남역으로 안내해 줘" to "강남역","강남역 가자" to "강남역","강남역으로 가 줘." to "강남역","강남역까지 길 안내해 줘" to "강남역",
   "롯데월드 가는 길 알려 줘" to "롯데월드","나 서울역 데려다 줘" to "서울역","부산 가고 싶어" to "부산","제주도로 가자" to "제주도","서울로 가자" to "서울",
   "COEX로 가자" to "COEX","강남역에 가자" to "강남역","강남역 좀 가자" to "강남역","빨리 병원 가자" to "병원","강남역으로 출발" to "강남역",
   "목적지를 부산역으로 바꿔 줘" to "부산역","부산역을 목적지로 설정해 줘" to "부산역","목적지 강남역으로 해 줘" to "강남역","목적지 강남역" to "강남역",
   "대학로 가자" to "대학로","종로 가자" to "종로","IFC몰로 가 줘" to "IFC몰").forEach{(spoken,place)->
   val r=VoiceDestination.parse(spoken);assertNotNull(spoken,r);assertEquals(spoken,place,r!!.query);assertFalse(spoken,r.searchOnly)
  }
 }
 @Test fun findPhrasesListResultsFirst(){
  mapOf("가까운 주유소 찾아 줘" to "주유소","여기서 제일 가까운 편의점 찾아 줘" to "편의점","스타벅스 검색해 줘" to "스타벅스","가는 길에 주유소 찾아 줘" to "주유소").forEach{(spoken,place)->
   val r=VoiceDestination.parse(spoken);assertNotNull(spoken,r);assertEquals(spoken,place,r!!.query);assertTrue(spoken,r.searchOnly)
  }
 }
 @Test fun savedHomeAndWork(){
  assertEquals("집",query("집으로 가자"));assertEquals("집",VoiceDestination.savedSlot(query("우리 집으로 안내해 줘")!!))
  assertEquals("회사",VoiceDestination.savedSlot(query("회사로 가자")!!));assertNull(VoiceDestination.savedSlot("강남역"))
 }
 @Test fun familyStopsPickupsAndOtherRequestsAreLeftAlone(){
  listOf("가족이 보내준 데 잠깐 찍고 가자","추천지를 경유해 줘","추천해 준 장소를 일정 중간에 추가해줄래","아까 얘기한 장소를 들르는 걸로 경로 바꿔",
   "픽업하러 들렀다가 최종 목적지로 가자","추천한 곳도 들렀다 가자","그곳을 중간에 넣어 줘","가족부터 태우고 가자","추천지로 목적지를 바꿔 줘",
   "저 휴게소를 경유해서 가는 길로 안내해 줘","원래 목적지로 바로 가 줘","최종 목적지로 가자","경유하지 말고 바로 목적지로 가자","새로운 목적지를 찾아 줘",
   "강남역 가지 마","강남역 안 가","강남역까지 얼마나 걸려","강남역 어디야","음성 안내","안내해 줘","다시 안내","에어컨 켜 줘","오늘 날씨 알려줘",
   "음악 검색해 줘","거기로 가자","여기로 가자","강남역 가서 커피 마시고 집으로 가자","강남역","승인","두 번째","").forEach{
   assertNull(it,VoiceDestination.parse(it))
  }
 }
 @Test fun exactNamesRankFirstAndOnlyUnambiguousResultsGoDirectly(){
  val found=listOf("강남역 스타벅스","서울역 공항철도","서울역","서울역버스환승센터")
  assertEquals(listOf("서울역","서울역 공항철도","서울역버스환승센터","강남역 스타벅스"),VoiceDestination.rank("서울역",found){it})
  assertEquals("서울역",VoiceDestination.direct("서울역",VoiceDestination.rank("서울역",found){it}){it})
  assertNull(VoiceDestination.direct("강남역",listOf("강남역 2호선","강남역 신분당선")){it})
  assertNull(VoiceDestination.direct("이마트",listOf("이마트","이마트")){it})
  assertEquals("한 곳",VoiceDestination.direct("아무 이름",listOf("한 곳")){it})
 }
 @Test fun spokenChoices(){
  val names=listOf("강남역 2호선","강남역 신분당선","강남역사거리")
  mapOf("두 번째" to 1,"2번" to 1,"첫 번째로 가자" to 0,"세 번째요" to 2,"둘" to 1,"신분당선" to 1,"강남역 2호선으로 가 줘" to 0,"사거리" to 2).forEach{(spoken,index)->
   assertEquals(spoken,index,VoiceDestination.choice(spoken,names))
  }
  listOf("강남역","승인","아무거나").forEach{assertNull(it,VoiceDestination.choice(it,names))}
  assertEquals(0,VoiceDestination.choice("하나은행",listOf("하나은행 강남점","국민은행")))
  assertEquals(1,VoiceDestination.ordinal("두 번째"));assertNull(VoiceDestination.ordinal("강남역"))
 }
 @Test fun directionParticle(){assertEquals("으로",VoiceDestination.ro("강남역"));assertEquals("로",VoiceDestination.ro("서울"));assertEquals("로",VoiceDestination.ro("카페"));assertEquals("로",VoiceDestination.ro("COEX"))}
}

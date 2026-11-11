package com.familycare.navigation
import org.junit.Assert.*
import org.junit.Test

class RequestDisplayTest {
    @Test fun onlyFinishedRequestsCanBeCleared(){
        assertFalse(handledRequest("pending"))
        assertFalse(handledRequest("executing"))
        assertTrue(handledRequest("confirmed"))
        assertTrue(handledRequest("rejected"))
        assertTrue(handledRequest("navigation"))
    }
    @Test fun kindLabelsStayShortAndNameTheSender(){
        assertEquals("온도 22°C · 가족",requestKindLabel(PassengerRequest("a","climate","온도",target=22.0)))
        assertEquals("온도 22.5°C · 소유자",requestKindLabel(PassengerRequest("a","climate","온도",target=22.5,originRole="owner")))
        assertEquals("경유지 제안 · 2표 · 가족",requestKindLabel(PassengerRequest("a","waypoint","카페",37.0,127.0,votes=2)))
        assertEquals("픽업 · 승인된 권한",requestKindLabel(PassengerRequest("a","pickup","픽업",37.0,127.0,delegated=true)))
    }
    @Test fun iconsFollowTheRequestKind(){
        assertEquals("cool",requestIcon(PassengerRequest("a","climate","온도",target=20.0)))
        assertEquals("warm",requestIcon(PassengerRequest("a","climate","온도",target=26.0)))
        assertEquals("pin",requestIcon(PassengerRequest("a","pickup","픽업",37.0,127.0)))
        assertEquals("lock",requestIcon(PassengerRequest("a","permission","권한")))
    }
    @Test fun approveLabelsFitOneLine(){
        assertEquals("픽업 경유지 추가",approveLabel("pickup",true))
        assertEquals("픽업 경로 찾기",approveLabel("pickup",false))
        assertEquals("선택한 권한 승인",approveLabel("permission",false))
        listOf("note","climate","care","timer","destination").forEach{assertTrue(approveLabel(it,false).length<=8)}
        assertEquals("경유지로 추가",approveLabel("destination",true,restStop=true))
        assertEquals("완료",statusLabel("confirmed"))
        assertEquals("보류",statusLabel("rejected"))
    }
}

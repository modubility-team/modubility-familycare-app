package com.familycare.navigation
import org.junit.Assert.*
import org.junit.Test

class DrivingPopupTest {
    @Test fun pendingRequestsStayVisibleWithoutAudio(){
        val requests=listOf(PassengerRequest("a","climate","온도",target=23.0))
        repeat(3){assertEquals(1,driverPopup(emptyList(),requests,"")!!.pending)}
        assertEquals("공조 조절 요청이 도착했어요",driverPopup(emptyList(),requests,"")!!.title)
    }
    @Test fun urgentCareKeepsPendingCount(){
        val popup=driverPopup(listOf(CareAlert("timer","확인 타이머가 끝났어요",3)),listOf(PassengerRequest("a","note","메모")),"")!!
        assertTrue(popup.urgent);assertEquals("timer",popup.code);assertEquals(1,popup.pending)
    }
    @Test fun handledRequestsDisappearAndPrivateTextIsNotShown(){
        val r=PassengerRequest("a","note","개인 정보 12345")
        assertFalse(driverPopup(emptyList(),listOf(r),"")!!.title.contains("12345"))
        assertNull(driverPopup(emptyList(),listOf(r.copy(status="confirmed")),""))
        assertNull(driverPopup(emptyList(),listOf(r.copy(status="rejected")),""))
    }
}

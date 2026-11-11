package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class DriverAlertPolicyTest {
    private val alert=CareAlert("timer","확인 타이머가 끝났어요",3)
    private fun request(id:String="1",text:String="다음 안전한 장소에서 쉬고 싶어요",status:String="pending")=PassengerRequest(id,"note",text,status=status)
    @Test fun persistentAlertSoundsOnceAndFlickerDoesNotSpam(){
        val p=DriverAlertPolicy();val first=p.next(0,listOf(alert),emptyList(),0,"")!!
        p.delivered(first,0)
        assertNull(p.next(15000,listOf(alert),emptyList(),0,""))
        p.next(16000,emptyList(),emptyList(),0,"")
        assertNull(p.next(20000,listOf(alert),emptyList(),0,""))
        assertNotNull(p.next(120000,listOf(alert),emptyList(),0,""))
    }
    @Test fun requestsAreGroupedAndDoNotReadPrivateText(){
        val p=DriverAlertPolicy()
        val n=p.next(0,emptyList(),listOf(request("1","비밀번호 1234"),request("2")),0,"")!!
        assertEquals(setOf("1","2"),n.requestIds);assertFalse(n.text.contains("1234"))
        p.delivered(n,0)
        assertNull(p.next(30000,emptyList(),listOf(request("1"),request("2")),0,""))
    }
    @Test fun ordinaryRequestUsesKnownShortPhrase(){
        val n=DriverAlertPolicy().next(0,emptyList(),listOf(request()),0,"")!!
        assertTrue(n.text.contains("휴식을 요청"))
    }
    @Test fun minimumGapAndHigherPriorityAlert(){
        val p=DriverAlertPolicy();val n=p.next(0,listOf(alert),listOf(request()),0,"")!!
        assertEquals("timer",n.alertCode);p.delivered(n,0)
        assertNull(p.next(14999,listOf(alert),listOf(request()),0,""))
        assertNotNull(p.next(15000,listOf(alert),listOf(request()),0,""))
    }
    @Test fun failedFocusDefersButOldRequestsExpire(){
        val p=DriverAlertPolicy();p.next(0,emptyList(),listOf(request()),0,"");p.deferred(0)
        assertNull(p.next(9999,emptyList(),listOf(request()),0,""))
        assertNotNull(p.next(10000,emptyList(),listOf(request()),0,""))
        assertNull(p.next(60001,emptyList(),listOf(request()),0,""))
    }
    @Test fun completedOrOfflineRequestsNeverAnnounce(){
        assertNull(DriverAlertPolicy().next(0,emptyList(),listOf(request(status="confirmed")),0,""))
        assertNull(DriverAlertPolicy().next(0,emptyList(),listOf(request(status="offline")),0,""))
    }
    @Test fun reconnectDoesNotReplayHeardRequest(){
        val p=DriverAlertPolicy();p.delivered(p.next(0,emptyList(),listOf(request()),0,"")!!,0)
        p.next(20000,emptyList(),listOf(request(status="offline")),0,"")
        assertNull(p.next(30000,emptyList(),listOf(request()),0,""))
    }
    @Test fun drivingFeedbackHasLongerCooldown(){
        val p=DriverAlertPolicy();p.delivered(p.next(0,emptyList(),emptyList(),1,"천천히 가속해 주세요")!!,0)
        assertNull(p.next(20000,emptyList(),emptyList(),2,"부드럽게 감속해 주세요"))
        assertNotNull(p.next(30000,emptyList(),emptyList(),2,"부드럽게 감속해 주세요"))
        assertNull(p.next(40000,emptyList(),emptyList(),3,""))
    }
}

package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class CareEngineTest {
    @Test fun fastSpeedCallbacksStillMeasureAccelerationAndBraking(){
        val e=engine()
        for(i in 0..20)e.update(Signal.SPEED,2.0+i*.2,2000L+i*50L)
        assertEquals(1,e.feedback.acceleration)
        for(i in 1..20)e.update(Signal.SPEED,6.0-i*.25,3000L+i*50L)
        assertEquals(1,e.feedback.braking)
        e.update(Signal.SPEED,50.0,12000)
        assertEquals(1,e.feedback.acceleration)
        e.update(Signal.SPEED,null,12050);e.update(Signal.SPEED,70.0,12100)
        assertEquals(1,e.feedback.acceleration)
    }
    @Test fun familyBreakThresholdUsesAccumulatedDrivingAndResetsAfterRest(){
        for(band in 0..2){
            val e=CareEngine().apply{ux=UxPolicy(true,false);profile=FamilyProfile(setOf(Passenger.CHILD),band);startTrip(1000,1000)}
            val seconds=(if(band==0)60 else 90)*60
            for(i in 1..seconds){val now=1000L+i*1000L;e.update(Signal.SPEED,5.0,now);e.tick(now);if(i<seconds)assertFalse(e.alerts(now).any{it.code=="break"})}
            assertTrue(e.alerts(1000L+seconds*1000L).any{it.code=="break"})
            e.takeBreak();assertEquals(0L,e.drivingSinceBreakMillis)
        }
    }
    private fun engine()=CareEngine().apply{ux=UxPolicy(true,false,0);profile=FamilyProfile(setOf(Passenger.CHILD,Passenger.PET));startTrip(1000,1700000000000)}
    @Test fun unknownAndStaleSignalsNeverMeanHealthy(){
        val e=engine();e.startWatch(1000)
        assertTrue(e.alerts(1000).any{it.code=="missing"})
        e.update(Signal.TEMPERATURE,22.0,1000);e.update(Signal.BATTERY,80.0,1000)
        assertFalse(e.alerts(2000).any{it.code=="missing"})
        assertTrue(e.alerts(32000).any{it.code=="missing"})
    }
    @Test fun derivativeGapsInvalidValuesAndCooldownDoNotInflateEvents(){
        val e=engine();e.update(Signal.SPEED,1.0,2000);e.update(Signal.SPEED,5.0,3000)
        e.update(Signal.SPEED,9.0,4000);assertEquals(1,e.feedback.acceleration)
        e.update(Signal.SPEED,40.0,20000);assertEquals(1,e.feedback.acceleration)
        e.update(Signal.SPEED,Double.NaN,21000);assertNull(e.value(Signal.SPEED,21000))
        e.update(Signal.SPEED,50.0,22000);assertEquals(1,e.feedback.acceleration)
    }
    @Test fun gentleProfileChangesPresetAndCountsBraking(){
        val gentle=engine();val base=engine().apply{profile=FamilyProfile()}
        listOf(gentle,base).forEach{it.update(Signal.SPEED,2.0,2000);it.update(Signal.SPEED,5.0,3000)}
        assertEquals(1,gentle.feedback.acceleration);assertEquals(0,base.feedback.acceleration)
        gentle.update(Signal.SPEED,1.0,4000);assertEquals(1,gentle.feedback.braking)
    }
    @Test fun stationarySteeringAndMissingSpeedDoNotProduceFeedback(){
        val e=engine();e.update(Signal.STEERING,0.0,2000);e.update(Signal.STEERING,200.0,3000)
        assertEquals(0,e.feedback.steering)
        e.update(Signal.SPEED,5.0,4000);e.update(Signal.STEERING,0.0,4000)
        assertEquals(1,e.feedback.steering)
    }
    @Test fun driveTimeExcludesStaleSpeedAndSuspendedIntervals(){
        val e=engine();e.update(Signal.SPEED,5.0,1000);e.tick(2000);e.tick(9000)
        assertEquals(1000L,e.drivingMillis);e.tick(40000);e.tick(41000)
        assertEquals(1000L,e.drivingMillis)
    }
    @Test fun endTripRequiresRearCheckWithoutPretendingToDetectOccupants(){
        val e=engine();val t=e.endTrip(61000)!!
        assertEquals(60L,t.durationSeconds);assertTrue(e.exitCheckRequired)
        e.confirmRearCheck();assertFalse(e.exitCheckRequired)
        assertFalse(e.running)
    }
    @Test fun watchCriteriaAndTimerAreIndependent(){
        val e=engine();e.checkMinutes=1;e.startWatch(1000)
        e.update(Signal.TEMPERATURE,32.0,61000);e.update(Signal.BATTERY,15.0,61000)
        assertTrue(e.alerts(61000).map{it.code}.containsAll(listOf("timer","temperature","battery")))
        e.stopWatch();assertTrue(e.alerts(61000).isEmpty())
    }
    @Test fun prohibitedActionsFailClosed(){
        val e=CareEngine()
        assertThrows(IllegalStateException::class.java){e.startTrip(0,0)}
        e.ux=UxPolicy(true,true,80)
        assertThrows(IllegalStateException::class.java){e.confirmRearCheck()}
    }
    @Test fun invalidDoorAndLateSnapshotRemainUnknownOrNewest(){
        val e=engine();e.update(Signal.FL_DOOR,0.5,2000);assertNull(e.value(Signal.FL_DOOR,2000))
        e.update(Signal.FL_DOOR,1.0,3000);e.update(Signal.FL_DOOR,0.0,2500)
        assertEquals(1.0,e.value(Signal.FL_DOOR,3000)!!,0.0);assertTrue(e.exitCheckRequired)
    }
    @Test fun requestsRejectInvalidCoordinates(){
        assertFalse(PassengerRequest("r","destination","장소",Double.NaN,127.0).valid())
        assertFalse(PassengerRequest("r","waypoint","장소",91.0,127.0).valid())
        assertTrue(PassengerRequest("r","note","쉬고 싶어요").valid())
    }
    @Test fun climateRequestsRequireFiniteBoundedHalfDegreeValues(){
        for(t in listOf(18.0,22.5,28.0))assertTrue(PassengerRequest("c","climate","온도",target=t).valid())
        for(t in listOf(17.5,28.5,22.3,Double.NaN,Double.POSITIVE_INFINITY))assertFalse(PassengerRequest("c","climate","온도",target=t).valid())
        assertFalse(PassengerRequest("c","climate","온도").valid())
    }
    @Test fun acknowledgedRearCheckDoesNotRepeatForUnchangedOpenDoor(){
        val e=engine();e.update(Signal.FL_DOOR,1.0,2000);e.confirmRearCheck()
        e.update(Signal.FL_DOOR,1.0,7000);assertFalse(e.exitCheckRequired)
        e.update(Signal.FL_DOOR,0.0,8000);e.update(Signal.FL_DOOR,1.0,9000);assertTrue(e.exitCheckRequired)
    }
    @Test fun vehicleWatchIsIndependentOfEndingTrip(){
        val e=engine();e.startWatch(1000);e.endTrip(6000)
        assertTrue(e.watching);e.stopWatch();assertFalse(e.watching)
    }
}

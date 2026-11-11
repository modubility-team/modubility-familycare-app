package com.familycare.navigation
import org.junit.Assert.*
import org.junit.Test

class JourneyRecorderTest {
    private fun fix(lat:Double,time:Long,accuracy:Float=5f)=PositionFix(GeoPoint(lat,127.0),accuracy,null,null,time,true)
    @Test fun onlyExplicitRecordingAcceptsFreshPlausibleMovement(){
        val r=JourneyRecorder();r.sample(fix(37.0,1000),1000,100000,2.0);assertTrue(r.points.isEmpty())
        r.start(100000,"가족");r.sample(fix(37.0,1000),1000,100000,2.0)
        r.sample(fix(37.0001,2000),2000,101000,2.0);assertTrue(r.meters in 10.0..12.0)
        r.sample(fix(38.0,3000),3000,102000,2.0);assertTrue(r.meters<12)
        r.sample(fix(38.0001,4000,100f),4000,103000,2.0);assertTrue(r.meters<12)
        r.finish(104000);r.sample(fix(38.0002,5000),5000,104000,2.0);assertTrue(r.meters<12)
    }
    @Test fun stopsNeedDwellAndScoresExcludeRestAlerts(){
        val r=JourneyRecorder();r.start(100000,"가족")
        for(t in 0..65)r.sample(fix(37.0,t*1000L),t*1000L,100000+t*1000L,0.0)
        assertEquals(1,r.events.count{it.kind=="stop"});r.event(166000,"rest","휴식");assertEquals(100,r.score)
        r.event(167000,"brake","감속");assertEquals(97,r.score)
    }
    @Test fun stricterCombinedProfileAndRollingMotionLoad(){
        val child=FamilyProfile(setOf(Passenger.CHILD),0)
        val combined=FamilyProfile(setOf(Passenger.CHILD,Passenger.PET),0)
        assertEquals(child.accelerationLimit,combined.accelerationLimit,0.0)
        assertTrue(child.copy(sensitivity=2).accelerationLimit<child.accelerationLimit)
        val e=CareEngine();e.ux=UxPolicy(true,false,0);e.startTrip(0,100000)
        e.update(Signal.SPEED,2.0,1000);e.update(Signal.SPEED,8.0,2000)
        assertEquals(20,e.motionLoad(2000));assertEquals(0,e.motionLoad(183000))
    }
}

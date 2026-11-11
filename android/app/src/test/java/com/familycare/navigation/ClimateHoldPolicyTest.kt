package com.familycare.navigation
import org.junit.Test
import org.junit.Assert.*
class ClimateHoldPolicyTest {
    @Test fun stalePowerBatteryOrLeavingParkStopsAutomaticWrites(){
        assertNull(ClimateHoldPolicy.stopReason(true,true,1.0,23.0,80.0,5000))
        assertNotNull(ClimateHoldPolicy.stopReason(false,true,1.0,23.0,80.0,5000))
        assertNotNull(ClimateHoldPolicy.stopReason(true,false,1.0,23.0,80.0,5000))
        assertNotNull(ClimateHoldPolicy.stopReason(true,true,null,23.0,80.0,5000))
        assertNotNull(ClimateHoldPolicy.stopReason(true,true,1.0,null,80.0,5000))
        assertNotNull(ClimateHoldPolicy.stopReason(true,true,1.0,23.0,25.0,5000))
        assertNotNull(ClimateHoldPolicy.stopReason(true,true,1.0,23.0,80.0,0))
    }
    @Test fun thermostatAdjustsOnlyOneDegreeWithinApprovedRange(){
        assertEquals(22.0,ClimateHoldPolicy.target(23.0,30.0),0.0)
        assertEquals(24.0,ClimateHoldPolicy.target(23.0,15.0),0.0)
        assertEquals(23.0,ClimateHoldPolicy.target(23.0,24.0),0.0)
    }
}

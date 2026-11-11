package com.familycare.navigation
import org.junit.Assert.*
import org.junit.Test
class ArrivalCareTest {
    @Test fun rearCheckWaitsForParkAndDoesNotReturnAfterConfirmation(){
        val engine=CareEngine();engine.profile=FamilyProfile(setOf(Passenger.CHILD))
        engine.destinationArrived(false);assertFalse(engine.exitCheckRequired)
        engine.destinationArrived(true);assertTrue(engine.exitCheckRequired)
        engine.exitCheckRequired=false
        engine.destinationArrived(true);assertFalse(engine.exitCheckRequired)
        engine.destinationChanged();engine.destinationArrived(true);assertTrue(engine.exitCheckRequired)
    }
    @Test fun arrivalDoesNotInventRearOccupants(){
        val engine=CareEngine();engine.destinationArrived(true);assertFalse(engine.exitCheckRequired)
    }
}

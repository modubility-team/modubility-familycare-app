package com.familycare.navigation
import org.junit.Assert.*
import org.junit.Test
class IgnitionSessionTest {
 @Test fun unsupportedUsesOnlyLiveConnectionAndUnknownDoesNotGrant(){
  val s=IgnitionSession(null);s.update(null,true);assertFalse(s.on)
  s.configure(false);s.update(null,true);assertTrue(s.on);val first=s.epoch
  s.update(null,true);assertEquals(first,s.epoch);s.update(null,false);assertFalse(s.on)
  s.update(null,true);assertNotEquals(first,s.epoch);s.configure(true);s.update(null,true);assertFalse(s.on)
 }

 @Test fun eachCycleHasNewAuthorityAndUnknownRevokes(){
  val s=IgnitionSession();assertFalse(s.on);s.update(4.0);val first=s.epoch;assertTrue(s.on)
  s.update(5.0);assertEquals(first,s.epoch);s.update(4.0);assertEquals(first,s.epoch)
  for(off in listOf(null,0.0,1.0,2.0,3.0,Double.NaN)){s.update(off);assertFalse(s.on);s.update(4.0);assertNotEquals(first,s.epoch)}
 }
}

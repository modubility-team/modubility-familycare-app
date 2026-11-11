package com.familycare.tablet;
import org.junit.Test;
import static org.junit.Assert.*;
public class GpsChoiceTest {
    static final long S=1_000_000_000L;
    @Test public void onlyFreshAccurateFixesAreForwarded(){
        assertTrue(GpsChoice.usable(true,12f,500));assertTrue(GpsChoice.usable(true,50f,3000));
        assertFalse(GpsChoice.usable(false,12f,500));assertFalse(GpsChoice.usable(true,51f,500));assertFalse(GpsChoice.usable(true,0f,500));
        assertFalse(GpsChoice.usable(true,12f,3001));assertFalse(GpsChoice.usable(true,12f,-1));
    }
    @Test public void sameSourceKeepsUpdating(){assertTrue(GpsChoice.prefer("network",11*S,40f,"network",10*S,20f));}
    @Test public void olderOrDuplicateFixIsIgnored(){assertFalse(GpsChoice.prefer("gps",10*S,5f,"gps",10*S,5f));assertFalse(GpsChoice.prefer("fused",9*S,5f,"gps",10*S,5f));}
    @Test public void lessAccurateSourceWaitsWhileGpsIsFresh(){
        assertFalse(GpsChoice.prefer("network",11*S,30f,"gps",10*S,5f));
        assertTrue(GpsChoice.prefer("network",12*S,30f,"gps",10*S,5f));
        assertTrue(GpsChoice.prefer("gps",11*S,5f,"network",10*S,30f));
    }
    @Test public void sourcesHaveKoreanLabels(){assertEquals("GPS",GpsChoice.label("gps"));assertEquals("Wi-Fi·기지국",GpsChoice.label("network"));assertEquals("GPS·Wi-Fi",GpsChoice.label("fused"));assertEquals("위치",GpsChoice.label(null));}
}

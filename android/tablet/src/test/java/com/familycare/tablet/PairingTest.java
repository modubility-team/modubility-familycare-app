package com.familycare.tablet;
import org.junit.Test;
import static org.junit.Assert.*;
public class PairingTest {
    final String token="a".repeat(43);
    @Test public void privateLinkAccepted(){assertEquals("http://192.168.219.2:8771",Pairing.parse("http://192.168.219.2:8771/join#"+token).base);}
    @Test public void invalidLinksRejected(){for(String v:new String[]{"https://192.168.1.2:8771/join#","http://example.com:8771/join#","http://8.8.8.8:8771/join#","http://192.168.1.2:80/join#","http://user@192.168.1.2:8771/join#","http://192.168.999.1:8771/join#","http://192.168.1.2:8771/other#","http://192.168.1.2:8771/join?q=1#"}){try{Pairing.parse(v+token);fail(v);}catch(IllegalArgumentException expected){}}}
    @Test public void shortTokenRejected(){try{Pairing.parse("http://192.168.1.2:8771/join#short");fail();}catch(IllegalArgumentException expected){}}
    @Test public void letterboxCoordinatesScaleExactly(){assertArrayEquals(new int[]{800,400},Pairing.point(400,200,800,450,1600,900));assertArrayEquals(new int[]{1598,898},Pairing.point(799,449,800,450,1600,900));assertNull(Pairing.point(-1,20,800,450,1600,900));assertNull(Pairing.point(800,20,800,450,1600,900));}
}

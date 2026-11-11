package com.familycare.phone
import org.junit.Assert.*
import org.junit.Test
class JoinLinkTest {
    private val code="A".repeat(16)+"."+"B".repeat(43)
    @Test fun acceptsScopedLocalDemoAndHttps(){
        assertEquals("http://192.168.1.3:8765",JoinLink.parse("http://192.168.1.3:8765/join#$code",true).origin)
        assertEquals("A".repeat(16),JoinLink.parse("https://demo.example/care#$code",false).session)
    }
    @Test fun releaseRejectsPlaintextAndUnexpectedLinks(){
        for(raw in listOf("http://192.168.1.3/join#$code","https://user:password@demo.example/join#$code","https://demo.example/other#$code","https://demo.example/join?x=1#$code","https://demo.example/join#bad")){
            assertTrue(runCatching{JoinLink.parse(raw,false)}.isFailure)
        }
    }
    @Test fun debugRejectsPublicAndMalformedHttp(){
        for(host in listOf("8.8.8.8","example.com","192.999.1.2","172.32.0.1"))assertTrue(runCatching{JoinLink.parse("http://$host/join#$code",true)}.isFailure)
    }
}

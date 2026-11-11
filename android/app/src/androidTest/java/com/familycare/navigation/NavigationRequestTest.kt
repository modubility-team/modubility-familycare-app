package com.familycare.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Actual local family relay -> driver approval -> HTTPS route -> result returned to the family. */
class NavigationRequestTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun api(link:String,action:String,body:JSONObject=JSONObject()):JSONObject{
        val credentials=android.net.Uri.parse(link).fragment!!.split('.',limit=2)
        val c=URL("http://127.0.0.1:8765/api/sessions/${credentials[0]}/$action").openConnection() as HttpURLConnection
        try{
            c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${credentials[1]}")
            c.outputStream.use{it.write(body.toString().toByteArray())}
            assertEquals(200,c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    @Test fun familyDestinationAndWaypointRequireDriverApproval(){
        val care=CareRuntime.get(compose.activity);val nav=care.navigation
        try{
            compose.waitUntil(20000){care.engine.ux.showDetails&&nav.locationUsable}
            compose.runOnUiThread{nav.cancel();care.share()}
            compose.waitUntil(15000){care.relay.connected&&care.relay.links.containsKey("family")}
            val family=care.relay.links.getValue("family")
            fun request(id:String,kind:String,point:GeoPoint){
                api(family,"request",JSONObject().put("id",id).put("client","navigation-test")
                    .put("kind",kind).put("title","가족의 $kind 제안").put("latitude",point.latitude).put("longitude",point.longitude))
                compose.waitUntil(10000){care.relay.requests.any{it.id==id}}
                val before=nav.route
                assertFalse(nav.active)
                val pending=care.relay.requests.first{it.id==id};assertEquals("pending",pending.status)
                assertSame("A family proposal alone cannot replace the route",before,nav.route)
                compose.runOnUiThread{care.decide(pending,true)}
                compose.waitUntil(40000){care.relay.requests.any{it.id==id&&it.status !in listOf("pending","executing")}}
                val result=care.relay.requests.first{it.id==id}
                assertEquals(result.message,"confirmed",result.status)
                assertNotNull(nav.route);assertFalse("Driver must start guidance separately",nav.active)
                compose.waitUntil(10000){
                    val requests=api(family,"status").getJSONArray("requests")
                    (0 until requests.length()).any{requests.getJSONObject(it).let{r->r.optString("id")==id&&r.optString("status")=="confirmed"}}
                }
            }
            request("route-request-test","destination",GeoPoint(37.501,127.05))
            val destination=nav.route!!.points.last()
            android.os.SystemClock.sleep(2200)
            val via=GeoPoint(37.49,127.042)
            request("via-request-test","waypoint",via)
            assertTrue(nav.route!!.project(via).offRoute<200)
            assertTrue(nav.route!!.points.last().distance(destination)<5)
            android.os.SystemClock.sleep(2200)
            var rejected:Boolean?=null
            compose.runOnUiThread{nav.plan("지원 범위 밖 목적지",GeoPoint(0.0,0.0)){ok,_->rejected=!ok}}
            compose.waitUntil(40000){rejected!=null}
            assertEquals(true,rejected)
            assertNull("A failed new destination must not replay the previous destination",nav.route)
            assertFalse(nav.active)
        }finally{
            compose.runOnUiThread{nav.cancel();care.relay.end()}
            compose.waitUntil(15000){!care.relay.sharing}
        }
    }
}

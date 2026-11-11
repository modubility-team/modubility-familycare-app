package com.familycare.navigation

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.familycare.ui.MapGateway
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in TMAP API test with public Seoul example coordinates; no response snapshots. */
class TmapIntegrationTest {
    private fun post(path:String,body:JSONObject=JSONObject(),token:String=""):JSONObject{
        val c=URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try{
            c.connectTimeout=4000;c.readTimeout=5000;c.requestMethod="POST";c.doOutput=true
            c.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token")
            c.outputStream.use{it.write(body.toString().toByteArray())}
            assertEquals(200,c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    @Test fun authenticatedSearchAndRouteThroughPcRelay(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTmap")=="true")
        val session=post("/api/sessions");val id=session.getString("id");val token=session.getString("token")
        try{
            val client=MapGateway("http://127.0.0.1:8765",id,token)
            assertTrue(client.call("maps-status",JSONObject()).getBoolean("routingConfigured"))
            val start=JSONArray(listOf(127.03636666,37.48544722));val end=JSONArray(listOf(127.0354,37.4703))
            val route=RoutingClient.parse(client.call("maps-route",JSONObject().put("consent",true).put("start",start).put("end",end)).toString(),"공개 예제 장소")
            assertEquals("TMAP",route.provider);assertTrue(route.points.size>2);assertTrue(route.turns.isNotEmpty())
            assertTrue(route.seconds>0);assertTrue(route.remainingSeconds(route.meters/2) in 0.0..route.seconds)
            SystemClock.sleep(1100)
            val places=client.call("maps-search",JSONObject().put("consent",true).put("center",start).put("query","양재시민의숲").put("facility",""))
            assertTrue(places.getJSONArray("places").length()>0)
            SystemClock.sleep(1100)
            val midpoint=route.points[route.points.size/2]
            val preview=client.call("maps-preview",JSONObject().put("consent",true).put("start",start).put("end",end).put("via",JSONArray(listOf(midpoint.longitude,midpoint.latitude))))
            assertTrue(preview.getDouble("extraSeconds").isFinite())
        }finally{post("/api/sessions/$id/end",token=token)}
    }
}

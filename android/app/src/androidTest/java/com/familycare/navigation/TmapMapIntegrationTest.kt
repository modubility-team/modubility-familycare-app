package com.familycare.navigation

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.familycare.ui.*
import com.skt.tmap.TMapView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in live SDK check, using public Seoul sample coordinates, never a saved credential. */
class TmapMapIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun post(path:String,token:String=""):JSONObject{
        val c=URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try{
            c.connectTimeout=4000;c.readTimeout=5000;c.requestMethod="POST";c.doOutput=true
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer $token")
            c.outputStream.use{it.write("{}".toByteArray())};assertEquals(200,c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    private fun map(view:View):TMapView?=if(view is TMapView)view else if(view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull{map(view.getChildAt(it))}else null
    @Test fun guidanceCameraFacesTheRoadAndKeepsVehicleBelowCentre(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTmap")=="true")
        val session=post("/api/sessions");val id=session.getString("id");val token=session.getString("token")
        val gateway=MapGateway("http://127.0.0.1:8765",id,token)
        val camera=MapViewportController()
        val position=mutableStateOf(MapPosition(MapPlaces.LATITUDE,MapPlaces.LONGITUDE,0f,navigation=true,speedMetersPerSecond=12f,turnDistance=80.0))
        try{
            compose.activity.runOnUiThread{compose.activity.setContent{
                ProvideMapGateway(gateway){PlaceMap(modifier=Modifier.fillMaxSize(),controller=camera,position=position.value,interactive=false)}
            }}
            compose.waitUntil(60000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle{camera.guidance(true)}
            for(bearing in listOf(0f,90f,180f,270f)){
                compose.runOnIdle{position.value=position.value.copy(heading=bearing)}
                var detail=""
                try{compose.waitUntil(10000){
                    var good=false
                    compose.activity.runOnUiThread{
                        val m=checkNotNull(map(compose.activity.window.decorView))
                        val p=position.value;val radians=Math.toRadians(bearing.toDouble())
                        val aheadLat=p.latitude+50*kotlin.math.cos(radians)/111195
                        val aheadLon=p.longitude+50*kotlin.math.sin(radians)/(111195*kotlin.math.cos(Math.toRadians(p.latitude)))
                        val x=m.getMapXForPoint(p.latitude,p.longitude);val y=m.getMapYForPoint(p.latitude,p.longitude)
                        val ax=m.getMapXForPoint(aheadLat,aheadLon);val ay=m.getMapYForPoint(aheadLat,aheadLon)
                        detail="bearing=$bearing car=$x,$y ahead=$ax,$ay size=${m.width},${m.height}"
                        good=y>m.height*.53&&y<m.height*.87&&kotlin.math.abs(x-m.width/2)<m.width*.12&&ay<y-10&&kotlin.math.abs(ax-x)<30
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();good
                }}catch(error:Throwable){throw AssertionError(detail,error)}
                println("NAVIGATION_CAMERA $detail")
                compose.runOnIdle{assertTrue(camera.headingUp);assertTrue(camera.following)}
            }
            compose.onNodeWithTag("live-map").performTouchInput{click(center)}
            compose.runOnIdle{assertTrue("Driving touches must not stop following",camera.following)}
            compose.runOnIdle{camera.overview(listOf(MapRoutePoint(37.48,127.03),MapRoutePoint(37.49,127.04)))}
            compose.runOnIdle{assertFalse(camera.following);assertFalse(camera.headingUp)}
            compose.runOnIdle{camera.toggleOrientation();assertTrue(camera.headingUp);assertFalse(camera.following);assertEquals(270.0,camera.cameraBearing,.1)}
        }finally{post("/api/sessions/$id/end",token)}
    }
    @Test fun nativeTilesCoordinatesControlsAndReadOnly(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTmap")=="true")
        val session=post("/api/sessions");val id=session.getString("id");val token=session.getString("token")
        val gateway=MapGateway("http://127.0.0.1:8765",id,token)
        val camera=MapViewportController();val interactive=mutableStateOf(true)
        val photos=mutableStateOf<List<MemoryPin>>(emptyList());val showPosition=mutableStateOf(true)
        var picks=0;var opened=""
        try{
            compose.activity.runOnUiThread{compose.activity.setContent{
                ProvideMapGateway(gateway){PlaceMap(modifier=Modifier.fillMaxSize(),controller=camera,
                    position=if(showPosition.value)MapPosition(MapPlaces.LATITUDE,MapPlaces.LONGITUDE)else null,
                    memories=photos.value,onMemory={opened=it},
                    route=listOf(MapRoutePoint(37.4848,127.0358),MapRoutePoint(MapPlaces.LATITUDE,MapPlaces.LONGITUDE),MapRoutePoint(37.4862,127.037)),
                    interactive=interactive.value,onPick={_,_->picks++})}
            }}
            compose.waitUntil(60000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(10000){
                var centered=false
                compose.activity.runOnUiThread{
                    val point=map(compose.activity.window.decorView)?.centerPoint
                    centered=point!=null&&kotlin.math.abs(point.latitude-MapPlaces.LATITUDE)<.001&&kotlin.math.abs(point.longitude-MapPlaces.LONGITUDE)<.001
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                centered
            }
            compose.runOnIdle{
                assertTrue(camera.tmapActive)
                val center=checkNotNull(map(compose.activity.window.decorView)).centerPoint
                assertEquals(MapPlaces.LATITUDE,center.latitude,.001)
                assertEquals(MapPlaces.LONGITUDE,center.longitude,.001)
            }
            compose.onNodeWithTag("map-zoom-in").performClick()
            compose.onNodeWithTag("map-zoom-out").performClick()
            compose.onNodeWithTag("live-map").performTouchInput{click(center)}
            compose.waitUntil(5000){picks>0}
            val thumbnail=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.MAGENTA)}
            val bytes=java.io.ByteArrayOutputStream().also{thumbnail.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
            thumbnail.recycle()
            compose.runOnIdle{
                showPosition.value=false
                photos.value=listOf(MemoryPin("sample-photo","테스트 사진",MapPlaces.LATITUDE,MapPlaces.LONGITUDE,android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)))
            }
            compose.waitUntil(5000){compose.onAllNodesWithTag("map-memories").fetchSemanticsNodes().isNotEmpty()}
            android.os.SystemClock.sleep(500)
            compose.onNodeWithTag("live-map").performTouchInput{click(center)}
            compose.waitUntil(5000){opened=="sample-photo"}
            compose.runOnIdle{interactive.value=false}
            val before=picks;opened=""
            compose.onNodeWithTag("live-map").performTouchInput{click(center)}
            compose.runOnIdle{assertEquals(before,picks);assertEquals("",opened)}
            android.os.SystemClock.sleep(3000)
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            compose.activity.openFileOutput("tmap-map-validation.png",0).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }finally{post("/api/sessions/$id/end",token)}
    }
}

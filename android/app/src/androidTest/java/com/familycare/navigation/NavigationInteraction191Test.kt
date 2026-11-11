package com.familycare.navigation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.familycare.ui.*
import com.skt.tmap.TMapView
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.ByteArrayOutputStream
class NavigationInteraction191Test {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun api(link:String,action:String,b:JSONObject):JSONObject{
  val p=android.net.Uri.parse(link).fragment!!.split('.',limit=2);val c=URL("http://127.0.0.1:8765/api/sessions/${p[0]}/$action").openConnection() as HttpURLConnection
  try{c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${p[1]}");c.readTimeout=10000;c.connectTimeout=4000;c.outputStream.use{it.write(b.toString().toByteArray())};assertEquals(200,c.responseCode);return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
 }
 private fun map(v:View):TMapView?=if(v is TMapView)v else if(v is ViewGroup)(0 until v.childCount).firstNotNullOfOrNull{map(v.getChildAt(it))}else null
 private fun screenshot(name:String){val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(compose.activity.filesDir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
 @Test fun oldPhotoVisibleOnlyExplicitSearchAndSmoothNativeCamera(){
  val care=CareRuntime.get(compose.activity);val proof=JSONObject()
  try{
   compose.waitUntil(20000){care.parked&&care.navigation.locationUsable}
   compose.runOnUiThread{care.navigation.cancel();care.share()};compose.waitUntil(20000){care.relay.connected&&care.relay.links.containsKey("family")}
   val family=care.relay.links.getValue("family")
   val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(0,44,95));val paint=Paint().apply{color=android.graphics.Color.YELLOW};canvas.drawCircle(80f,50f,30f,paint)
   val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,80,bytes);val encoded=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);bitmap.recycle()
   val lat=MapPlaces.LATITUDE+.0008;val lon=MapPlaces.LONGITUDE+.0008
   api(family,"memories",JSONObject().put("id","regression-photo-191").put("client","test191").put("title","이전 날짜 사진 확인").put("captured","2020-01-02 10:30").put("latitude",lat).put("longitude",lon).put("image",encoded).put("thumbnail",encoded))
   compose.waitUntil(20000){care.relay.memories.any{it.optString("id")=="regression-photo-191"}}
   compose.waitUntil(60000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithTag("navigation-photos").assertExists();compose.onNodeWithTag("map-memories").assertExists();proof.put("pastDatePhotoVisible",true)
   compose.onNodeWithTag("live-map").performTouchInput{click(Offset(width*.6f,height*.4f))};SystemClock.sleep(400);compose.onNodeWithTag("destination-picker").assertDoesNotExist()
   compose.onNodeWithTag("live-map").performTouchInput{swipe(Offset(width*.65f,height*.55f),Offset(width*.35f,height*.3f),600)};SystemClock.sleep(400);compose.onNodeWithTag("destination-picker").assertDoesNotExist()
   compose.onNodeWithTag("navigation-search").performClick();compose.onNodeWithTag("destination-picker").assertExists();compose.onNodeWithTag("close-destination-picker").performClick();compose.onNodeWithTag("destination-picker").assertDoesNotExist();proof.put("searchOnlyByExplicitButton",true)
   compose.onNodeWithTag("navigation-photos").performClick();compose.onNodeWithTag("locate-photo-regression-photo-191").performClick()
   compose.waitUntil(10000){var ok=false;compose.runOnUiThread{val c=map(compose.activity.window.decorView)?.centerPoint;ok=c!=null&&kotlin.math.abs(c.latitude-lat)<.00005&&kotlin.math.abs(c.longitude-lon)<.00005};ok}
   SystemClock.sleep(1000);screenshot("navigation191-photo.png")
   compose.onNodeWithTag("live-map").performTouchInput{click(center)}
   compose.waitUntil(8000){compose.onAllNodesWithText("추억 전체 보기").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("destination-picker").assertDoesNotExist();proof.put("photoMarkerOpensPhoto",true)
   compose.onNodeWithText("닫기").performClick()
   api(family,"delete-memory",JSONObject().put("id","regression-photo-191").put("client","test191"));compose.waitUntil(15000){care.relay.memories.none{it.optString("id")=="regression-photo-191"}};proof.put("photoDeletionPropagates",true)
   // The same production renderer, with two known GPS fixes one second apart.
   val camera=MapViewportController();val point=mutableStateOf(MapPosition(37.486,127.035,0f,fixElapsedMillis=0))
   compose.runOnUiThread{compose.activity.setContent{ProvideMapGateway(care.relay.mapGateway()){PlaceMap(modifier=Modifier.fillMaxSize(),controller=camera,position=point.value,interactive=false)}}}
   compose.waitUntil(60000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()};SystemClock.sleep(1200)
   compose.runOnUiThread{point.value=point.value.copy(latitude=37.4862,fixElapsedMillis=1000)}
   compose.mainClock.advanceTimeBy(32)
   val samples=mutableListOf<Double>()
   repeat(24){SystemClock.sleep(45);compose.runOnUiThread{samples.add(checkNotNull(map(compose.activity.window.decorView)).centerPoint.latitude)}}
   val distinct=samples.map{(it*1e8).toLong()}.distinct().size;proof.put("nativeIntermediateCameraPositions",distinct);proof.put("cameraSamples",JSONArray(samples));assertTrue("Only $distinct rendered positions",distinct>=8)
   assertTrue(samples.zipWithNext().all{it.second>=it.first-1e-7});assertTrue(samples.all{it<=37.4862+1e-7});screenshot("navigation191-motion.png")
  }catch(t:Throwable){proof.put("error",t.stackTraceToString());throw t}finally{
   File(compose.activity.filesDir,"navigation191-proof.json").writeText(proof.toString(2));compose.runOnUiThread{care.navigation.cancel();care.relay.end()}
  }
 }
}

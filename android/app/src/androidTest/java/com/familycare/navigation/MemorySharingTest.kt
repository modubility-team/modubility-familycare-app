package com.familycare.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.familycare.ui.MapPlaces
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Synthetic photo only: family upload -> vehicle map/preview -> deletion propagation. */
class MemorySharingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun api(link:String,action:String,body:JSONObject):JSONObject{
        val parts=android.net.Uri.parse(link).fragment!!.split('.',limit=2)
        val c=URL("http://127.0.0.1:8765/api/sessions/${parts[0]}/$action").openConnection() as HttpURLConnection
        try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${parts[1]}")
            c.outputStream.use{it.write(body.toString().toByteArray())};assertEquals(200,c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    private fun jpeg(bitmap:Bitmap):String{
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,80,out)
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
    }
    private fun capture(name:String){
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun selectedPhotoAppearsOnFamilyMapAndCanBeRemoved(){
        val care=CareRuntime.get(compose.activity)
        val bitmap=Bitmap.createBitmap(400,240,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(22,65,98))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(92,192,231);strokeWidth=58f}
        canvas.drawLine(0f,210f,400f,60f,paint)
        paint.color=Color.WHITE;paint.textSize=26f;canvas.drawText("FAMILY TRIP / DEMO",32f,52f,paint)
        val thumb=Bitmap.createScaledBitmap(bitmap,96,58,true)
        val photo=JSONObject().put("id","memory-integration").put("client","test-private-client")
            .put("title","함께한 하루 · 시연 사진").put("captured",LocalDate.now().toString()+" 10:30")
            .put("latitude",MapPlaces.LATITUDE).put("longitude",MapPlaces.LONGITUDE)
            .put("image",jpeg(bitmap)).put("thumbnail",jpeg(thumb))
        try{
            compose.waitUntil(15000){care.engine.ux.showDetails}
            compose.runOnUiThread{care.share()}
            compose.waitUntil(15000){care.relay.connected&&care.relay.links.containsKey("family")}
            val family=care.relay.links.getValue("family")
            api(family,"memories",photo)
            compose.waitUntil(15000){care.relay.memories.any{it.optString("id")=="memory-integration"}}
            assertFalse(care.relay.memories.first().has("image"))
            compose.waitUntil(45000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(10000){compose.onAllNodesWithTag("map-memories").fetchSemanticsNodes().isNotEmpty()}
            // MapLibre renders its native texture after Compose and its 250 ms camera animation.
            // Wait for that frame before a real marker hit-test, not just for Compose semantics.
            android.os.SystemClock.sleep(800)
            capture("vehicle-home-080")
            compose.onNodeWithTag("live-map").performTouchInput{click(androidx.compose.ui.geometry.Offset(center.x,center.y-18))}
            try{compose.waitUntil(5000){compose.onAllNodesWithText("함께한 하루 · 시연 사진",substring=true).fetchSemanticsNodes().isNotEmpty()}}
            catch(e:AssertionError){capture("vehicle-marker-debug");throw e}
            compose.onNodeWithText("추억 전체 보기").performClick()
            compose.waitUntil(45000){compose.onAllNodesWithTag("map-ready").fetchSemanticsNodes().isNotEmpty()}
            capture("vehicle-memories-080")
            compose.onNodeWithText("함께한 하루 · 시연 사진").performScrollTo().performClick()
            compose.waitUntil(15000){care.relay.memoryDetail?.optString("id")=="memory-integration"}
            compose.onNodeWithTag("memory-preview").performScrollTo().assertIsDisplayed()
            assertEquals(photo.getString("image"),care.relay.memoryDetail!!.getString("image"))
            api(family,"delete-memory",JSONObject().put("id","memory-integration").put("client","test-private-client"))
            compose.waitUntil(15000){care.relay.memories.isEmpty()}
            compose.onNodeWithTag("memory-preview").assertDoesNotExist()
        }finally{
            bitmap.recycle();thumb.recycle()
            compose.runOnUiThread{care.relay.end()}
            compose.waitUntil(15000){!care.relay.sharing}
        }
    }
}

package com.familycare.phone

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.familycare.ui.CameraPanel
import com.familycare.ui.MapGateway
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Test-only HTTP source. This verifies UI failures/park gating, not a physical camera. */
class CameraPanelTest {
    @get:Rule val rule=createComposeRule()
    @Test fun frameDisappearsOnFailureAndDriving(){
        val socket=ServerSocket(0)
        val failed=java.util.concurrent.atomic.AtomicBoolean(false)
        val frames=java.util.concurrent.atomic.AtomicInteger(0)
        val permitted=mutableStateOf(true)
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}
        val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,80,bytes);bitmap.recycle()
        val jpeg=Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)
        val worker=thread(isDaemon=true){
            while(!socket.isClosed)try{socket.accept().use{client->
                client.soTimeout=5000
                val reader=client.getInputStream().bufferedReader()
                val first=reader.readLine();var length=0
                while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                val body=CharArray(length);var read=0;while(read<length){val n=reader.read(body,read,length-read);if(n<0)break;read+=n}
                val frame=first.contains("camera-frame")
                if(frame)frames.incrementAndGet()
                val code=if(frame&&failed.get())502 else 200
                val result=if(code!=200)JSONObject().put("error","카메라 연결 끊김") else if(frame)JSONObject().put("image",jpeg).put("capturedAt",System.currentTimeMillis()) else JSONObject().put("configured",true)
                val raw=result.toString().toByteArray(Charsets.UTF_8)
                client.getOutputStream().write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${raw.size}\r\nConnection: close\r\n\r\n".toByteArray()+raw)
            }}catch(_:Exception){if(socket.isClosed)break}
        }
        try{
            rule.setContent{MaterialTheme{CameraPanel(MapGateway("http://127.0.0.1:${socket.localPort}","test","test-token"),false,permitted.value)}}
            rule.onNodeWithTag("camera-view").performClick()
            rule.waitUntil(15000){rule.onAllNodesWithTag("camera-frame").fetchSemanticsNodes().isNotEmpty()}
            failed.set(true)
            rule.waitUntil(15000){rule.onAllNodesWithText("카메라 연결 끊김").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithTag("camera-frame").assertDoesNotExist()
            failed.set(false)
            rule.waitUntil(15000){rule.onAllNodesWithTag("camera-frame").fetchSemanticsNodes().isNotEmpty()}
            rule.runOnIdle{permitted.value=false}
            rule.onNodeWithTag("camera-frame").assertDoesNotExist()
            rule.onNodeWithTag("camera-view").assertIsNotEnabled()
            val before=frames.get();Thread.sleep(2500)
            org.junit.Assert.assertTrue("Driving must stop camera polling",frames.get()<=before+1)
        }finally{socket.close();worker.join(2000)}
    }
}

package com.familycare.phone

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.familycare.ui.LocalAlbum
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LocalAlbumTest {
    @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
    @Test fun explicitSaveReloadTimeLocationMatchAndDelete(){
        val folder=File(compose.activity.cacheDir,"album-qa-"+UUID.randomUUID()).apply{mkdirs()}
        val context=object:ContextWrapper(compose.activity){override fun getFilesDir()=folder}
        val image=ByteArrayOutputStream().also{Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray()
        val encoded="data:image/jpeg;base64,"+Base64.encodeToString(image,Base64.NO_WRAP)
        val now=System.currentTimeMillis()
        val photo=JSONObject().put("id","album-test").put("title","앨범 저장 확인").put("captured",SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.KOREA).format(Date(now)))
            .put("latitude",37.48).put("longitude",127.03).put("image",encoded).put("thumbnail",encoded)
        val trip=JSONObject().put("startedAt",now-120000).put("endedAt",now+1000).put("points",JSONArray().put(JSONObject().put("lat",37.48).put("lon",127.03)))
        var epoch by mutableStateOf(0)
        try{
            compose.runOnUiThread{compose.activity.setContent{MaterialTheme{CompositionLocalProvider(LocalContext provides context){
                key(epoch){Column(Modifier.width(390.dp).height(820.dp).verticalScroll(rememberScrollState())){LocalAlbum(photo,listOf(trip))}}
            }}}}
            compose.onNodeWithText("지금 연 사진을 앨범에 저장").performScrollTo().performClick()
            compose.onNodeWithText("사진을 이 기기에 저장했어요").assertExists()
            assertEquals(1,File(folder,"family-album").listFiles()!!.size)
            compose.runOnUiThread{epoch++}
            compose.onNodeWithText("앨범 저장 확인").performScrollTo().performClick()
            compose.onNodeWithText("촬영 시간·위치로 연결된 여행:",substring=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("이 기기의 앨범에서 삭제").performScrollTo().performClick()
            compose.onAllNodesWithText("앨범 저장 확인").assertCountEquals(0)
            assertEquals(0,File(folder,"family-album").listFiles()!!.size)
        }finally{folder.deleteRecursively()}
    }
}

package com.familycare.navigation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import java.io.File

class SearchLiveTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String){
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun realNavigationSearchShowsButtonAndTmapResults(){
        val c=CareRuntime.get(compose.activity)
        try{
            compose.waitUntil(20000){c.parked}
            compose.runOnUiThread{c.navigation.cancel();c.share()}
            compose.waitUntil(20000){c.relay.connected}
            compose.onNodeWithTag("navigation-search").performClick()
            compose.onNodeWithTag("place-search-query").performClick().performTextInput("양재시민의숲")
            compose.onNodeWithTag("place-search-submit").assertIsDisplayed().assertIsEnabled()
            capture("search-input-0122.png")
            compose.onNodeWithTag("place-search-submit").performClick()
            compose.waitUntil(30000){compose.onAllNodesWithText("곳 · TMAP",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("곳 · TMAP",substring=true).performScrollTo().assertIsDisplayed()
            capture("search-results-0122.png")
        }finally{compose.runOnUiThread{c.stopSharing()}}
    }
}

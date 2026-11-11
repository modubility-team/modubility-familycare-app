package com.familycare.phone

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The SDK must not request credentials or map tiles before a phone joins the vehicle. */
class MapUiTest {
    @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
    @Test fun disconnectedPhoneRequiresVehicleConnectionForMap(){
        assumeTrue(BuildConfig.PRODUCT=="care")
        compose.activity.runOnUiThread{compose.activity.setContent{
            Box(Modifier.width(390.dp).height(780.dp).testTag("map-phone-frame")){PhoneScreen(compose.activity.model){_,_,_->}}
        }}
        compose.onNodeWithTag("phone-tab-요청").performClick()
        compose.onNodeWithTag("live-map").performScrollTo()
        compose.onNodeWithTag("map-load-status").assertTextContains("차량 연결",substring=true)
        compose.onAllNodesWithTag("map-ready").assertCountEquals(0)
        compose.onNodeWithTag("send-place").performScrollTo().assertIsNotEnabled()
    }
}

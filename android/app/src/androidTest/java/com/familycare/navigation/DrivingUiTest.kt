package com.familycare.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class DrivingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movementAndServiceLossHideDeparturePromptAndControls() {
        val care = CareRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.runOnUiThread { care.engine.ux = UxPolicy(true,false); care.publish() }
        compose.setContent { MaterialTheme { CareScreen(care) } }
        compose.onNodeWithTag("home-actions").assertExists()
        compose.onNodeWithTag("resize").assertExists()
        compose.runOnIdle { care.engine.ux = UxPolicy(true,true,80); care.publish() }
        compose.onNodeWithTag("home-actions").assertDoesNotExist()
        compose.onNodeWithTag("tab-요청").assertDoesNotExist()
        compose.onNodeWithTag("resize").assertDoesNotExist()
        compose.onNodeWithTag("vehicle-navigation").assertIsDisplayed()
        compose.runOnIdle { care.engine.ux = UxPolicy(); care.publish() }
        compose.onNodeWithTag("details").assertDoesNotExist()
        compose.runOnIdle { care.engine.ux = UxPolicy(true,false); care.publish() }
        compose.onNodeWithTag("home-actions").assertExists()
    }

    @Test fun narrowWindowKeepsItemsReachableWithoutClipping() {
        val care = CareRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.runOnUiThread { care.engine.ux = UxPolicy(true,false); care.publish() }
        compose.setContent { MaterialTheme {
            Box(Modifier.width(320.dp)) { CareScreen(care) }
        } }
        compose.onNodeWithTag("narrow").assertExists()
        compose.onNodeWithTag("home-requests").assertIsDisplayed()
        compose.onNodeWithTag("navigation-search").assertIsDisplayed()
        compose.onNodeWithTag("tab-동승자").performClick()
        compose.onNodeWithTag("passenger-NOVICE").performScrollTo().assertIsDisplayed()
    }

    @Test fun homeShortcutsOpenNavigationAndRequestsDirectly(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val care=CareRuntime.get(context)
        var opened=false
        compose.runOnUiThread{care.engine.ux=UxPolicy(true,false);care.publish()}
        compose.setContent{Box(Modifier.width(1200.dp).height(780.dp).testTag("vehicle-frame")){
            CareScreen(care,onOpenNavigation={opened=true})
        }}
        compose.onNodeWithTag("vehicle-frame").captureToImage().asAndroidBitmap().let{bitmap->
            File(context.filesDir,"vehicle-ui.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithTag("live-map").assertExists()
        compose.onNodeWithTag("navigation-search").performClick()
        compose.onNodeWithTag("open-system-navigation").performScrollTo().performClick()
        org.junit.Assert.assertTrue(opened)
        compose.runOnIdle{care.engine.ux=UxPolicy(true,true,80);care.publish()}
        compose.onNodeWithTag("vehicle-navigation").assertIsDisplayed()
        compose.runOnIdle{care.engine.ux=UxPolicy(true,false);care.publish()}
        compose.onNodeWithTag("home-requests").performClick()
        compose.onNodeWithText("받은 요청").assertIsDisplayed()
    }
}

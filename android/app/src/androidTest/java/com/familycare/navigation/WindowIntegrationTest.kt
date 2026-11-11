package com.familycare.navigation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Requires the Pleos emulator to be parked with UX restrictions cleared. */
class WindowIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun realCarConnectionSurvivesBothWindowSizesAndForegroundReturn() {
        waitFor("details")
        // The launcher can restore either orientation. First normalize to the wide layout.
        if (compose.onAllNodesWithTag("wide").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("resize").performClick()
            waitFor("wide")
        }
        capture("compliance-wide")
        compose.onNodeWithTag("resize").performClick()
        waitFor("narrow")
        compose.onNodeWithTag("navigation-search").assertIsDisplayed()
        compose.onNodeWithTag("home-requests").assertIsDisplayed()
        compose.onNodeWithTag("resize").assertIsDisplayed()
        capture("compliance-narrow")
        compose.onNodeWithTag("resize").performClick()
        waitFor("wide")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("details")
        compose.onNodeWithTag("resize").assertIsDisplayed()
    }
}

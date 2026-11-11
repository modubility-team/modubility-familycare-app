package com.familycare.phone
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
class PickupPageTest{
 @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
 @Test fun actualGpsAddressWaitMessageAndCancellation(){
  // The waiting-passenger pickup screen exists only in Family Care.
  org.junit.Assume.assumeTrue(BuildConfig.PRODUCT=="care")
  val m=compose.activity.model;val f=File(compose.activity.filesDir,"pickup-test-join.txt");val link=f.readText().trim();f.delete()
  compose.runOnUiThread{m.joinText=link;m.connect();compose.activity.setContent{Box(Modifier.width(390.dp).height(820.dp).testTag("pickup-frame")){PhoneScreen(m){_,_,_->}}}}
  compose.waitUntil(18000){m.connected&&!m.stale&&m.pickup.optBoolean("enabled")}
  compose.onNodeWithTag("pickup-current-location").performScrollTo().performClick()
  compose.waitUntil(45000){m.pickupDraft.has("draft")};assertTrue(m.pickupDraft.getString("address").isNotBlank())
  compose.onNodeWithTag("pickup-landmark").performScrollTo().performTextInput("시연 지점 건물 정문 앞")
  compose.onNodeWithTag("send-pickup").performScrollTo().performClick()
  compose.waitUntil(18000){m.pickup.optJSONArray("waiting")?.length()==1}
  assertTrue(m.requests.any{it.optString("kind")=="pickup"&&it.optString("title").contains("정문")})
  compose.onNodeWithText("대기 취소").performScrollTo()
  val bitmap=compose.onNodeWithTag("pickup-frame").captureToImage().asAndroidBitmap();File(compose.activity.filesDir,"pickup-page.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  compose.onNodeWithText("대기 취소").performClick();compose.waitUntil(15000){m.pickup.optJSONArray("waiting")?.length()==0}
 }
}

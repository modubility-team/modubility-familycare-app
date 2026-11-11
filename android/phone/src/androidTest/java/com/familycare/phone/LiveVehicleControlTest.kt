package com.familycare.phone

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Needs a live parked Pleos app and a private test-only owner link file. No fixture telemetry. */
class LiveVehicleControlTest{
    @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
    @Test fun phoneButtonChangesVehicleAndReceivesConfirmedResult(){
        check(BuildConfig.PRODUCT=="navigation")
        val m=compose.activity.model
        val link=File(compose.activity.filesDir,"control-test-join.txt").readText().trim()
        File(compose.activity.filesDir,"control-test-join.txt").delete()
        compose.runOnUiThread{
            m.joinText=link;m.connect()
            compose.activity.setContent{Box(Modifier.width(390.dp).height(820.dp).testTag("control-phone-frame")){PhoneScreen(m){_,_,_->}}}
        }
        compose.waitUntil(18000){m.isOwner&&!m.stale&&m.state.optBoolean("parked")&&m.state.optJSONArray("controls")!=null}
        fun ac():JSONObject?{val a=m.state.optJSONArray("controls")?:return null;return(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("id").startsWith("hvac.ac:")&&it.optBoolean("available")}}
        compose.waitUntil(10000){ac()!=null}
        val control=ac()!!;val original=control.getDouble("value")
        compose.onNodeWithTag("phone-tab-관리").performClick()
        try{
            compose.onNodeWithTag("control-"+control.getString("id")).performScrollTo().performClick()
            compose.onNodeWithText("실행").performClick()
            compose.waitUntil(20000){m.requests.any{it.optString("kind")=="control"&&it.optString("status")=="confirmed"}&&ac()?.optDouble("value")!=original}
            assertEquals(1.0-original,ac()!!.getDouble("value"),0.01)
            compose.waitForIdle()
            val bitmap=compose.onNodeWithTag("control-phone-frame").captureToImage().asAndroidBitmap()
            File(compose.activity.filesDir,"vehicle-controls-phone.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }finally{
            compose.waitUntil(6000){!m.busy}
            compose.runOnUiThread{m.vehicleControl(ac()?:control,original)}
            compose.waitUntil(20000){ac()?.optDouble("value")==original}
        }
    }
}

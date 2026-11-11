package com.familycare.phone

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Drives the actual permission screen against an isolated relay session. */
class PermissionPageTest {
    @get:Rule val compose=createAndroidComposeRule<PhoneActivity>()
    private fun api(path:String,body:JSONObject=JSONObject(),token:String=""):JSONObject{
        val c=URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try{
            c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer $token")
            c.outputStream.use{it.write(body.toString().toByteArray())}
            val code=c.responseCode
            val result=JSONObject((if(code==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()})
            check(code==200){"$path: $code $result"};return result
        }finally{c.disconnect()}
    }
    private fun hasTestTagStartingWith(prefix:String)=SemanticsMatcher("tag prefix $prefix"){it.config.getOrElse(SemanticsProperties.TestTag){""}.startsWith(prefix)}
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun capture(name:String){
        val bitmap=compose.onNodeWithTag("permission-frame").captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun firstConnectionShowsSelectionAndSendsOnlyChosenScope(){
        check(BuildConfig.PRODUCT=="care")
        val m=compose.activity.model
        val s=api("/api/sessions");val prefix="/api/sessions/"+s.getString("id");val car=s.getString("token")
        val link="http://127.0.0.1:8765/join#"+s.getJSONObject("links").getString("family").substringAfter('#')
        val zones=JSONArray("""[{"id":"ROW_1_LEFT","label":"운전석","min":18,"max":28,"target":23},{"id":"ROW_1_RIGHT","label":"조수석","min":18,"max":28,"target":23}]""")
        val controls=JSONArray("""[
          {"id":"hvac.ac:ROW_1_LEFT+ROW_1_RIGHT","label":"에어컨 A/C","available":true,"boolean":true,"min":0,"max":1,"step":1},
          {"id":"seat.heat:ROW_1_RIGHT","label":"조수석 열선","available":true,"boolean":false,"min":0,"max":3,"step":1},
          {"id":"window.position:ROW_1_RIGHT","label":"조수석 창문","available":true,"boolean":false,"min":0,"max":100,"step":1},
          {"id":"window.position:ROW_2_LEFT","label":"뒷좌석 왼쪽 창문","available":true,"boolean":false,"min":0,"max":100,"step":1}
        ]""")
        val state=JSONObject().put("climateSupported",true).put("climatePowerSupported",true).put("climatePower",true).put("climateTarget",23)
            .put("climateAreas",zones).put("controls",controls).put("parked",true).put("authorityActive",true).put("authorityEpoch","permission203").put("authorityMode","connection")
        val executor=Executors.newSingleThreadScheduledExecutor()
        try{
            api("$prefix/sync",state,car)
            executor.scheduleAtFixedRate({runCatching{api("$prefix/sync",state,car)}},2,2,TimeUnit.SECONDS)
            compose.runOnUiThread{
                m.disconnect();m.joinText=link;m.connect()
                compose.activity.setContent{Box(Modifier.width(390.dp).height(820.dp).testTag("permission-frame")){PhoneScreen(m){_,_,_->}}}
            }
            compose.waitUntil(18000){m.permissionPrompt>0&&!m.stale&&m.permissionCatalog.isNotEmpty()&&m.grant.optString("deviceId").isNotBlank()}
            compose.onNodeWithTag("grant-status").assertExists()
            assertFalse(m.canClimate)
            compose.onNodeWithTag("grant-scope-climate").assertDoesNotExist()
            click("permission-next")
            for(category in listOf("climate","seat","doors","route","care"))compose.onNodeWithTag("permission-category-$category").assertExists()
            compose.onNodeWithTag("grant-scope-climate").assertDoesNotExist()
            compose.onNodeWithTag("permission-review").assertIsNotEnabled()
            compose.onNodeWithTag("permission-category-climate").performScrollTo()
            capture("permission203-categories.png")
            // Selecting all is seat-filtered and still requires an explicit approval request.
            val allowed=m.permissionCatalog.filter{c->val seats=c.optJSONArray("seats");seats!=null&&(0 until seats.length()).any{seats.optString(it)=="ROW_1_RIGHT"}}
            click("permission-add-all")
            compose.onNodeWithTag("permission-selection-count").assertTextEquals("조수석 · ${allowed.size}개 선택")
            assertFalse(m.canClimate);assertFalse(m.requests.any{it.optString("kind")=="permission"})
            click("permission-review")
            compose.onNodeWithTag("permission-review-summary").assertTextEquals("동승자 · 조수석 · ${allowed.size}개")
            click("permission-edit-selection");click("permission-clear")
            click("permission-category-doors")
            click("permission-add-category-doors")
            compose.onNodeWithTag("permission-selection-count").assertTextEquals("조수석 · 1개 선택")
            click("permission-category-climate")
            compose.onNodeWithTag("grant-scope-control:window.position:ROW_1_RIGHT").assertDoesNotExist()
            click("grant-scope-climate")
            compose.onNodeWithTag("permission-selection-count").assertTextEquals("조수석 · 2개 선택")
            click("permission-range-climate")
            compose.onNodeWithText("허용 범위  18.0 ~ 28.0").assertExists()
            click("permission-range-climate")
            compose.onNodeWithText("허용 범위  18.0 ~ 28.0").assertDoesNotExist()
            compose.onNodeWithTag("permission-category-climate").performScrollTo()
            capture("permission203-climate.png")
            // Moving to another seat must not request controls from the previous seat.
            click("permission-edit-seat");click("seat-ROW_2_LEFT");click("permission-next")
            compose.onNodeWithTag("permission-selection-count").assertTextEquals("뒷좌석 왼쪽 · 0개 선택")
            click("permission-category-doors")
            compose.onNodeWithTag("grant-scope-control:window.position:ROW_1_RIGHT").assertDoesNotExist()
            click("grant-scope-control:window.position:ROW_2_LEFT")
            click("permission-review")
            compose.onNodeWithTag("permission-review-summary").assertTextEquals("동승자 · 뒷좌석 왼쪽 · 1개")
            click("permission-edit-selection")
            compose.onNodeWithTag("permission-selection-count").assertTextEquals("뒷좌석 왼쪽 · 1개 선택")
            click("permission-clear")
            compose.onNodeWithTag("permission-review").assertIsNotEnabled()
            click("permission-edit-seat");click("seat-ROW_1_RIGHT");click("permission-next")
            click("permission-category-climate");click("grant-scope-climate");click("permission-review")
            compose.onNodeWithTag("permission-review-summary").assertTextEquals("동승자 · 조수석 · 1개")
            compose.onNodeWithTag("permission-review-summary").performScrollTo()
            capture("permission203-review.png")
            click("request-permissions")
            compose.waitUntil(18000){m.requests.any{it.optString("kind")=="permission"&&it.optString("deviceId")==m.grant.optString("deviceId")}}
            val request=m.requests.last{it.optString("kind")=="permission"&&it.optString("deviceId")==m.grant.optString("deviceId")}
            assertEquals("pending",request.optString("status"))
            val options=request.getJSONObject("options");val scopes=options.getJSONArray("scopes")
            assertEquals(1,scopes.length());assertEquals("climate",scopes.getString(0));assertEquals("ROW_1_RIGHT",options.getString("seat"))
            val limits=options.getJSONObject("limits");assertEquals(1,limits.length());assertEquals(18.0,limits.getJSONObject("climate").getDouble("min"),0.01)
            assertEquals(28.0,limits.getJSONObject("climate").getDouble("max"),0.01);assertFalse(m.canClimate)
            api("$prefix/claim",JSONObject().put("id",request.getString("id")),car)
            api("$prefix/result",JSONObject().put("id",request.getString("id")).put("status","confirmed").put("message","승인 완료"),car)
            compose.waitUntil(12000){m.canClimate}
            assertFalse(m.hasScope("climatePower"));assertFalse(m.hasScope("control:window.position:ROW_2_LEFT"))
            // A permitted action has a specific, pinned result and never reports success early.
            compose.runOnUiThread{m.climate(22.0,"ROW_1_RIGHT")}
            compose.waitUntil(18000){m.requests.any{it.optString("kind")=="climate"}}
            val climate=m.requests.first{it.optString("kind")=="climate"}
            assertTrue(m.resultMessage.contains("조수석 22°C"));assertFalse(m.resultMessage.contains("반영 완료"))
            api("$prefix/claim",JSONObject().put("id",climate.getString("id")),car)
            api("$prefix/result",JSONObject().put("id",climate.getString("id")).put("status","confirmed").put("message","실제 설정값 확인"),car)
            compose.waitUntil(12000){m.resultMessage.contains("반영 완료")}
            compose.onNodeWithTag("phone-tab-홈").performClick()
            compose.onNodeWithText("장소 제안",useUnmergedTree=true).performScrollTo().performClick()
            compose.waitUntil(8000){runCatching{compose.onNodeWithTag("place-search-query").assertIsDisplayed();true}.getOrDefault(false)}
            compose.onNodeWithTag("result-message").assertIsDisplayed().assertTextContains("조수석 22°C · 반영 완료")
            compose.onNodeWithTag("phone-tab-홈").performClick()
            compose.onNodeWithText("가족 투표",useUnmergedTree=true).performScrollTo().performClick()
            compose.waitUntil(8000){runCatching{compose.onNodeWithText("함께 정하는 목적지 · 요청 결과").assertIsDisplayed();true}.getOrDefault(false)}
            compose.onNodeWithTag("result-message").assertIsDisplayed()
            compose.onNodeWithTag("phone-tab-홈").performClick()
            compose.onNodeWithText("쉬어 갈 곳 찾기",useUnmergedTree=true).performScrollTo().performClick()
            compose.waitUntil(8000){runCatching{compose.onNodeWithTag("place-search-query").assertIsDisplayed();true}.getOrDefault(false)}
            compose.runOnUiThread{m.climate(23.0,"ROW_1_RIGHT")}
            compose.waitUntil(18000){m.requests.count{it.optString("kind")=="climate"}==2}
            val refused=m.requests.first{it.optString("kind")=="climate"&&it.optString("id")!=climate.getString("id")}
            api("$prefix/claim",JSONObject().put("id",refused.getString("id")),car)
            api("$prefix/result",JSONObject().put("id",refused.getString("id")).put("status","rejected").put("message","차량에서 거절"),car)
            compose.waitUntil(12000){m.resultMessage.contains("적용 안 됨")};assertFalse(m.resultMessage.contains("반영 완료"))
            capture("feature204-shortcuts.png")
            // Search the production memory picker with the real configured provider.
            var selected:com.familycare.ui.FoundPlace?=null
            compose.runOnUiThread{compose.activity.setContent{
                androidx.compose.material3.MaterialTheme{Column(Modifier.width(390.dp).height(820.dp).testTag("permission-frame").verticalScroll(androidx.compose.foundation.rememberScrollState())){
                    com.familycare.ui.MemoryLocationPicker(null,null,"",m.mapGateway()){selected=it}
                }}
            }}
            compose.onNodeWithTag("place-search-query").performTextInput("양재시민의숲")
            compose.onNodeWithTag("place-search-submit").performClick()
            compose.waitUntil(60000){compose.onAllNodes(hasTestTagStartingWith("place-result-")).fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodes(hasTestTagStartingWith("place-result-"))[0].performScrollTo().performClick()
            assertNotNull(selected);assertTrue(com.familycare.ui.MapPlaces.valid(selected!!.latitude,selected!!.longitude))
            capture("feature204-memory-search.png")
            File(compose.activity.filesDir,"permission203-proof.json").writeText(JSONObject().put("specificConfirmedAndRejectedFeedback",true).put("pinnedFeedback",true).put("homeSectionShortcuts",true).put("realTmapMemorySearch",true).put("selectAllSeatFiltered",true).put("categorySelectAll",true).put("groupedSelection",true).put("singleExpandedCategory",true).put("rangeDisclosure",true).put("seatSwitchClears",true).put("reviewPreservesSelection",true).put("clearAll",true).put("onlySelectedScopeSubmitted",true).put("approvalStillRequired",true).toString())
        }finally{
            executor.shutdownNow();compose.runOnUiThread{m.disconnect()};api("$prefix/end",token=car)
        }
    }
}

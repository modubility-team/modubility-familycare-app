package com.familycare.navigation

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in browser walkthrough; sends no data beyond the local demo relay. */
class BrowserDemoTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun liveCompanionWalkthrough(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("browserDemo")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val care=CareRuntime.get(compose.activity)
        compose.waitUntil(15000){care.engine.ux.showDetails}
        val old=care.engine.profile
        fun main(work:()->Unit)=compose.runOnUiThread(work)
        fun tab(name:String){compose.onNodeWithTag("tab-$name").performClick()}
        fun capture(name:String){
            compose.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(ctx.filesDir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        }
        try{
            main{care.engine.profile=FamilyProfile(setOf(Passenger.CHILD,Passenger.PET),0);care.publish()}
            tab("동승자");capture("demo-profile");Thread.sleep(3500)
            tab("이동");main{care.startTrip();care.setTimer(1);care.startWatch();care.share()}
            compose.waitUntil(15000){care.relay.links.isNotEmpty()}
            File(ctx.filesDir,"demo-join.json").writeText(JSONObject(care.relay.links).toString())
            capture("demo-trip");Thread.sleep(3500)
            tab("요청")
            compose.waitUntil(70000){care.relay.requests.any{it.status=="pending"}}
            val request=care.relay.requests.first{it.status=="pending"}
            compose.onNodeWithText(request.title).performScrollTo();capture("demo-request");Thread.sleep(3000)
            main{care.decide(request,true)}
            compose.waitUntil(15000){care.relay.requests.any{it.id==request.id&&it.status=="confirmed"}}
            capture("demo-confirmed");Thread.sleep(3500)
            tab("차량")
            compose.waitUntil(70000){care.engine.alerts(care.now()).any{it.code=="timer"}}
            compose.waitUntil(5000){compose.onAllNodesWithText("확인 타이머가 끝났어요").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("확인 타이머가 끝났어요").performScrollTo();capture("demo-timer")
            Thread.sleep(20000)
            main{care.stopWatch();care.endTrip()};tab("이동");capture("demo-rear-check");Thread.sleep(3500)
            main{care.confirmRear()};tab("기록");capture("demo-records");Thread.sleep(3500)
        }finally{
            main{if(care.engine.ux.showDetails){care.stopWatch();if(care.engine.running)care.endTrip();care.confirmRear();care.stopSharing();care.engine.profile=old;care.publish()}}
            compose.waitUntil(15000){!care.relay.sharing}
            File(ctx.filesDir,"demo-join.json").delete()
        }
    }
}

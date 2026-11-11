package com.familycare.navigation

import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tests playback progress and cleanup, not audibility at the physical laptop speakers. */
class DriverAudioTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun notificationCuePlaysAndReleasesFocus(){
        lateinit var audio:DriverAudio
        compose.runOnUiThread{audio=DriverAudio(compose.activity)}
        try{
            compose.runOnUiThread{assertTrue(audio.play("시험",forceChime=true))}
            compose.runOnUiThread{audio.stopNavigation()}
            assertTrue("Navigation stop must not cancel family notifications",audio.busy)
            compose.waitUntil(3000){audio.playbackFrames>0}
            assertEquals("chime",audio.lastPath)
            compose.waitUntil(5000){!audio.busy}
            assertEquals("알림음 재생 완료",audio.lastResult)
            compose.runOnUiThread{assertTrue(audio.play("시험",forceChime=true));audio.onFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)}
            assertFalse(audio.busy);assertEquals(0,audio.playbackFrames)
        }finally{compose.runOnUiThread{audio.close()}}
    }
    @Test fun pleosKoreanSpeechCompletes(){
        lateinit var audio:DriverAudio
        compose.runOnUiThread{audio=DriverAudio(compose.activity)}
        try{
            compose.waitUntil(12000){!audio.outputStatus.startsWith("음성 엔진 확인 중")}
            assertTrue(audio.outputStatus,audio.outputStatus.startsWith("Pleos 한국어 음성 안내"))
            compose.runOnUiThread{assertTrue(audio.play("약 백 미터 앞에서 우회전하세요.",navigationGuidance=true))}
            compose.waitUntil(15000){!audio.busy}
            assertEquals("pleos-speech",audio.lastPath)
            assertEquals("한국어 음성 안내 재생 완료",audio.lastResult)
            java.io.File(compose.activity.filesDir,"driver-audio-evidence.txt").writeText(audio.outputStatus+"\n"+audio.lastPath+"\n"+audio.lastResult)
        }finally{compose.runOnUiThread{audio.close()}}
    }
    @Test fun speechStopsOnFocusLossAndFailureFallsBack(){
        lateinit var audio:DriverAudio
        compose.runOnUiThread{audio=DriverAudio(compose.activity)}
        try{
            compose.waitUntil(12000){audio.outputStatus.startsWith("Pleos 한국어 음성 안내")}
            compose.runOnUiThread{assertTrue(audio.play("동승자가 휴식을 요청했어요. 다음 안전한 장소에서 쉬어 가세요."))}
            compose.waitUntil(8000){audio.lastResult=="한국어 음성 안내 재생 중"}
            compose.runOnUiThread{audio.onFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)}
            assertFalse(audio.busy)
            Thread.sleep(500) // Let the SDK's stop callback arrive; it must not start fallback audio.
            assertEquals("다른 차량 소리가 우선되어 안내를 중단했어요",audio.lastResult)
            assertFalse(audio.busy)
            compose.runOnUiThread{
                assertTrue(audio.play("다음 안내"))
                audio.speechFailed("시험용 음성 실패")
            }
            assertEquals("chime",audio.lastPath)
            compose.waitUntil(5000){!audio.busy}
            assertEquals("알림음 재생 완료",audio.lastResult)
        }finally{compose.runOnUiThread{audio.close()}}
    }
}

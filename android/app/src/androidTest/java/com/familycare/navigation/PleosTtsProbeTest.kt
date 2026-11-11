package com.familycare.navigation

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Local-only engine, fixed sample sentence, no credentials or microphone. */
class PleosTtsProbeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun koreanOnDeviceSynthesis(){
        lateinit var speech:TextToSpeech
        val initialized=AtomicBoolean(false);val started=AtomicBoolean(false);val done=AtomicBoolean(false)
        val failure=AtomicReference("")
        val audio=compose.activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener{change->if(change<0)runCatching{speech.stop()}}.build()
        val listener=object:EventListener{
            override fun onReady(){}
            override fun onStart(){started.set(true)}
            override fun onUpdatedRms(rms:Double){}
            override fun onDone(){done.set(true)}
            override fun onStop(){}
            override fun onError(errorType:ErrorType){failure.set(errorType.toString())}
        }
        compose.runOnUiThread{
            speech=TextToSpeech(compose.activity.applicationContext,Mode.ON_DEVICE)
            speech.initialize(object:OnInitializeListener{
                override fun onSuccess(){initialized.set(true)}
                override fun onFailure(errorType:ErrorType){failure.set(errorType.toString())}
            })
        }
        try{
            compose.waitUntil(15000){initialized.get()||failure.get().isNotEmpty()}
            assertEquals("Initialization", "",failure.get())
            compose.runOnUiThread{
                speech.addEventListener(listener);speech.setLocale(Locale.KOREA)
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(focus))
                speech.speak("동승자가 휴식을 요청했어요. 다음 안전한 장소에서 쉬어 가세요.")
            }
            compose.waitUntil(20000){done.get()||failure.get().isNotEmpty()}
            assertEquals("Synthesis", "",failure.get());assertTrue(started.get());assertTrue(done.get())
        }finally{
            java.io.File(compose.activity.filesDir,"pleos-tts-probe.txt").writeText("mode=ON_DEVICE\nlocale=ko-KR\ninitialized=${initialized.get()}\nstarted=${started.get()}\ndone=${done.get()}\nerror=${failure.get()}")
            compose.runOnUiThread{speech.removeEventListener(listener);speech.stop();speech.release();audio.abandonAudioFocusRequest(focus)}
        }
    }
}

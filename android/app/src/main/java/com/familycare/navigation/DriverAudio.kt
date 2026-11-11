package com.familycare.navigation

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** On-device Pleos speech with an original cue fallback. Never overrides volume or focus. */
class DriverAudio(context:Context,private val changed:()->Unit={}) {
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(AudioManager::class.java)
    private var attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var focus:AudioFocusRequest?=null
    private var track:AudioTrack?=null
    private var tts:TextToSpeech?=null
    private var listener:EventListener?=null
    private var voiceReady=false
    private var closed=false
    private var serial=0
    private var navigationSpeech=false
    private var watchdog:Runnable?=null
    private val initializationTimeout=Runnable{
        if(!closed&&!voiceReady){outputStatus="Pleos 음성 초기화 지연 · 짧은 알림음 사용";changed()}
    }
    var busy=false;private set
    var outputStatus="음성 엔진 확인 중 · 지원하지 않으면 알림음 사용";private set
    var lastResult="아직 재생한 안내가 없어요";private set
    internal val playbackFrames get()=track?.playbackHeadPosition?:0
    internal var lastPath="";private set
    init{
        main.postDelayed(initializationTimeout,8000)
        try{
            tts=TextToSpeech(context.applicationContext,Mode.ON_DEVICE).also{engine->
                engine.initialize(object:OnInitializeListener{
                    override fun onSuccess(){main.post{
                        if(!closed){
                            main.removeCallbacks(initializationTimeout)
                            voiceReady=runCatching{engine.setLocale(Locale.KOREA)}.isSuccess
                            outputStatus=if(voiceReady)"Pleos 한국어 음성 안내" else "Pleos 한국어 설정 실패 · 짧은 알림음 사용"
                            changed()
                        }
                    }}
                    override fun onFailure(errorType:ErrorType){main.post{
                        if(!closed){main.removeCallbacks(initializationTimeout);voiceReady=false;outputStatus="Pleos 음성 초기화 실패 ($errorType) · 알림음 사용";changed()}
                    }}
                })
            }
        }catch(_:Exception){main.removeCallbacks(initializationTimeout);outputStatus="Pleos 음성 서비스 연결 실패 · 짧은 알림음 사용"}
    }
    fun play(text:String,forceChime:Boolean=false,navigationGuidance:Boolean=false):Boolean{
        if(closed||busy)return false
        navigationSpeech=navigationGuidance
        attributes=AudioAttributes.Builder().setUsage(if(navigationGuidance)AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({change->onFocusChanged(change)},main).build()
        val granted=runCatching{manager.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED}.getOrDefault(false)
        if(!granted){lastResult="다른 차량 소리가 우선되어 안내를 보류했어요";changed();return false}
        focus=request;busy=true;serial++
        return try{
            if(voiceReady&&!forceChime)speak(text) else chime()
            true
        }catch(_:Exception){
            if(lastPath=="pleos-speech"){speechFailed("Pleos 음성 요청 실패");true}
            else{finish("소리를 재생하지 못했어요. 정차 후 오디오 설정을 확인하세요");false}
        }
    }
    private fun speak(text:String){
        val generation=serial
        val engine=checkNotNull(tts)
        lastPath="pleos-speech"
        val events=object:EventListener{
            private fun current(action:()->Unit){main.post{if(!closed&&busy&&serial==generation&&listener===this)action()}}
            override fun onReady(){}
            override fun onStart(){current{lastResult="한국어 음성 안내 재생 중";changed()}}
            override fun onUpdatedRms(rms:Double){}
            override fun onDone(){current{finish("한국어 음성 안내 재생 완료")}}
            override fun onStop(){current{speechFailed("Pleos 음성 재생이 중단됨")}}
            override fun onError(errorType:ErrorType){current{speechFailed("Pleos 음성 오류 ($errorType)")}}
        }
        listener=events;engine.addEventListener(events)
        timeout(12000){speechFailed("Pleos 음성 응답 지연")}
        lastResult="한국어 음성 안내 준비 중";changed()
        // Actual 200 ms PCM pre-roll before asking the separate Pleos speech service to play.
        // Continuity across the two audio tracks still needs verification on a physical vehicle.
        pcm(ShortArray(4800)){
            if(busy&&serial==generation&&listener===events){
                runCatching{engine.speak(text)}.onFailure{speechFailed("Pleos 음성 요청 실패")}
            }
        }
    }
    internal fun speechFailed(reason:String){
        if(!busy||lastPath!="pleos-speech")return
        voiceReady=false;outputStatus="$reason · 짧은 알림음 사용"
        runCatching{chime()}.onFailure{finish("알림음을 재생하지 못했어요")}
    }
    private fun stopSpeech(){
        val previous=listener;listener=null
        if(previous!=null)runCatching{tts?.removeEventListener(previous)}
        runCatching{tts?.stop()}
    }
    private fun timeout(ms:Long,action:()->Unit){
        watchdog?.let{main.removeCallbacks(it)}
        watchdog=Runnable{if(busy)action()}.also{main.postDelayed(it,ms)}
    }
    private fun pcm(samples:ShortArray,onDone:()->Unit){
        runCatching{track?.stop()};runCatching{track?.release()};track=null
        val player=AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*2).build()
        track=player
        check(player.write(samples,0,samples.size)==samples.size)
        player.setNotificationMarkerPosition(samples.size)
        player.setPlaybackPositionUpdateListener(object:AudioTrack.OnPlaybackPositionUpdateListener{
            override fun onMarkerReached(t:AudioTrack?){
                if(track===t&&busy){track=null;runCatching{player.stop()};runCatching{player.release()};onDone()}
            }
            override fun onPeriodicNotification(t:AudioTrack?){}
        },main)
        player.play()
    }
    private fun chime(){
        stopSpeech();lastPath="chime"
        timeout(3000){finish("알림음 재생 시간이 지나 종료했어요")}
        // Original two-note cue, including 200 ms PCM silence and short fades.
        val rate=24000;val samples=ShortArray(rate*7/10)
        for(i in samples.indices){
            val t=i.toDouble()/rate
            val start=when{t in .2..<.36->.2;t in .44..<.60->.44;else->continue}
            val x=t-start;val fade=(minOf(x,.16-x)/.015).coerceIn(0.0,1.0)
            samples[i]=(sin(2*PI*(if(start==.2)660 else 880)*x)*6500*fade).toInt().toShort()
        }
        pcm(samples){finish("알림음 재생 완료")}
        lastResult="알림음 재생 중";changed()
    }
    internal fun onFocusChanged(change:Int){
        when(change){
            AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->
                if(busy)finish("다른 차량 소리가 우선되어 안내를 중단했어요")
        }
    }
    fun stop(){if(busy)finish("안내를 중단했어요")}
    fun stopNavigation(){if(navigationSpeech)stop()}
    private fun finish(result:String){
        busy=false;serial++
        watchdog?.let{main.removeCallbacks(it)};watchdog=null
        stopSpeech();runCatching{track?.stop()};runCatching{track?.release()};track=null
        focus?.let{runCatching{manager.abandonAudioFocusRequest(it)}};focus=null
        lastResult=result;changed()
    }
    fun close(){closed=true;main.removeCallbacks(initializationTimeout);stop();stopSpeech();runCatching{tts?.release()};tts=null}
}

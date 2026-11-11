package com.familycare.navigation
import android.content.Context
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.familycare.ui.MapGateway
class PhoneMusicReceiver(context:Context,private val gateway:()->MapGateway,private val allowed:()->Boolean,private val changed:()->Unit){
 private val manager=context.getSystemService(AudioManager::class.java);private val main=Handler(Looper.getMainLooper());private val worker=Executors.newSingleThreadScheduledExecutor()
 private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).apply{if(Build.VERSION.SDK_INT>=29)setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)}.build()
 @Volatile private var focusState=0;@Volatile private var closed=false
 private var focus:AudioFocusRequest?=null;private var track:AudioTrack?=null;private var stream="";private var after=-1L;private var pollAt=0L
 var status="휴대폰에서 음악 공유를 시작하세요";private set
 var playedFrames=0L;private set
 var volume=.65f;private set
 private val media=MediaSession(context,"FamilyCarePhoneMusic").apply{
  setCallback(object:MediaSession.Callback(){override fun onPlay(){command("resume")};override fun onPause(){command("pause")};override fun onStop(){command("stop")}})
  setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"휴대폰 음악").putString(MediaMetadata.METADATA_KEY_ARTIST,"WithME · 같은 Wi-Fi").build())
 }
 init{worker.scheduleWithFixedDelay({step()},0,70,TimeUnit.MILLISECONDS)}
 fun command(value:String){if(!closed)worker.execute{runCatching{gateway().call("music-command",JSONObject().put("command",value))};releaseTrack();pollAt=0}}
 fun volume(value:Float){volume=value.coerceIn(0f,1f)}
 private fun show(text:String,playing:Boolean){if(status==text)return;status=text;main.post{media.isActive=playing;media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP).setState(if(playing)PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,if(playing)1f else 0f).build());changed()}}
 private fun releaseTrack(){track?.let{playedFrames+=it.playbackHeadPosition.toLong() and 0xffffffffL;runCatching{it.pause();it.flush();it.release()}};track=null;focus?.let{manager.abandonAudioFocusRequest(it)};focus=null;focusState=0}
 private fun prepare():Boolean{
  if(focus==null){val f=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setWillPauseWhenDucked(false).setOnAudioFocusChangeListener({change->focusState=when(change){AudioManager.AUDIOFOCUS_GAIN->1;AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->2;else->-1}},main).build();focus=f;focusState=if(manager.requestAudioFocus(f)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)1 else -1}
  if(focusState<0){track?.pause();track?.flush();return false}
  if(track==null){val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();track=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format).setBufferSizeInBytes(maxOf(38400,AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT))).setTransferMode(AudioTrack.MODE_STREAM).build();check(track!!.state==AudioTrack.STATE_INITIALIZED)}
  track!!.setVolume(volume*(if(focusState==2).2f else 1f));if(track!!.playState!=AudioTrack.PLAYSTATE_PLAYING)track!!.play();return true
 }
 private fun step(){
  if(closed)return
  if(!allowed()){releaseTrack();stream="";after=-1;show("휴대폰 음악 연결 대기",false);return}
  if(SystemClock.elapsedRealtime()<pollAt)return
  try{
   val data=gateway().call("music-pull",JSONObject().put("stream",stream).put("after",after));val next=data.optString("stream")
   if(next!=stream){releaseTrack();stream=next;after=-1}
   if(!data.optBoolean("active")||data.optBoolean("paused")){releaseTrack();show(if(data.optBoolean("paused"))"차량 음악 일시정지" else "휴대폰에서 음악 공유를 시작하세요",false);pollAt=SystemClock.elapsedRealtime()+500;return}
   val frames=data.optJSONArray("frames")
   if(frames!=null)(0 until frames.length()).forEach{i->val f=frames.getJSONObject(i);after=f.getLong("seq");if(prepare()){val bytes=Base64.decode(f.getString("pcm"),Base64.NO_WRAP);if(bytes.size<=38400&&bytes.size%4==0){val count=track!!.write(bytes,0,bytes.size,AudioTrack.WRITE_BLOCKING);check(count>=0)}}}
   show(if(focusState<0)"다른 차량 안내·오디오가 재생 중이에요" else if(data.optBoolean("signal"))"휴대폰 음악 재생 중" else "연결됨 · 음악 신호 대기",focusState>0)
  }catch(_:Exception){releaseTrack();show("음악 연결이 끊겼어요 · 같은 Wi-Fi를 확인하세요",false);pollAt=SystemClock.elapsedRealtime()+1500}
 }
 fun close(){closed=true;worker.execute{releaseTrack();main.post{media.release()}};worker.shutdown()}
}

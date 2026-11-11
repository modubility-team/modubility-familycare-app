package com.familycare.phone
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.*
import android.os.*
import android.util.Base64
import androidx.compose.runtime.*
import com.familycare.ui.MapGateway
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
object MusicSharing{
 var active by mutableStateOf(false);var message by mutableStateOf("같은 Wi-Fi에서 차량으로 음악을 보낼 수 있어요")
}
/** Captures MEDIA playback only, never the microphone or screen pixels. Consent is obtained by Android UI. */
class PhoneMusicService:Service(){
 private val main=Handler(Looper.getMainLooper());@Volatile private var running=false;@Volatile private var record:AudioRecord?=null
 private var projection:MediaProjection?=null;private var player:MediaPlayer?=null;private var thread:Thread?=null
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
  if(Build.VERSION.SDK_INT<29||running||intent==null){if(!running)stopSelf();return START_NOT_STICKY}
  val link=runCatching{JoinLink.parse(intent.getStringExtra("link").orEmpty(),BuildConfig.DEBUG)}.getOrNull()
  @Suppress("DEPRECATION") val consent=intent.getParcelableExtra<Intent>("consent")
  if(link==null||consent==null||intent.getIntExtra("result",0)!=Activity.RESULT_OK){stopSelf();return START_NOT_STICKY}
  val nm=getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("phone-music","차량 음악 공유",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,211,Intent(this,PhoneMusicService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val n=Notification.Builder(this,"phone-music").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("WithME · 차량으로 음악 공유 중").setContentText("재생 소리만 전송 · 마이크·화면 영상은 보내지 않아요").setOngoing(true).addAction(Notification.Action.Builder(null,"공유 종료",stop).build()).build()
  startForeground(210,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
  try{
   val mp=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,consent);projection=mp
   mp.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},main)
   val capture=AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
   // Prevent a feedback loop if the vehicle APK is also installed on this test device.
   runCatching{packageManager.getApplicationInfo("com.familycare.navigation",0).uid}.getOrNull()?.let{capture.excludeUid(it)}
   val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
   val minimum=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT)
   val recorder=AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture.build()).setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum,115200)).build()
   check(recorder.state==AudioRecord.STATE_INITIALIZED){"휴대폰에서 음악 공유 장치를 열지 못했어요"};record=recorder
   running=true;MusicSharing.active=true;MusicSharing.message="차량 연결 중"
   val gateway=MapGateway(link.origin,link.session,link.token)
   thread=Thread{
    var stream=""
    try{
     val started=gateway.call("music-start",JSONObject());stream=started.getString("stream");recorder.startRecording()
     main.post{MusicSharing.message="연결됐어요. 음악 앱에서 음악을 재생해 주세요";intent.getStringExtra("file")?.let{playFile(it)}}
     val samples=ShortArray(19200);var seq=0;var silent=0
     while(running){
      val count=recorder.read(samples,0,samples.size,AudioRecord.READ_BLOCKING);if(count<=0){if(running)error("음악을 읽지 못했어요");break};val aligned=count-count%2
      val bytes=ByteBuffer.allocate(aligned*2).order(ByteOrder.LITTLE_ENDIAN);var peak=0
      for(i in 0 until aligned){bytes.putShort(samples[i]);peak=maxOf(peak,kotlin.math.abs(samples[i].toInt()))}
      val response=gateway.call("music-frame",JSONObject().put("stream",stream).put("seq",seq++).put("pcm",Base64.encodeToString(bytes.array(),Base64.NO_WRAP)).put("peak",peak))
      silent=if(peak<20)silent+1 else 0
      val message=when{response.optBoolean("paused")->"차량 재생 일시정지 · 공유 종료는 아래 버튼";silent>=30->"소리가 수신되지 않아요. 음악을 재생하거나 공유를 허용하는 다른 앱·음악 파일을 사용하세요";peak>=20->"음악을 차량으로 보내는 중";else->"연결됨 · 음악 재생 대기"}
      main.post{if(MusicSharing.active)MusicSharing.message=message}
     }
    }catch(error:Exception){main.post{MusicSharing.message=error.message?.take(150)?:"음악 연결이 끊겼어요"}}
    finally{running=false;runCatching{recorder.stop()};recorder.release();record=null;if(stream.isNotEmpty())runCatching{gateway.call("music-command",JSONObject().put("command","stop").put("stream",stream))};main.post{stopSelf()}}
   }.also{it.name="FamilyCarePlaybackShare";it.start()}
  }catch(error:Exception){MusicSharing.message=error.message?.take(150)?:"음악 공유 권한을 다시 확인하세요";stopSelf()}
  return START_NOT_STICKY
 }
 private fun playFile(uri:String){
  try{player=MediaPlayer().apply{
   setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).apply{if(Build.VERSION.SDK_INT>=29)setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)}.build())
   setDataSource(this@PhoneMusicService,android.net.Uri.parse(uri));setOnPreparedListener{it.start()};setOnCompletionListener{stopSelf()};setOnErrorListener{_,_,_->MusicSharing.message="이 음악 파일을 재생하지 못했어요";stopSelf();true};prepareAsync()
  }}catch(_:Exception){MusicSharing.message="선택한 음악 파일을 읽지 못했어요";stopSelf()}
 }
 override fun onDestroy(){
  running=false;MusicSharing.active=false;runCatching{record?.stop()};runCatching{player?.release()};player=null;runCatching{projection?.stop()};projection=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
 }
}

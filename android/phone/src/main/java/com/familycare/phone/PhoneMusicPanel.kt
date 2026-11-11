package com.familycare.phone
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
@Composable fun PhoneMusicPanel(m:PhoneModel){
 val context=LocalContext.current;var file by remember{mutableStateOf<String?>(null)}
 val capture=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->
  val link=m.musicLink()
  if(r.resultCode==Activity.RESULT_OK&&r.data!=null&&link!=null){
   runCatching{context.startForegroundService(Intent(context,PhoneMusicService::class.java).putExtra("consent",r.data).putExtra("result",r.resultCode).putExtra("link",link).apply{file?.let{putExtra("file",it)}})}.onFailure{m.explain("음악 공유를 시작하지 못했어요. 앱을 연 상태에서 다시 시도하세요")}
  }else m.explain("음악 공유를 허용하지 않아 시작하지 않았어요")
 }
 fun consent(){capture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){r->if(r[Manifest.permission.RECORD_AUDIO]==true)consent()else m.explain("안드로이드 재생 소리 공유 권한이 필요해요. 마이크 소리는 수집하지 않습니다")}
 val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->file=uri?.toString()}
 Text("휴대폰 음악을 차량으로",style=MaterialTheme.typography.headlineMedium)
 Text("USB 없이 같은 Wi-Fi로 재생 소리를 보냅니다. 먼저 소유자 QR로 내 차 앱을 연결하세요.")
 Text(MusicSharing.message)
 if(Build.VERSION.SDK_INT<29){Text("이 기능은 Android 10 이상이 필요해요");return}
 if(!m.isOwner){Text("차량에서 ‘소유자’ QR로 연결하면 사용할 수 있어요");return}
 Text("Android 공유 창에서 허용해야 시작됩니다. 다른 음악 앱을 공유할 때 선택지가 나오면 ‘전체 화면’을 선택하세요. 시스템이 화면 공유라고 표시하더라도 이 앱은 음악 재생 소리만 읽으며 마이크·통화·화면 영상은 전송하지 않습니다. 공유를 차단한 음악 앱은 재생 소리를 보낼 수 없어요.")
 if(!MusicSharing.active){
  OutlinedButton(onClick={choose.launch(arrayOf("audio/*"))},enabled=m.canRequest){Text(if(file==null)"휴대폰 음악 파일 선택 (선택 사항)" else "음악 파일 선택됨 · 다른 파일 선택")}
  if(file!=null)TextButton(onClick={file=null}){Text("파일 대신 다른 음악 앱의 소리 공유")}
  Button(onClick={if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)consent()else permission.launch(if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)else arrayOf(Manifest.permission.RECORD_AUDIO))},enabled=m.canRequest,modifier=Modifier.testTag("start-phone-music")){Text("음악 공유 시작")}
 }else{
  Row{Button(onClick={m.musicCommand(if(m.music.optBoolean("paused"))"resume" else "pause")},enabled=m.canRequest){Text(if(m.music.optBoolean("paused"))"차량에서 다시 재생" else "차량 재생 일시정지")};TextButton(onClick={context.stopService(Intent(context,PhoneMusicService::class.java));m.musicCommand("stop")}){Text("음악 공유 종료")}}
 }
 Text("공유를 시작한 뒤 음악 앱에서 곡을 재생하세요. 차량의 길안내가 나오면 음악을 잠시 줄이고 다시 이어 갑니다. 연결이 끊기면 차량 재생도 멈춥니다.",style=MaterialTheme.typography.bodySmall)
}

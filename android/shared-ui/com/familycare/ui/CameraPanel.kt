package com.familycare.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Explicit viewing only. Leaving the screen/background/parked state removes the image. */
@Composable fun CameraPanel(gateway:MapGateway?,canConfigure:Boolean,allowed:Boolean=true){
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    var viewing by remember(gateway){mutableStateOf(false)}
    var editing by remember(gateway){mutableStateOf(false)}
    var configured by remember(gateway){mutableStateOf(false)}
    var busy by remember(gateway){mutableStateOf(false)}
    var url by remember(gateway){mutableStateOf("")}
    var username by remember(gateway){mutableStateOf("")}
    var password by remember(gateway){mutableStateOf("")}
    var message by remember(gateway){mutableStateOf("소유자가 카메라를 연결하면 화면을 볼 수 있어요.")}
    var frame by remember(gateway){mutableStateOf<ImageBitmap?>(null)}
    var received by remember(gateway){mutableStateOf<Long?>(null)}
    var checked by remember(gateway){mutableStateOf<Long?>(null)}
    val scope=rememberCoroutineScope()
    suspend fun call(action:String,body:JSONObject=JSONObject())=withContext(Dispatchers.IO){checkNotNull(gateway){"차량 연결을 확인하세요"}.call(action,body)}
    fun action(block:suspend ()->Unit){scope.launch{busy=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"카메라 연결을 확인하세요"}finally{busy=false}}}
    LaunchedEffect(gateway,resumed,allowed){
        if(!resumed||!allowed){viewing=false;frame=null;received=null;return@LaunchedEffect}
        if(gateway!=null)try{val s=call("camera-status");configured=s.optBoolean("configured");if(canConfigure)url=s.optString("url");checked=if(s.isNull("checkedAt"))null else s.optLong("checkedAt")}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"연결 확인 필요"}
    }
    LaunchedEffect(gateway,viewing,resumed,allowed){
        frame=null;received=null
        if(!viewing||!resumed||!allowed||gateway==null)return@LaunchedEffect
        while(isActive){
            try{
                val response=call("camera-frame")
                val image=withContext(Dispatchers.IO){
                    val bytes=Base64.decode(response.getString("image"),Base64.NO_WRAP)
                    check(bytes.size<=1_000_000){"화면 크기를 줄여 주세요"}
                    val size=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,size)
                    check(size.outWidth in 1..4096&&size.outHeight in 1..4096&&size.outWidth.toLong()*size.outHeight<=8_000_000){"카메라 해상도를 720p 이하로 낮춰 주세요"}
                    val options=BitmapFactory.Options().apply{inSampleSize=if(size.outWidth>1600||size.outHeight>1600)2 else 1}
                    checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)){"화면을 읽지 못했어요"}.asImageBitmap()
                }
                frame=image;received=response.getLong("capturedAt");configured=true;message="화면 수신 중 · 1초 간격"
            }catch(e:CancellationException){throw e}catch(e:Exception){frame=null;received=null;message=e.message?:"카메라 연결 끊김"}
            delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth().testTag("pet-camera"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("반려동물 카메라",style=MaterialTheme.typography.titleLarge)
        Text(if(!allowed)"P단 정차 후 카메라 화면을 확인하세요." else if(gateway==null)"먼저 소유자 또는 가족 연결을 시작하세요." else message)
        if(allowed&&resumed&&viewing){
            frame?.let{Image(it,"연결된 반려동물 카메라",Modifier.fillMaxWidth().heightIn(min=160.dp,max=320.dp).testTag("camera-frame"),contentScale=ContentScale.Fit)}
            received?.let{Text("마지막 화면 수신 "+DateFormat.getTimeInstance().format(Date(it)))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={viewing=!viewing;if(!viewing){frame=null;received=null}},enabled=gateway!=null&&allowed&&resumed&&!busy,modifier=Modifier.testTag("camera-view")){Text(if(viewing)"화면 닫기" else "화면 보기")}
            if(canConfigure)OutlinedButton(onClick={editing=!editing},enabled=gateway!=null&&allowed&&!busy,modifier=Modifier.testTag("camera-settings")){Text("카메라 연결")}
        }
        if(editing&&canConfigure&&allowed){
            Text("같은 Wi-Fi의 IP Webcam에서 서버 시작 후 표시되는 주소를 입력하세요. 연결하면 소유자·가족이 볼 수 있습니다. 영상은 저장하지 않으며 HTTP로 전달됩니다.")
            OutlinedTextField(url,{url=it.take(200)},label={Text("http://192.168.0.10:8080")},modifier=Modifier.fillMaxWidth().testTag("camera-url"),singleLine=true)
            OutlinedTextField(username,{username=it.take(128)},label={Text("카메라 로그인 이름 · 설정한 경우")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(password,{password=it.take(128)},label={Text("비밀번호 · 설정한 경우")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={viewing=false;frame=null;action{call("camera-configure",JSONObject().put("enabled",true).put("url",url.trim()).put("username",username).put("password",password).put("consent",true));password="";configured=true;editing=false;message="연결 설정 저장 완료 · 화면 보기를 눌러 주세요"}},enabled=!busy,modifier=Modifier.testTag("camera-save")){Text("정보 확인하고 연결")}
            OutlinedButton(onClick={viewing=false;frame=null;action{call("camera-configure",JSONObject().put("enabled",false));configured=false;checked=null;message="카메라 연결을 해제했어요"}},enabled=!busy&&configured){Text("모든 앱의 카메라 연결 해제")}
        }
        OutlinedButton(onClick={action{checked=call("camera-check").getLong("checkedAt");message="직접 확인한 시각을 공유했어요"}},enabled=allowed&&gateway!=null&&configured&&!busy,modifier=Modifier.testTag("camera-checked")){Text("직접 확인했어요")}
        checked?.let{Text("마지막 직접 확인 "+DateFormat.getTimeInstance().format(Date(it)))}
        Text("건강·안전·짖음을 자동 판정하지 않습니다. 영상이 끊기거나 의심되는 상황에서는 직접 확인하세요.",style=MaterialTheme.typography.bodySmall)
    }
}

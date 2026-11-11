package com.familycare.navigation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Voice command window: what was heard, the reply, and numbered results only. Example phrases and notices live in the docs. */
@Composable internal fun DriverVoiceOverlay(care:CareRuntime){
 val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
 val voice=remember(care){DriverVoice(context,care)}
 DisposableEffect(voice,lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)voice.close()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);voice.dispose()}}
 LaunchedEffect(care.revision){voice.observeResult();if(!care.engine.ux.available)voice.close()}
 MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF002C5F),onPrimary=Color.White,secondaryContainer=Color.White,onSecondaryContainer=Color(0xFF002C5F),surface=Color.White,surfaceContainerHigh=Color.White)){
 if(care.engine.ux.available)Box(Modifier.fillMaxSize()){
  FilledTonalButton(onClick=voice::show,modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=if(care.navigation.active)146.dp else 22.dp).heightIn(min=56.dp).testTag("driver-microphone")){
   MicrophoneGlyph();Spacer(Modifier.width(10.dp));Text("음성 명령",fontSize=19.sp)
  }
 }
 if(voice.open)AlertDialog(onDismissRequest=voice::close,modifier=Modifier.testTag("driver-voice-dialog"),
  title={Text("음성 명령",fontWeight=FontWeight.Bold)},
  text={Column(Modifier.fillMaxWidth().heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
   voice.selected?.let{Text(voice.summary(it),fontSize=20.sp,modifier=Modifier.testTag("voice-selected-request"))}
   if(!voice.listening&&care.parked&&voice.pending.size>1)voice.pending.forEach{r->OutlinedButton(onClick={voice.select(r)},modifier=Modifier.fillMaxWidth()){Text(r.title,maxLines=2)}}
   if(voice.text.isNotBlank())Text("“${voice.text}”",fontSize=22.sp,fontWeight=FontWeight.Medium,modifier=Modifier.testTag("voice-transcript"))
   if(voice.listening)Box(if(voice.ready)Modifier.testTag("voice-ready") else Modifier){LinearProgressIndicator(progress={voice.level},modifier=Modifier.fillMaxWidth().testTag("voice-listening"))}
   if(voice.interpreting)LinearProgressIndicator(Modifier.fillMaxWidth().testTag("voice-interpreting"))
   if(voice.status.isNotBlank())Text(voice.status,fontSize=19.sp,modifier=Modifier.testTag("voice-status"))
   voice.choices.forEachIndexed{i,r->Text("${i+1}번 · ${r.title}",fontSize=20.sp,maxLines=2,modifier=Modifier.testTag("voice-choice-${i+1}"))}
   voice.places.forEachIndexed{i,p->
    val label="${i+1}번 · ${p.name}"+if(p.detail.isNotBlank())" · ${p.detail.take(40)}" else ""
    if(care.parked)OutlinedButton(onClick={voice.pickPlace(i)},enabled=!voice.interpreting,modifier=Modifier.fillMaxWidth().testTag("voice-place-${i+1}")){Text(label,maxLines=2)}
    else Text(label,fontSize=20.sp,maxLines=2,modifier=Modifier.testTag("voice-place-${i+1}"))
   }
  }},confirmButton={Button(onClick=voice::toggle,enabled=!voice.interpreting,modifier=Modifier.heightIn(min=56.dp).testTag("voice-listen")){MicrophoneGlyph();Spacer(Modifier.width(8.dp));Text(if(voice.listening)"듣기 중지" else "말하기",fontSize=19.sp)}},
  dismissButton={TextButton(onClick=voice::close,modifier=Modifier.heightIn(min=56.dp)){Text("닫기",fontSize=19.sp)}})
}}
@Composable private fun MicrophoneGlyph(){val c=LocalContentColor.current;Canvas(Modifier.size(24.dp)){
 val w=size.width;val h=size.height
 drawRoundRect(c,Offset(w*.35f,h*.05f),Size(w*.3f,h*.55f),androidx.compose.ui.geometry.CornerRadius(w*.15f))
 drawArc(c,0f,180f,false,Offset(w*.2f,h*.2f),Size(w*.6f,h*.55f),style=Stroke(w*.08f))
 drawLine(c,Offset(w*.5f,h*.75f),Offset(w*.5f,h*.95f),w*.08f)
 drawLine(c,Offset(w*.3f,h*.95f),Offset(w*.7f,h*.95f),w*.08f)
}}

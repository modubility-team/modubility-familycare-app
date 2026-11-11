@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.phone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun NotificationPanel(m:PhoneModel){
    val context=LocalContext.current
    val prefs=context.getSharedPreferences("care-notifications",0)
    var version by remember{mutableIntStateOf(0)}
    val request=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted->if(granted)m.startAlerts()else m.explain("알림 권한이 없어 앱 안에서만 확인할 수 있어요")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("도착·케어 알림",style=MaterialTheme.typography.titleLarge)
        Text("차량 연결은 앱을 나가도 유지됩니다. 아래 설정은 도착·케어 알림 수신만 바꿉니다.")
        listOf("arrival" to "목적지 도착", "rear" to "뒷좌석 확인", "care" to "온도·배터리·타이머·미확인 재알림").forEach{(key,label)->
            val checked=remember(version){prefs.getBoolean(key,true)}
            Row{Switch(checked,{prefs.edit().putBoolean(key,it).apply();version++});Text(label,Modifier.padding(12.dp))}
        }
        Button(onClick={if(android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)request.launch(android.Manifest.permission.POST_NOTIFICATIONS)else m.startAlerts()},enabled=m.connected&&!m.stale&&m.canConnect&&m.role in listOf("owner","family","observer")){Text("이 휴대폰에서 백그라운드 알림 켜기")}
        OutlinedButton(onClick={m.stopAlerts();m.explain("도착·케어 알림을 껐어요. 차량 연결은 계속 유지됩니다")}){Text("백그라운드 알림 끄기")}
        Text("이 휴대폰에서 받을 항목을 선택하세요. 다른 보호자도 상태 확인용 QR로 연결하고 알림을 켜야 받아요. 앱 강제 종료·기기 절전·Wi-Fi 이탈 시 알림이 중단될 수 있습니다.")
        if(m.isOwner)m.recipients.forEach{r->
            Text(r.optString("name")+" · "+r.optString("role"))
            Row{Switch(r.optBoolean("arrival",true),{m.recipient(r.optString("client"),it,r.optBoolean("care",true))});Text("도착 알림",Modifier.padding(12.dp))}
            Row{Switch(r.optBoolean("care",true),{m.recipient(r.optString("client"),r.optBoolean("arrival",true),it)});Text("케어 알림",Modifier.padding(12.dp))}
        }
        var guardian by rememberSaveable{mutableStateOf(prefs.getString("guardian","").orEmpty())}
        var backup by rememberSaveable{mutableStateOf(prefs.getString("backup","").orEmpty())}
        var guardianNumber by rememberSaveable{mutableStateOf(prefs.getString("guardian-number","").orEmpty())}
        var backupNumber by rememberSaveable{mutableStateOf(prefs.getString("backup-number","").orEmpty())}
        OutlinedTextField(guardian,{guardian=it.take(40)},label={Text("담당 보호자 별명")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(backup,{backup=it.take(40)},label={Text("보조 보호자 별명")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(guardianNumber,{guardianNumber=it.filter{c->c.isDigit()||c=='+'}.take(20)},label={Text("담당 보호자 전화번호 · 이 기기에만 저장")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(backupNumber,{backupNumber=it.filter{c->c.isDigit()||c=='+'}.take(20)},label={Text("보조 보호자 전화번호 · 이 기기에만 저장")},modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={prefs.edit().putString("guardian",guardian).putString("backup",backup).putString("guardian-number",guardianNumber).putString("backup-number",backupNumber).apply();m.explain("보호자 연락처를 이 기기에 저장했어요. 각 보호자에게 상태 확인용 연결 링크를 전달하세요. 별명은 알림을 켤 때 차량에 등록됩니다.")}){Text("보호자 연락처 저장")}
        listOf("담당 보호자에게 전화" to guardianNumber,"보조 보호자에게 전화" to backupNumber).forEach{(label,number)->if(number.isNotBlank())TextButton(onClick={runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,android.net.Uri.fromParts("tel",number,null)))}.onFailure{m.explain("전화 앱이 없는 기기예요")}}){Text(label)}}
    }
}

@Composable internal fun FamilyProfilePanel(m:PhoneModel){
    val prefs=LocalContext.current.getSharedPreferences("family-profiles",0)
    var names by rememberSaveable{mutableStateOf("")}
    var band by rememberSaveable{mutableIntStateOf(1)}
    var sensitivity by rememberSaveable{mutableIntStateOf(1)}
    var selectedSlot by rememberSaveable{mutableIntStateOf(0)}
    fun load(raw:String){runCatching{JSONObject(raw)}.getOrNull()?.let{p->val a=p.optJSONArray("members")?:JSONArray();names=(0 until a.length()).map{a.getString(it)}.joinToString(",");band=p.optInt("childBand",1);sensitivity=p.optInt("sensitivity",1)}}
    fun data()=JSONObject().put("members",JSONArray(names.split(",").filter{it.isNotBlank()})).put("childBand",band).put("sensitivity",sensitivity)
    LaunchedEffect(Unit){load(prefs.getString("profile-0","{}")?:"{}")}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("가족 구성 · 맞춤 모드",style=MaterialTheme.typography.titleLarge)
        Text("탑승 유형과 아이 나이대만 소유자·가족 연결에 공유합니다. 해당 권한이 승인되어 있으면 P단 정차 중 바로 반영돼요.")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("CHILD" to "아이","ELDER" to "어르신","PREGNANT" to "임산부","PET" to "반려동물","NOVICE" to "초보운전").forEach{(key,label)->
                val selected=key in names.split(",")
                FilterChip(selected,onClick={names=(if(selected)names.split(",").filter{it!=key}else names.split(",").filter{it.isNotBlank()}+key).joinToString(",")},label={Text(label)})
            }
        }
        if("CHILD" in names.split(","))FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("0~3세","4~7세","8세 이상").forEachIndexed{i,label->FilterChip(band==i,{band=i},label={Text(label)})}
        }
        Text("차량 적용: "+m.state.optJSONObject("profile")?.optString("mode").orEmpty().ifBlank{"연결 후 확인"})
        FlowRow{listOf("낮음","기본","높음").forEachIndexed{i,label->FilterChip(sensitivity==i,{sensitivity=i},label={Text("민감도 $label")})}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){(0..1).forEach{i->
            OutlinedButton(onClick={selectedSlot=i;load(prefs.getString("profile-$i","{}")?:"{}")}){Text("구성 ${i+1} 불러오기")}
        }}
        OutlinedButton(onClick={prefs.edit().putString("profile-$selectedSlot",data().toString()).apply();m.explain("구성 ${selectedSlot+1}을 이 휴대폰에 저장했어요")}){Text("선택한 구성 저장")}
        Button(onClick={m.careCommand("profile","가족 구성 변경 요청",data())},enabled=m.canPlan,modifier=Modifier.fillMaxWidth().testTag("request-profile")){Text("가족 구성 적용")}
    }
}

@Composable internal fun PetTimerPanel(m:PhoneModel){
    var minutes by rememberSaveable{mutableIntStateOf(5)}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("직접 확인 타이머",style=MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,5,10,20,30).forEach{n->FilterChip(minutes==n,{minutes=n},label={Text("${n}분")})}}
        Button(onClick={m.careCommand("timer","${minutes}분 확인 타이머 시작 요청",JSONObject().put("action","start").put("minutes",minutes))},enabled=m.canPlan,modifier=Modifier.fillMaxWidth().testTag("request-timer")){Text("확인 타이머 시작")}
        OutlinedButton(onClick={m.careCommand("timer","확인 타이머 종료 요청",JSONObject().put("action","stop").put("minutes",minutes))},enabled=m.canPlan&&m.state.optBoolean("watching")){Text("타이머 종료 요청")}
        Text("확인 타이머 권한을 승인받은 후 P단 정차 중에 사용할 수 있어요.")
        Text(if(m.state.optBoolean("climateHolding"))"승인된 공조 보조 · ${m.state.optInt("climateHoldSeconds")}초 남음" else "공조 보조 꺼짐")
        Button(onClick={m.careCommand("hold","5분 공조 보조 승인 요청 · 기준 23°C",JSONObject().put("action","start").put("target",23).put("minutes",5))},enabled=m.canPlan){Text("5분 공조 보조 시작")}
        OutlinedButton(onClick={m.careCommand("hold","공조 보조 종료 요청",JSONObject().put("action","stop").put("target",23).put("minutes",5))},enabled=m.canPlan&&m.state.optBoolean("climateHolding")){Text("공조 보조 종료 요청")}
        Text("공조 보조 권한이 있으면 30초 간격으로 22~24°C 설정을 보조합니다. P단·타이머·공조 전원·센서·배터리 조건이 깨지거나 수동으로 온도를 바꾸면 중단합니다. 자동 시동·공조 전원 켜기는 하지 않아요.")
    }
}

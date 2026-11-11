package com.familycare.phone
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun SharedAgreementPanel(m:PhoneModel){
 val device=m.grant.optString("deviceId")
 m.requests.filter{it.optString("status")=="awaiting_agreement"}.forEach{r->
  val a=r.optJSONObject("agreement")?:return@forEach;val people=a.optJSONArray("participants")?:return@forEach
  if((0 until people.length()).none{people.optString(it)==device})return@forEach
  Card(Modifier.fillMaxWidth().testTag("shared-agreement")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("공용 설정을 함께 정해요",style=MaterialTheme.typography.titleLarge)
   Text(a.optString("label"))
   fun value(key:String)=if(a.optBoolean("boolean")){if(a.optDouble(key)==1.0)"켜기 / 잠금" else "끄기 / 해제"}else a.optDouble(key).toString()
   Text("최근 설정 ${value("current")} · 새 요청 ${value("requested")}")
   Text("함께 사용할 제안 값: ${value("proposed")}")
   Text("두 사람의 승인 범위 안에서 제안합니다. 90초 안에 두 사람이 동의해야 적용돼요.")
   val votes=a.optJSONArray("accepted");val accepted=votes!=null&&(0 until votes.length()).any{votes.optString(it)==device}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Button(onClick={m.agree(r.optString("id"),true)},enabled=m.canRequest&&!accepted,modifier=Modifier.testTag("agree-shared")){Text(if(accepted)"상대방 동의 대기" else "제안 값에 동의")}
    OutlinedButton(onClick={m.agree(r.optString("id"),false)},enabled=m.canRequest){Text("취소")}
   }
  }}
 }
}

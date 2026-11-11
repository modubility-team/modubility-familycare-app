package com.familycare.navigation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.familycare.ui.SeatAccess
import com.familycare.ui.PermissionRange
import org.json.JSONObject
import org.json.JSONArray

@Composable fun SeatPermissionReview(care:CareRuntime,r:PassengerRequest){
 val requested=remember(r.id){JSONObject(r.options)}
 val raw=requested.optJSONArray("scopes")?:JSONArray()
 val original=(0 until raw.length()).map{raw.optString(it)}
 val seat=requested.optString("seat","ROW_1_LEFT")
 var selected by remember(r.id){mutableStateOf(original.toSet())}
 var limits by remember(r.id){mutableStateOf(JSONObject((requested.optJSONObject("limits")?:JSONObject()).toString()))}
 Surface(color=SoftFill,shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)){Text("${requested.optString("name")} · ${SeatAccess.seats[seat]?:seat}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(horizontal=14.dp,vertical=8.dp))}
 InfoLine("check","필요한 기능만 골라 범위를 좁혀 승인하세요")
 original.forEach{scope->
  val c=care.relay.permissionCatalog.firstOrNull{it.optString("id")==scope}
  val b=limits.optJSONObject(scope)
  Row(verticalAlignment=Alignment.CenterVertically){Checkbox(scope in selected,{selected=if(it)selected+scope else selected-scope});Text(c?.optString("label")?:scope)}
  if(scope in selected&&c!=null&&!c.optBoolean("boolean")){
   val proposed=requested.optJSONObject("limits")?.optJSONObject(scope)
   val minimum=proposed?.optDouble("min")?:c.optDouble("min")
   val maximum=proposed?.optDouble("max")?:c.optDouble("max")
   PermissionRange(b?.optDouble("min")?:minimum,b?.optDouble("max")?:maximum,minimum,maximum,c.optDouble("step",1.0)){lo,hi->limits=JSONObject(limits.toString()).put(scope,JSONObject().put("min",lo).put("max",hi))}
  }
 }
 InfoLine("clock","${care.grantLifetime} 유지 · 공용 기능은 다른 좌석에도 적용 · 좌석은 직접 확인")
 Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
  Button(onClick={
   val kept=JSONObject();selected.forEach{k->limits.optJSONObject(k)?.let{kept.put(k,it)}}
   care.decide(r,true,JSONObject().put("name",requested.optString("name")).put("seat",seat).put("scopes",JSONArray(selected.toList())).put("limits",kept))
  },enabled=selected.isNotEmpty(),modifier=Modifier.heightIn(min=60.dp).testTag("approve-seat-permission")){com.familycare.ui.FamilyGlyph("check",androidx.compose.ui.graphics.Color.White,Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text("선택한 권한 승인")}
  IconAction("보류","close",{care.decide(r,false)})
 }
}

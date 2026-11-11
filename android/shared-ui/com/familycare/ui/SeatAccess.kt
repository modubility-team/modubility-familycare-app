package com.familycare.ui

import org.json.JSONArray
import org.json.JSONObject

object SeatAccess {
 val seats=linkedMapOf("ROW_1_LEFT" to "운전석","ROW_1_RIGHT" to "조수석","ROW_2_LEFT" to "뒷좌석 왼쪽","ROW_2_CENTER" to "뒷좌석 가운데","ROW_2_RIGHT" to "뒷좌석 오른쪽")
 fun objects(a:JSONArray?)=if(a==null)emptyList()else(0 until a.length()).mapNotNull{a.optJSONObject(it)}
 fun members(id:String)=id.substringAfter(':',id).split('+').filter{it in seats}
 fun required(kind:String,o:JSONObject)=when(kind){
  "climate"->(if(!o.has("power")||o.optBoolean("power"))listOf("climate")else emptyList())+(if(o.has("power"))listOf("climatePower")else emptyList())
  "control"->listOf("control:"+o.optString("control"))
  "destination","waypoint"->listOf("route")
  "hold","profile","timer","care"->listOf(kind)
  else->emptyList()
 }
 fun has(g:JSONObject,scope:String):Boolean{val a=g.optJSONArray("scopes")?:return false;return(0 until a.length()).any{a.optString(it)==scope}}
 fun within(g:JSONObject,key:String,value:Double):Boolean{
  val b=g.optJSONObject("limits")?.optJSONObject(key)?:return false
  return value.isFinite()&&value>=b.optDouble("min",Double.POSITIVE_INFINITY)&&value<=b.optDouble("max",Double.NEGATIVE_INFINITY)
 }
 fun allows(g:JSONObject,kind:String,o:JSONObject,target:Double?,areas:JSONArray?,controls:JSONArray?):Boolean{
  val needed=required(kind,o);if(needed.isEmpty()||needed.any{!has(g,it)})return false
  val seat=g.optString("seat","ROW_1_LEFT")
  if(kind=="climate"&&(!o.has("power")||o.optBoolean("power"))){
   val area=o.optString("area","ROW_1_LEFT")
   if(objects(areas).none{it.optString("id")==area&&seat in members(area)}||!within(g,"climate",target?:Double.NaN))return false
  }
  if(kind=="control"){
   val id=o.optString("control");val c=objects(controls).firstOrNull{it.optString("id")==id&&it.optBoolean("available")}?:return false
   val affected=members(id).ifEmpty{if(id=="wheel.heat")listOf("ROW_1_LEFT")else seats.keys.toList()}
   if(seat !in affected)return false
   if(!c.optBoolean("boolean")&&!within(g,"control:"+id,o.optDouble("value",Double.NaN)))return false
  }
  if(kind=="care"&&o.has("temperature")){
   val z=objects(areas).firstOrNull{seat in members(it.optString("id"))}?:return false
   if(!allows(g,"climate",JSONObject().put("area",z.optString("id")),o.optDouble("temperature"),areas,controls))return false
  }
  if(kind=="hold"&&o.optString("action")=="start"&& (seat!="ROW_1_LEFT"||!has(g,"climate")||!within(g,"climate",22.0)||!within(g,"climate",24.0)))return false
  return true
 }
}

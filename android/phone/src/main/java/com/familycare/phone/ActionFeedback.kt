package com.familycare.phone
import org.json.JSONObject
import com.familycare.ui.SeatAccess

/** Describes the requested values; only a confirmed vehicle result may label them complete. */
object ActionFeedback {
    fun label(body:JSONObject,state:JSONObject):String? {
        val o=body.optJSONObject("options")?:JSONObject()
        fun amount(v:Double)=if(v==v.toInt().toDouble())v.toInt().toString()else "%.1f".format(v)
        return when(body.optString("kind")){
            "climate"->{
                val zone=SeatAccess.objects(state.optJSONArray("climateAreas")).firstOrNull{it.optString("id")==o.optString("area")}
                val seat=zone?.optString("label")?:SeatAccess.seats[o.optString("area")]?:"선택 좌석"
                if(o.opt("power")==false)"공조 전원 끄기" else "$seat ${amount(body.optDouble("target"))}°C"+if(o.opt("power")==true)" · 공조 켜기" else ""
            }
            "control"->{
                val id=o.optString("control");val value=o.optDouble("value")
                val c=SeatAccess.objects(state.optJSONArray("controls")).firstOrNull{it.optString("id")==id}
                val label=c?.optString("label")?:body.optString("title").substringBefore(" · ")
                val chosen=when{
                    (id.startsWith("door.lock:")||id.startsWith("window.lock:"))->if(value==1.0)"잠금" else "잠금 해제"
                    id.startsWith("window.position:")->{
                        val min=c?.optDouble("min",0.0)?:0.0;val max=c?.optDouble("max",Double.NaN)?:Double.NaN
                        when{value==min->"닫기";max.isFinite()&&max>min->"${kotlin.math.round(100*(value-min)/(max-min)).toInt()}% 열기";else->"${amount(value)} 단계"}
                    }
                    c?.optBoolean("boolean")==true->if(value==1.0)"켜기" else "끄기"
                    else->"${amount(value)} 단계"
                }
                "$label · $chosen"
            }
            "destination"->"목적지 · "+body.optString("title").take(60)
            "waypoint"->"경유지 · "+body.optString("title").take(60)
            "timer"->if(o.optString("action")=="stop")"반려동물 확인 타이머 종료" else "반려동물 확인 ${o.optInt("minutes")}분 타이머"
            "hold"->if(o.optString("action")=="stop")"공조 보조 종료" else "공조 보조 ${amount(o.optDouble("target"))}°C · ${o.optInt("minutes")}분"
            "profile"->"가족 구성·맞춤 모드 변경"
            "care"->body.optString("title").take(90)
            else->null
        }
    }
}

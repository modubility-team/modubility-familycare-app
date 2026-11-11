package com.familycare.ui
import org.json.JSONObject
/**
 * Wording for the family's designated rest stop in situation care, shared by the car and phones.
 * The stop is a place the family picked; it reaches the navigation as a waypoint, so the time shown is the
 * navigation's estimate to that waypoint. Older sessions with a timed plan (minutes) keep their old wording.
 */
object RestStopText {
    /** The fields the wording needs, read from the situation-care state. Plain values so the wording is unit-tested without Android. */
    data class Status(val stopName:String="",val onRoute:Boolean=false,val etaSeconds:Int?=null,val restDue:Boolean=false,
        val remainingSeconds:Int?=null,val restedAt:String="",val due:String="",val startedAt:String="")
    fun status(c:JSONObject)=Status(stopName(c),c.optBoolean("stopOnRoute"),if(c.isNull("stopEtaSeconds"))null else c.optInt("stopEtaSeconds"),
        c.optBoolean("restDue"),if(c.isNull("remainingSeconds"))null else c.optInt("remainingSeconds"),c.optString("restedAt"),c.optString("due"),c.optString("startedAt"))
    private fun minutes(seconds:Int)=((seconds+59)/60).coerceAtLeast(1)
    private fun eta(s:Status)=s.etaSeconds?.takeIf{s.onRoute}?.let{minutes(it)}
    fun stopName(c:JSONObject)=c.optJSONObject("stop")?.optString("name").orEmpty()
    /** Short line for the car screen (shown with the situation label). */
    fun car(c:JSONObject)=car(status(c))
    fun car(s:Status):String{
        val stop=s.stopName.isNotBlank();val eta=eta(s);val remaining=s.remainingSeconds
        return when{
            stop&&s.restDue->"지정 휴게소 도착 · 쉬어 가세요"
            stop&&eta!=null->"지정 휴게소 도착까지 ${eta}분"
            stop&&s.onRoute->"지정 휴게소 경유 안내 중"
            stop->"지정 휴게소 · 경로 반영 대기"
            s.restDue->"계획한 휴식 시간이에요"
            remaining!=null->"휴식 계획까지 ${minutes(remaining)}분"
            s.restedAt.isNotBlank()->"지정 휴게소 휴식 완료"
            else->"지정 휴게소 없음"
        }
    }
    /** Fuller line for the family's phones, with the stop's name. */
    fun phone(c:JSONObject)=phone(status(c))
    fun phone(s:Status):String{
        val name=s.stopName;val eta=eta(s);val remaining=s.remainingSeconds
        return when{
            name.isNotBlank()&&s.restDue->"지정 휴게소($name)에 도착했어요. 안전한 곳에서 쉬어 가세요"
            name.isNotBlank()&&eta!=null->"지정 휴게소($name) 도착까지 약 ${eta}분"
            name.isNotBlank()&&s.onRoute->"지정 휴게소($name) 경유 안내 중"
            name.isNotBlank()->"지정 휴게소($name) · 차량 경로 반영 대기. 주행 중에는 운전자가 음성으로 경유를 승인해요"
            s.restDue->"계획한 휴식 시간이에요. 안전한 곳에서 쉬어 가세요"
            remaining!=null->"다음 휴식까지 ${minutes(remaining)}분"
            s.restedAt.isNotBlank()->"지정 휴게소(${s.restedAt}) 휴식 완료 · 다음 휴게소를 지정할 수 있어요"
            else->"지정 휴게소 없음"
        }
    }
    /** Changes whenever there is a new arrival to tell about (used to notify once). */
    fun arrivalKey(c:JSONObject)=arrivalKey(status(c))
    fun arrivalKey(s:Status)=s.stopName.ifBlank{s.due}+"|"+s.startedAt
}

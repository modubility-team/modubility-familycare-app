package com.familycare.navigation

data class DrivingPopup(val title:String,val pending:Int,val urgent:Boolean=false,val code:String?=null)

/** Visual requests remain until handled, independently of speech cooldown and audio focus. */
fun driverPopup(alerts:List<CareAlert>,requests:List<PassengerRequest>,spokenNotice:String):DrivingPopup?{
    val pending=requests.filter{it.status=="pending"}
    alerts.maxByOrNull{it.level}?.let{return DrivingPopup(it.title,pending.size,it.level>=2,it.code)}
    pending.lastOrNull()?.let{r->
        val title=when(r.kind){
            "climate"->"공조 조절 요청이 도착했어요"
            "destination"->"목적지 제안이 도착했어요"
            "waypoint"->"경유지 제안이 도착했어요"
            "timer","hold"->"반려동물 케어 요청이 도착했어요"
            "profile"->"가족 구성 변경 요청이 도착했어요"
            else->when(r.title){"다음 안전한 장소에서 쉬고 싶어요"->"동승자가 휴식을 요청했어요";"음악 소리를 줄여 주세요"->"음악 소리를 줄여 주세요";else->"동승자 요청이 도착했어요"}
        }
        return DrivingPopup(title,pending.size)
    }
    return spokenNotice.takeIf{it.isNotBlank()}?.let{DrivingPopup(it,0)}
}

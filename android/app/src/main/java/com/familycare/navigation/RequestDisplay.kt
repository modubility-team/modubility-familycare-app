package com.familycare.navigation

/** Short, glanceable labels for passenger requests on the vehicle screen (WithME 0.21 readability pass). */

/** A request the driver has finished with: it may be cleared from the vehicle list. */
internal fun handledRequest(status:String)=status!="pending"&&status!="executing"

internal fun requestIcon(r:PassengerRequest)=when(r.kind){
    "pickup"->"pin"
    "destination","waypoint"->"route"
    "climate"->if((r.target?:23.0)<=22.0)"cool" else "warm"
    "hold"->"cool"
    "control"->"car"
    "note"->"chat"
    "care","profile"->"people"
    "permission"->"lock"
    "timer"->"clock"
    else->"chat"
}

internal fun requestKindLabel(r:PassengerRequest):String{
    val kind=when(r.kind){
        "pickup"->"픽업"
        "destination"->"목적지 제안 · ${r.votes}표"
        "waypoint"->"경유지 제안 · ${r.votes}표"
        "climate"->r.target?.let{"온도 ${formatTarget(it)}°C"}?:"온도"
        "hold"->"공조 보조"
        "control"->"차량 조절"
        "note"->"한마디"
        "care"->"상황별 케어"
        "permission"->"좌석 권한"
        "timer"->"반려동물 타이머"
        "profile"->"가족 구성"
        else->"요청"
    }
    val who=when{r.originRole=="owner"->"소유자";r.delegated->"승인된 권한";else->"가족"}
    return "$kind · $who"
}

/** Label for the primary approve button; kept short so it fits one line on the vehicle display. */
internal fun approveLabel(kind:String,hasRoute:Boolean,restStop:Boolean=false)=when(kind){
    "pickup"->if(hasRoute)"픽업 경유지 추가" else "픽업 경로 찾기"
    "care"->"케어 시작"
    "permission"->"선택한 권한 승인"
    "note"->"확인"
    "climate"->"온도 적용"
    "timer","profile","hold","control"->"승인하고 적용"
    else->if(restStop)"경유지로 추가" else "경로 찾기"
}

internal fun statusLabel(status:String)=when(status){
    "pending"->"확인 대기"
    "executing"->"처리 중"
    "confirmed"->"완료"
    "rejected"->"보류"
    else->"내비 확인"
}

private fun formatTarget(value:Double)=if(value==Math.floor(value))value.toInt().toString() else "%.1f".format(value)

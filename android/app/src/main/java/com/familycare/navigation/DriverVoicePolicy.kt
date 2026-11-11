package com.familycare.navigation

enum class VoiceCommand { APPROVE, PICKUP, REJECT, CANCEL, READ, NEXT, REPEAT, UNKNOWN }
/** Exact utterances only: partial/negated/compound speech must never approve a request. */
object DriverVoicePolicy {
    fun command(text:String):VoiceCommand=when(text.trim().lowercase().replace(Regex("[\\s.!?。！？,]"),"")){
        "승인","승인해","승인해줘","승인해주세요","이요청승인","요청승인","허용","허용해줘","수락","수락해줘"->VoiceCommand.APPROVE
        "픽업경유해줘","픽업경유","동승자픽업해줘","픽업해줘","픽업장소들러줘","픽업장소로경유해줘"->VoiceCommand.PICKUP
        "거절","거절해","거절해줘","거절해주세요","요청거절","보류","보류해줘","승인하지마","승인하지마세요","승인하지말아줘","승인하지말아주세요"->VoiceCommand.REJECT
        "취소","취소해","취소해줘","닫기","그만"->VoiceCommand.CANCEL
        "요청읽어줘","읽어줘","무슨요청이야"->VoiceCommand.READ
        "다음요청","다음요청읽어줘"->VoiceCommand.NEXT
        "다시안내","다시안내해줘","길안내다시","다시들려줘"->VoiceCommand.REPEAT
        else->VoiceCommand.UNKNOWN
    }
    fun allowedMoving(kind:String,control:String,vehicleFresh:Boolean):Boolean=vehicleFresh&&
        (kind=="note"||kind in setOf("pickup","destination","waypoint")||kind=="climate"||kind=="control"&&control.substringBefore(':') in setOf(
            "hvac.ac","hvac.auto","hvac.fan","hvac.recirculation","hvac.dual","seat.heat","seat.vent"))
    fun unchanged(selected:PassengerRequest,current:PassengerRequest?)=current!=null&&current.status=="pending"&&selected.copy(status="pending",message="")==current.copy(status="pending",message="")
}

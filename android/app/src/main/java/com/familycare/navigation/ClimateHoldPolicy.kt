package com.familycare.navigation

object ClimateHoldPolicy {
    fun stopReason(parked:Boolean,watching:Boolean,power:Double?,temperature:Double?,battery:Double?,remaining:Long):String?=when{
        remaining<=0->"승인한 공조 보조 시간이 끝났어요"
        !parked->"정차 상태가 바뀌어 공조 보조를 종료했어요"
        !watching->"확인 타이머가 종료되어 공조 보조를 종료했어요"
        power!=1.0->"공조 전원 켜짐을 확인할 수 없어 보조를 종료했어요"
        temperature==null||battery==null->"센서 상태 미확인으로 공조 보조를 종료했어요"
        battery<=25.0->"배터리 25% 이하로 공조 보조를 종료했어요"
        else->null
    }
    fun target(base:Double,cabin:Double)=when{cabin>base+2->base-1;cabin<base-2->base+1;else->base}.coerceIn(18.0,28.0)
}

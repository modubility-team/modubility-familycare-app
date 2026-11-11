package com.familycare.navigation

/**
 * Choices for applying the family's travel criteria ("우리 가족의 이동 기준") to the route being guided.
 * Pure Kotlin for unit tests; LiveNavigation makes the TMAP calls and swaps the route without pausing guidance.
 */
object FamilyReroute {
    data class Option(val id:String,val seconds:Double,val turns:Int,val stop:Boolean,val extraSeconds:Double)
    private val stopFacilities=listOf("반려견 산책" to "반려견 공원","화장실" to "화장실","쉬어 갈 장소" to "휴게소")
    /** The facility to look for ahead on the route, or null when no stop was asked for. */
    fun stopFacility(needs:List<String>):String?=stopFacilities.firstOrNull{it.first in needs}?.second
    /** Criteria that choose between roads (as opposed to adding a stop). */
    fun shapesRoute(needs:List<String>)="빠른 도착" in needs||"방향 전환 적게" in needs
    /** Each sent criteria event is acted on once, and only while fresh: an old event seen after a restart is ignored. */
    fun fresh(id:String,lastId:String,ageSeconds:Double)=id.isNotBlank()&&id!=lastId&&ageSeconds in 0.0..20.0
    /** The server already lists candidates in the family's order; a stop route is taken unless it is a long detour. */
    fun pick(options:List<Option>,wantStop:Boolean,maxStopExtraSeconds:Double=1200.0):Option?{
        if(wantStop)options.firstOrNull{it.stop&&it.extraSeconds<=maxStopExtraSeconds}?.let{return it}
        return options.firstOrNull{!it.stop}?:options.firstOrNull()
    }
    fun label(needs:List<String>)=needs.joinToString("·")
    fun announcement(needs:List<String>,stopName:String?):String=when{
        stopName!=null->"가족 기준에 맞춰 $stopName 경유로 경로를 바꿨어요."
        "방향 전환 적게" in needs->"가족 기준에 맞춰 방향 전환이 적은 길로 바꿨어요."
        "빠른 도착" in needs->"가족 기준에 맞춰 가장 빠른 길로 바꿨어요."
        else->"가족 기준에 맞춰 경로를 바꿨어요."
    }
    /** While driving a stop is not added for the driver; they can add one by voice. */
    fun suggestion(facility:String)="가족이 원하는 $facility 경유는 음성 명령으로 추가할 수 있어요."
    /**
     * True when the new route and the rest of the current one (from [from] metres) run along each other,
     * so switching would change nothing. Checked both ways so a dropped or added loop counts as a change.
     */
    fun sameRoad(candidate:RoadRoute,current:RoadRoute,from:Double=0.0,tolerance:Double=60.0)=
        follows(candidate,current,0.0,tolerance)&&follows(current,candidate,from,tolerance)
    private fun follows(route:RoadRoute,other:RoadRoute,from:Double,tolerance:Double):Boolean{
        val start=from.coerceIn(0.0,route.meters)
        val step=((route.meters-start)/200).coerceAtLeast(100.0)
        var along=start
        while(along<route.meters){
            if(other.project(route.pointAt(along)).offRoute>tolerance)return false
            along+=step
        }
        return other.project(route.points.last()).offRoute<=tolerance
    }
}

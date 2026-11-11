package com.familycare.navigation

import kotlin.math.*

data class GeoPoint(val latitude:Double,val longitude:Double){
    fun valid()=latitude.isFinite()&&longitude.isFinite()&&latitude in -85.0..85.0&&longitude in -180.0..180.0
    fun distance(other:GeoPoint):Double{
        val a=Math.toRadians(latitude);val b=Math.toRadians(other.latitude)
        val x=sin((b-a)/2).pow(2)+cos(a)*cos(b)*sin(Math.toRadians(other.longitude-longitude)/2).pow(2)
        return 6371000*2*asin(sqrt(x.coerceIn(0.0,1.0)))
    }
    fun bearingTo(other:GeoPoint):Float{
        val a=Math.toRadians(latitude);val b=Math.toRadians(other.latitude)
        val d=Math.toRadians(other.longitude-longitude)
        return ((Math.toDegrees(atan2(sin(d)*cos(b),cos(a)*sin(b)-sin(a)*cos(b)*cos(d)))+360)%360).toFloat()
    }
}
data class RouteTurn(val along:Double,val instruction:String,val symbol:String,val road:String)
data class RouteTime(val index:Int,val seconds:Double)
data class RouteRoad(val start:Int,val end:Int,val name:String)
data class RouteTraffic(val start:Int,val end:Int,val state:Int)
enum class RouteOption(val code:Int,val label:String){
    RECOMMENDED(0,"추천"),FREE(1,"무료 우선"),FAST(2,"최소 시간"),HIGHWAY(4,"고속도로 우선"),SHORT(10,"최단 거리"),SCHOOL(19,"어린이보호구역 회피");
    companion object{fun from(code:Int)=entries.firstOrNull{it.code==code}?:RECOMMENDED}
}
data class RoadRoute(val points:List<GeoPoint>,val turns:List<RouteTurn>,val seconds:Double,val destination:String,
    val provider:String="OSRM / OpenStreetMap",val generatedAt:Long=0,val timings:List<RouteTime> = emptyList(),
    val roads:List<RouteRoad> = emptyList(),val traffic:List<RouteTraffic> = emptyList(),val tollFare:Int?=null,
    val option:RouteOption=RouteOption.RECOMMENDED,val restStops:List<RouteTurn> = emptyList()){
    val cumulative:List<Double> = points.runningFoldIndexed(0.0){index,total,p->if(index==0)0.0 else total+points[index-1].distance(p)}.drop(1)
    val meters get()=cumulative.lastOrNull()?:0.0
    fun roadAt(along:Double)=roads.firstOrNull{it.start in points.indices&&it.end in points.indices&&along>=cumulative[it.start]&&along<cumulative[it.end]}?.name.orEmpty()
    fun trafficAt(index:Int)=traffic.firstOrNull{index>=it.start&&index<it.end}?.state
    init{require(points.size>=2&&points.all{it.valid()}&&seconds.isFinite()&&seconds>=0)}
    fun pointAt(along:Double):GeoPoint{
        val at=along.coerceIn(0.0,meters)
        val end=cumulative.indexOfFirst{it>at}.takeIf{it>0}?:return points.last()
        val start=points[end-1];val finish=points[end]
        val fraction=(at-cumulative[end-1])/(cumulative[end]-cumulative[end-1])
        return GeoPoint(start.latitude+(finish.latitude-start.latitude)*fraction,start.longitude+(finish.longitude-start.longitude)*fraction)
    }
    fun headingAt(along:Double):Float{
        val at=along.coerceIn(0.0,meters)
        val from=if(meters-at<3)pointAt((at-8).coerceAtLeast(0.0))else pointAt(at)
        return from.bearingTo(pointAt((at+8).coerceAtMost(meters)))
    }
    fun followingTurn(along:Double):RouteTurn?{
        val current=instruction(along)
        return turns.firstOrNull{it.along>current.along+8}
    }
    fun remainingSeconds(along:Double):Double{
        val valid=timings.isNotEmpty()&&timings.all{it.index in points.indices&&it.seconds.isFinite()&&it.seconds>=0}&&timings.zipWithNext().all{(a,b)->b.index>=a.index}
        if(!valid)return seconds*(1-along/max(meters,1.0)).coerceIn(0.0,1.0)
        val total=timings.sumOf{it.seconds}
        if(total<=0)return 0.0
        var previous=0.0;var elapsed=0.0
        for(t in timings){
            val end=cumulative[t.index]
            if(along>=end)elapsed+=t.seconds
            else if(along>previous&&end>previous)elapsed+=t.seconds*(along-previous)/(end-previous)
            previous=end
        }
        return (seconds*(1-elapsed/total)).coerceIn(0.0,seconds)
    }
    fun project(point:GeoPoint,previous:Double?=null):RouteProjection{
        val scale=cos(Math.toRadians(point.latitude)).coerceAtLeast(.01)
        val candidates=points.zipWithNext().mapIndexed{i,(a,b)->
            val ax=(a.longitude-point.longitude)*111195*scale;val ay=(a.latitude-point.latitude)*111195
            val dx=(b.longitude-a.longitude)*111195*scale;val dy=(b.latitude-a.latitude)*111195
            val denominator=dx*dx+dy*dy
            val t=if(denominator<.0001)0.0 else (-(ax*dx+ay*dy)/denominator).coerceIn(0.0,1.0)
            RouteProjection(cumulative[i]+(cumulative[i+1]-cumulative[i])*t,hypot(ax+t*dx,ay+t*dy),i)
        }
        val closest=candidates.minBy{it.offRoute}
        // Near an intersection, prefer continuity over jumping to a distant loop in the route.
        return if(previous==null)closest else candidates.filter{it.offRoute<=closest.offRoute+8}
            .minBy{abs(it.along-previous)}
    }
    fun instruction(along:Double):RouteTurn=turns.firstOrNull{it.along>along+7}
        ?:RouteTurn(meters,"목적지에 도착합니다","●",destination)
}
data class RouteProjection(val along:Double,val offRoute:Double,val segment:Int)
data class PositionFix(val point:GeoPoint,val accuracy:Float,val bearing:Float?,val speed:Float?,val elapsed:Long,val simulated:Boolean){
    fun usable(now:Long)=point.valid()&&accuracy.isFinite()&&accuracy in 0f..60f&&now-elapsed in 0..10_000
}

/** Navigation decisions are independent of Android, network callbacks and map rendering. */
class RouteFollower {
    var along=0.0;private set
    var offRoute=false;private set
    var arrived=false;private set
    private var outsideSince:Long?=null
    private var arrivalSince:Long?=null
    fun reset(){along=0.0;offRoute=false;arrived=false;outsideSince=null;arrivalSince=null}
    fun update(route:RoadRoute,fix:PositionFix,now:Long):Boolean{
        if(!fix.usable(now))return false
        val projection=route.project(fix.point,along.takeIf{it>0})
        val outside=projection.offRoute>max(45.0,fix.accuracy*1.5)||projection.along<along-max(60.0,fix.accuracy*2.0)
        if(outside){if(outsideSince==null)outsideSince=now}else{outsideSince=null;offRoute=false;along=max(along,projection.along)}
        offRoute=outsideSince?.let{now-it>=3000}?:false
        val nearEnd=!outside&&route.meters-along<35&&fix.point.distance(route.points.last())<35
        if(nearEnd){if(arrivalSince==null)arrivalSince=now}else arrivalSince=null
        arrived=arrivalSince?.let{now-it>=2000}?:false
        return true
    }
}

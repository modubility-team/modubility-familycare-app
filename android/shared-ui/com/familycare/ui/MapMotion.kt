package com.familycare.ui
import kotlin.math.*

/** Display-only interpolation. Never creates a GPS fix or advances navigation/arrival state. */
class MapMotion {
 private var from:MapPosition?=null;private var target:MapPosition?=null
 private var start=0L;private var duration=0L;private var inputAt=0L
 private var path:List<MapRoutePoint> = emptyList();private var lengths=doubleArrayOf()
 private fun meters(a:MapPosition,b:MapPosition)=hypot((b.latitude-a.latitude)*111195,(b.longitude-a.longitude)*111195*cos(Math.toRadians(a.latitude)))
 private fun setPath(route:List<MapRoutePoint>){path=route;lengths=DoubleArray(route.size);for(i in 1 until route.size){val a=route[i-1];val b=route[i];val lat=Math.toRadians(b.latitude-a.latitude);val lon=Math.toRadians(b.longitude-a.longitude);val h=sin(lat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2);lengths[i]=lengths[i-1]+12742000*asin(sqrt(h.coerceIn(0.0,1.0)))}}
 fun offer(value:MapPosition?,route:List<MapRoutePoint>,now:Long){
  val changedRoute=route!=path
  if(changedRoute)setPath(route)
  val old=target
  if(value==null||old==null||!value.fresh||!old.fresh||changedRoute||value.navigation!=old.navigation||meters(old,value)>250||now-inputAt>4000){from=value;target=value;start=now;duration=0;inputAt=now;return}
  // Other telemetry can recompose the map without supplying a new location.
  if(value.latitude==old.latitude&&value.longitude==old.longitude&&value.heading==old.heading){target=value;return}
  val current=sample(now)?:value
  val interval=if(value.fixElapsedMillis!=null&&old.fixElapsedMillis!=null&&value.fixElapsedMillis>old.fixElapsedMillis)value.fixElapsedMillis-old.fixElapsedMillis else now-inputAt
  from=current;target=value;start=now;duration=interval.coerceIn(80,1200);inputAt=now
 }
 fun sample(now:Long):MapPosition?{
  val b=target?:return null;val a=from?:return b
  if(duration<=0)return b
  val f=((now-start).toDouble()/duration).coerceIn(0.0,1.0)
  if(f>=1)return b
  val heading=if(a.heading!=null&&b.heading!=null){val d=((b.heading-a.heading+540)%360)-180;((a.heading+d*f+360)%360).toFloat()}else b.heading
  val progress=if(a.routeProgressMeters!=null&&b.routeProgressMeters!=null)a.routeProgressMeters+(b.routeProgressMeters-a.routeProgressMeters)*f else null
  var lat=a.latitude+(b.latitude-a.latitude)*f;var lon=a.longitude+(b.longitude-a.longitude)*f
  if(progress!=null&&path.size>=2){
   val distance=progress.coerceIn(0.0,lengths.last());val found=lengths.binarySearch(distance);val end=(if(found>=0)found else -found-1).coerceIn(1,path.lastIndex);val begin=end-1
   val t=((distance-lengths[begin])/(lengths[end]-lengths[begin]).coerceAtLeast(.0001)).coerceIn(0.0,1.0)
   lat=path[begin].latitude+(path[end].latitude-path[begin].latitude)*t;lon=path[begin].longitude+(path[end].longitude-path[begin].longitude)*t
  }
  return b.copy(latitude=lat,longitude=lon,heading=heading,routeProgressMeters=progress)
 }
}

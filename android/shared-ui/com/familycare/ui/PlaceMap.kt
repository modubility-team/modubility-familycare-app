package com.familycare.ui

import androidx.compose.runtime.*
import com.skt.tmap.TMapInsets
import com.skt.tmap.TMapPoint
import com.skt.tmap.TMapView
import kotlin.math.*

/** Public Seoul reference point. Actual guidance positions come from the vehicle GPS. */
object MapPlaces {
    const val LATITUDE=37.48544722
    const val LONGITUDE=127.03636666
    fun valid(lat:Double?,lon:Double?)=lat!=null&&lon!=null&&lat.isFinite()&&lon.isFinite()&&lat in -85.0..85.0&&lon in -180.0..180.0
}

/** Explicit north-up / heading-up controls; the SDK does not expose a camera-pitch setter. */
class MapViewportController {
    private var map:TMapView?=null
    var headingUp by mutableStateOf(false);private set
    var following by mutableStateOf(true);private set
    var navigating by mutableStateOf(false);private set
    var autoZoom by mutableStateOf(true);private set
    internal var cameraBearing=0.0;private set
    internal val tmapActive get()=map!=null
    private var firstFix=true
    private var lastPosition:MapPosition?=null
    private var navigationZoom:Int?=null
    internal fun attachTmap(value:TMapView){map=value;firstFix=true;value.setRotationAngle(0.0)}
    internal fun detachTmap(value:TMapView){if(map===value)map=null}
    fun toggleOrientation(){
        headingUp=!headingUp
        cameraBearing=if(headingUp)lastPosition?.heading?.toDouble()?:0.0 else 0.0
        map?.setRotationAngle(cameraBearing)
        if(following)position(lastPosition)
    }
    fun zoomIn(){autoZoom=false;map?.mapZoomIn()}
    fun zoomOut(){autoZoom=false;map?.mapZoomOut()}
    fun toggleAutoZoom(){autoZoom=!autoZoom;if(autoZoom){firstFix=true;position(lastPosition)}}
    fun reset(){map?.let{it.setCenterPoint(MapPlaces.LATITUDE,MapPlaces.LONGITUDE,false);it.setZoomLevel(15)}}
    fun follow(){following=true;firstFix=true}
    fun guidance(enabled:Boolean){
        navigating=enabled;headingUp=enabled;navigationZoom=null
        if(enabled)follow()
        position(lastPosition)
    }
    fun stopFollowing(){following=false}
    fun showPoint(latitude:Double,longitude:Double){if(!MapPlaces.valid(latitude,longitude))return;following=false;headingUp=false;cameraBearing=0.0;map?.let{it.setRotationAngle(0.0);it.setZoomLevel(17);it.setCenterPoint(latitude,longitude,false)}}
    fun overview(points:List<MapRoutePoint>){
        val valid=points.filter{MapPlaces.valid(it.latitude,it.longitude)}
        if(valid.size<2)return
        following=false
        headingUp=false;cameraBearing=0.0
        map?.let{it.setRotationAngle(0.0);it.fitBounds(it.getBoundsFromPoints(ArrayList(valid.map{p->TMapPoint(p.latitude,p.longitude)})),TMapInsets(100,180,100,150))}
    }
    internal fun position(value:MapPosition?){
        lastPosition=value
        if(value==null||!following||!MapPlaces.valid(value.latitude,value.longitude))return
        map?.let{
            cameraBearing=if(headingUp)value.heading?.toDouble()?:cameraBearing else 0.0
            val driving=navigating&&value.navigation
            val zoom=when{
                !driving->17
                (value.turnDistance?:Double.MAX_VALUE)<150->17
                (value.speedMetersPerSecond?:0f)>25->15
                else->16
            }
            if(autoZoom&&(firstFix||(driving&&navigationZoom!=zoom))){
                it.setZoomLevel(zoom);navigationZoom=zoom;firstFix=false
                // Native camera changes are asynchronous. Re-measure after the new zoom is drawn.
                val attached=it
                it.postDelayed({if(map===attached&&following)position(lastPosition)},200)
            }
            // Show more road ahead, keeping the vehicle below the map's centre.
            // Measure the rendered viewport instead of assuming Web Mercator tile/density scales.
            val center=it.convertPointToGps(it.width/2f,it.height/2f)
            val above=it.convertPointToGps(it.width/2f,it.height/2f-100)
            val metersPer100px=if(center!=null&&above!=null)hypot((above.latitude-center.latitude)*111195,
                (above.longitude-center.longitude)*111195*cos(Math.toRadians(value.latitude)))else 0.0
            val ahead=if(driving&&headingUp&&metersPer100px.isFinite())it.height*.18*metersPer100px/100 else 0.0
            val angle=Math.toRadians(cameraBearing)
            val lat=value.latitude+ahead*cos(angle)/111195.0
            val lon=value.longitude+ahead*sin(angle)/(111195.0*cos(Math.toRadians(value.latitude)).coerceAtLeast(.01))
            it.setRotationAngle(cameraBearing)
            it.setCenterPoint(lat,lon,false)
        }
    }
}

data class MapPosition(val latitude:Double,val longitude:Double,val heading:Float?=null,val fresh:Boolean=true,
    val navigation:Boolean=false,val speedMetersPerSecond:Float?=null,val turnDistance:Double?=null,
    val fixElapsedMillis:Long?=null,val routeProgressMeters:Double?=null)
data class MapRoutePoint(val latitude:Double,val longitude:Double,val traffic:Int?=null)

package com.familycare.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.net.Uri
import android.view.MotionEvent
import android.view.Choreographer
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skt.tmap.TMapPoint
import com.skt.tmap.TMapView
import com.skt.tmap.overlay.TMapMarkerItem
import com.skt.tmap.overlay.TMapPolyLine
import com.skt.tmap.poi.TMapPOIItem
import kotlinx.coroutines.delay

/** The parent intercepts touches before the SDK's internal SurfaceView receives them. */
private class TmapContainer(context:Context):FrameLayout(context){
    var interactive=true
    var touched:()->Unit={}
    var wasTap=false;private var downX=0f;private var downY=0f;private var downAt=0L;private var dragged=false
    override fun dispatchTouchEvent(event:MotionEvent):Boolean{
        if(!interactive)return true
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{wasTap=false;dragged=false;downX=event.x;downY=event.y;downAt=event.eventTime;parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_MOVE->if(!dragged&&kotlin.math.hypot(event.x-downX,event.y-downY)>12*resources.displayMetrics.density){dragged=true;touched()}
            MotionEvent.ACTION_POINTER_DOWN->{dragged=true;wasTap=false;touched()}
            MotionEvent.ACTION_UP->{wasTap=!dragged&&event.eventTime-downAt<700;parent?.requestDisallowInterceptTouchEvent(false)}
            MotionEvent.ACTION_CANCEL->{wasTap=false;parent?.requestDisallowInterceptTouchEvent(false)}
        }
        return super.dispatchTouchEvent(event)
    }
}

private class TmapHandle(private val context:Context){
    val map=TMapView(context)
    val container=TmapContainer(context).apply{addView(map,FrameLayout.LayoutParams(-1,-1))}
    var alive=true
    var ready=false
    private var resumed=false
    private var selected:Pair<Double,Double>?=null
    private var selectionShown=false
    private var renderedRoute:List<MapRoutePoint> = emptyList()
    private var renderedGuidance=false
    private var renderedPosition:MapPosition?=null
    private var memorySignature=""
    val memoryIds=mutableMapOf<String,String>()
    private var photoPins:List<MemoryPin> = emptyList()
    private var photoStyle=MemoryPinStyle.Thumbnail
    private var photoCheck=0L
    private val photoIcons=mutableMapOf<String,MemoryPinIcons.Icon>()
    private val positionIcons=mutableMapOf<String,Bitmap>()
    private var renderedAngle:Int?=null
    private val trafficIds=mutableListOf<String>()
    private val motion=MapMotion()
    private var motionRoute:List<MapRoutePoint> = emptyList()
    private var viewport:MapViewportController?=null
    private var lastFrame=0L
    private val choreographer=Choreographer.getInstance()
    private val frame=object:Choreographer.FrameCallback{
        override fun doFrame(nanos:Long){
            if(!alive||!resumed)return
            if(ready&&nanos-lastFrame>=30_000_000L){
                lastFrame=nanos
                val position=motion.sample(SystemClock.elapsedRealtime())
                viewport?.position(position)
                navigation(context,position,motionRoute,viewport?.cameraBearing?:0.0)
            }
            // Zooming changes which car photos would cover each other: re-check the photo markers twice a second.
            if(ready&&photoPins.isNotEmpty()&&photoStyle!=MemoryPinStyle.Thumbnail&&nanos-photoCheck>=500_000_000L){
                photoCheck=nanos;photos(photoPins,photoStyle)
            }
            choreographer.postFrameCallback(this)
        }
    }
    fun updateMotion(position:MapPosition?,route:List<MapRoutePoint>,controller:MapViewportController?){
        viewport=controller;motionRoute=route;motion.offer(position,route,SystemClock.elapsedRealtime())
    }
    private val destinationIcon by lazy{
        val size=(48*context.resources.displayMetrics.density).toInt()
        Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also{bitmap->
            val c=Canvas(bitmap);val p=Paint(Paint.ANTI_ALIAS_FLAG);val s=size.toFloat()
            p.color=android.graphics.Color.WHITE;c.drawCircle(s/2,s/2,s*.49f,p)
            p.color=android.graphics.Color.rgb(193,43,71);c.drawCircle(s/2,s/2,s*.43f,p)
            p.color=android.graphics.Color.WHITE;p.strokeWidth=s*.065f
            c.drawLine(s*.33f,s*.25f,s*.33f,s*.77f,p)
            c.drawPath(Path().apply{moveTo(s*.33f,s*.25f);lineTo(s*.73f,s*.25f);lineTo(s*.62f,s*.42f);lineTo(s*.73f,s*.56f);lineTo(s*.33f,s*.56f);close()},p)
        }
    }

    private fun marker(id:String,lat:Double,lon:Double,title:String,icon:Bitmap?=null)=TMapMarkerItem().apply{
        setId(id);setTMapPoint(TMapPoint(lat,lon));setName(title);setPosition(.5f,.5f)
        setCanShowCallout(false);setVisible(true);icon?.let{setIcon(it)}
    }
    private fun positionIcon(context:Context,fresh:Boolean,heading:Int?,guidance:Boolean)=positionIcons.getOrPut("$fresh:$heading:$guidance"){
        val size=((if(heading==null)36 else if(guidance)92 else 58)*context.resources.displayMetrics.density).toInt().coerceAtLeast(36)
        Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also{bitmap->
            val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG);val c=size/2f
            paint.color=android.graphics.Color.argb(40,0,50,100);canvas.drawCircle(c,c,c-1,paint)
            paint.color=android.graphics.Color.WHITE;canvas.drawCircle(c,c,c*.89f,paint)
            paint.color=if(fresh)android.graphics.Color.rgb(0,119,255)else android.graphics.Color.GRAY
            if(heading==null){canvas.drawCircle(c,c,c*.75f,paint);paint.color=android.graphics.Color.WHITE;canvas.drawCircle(c,c,c*.3f,paint)}
            else{
                canvas.rotate(heading.toFloat(),c,c)
                val arrow=Path().apply{moveTo(c,c*.20f);lineTo(c*1.64f,c*1.61f);lineTo(c,c*1.30f);lineTo(c*.36f,c*1.61f);close()}
                canvas.drawPath(arrow,paint)
            }
        }
    }
    fun navigation(context:Context,position:MapPosition?,route:List<MapRoutePoint>,cameraBearing:Double=0.0){
        if(!ready)return
        val guidance=position?.navigation==true
        if(route!=renderedRoute||guidance!=renderedGuidance){
            trafficIds.forEach{map.removeTMapPolyLine(it)};trafficIds.clear()
            map.removeTMapMarkerItem("family-destination")
            route.lastOrNull()?.let{end->map.addTMapMarkerItem(marker("family-destination",end.latitude,end.longitude,"목적지",destinationIcon))}
            map.removeTMapPolyLine("family-route");renderedRoute=route;renderedGuidance=guidance
            if(route.size>=2)map.addTMapPolyLine(TMapPolyLine().apply{
                setID("family-route");setLineColor(android.graphics.Color.rgb(0,112,246));setLineWidth(if(guidance)22f else 14f)
                setLineAlpha(255);setOutLineColor(android.graphics.Color.rgb(255,255,255));setOutLineWidth(if(guidance)6f else 4f)
                route.filter{MapPlaces.valid(it.latitude,it.longitude)}.forEach{addLinePoint(TMapPoint(it.latitude,it.longitude))}
            })
            var first=0
            while(first<route.lastIndex){
                val state=route[first].traffic;var end=first+1
                while(end<route.lastIndex&&route[end].traffic==state)end++
                if(state in 1..4){
                    val id="family-traffic-${trafficIds.size}";trafficIds.add(id)
                    map.addTMapPolyLine(TMapPolyLine().apply{
                        setID(id);setLineWidth(if(guidance)12f else 8f);setLineAlpha(255)
                        setLineColor(when(state){1->android.graphics.Color.rgb(0,155,123);2->android.graphics.Color.rgb(239,166,28);else->android.graphics.Color.rgb(220,61,61)})
                        for(i in first..end)addLinePoint(TMapPoint(route[i].latitude,route[i].longitude))
                    })
                }
                first=end
            }
        }
        val angle=position?.heading?.takeIf{it.isFinite()}?.let{(((it-cameraBearing+360)%360)/5).toInt()*5}
        if(position==renderedPosition&&angle==renderedAngle)return
        renderedAngle=angle
        val hadPosition=renderedPosition!=null;renderedPosition=position
        if(position==null){map.removeTMapMarkerItem("family-position");return}
        val item=marker("family-position",position.latitude,position.longitude,"현재 위치",positionIcon(context,position.fresh,angle,guidance))
        item.setPriority(100f)
        if(hadPosition)map.updateTMapMarkerItem(item)else map.addTMapMarkerItem(item)
    }
    fun select(lat:Double?,lon:Double?,showMarker:Boolean){
        if(!ready)return
        val next=if(MapPlaces.valid(lat,lon))lat!! to lon!! else null
        if(next==selected&&showMarker==selectionShown)return
        val moved=next!=selected;selected=next;selectionShown=showMarker
        map.removeTMapMarkerItem("family-selection")
        if(next!=null){
            if(showMarker)map.addTMapMarkerItem(marker("family-selection",next.first,next.second,"선택한 장소"))
            if(moved)map.setCenterPoint(next.first,next.second)
        }
    }
    /** Metres per dp across the map's centre, measured on the drawn map; 0 until it can be measured. */
    private fun metersPerDp(density:Float):Double{
        if(map.width<=0||map.height<=0)return 0.0
        val x=map.width/2f;val y=map.height/2f;val step=100f
        val a=map.convertPointToGps(x,y);val b=map.convertPointToGps(x+step,y)
        if(a==null||b==null)return 0.0
        val meters=MemoryPinLayout.distance(a.latitude,a.longitude,b.latitude,b.longitude)
        return if(meters.isFinite())meters/step*density else 0.0
    }
    fun photos(pins:List<MemoryPin>,style:MemoryPinStyle=MemoryPinStyle.Thumbnail){
        if(!ready)return
        photoPins=pins;photoStyle=style
        val visible=pins.filter{MapPlaces.valid(it.latitude,it.longitude)}.take(12)
        fun source(p:MemoryPin)=if(style==MemoryPinStyle.Card&&p.image.isNotBlank())p.image else p.thumbnail
        val density=context.resources.displayMetrics.density
        // Cards follow the map's size so a narrow (split) screen does not get oversized photos.
        val scale=if(style==MemoryPinStyle.Card)MemoryPinLayout.cardScale(map.width/density,map.height/density)else 1f
        // Phone maps keep one marker per photo; the car merges photos whose markers would cover each other at this zoom
        // (one marker with the newest photo and the count; photos within 50 m always share one).
        val groups=if(style==MemoryPinStyle.Thumbnail)visible.map{listOf(it)}
            else MemoryPinLayout.group(visible,MemoryPinLayout.groupMeters(style,scale,metersPerDp(density)))
        val keys=groups.map{g->val p=g.last();val s=source(p);"${p.id}:$style:$scale:${g.size}:${p.title.hashCode()}:${s.length}:${s.hashCode()}"}
        val signature=groups.zip(keys).joinToString{(g,k)->"$k:${g.last().latitude}:${g.last().longitude}"}
        if(signature==memorySignature)return
        memorySignature=signature
        photoIcons.keys.retainAll(keys.toSet())
        memoryIds.keys.forEach{map.removeTMapMarkerItem(it)};memoryIds.clear()
        groups.forEachIndexed{i,g->
            val p=g.last();val id="family-photo-$i";memoryIds[id]=p.id
            // Icons are reused while only other photos change (full photos arrive one by one).
            val icon=photoIcons[keys[i]]?:MemoryPinIcons.icon(source(p),density,style,p.title,g.size,scale)?.also{photoIcons[keys[i]]=it}
            map.addTMapMarkerItem(marker(id,p.latitude,p.longitude,p.title,icon?.bitmap).apply{
                setPriority(50f);icon?.let{setPosition(it.anchorX,it.anchorY)}
            })
        }
    }
    fun sync(state:Lifecycle.State){
        if(!alive)return
        val resume=state.isAtLeast(Lifecycle.State.RESUMED)
        if(resumed&&!resume){choreographer.removeFrameCallback(frame);map.onPause()}
        if(!resumed&&resume){map.onResume();choreographer.postFrameCallback(frame)}
        resumed=resume
    }
    fun close(){if(!alive)return;sync(Lifecycle.State.CREATED);alive=false;ready=false;map.onDestroy()}
}

/** Official SDK renderer. Caller supplies a runtime credential; never hard-code or persist it. */
@Composable internal fun TmapPlaceMap(appKey:String,latitude:Double?=null,longitude:Double?=null,
    onPick:((Double,Double)->Unit)?=null,modifier:Modifier=Modifier,memories:List<MemoryPin> = emptyList(),
    onMemory:((String)->Unit)?=null,showSelectionMarker:Boolean=true,controller:MapViewportController?=null,
    showControls:Boolean=true,showStatus:Boolean=true,position:MapPosition?=null,
    route:List<MapRoutePoint> = emptyList(),interactive:Boolean=true,memoryStyle:MemoryPinStyle=MemoryPinStyle.Thumbnail){
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    val pick by rememberUpdatedState(onPick);val openPhoto by rememberUpdatedState(onMemory)
    val selectedLat by rememberUpdatedState(latitude);val selectedLon by rememberUpdatedState(longitude)
    val photoPins by rememberUpdatedState(memories);val currentPosition by rememberUpdatedState(position)
    val currentRoute by rememberUpdatedState(route);val canInteract by rememberUpdatedState(interactive)
    val pinStyle by rememberUpdatedState(memoryStyle)
    var attempt by remember{mutableIntStateOf(0)}
    var ready by remember(appKey,attempt){mutableStateOf(false)}
    var failed by remember(appKey,attempt){mutableStateOf(false)}
    val handle=remember(context,appKey,attempt){TmapHandle(context).also{h->
        h.map.setOnApiKeyListenerCallback(object:TMapView.OnApiKeyListenerCallback{
            override fun onSKTMapApikeySucceed()=Unit
            override fun onSKTMapApikeyFailed(message:String?){h.map.post{if(h.alive)failed=true}}
        })
        h.map.setOnMapReadyListener{h.map.post{if(h.alive){
            h.ready=true;ready=true;failed=false
            h.map.setVisibleLogo(true);h.map.setZoomLevel(15)
            h.map.setRotateEnable(false);h.map.setTiltEnable(false);h.map.setMarkerRotate(false)
            // Vector SDK uses latitude, longitude (the old raster SDK used the reverse order).
            h.map.setCenterPoint(if(MapPlaces.valid(selectedLat,selectedLon))selectedLat!! else MapPlaces.LATITUDE,
                if(MapPlaces.valid(selectedLat,selectedLon))selectedLon!! else MapPlaces.LONGITUDE,false)
            controller?.attachTmap(h.map)
            h.select(selectedLat,selectedLon,showSelectionMarker);h.photos(photoPins,pinStyle)
            h.updateMotion(currentPosition,currentRoute,controller)
        }}}
        h.map.setOnClickListenerCallback(object:TMapView.OnClickListenerCallback{
            override fun onPressDown(markers:ArrayList<TMapMarkerItem>?,pois:ArrayList<TMapPOIItem>?,point:TMapPoint?,screen:PointF?)=Unit
            override fun onPressUp(markers:ArrayList<TMapMarkerItem>?,pois:ArrayList<TMapPOIItem>?,point:TMapPoint?,screen:PointF?){
                if(!h.alive||!canInteract||!h.container.wasTap)return
                val id=markers?.firstNotNullOfOrNull{h.memoryIds[it.id]}
                if(id!=null)openPhoto?.invoke(id)else if(point!=null)pick?.invoke(point.latitude,point.longitude)
            }
        })
        h.container.touched={controller?.stopFollowing()}
    }}
    DisposableEffect(handle,owner){
        val observer=LifecycleEventObserver{_,_->handle.sync(owner.lifecycle.currentState)}
        owner.lifecycle.addObserver(observer);handle.sync(owner.lifecycle.currentState)
        onDispose{owner.lifecycle.removeObserver(observer);controller?.detachTmap(handle.map);handle.close()}
    }
    LaunchedEffect(handle){
        handle.map.setSKTMapApiKey(appKey)
        delay(25000);if(!ready)failed=true
    }
    Column(modifier){
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE9EDF1)).testTag("live-map")){
            key(handle){AndroidView(factory={handle.container},update={
                it.interactive=interactive
                handle.select(latitude,longitude,showSelectionMarker);handle.photos(memories,memoryStyle);handle.updateMotion(position,route,controller)
            },modifier=Modifier.fillMaxSize())}
            if(!ready||failed)Surface(color=Color.White.copy(alpha=.96f),modifier=Modifier.align(Alignment.TopCenter).padding(12.dp)){
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(if(failed)"티맵을 불러오지 못했어요. 인터넷·무료 한도·키 설정을 확인하세요." else "티맵 지도 불러오는 중…",
                        color=Color(0xFF14171D),fontSize=12.sp,modifier=Modifier.weight(1f).testTag("map-load-status"))
                    if(failed)TextButton(onClick={attempt++}){Text("재시도")}
                }
            }
            if(showControls&&interactive)Column(Modifier.align(Alignment.TopEnd).padding(top=72.dp,end=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={if(ready)handle.map.mapZoomIn()},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).testTag("map-zoom-in")){Text("+",fontSize=24.sp)}
                Button(onClick={if(ready)handle.map.mapZoomOut()},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).testTag("map-zoom-out")){Text("−",fontSize=24.sp)}
            }
            if(ready&&!failed){
                if(showStatus)Text("TMAP 지도",fontSize=10.sp,color=Color(0xFF173B9F),modifier=Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.White).padding(6.dp).testTag("map-ready"))
                else Box(Modifier.size(1.dp).testTag("map-ready"))
                // The car map has its own photo button; there only the test hook stays so the label does not sit under its controls.
                if(memories.isNotEmpty()){
                    if(showStatus)Text("추억 ${memories.size}장",fontSize=12.sp,color=Color(0xFF173B9F),modifier=Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.White).padding(8.dp).testTag("map-memories"))
                    else Box(Modifier.size(1.dp).testTag("map-memories"))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=8.dp,vertical=5.dp),horizontalArrangement=Arrangement.SpaceBetween){
            Text("© TMAP MOBILITY",fontSize=10.sp,color=Color(0xFF263D72))
            Text("지도 이용약관",fontSize=10.sp,color=Color(0xFF263D72),modifier=Modifier.clickable{
                runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://tmapapi.tmapmobility.com/terms.html")))}
            })
        }
    }
}

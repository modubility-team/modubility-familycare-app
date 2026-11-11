package com.familycare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LocalMapGateway=staticCompositionLocalOf<MapGateway?>{null}

@Composable fun ProvideMapGateway(gateway:MapGateway?,content:@Composable ()->Unit){
    // One memory-only SDK credential per joined session, reused across map panels.
    val stable=remember(gateway){gateway}
    CompositionLocalProvider(LocalMapGateway provides stable,content=content)
}

@Composable fun PlaceMap(latitude:Double?=null,longitude:Double?=null,onPick:((Double,Double)->Unit)?=null,
    modifier:Modifier=Modifier,memories:List<MemoryPin> = emptyList(),onMemory:((String)->Unit)?=null,
    showSelectionMarker:Boolean=true,controller:MapViewportController?=null,showControls:Boolean=true,
    showStatus:Boolean=true,position:MapPosition?=null,route:List<MapRoutePoint> = emptyList(),interactive:Boolean=true,
    memoryStyle:MemoryPinStyle=MemoryPinStyle.Thumbnail){
    val gateway=LocalMapGateway.current
    var credential by remember(gateway){mutableStateOf<String?>(null)}
    var failure by remember(gateway){mutableStateOf(false)}
    var attempt by remember(gateway){mutableIntStateOf(0)}
    LaunchedEffect(gateway,attempt){
        failure=false
        if(gateway!=null){
            val result=withContext(Dispatchers.IO){runCatching{gateway.mapSdkCredential()}}
            result.onSuccess{credential=it}.onFailure{failure=true}
        }
    }
    val key=credential
    if(key!=null)TmapPlaceMap(key,latitude,longitude,onPick,modifier,memories,onMemory,showSelectionMarker,
        controller,showControls,showStatus,position,route,interactive,memoryStyle)
    else Box(modifier.background(Color(0xFFE9EDF1)).testTag("live-map"),contentAlignment=Alignment.Center){
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text(when{
                gateway==null->"차량 연결을 시작하면 티맵 지도가 표시돼요"
                failure->"티맵 연결을 확인해 주세요. PC 서버·무료 지도 설정을 확인한 뒤 다시 시도하세요."
                else->"티맵 지도 연결 중…"
            },color=Color(0xFF142435),modifier=Modifier.testTag("map-load-status"))
            if(failure&&interactive)TextButton(onClick={attempt++}){Text("재시도")}
        }
    }
}

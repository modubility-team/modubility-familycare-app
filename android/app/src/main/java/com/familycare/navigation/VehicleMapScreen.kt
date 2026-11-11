package com.familycare.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycare.ui.MapPlaces
import com.familycare.ui.PlaceMap
import com.familycare.ui.MemoryPhotos
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

@Composable internal fun VehicleMapScreen(care:CareRuntime,wide:Boolean,back:()->Unit,openNavigation:()->Unit,showBack:Boolean=true){
    @Suppress("UNUSED_VARIABLE") val revision=care.revision
    var latitude by rememberSaveable{mutableStateOf<Double?>(null)}
    var longitude by rememberSaveable{mutableStateOf<Double?>(null)}
    var name by rememberSaveable{mutableStateOf("")}
    var photoId by rememberSaveable{mutableStateOf("")}
    val photos=care.relay.memories
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        if(showBack)TextButton(onClick=back,modifier=Modifier.heightIn(min=56.dp)){Text("‹ 이동 홈",color=CareInk)}
        else Column{
            Text("어디로 갈까요?",fontSize=30.sp,fontWeight=FontWeight.Bold)
            Text(care.remainingMeters?.let{"남은 거리 %.1f km".format(it/1000.0)}?:"가족과 함께하는 내비게이션",color=CareMuted,fontSize=16.sp)
        }
        OutlinedButton(onClick=openNavigation,modifier=Modifier.heightIn(min=56.dp).testTag("open-system-navigation")){Text("차량 내비 열기",color=CareInk)}
    }
    PlaceMap(latitude,longitude,{lat,lon->latitude=lat;longitude=lon;name="지도에서 선택한 곳"},Modifier.fillMaxWidth().height(if(wide)500.dp else 340.dp),memories=photos.mapNotNull(MemoryPhotos::pin),onMemory={photoId=it})
    photos.find{it.optString("id")==photoId}?.let{photo->
        Text(photo.optString("title")+" · "+photo.optString("captured"),fontWeight=FontWeight.Bold)
        val bitmap=remember(photo.optString("thumbnail")){MemoryPhotos.bitmap(photo.optString("thumbnail"))}
        bitmap?.let{Image(it.asImageBitmap(),photo.optString("title"),Modifier.height(120.dp))}
    }
    Text(if(latitude==null)"기본 위치: 서울 42dot 주변 · 지도를 누르면 목적지를 선택합니다." else "선택 좌표: %.5f, %.5f".format(latitude,longitude),color=CareMuted,fontSize=14.sp)
    OutlinedTextField(name,{name=it.take(120)},label={Text("목적지 이름")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("map-place-name"))
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Button(onClick={care.navigateTo(name.trim(),latitude!!,longitude!!)},enabled=MapPlaces.valid(latitude,longitude)&&name.isNotBlank()&&!care.mapRequestPending,
            modifier=Modifier.weight(1f).heightIn(min=64.dp).testTag("map-send-route")){Text(if(care.mapRequestPending)"내비 응답 확인 중…" else "이곳으로 길 안내 요청",fontSize=18.sp)}
        OutlinedButton(onClick={latitude=MapPlaces.LATITUDE;longitude=MapPlaces.LONGITUDE;name="42dot"},modifier=Modifier.heightIn(min=64.dp).testTag("map-sample")){Text("42dot",color=CareInk)}
    }
    Text("선택한 목적지를 차량 내비에 전달합니다. 경로선·교통정보·회전 안내는 차량 내비에서 확인하세요. 현재 위치를 자동 추적하지 않습니다.",color=CareMuted,fontSize=14.sp)
}

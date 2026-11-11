package com.familycare.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** [prefill] and [address] come from the photo's own location when it has one (see MemoryPlaceFill). */
@Composable fun MemoryLocationPicker(latitude:Double?,longitude:Double?,label:String,gateway:MapGateway?,address:String="",prefill:PlacePrefill?=null,onSelect:(FoundPlace)->Unit){
 Column(Modifier.testTag("memory-location-picker"),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("추억 장소 검색",style=MaterialTheme.typography.titleMedium)
  Text(if(prefill!=null)"사진의 촬영 위치로 검색어와 근처 장소를 채웠어요. 다른 곳이면 목록에서 고르거나 다시 검색하세요." else "가게·관광지 이름이나 주소를 검색한 뒤 결과에서 선택하세요.")
  PlaceSearch(latitude?:MapPlaces.LATITUDE,longitude?:MapPlaces.LONGITUDE,gateway=gateway,showFacilities=false,prefill=prefill,onSelect=onSelect)
  Text(if(MapPlaces.valid(latitude,longitude))"선택한 장소: "+label.ifBlank{"사진의 촬영 위치"} else "검색 결과에서 장소를 선택해 주세요",modifier=Modifier.testTag("memory-selected-place"))
  if(address.isNotBlank()&&MapPlaces.valid(latitude,longitude))Text("주소: $address",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("memory-selected-address"))
 }
}

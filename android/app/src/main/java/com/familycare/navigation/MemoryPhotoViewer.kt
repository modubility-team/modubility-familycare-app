package com.familycare.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.familycare.ui.MemoryPhotos
import org.json.JSONObject

/**
 * Large view of a shared photo, opened from its map card while the car is not driving (the caller closes it when driving starts).
 * Shows the full photo once it has arrived (the small preview until then); previous/next go through the trip in the order taken.
 */
@Composable internal fun MemoryPhotoViewer(photos:List<JSONObject>,images:Map<String,String>,id:String,onSelect:(String)->Unit,
    onNeedImage:(String)->Unit,onLocate:(JSONObject)->Unit,onAll:()->Unit,onClose:()->Unit){
    val ordered=photos.sortedWith(compareBy<JSONObject>{it.optString("captured")}.thenBy{it.optString("id")})
    val index=ordered.indexOfFirst{it.optString("id")==id}
    if(index<0)return
    val photo=ordered[index]
    val full=images[id]
    LaunchedEffect(id,full==null){if(full==null)onNeedImage(id)}
    val bitmap=remember(id,full!=null){MemoryPhotos.bitmap(full?:photo.optString("thumbnail"))}
    val muted=Color(0xFF5B6573)
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.86f).fillMaxHeight(.88f).testTag("memory-viewer"),shape=RoundedCornerShape(16.dp),color=Color.White){
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(photo.optString("title"),fontSize=24.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(photo.optString("captured")+if(ordered.size>1)" · ${index+1} / ${ordered.size}" else "",fontSize=16.sp,color=muted)
                    }
                    TextButton(onClick=onClose,modifier=Modifier.heightIn(min=56.dp).testTag("memory-viewer-close")){Text("닫기",fontSize=18.sp)}
                }
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF1F3F5)),contentAlignment=Alignment.Center){
                    bitmap?.let{Image(it.asImageBitmap(),photo.optString("title"),Modifier.fillMaxSize().testTag("memory-viewer-photo"))}
                    if(full==null)Text("원본 사진 불러오는 중…",fontSize=14.sp,color=muted,
                        modifier=Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Color.White).padding(horizontal=10.dp,vertical=4.dp))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                    if(ordered.size>1){
                        OutlinedButton(onClick={onSelect(ordered[(index-1+ordered.size)%ordered.size].optString("id"))},
                            modifier=Modifier.heightIn(min=60.dp).testTag("memory-viewer-previous")){Text("‹ 이전",fontSize=18.sp)}
                        OutlinedButton(onClick={onSelect(ordered[(index+1)%ordered.size].optString("id"))},
                            modifier=Modifier.heightIn(min=60.dp).testTag("memory-viewer-next")){Text("다음 ›",fontSize=18.sp)}
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick={onLocate(photo)},modifier=Modifier.heightIn(min=60.dp).testTag("memory-viewer-locate")){Text("지도에서 위치 보기",fontSize=18.sp)}
                    Button(onClick=onAll,modifier=Modifier.heightIn(min=60.dp).testTag("memory-viewer-all")){Text("추억 전체 보기",fontSize=18.sp)}
                }
            }
        }
    }
}

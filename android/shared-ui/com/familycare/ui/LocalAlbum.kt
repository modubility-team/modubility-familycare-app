package com.familycare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Explicit local copy; server expiry never silently persists another person's photos. */
@Composable fun LocalAlbum(sharedDetail:JSONObject?,journeys:List<JSONObject>){
    val context=LocalContext.current
    val folder=remember{File(context.filesDir,"family-album").apply{mkdirs()}}
    fun load()=folder.listFiles()?.filter{it.name.matches(Regex("[a-f0-9]{64}\\.json"))&&it.length()<=400000}?.sortedByDescending{it.lastModified()}?.take(30)?.mapNotNull{runCatching{JSONObject(it.readText())}.getOrNull()}?:emptyList()
    var entries by remember{mutableStateOf(load())};var selected by remember{mutableStateOf<JSONObject?>(null)};var text by remember{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("이 기기의 여행 앨범",style=MaterialTheme.typography.titleLarge)
        Text("공유 사진을 열고 저장하면 연결 종료 후에도 이 기기에 남아요. 최근 30장까지 저장하며 앱 데이터 삭제 시 지워져요.")
        Button(onClick={sharedDetail?.let{photo->runCatching{
            check(photo.has("image")&&photo.toString().length<=400000)
            val id=MessageDigest.getInstance("SHA-256").digest((photo.optString("id")+photo.optString("captured")).toByteArray()).joinToString(""){"%02x".format(it)}
            File(folder,"$id.json").writeText(photo.toString());folder.listFiles()?.sortedByDescending{it.lastModified()}?.drop(30)?.forEach{it.delete()};entries=load();text="사진을 이 기기에 저장했어요"
        }.onFailure{text="사진을 다시 열어 저장하세요"}}},enabled=sharedDetail?.has("image")==true){Text("지금 연 사진을 앨범에 저장")}
        MemoryGallery(entries,{id->folder.listFiles()?.forEach{f->if(runCatching{JSONObject(f.readText()).optString("id")==id}.getOrDefault(false))f.delete()};entries=load();selected=null},selected,{id->selected=entries.firstOrNull{it.optString("id")==id}},deleteLabel="이 기기의 앨범에서 삭제")
        if(text.isNotBlank())Text(text)
        selected?.let{photo->
            val captured=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.KOREA).apply{isLenient=false}.parse(photo.optString("captured"))?.time}.getOrNull()
            val matched=journeys.filter{j->val end=j.optLong("endedAt").takeIf{it>0}?:System.currentTimeMillis();captured!=null&&captured in (j.optLong("startedAt")-60000)..end&&nearJourney(photo,j)}
            Text(if(matched.isEmpty())"촬영 시간·위치가 일치하는 이동 기록이 없어요." else "촬영 시간·위치로 연결된 여행: "+matched.joinToString{journeyTime(it.optLong("startedAt"))})
        }
    }
}
private fun nearJourney(photo:JSONObject,journey:JSONObject):Boolean{
    val lat=photo.optDouble("latitude");val lon=photo.optDouble("longitude");if(!MapPlaces.valid(lat,lon))return false
    val a=journey.optJSONArray("points")?:return false
    return (0 until a.length()).any{i->val p=a.getJSONObject(i);val dy=(p.optDouble("lat")-lat)*111195;val dx=(p.optDouble("lon")-lon)*111195*kotlin.math.cos(Math.toRadians(lat));kotlin.math.hypot(dx,dy)<=250}
}

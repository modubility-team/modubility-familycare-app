package com.familycare.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** [image] is the full photo once the car has fetched it (large map cards); [captured] orders photos taken at one place. */
data class MemoryPin(val id:String,val title:String,val latitude:Double,val longitude:Double,val thumbnail:String="",val image:String="",val captured:String="")
data class PhotoDraft(val image:String,val thumbnail:String,val captured:String,val latitude:Double?,val longitude:Double?)

object MemoryPhotos {
    val dateFormat:DateTimeFormatter=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    fun validTime(text:String)=runCatching{LocalDateTime.parse(text,dateFormat)}.isSuccess
    /** The photo's own capture time, or now when the photo has none. */
    fun shareTime(captured:String,now:LocalDateTime=LocalDateTime.now())=if(validTime(captured))captured else now.format(dateFormat)
    fun bitmap(encoded:String):Bitmap?=runCatching{
        require(encoded.length<=350000)
        val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..2048&&bounds.outHeight in 1..2048)
        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
    }.getOrNull()
    fun pin(o:JSONObject):MemoryPin?=runCatching{
        val lat=o.getDouble("latitude");val lon=o.getDouble("longitude")
        require(MapPlaces.valid(lat,lon));MemoryPin(o.getString("id"),o.getString("title"),lat,lon,o.optString("thumbnail"),captured=o.optString("captured"))
    }.getOrNull()
    /** Map marker for a shared photo; [encoded] is the thumbnail, or the full photo for a large card. */
    fun marker(encoded:String,density:Float,style:MemoryPinStyle=MemoryPinStyle.Thumbnail,title:String="",count:Int=1):Bitmap?=
        MemoryPinIcons.fromEncoded(encoded,density,style,title,count)
    /** Reads only the document selected by the user. Re-encoding removes unrelated EXIF. */
    fun read(context:Context,uri:Uri):PhotoDraft {
        val resolver=context.contentResolver
        val original=if(Build.VERSION.SDK_INT>=29&&context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED)
            runCatching{MediaStore.setRequireOriginal(MediaStore.getMediaUri(context,uri)?:uri)}.getOrDefault(uri) else uri
        val bytes=(runCatching{resolver.openInputStream(original)}.getOrNull()?:resolver.openInputStream(uri))!!.use{input->
            val out=ByteArrayOutputStream();val buf=ByteArray(8192)
            while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=25*1024*1024){"25MB 이하 사진을 선택하세요"};out.write(buf,0,n)}
            out.toByteArray()
        }
        val exif=runCatching{ExifInterface(bytes.inputStream())}.getOrNull()
        val coords=FloatArray(2)
        val gps=exif?.getLatLong(coords)==true&&MapPlaces.valid(coords[0].toDouble(),coords[1].toDouble())
        val time=runCatching{LocalDateTime.parse(exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?:exif?.getAttribute(ExifInterface.TAG_DATETIME),DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).format(dateFormat)}.getOrDefault("")
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=200_000_000){"이 사진 형식은 읽을 수 없어요"}
        val options=BitmapFactory.Options().apply{inSampleSize=1};while(maxOf(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2
        val source=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:error("사진을 읽지 못했어요")
        val matrix=Matrix()
        when(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)){
            2->matrix.setScale(-1f,1f);3->matrix.setRotate(180f);4->matrix.setScale(1f,-1f)
            5->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
            6->matrix.setRotate(90f);7->{matrix.setRotate(270f);matrix.postScale(-1f,1f)};8->matrix.setRotate(270f)
        }
        val image=Bitmap.createBitmap(source,0,0,source.width,source.height,matrix,true)
        fun encode(bitmap:Bitmap,maxBytes:Int):String {
            var quality=85;var data:ByteArray
            do{val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out);data=out.toByteArray();quality-=10}while(data.size>maxBytes&&quality>=15)
            require(data.size<=maxBytes){"사진 크기를 줄여서 다시 선택하세요"}
            return Base64.encodeToString(data,Base64.NO_WRAP)
        }
        val ratio=96f/maxOf(image.width,image.height)
        val thumb=Bitmap.createScaledBitmap(image,(image.width*ratio).toInt().coerceAtLeast(1),(image.height*ratio).toInt().coerceAtLeast(1),true)
        return PhotoDraft(encode(image,200000),encode(thumb,6000),time,if(gps)coords[0].toDouble() else null,if(gps)coords[1].toDouble() else null).also{
            if(thumb!==image)thumb.recycle();if(image!==source)image.recycle();source.recycle()
        }
    }
}

/** [blocked] says why sharing is off (connection etc.); shown under the share button. */
@Composable fun MemoryComposer(enabled:Boolean,onShare:(JSONObject)->Unit,gateway:MapGateway?=null,blocked:String=""){
    val context=LocalContext.current
    var uri by remember{mutableStateOf<Uri?>(null)}
    var draft by remember{mutableStateOf<PhotoDraft?>(null)}
    var placeLabel by rememberSaveable{mutableStateOf("")}
    var address by rememberSaveable{mutableStateOf("")}
    var title by rememberSaveable{mutableStateOf("")}
    // True while the title is the app's own suggestion; typing a title turns it off.
    var titleAuto by rememberSaveable{mutableStateOf(true)}
    var prefill by remember{mutableStateOf<PlacePrefill?>(null)}
    var locating by remember{mutableStateOf(false)}
    var captured by rememberSaveable{mutableStateOf("")}
    var lat by rememberSaveable{mutableStateOf<Double?>(null)}
    var lon by rememberSaveable{mutableStateOf<Double?>(null)}
    var info by remember{mutableStateOf("")}
    var consent by remember{mutableStateOf(false)}
    var reading by remember{mutableStateOf(false)}
    var draftId by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri=it}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){picker.launch(arrayOf("image/*"))}
    LaunchedEffect(uri){uri?.let{selected->
        reading=true;draft=null;address="";prefill=null;locating=false
        val result=withContext(Dispatchers.IO){runCatching{MemoryPhotos.read(context,selected)}}
        result.onSuccess{draft=it;draftId=UUID.randomUUID().toString();lat=it.latitude;lon=it.longitude;placeLabel=if(lat!=null)MemoryPlaceFill.PHOTO_LOCATION else "";captured=it.captured;title=MemoryPlaceFill.DEFAULT_TITLE;titleAuto=true
            info=if(lat!=null)"촬영 위치를 찾았어요. 날짜와 장소를 확인해 주세요." else "사진에 접근 가능한 위치 정보가 없어요. 아래에서 장소 이름이나 주소를 검색해 주세요."}
            .onFailure{info=it.message?:"사진을 읽지 못했어요"}
        reading=false
        // From the photo's own GPS: address, nearby place, search box and title (TMAP through the PC server).
        val photo=result.getOrNull();val photoLat=photo?.latitude;val photoLon=photo?.longitude;val client=gateway
        if(photoLat!=null&&photoLon!=null&&client!=null&&client.connected){
            locating=true;info="촬영 위치를 찾았어요. 주소와 장소 이름을 찾는 중…"
            val found=withContext(Dispatchers.IO){runCatching{MemoryPlaceFill.parse(client.call("memory-place",MemoryPlaceFill.request(photoLat,photoLon)))}}
            locating=false
            if(MemoryPlaceFill.stillPhotoLocation(lat,lon,photoLat,photoLon))found.onSuccess{g->
                address=g.address;placeLabel=MemoryPlaceFill.label(g);title=MemoryPlaceFill.title(title,titleAuto,g.place?.name)
                prefill=PlacePrefill(g.query,g.places,MemoryPlaceFill.status(g));info=MemoryPlaceFill.info(g)
            }.onFailure{info="촬영 위치를 찾았어요. 주소 자동 채우기는 하지 못했어요("+(it.message?.take(80)?:"연결 확인")+"). 아래에서 검색해 주세요."}
        }else if(photoLat!=null)info="촬영 위치를 찾았어요. 차량에 연결되어 있으면 주소와 장소 이름도 자동으로 채워요."
    }}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("사진으로 남기는 오늘")
        Text("선택한 사진의 촬영 시간·위치를 읽어 지도에 붙이고, 그 위치의 주소와 장소 이름을 채웁니다. 공유한 사진·장소·시간은 연결된 가족과 차량에 보이며, 연결 종료 시 서버에서 삭제됩니다.")
        OutlinedButton(onClick={consent=true},enabled=!reading,modifier=Modifier.heightIn(min=52.dp).testTag("pick-memory")){Text(if(reading)"사진 읽는 중…" else if(locating)"주소 찾는 중… (다른 사진 선택)" else "추억 사진 선택")}
        if(info.isNotEmpty())Text(info,modifier=Modifier.testTag("photo-metadata-status"))
        draft?.let{photo->
            val bitmap=remember(photo.image){MemoryPhotos.bitmap(photo.image)}
            bitmap?.let{Image(it.asImageBitmap(),"선택한 추억 사진",Modifier.fillMaxWidth().height(180.dp))}
            OutlinedTextField(title,{title=it.take(80);titleAuto=false},label={Text("사진 제목")},modifier=Modifier.fillMaxWidth().testTag("memory-title"))
            // The capture time is read from the photo; without one the photo keeps the time it is shared (no typing needed).
            Text(if(MemoryPhotos.validTime(captured))"촬영 시간: $captured (사진 정보)" else "사진에 촬영 시간이 없어 공유하는 시각으로 남겨요.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("memory-time"))
            MemoryLocationPicker(lat,lon,placeLabel,gateway,address,prefill){place->lat=place.latitude;lon=place.longitude;placeLabel=place.name
                address=MemoryPlaceFill.addressOf(place);title=MemoryPlaceFill.title(title,titleAuto,place.name)}
            val valid=title.isNotBlank()&&MapPlaces.valid(lat,lon)
            Button(onClick={onShare(JSONObject().put("id",draftId).put("title",title.trim()).put("captured",MemoryPhotos.shareTime(captured)).put("latitude",lat).put("longitude",lon).put("image",photo.image).put("thumbnail",photo.thumbnail))},enabled=enabled&&valid,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("share-memory")){Text("이 사진을 가족 지도에 공유")}
            // Say why the button is off instead of leaving it silently grey.
            val why=when{!enabled->blocked.ifBlank{"차량에 연결된 뒤 공유할 수 있어요"};title.isBlank()->"사진 제목을 입력해 주세요";!MapPlaces.valid(lat,lon)->"검색 결과에서 장소를 하나 골라 주세요";else->""}
            if(why.isNotBlank())Text(why,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("share-memory-blocked"))
        }
    }
    if(consent)AlertDialog(onDismissRequest={consent=false},title={Text("촬영 장소를 지도에 표시할까요?")},text={Text("선택한 사진의 촬영 위치와 시간을 사용합니다. 촬영 위치의 주소와 장소 이름을 찾기 위해 그 좌표를 PC 서버를 거쳐 TMAP에 보내요(저장하지 않음). 현재 휴대폰 위치를 추적하지 않아요. 위치 정보가 없거나 권한을 거절하면 장소 이름이나 주소를 검색해 선택할 수 있어요.")},confirmButton={TextButton(onClick={
        consent=false
        if(Build.VERSION.SDK_INT>=29&&context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        else picker.launch(arrayOf("image/*"))
    }){Text("사진 선택")}},dismissButton={TextButton(onClick={consent=false}){Text("취소")}})
}

@Composable fun MemoryGallery(memories:List<JSONObject>,onDelete:((String)->Unit)?=null,detail:JSONObject?=null,onOpen:((String)->Unit)?=null,deleteLabel:String="이 사진 공유 삭제"){
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    var day by rememberSaveable{mutableStateOf("")}
    val days=memories.map{it.optString("captured").take(10)}.distinct().sortedDescending()
    val shown=memories.filter{day.isBlank()||it.optString("captured").startsWith(day)}
    val pins=shown.mapNotNull(MemoryPhotos::pin)
    fun select(photo:JSONObject?){selected=photo;photo?.optString("id")?.let{onOpen?.invoke(it)}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(days.isNotEmpty()){
            Text("날짜별 여행 지도")
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                TextButton(onClick={day="";selected=null}){Text("전체")}
                days.take(3).forEach{d->TextButton(onClick={day=d;selected=null}){Text(d.takeLast(5))}}
            }
        }
        PlaceMap(selected?.optDouble("latitude")?:pins.firstOrNull()?.latitude,selected?.optDouble("longitude")?:pins.firstOrNull()?.longitude,modifier=Modifier.fillMaxWidth().height(300.dp),memories=pins,onMemory={id->select(memories.find{it.optString("id")==id})},showSelectionMarker=false)
        if(shown.isEmpty())Text("공유한 사진이 여기에 지도와 함께 모여요.")
        shown.forEach{photo->
            OutlinedButton(onClick={select(photo)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){
                val thumb=remember(photo.optString("thumbnail")){MemoryPhotos.bitmap(photo.optString("thumbnail"))}
                thumb?.let{Image(it.asImageBitmap(),null,Modifier.size(56.dp));Spacer(Modifier.width(10.dp))}
                Column(Modifier.weight(1f)){Text(photo.optString("title"));Text(photo.optString("captured"))}
            }
        }
        selected?.takeIf{p->memories.any{it.optString("id")==p.optString("id")}}?.let{photo->
            Text(photo.optString("title")+" · "+photo.optString("captured"))
            val encoded=detail?.takeIf{it.optString("id")==photo.optString("id")}?.optString("image")?:photo.optString("thumbnail")
            val thumb=remember(encoded){MemoryPhotos.bitmap(encoded)}
            thumb?.let{Image(it.asImageBitmap(),photo.optString("title"),Modifier.fillMaxWidth().height(240.dp).testTag("memory-preview"))}
            onDelete?.let{remove->TextButton(onClick={remove(photo.getString("id"));selected=null}){Text(deleteLabel)}}
        }
    }
}

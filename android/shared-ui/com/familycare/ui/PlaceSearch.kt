@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.familycare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FoundPlace(val name:String,val latitude:Double,val longitude:Double,val detail:String)
/** Search text and results shown without typing (e.g. places near where a memory photo was taken). */
data class PlacePrefill(val query:String,val places:List<FoundPlace>,val status:String)
object PlaceSearchClient {
    private var lastRequest=0L
    val filters=mapOf("주차장" to "[amenity=parking]","주유소" to "[amenity=fuel]","전기차 충전소" to "[amenity=charging_station]","휴게소" to "[highway=services]","화장실" to "[amenity=toilets]","기저귀 교환" to "[changing_table=yes]","수유" to "[baby_feeding=yes]","반려견 공원" to "[leisure=dog_park]","무장애 화장실" to "[amenity=toilets][wheelchair=yes]")
    @Synchronized fun search(query:String,facility:String,latitude:Double,longitude:Double):List<FoundPlace>{
        require(MapPlaces.valid(latitude,longitude)&&query.length<=120&&(facility.isBlank()||facility in filters))
        val wait=1100-(android.os.SystemClock.elapsedRealtime()-lastRequest);if(wait>0)Thread.sleep(wait)
        lastRequest=android.os.SystemClock.elapsedRealtime()
        val url=if(facility.isBlank())"https://photon.komoot.io/api/?limit=8&q="+URLEncoder.encode(query,"UTF-8")+"&lat=$latitude&lon=$longitude"
            else "https://overpass-api.de/api/interpreter?data="+URLEncoder.encode("[out:json][timeout:12];nwr(around:5000,$latitude,$longitude)${filters[facility]};out center 60;","UTF-8")
        val connection=URL(url).openConnection() as HttpURLConnection
        val data=try{connection.connectTimeout=8000;connection.readTimeout=15000;connection.instanceFollowRedirects=false;connection.setRequestProperty("User-Agent","FamilyCarePrototype/0.11 (manual place search)")
            check(connection.responseCode==200)
            val bytes=connection.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;check(out.size()+n<=1_000_000);out.write(b,0,n)};out.toByteArray()}
            JSONObject(bytes.toString(Charsets.UTF_8))
        }finally{connection.disconnect()}
        val results=mutableListOf<FoundPlace>()
        if(facility.isBlank()){
            val features=data.optJSONArray("features")
            if(features!=null)(0 until features.length()).forEach{i->val f=features.getJSONObject(i);val p=f.optJSONObject("properties")?:JSONObject();val c=f.optJSONObject("geometry")?.optJSONArray("coordinates")
                if(c!=null&&MapPlaces.valid(c.optDouble(1),c.optDouble(0)))results.add(FoundPlace(p.optString("name").ifBlank{p.optString("street").ifBlank{"검색한 주소"}},c.getDouble(1),c.getDouble(0),listOf("city","district","street","housenumber").map{p.optString(it)}.filter{it.isNotBlank()}.distinct().joinToString(" ")))
            }
        }else{
            val elements=data.optJSONArray("elements")
            if(elements!=null)(0 until elements.length()).forEach{i->val e=elements.getJSONObject(i);val p=e.optJSONObject("tags")?:JSONObject();val c=e.optJSONObject("center")?:e
                if(MapPlaces.valid(c.optDouble("lat"),c.optDouble("lon")))results.add(FoundPlace(p.optString("name:ko").ifBlank{p.optString("name").ifBlank{facility}},c.getDouble("lat"),c.getDouble("lon"),"$facility · OSM 등록 정보 · 운영 여부 직접 확인"))
            }
        }
        val ordered=results.sortedBy{(it.latitude-latitude)*(it.latitude-latitude)+(it.longitude-longitude)*(it.longitude-longitude)}.take(12)
        return ordered
    }
}

@Composable fun PlaceSearch(latitude:Double=MapPlaces.LATITUDE,longitude:Double=MapPlaces.LONGITUDE,
    gateway:MapGateway?=null,route:org.json.JSONArray?=null,initialFacility:String="",showFacilities:Boolean=true,prefill:PlacePrefill?=null,
    /** Rest stops on the whole remaining [route] ahead (the server spreads the search over it). */
    alongRoute:Boolean=false,
    /** Search once as soon as the screen opens (with [initialFacility]). */
    autoSearch:Boolean=false,onSelect:(FoundPlace)->Unit){
    var query by remember{mutableStateOf("")};var facility by remember{mutableStateOf(initialFacility)}
    var tmap by remember{mutableStateOf(true)}
    var busy by remember{mutableStateOf(false)};var result by remember{mutableStateOf(emptyList<FoundPlace>())};var status by remember{mutableStateOf("")}
    var preview by remember{mutableStateOf("")}
    var resultTime by remember{mutableLongStateOf(0L)}
    LaunchedEffect(resultTime){
        if(resultTime>0){kotlinx.coroutines.delay(3_600_000);result=emptyList();preview="";status="검색 결과가 만료됐어요. 다시 검색해 주세요"}
    }
    // Filled in from outside (TMAP results); the user can still edit the text and search again.
    LaunchedEffect(prefill){prefill?.let{query=it.query.take(120);facility="";tmap=true;result=it.places;preview="";status=it.status
        if(it.places.isNotEmpty())resultTime=android.os.SystemClock.elapsedRealtime()}}
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val search:()->Unit={
        if(!busy&&(facility.isNotBlank()||query.isNotBlank())){
            focus.clearFocus()
            busy=true;result=emptyList();preview="";status="검색 중"
            val selectedTmap=tmap;val q=query.trim();val f=facility;val client=gateway
            scope.launch{
                val r=withContext(Dispatchers.IO){runCatching{
                    if(!selectedTmap)PlaceSearchClient.search(q,f,latitude,longitude)
                    else{
                        check(client!=null){"먼저 차량 연결을 시작해 주세요"}
                        val body=JSONObject().put("query",q).put("facility",f).put("center",org.json.JSONArray(listOf(longitude,latitude))).put("consent",true)
                        if(route!=null)body.put("route",route)
                        if(alongRoute&&route!=null)body.put("alongRoute",true)
                        val places=client.call("maps-search",body).getJSONArray("places")
                        (0 until places.length()).map{places.getJSONObject(it).let{p->FoundPlace(p.getString("name"),p.getDouble("latitude"),p.getDouble("longitude"),p.getString("detail"))}}
                    }
                }}
                busy=false
                r.onSuccess{resultTime=android.os.SystemClock.elapsedRealtime();result=it;status=if(it.isEmpty())"검색 결과가 없어요. 시설이 없다는 뜻은 아니에요." else "${it.size}곳 · ${if(selectedTmap)"TMAP · 실제 우회 시간은 경로 반영 후 확인" else "OpenStreetMap contributors"}"}
                    .onFailure{status=it.message?.take(160)?:"검색 연결 실패 · 잠시 후 다시 시도하세요"}
            }
        }
    }
    LaunchedEffect(autoSearch&&gateway!=null&&(route?.length()?:0)>1){if(autoSearch&&gateway!=null&&(route?.length()?:0)>1)search()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(query,{query=it.take(120);facility="";result=emptyList();preview="";status=""},enabled=!busy,singleLine=true,label={Text("주소·장소 이름 검색")},
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),
                modifier=Modifier.weight(1f).testTag("place-search-query"))
            Button(onClick=search,enabled=!busy&&(facility.isNotBlank()||query.isNotBlank()),modifier=Modifier.heightIn(min=56.dp).testTag("place-search-submit")){
                Text(if(busy)"검색 중" else "검색")
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(tmap,{tmap=true;result=emptyList();preview="";status=""},enabled=!busy,label={Text("TMAP")})
            FilterChip(!tmap,{tmap=false;result=emptyList();preview="";status=""},enabled=!busy,label={Text("공개 검색")})
        }
        if(showFacilities)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){(listOf("")+PlaceSearchClient.filters.keys).forEach{label->FilterChip(facility==label,{facility=label;result=emptyList();preview="";status=""},enabled=!busy,label={Text(label.ifBlank{"이름 검색"})})}}
        Text(if(tmap)"검색하면 검색어·기준 위치를 PC 서버를 통해 TMAP에 전송합니다. 경로가 공유되어 있으면 앞쪽 시설도 찾습니다. 수유·반려견 등은 검색어 일치 후보이며 시설 보유를 보장하지 않습니다." else "검색하면 검색어·기준 위치를 Photon 또는 Overpass에 전송합니다. 편의시설은 기준 위치 5 km 이내 OSM 등록 정보입니다.",style=MaterialTheme.typography.bodySmall)
        if(status.isNotBlank())Text(status)
        if(preview.isNotBlank())Text(preview,style=MaterialTheme.typography.bodySmall)
        if(tmap&&(route?.length()?:0)>1)Text("우회 시간 확인은 출발지·목적지·후보지를 TMAP에 전송해 비교합니다. 기존 경유지는 제외한 비교이며 실제 적용은 운전자 승인 후 진행해요.",style=MaterialTheme.typography.bodySmall)
        result.forEach{p->
            OutlinedButton(onClick={onSelect(p);result=emptyList();preview="";status="${p.name} 선택됨"},modifier=Modifier.fillMaxWidth().testTag("place-result-"+p.name),enabled=!busy){Column{Text(p.name);Text(p.detail,style=MaterialTheme.typography.bodySmall)}}
            if(tmap&&(route?.length()?:0)>1&&gateway!=null)TextButton(enabled=!busy,onClick={
                busy=true;preview="${p.name} 경유 시간 비교 중"
                val body=JSONObject().put("consent",true).put("start",org.json.JSONArray(listOf(longitude,latitude)))
                    .put("end",route!!.getJSONArray(route.length()-1)).put("via",org.json.JSONArray(listOf(p.longitude,p.latitude)))
                scope.launch{
                    val r=withContext(Dispatchers.IO){runCatching{gateway.call("maps-preview",body)}}
                    busy=false
                    preview=r.fold({"${p.name} · 예상 ${"%+.1f".format(it.getDouble("extraSeconds")/60)}분 · 승인 시 재계산"},{it.message?:"우회 시간을 확인하지 못했어요"})
                }
            }){Text("이곳 경유 시 우회 시간 확인")}
        }
    }
}

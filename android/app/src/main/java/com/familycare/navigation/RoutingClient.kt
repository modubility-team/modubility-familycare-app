package com.familycare.navigation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One request per user action or bounded reroute. No GPS history, identity or telemetry uploads. */
object RoutingClient {
    const val PROVIDER="OSRM · FOSSGIS / OpenStreetMap"
    const val POLICY="https://routing.openstreetmap.de/about.html"
    const val ENDPOINT="https://router.project-osrm.org/route/v1/driving/"
    fun fetch(start:GeoPoint,end:GeoPoint,name:String,via:GeoPoint?=null):RoadRoute{
        require(start.valid()&&end.valid()&&(via==null||via.valid()))
        val coordinates=listOfNotNull(start,via,end).joinToString(";"){String.format(Locale.US,"%.6f,%.6f",it.longitude,it.latitude)}
        val connection=URL("$ENDPOINT$coordinates?overview=full&geometries=geojson&steps=true&alternatives=false&generate_hints=false").openConnection() as HttpURLConnection
        try{
            connection.connectTimeout=12000;connection.readTimeout=20000
            connection.setRequestProperty("User-Agent","FamilyCare/0.10 (non-commercial local competition prototype)")
            connection.setRequestProperty("Accept","application/json")
            check(connection.responseCode==200){"경로 서버 응답 오류 (${connection.responseCode})"}
            val bytes=connection.inputStream.use{input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(out.size()<=4_000_000){val count=input.read(buffer);if(count<0)break;out.write(buffer,0,count)}
                out.toByteArray()
            }
            check(bytes.size<=4_000_000){"경로가 너무 길어요. 가까운 목적지를 선택해 주세요"}
            return parse(String(bytes,Charsets.UTF_8),name).also{
                check(it.points.first().distance(start)<=200){"현재 위치 근처의 자동차 도로를 찾지 못했어요"}
                check(it.points.last().distance(end)<=200){"목적지 근처의 자동차 도로를 찾지 못했어요"}
                check(via==null||it.project(via).offRoute<=200){"경유지 근처의 자동차 도로를 찾지 못했어요"}
            }
        }finally{connection.disconnect()}
    }
    internal fun parse(text:String,name:String):RoadRoute{
        val response=JSONObject(text)
        check(response.optString("code")=="Ok"){"두 지점을 잇는 자동차 경로가 없어요"}
        val data=response.getJSONArray("routes").getJSONObject(0)
        val coordinates=data.getJSONObject("geometry").getJSONArray("coordinates")
        check(coordinates.length() in 2..100000){"경로 좌표를 확인하지 못했어요"}
        val points=(0 until coordinates.length()).map{coordinates.getJSONArray(it).let{p->GeoPoint(p.getDouble(1),p.getDouble(0))}}
        val segments=data.optJSONArray("segments")
        val times=if(segments==null)emptyList()else (0 until segments.length()).map{segments.getJSONArray(it).let{t->RouteTime(t.getInt(0),t.getDouble(1))}}
        val roadData=data.optJSONArray("roads")?:org.json.JSONArray()
        val roads=(0 until roadData.length()).mapNotNull{roadData.getJSONObject(it).let{r->
            val a=r.optInt("start",-1);val b=r.optInt("end",-1)
            if(a in points.indices&&b in points.indices&&b>a)RouteRoad(a,b,r.optString("name"))else null}}
        val trafficData=data.optJSONArray("traffic")?:org.json.JSONArray()
        val traffic=(0 until trafficData.length()).mapNotNull{trafficData.getJSONObject(it).let{r->
            val a=r.optInt("start",-1);val b=r.optInt("end",-1);val state=r.optInt("state")
            if(a in points.indices&&b in points.indices&&b>a&&state in 1..4)RouteTraffic(a,b,state)else null}}
        val base=RoadRoute(points,emptyList(),data.getDouble("duration"),name,response.optString("provider","OSRM / OpenStreetMap"),response.optLong("generatedAt"),times,
            roads,traffic,data.optInt("tollFare",-1).takeIf{it>=0},RouteOption.from(data.optInt("routeOption")))
        val turns=mutableListOf<RouteTurn>();val services=mutableListOf<RouteTurn>();var previous=0.0
        val legs=data.getJSONArray("legs")
        for(i in 0 until legs.length()){
            val steps=legs.getJSONObject(i).getJSONArray("steps")
            for(j in 0 until steps.length()){
                val step=steps.getJSONObject(j);val maneuver=step.getJSONObject("maneuver")
                val p=maneuver.getJSONArray("location");val point=GeoPoint(p.getDouble(1),p.getDouble(0))
                val along=base.project(point,previous).along.coerceAtLeast(previous);previous=along
                val type=maneuver.getString("type");val modifier=maneuver.optString("modifier")
                val road=step.optString("name").ifBlank{step.optString("ref")}
                if(type=="service"){services.add(RouteTurn(along,step.optString("instruction"),"↑",road));continue}
                val (instruction,symbol)=when{
                    type=="arrive"->(if(i==legs.length()-1)"목적지에 도착합니다" else "경유지를 통과합니다") to "●"
                    type=="depart"->(if(i==0)"출발합니다" else "계속 이동합니다") to "↑"
                    type.contains("roundabout")||type=="rotary"->"회전교차로 ${maneuver.optInt("exit",1)}번째 출구" to "↻"
                    modifier=="uturn"->"유턴하세요" to "↶"
                    modifier.contains("left")->(if(type=="fork")"왼쪽 갈림길로 진입하세요" else "좌회전하세요") to "←"
                    modifier.contains("right")->(if(type=="fork")"오른쪽 갈림길로 진입하세요" else "우회전하세요") to "→"
                    type=="merge"->"합류하세요" to "↗"
                    else->"직진하세요" to "↑"
                }
                turns.add(RouteTurn(along,step.optString("instruction").ifBlank{instruction},symbol,road))
            }
        }
        return base.copy(turns=turns,restStops=services)
    }
}

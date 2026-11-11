package com.familycare.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A session-scoped relay client. Join tokens and runtime SDK credentials stay in memory; no APK key constant. */
class MapGateway(private val origin:String, private val session:String, private val token:String){
    private var sdkCredential:String?=null
    @Synchronized fun mapSdkCredential():String{
        sdkCredential?.let{return it}
        return call("maps-sdk-config",JSONObject().put("consent",true)).getString("appKey").also{sdkCredential=it}
    }
    val connected get()=session.isNotBlank()&&token.isNotBlank()
    // Stable identity across recompositions; credentials never appear in toString().
    override fun equals(other:Any?)=other is MapGateway&&origin==other.origin&&session==other.session&&token==other.token
    override fun hashCode()=31*(31*origin.hashCode()+session.hashCode())+token.hashCode()
    fun call(action:String, body:JSONObject):JSONObject{
        require(action in listOf("voice-interpret","music-start","music-frame","music-pull","music-command","pickup-location","pickup-eta","memory-place","maps-compare","maps-status","maps-search","maps-route","maps-preview","maps-sdk-config","camera-status","camera-configure","camera-frame","camera-check"))
        check(session.isNotBlank()&&token.isNotBlank()){ "먼저 차량 연결을 시작해 주세요" }
        val connection=URL("$origin/api/sessions/$session/$action").openConnection() as HttpURLConnection
        try{
            connection.connectTimeout=4000;connection.readTimeout=if(action.startsWith("music-"))4000 else if(action=="voice-interpret")16000 else if(action=="maps-compare")90000 else 45000;connection.instanceFollowRedirects=false
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json")
            connection.setRequestProperty("Authorization","Bearer $token")
            connection.outputStream.use{it.write(body.put("provider","tmap").toString().toByteArray(Charsets.UTF_8))}
            val code=connection.responseCode
            val bytes=(if(code in 200..299)connection.inputStream else connection.errorStream)?.use{input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=(if(action=="maps-compare")16_000_000 else 4_000_000)){"검색 결과가 너무 커요"};out.write(buffer,0,n)}
                out.toByteArray()
            }?:byteArrayOf()
            val response=runCatching{JSONObject(bytes.toString(Charsets.UTF_8))}.getOrElse{error("PC 서버를 최신 버전으로 다시 실행하세요")}
            check(code in 200..299){response.optString("error","TMAP 연결을 확인하세요")}
            return response
        }catch(error:java.net.SocketTimeoutException){
            throw IllegalStateException("검색 시간이 지났어요. PC 서버·인터넷 연결을 확인하세요")
        }catch(error:java.io.IOException){
            throw IllegalStateException("PC 서버에 연결할 수 없어요. 같은 Wi-Fi와 연결 상태를 확인하세요")
        }finally{connection.disconnect()}
    }
}

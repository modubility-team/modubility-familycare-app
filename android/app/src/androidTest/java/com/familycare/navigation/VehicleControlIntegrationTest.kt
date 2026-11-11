package com.familycare.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual Pleos SDK writes/readbacks; every changed property is restored on the emulator. */
class VehicleControlIntegrationTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun writableControlsConfirmAndRestore(){
        check(android.os.Build.MODEL.contains("emulator",true)){"Pleos emulator only"}
        val care=CareRuntime.get(compose.activity)
        compose.waitUntil(20000){care.parked&&care.vehicleControls.length()>0}
        Thread.sleep(6000)
        val caps=JSONArray(care.vehicleControls.toString())
        File(compose.activity.filesDir,"vehicle-control-capabilities.json").writeText(caps.toString(2))
        val results=JSONArray()
        for(i in 0 until caps.length()){
            val c=caps.getJSONObject(i);if(!c.optBoolean("available"))continue
            val id=c.getString("id");val original=c.getDouble("value")
            val next=if(original<c.getDouble("max"))original+1 else original-1
            if(next<c.getDouble("min"))continue
            fun run(value:Double):Pair<String,String>{
                var status="";var message=""
                compose.runOnUiThread{care.applyControl(id,value,true){s,t->status=s;message=t}}
                compose.waitUntil(14000){status.isNotBlank()}
                return status to message
            }
            val change=run(next)
            val restore=run(original)
            results.put(JSONObject().put("id",id).put("change",change.first).put("restore",restore.first).put("message",change.second))
            File(compose.activity.filesDir,"vehicle-control-results.json").writeText(results.toString(2))
        }
        assertTrue("No writable controls detected",results.length()>0)
        for(i in 0 until results.length()){
            assertEquals(results.getJSONObject(i).toString(),"confirmed",results.getJSONObject(i).getString("change"))
            assertEquals(results.getJSONObject(i).toString(),"confirmed",results.getJSONObject(i).getString("restore"))
        }
    }

    @Test fun ownerRelayAppliesAndPassengerWaitsForApproval(){
        check(android.os.Build.MODEL.contains("emulator",true))
        val care=CareRuntime.get(compose.activity)
        fun fan():JSONObject?{val a=care.vehicleControls;return(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("id").startsWith("hvac.fan:")&&it.optBoolean("available")}}
        compose.waitUntil(20000){care.parked&&fan()!=null}
        val c=fan()!!;val original=c.getDouble("value");val value=if(original<c.getDouble("max"))original+1 else original-1
        compose.runOnUiThread{care.share()}
        compose.waitUntil(18000){care.relay.links["owner"]!=null&&care.relay.connected}
        Thread.sleep(3500)
        fun request(role:String,id:String,target:Double){
            val parts=android.net.Uri.parse(care.relay.links.getValue(role)).fragment!!.split('.',limit=2)
            val connection=java.net.URL("http://127.0.0.1:8765/api/sessions/${parts[0]}/request").openConnection() as java.net.HttpURLConnection
            try{connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=4000;connection.readTimeout=5000
                connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer ${parts[1]}")
                val body=JSONObject().put("id",id).put("client","vehicle-control-integration").put("kind","control").put("title","풍량 조절").put("options",JSONObject().put("control",c.getString("id")).put("value",target))
                connection.outputStream.use{it.write(body.toString().toByteArray())};assertEquals("Relay request failed",200,connection.responseCode)
            }finally{connection.disconnect()}
        }
        val suffix=java.util.UUID.randomUUID().toString()
        try{
            request("owner","owner-$suffix",value)
            compose.waitUntil(20000){care.relay.requests.any{it.id=="owner-$suffix"&&it.status=="confirmed"}}
            compose.waitUntil(8000){fan()?.optDouble("value")==value}
            request("family","family-$suffix",original)
            compose.waitUntil(12000){care.relay.requests.any{it.id=="family-$suffix"}}
            Thread.sleep(2500)
            assertEquals("pending",care.relay.requests.first{it.id=="family-$suffix"}.status)
            assertEquals(value,fan()!!.getDouble("value"),0.01)
            compose.runOnUiThread{care.decide(care.relay.requests.first{it.id=="family-$suffix"},true)}
            compose.waitUntil(20000){care.relay.requests.any{it.id=="family-$suffix"&&it.status=="confirmed"}}
            compose.waitUntil(8000){fan()?.optDouble("value")==original}
        }finally{
            compose.waitUntil(16000){!care.climatePending}
            var result="";compose.runOnUiThread{care.applyControl(c.getString("id"),original,true){s,_->result=s}}
            compose.waitUntil(14000){result.isNotBlank()};assertEquals("confirmed",result)
        }
    }
}

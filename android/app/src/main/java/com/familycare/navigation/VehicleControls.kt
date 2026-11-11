package com.familycare.navigation

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Only capabilities explicitly reported writable by the official Vehicle SDK become controls. */
class VehicleControls(private val context:Context,private val changed:()->Unit){
    private val vehicle=Vehicle(context.applicationContext)
    private val main=Handler(Looper.getMainLooper())
    private var closed=false
    private val entries=linkedMapOf<String,Entry>()
    private data class Entry(val id:String,val label:String,val group:String,val ownerOnly:Boolean,val boolean:Boolean,
        val min:Double,val max:Double,val read:((Double?)->Unit,(Exception)->Unit)->Unit,
        val write:(Double,()->Unit,(Exception)->Unit)->Unit,var value:Double?=null,var at:Long=0,var reading:Boolean=false)
    private fun dispatch(block:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())block()else main.post{block()}}
    private fun allowedPermission(name:String)=context.checkSelfPermission("pleos.car.permission.$name")==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun number(v:Boolean?)=v?.let{if(it)1.0 else 0.0}
    private fun seat(names:Array<out Any>):String=when{
        names.size>2->"전체"
        names.size==2&&names.all{it.toString() in listOf("ROW_1_LEFT","ROW_1_RIGHT")}->"앞좌석"
        else->names.joinToString(" + "){when(it.toString()){"ROW_1_LEFT"->"운전석";"ROW_1_RIGHT"->"조수석";"ROW_2_LEFT"->"뒷좌석 왼쪽";"ROW_2_CENTER"->"뒷좌석 가운데";"ROW_2_RIGHT"->"뒷좌석 오른쪽";"FRONT_WINDSHIELD"->"앞유리";"REAR_WINDSHIELD"->"뒷유리";"HOOD"->"보닛";"REAR"->"트렁크";else->it.toString()}}
    }
    private val ignored:(Exception)->Unit={}
    private fun add(id:String,label:String,group:String,ownerOnly:Boolean,boolean:Boolean,min:Double,max:Double,
        read:((Double?)->Unit,(Exception)->Unit)->Unit,write:(Double,()->Unit,(Exception)->Unit)->Unit)=dispatch{
        if(!closed&&min.isFinite()&&max.isFinite()&&min<=max){entries[id]=Entry(id,label,group,ownerOnly,boolean,min,max,read,write);refresh(entries.getValue(id));changed()}
    }
    private fun refresh(e:Entry){
        if(closed||e.reading)return
        e.reading=true
        var finished=false
        fun finish(v:Double?)=dispatch{if(!finished){finished=true;e.reading=false;if(!closed){e.value=v?.takeIf{it.isFinite()&&it in e.min..e.max};e.at=SystemClock.elapsedRealtime();changed()}}}
        val timeout=Runnable{finish(null)};main.postDelayed(timeout,5000)
        try{e.read({v->main.removeCallbacks(timeout);finish(v)},{main.removeCallbacks(timeout);finish(null)})}catch(_:Exception){main.removeCallbacks(timeout);finish(null)}
    }
    private val poll=object:Runnable{override fun run(){if(closed)return;entries.values.toList().forEach(::refresh);main.postDelayed(this,5000)}}
    fun snapshot():JSONArray=JSONArray(entries.values.sortedWith(compareBy({it.group},{it.id})).map{e->
        val fresh=e.value!=null&&SystemClock.elapsedRealtime()-e.at in 0..15000
        JSONObject().put("id",e.id).put("label",e.label).put("group",e.group).put("ownerOnly",e.ownerOnly)
            .put("boolean",e.boolean).put("min",e.min).put("max",e.max).put("step",1).put("available",fresh).put("value",if(fresh)e.value else JSONObject.NULL)
    })
    fun execute(id:String,value:Double,owner:Boolean,allowed:()->Boolean,done:(String,String)->Unit){
        val e=entries[id]
        if(closed||e==null||!ControlPolicy.valid(value,e.min,e.max)||e.ownerOnly&&!owner||e.value==null||SystemClock.elapsedRealtime()-e.at !in 0..15000||!allowed()){
            done("rejected","차량의 정차 상태·제어 권한·최신 상태를 확인하세요");return
        }
        var completed=false
        lateinit var timeout:Runnable
        fun finish(status:String,message:String){if(!completed){completed=true;main.removeCallbacks(timeout);done(status,message);changed()}}
        val fail:(Exception)->Unit={error->dispatch{if(android.os.Build.TYPE!="user")android.util.Log.w("FamilyCareControl","Vehicle control failed: $id",error);finish("needs_review","${e.label} 변경을 확인하지 못했어요. 차량 상태를 확인하세요")}}
        fun verify(){
            if(completed||closed)return
            try{e.read({v->dispatch{
                if(completed)return@dispatch
                e.value=v;e.at=SystemClock.elapsedRealtime()
                if(v!=null&&abs(v-value)<0.01)finish("confirmed","${e.label} 변경을 차량 응답으로 확인했어요")
                else main.postDelayed({verify()},350)
            }},fail)}catch(error:Exception){fail(error)}
        }
        timeout=Runnable{finish("needs_review","${e.label} 응답 시간이 지났어요. 실제 상태를 확인하세요")}
        main.postDelayed(timeout,10000)
        try{if(!allowed())finish("rejected","주행 상태가 바뀌어 조절을 취소했어요")else e.write(value,{dispatch{verify()}},fail)}catch(error:Exception){fail(error)}
    }
    fun start(){
        vehicle.initialize()
        discover()
        main.post(poll)
    }
    fun close(){closed=true;main.removeCallbacks(poll);entries.clear();main.postDelayed({runCatching{vehicle.release()}},12000)}
    private fun discover(){
        val hvac=vehicle.getHVAC();val door=vehicle.getDoor();val window=vehicle.getWindow();val seats=vehicle.getSeat()
        runCatching{hvac.checkHvacAcCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))areas.forEach{a->
 add("hvac.ac:"+a.values.joinToString("+"){it.name},"에어컨 A/C · "+seat(a.values),"공조",false,true,0.0,1.0,
 {ok,err->hvac.getHvacAcState(a,{ok(number(it))},err)},{v,ok,err->hvac.setHvacAc(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{hvac.checkHvacAutoCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))areas.forEach{a->
 add("hvac.auto:"+a.values.joinToString("+"){it.name},"자동 공조 · "+seat(a.values),"공조",false,true,0.0,1.0,
 {ok,err->hvac.getHvacAutoState(a,{ok(number(it))},err)},{v,ok,err->hvac.setHvacAuto(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{hvac.checkHvacRecirculationCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))areas.forEach{a->
 add("hvac.recirculation:"+a.values.joinToString("+"){it.name},"실내 순환 · "+seat(a.values),"공조",false,true,0.0,1.0,
 {ok,err->hvac.getHvacRecirculationState(a,{ok(number(it))},err)},{v,ok,err->hvac.setHvacRecirculation(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{hvac.checkHvacDualCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))areas.forEach{a->
 add("hvac.dual:"+a.values.joinToString("+"){it.name},"좌우 독립 온도 · "+seat(a.values),"공조",false,true,0.0,1.0,
 {ok,err->hvac.getHvacDualState(a,{ok(number(it))},err)},{v,ok,err->hvac.setHvacDual(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{hvac.checkHvacMaxDefrostCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))areas.forEach{a->
 add("hvac.maxdefrost:"+a.values.joinToString("+"){it.name},"최대 성에 제거 · "+seat(a.values),"공조",false,true,0.0,1.0,
 {ok,err->hvac.getHvacMaxDefrostState(a,{ok(number(it))},err)},{v,ok,err->hvac.setHvacMaxDefrost(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{hvac.checkHvacDefrosterCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))areas.forEach{a->
 add("hvac.defroster:"+a.values.joinToString("+"){it.name},"유리 열선 · "+seat(a.values),"공조",false,true,0.0,1.0,
 {ok,err->hvac.getHvacDefrosterState(a,{ok(number(it))},err)},{v,ok,err->hvac.setHvacDefroster(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{hvac.checkHvacFanSpeedCapabilities({c,bounds->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))bounds.forEach{(a,b)->
 add("hvac.fan:"+a.values.joinToString("+"){it.name},"풍량 · "+seat(a.values),"공조",false,false,b.minValue.toDouble(),b.maxValue.toDouble(),
 {ok,err->hvac.getHvacFanSpeed(a,{ok(it?.toDouble())},err)},{v,ok,err->hvac.setHvacFanSpeed(a,v.toInt(),{_,_->ok()},err)})
}},ignored)}
        runCatching{door.checkDoorLockCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_DOORS"))areas.forEach{a->
 add("door.lock:"+a.values.joinToString("+"){it.name},"문 잠금 · "+seat(a.values),"문과 창문",true,true,0.0,1.0,
 {ok,err->door.getDoorLockState(a,{ok(number(it))},err)},{v,ok,err->door.setDoorLock(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{window.checkWindowPositionCapabilities({c,bounds->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_WINDOWS"))bounds.forEach{(a,b)->
 add("window.position:"+a.values.joinToString("+"){it.name},"창문 개방 · "+seat(a.values),"문과 창문",true,false,b.minValue.toDouble(),b.maxValue.toDouble(),
 {ok,err->window.getCurrentWindowPosition(a,{ok(it?.toDouble())},err)},{v,ok,err->window.setWindowPosition(a,v.toInt(),{_,_->ok()},err)})
}},ignored)}
        runCatching{window.checkWindowLockCapabilities({c,areas->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_WINDOWS"))areas.forEach{a->
 add("window.lock:"+a.values.joinToString("+"){it.name},"창문 잠금 · "+seat(a.values),"문과 창문",true,true,0.0,1.0,
 {ok,err->window.getCurrentWindowLockState(a,{ok(number(it))},err)},{v,ok,err->window.setWindowLock(a,v==1.0,{_,_->ok()},err)})
}},ignored)}
        runCatching{seats.checkSeatTemperatureCapabilities({c,bounds->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_SEATS"))bounds.forEach{(a,b)->
 add("seat.heat:"+a.values.joinToString("+"){it.name},"시트 온열·냉방 단계 · "+seat(a.values),"시트와 열선",false,false,b.minValue.toDouble(),b.maxValue.toDouble(),
 {ok,err->seats.getHvacSeatTemperature(a,{ok(it?.toDouble())},err)},{v,ok,err->seats.setHvacSeatTemperature(a,v.toInt(),{_,_->ok()},err)})
}},ignored)}
        runCatching{seats.checkSeatVentilationCapabilities({c,bounds->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_SEATS"))bounds.forEach{(a,b)->
 add("seat.vent:"+a.values.joinToString("+"){it.name},"시트 통풍 단계 · "+seat(a.values),"시트와 열선",false,false,b.minValue.toDouble(),b.maxValue.toDouble(),
 {ok,err->seats.getHvacSeatVentilationState(a,{ok(it?.toDouble())},err)},{v,ok,err->seats.setHvacSeatVentilation(a,v.toInt(),{_,_->ok()},err)})
}},ignored)}
        val wheel=vehicle.getSteeringWheel()
runCatching{wheel.checkHvacSteeringWheelHeatCapabilities({c,b->if(c.accessType==AccessType.READ_WRITE&&allowedPermission("CONTROL_CAR_CLIMATE"))
add("wheel.heat","핸들 열선","시트와 열선",true,false,b.minValue.toDouble(),b.maxValue.toDouble(),{ok,err->wheel.getHvacSteeringWheelHeatState({ok(it?.toDouble())},err)},{v,ok,err->wheel.setHvacSteeringWheelHeat(v.toInt(),{ok()},err)})},ignored)}
    }
}

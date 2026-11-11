package com.familycare.navigation

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.*
import ai.pleos.playground.vehicle.api.model.capability.*
import ai.pleos.playground.vehicle.constant.area.*
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** SDK signals and explicitly approved climate writes with verified readback. */
class VehicleSensors(private val context: Context, private val report: (Signal, Double?, Long, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext)
    private var active = true
    private var pending = 0
    private var released = false
    private val readers = mutableMapOf<Signal, ((Double?) -> Unit, (Exception) -> Unit) -> Unit>()
    private val inFlight = mutableSetOf<Signal>()
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private val carSignals = mapOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to Signal.SPEED,
        VehiclePropertyIds.GEAR_SELECTION to Signal.GEAR, VehiclePropertyIds.IGNITION_STATE to Signal.IGNITION)
    private val supportedProperties = mutableSetOf<Int>()
    private var ticks = 0
    var ignitionSupported:Boolean?=null;private set
    var climateSupported=false;private set
    var climatePowerSupported=false;private set
    private var powerArea:SeatArea?=null
    private var climateMin=18f
    private var climateMax=28f
    private var climateBusy=false
    private data class ClimateZone(val area:SeatArea,val minimum:Double,val maximum:Double)
    private val zones=mutableMapOf<String,ClimateZone>()
    private val targets=mutableMapOf<String,Pair<Double,Long>>()
    private val readingZones=mutableSetOf<String>()
    fun climateZones():org.json.JSONArray=org.json.JSONArray(zones.map{(id,z)->
        val reading=targets[id]?.takeIf{SystemClock.elapsedRealtime()-it.second in 0..30000}
        org.json.JSONObject().put("id",id).put("label",z.area.values.joinToString(" + "){when(it){SeatValue.ROW_1_LEFT->"운전석";SeatValue.ROW_1_RIGHT->"조수석";SeatValue.ROW_2_LEFT->"뒷좌석 왼쪽";SeatValue.ROW_2_RIGHT->"뒷좌석 오른쪽";else->"뒷좌석 공조"}}).put("min",z.minimum).put("max",z.maximum).put("target",reading?.first?:org.json.JSONObject.NULL)
    })
    private fun dispatch(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private fun readable(c: CapabilityConfig) = c.accessType == AccessType.READ || c.accessType == AccessType.READ_WRITE
    private fun emit(s: Signal, v: Double?, error: String = "") {
        if(active) report(s, v, SystemClock.elapsedRealtime(), error)
    }
    private fun fail(s: Signal): (Exception) -> Unit = { dispatch { emit(s, null, "신호 읽기 실패") } }
    private fun finish() { pending--; if(!active && pending == 0) release() }
    private fun release() { if(!released) { released = true; runCatching { vehicle.release() } } }
    private fun capability(signals: List<Signal>, call: (done: (() -> Unit) -> Unit, failed: (Exception) -> Unit) -> Unit) {
        pending++
        var complete = false
        fun done(action: () -> Unit) = dispatch {
            if(!complete) { complete = true; try { if(active) action() } finally { finish() } }
        }
        try { call({ action -> done(action) }, { done { signals.forEach { emit(it, null, "지원 여부 확인 실패") } } }) }
        catch (_: Exception) { done { signals.forEach { emit(it, null, "지원되지 않는 신호") } } }
    }

    fun start() {
        vehicle.initialize()
        val door = vehicle.getDoor()
        val doors = listOf(Signal.FL_DOOR to DoorValue.ROW_1_LEFT, Signal.FR_DOOR to DoorValue.ROW_1_RIGHT,
            Signal.RL_DOOR to DoorValue.ROW_2_LEFT, Signal.RR_DOOR to DoorValue.ROW_2_RIGHT)
        capability(doors.map { it.first }) { done, failed ->
            door.checkDoorPositionCapabilities({ c, bounds -> done {
                doors.forEach { (s, v) ->
                    val area = DoorArea(v)
                    if(readable(c) && area in bounds) readers[s] = { ok, err -> door.getCurrentDoorPosition(area, { ok(it?.toDouble()) }, err) }
                    else emit(s, null, "지원되지 않는 문")
                }
            } }, failed)
        }
        val window = vehicle.getWindow()
        val windows = listOf(Signal.FL_WINDOW to WindowValue.ROW_1_LEFT, Signal.FR_WINDOW to WindowValue.ROW_1_RIGHT,
            Signal.RL_WINDOW to WindowValue.ROW_2_LEFT, Signal.RR_WINDOW to WindowValue.ROW_2_RIGHT)
        capability(windows.map { it.first }) { done, failed ->
            window.checkWindowPositionCapabilities({ c, bounds -> done {
                windows.forEach { (s, v) ->
                    val area = WindowArea(v); val b = bounds[area]
                    if(readable(c) && b != null && b.maxValue > b.minValue) readers[s] = { ok, err ->
                        window.getCurrentWindowPosition(area, { n -> ok(n?.takeIf { it in b.minValue..b.maxValue }?.let { (it - b.minValue) * 100.0 / (b.maxValue - b.minValue) }) }, err)
                    } else emit(s, null, "지원되지 않는 창문")
                }
            } }, failed)
        }
        val battery = vehicle.getEvBattery()
        capability(listOf(Signal.BATTERY)) { done, failed ->
            battery.checkEvBatteryStateOfChargeCapabilities({ c -> done {
                if(readable(c)) readers[Signal.BATTERY] = { ok, err -> battery.getEvBatteryStateOfCharge({ ok(it?.toDouble()) }, err) }
            } }, failed)
        }
        val hvac = vehicle.getHVAC()
        capability(listOf(Signal.CLIMATE_POWER)){done,failed->hvac.checkHvacPowerCapabilities({c,areas->done{
            val area=areas.firstOrNull{SeatValue.ROW_1_LEFT in it.values}
            powerArea=area
            climatePowerSupported=c.accessType==AccessType.READ_WRITE&&area!=null&&context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_CLIMATE")==android.content.pm.PackageManager.PERMISSION_GRANTED
            if(readable(c)&&area!=null)readers[Signal.CLIMATE_POWER]={ok,err->hvac.getHvacPowerState(area,{ok(it?.let{v->if(v)1.0 else 0.0})},err)}
        }},failed)}
        capability(listOf(Signal.CLIMATE_TARGET)){done,failed->
            hvac.checkHvacTemperatureCapabilities({c,bounds->done{
                if(c.accessType==AccessType.READ_WRITE&&context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_CLIMATE")==android.content.pm.PackageManager.PERMISSION_GRANTED){
                    bounds.forEach{(zone,r)->val low=maxOf(18.0,r.minValue.toDouble());val high=minOf(28.0,r.maxValue.toDouble());if(low<=high&&zone.values.isNotEmpty())zones[zone.values.joinToString("+"){it.name}]=ClimateZone(zone,low,high)}
                }
                val area=SeatArea(SeatValue.ROW_1_LEFT)
                val range=bounds[area]
                climateSupported=c.accessType==AccessType.READ_WRITE&&range!=null
                if(range!=null&&readable(c)){
                    climateMin=maxOf(18f,range.minValue);climateMax=minOf(28f,range.maxValue)
                    climateSupported=climateSupported&&climateMin<=climateMax&&context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_CLIMATE")==android.content.pm.PackageManager.PERMISSION_GRANTED
                    readers[Signal.CLIMATE_TARGET]={ok,err->hvac.getHvacTemperature(area,TemperatureUnit.CELSIUS,{ok(it?.toDouble())},err)}
                }
            }},failed)
        }
        capability(listOf(Signal.TEMPERATURE)) { done, failed ->
            hvac.checkHvacTemperatureCurrentCapabilities({ c, areas -> done {
                val area = SeatArea(SeatValue.ROW_1_LEFT)
                if(readable(c) && area in areas) readers[Signal.TEMPERATURE] = { ok, err -> hvac.getHvacTemperatureCurrent(area, { ok(it?.toDouble()) }, err) }
                else emit(Signal.TEMPERATURE, null, "운전석 온도 미지원")
            } }, failed)
        }
        val steering = vehicle.getSteeringWheel()
        capability(listOf(Signal.STEERING)) { done, failed ->
            steering.checkSteeringWheelAngleCapabilities({ c, _ -> done {
                if(readable(c)) readers[Signal.STEERING] = { ok, err -> steering.getCurrentSteeringWheelAngle({ ok(it?.toDouble()) }, err) }
            } }, failed)
        }
        val brake = vehicle.getBrake()
        capability(listOf(Signal.BRAKE)) { done, failed ->
            brake.checkBrakePedalStatusCapabilities({ c -> done {
                if(readable(c)) readers[Signal.BRAKE] = { ok, err -> brake.getCurrentBrakePedalStatus({ ok(it?.let { b -> if(b) 1.0 else 0.0 }) }, err) }
            } }, failed)
        }
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready -> dispatch {
                if(!active) { runCatching { connected.disconnect() }; return@dispatch }
                if(!ready) {
                    properties = null; supportedProperties.clear();ignitionSupported=null
                    carSignals.values.forEach { emit(it, null, "차량 연결 끊김") }
                } else try {
                    val pm = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    properties = pm
                    carSignals.forEach { (id, s) ->
                        val config=pm.getCarPropertyConfig(id)
                        if(s==Signal.IGNITION)ignitionSupported=config!=null
                        if(config != null) {
                            supportedProperties.add(id)
                            pm.registerCallback(callback, id, if(s == Signal.SPEED) 5f else CarPropertyManager.SENSOR_RATE_ONCHANGE)
                        } else emit(s, null, "차량에서 제공하지 않음")
                    }
                } catch (_: Exception) { carSignals.values.forEach { emit(it, null, "차량 신호 연결 실패") } }
            } }
        } catch (_: Exception) { carSignals.values.forEach { emit(it, null, "차량 API 미지원") } }
        catch (_: LinkageError) { carSignals.values.forEach { emit(it, null, "차량 API 미지원") } }
        main.post(poll)
    }

    fun setClimate(target:Double,allowed:()->Boolean,done:(String,String)->Unit,zoneId:String="ROW_1_LEFT",power:Boolean?=null){
        val zone=zones[zoneId]
        if(!active||climateBusy||zone==null||!target.isFinite()||target<zone.minimum||target>zone.maximum||target*2!=kotlin.math.floor(target*2)||!allowed()){
            done("rejected","정차 상태·공조 지원·설정 범위를 확인해 주세요");return
        }
        climateBusy=true;pending++
        val hvac=vehicle.getHVAC();val area=zone.area
        var reported=false;var releasedCall=false
        fun report(status:String,text:String){if(!reported){reported=true;climateBusy=false;done(status,text)}}
        fun finishCall(){if(!releasedCall){releasedCall=true;finish()}}
        val timeout=Runnable{report("needs_review","공조 응답이 늦어요. 실제 설정값을 직접 확인하세요");finishCall()}
        main.postDelayed(timeout,12000)
        val failure:(Exception)->Unit={error->dispatch{if(BuildConfig.DEBUG)android.util.Log.w("FamilyCareClimate","SDK climate operation failed",error);main.removeCallbacks(timeout);report("needs_review","공조 설정 결과를 확인하지 못했어요. 차량 공조 전원과 설정을 확인하세요");finishCall()}}
        fun verify(){
            if(reported){finishCall();return}
            try{hvac.getHvacTemperature(area,TemperatureUnit.CELSIUS,{actual->dispatch{
                if(zoneId=="ROW_1_LEFT")emit(Signal.CLIMATE_TARGET,actual?.toDouble())
                if(actual!=null)targets[zoneId]=actual.toDouble() to SystemClock.elapsedRealtime()
                if(reported){finishCall();return@dispatch}
                if(actual!=null&&kotlin.math.abs(actual-target)<0.05){
                    main.removeCallbacks(timeout);report("confirmed",(if(power==true)"공조 켜짐 · " else "")+"설정 온도 ${target}°C를 차량 응답으로 확인했어요");finishCall()
                }else main.postDelayed({verify()},400)
            }},failure)}catch(e:Exception){failure(e)}
        }
        fun writeTemperature(){try{
            if(reported)return
            if(!allowed()){main.removeCallbacks(timeout);report("rejected","차량 상태가 바뀌어 조절을 취소했어요");finishCall();return}
            hvac.setHvacTemperature(area,target.toFloat(),TemperatureUnit.CELSIUS,{_,_->dispatch{
                verify()
            }},failure)
        }catch(e:Exception){failure(e)}}
        if(power==null){writeTemperature();return}
        val pa=powerArea
        if(!climatePowerSupported||pa==null){main.removeCallbacks(timeout);report("rejected","이 차량은 공조 전원 제어를 지원하지 않아요");finishCall();return}
        fun verifyPower(){
            if(reported)return
            try{hvac.getHvacPowerState(pa,{actual->dispatch{
                emit(Signal.CLIMATE_POWER,actual?.let{if(it)1.0 else 0.0})
                if(reported)return@dispatch
                if(actual==power){
                    if(power)writeTemperature()
                    else{main.removeCallbacks(timeout);report("confirmed","공조 꺼짐을 차량 응답으로 확인했어요");finishCall()}
                }else main.postDelayed({verifyPower()},400)
            }},failure)}catch(e:Exception){failure(e)}
        }
        try{
            if(!allowed()){main.removeCallbacks(timeout);report("rejected","차량 상태가 바뀌어 조절을 취소했어요");finishCall();return}
            hvac.setHvacPower(pa,power,{_,_->dispatch{verifyPower()}},failure)
        }catch(e:Exception){failure(e)}
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) { dispatch {
            carSignals[v.propertyId]?.let { emit(it, if(v.status == CarPropertyValue.STATUS_AVAILABLE) (v.value as? Number)?.toDouble() else null, "신호 미확인") }
        } }
        override fun onErrorEvent(prop: Int, area: Int) { dispatch { carSignals[prop]?.let { emit(it, null, "신호 오류") } } }
    }
    fun refreshSpeedPermission() {
        val pm = properties ?: return
        val id = VehiclePropertyIds.PERF_VEHICLE_SPEED
        try {
            if(pm.getCarPropertyConfig(id) != null) {
                supportedProperties.add(id); pm.registerCallback(callback,id,5f)
                pm.getProperty<Float>(id,0)?.let { callback.onChangeEvent(it) }
            }
        } catch (_: Exception) { emit(Signal.SPEED,null,"속도 읽기 권한 필요") }
    }
    private val poll = object : Runnable {
        override fun run() {
            if(!active) return
            ticks++
            if(ticks%5==1)zones.forEach{(id,zone)->if(readingZones.add(id)){
                pending++;var done=false
                fun complete(value:Float?)=dispatch{if(!done){done=true;readingZones.remove(id);if(value!=null&&value.isFinite())targets[id]=value.toDouble() to SystemClock.elapsedRealtime()else targets.remove(id);finish()}}
                try{vehicle.getHVAC().getHvacTemperature(zone.area,TemperatureUnit.CELSIUS,{complete(it)},{complete(null)})}catch(_:Exception){complete(null)}
            }}
            readers.toMap().forEach { (s, read) ->
                if(s != Signal.STEERING && ticks % 5 != 1) return@forEach
                if(!inFlight.add(s)) return@forEach
                pending++
                var finished = false
                fun done(v: Double?, error: String) = dispatch {
                    if(!finished) { finished = true; inFlight.remove(s); emit(s, v, error); finish() }
                }
                try { read({ done(it, "미확인") }, { done(null, "읽기 실패") }) }
                catch (_: Exception) { done(null, "읽기 실패") }
            }
            if(ticks % 5 == 1) supportedProperties.toList().forEach { id ->
                try { properties?.getProperty<Any>(id, 0)?.let { callback.onChangeEvent(it) } }
                catch (_: Exception) { carSignals[id]?.let { emit(it, null, "읽기 실패") } }
            }
            main.postDelayed(this, 1000)
        }
    }
    fun close() {
        if(!active) return
        active = false; main.removeCallbacks(poll)
        runCatching { properties?.unregisterCallback(callback) }; properties = null
        runCatching { car?.disconnect() }; car = null
        if(pending == 0) release()
        else main.postDelayed({ release() }, 10_000) // Bound cleanup even if the SDK never calls back.
    }
}

package com.familycare.navigation

import kotlin.math.abs

enum class Passenger(val label: String) {
    CHILD("아이"), ELDER("어르신"), PREGNANT("임산부"), PET("반려동물"), NOVICE("초보운전자")
}

data class FamilyProfile(val members: Set<Passenger> = emptySet(), val childBand: Int = 1, val sensitivity:Int=1) {
    val accelerationLimit get()=(members.minOfOrNull{when(it){Passenger.CHILD->if(childBand==0)2.0 else 2.3;Passenger.ELDER->2.2;Passenger.PREGNANT->2.0;Passenger.PET->2.7;Passenger.NOVICE->2.5}}?:3.5)*listOf(1.2,1.0,0.8)[sensitivity.coerceIn(0,2)]
    val steeringLimit get()=(members.minOfOrNull{when(it){Passenger.CHILD,Passenger.PREGNANT->60.0;Passenger.ELDER->65.0;Passenger.PET->80.0;Passenger.NOVICE->70.0}}?:100.0)*listOf(1.2,1.0,0.8)[sensitivity.coerceIn(0,2)]
    val gentle get() = members.any { it != Passenger.PET }
    val mode: String get() = when {
        members.isEmpty() -> "기본 이동"
        members == setOf(Passenger.PET) -> "반려동물 동승"
        members.size>1 -> "복합 가족 케어"
        else -> members.first().label+" 맞춤 이동"
    }
    val breakMinutes get() = if (Passenger.CHILD in members && childBand == 0) 60 else 90
    val rearCheck get() = Passenger.CHILD in members || Passenger.PET in members
}

enum class Signal(val label: String, val min: Double, val max: Double) {
    CLIMATE_POWER("공조 전원",0.0,1.0),
    TEMPERATURE("실내 온도", -50.0, 100.0), CLIMATE_TARGET("설정 온도", 16.0, 32.0), BATTERY("배터리", 0.0, 100.0),
    FL_DOOR("운전석 문", 0.0, 1.0), FR_DOOR("조수석 문", 0.0, 1.0),
    RL_DOOR("왼쪽 뒷문", 0.0, 1.0), RR_DOOR("오른쪽 뒷문", 0.0, 1.0),
    FL_WINDOW("앞 왼쪽 창문", 0.0, 100.0), FR_WINDOW("앞 오른쪽 창문", 0.0, 100.0),
    RL_WINDOW("뒤 왼쪽 창문", 0.0, 100.0), RR_WINDOW("뒤 오른쪽 창문", 0.0, 100.0),
    SPEED("속도", -100.0, 100.0), GEAR("기어", 0.0, 16.0),
    BRAKE("브레이크", 0.0, 1.0), STEERING("조향각", -2000.0, 2000.0), IGNITION("시동", 0.0, 10.0)
}

data class Reading(val value: Double?, val at: Long, val problem: String = "") {
    fun fresh(now: Long): Double? = value?.takeIf { now - at in 0..30_000 }
}
data class CareAlert(val code: String, val title: String, val level: Int)
data class Feedback(val acceleration: Int = 0, val braking: Int = 0, val steering: Int = 0) {
    val total get() = acceleration + braking + steering
}
data class TripSummary(val startedAt: Long, val durationSeconds: Long, val drivingSeconds: Long,
    val feedback: Feedback, val mode: String)

/** Deterministic domain rules. Thresholds are prototype presets, not medical/safety guarantees. */
class CareEngine {
    var onDrivingEvent:((String,String)->Unit)?=null
    private val motionEvents=mutableListOf<Long>()
    fun motionLoad(now:Long):Int=motionEvents.count{now-it in 0..180_000}.times(20).coerceAtMost(100)
    var profile = FamilyProfile()
    val signals = mutableMapOf<Signal, Reading>()
    var ux = UxPolicy()
    var tripStartedAt: Long? = null
        private set
    private var tripWallTime = 0L
    var drivingMillis = 0L
        private set
    var drivingSinceBreakMillis = 0L
        private set
    var feedback = Feedback()
        private set
    var lastFeedback = ""
        private set
    private var lastFeedbackAt = Long.MIN_VALUE / 2
    private var lastTick: Long? = null
    private val lastEvents = mutableMapOf<String, Long>()
    private val derivativeBaselines = mutableMapOf<Signal, Reading>()
    var watchStartedAt: Long? = null
        private set
    var checkMinutes = 10
    var temperatureLimit = 30.0
    var batteryLimit = 20.0
    var exitCheckRequired = false
    var rearDoorObserved = false
        private set
    private var arrivalHandled = false
    fun destinationChanged() { arrivalHandled = false }
    fun destinationArrived(parked: Boolean) {
        if (parked && !arrivalHandled) {
            arrivalHandled = true
            if (profile.rearCheck) exitCheckRequired = true
        }
    }

    val running get() = tripStartedAt != null
    val watching get() = watchStartedAt != null
    fun value(signal: Signal, now: Long) = signals[signal]?.fresh(now)

    fun update(signal: Signal, value: Double?, now: Long, problem: String = "") {
        val discrete = signal in listOf(Signal.FL_DOOR,Signal.FR_DOOR,Signal.RL_DOOR,Signal.RR_DOOR,Signal.BRAKE)
        val valid = value?.takeIf { it.isFinite() && it in signal.min..signal.max && (!discrete || it == 0.0 || it == 1.0) }
        val old = signals[signal]
        if (old != null && now < old.at) return
        signals[signal] = Reading(valid, now, if (valid == null) problem.ifBlank { "미확인" } else "")
        if (!running) return
        if (signal in listOf(Signal.RL_DOOR, Signal.RR_DOOR) && valid == 1.0) rearDoorObserved = true
        if (profile.rearCheck && signal == Signal.FL_DOOR && valid == 1.0 && old?.value != 1.0 && ux.showDetails) exitCheckRequired = true
        if (signal !in listOf(Signal.SPEED, Signal.STEERING)) return
        if (valid == null) { derivativeBaselines.remove(signal); return }
        val baseline = derivativeBaselines[signal]
        if (baseline?.value == null) { derivativeBaselines[signal] = Reading(valid, now); return }
        val dt = (now - baseline.at) / 1000.0
        // Fast callbacks must accumulate a measurement window, not erase every derivative.
        if (dt < 0.2) return
        derivativeBaselines[signal] = Reading(valid, now)
        if (dt > 2.5) return // Reconnects/gaps cannot generate artificial events.
        if (signal == Signal.SPEED) {
            val acceleration = (abs(valid) - abs(baseline.value)) / dt
            val threshold = profile.accelerationLimit
            if (abs(valid) > 1 && acceleration > threshold) event("accel", now, "천천히 가속해 주세요")
            if (abs(baseline.value) > 2 && acceleration < -threshold) event("brake", now, "부드럽게 감속해 주세요")
        }
        if (signal == Signal.STEERING && abs(value(Signal.SPEED, now) ?: 0.0) > 3) {
            val threshold = profile.steeringLimit
            if (abs(valid - baseline.value) / dt > threshold) event("steer", now, "부드럽게 방향을 바꿔 주세요")
        }
    }

    private fun event(kind: String, now: Long, message: String) {
        if (now - (lastEvents[kind] ?: Long.MIN_VALUE / 2) < 8_000) return
        lastEvents[kind] = now
        feedback = when(kind) {
            "accel" -> feedback.copy(acceleration = feedback.acceleration + 1)
            "brake" -> feedback.copy(braking = feedback.braking + 1)
            else -> feedback.copy(steering = feedback.steering + 1)
        }
        lastFeedback = message
        lastFeedbackAt = now
        motionEvents.removeAll{now-it>180_000};motionEvents.add(now);onDrivingEvent?.invoke(kind,message)
    }

    fun startTrip(now: Long, wallTime: Long) {
        check(ux.showDetails) { "정차 후 시작하세요" }
        if (running) return
        tripStartedAt = now; tripWallTime = wallTime
        drivingMillis = 0; drivingSinceBreakMillis = 0; feedback = Feedback()
        arrivalHandled = false;motionEvents.clear()
        lastTick = now; rearDoorObserved = false; lastEvents.clear(); lastFeedback = ""
        derivativeBaselines.clear()
        // Reset derivative baselines without discarding observed values.
        signals.replaceAll { _, reading -> reading.copy(at = now - 3_000) }
    }

    fun tick(now: Long) {
        val previous = lastTick
        if (running && previous != null && now - previous in 0..3_000 &&
            abs(value(Signal.SPEED, now) ?: 0.0) > 0.5) {
            drivingMillis += now - previous
            drivingSinceBreakMillis += now - previous
        }
        lastTick = now
        if (now - lastFeedbackAt > 5_000) lastFeedback = ""
    }

    fun takeBreak() { check(ux.showDetails); drivingSinceBreakMillis = 0 }
    fun startWatch(now: Long) {
        check(ux.showDetails && Passenger.PET in profile.members)
        watchStartedAt = now
    }
    fun stopWatch() { watchStartedAt = null }
    fun confirmRearCheck() { check(ux.showDetails); exitCheckRequired = false }

    fun endTrip(now: Long): TripSummary? {
        check(ux.showDetails)
        val start = tripStartedAt ?: return null
        val summary = TripSummary(tripWallTime, ((now - start) / 1000).coerceAtLeast(0),
            drivingMillis / 1000, feedback, profile.mode)
        if (profile.rearCheck) exitCheckRequired = true
        tripStartedAt = null; lastTick = null
        return summary
    }

    fun alerts(now: Long): List<CareAlert> = buildList {
        if(running&&motionLoad(now)>=60)add(CareAlert("motion","급조작이 이어졌어요 · 부드럽게 운전하고 휴식을 고려하세요",1))
        if (exitCheckRequired) add(CareAlert("rear", "뒷좌석을 직접 확인해 주세요", 3))
        if (running && drivingSinceBreakMillis >= profile.breakMinutes * 60_000L)
            add(CareAlert("break", "다음 안전한 장소에서 쉬어 가세요", 1))
        if (watching) {
            val temperature = value(Signal.TEMPERATURE, now)
            val battery = value(Signal.BATTERY, now)
            if (temperature == null || battery == null) add(CareAlert("missing", "차량 상태 미확인 · 직접 확인해 주세요", 3))
            if (temperature != null && temperature >= temperatureLimit) add(CareAlert("temperature", "설정한 온도 확인 기준을 넘었어요", 2))
            if (battery != null && battery <= batteryLimit) add(CareAlert("battery", "설정한 배터리 확인 기준보다 낮아요", 2))
            if (now - watchStartedAt!! >= checkMinutes * 60_000L) add(CareAlert("timer", "확인 타이머가 끝났어요", 3))
            val doors = listOf(Signal.FL_DOOR, Signal.FR_DOOR, Signal.RL_DOOR, Signal.RR_DOOR)
            val windows = listOf(Signal.FL_WINDOW, Signal.FR_WINDOW, Signal.RL_WINDOW, Signal.RR_WINDOW)
            if ((doors + windows).any { value(it, now) == null }) add(CareAlert("closures-missing", "문·창문 일부 상태 미확인", 2))
            else if ((doors + windows).any { (value(it, now) ?: 0.0) > 0 }) add(CareAlert("closures", "열린 문 또는 창문을 확인해 주세요", 1))
        }
    }.sortedByDescending { it.level }
}

data class PassengerRequest(val id: String, val kind: String, val title: String,
    val latitude: Double? = null, val longitude: Double? = null,
    val status: String = "pending", val message: String = "", val votes: Int = 0,
    val target:Double?=null,val originRole:String="family", val options:String="{}",val deviceId:String="",val authorityEpoch:String="",val delegated:Boolean=false) {
    fun valid(): Boolean = id.length in 1..80 && title.length in 1..120 &&
        kind in setOf("note", "destination", "waypoint", "climate", "timer", "profile","hold","control","permission","care","pickup") && options.length<=8192 &&
        (kind in setOf("note","timer","profile","hold","control","permission","care") || (kind=="climate"&&target?.isFinite()==true&&target in 18.0..28.0&&target*2==(target*2).toInt().toDouble()) || (kind in setOf("destination","waypoint","pickup")&&latitude?.isFinite() == true && longitude?.isFinite() == true &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0))
}

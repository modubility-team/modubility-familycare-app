package com.familycare.navigation

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class CarUxGateway(private val context: Context) : UxGateway {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var active = false
    private var generation = 0

    override fun connect(onUpdate: (Result<UxPolicy>) -> Unit) {
        active = true
        // No synchronous wait on the UI thread. Lifecycle callbacks also detect service loss.
        car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
            if (!active) {
                connectedCar.disconnect()
            } else {
                detachManager()
                if (!ready) onUpdate(Result.success(UxPolicy())) else {
                    try {
                        val current = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                            as? CarUxRestrictionsManager ?: error("UX manager unavailable")
                        manager = current
                        val connection = generation
                        var revision = 0
                        current.registerListener { restrictions ->
                            if (active && generation == connection) {
                                revision++
                                onUpdate(Result.success(restrictions.toPolicy()))
                            }
                        }
                        val beforeRead = revision
                        val snapshot = current.currentCarUxRestrictions
                        if (active && generation == connection && revision == beforeRead) {
                            onUpdate(Result.success(snapshot.toPolicy()))
                        }
                    } catch (e: Exception) {
                        detachManager()
                        onUpdate(Result.failure(e))
                    }
                }
            }
        }
        if (car == null) onUpdate(Result.success(UxPolicy()))
    }

    private fun CarUxRestrictions.toPolicy() = UxPolicy(
        available = true,
        requiresOptimization = isRequiresDistractionOptimization,
        activeRestrictions = activeRestrictions,
        maxStringLength = if (activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0)
            maxRestrictedStringLength else null
    )

    private fun detachManager() {
        generation++
        runCatching { manager?.unregisterListener() }
        manager = null
    }

    override fun close() {
        active = false
        detachManager()
        runCatching { car?.disconnect() }
        car = null
    }
}

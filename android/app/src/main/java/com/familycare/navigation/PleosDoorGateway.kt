package com.familycare.navigation

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Context

class PleosDoorGateway(context: Context) : DoorGateway {
    private val vehicle = Vehicle(context.applicationContext)
    private val door = vehicle.getDoor()
    private val area = DoorArea(DoorValue.ROW_1_LEFT)
    private var listener: DoorPositionListener? = null

    override fun initialize() { vehicle.initialize() }

    override fun checkReadable(callback: (Result<Boolean>) -> Unit) {
        door.checkDoorPositionCapabilities(
            onComplete = { config, _ -> callback(Result.success(
                config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
            )) },
            onFailed = { callback(Result.failure(it)) }
        )
    }

    override fun read(callback: (Result<Int?>) -> Unit) {
        door.getCurrentDoorPosition(area,
            onComplete = { callback(Result.success(it)) },
            onFailed = { callback(Result.failure(it)) })
    }

    override fun subscribe(callback: (Result<Int?>) -> Unit) {
        val sameListener = object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) { callback(Result.success(pos)) }
            override fun onFailed(e: Exception) { callback(Result.failure(e)) }
        }
        listener = sameListener
        door.registerDoorPosition(area, sameListener)
    }

    override fun unsubscribe() {
        listener?.let { door.unregisterDoorPosition(area, it) }
        listener = null
    }

    override fun release() { vehicle.release() }
}

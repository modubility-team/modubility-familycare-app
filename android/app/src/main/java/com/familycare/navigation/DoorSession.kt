package com.familycare.navigation

enum class DoorStatus { CONNECTING, CLOSED, OPEN, UNAVAILABLE, ERROR }

interface DoorGateway {
    fun initialize()
    fun checkReadable(callback: (Result<Boolean>) -> Unit)
    fun read(callback: (Result<Int?>) -> Unit)
    fun subscribe(callback: (Result<Int?>) -> Unit)
    fun unsubscribe()
    fun release()
}

/** All session state is confined to the supplied dispatcher (the main thread on Android). */
class DoorSession(
    private val gateway: DoorGateway,
    private val dispatch: (() -> Unit) -> Unit,
    private val publish: (DoorStatus) -> Unit
) {
    private var started = false
    private var active = false
    private var pending = 0
    private var subscribed = false
    private var released = false
    private var revision = 0

    fun start() {
        check(!started) { "Create a new session when returning to the foreground" }
        started = true
        active = true
        publish(DoorStatus.CONNECTING)
        try { gateway.initialize() } catch (_: Exception) {
            publish(DoorStatus.ERROR)
            return
        }
        request<Boolean>(gateway::checkReadable) { capability ->
            capability.fold(onSuccess = { readable ->
                if (!readable) publish(DoorStatus.UNAVAILABLE) else connectDoor()
            }, onFailure = { publish(DoorStatus.ERROR) })
        }
    }

    private fun connectDoor() {
        try {
            subscribed = true
            gateway.subscribe { result -> dispatch {
                if (active) { revision++; accept(result) }
            } }
        } catch (_: Exception) {
            // Keep cleanup enabled: registration may have partially succeeded.
            publish(DoorStatus.ERROR)
            return
        }
        val requestRevision = revision
        request<Int?>(gateway::read) { result ->
            // A late snapshot must not overwrite a newer door event.
            if (revision == requestRevision) accept(result)
        }
    }

    private fun accept(result: Result<Int?>) {
        result.fold(onSuccess = { position ->
            publish(when(position) {
                0 -> DoorStatus.CLOSED
                1 -> DoorStatus.OPEN
                else -> DoorStatus.UNAVAILABLE
            })
        }, onFailure = { publish(DoorStatus.ERROR) })
    }

    private fun <T> request(call: ((Result<T>) -> Unit) -> Unit, consume: (Result<T>) -> Unit) {
        pending++
        var completed = false
        val complete: (Result<T>) -> Unit = { result -> dispatch {
            if (!completed) {
                completed = true
                pending--
                if (active) consume(result)
                releaseIfStopped()
            }
        } }
        try { call(complete) } catch (e: Exception) { complete(Result.failure(e)) }
    }

    fun close() {
        if (!active) return
        active = false
        if (subscribed) {
            runCatching { gateway.unsubscribe() }
            subscribed = false
        }
        releaseIfStopped()
    }

    private fun releaseIfStopped() {
        if (!active && pending == 0 && !released) {
            released = true
            runCatching { gateway.release() }
        }
    }
}

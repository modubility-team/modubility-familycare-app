package com.familycare.navigation

/** Unknown/disconnected states must never unlock parked-only UI. */
data class UxPolicy(
    val available: Boolean = false,
    val requiresOptimization: Boolean = true,
    val activeRestrictions: Int = 0,
    val maxStringLength: Int? = null
) {
    val showDetails: Boolean
        get() = available && !requiresOptimization && activeRestrictions == 0

    fun shortText(text: String): String = maxStringLength?.let { text.take(it.coerceAtLeast(0)) } ?: text
}

interface UxGateway {
    fun connect(onUpdate: (Result<UxPolicy>) -> Unit)
    fun close()
}

/** Serialized on the main dispatcher; a fresh session is used on each foreground entry. */
class UxSession(
    private val gateway: UxGateway,
    private val dispatch: (() -> Unit) -> Unit,
    private val publish: (UxPolicy) -> Unit
) {
    private var started = false
    private var active = false

    fun start() {
        check(!started)
        started = true
        active = true
        publish(UxPolicy())
        try {
            gateway.connect { result -> dispatch {
                if (active) publish(result.getOrDefault(UxPolicy()))
            } }
        } catch (_: Exception) { publish(UxPolicy()) }
        catch (_: LinkageError) { publish(UxPolicy()) }
    }

    fun close() {
        if (!active) return
        active = false
        try { gateway.close() } catch (_: Exception) { /* UI remains restricted. */ }
        catch (_: LinkageError) { /* Optional car library unavailable. */ }
    }
}

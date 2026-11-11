package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class UxSessionTest {
    private class Fake : UxGateway {
        lateinit var update: (Result<UxPolicy>) -> Unit
        var closes = 0
        override fun connect(onUpdate: (Result<UxPolicy>) -> Unit) { update = onUpdate }
        override fun close() { closes++ }
    }
    private val fake = Fake()
    private val states = mutableListOf<UxPolicy>()
    private val session = UxSession(fake, { it() }, states::add)
    private val unrestricted = UxPolicy(true, false)

    @Test fun unknownIsRestrictedAndOnlyExplicitUnrestrictedPolicyUnlocks() {
        session.start()
        assertFalse(states.last().showDetails)
        fake.update(Result.success(unrestricted)); assertTrue(states.last().showDetails)
        fake.update(Result.success(UxPolicy(true, true, 80))); assertFalse(states.last().showDetails)
        fake.update(Result.success(unrestricted)); assertTrue(states.last().showDetails)
    }
    @Test fun lossOfServiceClosesPreviouslyUnlockedScreen() {
        session.start(); fake.update(Result.success(unrestricted))
        fake.update(Result.failure(IllegalStateException("disconnected")))
        assertFalse(states.last().showDetails)
    }
    @Test fun queuedCallbacksCannotUnlockAfterStop() {
        val queue = mutableListOf<() -> Unit>()
        val queuedSession = UxSession(fake, { queue.add(it); Unit }, states::add)
        queuedSession.start(); fake.update(Result.success(unrestricted)); queuedSession.close()
        queue.forEach { it() }; queuedSession.close()
        assertFalse(states.last().showDetails)
        assertEquals(1, fake.closes)
    }
    @Test fun optimizationBaselineAndUnexpectedFlagsStillRestrict() {
        assertFalse(UxPolicy(true, true, 0).showDetails)
        assertFalse(UxPolicy(true, false, 80).showDetails)
    }
    @Test fun textHonorsPlatformLimitIncludingZero() {
        assertEquals("운전에", UxPolicy(maxStringLength = 3).shortText("운전에 집중해 주세요"))
        assertEquals("", UxPolicy(maxStringLength = 0).shortText("안내"))
        assertEquals("", UxPolicy(maxStringLength = -1).shortText("안내"))
    }
    @Test fun unavailableCarLibraryDoesNotUnlockOrCrash() {
        val missing = object : UxGateway {
            override fun connect(onUpdate: (Result<UxPolicy>) -> Unit) { throw NoClassDefFoundError("android.car.Car") }
            override fun close() {}
        }
        UxSession(missing, { it() }, states::add).start()
        assertFalse(states.last().showDetails)
    }
}

package com.familycare.navigation

import org.junit.Assert.*
import org.junit.Test

class DoorSessionTest {
    private class Fake : DoorGateway {
        lateinit var capability: (Result<Boolean>) -> Unit
        lateinit var snapshot: (Result<Int?>) -> Unit
        lateinit var update: (Result<Int?>) -> Unit
        val calls = mutableListOf<String>()
        override fun initialize() { calls += "initialize" }
        override fun checkReadable(callback: (Result<Boolean>) -> Unit) { capability = callback }
        override fun read(callback: (Result<Int?>) -> Unit) { calls += "read"; snapshot = callback }
        override fun subscribe(callback: (Result<Int?>) -> Unit) { calls += "subscribe"; update = callback }
        override fun unsubscribe() { calls += "unsubscribe" }
        override fun release() { calls += "release" }
    }
    private val fake = Fake()
    private val states = mutableListOf<DoorStatus>()
    private val session = DoorSession(fake, {it()}, states::add)

    @Test fun doorChangesUpdateReminder() {
        session.start(); fake.capability(Result.success(true)); fake.snapshot(Result.success(0))
        assertEquals(DoorStatus.CLOSED,states.last())
        fake.update(Result.success(1)); assertEquals(DoorStatus.OPEN,states.last())
        fake.update(Result.success(0)); assertEquals(DoorStatus.CLOSED,states.last())
    }
    @Test fun oldSnapshotCannotOverwriteNewDoorEvent() {
        session.start(); fake.capability(Result.success(true))
        fake.update(Result.success(1)); fake.snapshot(Result.success(0))
        assertEquals(DoorStatus.OPEN,states.last())
    }
    @Test fun unavailableValueIsNotReportedAsClosed() {
        session.start(); fake.capability(Result.success(true)); fake.snapshot(Result.success(null))
        assertEquals(DoorStatus.UNAVAILABLE,states.last())
        fake.update(Result.success(99)); assertEquals(DoorStatus.UNAVAILABLE,states.last())
        fake.update(Result.failure(Exception("disconnected"))); assertEquals(DoorStatus.ERROR,states.last())
    }
    @Test fun stoppedSessionWaitsForCapabilityAndDoesNotSubscribe() {
        session.start(); session.close(); assertFalse(fake.calls.contains("release"))
        fake.capability(Result.success(true))
        assertFalse(fake.calls.contains("subscribe")); assertEquals("release",fake.calls.last())
        assertEquals(listOf(DoorStatus.CONNECTING),states)
    }
    @Test fun closeUnsubscribesBeforeReleasingAndIgnoresLateResults() {
        session.start(); fake.capability(Result.success(true)); session.close()
        assertEquals("unsubscribe",fake.calls.last())
        val count=states.size
        fake.update(Result.success(1)); fake.snapshot(Result.success(1)); session.close()
        assertEquals(count,states.size)
        assertEquals(listOf("unsubscribe","release"),fake.calls.takeLast(2))
        assertEquals(1,fake.calls.count {it=="release"})
    }
    @Test fun unsupportedCapabilityDoesNotReadOrSubscribe() {
        session.start(); fake.capability(Result.success(false))
        assertEquals(DoorStatus.UNAVAILABLE,states.last())
        assertFalse(fake.calls.contains("read")); assertFalse(fake.calls.contains("subscribe"))
    }
}

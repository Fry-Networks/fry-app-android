package com.frynetworks.fryapp.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattBusyRetryTest {

    private val ok = 0

    @Test
    fun `a busy refusal is retried until the call is accepted`() = runTest {
        val outcomes = ArrayDeque(listOf(GATT_CALL_REJECTED, GATT_CALL_REJECTED, ok))
        var calls = 0
        val result = retryWhileBusy({ it }) { calls++; outcomes.removeFirst() }
        assertEquals(ok, result)
        assertEquals(3, calls)
        assertEquals(BUSY_BACKOFF_MS * (1 + 2), currentTime)
    }

    @Test
    fun `at most three retries, then the refusal is returned`() = runTest {
        var calls = 0
        val result = retryWhileBusy({ it }) { calls++; GATT_CALL_REJECTED }
        assertEquals(GATT_CALL_REJECTED, result)
        assertEquals(1 + BUSY_RETRIES, calls)
    }

    @Test
    fun `other failures are not retried here`() = runTest {
        var calls = 0
        val result = retryWhileBusy({ it }) { calls++; 133 }
        assertEquals(133, result)
        assertEquals(1, calls)
        assertEquals(0L, currentTime)
    }
}

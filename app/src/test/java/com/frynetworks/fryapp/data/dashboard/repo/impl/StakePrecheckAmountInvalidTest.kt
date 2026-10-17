package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.common.ErrorCopy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The server's 400 STAKE_AMOUNT_INVALID precheck answer reaches the user as the server's own message. */
class StakePrecheckAmountInvalidTest {

    private val server = MockWebServer()
    private lateinit var stack: DashboardStack
    private lateinit var repo: StakeRepositoryImpl

    @Before
    fun start() {
        server.start()
        stack = DashboardStack(server)
        repo = StakeRepositoryImpl(stack.api, stack.bus, now = { 1_757_548_800_000L })
    }

    @After fun stop() = server.shutdown()

    @Test
    fun `precheck 400 STAKE_AMOUNT_INVALID surfaces the server message as the copy`() = runTest {
        val message = "This miner has no registration stake amount configured."
        server.enqueue(Fixtures.body("""{"allowed":false,"success":false,"code":"STAKE_AMOUNT_INVALID","message":"$message"}""", 400))
        val e = runCatching { repo.precheck(Fixtures.FEM, Fixtures.ADDRESS, StakeContext.Registration) }.exceptionOrNull() as DashboardException
        assertEquals("STAKE_AMOUNT_INVALID", e.code)
        assertEquals(400, e.httpStatus)
        assertEquals(message, e.message)
        assertEquals(message, ErrorCopy.forCode(e.code, e.message))
    }
}

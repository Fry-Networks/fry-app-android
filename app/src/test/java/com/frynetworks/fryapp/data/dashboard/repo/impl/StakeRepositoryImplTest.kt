package com.frynetworks.fryapp.data.dashboard.repo.impl

import app.cash.turbine.test
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeTier
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** [StakeRepositoryImpl]: Stake.tsx / Withdraw*.tsx body parity, the real (non-DTO) answer shapes, 429 mapping. */
class StakeRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var stack: DashboardStack
    private lateinit var repo: StakeRepositoryImpl
    private val now = 1_757_548_800_000L

    @Before
    fun start() {
        server.start()
        stack = DashboardStack(server)
        repo = StakeRepositoryImpl(stack.api, stack.bus, now = { now })
    }

    @After fun stop() = server.shutdown()

    private fun payload(amount: String = "40") = StakeSubmitPayload(
        address = Fixtures.ADDRESS,
        minerKey = Fixtures.FEM,
        txId = "STAKETXID0000000000000000000000000000000000000000000000",
        amount = BigDecimal(amount),
        assetId = 2681521901L,
    )

    // --- withdrawable ---

    @Test
    fun `withdrawable posts address then miner_key signed and maps the real data envelope`() = runTest {
        server.enqueue(Fixtures.body("""{"message":"ok","data":{"available":false,"availableIn":${now + 90_000L},"legacy":false}}"""))
        val locked = repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Verification(StakeTier.ONE))
        stack.assertSignedPost(server.takeRequest(), "/api/stake/withdrawable", """{"address":"${Fixtures.ADDRESS}","miner_key":"${Fixtures.FEM}"}""")
        assertFalse(locked.withdrawable)
        assertEquals(90L, locked.lockRemainingSeconds)
        assertEquals(true, locked.success)

        server.enqueue(Fixtures.body("""{"message":"ok","data":{"available":true,"availableIn":${now - 5L},"legacy":true}}"""))
        val open = repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Verification(StakeTier.TWO))
        assertTrue(open.withdrawable)
        assertEquals(0L, open.lockRemainingSeconds)
    }

    @Test
    fun `withdrawable also accepts the modelled DTO shape`() = runTest {
        server.enqueue(Fixtures.json("withdrawable"))
        val response = repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Verification(StakeTier.ONE))
        assertTrue(response.withdrawable)
        assertEquals(0, BigDecimal("20.5").compareTo(response.amount))
        assertEquals("2681521901", response.assetId)
    }

    @Test
    fun `withdrawable for registration and node contexts is answered locally, like WithdrawAll tsx`() = runTest {
        val registration = repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Registration)
        val node = repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Node)
        assertTrue(registration.withdrawable)
        assertTrue(node.withdrawable)
        assertEquals(0L, registration.lockRemainingSeconds)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `withdrawable 401 for a device without a verification stake is surfaced without expiring the session`() = runTest {
        server.enqueue(Fixtures.body("""{"success":false,"code":"UNAUTHORIZED","message":"Verification stake not found for this device."}""", 401))
        stack.bus.events.test {
            val e = runCatching { repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Verification(StakeTier.ONE)) }.exceptionOrNull() as DashboardException
            assertEquals("UNAUTHORIZED", e.code)
            assertEquals(401, e.httpStatus)
            expectNoEvents()
        }

        server.enqueue(Fixtures.body("""{"success":false,"code":"SESSION_REQUIRED","message":"Your session has expired"}""", 401))
        stack.bus.events.test {
            val e = runCatching { repo.withdrawable(Fixtures.ADDRESS, Fixtures.FEM, StakeContext.Verification(StakeTier.ONE)) }.exceptionOrNull() as DashboardException
            assertEquals("SESSION_REQUIRED", e.code)
            assertEquals(SessionEvent.Expired, awaitItem())
        }
    }

    // --- precheck ---

    @Test
    fun `precheck posts miner_key, address, context with the dashboard context strings and maps allowed`() = runTest {
        for ((context, wire) in listOf(StakeContext.Registration to "registration", StakeContext.Node to "node", StakeContext.Verification(StakeTier.TWO) to "verification")) {
            server.enqueue(Fixtures.body("""{"allowed":true}"""))
            val result = repo.precheck(Fixtures.FEM, Fixtures.ADDRESS, context)
            assertEquals(true, result.success)
            stack.assertSignedPost(server.takeRequest(), "/api/stake/precheck", """{"miner_key":"${Fixtures.FEM}","address":"${Fixtures.ADDRESS}","context":"$wire"}""")
        }
        server.enqueue(Fixtures.json("precheck"))
        assertEquals("Precheck passed.", repo.precheck(Fixtures.FEM, Fixtures.ADDRESS, StakeContext.Node).message)
    }

    @Test
    fun `precheck 429 is RATE_LIMIT_EXCEEDED with the Retry-After seconds as the action`() = runTest {
        server.enqueue(
            Fixtures.body("""{"success":false,"code":"RATE_LIMIT_EXCEEDED","message":"Too many verification staking requests. Please wait before trying again.","action":"Give it a moment"}""", 429)
                .setHeader("Retry-After", "7"),
        )
        val e = runCatching { repo.precheck(Fixtures.FEM, Fixtures.ADDRESS, StakeContext.Verification(StakeTier.ONE)) }.exceptionOrNull() as DashboardException
        assertEquals("RATE_LIMIT_EXCEEDED", e.code)
        assertEquals("7", e.action)
        assertEquals(429, e.httpStatus)
        assertTrue(e.message.startsWith("Too many verification staking requests"))

        server.enqueue(Fixtures.body("""{"success":false,"code":"RATE_LIMIT_EXCEEDED","message":"Slow down","action":"Wait a moment"}""", 429))
        val noHeader = runCatching { repo.precheck(Fixtures.FEM, Fixtures.ADDRESS, StakeContext.Node) }.exceptionOrNull() as DashboardException
        assertEquals("Wait a moment", noHeader.action)

        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
        val bare = runCatching { repo.precheck(Fixtures.FEM, Fixtures.ADDRESS, StakeContext.Node) }.exceptionOrNull() as DashboardException
        assertEquals("RATE_LIMIT_EXCEEDED", bare.code)
        assertEquals("3", bare.action)
    }

    // --- verify-txn ---

    @Test
    fun `verifyTxn posts address then txId unsigned and throws TXN_VERIFY_FAILED on a 200 success false`() = runTest {
        server.enqueue(Fixtures.body("""{"success":true,"message":"ok"}"""))
        repo.verifyTxn(Fixtures.ADDRESS, "TX1")
        stack.assertUnsignedPost(server.takeRequest(), "/api/algorand/verify-txn", """{"address":"${Fixtures.ADDRESS}","txId":"TX1"}""")

        server.enqueue(Fixtures.body("""{"success":false,"message":"Failed in trasaction verification"}"""))
        val e = runCatching { repo.verifyTxn(Fixtures.ADDRESS, "TX1") }.exceptionOrNull() as DashboardException
        assertEquals("TXN_VERIFY_FAILED", e.code)
        assertEquals("Failed in trasaction verification", e.message)
    }

    // --- submit ---

    @Test
    fun `submit routes registration and node with miner_key, address, txId, amount, asset_id in Stake tsx order`() = runTest {
        server.enqueue(Fixtures.json("stake-submit"))
        val registration = repo.submit(StakeContext.Registration, payload("40"))
        assertEquals("STAKESUBMITTXID0000000000000000000000000000000000000000", registration.txId)
        assertEquals(true, registration.success)
        assertFalse(registration.waived)
        stack.assertSignedPost(
            server.takeRequest(),
            "/api/stake/registration",
            """{"miner_key":"${Fixtures.FEM}","address":"${Fixtures.ADDRESS}","txId":"STAKETXID0000000000000000000000000000000000000000000000","amount":40,"asset_id":"2681521901"}""",
        )

        server.enqueue(Fixtures.body("""{"success":true,"message":"Node stake recorded."}"""))
        repo.submit(StakeContext.Node, payload("100.00"))
        stack.assertSignedPost(
            server.takeRequest(),
            "/api/stake/node-staking",
            """{"miner_key":"${Fixtures.FEM}","address":"${Fixtures.ADDRESS}","txId":"STAKETXID0000000000000000000000000000000000000000000000","amount":100,"asset_id":"2681521901"}""",
        )
    }

    @Test
    fun `submit verification inserts type before asset_id`() = runTest {
        server.enqueue(Fixtures.body("""{"success":true,"message":"Verification stake recorded."}"""))
        repo.submit(StakeContext.Verification(StakeTier.TWO), payload("20"))
        stack.assertSignedPost(
            server.takeRequest(),
            "/api/stake/verification",
            """{"miner_key":"${Fixtures.FEM}","address":"${Fixtures.ADDRESS}","txId":"STAKETXID0000000000000000000000000000000000000000000000","amount":20,"type":"two","asset_id":"2681521901"}""",
        )
    }

    @Test
    fun `submit surfaces a waived registration as a success and rejects a fractional amount before any request`() = runTest {
        server.enqueue(Fixtures.body("""{"success":true,"message":"Registration stake waived by active event","waived":true}"""))
        val waived = repo.submit(StakeContext.Registration, payload("40"))
        assertTrue(waived.waived)
        assertEquals(true, waived.success)
        assertNull(waived.txId)
        server.takeRequest()

        val e = runCatching { repo.submit(StakeContext.Registration, payload("12.5")) }.exceptionOrNull() as DashboardException
        assertEquals("INVALID_INPUT", e.code)
        assertEquals(1, server.requestCount)
    }

    // --- withdraw ---

    @Test
    fun `withdraw routes by context with address then miner_key and normalises the message ok answer`() = runTest {
        for ((context, path) in listOf(
            StakeContext.Registration to "/api/stake/r-withdraw",
            StakeContext.Node to "/api/stake/n-withdraw",
            StakeContext.Verification(StakeTier.ONE) to "/api/stake/stake-withdraw",
        )) {
            server.enqueue(Fixtures.body("""{"message":"ok","txId":"WITHDRAWTX-$path"}"""))
            val result = repo.withdraw(context, Fixtures.ADDRESS, Fixtures.FEM)
            assertEquals("WITHDRAWTX-$path", result.txId)
            assertEquals(true, result.success)
            stack.assertSignedPost(server.takeRequest(), path, """{"address":"${Fixtures.ADDRESS}","miner_key":"${Fixtures.FEM}"}""")
        }
    }

    @Test
    fun `withdraw errors carry the dashboard envelope`() = runTest {
        server.enqueue(Fixtures.body("""{"success":false,"code":"DEVICE_NOT_FOUND","message":"Device not found","action":"Verify the miner key and try again."}""", 404))
        val e = runCatching { repo.withdraw(StakeContext.Node, Fixtures.ADDRESS, Fixtures.FEM) }.exceptionOrNull() as DashboardException
        assertEquals("DEVICE_NOT_FOUND", e.code)
        assertEquals("Verify the miner key and try again.", e.action)
        assertEquals(404, e.httpStatus)
    }
}

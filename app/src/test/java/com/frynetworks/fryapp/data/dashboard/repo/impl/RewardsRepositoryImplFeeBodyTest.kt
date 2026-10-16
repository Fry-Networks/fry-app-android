package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.network.dashboard.DashboardException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 0.4.9: `fee_tx_id` rides the non-preview claim request only, through the same signed canonical body. */
class RewardsRepositoryImplFeeBodyTest {

    private val server = MockWebServer()
    private lateinit var stack: DashboardStack
    private lateinit var repo: RewardsRepositoryImpl

    @Before
    fun start() {
        server.start()
        stack = DashboardStack(server)
        repo = RewardsRepositoryImpl(stack.api, FakeRewardSummaryCacheDao(), FakeAssetTotalsCacheDao(), { Fixtures.ADDRESS }, stack.bus, now = { 42L }, onServerTime = {})
    }

    @After fun stop() = server.shutdown()

    @Test
    fun `the production repository is the fee-aware one the view model sends the fee through`() {
        assertTrue(repo is com.frynetworks.fryapp.data.dashboard.repo.FeeAwareRewardsRepository)
    }

    @Test
    fun `the envelope request carries fee_tx_id after miner_key and no, signed over the canonical body`() = runTest {
        server.enqueue(Fixtures.json("claim-custodial"))
        repo.claimEnvelope(Fixtures.FEM, null, "FEETX1")
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","fee_tx_id":"FEETX1"}""")

        server.enqueue(Fixtures.json("claim-envelope-user-pays"))
        repo.claimEnvelope(Fixtures.FEM, 7, "FEETX2")
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","no":7,"fee_tx_id":"FEETX2"}""")
    }

    @Test
    fun `a null fee gives exactly the old body and the 2-arg call is unchanged`() = runTest {
        server.enqueue(Fixtures.json("claim-custodial"))
        repo.claimEnvelope(Fixtures.FEM, 7, null)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","no":7}""")

        server.enqueue(Fixtures.json("claim-custodial"))
        repo.claimEnvelope(Fixtures.FEM)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}"}""")
    }

    @Test
    fun `a preview never carries fee_tx_id`() = runTest {
        server.enqueue(Fixtures.json("claim-preview"))
        repo.claimPreview(Fixtures.FEM, 7)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","no":7,"preview":true}""")
    }

    @Test
    fun `402 and 503 fee rejections surface their code and status`() = runTest {
        for ((code, status) in listOf("FEE_INVALID" to 402, "FEE_REUSED" to 402, "FEE_IN_USE" to 402, "FEE_REQUIRED" to 402, "FEE_UNVERIFIABLE" to 503)) {
            server.enqueue(Fixtures.body("""{"success":false,"code":"$code","message":"m"}""").setResponseCode(status))
            val e = runCatching { repo.claimEnvelope(Fixtures.FEM, null, "FEETX1") }.exceptionOrNull() as DashboardException
            assertEquals(code, e.code)
            assertEquals(status, e.httpStatus)
            server.takeRequest()
        }
    }

    @Test
    fun `a custodial confirm answer carries code and terminal and an old answer has neither`() = runTest {
        server.enqueue(Fixtures.body("""{"success":false,"code":"CLAIM_EXPIRED","terminal":true,"message":"gone"}"""))
        val r: ConfirmResultResponse = repo.claimConfirmCustodial("TX1")
        assertEquals("CLAIM_EXPIRED", r.code)
        assertEquals(true, r.terminal)
        assertEquals(false, r.success)
        server.takeRequest()

        server.enqueue(Fixtures.body("""{"success":false,"code":"NETWORK_ERROR"}"""))
        val old = repo.claimConfirmCustodial("TX1")
        assertEquals("NETWORK_ERROR", old.code)
        assertNull(old.terminal)
    }
}

package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.WalletBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * After the stake transfer may have reached the network, Retry and reopening the sheet resume from its transaction id.
 * A new transfer is built only when that transaction is provably dead: unknown to the pool and past its last valid round.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StakeViewModelPostBroadcastTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val fake = FakeWalletBridge()
    private val backing = FakeAlgodRepository()

    private var status: TxStatus = TxStatus.Unknown
    private var statusError: Throwable? = null
    private var round: Long? = 1_000L
    private var waitError: Throwable? = null
    private var throwOnSubmit: Throwable? = null
    private var bridgeLastValid: Long? = 1_500L
    private var decodedTxId: String? = TX
    private var decodeThrows = false
    private var signThrows: Throwable? = null

    private val algod = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus {
            statusError?.let { throw it }
            return status
        }

        override suspend fun currentRound(): Long? = round

        override suspend fun waitConfirmed(txId: String, rounds: Int) {
            backing.waited += txId
            waitError?.let { throw it }
        }
    }

    /** The scripted bridge, but the built transfer decodes with its transaction id (as the real bridge reports it) and `submit` can fail after signing. */
    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun decodeTxn(txnB64: String): TxnSummary {
            val summary = fake.decodeTxn(txnB64)
            if (summary.type == "axfer" && decodeThrows) throw IllegalStateException("cannot decode")
            return if (summary.type == "axfer") summary.copy(txId = decodedTxId, lastValid = bridgeLastValid) else summary
        }

        override suspend fun signTxns(groups: List<List<com.frynetworks.fryapp.wallet.TxnToSign>>): List<List<String?>> {
            signThrows?.let { throw it }
            return fake.signTxns(groups)
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            throwOnSubmit?.let {
                fake.submitted += signedB64
                throw it
            }
            return fake.submit(signedB64, waitRounds)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake.reconnectAddress = TEST_ADDRESS
        fake.submitTxIds = listOf(TX)
        miners.products = mapOf(
            key to Product(
                key = "FEM",
                reward = ProductReward(
                    stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
                    tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
                ),
            ),
        )
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"))
        backing.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StakeViewModel(miners, rewards, stakes, algod, bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L), openUri = {})

    private fun transfers() = fake.built.count { it.startsWith("axfer|") }

    private fun failed(vm: StakeViewModel): StakeState.Failed {
        val state = vm.uiState.value.state
        assertTrue("expected Failed but was $state", state is StakeState.Failed)
        return state as StakeState.Failed
    }

    private fun begin(): StakeViewModel {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        return vm
    }

    private fun assertDone(vm: StakeViewModel) = assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)

    // --- resume from the txId in each post-broadcast state ----------------------------------------------------------

    @Test
    fun `a failure while waiting for confirmation is resumed from the transaction, not repeated`() = runTest {
        waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        assertEquals(TX, failed(vm).txId)
        waitError = null
        status = TxStatus.Confirmed(1_200)

        vm.retry()

        assertDone(vm)
        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
        assertEquals(1, stakes.submitCalls.size)
    }

    @Test
    fun `a transaction still in the pool is waited for again, not repeated`() = runTest {
        waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        waitError = null
        status = TxStatus.InPool

        vm.retry()

        assertDone(vm)
        assertEquals(listOf(TX, TX), backing.waited)
        assertEquals(1, transfers())
    }

    @Test
    fun `an unknown transaction status never builds a new transfer`() = runTest {
        waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        status = TxStatus.Unknown
        stakes.verifyError = DashboardException("TXN_VERIFY_FAILED", "not found")

        repeat(3) {
            vm.retry()
            assertEquals(TX, failed(vm).txId)
        }

        assertEquals(1, transfers())
    }

    @Test
    fun `a failure while verifying is resumed from the transaction`() = runTest {
        stakes.verifyError = DashboardException("TXN_VERIFY_FAILED", "not yet")
        val vm = begin()
        assertEquals(TX, failed(vm).txId)
        stakes.verifyError = null
        status = TxStatus.Confirmed(1_200)

        vm.retry()

        assertDone(vm)
        assertEquals(2, stakes.verifyCalls.size)
        assertEquals(1, transfers())
    }

    @Test
    fun `a failure while recording is resumed from the transaction`() = runTest {
        stakes.submitError = IOException("offline")
        val vm = begin()
        assertEquals(TX, failed(vm).txId)
        stakes.submitError = null
        status = TxStatus.Confirmed(1_200)

        vm.retry()

        assertDone(vm)
        assertEquals(2, stakes.submitCalls.size)
        assertEquals(1, transfers())
    }

    // --- submit itself fails after the transfer was signed -----------------------------------------------------------

    private fun submitFailsThenRetry(error: Throwable) = runTest {
        throwOnSubmit = error
        val vm = begin()
        val f = failed(vm)
        assertEquals("the failure must carry the transaction id derived before submit", TX, f.txId)
        assertTrue(f.recoverable)
        assertEquals(1, transfers())
        throwOnSubmit = null
        status = TxStatus.Confirmed(1_200)

        vm.retry()

        assertDone(vm)
        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
    }

    @Test
    fun `a bridge timeout from submit leads to resume, not a second transfer`() = submitFailsThenRetry(BridgeException(BridgeErrorCode.TIMEOUT, "no answer"))

    @Test
    fun `a wrong-shape reply from submit leads to resume, not a second transfer`() = submitFailsThenRetry(IllegalStateException("Not a JSON Object"))

    @Test
    fun `an IOException from submit leads to resume, not a second transfer`() = submitFailsThenRetry(IOException("connection reset"))

    @Test
    fun `reopening the sheet after a failed submit resumes instead of building again`() = runTest {
        throwOnSubmit = IOException("connection reset")
        val vm = begin()
        throwOnSubmit = null
        status = TxStatus.Absent
        round = 1_500L

        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TX, failed(vm).txId)
        assertEquals(1, transfers())

        status = TxStatus.Confirmed(1_200)
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertDone(vm)
        assertEquals(1, transfers())
    }

    // --- rebuild only when provably dead -----------------------------------------------------------------------------

    private fun failedSubmitThenRetryAt(round: Long?, status: TxStatus): StakeViewModel {
        throwOnSubmit = IOException("connection reset")
        val vm = begin()
        throwOnSubmit = null
        this.status = status
        this.round = round
        vm.retry()
        return vm
    }

    @Test
    fun `at the last valid round the transaction can still land, so no rebuild`() = runTest {
        val vm = failedSubmitThenRetryAt(round = 1_500L, status = TxStatus.Absent)

        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TX, failed(vm).txId)
        assertEquals(1, transfers())
    }

    @Test
    fun `one round past the last valid round a rebuild is allowed and needs a new confirmation`() = runTest {
        val vm = failedSubmitThenRetryAt(round = 1_501L, status = TxStatus.Absent)

        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is StakeState.Ready)
        assertEquals(1, transfers())

        round = 1_501L
        vm.confirm()
        assertDone(vm)
        assertEquals(2, transfers())
    }

    @Test
    fun `without a last valid round from the bridge the bound is the build round plus 1001`() = runTest {
        bridgeLastValid = null
        var vm = failedSubmitThenRetryAt(round = 2_001L, status = TxStatus.Absent)
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, transfers())

        vm.retry()
        round = 2_002L
        vm.retry()
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is StakeState.Ready)
    }

    @Test
    fun `the earlier of the bridge's last valid round and the bound is used`() = runTest {
        bridgeLastValid = 5_000L // later than the build round plus 1001 = 2001
        val vm = failedSubmitThenRetryAt(round = 2_002L, status = TxStatus.Absent)
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is StakeState.Ready)
    }

    @Test
    fun `past the last valid round only an absent transaction may be rebuilt`() = runTest {
        for (blocked in listOf(TxStatus.InPool, TxStatus.Unknown, TxStatus.Rejected("pool error"), TxStatus.Confirmed(1_200))) {
            val vm = failedSubmitThenRetryAt(round = 9_999L, status = blocked)
            assertTrue("$blocked: ${vm.uiState.value.state}", vm.uiState.value.state !is StakeState.Ready)
        }
        assertEquals(4, transfers())
    }

    @Test
    fun `a failing status lookup or an unknown round never rebuilds`() = runTest {
        throwOnSubmit = IOException("connection reset")
        val vm = begin()
        throwOnSubmit = null
        status = TxStatus.Absent

        stakes.verifyError = DashboardException("TXN_VERIFY_FAILED", "not found")
        statusError = IOException("algod down")
        round = 9_999L
        vm.retry()
        assertEquals(TX, failed(vm).txId)

        statusError = null
        round = null
        vm.retry()
        assertEquals(TX, failed(vm).txId)
        assertEquals(1, transfers())
    }

    @Test
    fun `with no known last valid round a transfer is never rebuilt`() = runTest {
        bridgeLastValid = null
        round = null // the node's round cannot be read when the transfer is built
        throwOnSubmit = IOException("connection reset")
        val vm = begin()
        throwOnSubmit = null
        status = TxStatus.Absent
        round = 9_999L

        vm.retry()

        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, transfers())
    }

    // --- liveness: a pool that forgot a confirmed transaction -------------------------------------------------------------

    @Test
    fun `an unknown status with the dashboard verifying it goes on to record, building nothing`() = runTest {
        waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        waitError = null
        status = TxStatus.Unknown // the pool entry aged out

        vm.retry()

        assertDone(vm)
        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
        assertEquals(1, stakes.submitCalls.size)
    }

    @Test
    fun `an unknown status the dashboard cannot verify stays pending with the txId, building nothing`() = runTest {
        waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        stakes.verifyError = DashboardException("TXN_VERIFY_FAILED", "not found")
        status = TxStatus.Unknown

        vm.retry()

        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TX, failed(vm).txId)
        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
        assertTrue(stakes.submitCalls.isEmpty())
    }

    // --- an unresolved transfer is never replaced by another stake --------------------------------------------------------

    private val otherKey = "FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210"

    private fun failedSubmitThenOtherStake(start: (StakeViewModel) -> Unit): StakeViewModel {
        miners.products = miners.products + (otherKey to miners.products.getValue(key))
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"), FryAsset.FNODE.id to BigDecimal("2"))
        backing.markOptedIn(FryAsset.FNODE.id, 1_000_000_000L)
        throwOnSubmit = IOException("connection reset")
        val vm = begin()
        throwOnSubmit = null
        start(vm)
        vm.confirm()
        return vm
    }

    private fun assertOtherStakeBlockedAndFirstResumable(vm: StakeViewModel) {
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TX, failed(vm).txId)
        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
        status = TxStatus.Confirmed(1_200)
        vm.retry()
        assertDone(vm)
        assertEquals(1, transfers())
        assertEquals(1, stakes.submitCalls.size)
    }

    @Test
    fun `a stake for another context neither builds nor loses the unresolved transaction`() = runTest {
        val vm = failedSubmitThenOtherStake { it.start(key, StakeContext.Node, byod = false) }
        assertOtherStakeBlockedAndFirstResumable(vm)
    }

    @Test
    fun `a stake for another miner neither builds nor loses the unresolved transaction`() = runTest {
        val vm = failedSubmitThenOtherStake { it.start(otherKey, StakeContext.Registration, byod = false) }
        assertOtherStakeBlockedAndFirstResumable(vm)
    }

    // --- a transfer whose id could not be learned before it was sent -------------------------------------------------------

    private fun unknownBroadcastAfterFailedSubmit(): StakeViewModel {
        throwOnSubmit = IOException("connection reset")
        val vm = begin()
        throwOnSubmit = null
        val f = failed(vm)
        assertEquals("TXN_PENDING", f.code)
        assertNull(f.txId)
        assertTrue(f.recoverable)
        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
        return vm
    }

    private fun assertNothingFurtherBuilt(vm: StakeViewModel) {
        status = TxStatus.Absent
        round = 9_999L

        vm.retry()
        assertEquals("TXN_PENDING", failed(vm).code)

        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("TXN_PENDING", failed(vm).code)

        miners.products = miners.products + ("FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210" to miners.products.getValue(key))
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"), FryAsset.FNODE.id to BigDecimal("2"))
        backing.markOptedIn(FryAsset.FNODE.id, 1_000_000_000L)
        vm.start(key, StakeContext.Node, byod = false)
        vm.confirm()
        assertEquals("TXN_PENDING", failed(vm).code)
        vm.start("FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210", StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("TXN_PENDING", failed(vm).code)

        assertEquals(1, transfers())
        assertEquals(1, fake.signRequests.size)
    }

    @Test
    fun `no transaction id and a failing submit refuses every further stake`() = runTest {
        decodedTxId = null
        assertNothingFurtherBuilt(unknownBroadcastAfterFailedSubmit())
    }

    @Test
    fun `a transaction that cannot be decoded and a failing submit refuses every further stake`() = runTest {
        decodeThrows = true
        assertNothingFurtherBuilt(unknownBroadcastAfterFailedSubmit())
    }

    @Test
    fun `no transaction id but a good submit behaves as usual and resumes from the id submit returned`() = runTest {
        decodedTxId = null
        waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        assertEquals(TX, failed(vm).txId)
        waitError = null
        status = TxStatus.Confirmed(1_200)

        vm.retry()

        assertDone(vm)
        assertEquals(1, transfers())
    }

    @Test
    fun `no transaction id and a good submit lifts the mark for the next stake`() = runTest {
        decodedTxId = null
        val vm = begin()
        assertDone(vm)

        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()

        assertDone(vm)
        assertEquals(2, transfers())
    }

    @Test
    fun `no transaction id and a rejected signature sent nothing, so the next stake builds`() = runTest {
        decodedTxId = null
        fake.signBehaviour = listOf(FakeWalletBridge.SignBehaviour.Reject)
        val vm = begin()
        assertEquals("USER_REJECTED", failed(vm).code)
        fake.signBehaviour = listOf(FakeWalletBridge.SignBehaviour.Approve)

        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()

        assertDone(vm)
        assertEquals(2, transfers())
    }

    @Test
    fun `no transaction id and a cancelled signing sent nothing, so the next stake builds`() = runTest {
        decodedTxId = null
        signThrows = kotlinx.coroutines.CancellationException("cancelled")
        val vm = begin()
        signThrows = null

        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()

        assertDone(vm)
        assertEquals(2, transfers())
    }

    // --- control ------------------------------------------------------------------------------------------------------

    @Test
    fun `after a recorded stake the next stake builds its own transfer`() = runTest {
        val vm = begin()
        assertDone(vm)
        assertEquals(1, transfers())

        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()

        assertDone(vm)
        assertEquals(2, transfers())
    }

    private companion object {
        const val TX = "DERIVEDTX"
    }
}

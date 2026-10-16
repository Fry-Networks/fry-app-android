package com.frynetworks.fryapp.pending

import android.app.Activity
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.auth.SignOutUseCase
import com.frynetworks.fryapp.data.pending.PendingLookup
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.data.pending.TransferState
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.fakes.FakeNextAuthApi
import com.frynetworks.fryapp.fakes.OTHER_ADDRESS
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.ui.miners.stake.StakeViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Crash and update survival of a pending stake transfer or claim fee, on a real device, with every network edge faked from a
 * ledger that lives in the debug app's filesDir (see [DeviceHarness]). One scenario is several PHASES, each its own
 * `am instrument -e scenario <name> -e phase <n>` run, with `am force-stop` (and for update-survival `install -r`) between them by
 * scripts/device_matrix.sh. A phase that simulates a crash stops at the exact point and returns; the next phase is a new process.
 * Every scenario's last phase asserts EXACTLY ONE submit reached the fake chain. Run without arguments, it skips itself.
 */
class PendingTransferDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    private class Failure(message: String) : AssertionError(message)

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw Failure(message)
    }

    @Test
    fun phase() {
        val scenario = args.getString("scenario")
        val phase = args.getString("phase")?.toIntOrNull()
        assumeTrue("no scenario/phase given: nothing to do", scenario != null && phase != null)
        val context = instrumentation.targetContext
        check(context.packageName == "com.frynetworks.fryapp.debug", "refusing to run against ${context.packageName}")
        when (scenario) {
            "crash-before-submit" -> crashBeforeSubmit(context, phase!!)
            "absent-boundaries" -> absentBoundaries(context, phase!!)
            "crash-after-submit" -> crashAfterSubmit(context, phase!!, updateCheck = false)
            "crash-before-remove" -> crashBeforeRemove(context, phase!!)
            "update-survival" -> crashAfterSubmit(context, phase!!, updateCheck = true)
            "claim-fee" -> claimFee(context, phase!!)
            "claim-fee-repeat" -> claimFeeRepeat(context, phase!!)
            "cancel-after-submit" -> cancelAfterSubmit(context, phase!!)
            "logout" -> logout(context, phase!!)
            "different-sender" -> differentSender(context, phase!!)
            else -> throw Failure("unknown scenario $scenario")
        }
    }

    // --- scenarios ---------------------------------------------------------------------------------------------------------

    /** Window 1: recorded, then the process dies before submit reached the network. Nothing was sent, so once provably dead one rebuild is allowed. */
    private fun crashBeforeSubmit(context: android.content.Context, phase: Int) = when (phase) {
        1 -> stakeUntilHang(context, Hang.BEFORE_NETWORK, expectSubmits = 0)
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            h.ledger.setRounds(1_501, 1_501, 1_501)
            rebuildAndFinish(h, expectSigns = 2)
        }
        else -> throw Failure("no phase $phase")
    }

    /** The boundaries of "provably dead", each in a fresh view model over the same on-disk record. */
    private fun absentBoundaries(context: android.content.Context, phase: Int) = when (phase) {
        1 -> stakeUntilHang(context, Hang.BEFORE_NETWORK, expectSubmits = 0)
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            h.ledger.setRounds(1_500, 1_500, 1_500) // round == last valid round: still able to land
            expectBlocked(h, "every source at lv")
        }
        3 -> {
            val h = DeviceHarness(context, Hang.NONE)
            h.ledger.setRounds(1_500, 1_501, 1_501)
            expectBlocked(h, "algod lagging at lv")
            h.ledger.setRounds(1_501, 1_500, 1_501)
            expectBlocked(h, "indexer 1 lagging at lv")
            h.ledger.setRounds(1_501, 1_501, 1_500)
            expectBlocked(h, "indexer 2 lagging at lv")
            report(h, "all three boundaries blocked")
        }
        4 -> {
            val h = DeviceHarness(context, Hang.NONE)
            h.ledger.setRounds(1_501, 1_501, 1_501)
            rebuildAndFinish(h, expectSigns = 2)
        }
        else -> throw Failure("no phase $phase")
    }

    /** Window 2: on the chain, the process dies before the dashboard recorded it. */
    private fun crashAfterSubmit(context: android.content.Context, phase: Int, updateCheck: Boolean) = when (phase) {
        1 -> {
            stakeUntilHang(context, Hang.BEFORE_RECORD, expectSubmits = 1)
            if (updateCheck) {
                val h = DeviceHarness(context, Hang.NONE)
                h.ledger.update { it.lastUpdateTime = lastUpdateTime(context) }
            }
            Unit
        }
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            if (updateCheck) {
                val before = h.ledger.read().lastUpdateTime
                check(before != 0L && before != lastUpdateTime(context), "the app was not updated between the phases (lastUpdateTime $before)")
            }
            resumeToDone(h, TEST_ADDRESS, expectSigns = 1, expectRecordCalls = 1)
        }
        else -> throw Failure("no phase $phase")
    }

    /** Window 3: recorded by the dashboard, the process dies before the pending record was removed. */
    private fun crashBeforeRemove(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness(context, Hang.BEFORE_REMOVE)
            h.resetWorld()
            runStake(h, TEST_ADDRESS)
            awaitReached(h)
            val s = h.ledger.read()
            check(s.submits == 1, "submits=${s.submits} at the crash")
            check(s.recorded == setOf(Blobs.STAKE_1500_ID), "dashboard recorded ${s.recorded}")
            check(record(h, TEST_ADDRESS) != null, "the pending record is gone at the crash")
            report(h, "crashed after the dashboard record, before remove")
        }
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            resumeToDone(h, TEST_ADDRESS, expectSigns = 1, expectRecordCalls = 2)
        }
        else -> throw Failure("no phase $phase")
    }

    /** The claim fee: paid once (on the chain), the process dies before the envelope; the new process continues and never pays again. */
    private fun claimFee(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness(context, Hang.BEFORE_ENVELOPE)
            h.resetWorld()
            h.ledger.setRounds(66_029_500, 66_029_500, 66_029_500)
            val vm = h.claimVm(h.session(TEST_ADDRESS))
            vm.start(h.key)
            await("claim preview") { vm.uiState.value.state is ClaimState.Preview }
            vm.confirm()
            awaitReached(h)
            val s = h.ledger.read()
            check(s.submits == 1 && s.chain.keys == setOf(Blobs.FEE_ID), "fee submits=${s.submits} chain=${s.chain.keys}")
            val r = feeRecord(h)
            check(r != null && r.txId == Blobs.FEE_ID && r.lastValid == Blobs.FEE_LV && r.state == TransferState.PRE_SUBMIT, "fee record $r")
            report(h, "fee paid and recorded, crashed before the envelope")
        }
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            val vm = h.claimVm(h.session(TEST_ADDRESS))
            vm.start(h.key)
            await("claim preview") { vm.uiState.value.state is ClaimState.Preview }
            vm.confirm()
            await("claim done") { (vm.uiState.value.state as? ClaimState).let { it is ClaimState.Done || it is ClaimState.Failed } }
            check(vm.uiState.value.state is ClaimState.Done, "claim ended in ${vm.uiState.value.state}")
            val s = h.ledger.read()
            check(s.submits == 1, "fee submits=${s.submits}, the fee was paid again")
            check(s.signs == 1, "signs=${s.signs}")
            check(feeRecord(h) == null, "the spent fee record was not cleared")
            report(h, "claim done on the first fee")
        }
        else -> throw Failure("no phase $phase")
    }

    /** The corrected fee rule: a paid fee is reused through repeated REWARD_ALREADY_CLAIMING, and cleared only by the claim's success. */
    private fun claimFeeRepeat(context: android.content.Context, phase: Int) = when (phase) {
        1, 2 -> {
            val h = DeviceHarness(context, Hang.ENVELOPE_ALREADY_CLAIMING)
            if (phase == 1) {
                h.resetWorld()
                h.ledger.setRounds(66_029_500, 66_029_500, 66_029_500)
            }
            val vm = h.claimVm(h.session(TEST_ADDRESS))
            vm.start(h.key)
            await("claim preview") { vm.uiState.value.state is ClaimState.Preview }
            vm.confirm()
            await("claim failed") { vm.uiState.value.state is ClaimState.Failed }
            val failed = vm.uiState.value.state as ClaimState.Failed
            check(failed.code == "REWARD_ALREADY_CLAIMING", "phase $phase ended in ${failed.code}")
            val s = h.ledger.read()
            check(s.submits == 1 && s.chain.keys == setOf(Blobs.FEE_ID), "fee submits=${s.submits} chain=${s.chain.keys}")
            check(feeRecord(h)?.txId == Blobs.FEE_ID, "the confirmed fee record was cleared by REWARD_ALREADY_CLAIMING")
            report(h, "REWARD_ALREADY_CLAIMING, fee record kept")
        }
        3 -> {
            val h = DeviceHarness(context, Hang.NONE)
            val vm = h.claimVm(h.session(TEST_ADDRESS))
            vm.start(h.key)
            await("claim preview") { vm.uiState.value.state is ClaimState.Preview }
            vm.confirm()
            await("claim done") { vm.uiState.value.state.let { it is ClaimState.Done || it is ClaimState.Failed } }
            check(vm.uiState.value.state is ClaimState.Done, "claim ended in ${vm.uiState.value.state}")
            val s = h.ledger.read()
            check(s.submits == 1 && s.signs == 1, "fee submits=${s.submits} signs=${s.signs}: the fee was paid again")
            check(feeRecord(h) == null, "the fee record was not cleared by the claim's success")
            report(h, "claim succeeded on the first fee, record cleared")
        }
        else -> throw Failure("no phase $phase")
    }

    /** MEDIUM-1: the job is cancelled the moment submit returns; the record must still be there, with the right txid, for the next process. */
    private fun cancelAfterSubmit(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness(context, Hang.CANCEL_AFTER_SUBMIT)
            h.resetWorld()
            runStake(h, TEST_ADDRESS)
            awaitReached(h)
            Thread.sleep(1_000) // let the cancelled job unwind
            val s = h.ledger.read()
            check(s.submits == 1 && s.chain.keys == setOf(Blobs.STAKE_1500_ID), "submits=${s.submits} chain=${s.chain.keys}")
            check(s.recorded.isEmpty(), "dashboard recorded ${s.recorded} after the cancel")
            val r = record(h, TEST_ADDRESS)
            check(r != null && r.txId == Blobs.STAKE_1500_ID && r.lastValid == 1_500L, "record after the cancel: $r")
            report(h, "job cancelled after submit, record intact")
        }
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            resumeToDone(h, TEST_ADDRESS, expectSigns = 1, expectRecordCalls = 1)
        }
        else -> throw Failure("no phase $phase")
    }

    /** Record, crash; then the real sign-out path; then the same sender returns and the record resumes. */
    private fun logout(context: android.content.Context, phase: Int) = when (phase) {
        1 -> stakeUntilHang(context, Hang.BEFORE_RECORD, expectSubmits = 1)
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            val before = record(h, TEST_ADDRESS)
            check(before != null, "no record to survive the sign-out")
            val session = h.session(TEST_ADDRESS)
            val signOut = SignOutUseCase(FakeNextAuthApi(), h.bridge, session, clearCaches = {})
            runBlocking { signOut.signOut() }
            check(session.state.value is SessionState.SignedOut, "sign-out left ${session.state.value}")
            check(record(h, TEST_ADDRESS) == before, "the record changed across the sign-out")
            check(h.ledger.read().submits == 1, "submits=${h.ledger.read().submits}")
            report(h, "signed out, record intact")
        }
        3 -> {
            val h = DeviceHarness(context, Hang.NONE)
            resumeToDone(h, TEST_ADDRESS, expectSigns = 1, expectRecordCalls = 1)
        }
        else -> throw Failure("no phase $phase")
    }

    /** A record of one sender never blocks another: A is stuck before the network, B stakes freely, A's record is untouched. */
    private fun differentSender(context: android.content.Context, phase: Int) = when (phase) {
        1 -> stakeUntilHang(context, Hang.BEFORE_NETWORK, expectSubmits = 0)
        2 -> {
            val h = DeviceHarness(context, Hang.NONE)
            val a = record(h, TEST_ADDRESS)
            check(a != null && a.txId == Blobs.STAKE_1500_ID, "sender A's record is missing")
            val vm = h.stakeVm(h.session(OTHER_ADDRESS))
            vm.start(h.key, DeviceHarness.REGISTRATION, byod = false)
            await("sender B ready") { vm.uiState.value.state.let { it is StakeState.Ready || it is StakeState.Failed } }
            check(vm.uiState.value.state is StakeState.Ready, "sender B was blocked: ${vm.uiState.value.state}")
            vm.confirm()
            awaitDone(vm)
            val s = h.ledger.read()
            check(s.submits == 1 && s.chain.keys == setOf(Blobs.STAKE_5000_ID), "submits=${s.submits} chain=${s.chain.keys}")
            check(record(h, OTHER_ADDRESS) == null, "sender B's record was not cleared")
            check(record(h, TEST_ADDRESS) == a, "sender A's record was touched")
            report(h, "sender B staked, sender A untouched")
        }
        else -> throw Failure("no phase $phase")
    }

    // --- building blocks ---------------------------------------------------------------------------------------------------

    /** Phase 1 of most scenarios: reset, run a stake until it reaches [hang], check where it stopped. */
    private fun stakeUntilHang(context: android.content.Context, hang: Hang, expectSubmits: Int) {
        val h = DeviceHarness(context, hang)
        h.resetWorld()
        runStake(h, TEST_ADDRESS)
        awaitReached(h)
        val s = h.ledger.read()
        check(s.submits == expectSubmits, "submits=${s.submits} at the crash, expected $expectSubmits")
        check(s.signs == 1, "signs=${s.signs}")
        val r = record(h, TEST_ADDRESS)
        check(r != null && r.txId == Blobs.STAKE_1500_ID && r.state == TransferState.PRE_SUBMIT && r.lastValid == 1_500L, "record at the crash: $r")
        check(s.recorded.isEmpty(), "dashboard recorded ${s.recorded} before the crash")
        report(h, "crashed at $hang")
    }

    /** Opens the sheet for a fresh stake and confirms it. */
    private fun runStake(h: DeviceHarness, sender: String): StakeViewModel {
        val vm = h.stakeVm(h.session(sender))
        vm.start(h.key, DeviceHarness.REGISTRATION, byod = false)
        await("stake ready") { vm.uiState.value.state is StakeState.Ready }
        vm.confirm()
        return vm
    }

    /** A new process finds the record, must not rebuild, and carries it to Done. */
    private fun resumeToDone(h: DeviceHarness, sender: String, expectSigns: Int, expectRecordCalls: Int) {
        val vm = h.stakeVm(h.session(sender))
        vm.start(h.key, DeviceHarness.REGISTRATION, byod = false)
        awaitDone(vm)
        val s = h.ledger.read()
        check(s.submits == 1, "submits=${s.submits}: the transfer was sent again")
        check(s.signs == expectSigns, "signs=${s.signs}, expected $expectSigns")
        check(s.recorded == setOf(Blobs.STAKE_1500_ID), "dashboard recorded ${s.recorded}")
        check(s.recordCalls == expectRecordCalls, "dashboard record calls=${s.recordCalls}, expected $expectRecordCalls")
        check(record(h, sender) == null, "the pending record was not cleared")
        report(h, "resumed to Done on the first transfer")
    }

    /** The record is proven dead: cleared, the sheet is ready for a fresh stake, and that one submit is the scenario's only one. */
    private fun rebuildAndFinish(h: DeviceHarness, expectSigns: Int) {
        val vm = h.stakeVm(h.session(TEST_ADDRESS))
        vm.start(h.key, DeviceHarness.REGISTRATION, byod = false)
        await("rebuild ready") { vm.uiState.value.state.let { it is StakeState.Ready || it is StakeState.Failed } }
        check(vm.uiState.value.state is StakeState.Ready, "expected a fresh Ready after a proven-dead record, got ${vm.uiState.value.state}")
        check(record(h, TEST_ADDRESS) == null, "the dead record was not cleared")
        check(h.ledger.read().submits == 0, "something was submitted before the rebuild was confirmed")
        vm.confirm()
        awaitDone(vm)
        val s = h.ledger.read()
        check(s.submits == 1, "submits=${s.submits}")
        check(s.signs == expectSigns, "signs=${s.signs}, expected $expectSigns")
        check(s.chain.keys == setOf(Blobs.STAKE_5000_ID), "chain=${s.chain.keys}")
        check(record(h, TEST_ADDRESS) == null, "the pending record was not cleared after Done")
        report(h, "rebuilt after proof of death, one submit")
    }

    /** A new view model over the same record must stay blocked (TXN_PENDING) and send and sign nothing. */
    private fun expectBlocked(h: DeviceHarness, label: String) {
        val signs = h.ledger.read().signs
        val vm = h.stakeVm(h.session(TEST_ADDRESS))
        vm.start(h.key, DeviceHarness.REGISTRATION, byod = false)
        await("blocked: $label") { vm.uiState.value.state.let { it is StakeState.Failed || it is StakeState.Ready || it is StakeState.Done } }
        val state = vm.uiState.value.state
        check(state is StakeState.Failed && state.code == "TXN_PENDING", "$label: expected TXN_PENDING, got $state")
        val s = h.ledger.read()
        check(s.submits == 0 && s.signs == signs, "$label: submits=${s.submits} signs=${s.signs}")
        check(record(h, TEST_ADDRESS)?.txId == Blobs.STAKE_1500_ID, "$label: the record is gone")
        report(h, "blocked: $label")
    }

    // --- plumbing ----------------------------------------------------------------------------------------------------------

    private fun record(h: DeviceHarness, sender: String): PendingTransfer? =
        (h.store.find(sender, TransferKind.STAKE) as? PendingLookup.Found)?.record

    private fun feeRecord(h: DeviceHarness): PendingTransfer? =
        (h.store.get(TEST_ADDRESS, TransferKind.CLAIM_FEE, h.key, "all") as? PendingLookup.Found)?.record

    private fun lastUpdateTime(context: android.content.Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    private fun awaitDone(vm: StakeViewModel) {
        await("stake done") { vm.uiState.value.state.let { it is StakeState.Done || it is StakeState.Failed } }
        val state = vm.uiState.value.state
        check(state is StakeState.Done, "stake ended in $state")
    }

    private fun awaitReached(h: DeviceHarness) {
        runBlocking { withTimeout(TIMEOUT_MILLIS) { h.reached.await() } }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw Failure("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** One line the driver greps: `PHASE_RESULT scenario=.. phase=.. submits=.. signs=.. recorded=.. :: note`. */
    private fun report(h: DeviceHarness, note: String) {
        val s = h.ledger.read()
        val line = "PHASE_RESULT scenario=${args.getString("scenario")} phase=${args.getString("phase")} pid=${android.os.Process.myPid()} submits=${s.submits} signs=${s.signs} " +
            "recorded=${s.recorded.size} :: $note"
        instrumentation.sendStatus(Activity.RESULT_OK, Bundle().apply { putString(Instrumentation_STREAM, line + "\n") })
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
        const val Instrumentation_STREAM = "stream"
    }
}

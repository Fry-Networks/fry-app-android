package com.frynetworks.fryapp.pending

import android.app.Activity
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.frynetworks.fryapp.data.pending.BindingLookup
import com.frynetworks.fryapp.data.pending.PendingLookup
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.ui.miners.claim.ClaimViewModel
import com.frynetworks.fryapp.ui.miners.stake.StakeViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The 0.4.8 fixes on a real device, in the same phase model as [PendingTransferDeviceTest] (which stays untouched): one scenario is
 * several PHASES, each its own `am instrument -e scenario <name> -e phase <n>` run with `am force-stop` between them (scripts/device_matrix_048.sh).
 * A phase that simulates a crash stops at the exact point; the next phase is a new process over the same on-disk state.
 * The `submits=` of a PHASE_RESULT line is the fake chain's count of transactions that reached it. Run without arguments, it skips itself.
 */
class PendingTransferDevice048Test {

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
            "f1-clear-fail" -> f1ClearFail(context, phase!!)
            "f1-no-burn" -> f1NoBurn(context, phase!!)
            "f4-optin-before-network" -> f4BeforeNetwork(context, phase!!)
            "f4-optin-after-submit" -> f4AfterSubmit(context, phase!!)
            "f4-optin-shared-key" -> f4SharedKey(context, phase!!)
            "f3-wrong-miner" -> f3WrongMiner(context, phase!!)
            else -> throw Failure("unknown scenario $scenario")
        }
    }

    // --- F1 ----------------------------------------------------------------------------------------------------------------

    /** The claim succeeds but its fee record cannot be removed. The next claim, in a new process, must pay its own fee: one fee per claim. */
    private fun f1ClearFail(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness048(context, Hang048.REMOVE_FAILS)
            h.resetWorld()
            h.ledger.setRounds(66_029_500, 66_029_500, 66_029_500)
            claimToEnd(h, expect = "done")
            val s = h.ledger.read()
            check(s.submits == 1, "fee submits=${s.submits}")
            check(feeRecord(h) != null, "the fee record was removed although every removal fails")
            check(consumedBinding(h), "the spent fee is not marked consumed in the binding store")
            report(h, "claim 1 done, fee record could not be removed, binding says consumed")
        }
        2 -> {
            val h = DeviceHarness048(context, Hang048.NONE)
            claimToEnd(h, expect = "done")
            val s = h.ledger.read()
            check(s.submits == 2, "fee submits=${s.submits}: the second claim did not pay its own fee")
            check(s.signs == 2, "signs=${s.signs}")
            check(feeRecord(h) == null, "the fee record was not cleared by the second claim")
            check(h.bindings.get(TEST_ADDRESS, h.key, "all") == BindingLookup.None, "the binding was not removed")
            report(h, "claim 2 reconciled the spent record and paid its own fee")
        }
        else -> throw Failure("no phase $phase")
    }

    /** Repeated claim errors: the paid fee is never cleared, never paid again, and the record never changes. */
    private fun f1NoBurn(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness048(context, Hang048.CONFIRM_NEVER)
            h.resetWorld()
            h.ledger.setRounds(66_029_500, 66_029_500, 66_029_500)
            claimToEnd(h, expect = "CONFIRM_PENDING")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}")
            check(feeRecord(h) != null && (h.bindings.get(TEST_ADDRESS, h.key, "all") as? BindingLookup.Found)?.binding?.mode == "custodial", "no custodial binding")
            val snap = snapshot(h)
            h.ledger.update { it.log += "snapshot $snap" }
            report(h, "claim ended CONFIRM_PENDING, fee bound to the custodial claim")
        }
        2, 3 -> {
            val h = DeviceHarness048(context, Hang048.CONFIRM_NEVER)
            claimToEnd(h, expect = "TXN_PENDING")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}: a second fee was paid")
            val before = h.ledger.read().log.last { it.startsWith("snapshot ") }.removePrefix("snapshot ")
            check(snapshot(h) == before, "the fee record or its binding changed")
            report(h, "claim blocked again, record untouched, no new fee")
        }
        else -> throw Failure("no phase $phase")
    }

    // --- F4 ----------------------------------------------------------------------------------------------------------------

    /** Window 1: the opt-in is signed and recorded, the process dies before the network saw it. Once provably dead exactly one opt-in goes out. */
    private fun f4BeforeNetwork(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness048(context, Hang048.BEFORE_NETWORK, optInManaged = true)
            h.resetWorld()
            val vm = stakeAtOptIn(h, DeviceHarness.REGISTRATION)
            vm.optIn()
            awaitReached(h)
            check(h.ledger.read().submits == 0, "something reached the chain")
            val r = optInRecord(h, FryAsset.TFRY)
            check(r != null && r.txId == OptInBlobs048.txId(FryAsset.TFRY.id) && r.lastValid == OptInBlobs048.LV, "opt-in record at the crash: $r")
            report(h, "crashed before the network, opt-in recorded")
        }
        2 -> {
            val h = DeviceHarness048(context, Hang048.NONE, optInManaged = true)
            h.ledger.setRounds(1_501, 1_501, 1_501)
            val vm = stakeAtOptIn(h, DeviceHarness.REGISTRATION)
            vm.optIn()
            await("opt-in rebuilt") { vm.uiState.value.state.let { it != null && it !is StakeState.OptInRequired } }
            check(vm.uiState.value.state !is StakeState.Failed, "ended in ${vm.uiState.value.state}")
            check(optInSubmits(h) == 1, "opt-in submits=${optInSubmits(h)}")
            check(optInRecord(h, FryAsset.TFRY) == null, "the opt-in record was not cleared")
            report(h, "dead record cleared, exactly one opt-in submitted")
        }
        else -> throw Failure("no phase $phase")
    }

    /** Window 2: the opt-in reached the chain, the process dies before reading the result. The next process settles it and sends nothing. */
    private fun f4AfterSubmit(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness048(context, Hang048.CANCEL_AFTER_SUBMIT, optInManaged = true, optInLag = true)
            h.resetWorld()
            val vm = claimAtOptIn(h)
            vm.optIn()
            awaitReached(h)
            check(optInSubmits(h) == 1, "opt-in submits=${optInSubmits(h)}")
            check(optInRecord(h, FryAsset.FNODE) != null, "no opt-in record after the submit")
            report(h, "opt-in on the chain, process cut before the result was read")
        }
        2 -> {
            val h = DeviceHarness048(context, Hang048.NONE, optInManaged = true, optInLag = true)
            val vm = claimAtOptIn(h)
            h.ledger.setRounds(1_001, 1_001, 1_001) // the node now shows the account opted in
            vm.optIn()
            await("claim preview") { vm.uiState.value.state.let { it is ClaimState.Preview || it is ClaimState.Failed } }
            check(vm.uiState.value.state is ClaimState.Preview, "ended in ${vm.uiState.value.state}")
            check(optInSubmits(h) == 1, "opt-in submits=${optInSubmits(h)}: a second opt-in was sent")
            check(optInRecord(h, FryAsset.FNODE) == null, "the settled opt-in record was not cleared")
            report(h, "settled the landed opt-in without a second submit")
        }
        else -> throw Failure("no phase $phase")
    }

    /** Stake and claim share one record per (sender, asset): the claim sheet is blocked by the stake sheet's unresolved opt-in. */
    private fun f4SharedKey(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness048(context, Hang048.BEFORE_NETWORK, optInManaged = true)
            h.resetWorld()
            val vm = stakeAtOptIn(h, StakeContext.Node)
            vm.optIn()
            awaitReached(h)
            check(h.ledger.read().submits == 0, "something reached the chain")
            check(optInRecord(h, FryAsset.FNODE) != null, "the stake sheet recorded no opt-in")
            report(h, "stake sheet crashed before the network")
        }
        2 -> {
            val h = DeviceHarness048(context, Hang048.NONE, optInManaged = true)
            val vm = claimAtOptIn(h)
            vm.optIn()
            await("claim blocked") { vm.uiState.value.state.let { it is ClaimState.Failed || it is ClaimState.Preview } }
            val state = vm.uiState.value.state
            check(state is ClaimState.Failed && state.code == "TXN_PENDING", "expected TXN_PENDING, got $state")
            check(h.ledger.read().submits == 0, "the claim sheet sent an opt-in over the stake sheet's record")
            check(optInRecord(h, FryAsset.FNODE) != null, "the record is gone")
            report(h, "claim sheet blocked by the shared record")
        }
        3 -> {
            val h = DeviceHarness048(context, Hang048.NONE, optInManaged = true)
            h.ledger.setRounds(1_501, 1_501, 1_501)
            val vm = claimAtOptIn(h)
            vm.optIn()
            await("claim preview") { vm.uiState.value.state.let { it is ClaimState.Preview || it is ClaimState.Failed } }
            check(vm.uiState.value.state is ClaimState.Preview, "ended in ${vm.uiState.value.state}")
            check(optInSubmits(h) == 1, "opt-in submits=${optInSubmits(h)}")
            check(optInRecord(h, FryAsset.FNODE) == null, "the opt-in record was not cleared")
            report(h, "dead shared record cleared, exactly one opt-in submitted")
        }
        else -> throw Failure("no phase $phase")
    }

    // --- F3 ----------------------------------------------------------------------------------------------------------------

    /** The recorded transfer is miner A's, the sheet is for miner B: Retry finishes A's and the sheet must name A, never B. */
    private fun f3WrongMiner(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness048(context, Hang048.BEFORE_RECORD)
            h.resetWorld()
            h.ledger.setRounds(1_200, 1_200, 1_200)
            val vm = h.stakeVm(h.session(TEST_ADDRESS))
            vm.start(h.key, DeviceHarness.REGISTRATION, byod = false)
            await("stake ready") { vm.uiState.value.state is StakeState.Ready }
            vm.confirm()
            awaitReached(h)
            check(h.ledger.read().submits == 1, "submits=${h.ledger.read().submits}")
            check((h.store.find(TEST_ADDRESS, TransferKind.STAKE) as? PendingLookup.Found)?.record?.minerKey == h.key, "no stake record for miner A")
            report(h, "miner A's transfer on the chain, dashboard not told")
        }
        2 -> {
            val h = DeviceHarness048(context, Hang048.NONE)
            val vm = h.stakeVm(h.session(TEST_ADDRESS))
            vm.start(h.otherKey, DeviceHarness.REGISTRATION, byod = false)
            await("blocked by A's record") { vm.uiState.value.state.let { it is StakeState.Failed || it is StakeState.Done } }
            check((vm.uiState.value.state as? StakeState.Failed)?.code == "TXN_PENDING", "expected TXN_PENDING, got ${vm.uiState.value.state}")
            vm.retry()
            await("stake done") { vm.uiState.value.state.let { it is StakeState.Done || (it is StakeState.Failed && it.code != "TXN_PENDING") } }
            val ui = vm.uiState.value
            check(ui.state is StakeState.Done, "ended in ${ui.state}")
            check(ui.minerKey == h.otherKey, "the sheet is no longer for miner B: ${ui.minerKey}")
            check(ui.completedMinerKey == h.key, "completedMinerKey=${ui.completedMinerKey}, expected miner A ${h.key}")
            val s = h.ledger.read()
            check(s.submits == 1 && s.recordCalls == 1 && s.recorded == setOf(Blobs.STAKE_1500_ID), "submits=${s.submits} recordCalls=${s.recordCalls} recorded=${s.recorded}")
            report(h, "Done names miner A while the sheet's selection is miner B")
        }
        else -> throw Failure("no phase $phase")
    }

    // --- building blocks ---------------------------------------------------------------------------------------------------

    private fun claimToEnd(h: DeviceHarness048, expect: String) {
        val vm = h.claimVm(h.session(TEST_ADDRESS))
        vm.start(h.key)
        await("claim preview") { vm.uiState.value.state is ClaimState.Preview }
        vm.confirm()
        await("claim end") { vm.uiState.value.state.let { it is ClaimState.Done || it is ClaimState.Failed } }
        val state = vm.uiState.value.state
        val actual = if (state is ClaimState.Done) "done" else (state as ClaimState.Failed).code
        check(actual == expect, "claim ended in $state, expected $expect")
    }

    private fun stakeAtOptIn(h: DeviceHarness048, context: StakeContext): StakeViewModel {
        val vm = h.stakeVm(h.session(TEST_ADDRESS))
        vm.start(h.key, context, byod = false)
        await("stake ready") { vm.uiState.value.state is StakeState.Ready }
        vm.confirm()
        await("opt-in required") { vm.uiState.value.state is StakeState.OptInRequired }
        return vm
    }

    private fun claimAtOptIn(h: DeviceHarness048): ClaimViewModel {
        val vm = h.claimVm(h.session(TEST_ADDRESS))
        vm.start(h.key)
        await("opt-in required") { vm.uiState.value.state is ClaimState.OptInRequired }
        return vm
    }

    private fun feeRecord(h: DeviceHarness048): PendingTransfer? =
        (h.store.get(TEST_ADDRESS, TransferKind.CLAIM_FEE, h.key, "all") as? PendingLookup.Found)?.record

    private fun optInRecord(h: DeviceHarness048, asset: FryAsset): PendingTransfer? =
        (h.store.get(TEST_ADDRESS, TransferKind.ASA_OPTIN, "-", asset.id.toString()) as? PendingLookup.Found)?.record

    private fun consumedBinding(h: DeviceHarness048) =
        (h.bindings.get(TEST_ADDRESS, h.key, "all") as? BindingLookup.Found)?.binding?.consumed == true

    private fun optInSubmits(h: DeviceHarness048) = h.ledger.read().log.count { it.startsWith("optin-submit#") }

    /** A digest of the fee record and its binding as stored, so "untouched" is a comparison of bytes, not of fields. */
    private fun snapshot(h: DeviceHarness048): String {
        val text = (h.prefs.all.entries + h.bindingPrefs.all.entries).sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun awaitReached(h: DeviceHarness048) {
        runBlocking { withTimeout(TIMEOUT_MILLIS) { h.reached.await() } }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw Failure("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** One line the driver greps: `PHASE_RESULT scenario=.. phase=.. pid=.. submits=.. signs=.. recorded=.. :: note`. */
    private fun report(h: DeviceHarness048, note: String) {
        val s = h.ledger.read()
        val line = "PHASE_RESULT scenario=${args.getString("scenario")} phase=${args.getString("phase")} pid=${android.os.Process.myPid()} submits=${s.submits} signs=${s.signs} " +
            "recorded=${s.recorded.size} :: $note"
        instrumentation.sendStatus(Activity.RESULT_OK, Bundle().apply { putString("stream", line + "\n") })
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
    }
}

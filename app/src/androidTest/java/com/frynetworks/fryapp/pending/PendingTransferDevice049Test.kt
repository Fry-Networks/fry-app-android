package com.frynetworks.fryapp.pending

import android.app.Activity
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.frynetworks.fryapp.data.pending.BindingLookup
import com.frynetworks.fryapp.data.pending.PendingLookup
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The 0.4.9 fixes on a real device, in the 0.4.8 phase model ([PendingTransferDevice048Test] stays untouched): one scenario is several
 * PHASES, each its own `am instrument -e scenario <name> -e phase <n>` run with `am force-stop` between them (scripts/device_matrix_049.sh),
 * so every phase is a new process over the same on-disk state. Run without arguments, it skips itself.
 */
class PendingTransferDevice049Test {

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
            "f049FeeRejected" -> f049FeeRejected(context, phase!!)
            "f049CustodialTerminal" -> f049CustodialTerminal(context, phase!!)
            else -> throw Failure("unknown scenario $scenario")
        }
    }

    /** The dashboard answers 402 FEE_INVALID: blocked without Retry, the paid fee record untouched, no second fee across a process death. */
    private fun f049FeeRejected(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness049(context, Edge049.FEE_INVALID)
            h.resetWorld()
            h.ledger.setRounds(66_029_500, 66_029_500, 66_029_500)
            val failed = claimToEnd(h, expectCode = "FEE_INVALID")
            check(!failed.recoverable, "FEE_INVALID offers Retry")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}")
            val record = checkNotNull(feeRecord(h)) { "the paid fee record is gone" }
            check(h.bindings.get(TEST_ADDRESS, h.key, "all") == BindingLookup.None, "a binding was written for a refused envelope")
            check(h.envelopeFees() == listOf(record.txId), "the envelope did not carry the paid fee: ${h.envelopeFees()}")
            val snap = snapshot(h)
            h.ledger.update { it.log += "snapshot $snap" }
            report(h, "FEE_INVALID blocked, fee record intact, fee resent once")
        }
        2 -> {
            val h = DeviceHarness049(context, Edge049.FEE_INVALID)
            val before = h.ledger.read().log.last { it.startsWith("snapshot ") }.removePrefix("snapshot ")
            check(snapshot(h) == before, "the fee record changed across the process death")
            val failed = claimToEnd(h, expectCode = "FEE_INVALID")
            check(!failed.recoverable, "FEE_INVALID offers Retry")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}: a second fee was paid")
            check(snapshot(h) == before, "the fee record changed after the second attempt")
            val record = checkNotNull(feeRecord(h))
            check(h.envelopeFees() == listOf(record.txId, record.txId), "the same fee was not resent: ${h.envelopeFees()}")
            report(h, "new process, next attempt paid nothing, record identical")
        }
        else -> throw Failure("no phase $phase")
    }

    /** The custodial confirm says CLAIM_EXPIRED terminal: the same fee moves to a new envelope once, never a second fee, then it blocks. */
    private fun f049CustodialTerminal(context: android.content.Context, phase: Int) = when (phase) {
        1 -> {
            val h = DeviceHarness049(context, Edge049.CUSTODIAL_TERMINAL)
            h.resetWorld()
            h.ledger.setRounds(66_029_500, 66_029_500, 66_029_500)
            claimToEnd(h, expectCode = "CLAIM_EXPIRED")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}")
            check(feeRecord(h) != null, "the fee record is gone")
            val binding = (h.bindings.get(TEST_ADDRESS, h.key, "all") as? BindingLookup.Found)?.binding
            check(binding?.mode == "custodial" && !binding.rebound, "binding after the terminal answer: $binding")
            report(h, "terminal answer, binding kept, one fee")
        }
        2 -> {
            val h = DeviceHarness049(context, Edge049.CUSTODIAL_TERMINAL)
            claimToEnd(h, expectCode = "CLAIM_EXPIRED")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}: the rebind paid a second fee")
            val record = checkNotNull(feeRecord(h))
            check(h.envelopeFees() == listOf(record.txId, record.txId), "the fee was not moved to the new envelope: ${h.envelopeFees()}")
            val binding = (h.bindings.get(TEST_ADDRESS, h.key, "all") as? BindingLookup.Found)?.binding
            check(binding?.rebound == true, "binding not marked rebound: $binding")
            report(h, "rebound once with the same fee")
        }
        3 -> {
            val h = DeviceHarness049(context, Edge049.CUSTODIAL_TERMINAL)
            claimToEnd(h, expectCode = "TXN_PENDING")
            check(h.ledger.read().submits == 1, "fee submits=${h.ledger.read().submits}")
            check(h.envelopeFees().size == 2, "a third envelope was requested: ${h.envelopeFees()}")
            report(h, "second terminal after the rebind blocks, no further fee or envelope")
        }
        else -> throw Failure("no phase $phase")
    }

    // --- building blocks ---------------------------------------------------------------------------------------------------

    private fun claimToEnd(h: DeviceHarness049, expectCode: String): ClaimState.Failed {
        val vm = h.claimVm(h.session(TEST_ADDRESS))
        vm.start(h.key)
        await("claim preview") { vm.uiState.value.state is ClaimState.Preview }
        vm.confirm()
        await("claim end") { vm.uiState.value.state.let { it is ClaimState.Done || it is ClaimState.Failed } }
        val state = vm.uiState.value.state
        check(state is ClaimState.Failed && state.code == expectCode, "claim ended in $state, expected $expectCode")
        return state as ClaimState.Failed
    }

    private fun feeRecord(h: DeviceHarness049): PendingTransfer? =
        (h.store.get(TEST_ADDRESS, TransferKind.CLAIM_FEE, h.key, "all") as? PendingLookup.Found)?.record

    /** A digest of the fee record and its binding as stored, so "untouched" is a comparison of bytes, not of fields. */
    private fun snapshot(h: DeviceHarness049): String {
        val text = (h.prefs.all.entries + h.bindingPrefs.all.entries).sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw Failure("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** One line the driver greps: `PHASE_RESULT scenario=.. phase=.. pid=.. submits=.. signs=.. recorded=.. :: note`. */
    private fun report(h: DeviceHarness049, note: String) {
        val s = h.ledger.read()
        val line = "PHASE_RESULT scenario=${args.getString("scenario")} phase=${args.getString("phase")} pid=${android.os.Process.myPid()} submits=${s.submits} signs=${s.signs} " +
            "recorded=${s.recorded.size} :: $note"
        instrumentation.sendStatus(Activity.RESULT_OK, Bundle().apply { putString("stream", line + "\n") })
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
    }
}

package com.frynetworks.fryapp.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val versionName: String) : UpdateState
    data class Installing(val versionName: String) : UpdateState
    data class Deferred(val versionName: String, val reason: String) : UpdateState
    data class Failed(val reason: String) : UpdateState

    /** This build cannot update itself from the release channel (e.g. the `.debug` package). */
    data class NotApplicable(val reason: String) : UpdateState
}

enum class UpdateTrigger { LAUNCH, DAILY, MANUAL }

interface UpdateStore {
    var lastCheckMillis: Long
    val lastInstallMessage: String?
}

/** The coordinator's collaborators, as interfaces so the whole flow runs on the JVM in tests. */
interface UpdateSources {
    fun channel(): UpdateChannel
    fun manifestText(channel: UpdateChannel): String?
    fun download(manifest: UpdateManifest): DownloadResult
    fun installedSigner(): SignerFacts?
    fun candidateSigner(apk: File): SignerFacts?
    fun install(apk: File, packageName: String): Boolean

    /**
     * Like [install], but [mayCommit] is asked once more right before the session commits (the
     * APK write into the session takes a moment): false abandons the session and returns false.
     */
    fun install(apk: File, packageName: String, mayCommit: () -> Boolean): Boolean = install(apk, packageName)
}

/**
 * One update pass: manifest (fixed channel URL) → [VersionPolicy] → download (size + SHA-256)
 * → [SignerPolicy] → PackageInstaller session. Any failed check stops the pass before an install
 * session exists. One pass at a time; a launch check runs at most every [LAUNCH_THROTTLE_MS];
 * nothing installs while [InstallInhibitor] is held (a board setup or a signed chain transaction
 * in flight), and an unattended pass (launch, daily) never installs while the app is on screen
 * ([foreground]); only the user's own "Check for updates" may.
 */
class UpdateCoordinator(
    private val installedPackage: String,
    private val installedVersionCode: Long,
    private val sources: UpdateSources,
    private val store: UpdateStore,
    private val inhibited: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val foreground: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)

    fun channel(): UpdateChannel = sources.channel()

    /** Runs a pass unless one is running or (for [UpdateTrigger.LAUNCH]) one ran recently. */
    fun check(trigger: UpdateTrigger): UpdateState {
        if (trigger == UpdateTrigger.LAUNCH && clock() - store.lastCheckMillis in 0 until LAUNCH_THROTTLE_MS) return _state.value
        if (!running.compareAndSet(false, true)) return _state.value
        try {
            store.lastCheckMillis = clock()
            _state.value = UpdateState.Checking
            _state.value = pass(trigger)
        } finally {
            running.set(false)
        }
        return _state.value
    }

    /** Why this pass must not install right now, if anything. */
    private fun deferred(trigger: UpdateTrigger, offer: UpdateManifest): UpdateState? = when {
        inhibited() -> UpdateState.Deferred(offer.versionName, DEFERRED_PROVISIONING)
        trigger != UpdateTrigger.MANUAL && foreground() -> UpdateState.Deferred(offer.versionName, DEFERRED_IN_USE)
        else -> null
    }

    private fun pass(trigger: UpdateTrigger): UpdateState {
        val channel = sources.channel()
        val manifest = when (val parsed = UpdateManifestParser.parse(sources.manifestText(channel), channel)) {
            is ManifestParse.Ok -> parsed.manifest
            is ManifestParse.Invalid -> return UpdateState.Failed("No usable update information (${parsed.reason}).")
        }
        val offer = when (val decision = VersionPolicy.decide(installedPackage, installedVersionCode, manifest)) {
            UpdateDecision.UpToDate -> return UpdateState.UpToDate
            is UpdateDecision.Rejected -> return UpdateState.NotApplicable(decision.reason)
            is UpdateDecision.Offer -> decision.manifest
        }
        deferred(trigger, offer)?.let { return it }
        _state.value = UpdateState.Downloading(offer.versionName)
        val apk = when (val result = sources.download(offer)) {
            is DownloadResult.Ok -> result.file
            is DownloadResult.Failed -> return UpdateState.Failed(result.reason)
        }
        val verdict = SignerPolicy.check(sources.installedSigner(), sources.candidateSigner(apk), offer)
        if (verdict is SignerVerdict.Deny) {
            apk.delete()
            return UpdateState.Failed("Update refused: ${verdict.reason}.")
        }
        // Provisioning, a transaction or the user's return may have started while the APK
        // downloaded; a deferred APK is not kept around (the next pass downloads afresh).
        deferred(trigger, offer)?.let { apk.delete(); return it }
        // The session write takes a moment too: the gateway asks once more right before the commit.
        val committed = sources.install(apk, installedPackage) { deferred(trigger, offer) == null }
        return when {
            committed -> UpdateState.Installing(offer.versionName)
            else -> deferred(trigger, offer)?.also { apk.delete() } ?: UpdateState.Failed("Android would not start the update.")
        }
    }

    companion object {
        const val LAUNCH_THROTTLE_MS = 6L * 60 * 60 * 1000
        const val DEFERRED_PROVISIONING = "waiting until board setup finishes"
        const val DEFERRED_IN_USE = "waiting until the app is not in use"
    }
}

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
}

/**
 * One update pass: manifest (fixed channel URL) → [VersionPolicy] → download (size + SHA-256)
 * → [SignerPolicy] → PackageInstaller session. Any failed check stops the pass before an install
 * session exists. One pass at a time; a launch check runs at most every [LAUNCH_THROTTLE_MS];
 * nothing installs while [InstallInhibitor] is held (provisioning in progress).
 */
class UpdateCoordinator(
    private val installedPackage: String,
    private val installedVersionCode: Long,
    private val sources: UpdateSources,
    private val store: UpdateStore,
    private val inhibited: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
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
            _state.value = pass()
        } finally {
            running.set(false)
        }
        return _state.value
    }

    private fun pass(): UpdateState {
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
        if (inhibited()) return UpdateState.Deferred(offer.versionName, DEFERRED_PROVISIONING)
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
        // Provisioning may have started while the APK downloaded.
        if (inhibited()) return UpdateState.Deferred(offer.versionName, DEFERRED_PROVISIONING)
        return if (sources.install(apk, installedPackage)) {
            UpdateState.Installing(offer.versionName)
        } else {
            UpdateState.Failed("Android would not start the update.")
        }
    }

    companion object {
        const val LAUNCH_THROTTLE_MS = 6L * 60 * 60 * 1000
        const val DEFERRED_PROVISIONING = "waiting until board setup finishes"
    }
}

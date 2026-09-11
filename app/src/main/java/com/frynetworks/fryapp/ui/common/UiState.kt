package com.frynetworks.fryapp.ui.common

import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.wallet.BridgeException
import java.io.IOException

/** Screen-level phase shared by every miner-management screen (blueprint section 5 convention). */
enum class Phase { Loading, Content, Empty, Error }

data class UiError(val code: String, val message: String)

/** Maps any failure thrown by the repositories/bridge to a code + user copy. */
fun Throwable.toUiError(): UiError = when (this) {
    is DashboardException -> UiError(code, ErrorCopy.forCode(code, message))
    is BridgeException -> UiError(code.name, ErrorCopy.forCode(code.name, message))
    is IOException -> UiError("NETWORK_ERROR", ErrorCopy.forCode("NETWORK_ERROR"))
    else -> UiError("UNKNOWN", ErrorCopy.forCode("UNKNOWN", message))
}

/** The error code carried by a failure, for reducer events that need `(code, message)`. */
fun Throwable.errorCode(): String = when (this) {
    is DashboardException -> code
    is BridgeException -> code.name
    is IOException -> "NETWORK_ERROR"
    else -> "UNKNOWN"
}

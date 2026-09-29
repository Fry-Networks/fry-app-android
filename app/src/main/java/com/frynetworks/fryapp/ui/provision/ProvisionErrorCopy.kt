package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.wifi.SOFTAP_NEEDS_ANDROID_10

/**
 * Every provisioning error a user can meet, phrased as what happened and what to do next. Pure,
 * so the JVM suite covers every branch (U4/U8: "Device registration with hardwareapi failed" was
 * the only thing users ever saw, whatever went wrong).
 */
object ProvisionErrorCopy {

    /**
     * Boards before PROTOCOL.md v1.1 stay in Error until restarted ("latched"); v1.1 boards reset
     * when the app writes the Wi-Fi name again ([errorResetSupported], capability `error_reset`).
     */
    const val LATCHED_HINT = "The board stays in this error until it restarts: unplug it for 5 seconds, plug it back in, then set it up again."

    /** Headline when the body did not come from [forError] (provisioner failures, unknown refusals). */
    const val GENERIC_TITLE = "Setup failed"

    /** A short headline for each device error; [forError] gives the body shown under it. */
    fun title(error: ProvError): String = when (error) {
        ProvError.NONE -> "Board reported an error"
        ProvError.BAD_SSID -> "Wi-Fi network not found"
        ProvError.WIFI_AUTH_FAILED -> "Wi-Fi password not accepted"
        ProvError.NO_IP -> "No IP address from the router"
        ProvError.HARDWAREAPI_REGISTRATION_FAILED -> "Registration not accepted yet"
        ProvError.BAD_WALLET -> "Wallet address rejected"
        ProvError.KEY_REQUIRED -> "Miner key needed"
        ProvError.BAD_KEY -> "Miner key rejected"
        ProvError.KEY_LOCKED -> "Miner key cannot be replaced"
        ProvError.REG_UNAUTHORIZED -> "Miner key not recognised"
        ProvError.REG_FORBIDDEN -> "Registration refused"
        ProvError.REG_KEY_IN_USE -> "Miner key active elsewhere"
        ProvError.REG_REJECTED -> "Registration rejected"
        ProvError.UNREACHABLE -> "Fry not reachable"
    }

    /**
     * The title for a stored error body: [ProvisionUiState.Error] carries only the text, so the
     * screen finds the code by the body [forError] produced (with or without [LATCHED_HINT]).
     */
    fun titleFor(reason: String): String =
        ProvError.entries.firstOrNull { reason.startsWith(forError(it, errorResetSupported = true)) }?.let(::title) ?: GENERIC_TITLE

    /** What the provisioning status line shows for an error: the title, then the body. */
    fun statusLine(reason: String): String = "Error: ${titleFor(reason)}\n$reason"

    fun forError(error: ProvError, errorResetSupported: Boolean = false): String {
        val text = when (error) {
            ProvError.NONE -> "The board reported an error without a reason."
            ProvError.BAD_SSID -> "The board could not find that Wi-Fi network. Check the name (it is case-sensitive) and that the network is 2.4 GHz: ESP boards cannot see 5 GHz networks."
            ProvError.WIFI_AUTH_FAILED -> "The Wi-Fi password was not accepted. Check it and try again."
            ProvError.NO_IP -> "The board joined Wi-Fi but the router gave it no IP address. Check the router's device limit or MAC filtering, then try again."
            ProvError.HARDWAREAPI_REGISTRATION_FAILED -> "The board is on Wi-Fi but Fry did not accept its registration. It keeps retrying by itself; check the dashboard in a few minutes."
            ProvError.BAD_WALLET -> "The board rejected the wallet address. Paste the full 58-character Algorand address and try again."
            ProvError.KEY_REQUIRED -> "This board needs your miner key before it can be set up. Paste your FEM- key (Dashboard → Generate Free FEM Key) and try again."
            ProvError.BAD_KEY -> "The board rejected the miner key. It must be FEM- followed by 32 letters and digits, exactly as the dashboard shows it."
            ProvError.KEY_LOCKED -> "This board already has a confirmed miner key and will not replace it over the air. To change the key, use the USB web setup."
            ProvError.REG_UNAUTHORIZED -> "Fry does not recognise this miner key. Check it on the dashboard; IOT- keys are no longer accepted, use the FEM- key with the same 32 characters."
            ProvError.REG_FORBIDDEN -> "Fry refused this board's registration. The key may be registered to another wallet; check it on the dashboard."
            ProvError.REG_KEY_IN_USE -> "This miner key is already active on another install. Stop the other miner or use a different key; one key runs on one install at a time."
            ProvError.REG_REJECTED -> "Fry rejected the registration. Check the miner key and wallet on the dashboard, then try again."
            ProvError.UNREACHABLE -> "The board is on Wi-Fi but cannot reach Fry right now. Leave it powered: it keeps retrying by itself."
        }
        // Registration and reachability errors are retried by the board itself; the rest latch.
        val selfHealing = error == ProvError.HARDWAREAPI_REGISTRATION_FAILED || error == ProvError.UNREACHABLE
        return if (errorResetSupported || selfHealing) text else "$text\n\n$LATCHED_HINT"
    }

    /** Maps the provisioners' own failure reasons (not device error codes) to guidance. */
    fun forFailure(reason: String): String = when {
        reason == SOFTAP_NEEDS_ANDROID_10 -> reason
        reason.startsWith("GATT connect failed") ->
            "Could not connect to the board over Bluetooth. Keep the phone within 2 metres of it, check it is still in setup mode, and try again."
        reason.startsWith("Service discovery failed") ->
            "The board connected but did not answer. Restart it (unplug for 5 seconds) and try again."
        reason.startsWith("Write failed") ->
            "The board did not accept the settings. Move the phone closer and try again."
        reason.startsWith("GATT disconnected") ->
            "The Bluetooth connection dropped before the settings were sent. Move the phone closer and try again."
        reason == "Pairing failed" ->
            "The phone did not pair with the board, so the miner key was not sent. Tap Pair when Android asks, keep the phone within 2 metres, and try again."
        reason == "Provisioning timed out" ->
            "The board did not finish within 2 minutes. If it joined Wi-Fi it registers by itself; otherwise restart it and try again."
        reason == "Bluetooth adapter unavailable" ->
            "Bluetooth is off or unavailable. Turn Bluetooth on and try again."
        reason.startsWith("Failed to join") ->
            "Could not join the board's FRY-SETUP network. Approve Android's connection prompt, keep the board close, and try again."
        reason.startsWith("GET /info failed") || reason.startsWith("POST /provision failed") ->
            "Joined the setup network but the board did not answer. Restart the board and try again."
        reason == "Status polling timed out" ->
            "The board stopped reporting progress. If it joined Wi-Fi it registers by itself; check the dashboard in a few minutes."
        else -> reason
    }

    /** `POST /provision` refusals (PROTOCOL.md v1.1 section ESP8266). */
    fun forRefusal(httpCode: Int, err: String?): String = when (err) {
        "bad_ssid" -> forError(ProvError.BAD_SSID, errorResetSupported = true)
        "bad_wallet" -> forError(ProvError.BAD_WALLET, errorResetSupported = true)
        "bad_key" -> forError(ProvError.BAD_KEY, errorResetSupported = true)
        "key_required" -> forError(ProvError.KEY_REQUIRED, errorResetSupported = true)
        "key_locked" -> forError(ProvError.KEY_LOCKED, errorResetSupported = true)
        "key_needs_secure_ap" -> "This board only takes a miner key over its protected setup network. Restart it without a key set, or use the USB web setup."
        "busy" -> "The board is still working on the previous attempt. Wait 30 seconds and try again."
        else -> "The board refused the settings (HTTP $httpCode). Restart it and try again."
    }
}

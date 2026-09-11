package com.frynetworks.fryapp.wallet.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.frynetworks.fryapp.wallet.WalletVendor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands wallet deep links (`wc:`, `perawallet-wc://`, `defly-wc://`, `algorand://`) to the
 * wallet app. `wc:` connect URIs are aimed at the chosen vendor's package first so Android does
 * not show a chooser; http(s) URLs are never opened (the bridge has no business in a browser).
 */
@Singleton
class ExternalUriLauncher @Inject constructor(@ApplicationContext private val context: Context) {

    fun open(uri: String, vendor: WalletVendor?): Boolean {
        val parsed = Uri.parse(uri)
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            Log.i(TAG, "ignoring http(s) URI from the bridge")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (scheme == "wc" && vendor != null) {
            val targeted = Intent(intent).setPackage(vendor.packageName)
            if (start(targeted)) return true
        }
        return start(intent)
    }

    fun isInstalled(vendor: WalletVendor): Boolean = try {
        context.packageManager.getPackageInfo(vendor.packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for ${intent.data?.scheme}://")
        false
    }

    private companion object {
        const val TAG = "FryBridge"
    }
}

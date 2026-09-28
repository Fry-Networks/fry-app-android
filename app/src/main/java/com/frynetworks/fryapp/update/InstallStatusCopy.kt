package com.frynetworks.fryapp.update

/** PackageInstaller results in words. Constants duplicated so this stays a pure JVM object. */
object InstallStatusCopy {
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    fun forStatus(status: Int): String = when (status) {
        STATUS_PENDING_USER_ACTION -> "Confirm the update in the Android prompt to finish installing."
        STATUS_SUCCESS -> "The app was updated."
        STATUS_FAILURE_BLOCKED -> "Android blocked the update. Allow Fry to install apps (Settings → Apps → Fry → Install unknown apps) and try again."
        STATUS_FAILURE_ABORTED -> "The update was cancelled. It will be offered again later."
        STATUS_FAILURE_INVALID -> "The downloaded update was not a valid app package. It will be downloaded again."
        STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed app (a different signing key). Nothing was changed."
        STATUS_FAILURE_STORAGE -> "Not enough storage to install the update. Free some space and try again."
        STATUS_FAILURE_INCOMPATIBLE -> "This update does not support this phone."
        STATUS_FAILURE_TIMEOUT -> "The update timed out. It will be offered again later."
        else -> "The update could not be installed (status $status). It will be offered again later."
    }
}

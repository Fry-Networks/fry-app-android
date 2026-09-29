package com.frynetworks.fryqa

/**
 * The only packages this suite may drive. The test phone is shared with other automation, so an
 * instrumentation argument can never point the suite at another app.
 */
object QaTarget {
    const val RELEASE = "com.frynetworks.fryapp"
    const val DEBUG = "com.frynetworks.fryapp.debug"
    val ALLOWED = setOf(RELEASE, DEBUG)

    /** The app's launcher label (`app_name`), as the install prompt shows it. */
    const val APP_LABEL = "Fry"

    /** The package to drive: [name] when it is a Fry app package, [RELEASE] when absent; anything else is refused. */
    fun checked(name: String?): String {
        val pkg = name?.trim()?.takeIf { it.isNotEmpty() } ?: RELEASE
        require(pkg in ALLOWED) { "targetPackage '$pkg' is not a Fry app package (allowed: $ALLOWED)" }
        return pkg
    }

    /**
     * True when a system prompt's text names this app: an install or update prompt may only be
     * confirmed when its window carries [APP_LABEL], never one raised for another app.
     */
    fun namesApp(text: String?): Boolean = text?.contains(APP_LABEL) == true
}

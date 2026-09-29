package com.frynetworks.fryqa

/**
 * The only packages this suite may drive. The test phone is shared with other automation, so an
 * instrumentation argument can never point the suite at another app.
 */
object QaTarget {
    const val RELEASE = "com.frynetworks.fryapp"
    const val DEBUG = "com.frynetworks.fryapp.debug"
    val ALLOWED = setOf(RELEASE, DEBUG)

    /** The package to drive: [name] when it is a Fry app package, [RELEASE] when absent; anything else is refused. */
    fun checked(name: String?): String {
        val pkg = name?.trim()?.takeIf { it.isNotEmpty() } ?: RELEASE
        require(pkg in ALLOWED) { "targetPackage '$pkg' is not a Fry app package (allowed: $ALLOWED)" }
        return pkg
    }
}

package com.frynetworks.fryapp.update

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Held while a board is being provisioned: an app update then would kill the session mid-write. */
@Singleton
class InstallInhibitor @Inject constructor() {
    private val holders = AtomicInteger(0)

    val inhibited: Boolean get() = holders.get() > 0

    fun acquire() {
        holders.incrementAndGet()
    }

    fun release() {
        holders.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

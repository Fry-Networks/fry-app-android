package com.frynetworks.fryapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStatusCopyTest {

    @Test
    fun `every PackageInstaller status has its own sentence`() {
        val codes = (-1..8).toList()
        val texts = codes.map { InstallStatusCopy.forStatus(it) }
        assertTrue(texts.all { it.endsWith(".") })
        assertEquals(codes.size, texts.toSet().size)
        assertTrue(InstallStatusCopy.forStatus(99).contains("status 99"))
    }

    @Test
    fun `the constants match android-content-pm-PackageInstaller`() {
        assertEquals(-1, InstallStatusCopy.STATUS_PENDING_USER_ACTION)
        assertEquals(0, InstallStatusCopy.STATUS_SUCCESS)
        assertEquals(3, InstallStatusCopy.STATUS_FAILURE_ABORTED)
        assertEquals(5, InstallStatusCopy.STATUS_FAILURE_CONFLICT)
        assertTrue(InstallStatusCopy.forStatus(InstallStatusCopy.STATUS_FAILURE_CONFLICT).contains("signing key"))
    }
}

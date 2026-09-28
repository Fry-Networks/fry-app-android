package com.frynetworks.fryapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(s: String) = requireNotNull(SemVer.parse(s)) { s }

    @Test
    fun `SemVer 2-0 section 11 example order`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        for (i in 0 until ordered.size - 1) assertTrue("${ordered[i]} < ${ordered[i + 1]}", v(ordered[i]) < v(ordered[i + 1]))
        assertTrue(v("0.4.1-rc.1") < v("0.4.1"))
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
    }

    @Test
    fun `build metadata is ignored and a leading v is accepted`() {
        assertEquals(0, v("1.0.0+build.5").compareTo(v("1.0.0")))
        assertEquals(v("0.3.1"), v("v0.3.1"))
    }

    @Test
    fun `malformed versions do not parse`() {
        for (bad in listOf("", "1", "1.0", "01.0.0", "1.0.0-", "latest", "1.0.0.0", "dev-build")) assertNull(bad, SemVer.parse(bad))
        assertNull(SemVer.parse(null))
    }
}

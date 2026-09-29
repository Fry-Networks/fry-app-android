package com.frynetworks.fryqa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suite runs on a shared phone: only the Fry app packages may ever be driven. */
class QaTargetTest {

    @Test
    fun `only the Fry app packages are accepted, the release one by default`() {
        assertEquals("com.frynetworks.fryapp", QaTarget.checked(null))
        assertEquals("com.frynetworks.fryapp", QaTarget.checked(""))
        assertEquals("com.frynetworks.fryapp", QaTarget.checked(" com.frynetworks.fryapp "))
        assertEquals("com.frynetworks.fryapp.debug", QaTarget.checked("com.frynetworks.fryapp.debug"))
        assertEquals(setOf("com.frynetworks.fryapp", "com.frynetworks.fryapp.debug"), QaTarget.ALLOWED)
    }

    @Test
    fun `any other package is refused before the suite touches it`() {
        val others = listOf(
            "com.frynetworks.roombaadvanced", "com.android.settings", "com.frynetworks.fryapp.evil",
            "com.frynetworks", "com.frynetworks.fryapp2", "COM.FRYNETWORKS.FRYAPP",
        )
        for (other in others) {
            val refused = runCatching { QaTarget.checked(other) }.exceptionOrNull()
            assertTrue(other, refused is IllegalArgumentException && refused.message!!.contains(other))
        }
    }
}

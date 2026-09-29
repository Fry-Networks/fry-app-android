package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.ProvError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every device error code, and in particular the PROTOCOL.md v1.1 key and registration codes
 * 6-13, has a short title next to its body, and the title is recovered from the body the view
 * model stores so the provisioning screen can show both.
 */
class ProvisionErrorTitleTest {

    @Test
    fun `every device error code has a short title distinct from its body`() {
        val titles = ProvError.entries.map { ProvisionErrorCopy.title(it) }
        assertTrue("titles are short headlines: $titles", titles.all { it.isNotBlank() && !it.endsWith(".") && it.length <= 40 })
        assertEquals("each code has its own title", titles.size, titles.toSet().size)
        ProvError.entries.forEach {
            assertFalse("${it.name}: the title is not the start of the body", ProvisionErrorCopy.forError(it, errorResetSupported = true).startsWith(ProvisionErrorCopy.title(it)))
        }
    }

    @Test
    fun `codes 6 to 13 are titled by the key or registration problem`() {
        assertEquals("Miner key needed", ProvisionErrorCopy.title(ProvError.KEY_REQUIRED))
        assertEquals("Miner key rejected", ProvisionErrorCopy.title(ProvError.BAD_KEY))
        assertEquals("Miner key cannot be replaced", ProvisionErrorCopy.title(ProvError.KEY_LOCKED))
        assertEquals("Miner key not recognised", ProvisionErrorCopy.title(ProvError.REG_UNAUTHORIZED))
        assertEquals("Registration refused", ProvisionErrorCopy.title(ProvError.REG_FORBIDDEN))
        assertEquals("Miner key active elsewhere", ProvisionErrorCopy.title(ProvError.REG_KEY_IN_USE))
        assertEquals("Registration rejected", ProvisionErrorCopy.title(ProvError.REG_REJECTED))
        assertEquals("Fry not reachable", ProvisionErrorCopy.title(ProvError.UNREACHABLE))
    }

    @Test
    fun `the title is recovered from the stored body, with or without the restart hint`() {
        ProvError.entries.forEach { error ->
            assertEquals(error.name, ProvisionErrorCopy.title(error), ProvisionErrorCopy.titleFor(ProvisionErrorCopy.forError(error, errorResetSupported = true)))
            assertEquals(error.name, ProvisionErrorCopy.title(error), ProvisionErrorCopy.titleFor(ProvisionErrorCopy.forError(error, errorResetSupported = false)))
        }
        assertEquals(ProvisionErrorCopy.title(ProvError.KEY_REQUIRED), ProvisionErrorCopy.titleFor(ProvisionErrorCopy.forRefusal(422, "key_required")))
        assertEquals(ProvisionErrorCopy.GENERIC_TITLE, ProvisionErrorCopy.titleFor(ProvisionErrorCopy.forFailure("GATT connect failed: status=133")))
        assertEquals(ProvisionErrorCopy.GENERIC_TITLE, ProvisionErrorCopy.titleFor("something new"))
    }

    @Test
    fun `the status line shows the title above the body`() {
        val body = ProvisionErrorCopy.forError(ProvError.REG_KEY_IN_USE)
        assertEquals("Error: Miner key active elsewhere\n$body", ProvisionErrorCopy.statusLine(body))
        assertEquals("Error: ${ProvisionErrorCopy.GENERIC_TITLE}\nsomething new", ProvisionErrorCopy.statusLine("something new"))
    }
}

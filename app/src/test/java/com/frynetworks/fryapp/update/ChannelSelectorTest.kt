package com.frynetworks.fryapp.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChannelSelectorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `only the word test selects the test channel`() {
        assertEquals(UpdateChannel.TEST, ChannelSelector.fromFileContent("test"))
        assertEquals(UpdateChannel.TEST, ChannelSelector.fromFileContent(" TEST\n"))
        for (other in listOf(null, "", "stable", "tests", "https://evil.example/fryapp-update.json", "test channel")) {
            assertEquals(other.toString(), UpdateChannel.STABLE, ChannelSelector.fromFileContent(other))
        }
    }

    @Test
    fun `a missing, oversized or unreadable file means stable`() {
        assertEquals(UpdateChannel.STABLE, ChannelSelector.read(null as File?))
        assertEquals(UpdateChannel.STABLE, ChannelSelector.read(File(tmp.root, "absent")))
        assertEquals(UpdateChannel.STABLE, ChannelSelector.read(tmp.newFile("big").apply { writeText("test" + " ".repeat(100)) }))
        assertEquals(UpdateChannel.STABLE, ChannelSelector.read(tmp.newFolder("dir")))
        assertEquals(UpdateChannel.TEST, ChannelSelector.read(tmp.newFile(ChannelSelector.FILE_NAME).apply { writeText("test\n") }))
    }

    @Test
    fun `the channel URLs are fixed C-6 locations`() {
        assertEquals("https://github.com/Fry-Networks/fry-app-android/releases/latest/download/fryapp-update.json", UpdateChannel.STABLE.manifestUrl)
        assertEquals("https://github.com/Fry-Networks/fry-app-android/releases/download/update-channel-test/fryapp-update.json", UpdateChannel.TEST.manifestUrl)
        for (c in UpdateChannel.entries) assertEquals(true, UpdateHosts.allowed(c.manifestUrl))
    }
}

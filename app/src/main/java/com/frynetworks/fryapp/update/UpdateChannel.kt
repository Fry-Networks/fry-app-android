package com.frynetworks.fryapp.update

import android.content.Context
import java.io.File

/** C-6 channels. The manifest URL is fixed per channel; it is never read from anywhere else. */
enum class UpdateChannel(val wire: String, val manifestUrl: String) {
    STABLE("stable", "https://github.com/Fry-Networks/fry-app-android/releases/latest/download/fryapp-update.json"),
    TEST("test", "https://github.com/Fry-Networks/fry-app-android/releases/download/update-channel-test/fryapp-update.json"),
    ;

    companion object {
        fun fromWire(value: String?): UpdateChannel? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The channel comes from `<external files dir>/update-channel`, a file only adb (or the QA
 * harness) can write: its content `test` selects [UpdateChannel.TEST]; anything else, a missing
 * file or an unreadable one means [UpdateChannel.STABLE]. The file never supplies a URL.
 */
object ChannelSelector {
    const val FILE_NAME = "update-channel"
    private const val MAX_BYTES = 64

    fun fromFileContent(content: String?): UpdateChannel =
        if (content?.trim()?.lowercase() == UpdateChannel.TEST.wire) UpdateChannel.TEST else UpdateChannel.STABLE

    fun read(file: File?): UpdateChannel = fromFileContent(
        runCatching { file?.takeIf { it.isFile && it.length() <= MAX_BYTES }?.readText() }.getOrNull(),
    )

    fun read(context: Context): UpdateChannel = read(context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) })
}

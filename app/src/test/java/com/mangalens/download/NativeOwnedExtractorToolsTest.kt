package com.mangalens.download

import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeOwnedExtractorToolsTest {
    private val source = "https://example.invalid/public-video"
    private val installed = NativeOriginalMediaInstallation(File("immutable APK tools"), emptyList())
    private fun lastLocation(arguments: List<String>) = arguments[arguments.indexOfLast { it == "--ffmpeg-location" } + 1]

    @Test fun ownedLocationWinsAfterTheRealWrapperAppendsItsLegacyOption() {
        val session = MediaResolutionSession(5_000)
        val request = YoutubeDLRequest(source).apply {
            addOption("--ignore-config")
            addCommands(listOf("--proxy", "", "--no-remote-components"))
        }
        try {
            NativeOwnedExtractorTools.configureWithInitializer(request, session) { installed }
            // Wrapper 0.18.1 does this inside execute(), after the app has configured its request.
            request.addOption("--ffmpeg-location", "/legacy/libffmpeg.so")
            val command = request.buildCommand()
            assertEquals(File(installed.binaryDirectory, "libmangalens_ffmpeg.so").absolutePath, lastLocation(command))
            assertEquals(source, command.last())
            val proxy = command.indexOf("--proxy")
            assertEquals("", command[proxy + 1])
            assertTrue(command.indexOfLast { it == "--ffmpeg-location" } > proxy)
            assertTrue(command.contains("--ignore-config"))
        } finally { session.cancel() }
    }
    @Test fun installedPathsWithSpacesRemainOneArgAndNoUrlCanSelectTheTool() {
        val session = MediaResolutionSession(5_000)
        val request = YoutubeDLRequest(source)
        try {
            NativeOwnedExtractorTools.configureWithInitializer(request, session) { installed }
            request.addOption("--ffmpeg-location", "/legacy/libffmpeg.so")
            assertEquals(listOf("--ffmpeg-location", "/legacy/libffmpeg.so", "--ffmpeg-location",
                File(installed.binaryDirectory, "libmangalens_ffmpeg.so").absolutePath, source), request.buildCommand())
        } finally { session.cancel() }
    }
    @Test fun failedAdmissionCannotPublishAReplacementLocation() {
        val session = MediaResolutionSession(5_000)
        val request = YoutubeDLRequest(source).apply { addOption("--ignore-config") }
        val before = request.buildCommand()
        val failure = IllegalStateException("Packaged original-media executable failed its integrity check.")
        try {
            assertSame(failure, runCatching { NativeOwnedExtractorTools.configureWithInitializer(request, session) { throw failure } }.exceptionOrNull())
            assertEquals(before, request.buildCommand())
        } finally { session.cancel() }
    }
    @Test fun cancellationDuringAdmissionCannotAppendAnOldRequestLocation() {
        val session = MediaResolutionSession(5_000)
        val request = YoutubeDLRequest(source)
        val replacement = YoutubeDLRequest("https://example.invalid/new-request")
        val before = request.buildCommand()
        val replacementBefore = replacement.buildCommand()
        try {
            assertTrue(runCatching { NativeOwnedExtractorTools.configureWithInitializer(request, session) { active ->
                assertSame(session, active); session.cancel(); installed
            } }.exceptionOrNull() is InterruptedException)
            assertEquals(before, request.buildCommand())
            assertEquals(replacementBefore, replacement.buildCommand())
        } finally { session.cancel() }
    }
}

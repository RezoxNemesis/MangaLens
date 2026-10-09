package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class NativeOriginalOwnedRuntimeIntegrationTest {
    @Test fun toolNamesUseOnlyTheAppOwnedImmutableExecutables() {
        assertEquals("libmangalens_ffmpeg.so", NativeMediaTool.FFMPEG.fileName)
        assertEquals("libmangalens_ffprobe.so", NativeMediaTool.FFPROBE.fileName)
    }

    @Test fun actualProbeLaunchUsesTheOwnedPackagedPath() {
        val directory = File("owned-runtime-test-directory")
        val session = MediaResolutionSession(5_000)
        try {
            NativeOriginalMediaRuntime.executeWithLifecycle(NativeOriginalMediaInstallation(directory, emptyList()),
                NativeMediaTool.FFPROBE, listOf("-version"), session,
                launch = { builder ->
                    assertEquals(listOf(File(directory, "libmangalens_ffprobe.so").absolutePath, "-version"), builder.command())
                    CompletedProcess()
                }, stop = { true })
        } finally { session.cancel() }
    }

    @Test fun actualCopyLaunchDoesNotAddPublisherOrPythonLoaderDirectories() {
        val session = MediaResolutionSession(5_000)
        try {
            NativeOriginalMediaRuntime.executeWithLifecycle(NativeOriginalMediaInstallation(File("owned-runtime-test-directory"),
                listOf(File("old-publisher/usr/lib"), File("old-python/usr/lib"))),
                NativeMediaTool.FFMPEG, emptyList(), session,
                launch = { builder ->
                    assertNull("System-only tools must not set LD_LIBRARY_PATH", builder.environment()["LD_LIBRARY_PATH"])
                    assertNull("System-only tools must not inherit LD_PRELOAD", builder.environment()["LD_PRELOAD"])
                    assertEquals("/system/bin", builder.environment()["PATH"])
                    CompletedProcess()
                }, stop = { true })
        } finally { session.cancel() }
    }

    private class CompletedProcess : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }
}

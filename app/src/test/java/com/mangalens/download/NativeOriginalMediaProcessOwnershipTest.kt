package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeOriginalMediaProcessOwnershipTest {
    @Test(timeout = 10_000) fun cancellationDuringStartCannotLoseTheLateChildBehindAnAlreadyQueuedStop() {
        val child = AtomicReference<Process?>()
        val nullStopEntered = CountDownLatch(1)
        val releaseNullStop = CountDownLatch(1)
        val session = MediaResolutionSession(8_000)
        val runtime = NativeOriginalMediaInstallation(File("unused-test-tool-directory"), emptyList())
        try {
            val failure = runCatching {
                NativeOriginalMediaRuntime.executeWithLifecycle(runtime, NativeMediaTool.FFPROBE, emptyList(), session,
                    launch = {
                        val classpath = listOf(ChildFixture::class.java, kotlin.Unit::class.java)
                            .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).absolutePath }
                            .distinct().joinToString(File.pathSeparator)
                        val running = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                            "-Xmx32m", "-cp", classpath, ChildFixture::class.java.name).start()
                        child.set(running)
                        assertEquals("The fixture must actually start", "ready", running.inputStream.bufferedReader().readLine())
                        session.cancel()
                        // On the old implementation cleanup captures null here and remains
                        // pending while start returns. close() then coalesces the required stop.
                        nullStopEntered.await(500, TimeUnit.MILLISECONDS)
                        running
                    }, stop = { running ->
                        if (running == null) {
                            nullStopEntered.countDown()
                            releaseNullStop.await(2, TimeUnit.SECONDS)
                            false
                        } else { running.destroyForcibly(); true }
                    })
            }.exceptionOrNull()
            assertTrue(failure is InterruptedException)
            releaseNullStop.countDown()
            assertTrue("The actual late child must be stopped", child.get()!!.waitFor(2, TimeUnit.SECONDS))
            assertFalse(child.get()!!.isAlive)
        } finally {
            releaseNullStop.countDown()
            child.get()?.destroyForcibly()
            session.cancel()
        }
    }

    object ChildFixture {
        @JvmStatic fun main(args: Array<String>) {
            println("ready")
            System.out.flush()
            Thread.sleep(30_000)
        }
    }
}

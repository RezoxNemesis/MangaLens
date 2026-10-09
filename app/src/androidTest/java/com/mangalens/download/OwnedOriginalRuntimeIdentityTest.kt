package com.mangalens.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.BuildConfig
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Installed executable and wrapper checks, independent of public-provider access. */
@RunWith(AndroidJUnit4::class)
class OwnedOriginalRuntimeIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 180_000) fun actualPackagedCopyAndProbeExecuteTheirPinnedVersionAndLgplLicense() {
        runBlocking {
            MediaResolutionRunner.run(120_000) { session ->
                val installation = NativeOriginalMediaRuntime.initialize(context, session)
                assertTrue("Owned copy uses Android system dependencies only", installation.libraryDirectories.isEmpty())
                val evidence = JSONObject().put("source_sha", BuildConfig.SOURCE_SHA)
                for (tool in NativeMediaTool.entries) {
                    val version = capture(installation, tool, listOf("-version"), session)
                    assertTrue("Actual executable version must be FFmpeg 7.1.1", version.lineSequence().first().contains("7.1.1"))
                    assertTrue(version.contains("--disable-gpl"))
                    assertTrue(version.contains("--disable-nonfree"))
                    assertFalse(version.contains("--enable-gpl"))
                    val license = capture(installation, tool, listOf("-L"), session)
                    assertTrue("Actual CLI must identify LGPL", license.contains("Lesser General Public License"))
                    evidence.put(tool.name, JSONObject().put("version", version.lineSequence().first())
                        .put("actual_lgpl_license", true).put("owned_filename", tool.fileName))
                }
                record("actual-version-license", evidence)
            }
        }
    }

    @Test(timeout = 180_000) fun retainedRealSiteExtractorInitializesAndExecutesOfflineWithoutPublisherFfmpegArchive() {
        runBlocking {
            MediaResolutionRunner.run(120_000) { session ->
                val installation = NativeOriginalMediaRuntime.initialize(context, session)
                val names = installation.binaryDirectory.listFiles().orEmpty().map { it.name }.toSet()
                assertTrue(names.containsAll(NativeMediaTool.entries.map { it.fileName }))
                assertFalse("Publisher FFmpeg executable must be retired", "libffmpeg.so" in names)
                assertFalse("Publisher probe executable must be retired", "libffprobe.so" in names)
                assertFalse("Publisher FFmpeg dependency archive must be retired", "libffmpeg.zip.so" in names)
                assertTrue("Site extractor Python runtime is retained", "libpython.so" in names)
                BundledYtDlpRuntime.initialize(context, session::checkActive)
                val request = YoutubeDLRequest("https://fixture.invalid/offline-version").apply {
                    addOption("--ignore-config")
                    addOption("--no-plugin-dirs")
                    addOption("--version")
                }
                NativeOwnedExtractorTools.configure(context, request, session)
                val processId = "mangalens-owned-version-${UUID.randomUUID()}"
                val guard = MediaProcessGuard(session, processId, session.remainingMillis(), YoutubeDL::destroyProcessById)
                val response = try { guard.run { YoutubeDL.execute(request, processId = processId, callback = null) } }
                    finally { guard.close() }
                val version = response.out.trim()
                assertTrue("Actual Python site extractor must execute its version command", version.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}[A-Za-z0-9._+-]*")))
                record("real-wrapper-offline-version", JSONObject().put("source_sha", BuildConfig.SOURCE_SHA)
                    .put("extractor_version", version).put("publisher_ffmpeg_archive_present", false)
                    .put("public_provider_download", "NOT_EVALUATED"))
            }
        }
    }

    private fun capture(installation: NativeOriginalMediaInstallation, tool: NativeMediaTool,
                        arguments: List<String>, session: MediaResolutionSession): String {
        val text = StringBuilder()
        NativeOriginalMediaRuntime.execute(installation, tool, arguments, session) { line ->
            check(text.length + line.length + 1 <= 128 * 1024) { "CLI identity output exceeded its bound" }
            text.append(line).append('\n')
        }
        return text.toString()
    }

    private fun record(name: String, value: JSONObject) {
        File(context.filesDir, "qa/owned-original-runtime").apply { check(isDirectory || mkdirs()) }
            .resolve("$name.json").writeText(value.toString(2))
    }
}

package com.mangalens

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.*
import com.mangalens.ui.video.RecentVideoSource
import com.mangalens.ui.video.RecentVideoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authored UNRUN. Genuine pinned fMP4 bodies through a guarded synthetic exchange, native
 * original assembly, and the production saved-video route. Public DNS/TLS/provider access,
 * extractor/enrich, HLS streaming Factory, process death, and model quality are NOT_EVALUATED.
 * Missing staged fixture arguments fail; ordinary MP4 bytes split into pieces are rejected.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PairedHlsVodRuntimeTest {
    @Test fun pinnedObservedFmp4PairCancelsResumesPreservesOriginalTracksAndPlaysAfterActivityReopen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val descriptorPath = requireNotNull(args.getString("paired_hls_fixture_descriptor")) {
            "Stage an exact genuine paired fMP4 fixture descriptor; missing fixture is not a pass."
        }
        val descriptorHash = requireNotNull(args.getString("paired_hls_fixture_descriptor_sha256")) {
            "Provide the independently pinned fixture descriptor SHA-256."
        }.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val descriptor = ownedStagedFile(context, File(descriptorPath), 512 * 1024L)
        assertEquals("Descriptor pin", descriptorHash, sha256(descriptor))
        val spec = JSONObject(descriptor.readText(Charsets.UTF_8))
        assertEquals("paired-hls-fmp4-runtime-v1", spec.getString("schema"))
        val durationUs = spec.getLong("durationUs").also { require(it in 4_000_000L..20_000_000L) }
        val id = "qa-hls-${UUID.randomUUID()}"
        val directory = File(context.filesDir, "downloads/$id").apply { check(mkdirs()) }
        val evidence = JSONObject().put("status", "RUNNING").put("source_sha", BuildConfig.SOURCE_SHA)
            .put("descriptor_sha256", descriptorHash).put("scope", "guarded_synthetic_http_genuine_fmp4_native_saved_player")
            .put("public_provider_access", "NOT_EVALUATED").put("public_dns_tls_socket", "NOT_EVALUATED")
            .put("provenance", "HASHED_STAGED_CAPTURE_METADATA_NOT_NETWORK_REVALIDATED")
            .put("extractor_enrich_and_streaming_factory", "NOT_EVALUATED").put("process_death", "NOT_EVALUATED")
        var scenario: ActivityScenario<MainActivity>? = null
        var savedUri: Uri? = null
        var ownedPlayer: ExoPlayer? = null
        var primaryFailure: Throwable? = null
        val privateReleaseProven = AtomicBoolean(true)
        val fileOwner = DownloadPrivateFileOwner(directory, id)
        val transferLease = DownloadPrivateFileOwners.acquire(fileOwner)
        fun transfer(exchange: Exchange, anchor: String) = OriginalFragmentTransfer({ exchange.client(anchor) },
            privateFileCloseFailed = { privateReleaseProven.set(false); transferLease.retain() },
            transportCloseFailed = { resource -> privateReleaseProven.set(false); transferLease.retain(resource) })
        try {
            val video = loadTrack(context, descriptor.parentFile!!, spec.getJSONObject("video"), "video", durationUs)
            val audio = loadTrack(context, descriptor.parentFile!!, spec.getJSONObject("audio"), "audio", durationUs)
            val reference = when (spec.getString("fixtureOrigin")) {
                "controlled-generated-from-pinned-original" -> {
                    assertEquals(8_000_000L, durationUs)
                    val pins = spec.getJSONObject("originalInputs")
                    val originalVideo = loadPin(context, descriptor.parentFile!!, pins.getJSONObject("video"), 16 * 1024 * 1024L)
                    val originalAudio = loadPin(context, descriptor.parentFile!!, pins.getJSONObject("audio"), 16 * 1024 * 1024L)
                    assertEquals(719_685L, originalVideo.bytes)
                    assertEquals("9fb465a7980b07698683ee2268c0531ec788328433c180b4fcff1111848dbeb1", originalVideo.sha256)
                    assertEquals(98_655L, originalAudio.bytes)
                    assertEquals("788a4d7b262ee0b28119de8ac5193260f1e46e3d35829ca0ee7be8a5e33a7a20", originalAudio.sha256)
                    evidence.put("provenance", "CONTROLLED_GENERATED_FROM_HARDCODED_PINNED_ORIGINAL_AVC720_AAC8")
                    originalVideo.file to originalAudio.file
                }
                "pinned-public-fmp4-capture" -> {
                    val receipt = loadPin(context, descriptor.parentFile!!, spec.getJSONObject("publicCaptureReceipt"), 512 * 1024L)
                    validateCaptureMetadata(JSONObject(receipt.file.readText()), video.blobs + audio.blobs)
                    null
                }
                else -> error("Unknown fixture origin; generated media must not claim public HTTP capture.")
            }
            evidence.put("fixture_origin", spec.getString("fixtureOrigin"))
            val selection = OriginalMediaSelection(video.plan.formatId, audio.plan.formatId,
                video.audit.getString("codec"), audio.audit.getString("codec"), video.audit.getInt("height"))
            val media = ResolvedMediaLink(video.plan.sourceUrl, "video/mp4", detectedHeight = video.audit.getInt("height"),
                audioUrl = audio.plan.sourceUrl, expectedDurationUs = durationUs, originalSelection = selection,
                audioMimeType = "audio/mp4", videoFragments = video.plan, audioFragments = audio.plan)
            assertNotNull(requireBoundMediaSource(media.url, media))
            OriginalFragmentTransport.requireDownloadBinding(OriginalFragmentTransport.downloadKind(media), media)

            // The source body really blocks during the third request. Caller cancellation must
            // cancel the actual OkHttp Call, return the owned source close, and retain only the
            // first two durable entries. A fresh Transfer then reads that actual journal.
            val partial = File(directory, "video.partial")
            val checkpoint = File(directory, "video.checkpoint")
            val interrupted = Exchange(video.blobs, video.plan.fragments[2].url)
            val firstTransfer = transfer(interrupted, video.plan.sourceUrl)
            val job = launch(Dispatchers.IO) { firstTransfer.download(video.plan, partial, checkpoint) }
            try {
                assertTrue("Actual third fragment read did not start", interrupted.blocked.await(8, TimeUnit.SECONDS))
            } finally {
                try { withTimeout(15_000L) { job.cancelAndJoin() } }
                catch (failure: Throwable) { privateReleaseProven.set(false); throw failure }
            }
            assertTrue("Cancellation did not return every actual body close", interrupted.closedExactlyOnce())
            val prefix = video.blobs.drop(1).take(2).sumOf { it.file.length() }
            assertEquals("Uncommitted media survived cancellation", prefix, partial.length())
            val journal = JSONObject(checkpoint.readText())
            assertEquals(video.plan.sha256(), journal.getString("planSha256"))
            assertEquals(2, journal.getJSONArray("completed").length())
            assertEquals("Committed prefix pin", digestSequence(video.blobs.drop(1).take(2)), sha256(partial))
            assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".muxed-") })
            val resumed = Exchange(video.blobs)
            transfer(resumed, video.plan.sourceUrl).download(video.plan, partial, checkpoint)
            assertEquals("Resume reacquired committed init/media", video.plan.fragments.drop(2).map { it.url }, resumed.requests.toList())
            assertTrue(resumed.closedExactlyOnce())
            assertEquals(video.sequenceSha256, sha256(partial))
            val sound = File(directory, "audio.partial")
            val soundJournal = File(directory, "audio.checkpoint")
            val soundExchange = Exchange(audio.blobs)
            transfer(soundExchange, audio.plan.sourceUrl).download(audio.plan, sound, soundJournal)
            assertEquals(audio.plan.fragments.map { it.url }, soundExchange.requests.toList())
            assertTrue(soundExchange.closedExactlyOnce())
            assertEquals(audio.sequenceSha256, sha256(sound))
            evidence.put("cancelled_committed_fragments", 2).put("cancelled_prefix_bytes", prefix)
                .put("fresh_transfer_resume", true).put("owned_source_closes_once", true)
                .put("video_plan_sha256", video.plan.sha256()).put("audio_plan_sha256", audio.plan.sha256())

            val base = OriginalMediaRemuxer.newAttemptBase(directory, id)
            val publication = OriginalMediaRemuxer.assemble(context, partial, sound, "video/mp4", base,
                durationUs, video.audit.getInt("height"), selection, requireCombinedAudio = true,
                observedVideoSpanUs = video.plan.hls!!.observedDurationUs,
                observedAudioSpanUs = audio.plan.hls!!.observedDurationUs)
            assertEquals("video/mp4", publication.mime)
            assertNotNull(publication.media.video); assertNotNull(publication.media.audio)
            OriginalMediaProbe.verifyTail(publication.media, durationUs)
            assertPinnedTrack(publication.media, publication.media.video!!, video.audit, video.plan.hls!!.observedDurationUs)
            assertPinnedTrack(publication.media, publication.media.audio!!, audio.audit, audio.plan.hls!!.observedDurationUs)
            reference?.let { (originalVideo, originalAudio) ->
                MediaResolutionRunner.run(300_000L, DownloadPrivateFileOwner(directory, id)) { session ->
                    val runtime = NativeOriginalMediaRuntime.initialize(context, session)
                    val referenceVideo = OriginalMediaProbe.inspect(runtime, originalVideo, session, OriginalTrackSelection.VIDEO)
                    val referenceAudio = OriginalMediaProbe.inspect(runtime, originalAudio, session, OriginalTrackSelection.AUDIO)
                    assertEquals(720, referenceVideo.video!!.height)
                    assertEquals("h264", referenceVideo.video!!.codec); assertEquals("aac", referenceAudio.audio!!.codec)
                    assertEquals(referenceVideo.video!!.width, publication.media.video!!.width)
                    assertEquals(referenceVideo.video!!.height, publication.media.video!!.height)
                    assertEquals(referenceAudio.audio!!.sampleRate, publication.media.audio!!.sampleRate)
                    assertEquals(referenceAudio.audio!!.channels, publication.media.audio!!.channels)
                    assertNotNull(referenceVideo.video!!.codecConfigurationHash); assertNotNull(referenceAudio.audio!!.codecConfigurationHash)
                    assertTrue("Generated HLS changed pinned original video packets/CSD", OriginalMediaProbe.sameOriginalTrack(
                        referenceVideo, referenceVideo.video!!, publication.media, publication.media.video!!))
                    assertTrue("Generated HLS changed pinned original audio packets/CSD", OriginalMediaProbe.sameOriginalTrack(
                        referenceAudio, referenceAudio.audio!!, publication.media, publication.media.audio!!))
                    OriginalMediaProbe.verifyTail(referenceVideo, durationUs); OriginalMediaProbe.verifyTail(referenceAudio, durationUs)
                    evidence.put("pinned_original_native_tracks", JSONArray().put(trackEvidence(referenceVideo, referenceVideo.video!!))
                        .put(trackEvidence(referenceAudio, referenceAudio.audio!!)))
                }
                evidence.put("actual_pinned_original_payload_csd_comparison", true).put("selected_height", 720)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", publication.file)
            savedUri = uri
            val decoded = ProviderMediaAudit.inspect(context, uri, durationUs) // Existing 90-second audit.
            assertTrue(decoded.getLong("decoded_pcm_samples_16k_with_overlap") > 0)
            assertTrue(decoded.getLong("non_silent_decoded_samples") > 0)
            assertTrue(decoded.getLong("decoded_pcm_end_ms") >= durationUs / 1000L - 500L)
            evidence.put("audio_decode", decoded).put("native_tracks", JSONArray().put(trackEvidence(publication.media, publication.media.video!!))
                .put(trackEvidence(publication.media, publication.media.audio!!)))
            val outputPin = sha256(publication.file)
            evidence.put("output_sha256", outputPin).put("output_bytes", publication.file.length()).put("output_mime", publication.mime)
            val observations = JSONArray()
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            repeat(2) { entry ->
                // This is MainActivity's unchanged production ACTION_SEND -> local_player route.
                // The nav-entry ViewModel owns playback; the test observes PlayerView itself.
                val active = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType(publication.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)).also { scenario = it }
                var view: PlayerView? = null
                await(8_000L, "Verified saved HLS output never entered production local_player") {
                    active.onActivity { view = findPlayer(it.window.decorView)?.takeIf { playerView ->
                        playerView.isShown && playerView.player?.currentMediaItem?.localConfiguration?.uri == uri
                    } }; view != null
                }
                val player = onMain { requireNotNull(view?.player) as ExoPlayer }.also { ownedPlayer = it }
                await(30_000L, "Native saved fMP4 assembly never became READY") {
                    onMain { assertNull(player.playerError); player.playbackState == Player.STATE_READY }
                }
                onMain { player.seekTo(0L); player.play() }
                await(30_000L, "Actual native clock did not advance") { onMain {
                    assertNull(player.playerError); player.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition > 1_000L
                } }
                await(30_000L, "Actual nonuniform video frame was not rendered") { onMain { hasDecodedFrame(view) } }
                val seekMs = durationUs / 2_000L
                onMain { player.seekTo(seekMs); player.play() }
                await(30_000L, "Native saved output did not advance after seek") { onMain {
                    assertNull(player.playerError); player.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition > seekMs + 250L
                } }
                observations.put(onMain {
                    assertEquals(uri, player.currentMediaItem?.localConfiguration?.uri)
                    assertEquals(video.audit.getString("mime"), player.videoFormat?.sampleMimeType)
                    assertEquals(audio.audit.getString("mime"), player.audioFormat?.sampleMimeType)
                    assertEquals(video.audit.getInt("height"), player.videoSize.height)
                    assertTrue(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                    assertTrue(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
                    assertTrue(kotlin.math.abs(player.duration - durationUs / 1000L) <= 1_000L)
                    JSONObject().put("entry", entry).put("frame", true).put("seek_ms", seekMs)
                        .put("clock_ms", player.currentPosition).put("duration_ms", player.duration)
                })
                await(30_000L, "Actual native player did not reach the complete saved tail") { onMain {
                    assertNull(player.playerError); player.playbackState == Player.STATE_ENDED
                } }
                onMain { player.pause() }
                active.close(); scenario = null; ownedPlayer = null
                assertEquals("Saved assembly changed across Activity closure", outputPin, sha256(publication.file))
            }
            evidence.put("cold_activity_entries", 2).put("playback", observations).put("status", "PASS")
        } catch (failure: Throwable) {
            primaryFailure = failure
            evidence.put("status", "FAIL").put("failure_class", failure.javaClass.name)
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) { cleanupFailures += failure } }
            cleanup {
                onMain { ownedPlayer?.takeIf { it.currentMediaItem?.localConfiguration?.uri == savedUri }?.pause() }
            }
            cleanup { scenario?.close() }
            cleanup {
                savedUri?.let { uri ->
                    context.getSharedPreferences("mangalens_video_positions", 0).edit().remove(sha256(uri.toString().toByteArray())).commit()
                    RecentVideoSource.local(uri.toString())?.let { RecentVideoStore.shared(context).remove(it.key) }
                }
            }
            cleanup { transferLease.close() }
            var retained = true
            cleanup {
                val outstandingOwners = DownloadPrivateFileOwners.hasOwners(fileOwner)
                retained = primaryFailure != null || cleanupFailures.isNotEmpty() || !privateReleaseProven.get() ||
                    outstandingOwners
                if (primaryFailure == null && cleanupFailures.isEmpty())
                    check(privateReleaseProven.get() && !outstandingOwners) { "Actual private producer release remains unproven." }
                if (!retained) check(directory.deleteRecursively()) { "Owned successful attempt files did not clean up." }
            }
            evidence.put("attempt_directory_retained", retained || cleanupFailures.isNotEmpty())
            if (cleanupFailures.isNotEmpty()) evidence.put("status", "FAIL").put("cleanup_failure_class", cleanupFailures.first().javaClass.name)
            cleanup {
                val target = File(context.filesDir, "qa-private/paired-hls-vod/$id.json")
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                val atomic = AtomicFile(target); val output = atomic.startWrite()
                try { output.write(evidence.toString(2).toByteArray()); atomic.finishWrite(output) }
                catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            }
            if (cleanupFailures.isNotEmpty()) {
                val original = primaryFailure
                if (original != null) cleanupFailures.forEach(original::addSuppressed)
                else throw cleanupFailures.first().also { first -> cleanupFailures.drop(1).forEach(first::addSuppressed) }
            }
        }
    }

    private data class Blob(val url: String, val file: File, val bytes: Long, val sha256: String, val mime: String)
    private data class Track(val plan: OriginalFragmentPlan, val blobs: List<Blob>, val audit: JSONObject, val sequenceSha256: String)

    private fun loadPin(context: Context, root: File, row: JSONObject, limit: Long): Blob {
        val name = row.getString("file").also { require(it.matches(Regex("[A-Za-z0-9._/-]{1,240}")) && !it.startsWith('/') && it.split('/').none { part -> part in setOf("", ".", "..") }) }
        val file = ownedStagedFile(context, File(root, name), limit)
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        val bytes = row.getLong("bytes"); val hash = row.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        assertEquals("Pinned body length", bytes, file.length()); assertEquals("Pinned encoded body", hash, sha256(file))
        val url = row.optString("url")
        if (url.isNotEmpty()) {
            val host = OriginalHlsPublicTransport.requireUrl(url).host
            require(host != "localhost" && !host.endsWith(".invalid") && !host.endsWith(".test")) { "Use public-address-form fixture URLs for guarded admission; no public network claim follows." }
        }
        return Blob(url, file, bytes, hash, row.optString("mime"))
    }

    private fun loadTrack(context: Context, root: File, row: JSONObject, role: String, durationUs: Long): Track {
        val playlist = loadPin(context, root, row.getJSONObject("playlist"), OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES.toLong())
        val mediaRows = row.getJSONArray("fragments")
        require(mediaRows.length() in 3..16) // One genuine init and at least two genuine media objects.
        val fragments = (0 until mediaRows.length()).map { loadPin(context, root, mediaRows.getJSONObject(it), 16 * 1024 * 1024L) }
        require(fragments.sumOf { it.bytes } <= 64 * 1024 * 1024L)
        require((listOf(playlist) + fragments).map { it.url }.let { it.all(String::isNotEmpty) && it.distinct().size == it.size })
        fragments.forEachIndexed { index, blob -> requireGenuineFmp4(blob.file, initialization = index == 0) }
        val exchange = Exchange(listOf(playlist) + fragments)
        val source = CapturedHlsTrackSource(row.getString("sourceUrl"), row.getString("formatId"), "$role/mp4", row.getString("protocol"))
        assertEquals("This bounded fixture has no redirect or range exchange", source.sourceUrl, playlist.url)
        val plan = exchange.client(source.sourceUrl).newCall(Request.Builder().url(source.sourceUrl).header("Accept-Encoding", "identity").build()).execute().use { response ->
            assertEquals(200, response.code)
            OriginalHlsVodPlaylist.capture(source, response.request.url.toString(), response.header("Content-Type")!!,
                response.body!!.bytes(), durationUs)
        }
        assertTrue(exchange.closedExactlyOnce()); assertEquals(listOf(playlist.url), exchange.requests.toList())
        assertEquals(fragments.map { it.url }, plan.fragments.map { it.url })
        assertTrue(plan.fragments.all { it.rangeStart == null && it.rangeEndExclusive == null })
        assertEquals(playlist.sha256, plan.hls!!.playlistSha256)
        val sequence = row.getString("encodedSequenceSha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        assertEquals(sequence, digestSequence(fragments))
        val audit = row.getJSONObject("audit")
        assertEquals(if (role == "video") "video/avc" else "audio/mp4a-latm", audit.getString("mime"))
        require(audit.getString("codecConfigurationHash").matches(Regex("SHA256:[a-fA-F0-9]{64}")))
        return Track(plan, listOf(playlist) + fragments, audit, sequence)
    }

    private fun validateCaptureMetadata(receipt: JSONObject, blobs: List<Blob>) {
        assertEquals("paired-hls-public-capture-v1", receipt.getString("schema"))
        assertEquals("actual-public-http", receipt.getString("origin"))
        require(receipt.getString("capturedAtUtc").matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z")))
        val requests = receipt.getJSONArray("requests")
        val rows = (0 until requests.length()).map(requests::getJSONObject)
        blobs.forEach { blob ->
            val matching = rows.single { it.getString("finalUrl") == blob.url }
            assertEquals(200, matching.getInt("status")); assertEquals(blob.bytes, matching.getLong("bodyBytes"))
            assertEquals(blob.sha256, matching.getString("bodySha256")); assertEquals(blob.mime, matching.getString("contentType"))
        }
    }

    /** Append after production admission/ownership; no DNS/proxy/socket policy replacement. */
    private class Exchange(blobs: List<Blob>, private val blockUrl: String? = null) : Interceptor {
        private val byUrl = blobs.associateBy { it.url }
        val requests = CopyOnWriteArrayList<String>()
        private val closes = CopyOnWriteArrayList<AtomicInteger>()
        val blocked = CountDownLatch(1)
        fun client(anchor: String): OkHttpClient = OriginalHlsPublicTransport.client(anchor, null, emptyMap()) { null }
            .newBuilder().addInterceptor(this).build()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request(); val url = request.url.toString()
            check(request.header("Range") == null)
            val blob = requireNotNull(byUrl[url]) { "Unknown URL in pinned synthetic exchange; no network fallback." }
            requests += url
            val count = AtomicInteger(); closes += count
            val input = object : ForwardingSource(blob.file.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    if (url == blockUrl) {
                        blocked.countDown()
                        val deadline = SystemClock.elapsedRealtime() + 15_000L
                        while (!chain.call().isCanceled()) {
                            if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Cancellation failed to reach the actual Call.")
                            SystemClock.sleep(10L)
                        }
                        throw IOException("Actual synthetic transport Call was cancelled.")
                    }
                    return super.read(sink, byteCount)
                }
                override fun close() { check(count.incrementAndGet() == 1); super.close() }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = blob.mime.toMediaType()
                override fun contentLength() = blob.bytes
                override fun source(): BufferedSource = input
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("Pinned synthetic exchange; no public network claim").header("Content-Type", blob.mime).body(body).build()
        }
        fun closedExactlyOnce() = closes.isNotEmpty() && closes.all { it.get() == 1 }
    }

    private fun assertPinnedTrack(media: VerifiedOriginalMedia, track: OriginalMediaTrack, pin: JSONObject, observedUs: Long) {
        val packets = media.packets.getValue(track.index)
        assertEquals(pin.getString("codec"), track.codec); assertEquals(pin.getString("mime"), track.mime)
        assertEquals(pin.getString("codecConfigurationHash"), track.codecConfigurationHash)
        assertEquals(pin.getLong("samples"), packets.samples); assertEquals(pin.getLong("encodedBytes"), packets.encodedBytes)
        assertEquals(pin.getString("payloadFingerprint"), packets.payloadFingerprint)
        assertEquals(pin.getInt("width"), track.width); assertEquals(pin.getInt("height"), track.height)
        assertEquals(pin.getInt("sampleRate"), track.sampleRate); assertEquals(pin.getInt("channels"), track.channels)
        OriginalMediaProbe.verifyObservedSpan(media, track, observedUs)
    }
    private fun trackEvidence(media: VerifiedOriginalMedia, track: OriginalMediaTrack): JSONObject {
        val p = media.packets.getValue(track.index)
        return JSONObject().put("mime", track.mime).put("codec", track.codec).put("height", track.height)
            .put("codec_configuration_hash", track.codecConfigurationHash).put("samples", p.samples)
            .put("encoded_bytes", p.encodedBytes).put("payload_fingerprint", p.payloadFingerprint)
            .put("first_sample_us", p.firstSampleUs).put("last_sample_end_us", p.lastSampleEndUs)
    }
    private fun ownedStagedFile(context: Context, file: File, limit: Long): File {
        require(!Files.isSymbolicLink(file.toPath()))
        val canonical = file.canonicalFile
        require(listOf(context.filesDir, context.cacheDir).any { canonical.toPath().startsWith(it.canonicalFile.toPath()) })
        require(canonical.isFile && canonical.length() in 1..limit)
        return canonical
    }
    private fun requireGenuineFmp4(file: File, initialization: Boolean) = RandomAccessFile(file, "r").use { input ->
        fun boxes(start: Long, end: Long): List<Pair<String, Pair<Long, Long>>> {
            val result = mutableListOf<Pair<String, Pair<Long, Long>>>(); var at = start
            while (at < end) {
                require(end - at >= 8L); input.seek(at)
                val short = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val header = if (short == 1L) 16L else 8L
                val size = when (short) { 0L -> end - at; 1L -> input.readLong(); else -> short }
                require(size >= header && size <= end - at)
                result += type to (at + header to at + size); at += size
                require(result.size <= 256)
            }
            return result
        }
        val top = boxes(0L, input.length())
        if (initialization) {
            require(top.any { it.first == "ftyp" } && top.none { it.first in setOf("moof", "mdat") })
            val moov = top.single { it.first == "moov" }.second
            require(boxes(moov.first, moov.second).any { it.first == "mvex" }) { "Ordinary MP4 initialization is not fMP4." }
        } else require(top.any { it.first == "moof" } && top.any { it.first == "mdat" } && top.none { it.first == "moov" }) {
            "Require genuine fragmented media objects, not arbitrary slices of an ordinary MP4."
        }
    }
    private fun hasDecodedFrame(view: PlayerView?): Boolean {
        val texture = view?.videoSurfaceView as? TextureView ?: return false
        if (!texture.isAvailable) return false
        val bitmap = texture.getBitmap(320, 180) ?: return false
        return try {
            val values = (0 until 180 step 8).flatMap { y -> (0 until 320 step 8).map { x ->
                bitmap.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) }
            } }
            values.max() - values.min() > 60
        } finally { bitmap.recycle() }
    }
    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun await(ms: Long, message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail(message); SystemClock.sleep(100L) }
    }
    private fun <T> onMain(action: () -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun digestSequence(blobs: List<Blob>): String = MessageDigest.getInstance("SHA-256").let { digest ->
        blobs.forEach { blob -> blob.file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        } }; digest.digest().hex()
    }
    private fun sha256(file: File) = digestSequence(listOf(Blob("", file, file.length(), "", "")))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

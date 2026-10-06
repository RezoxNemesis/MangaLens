package com.mangalens.orez

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.security.MessageDigest

class OrezResourcePackWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(2, java.util.concurrent.TimeUnit.MINUTES).readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()

    override suspend fun doWork(): Result {
        val url=inputData.getString(KEY_URL)
        val uri=inputData.getString(KEY_URI)
        if(url.isNullOrBlank()&&uri.isNullOrBlank()) return Result.failure(workDataOf(KEY_ERROR to "No resource pack source was supplied."))
        return try {
            setForeground(foregroundInfo("OREZ large local AI pack",0L,-1L))
            val dir = File(applicationContext.filesDir, "orez_packs").apply { mkdirs() }
            val key = MessageDigest.getInstance("SHA-256").digest((url ?: "local://$uri").toByteArray()).joinToString("") { "%02x".format(it) }
            val target = File(dir, "pack-$key.jsonl.part")
            var done = target.length().coerceAtMost(MAX_PACK_BYTES)

            if(!uri.isNullOrBlank()){
                val selected=Uri.parse(uri)
                val total = runCatching { applicationContext.contentResolver.openAssetFileDescriptor(selected, "r")?.use { it.length } }.getOrNull() ?: -1L
                val input=applicationContext.contentResolver.openInputStream(selected) ?: error("Unable to open selected pack.")
                val stats=input.use { HeavyweightDataVaultManager(applicationContext).importExternalStream(it, selected.lastPathSegment.orEmpty()) }
                setProgress(workDataOf(KEY_BYTES to total.coerceAtLeast(0L),KEY_TOTAL to total))
                return Result.success(workDataOf(
                    KEY_BYTES to total.coerceAtLeast(0L),
                    KEY_TOTAL to total,
                    "knowledgeRows" to stats.knowledgeRows,
                    "translationRows" to stats.translationRows,
                    "regexRows" to stats.regexRows
                ))
            }else{
            val remoteUrl = url ?: error("No remote resource pack URL was supplied.")
            val builder=Request.Builder().url(remoteUrl).header("User-Agent","MangaLens/14 OREZ-Pack").header("Accept","application/x-ndjson,application/json,text/plain,*/*")
            if (done > 0L) builder.header("Range", "bytes=$done-")

            client.newCall(builder.build()).execute().use { response ->
                if (done > 0L && response.code == 200) {
                    target.delete()
                    done = 0L
                }
                check(response.isSuccessful) { "HTTP " + response.code }

                val body = response.body ?: error("Empty pack")
                val announced = body.contentLength()
                check(announced < 0L || done + announced <= MAX_PACK_BYTES) { "Pack exceeds the 10 GB conversation-pack limit." }

                body.byteStream().use { input ->
                    FileOutputStream(target, true).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            check(done <= MAX_PACK_BYTES) { "Pack exceeds the 10 GB conversation-pack limit." }
                            setProgress(
                                workDataOf(
                                    KEY_BYTES to done,
                                    KEY_TOTAL to if (announced > 0L) done + announced else -1L
                                )
                            )
                        }
                    }
                }
            }
            }

            check(target.length() == done) { "Pack write size mismatch." }
            check(target.length() > 0L) { "Pack was empty." }

            val digest = sha256(target)
            val final = File(dir, "pack-$digest.jsonl")
            if (final.exists() && final.length() == target.length()) {
                target.delete()
            } else if (!target.renameTo(final)) {
                target.copyTo(final, true)
                target.delete()
            }

            validateJsonlEnvelope(final)
            val stats = HeavyweightDataVaultManager(applicationContext).importExternalJsonl(final)
            final.delete()
            Result.success(workDataOf(
                KEY_BYTES to done,
                KEY_TOTAL to done,
                "knowledgeRows" to stats.knowledgeRows,
                "translationRows" to stats.translationRows,
                "regexRows" to stats.regexRows
            ))
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: IOException) {
            if (runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (t.message ?: "Network failure while importing the pack")))
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "Pack installation failed")))
        }
    }

    private fun foregroundInfo(title:String,done:Long,total:Long):ForegroundInfo {
        val channelId="orez_data"
        val nm=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if(android.os.Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(android.app.NotificationChannel(channelId,"OREZ data",android.app.NotificationManager.IMPORTANCE_LOW))
        val determinate=total>0L
        val percent=if(determinate)((done*100L)/total).toInt().coerceIn(0,100) else 0
        val notification=NotificationCompat.Builder(applicationContext,channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if(determinate) "${percent}% • ${formatBytes(done)} / ${formatBytes(total)}" else "Preparing large local AI data…")
            .setProgress(if(determinate)100 else 0,percent,!determinate)
            .setOngoing(true)
            .build()
        return ForegroundInfo(10004,notification, if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    private fun formatBytes(bytes:Long):String="%.1f MB".format(bytes/1048576.0)

    private fun validateJsonlEnvelope(file: File) {
        val buffered = file.inputStream().buffered(64 * 1024)
        buffered.mark(4)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()
        if (read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            java.util.zip.ZipInputStream(buffered).use { zip ->
                var found = false
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.endsWith(".jsonl", true) || entry.name.endsWith(".jsonl.gz", true) || entry.name.endsWith(".ndjson", true))) {
                        val source: java.io.InputStream = if (entry.name.endsWith(".gz", true)) GZIPInputStream(zip, 64 * 1024) else zip
                        validateReader(BufferedReader(InputStreamReader(source, Charsets.UTF_8), 64 * 1024))
                        found = true
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                check(found) { "ZIP pack contains no .jsonl or .jsonl.gz data file." }
            }
            return
        }
        val decoded = if (read >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) GZIPInputStream(buffered, 64 * 1024) else buffered
        validateReader(BufferedReader(InputStreamReader(decoded, Charsets.UTF_8), 64 * 1024))
    }

    private fun validateReader(reader: BufferedReader) {
        var valid = 0L
        reader.useLines { lines ->
            lines.take(100).forEach { line ->
                if (line.isBlank()) return@forEach
                val trimmed = line.trim()
                require(trimmed.startsWith("{") && trimmed.endsWith("}")) { "Invalid JSONL record near row $valid" }
                valid++
            }
        }
        check(valid > 0L) { "Knowledge pack contained no JSON records." }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_URI = "uri"
        const val KEY_LABEL = "label"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val MAX_PACK_BYTES = 10L * 1024L * 1024L * 1024L
    }
}

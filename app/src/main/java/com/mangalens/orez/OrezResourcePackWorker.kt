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
                val total=runCatching{applicationContext.contentResolver.openAssetFileDescriptor(selected,"r")?.use{it.length}}.getOrDefault(-1L)
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
            val builder=Request.Builder().url(url!!).header("User-Agent","MangaLens/14 OREZ-Pack").header("Accept","application/x-ndjson,application/json,text/plain,*/*")
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
            HeavyweightDataVaultManager(applicationContext).importExternalJsonl(final)
            final.delete()
            Result.success()
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
        return ForegroundInfo(10004,notification)
    }

    private fun formatBytes(bytes:Long):String="%.1f MB".format(bytes/1048576.0)

    private fun validateJsonlEnvelope(file: File) {
        val buffered = file.inputStream().buffered(64 * 1024)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        var valid = 0L
        BufferedReader(InputStreamReader(decoded, Charsets.UTF_8), 64 * 1024).useLines { lines ->
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

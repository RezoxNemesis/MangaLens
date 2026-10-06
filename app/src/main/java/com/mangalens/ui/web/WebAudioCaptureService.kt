package com.mangalens.ui.web

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Captures this app's permitted playback only, after Android's explicit one-use consent. */
class WebAudioCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var reader: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 29 || intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (reader != null) return START_NOT_STICKY
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Audio capture permission was not granted." }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Web English subtitles", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 71, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("MangaLens English subtitles")
                .setContentText("Listening to MangaLens web playback on this device")
                .setOngoing(true).addAction(0, "Stop", stop).build()
            startForeground(71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION") val token = intent?.getParcelableExtra<Intent>("token") ?: error("Audio capture consent expired.")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, token)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUid(Process.myUid()).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val size = maxOf(32000, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).build()
            check(record!!.state == AudioRecord.STATE_INITIALIZED) { "Playback audio capture is unavailable on this device." }
            record!!.startRecording()
            status.value = "Listening to web audio • English • offline"
            active.value = true
            reader = scope.launch {
                val input = ShortArray(1600)
                val chunk = FloatArray(16000 * 12)
                var used = 0; var elapsed = 0L; var silentChunks = 0
                try {
                    while (isActive) {
                        val n = record?.read(input, 0, input.size, AudioRecord.READ_BLOCKING) ?: break
                        check(n >= 0) { "Playback audio capture stopped ($n)." }
                        clock?.invoke(elapsed + used * 1000L / 16000 + n * 1000L / 16000)
                        for (i in 0 until n) {
                            chunk[used++] = input[i] / 32768f
                            if (used >= 16000 * (windowSeconds?.invoke() ?: 6).coerceIn(3, 12)) {
                                val start = elapsed; elapsed += used * 1000L / 16000
                                var energy = 0.0
                                for (j in 0 until used) energy += (chunk[j] * chunk[j]).toDouble()
                                energy /= used
                                if (energy > .000015) { silentChunks = 0; sink?.invoke(chunk.copyOf(used), start) }
                                else if (++silentChunks >= 3) status.value = "No capturable audio. Start playback, or use Open in Video if this site blocks capture."
                                used = 0
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { status.value = failure.message ?: "Audio capture failed"; stopSelf() }
            }
        } catch (failure: Exception) {
            status.value = failure.message ?: "Web audio capture could not start"
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        reader?.cancel()
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        projection?.stop(); projection = null
        active.value = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "web_english_subtitles"
        private const val ACTION_STOP = "com.mangalens.STOP_WEB_SUBTITLES"
        @Volatile var windowSeconds: (() -> Int)? = null
        @Volatile var clock: ((Long) -> Unit)? = null
        @Volatile var sink: ((FloatArray, Long) -> Unit)? = null
        val status = MutableStateFlow("")
        val active = MutableStateFlow(false)
    }
}

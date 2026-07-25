package com.example.aicallresponder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service that downloads a whisper ggml model into [ModelStore]. Supports resume (via a
 * .part file + HTTP Range) and cancellation. Progress is exposed through the companion fields, which
 * [ModelManagerActivity] polls while visible; a progress notification is also shown.
 */
class ModelDownloadService : Service() {

    @Volatile private var cancelRequested = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelRequested = true
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                val id = intent.getStringExtra(EX_ID) ?: return stopNow()
                if (activeId != null) {
                    Log.w(TAG, "Download already in progress ($activeId); ignoring $id")
                    return START_NOT_STICKY
                }
                val url = intent.getStringExtra(EX_URL) ?: return stopNow()
                val fileName = intent.getStringExtra(EX_FILE) ?: return stopNow()
                val name = intent.getStringExtra(EX_NAME) ?: id

                activeId = id
                progress = 0
                lastError = null
                cancelRequested = false
                startForegroundCompat(name, 0)

                worker = Thread { runDownload(id, url, fileName, name) }.also { it.start() }
            }
            else -> return stopNow()
        }
        return START_NOT_STICKY
    }

    private fun runDownload(id: String, urlStr: String, fileName: String, name: String) {
        val dir = ModelStore.modelsDir(this)
        val part = File(dir, "$fileName.part")
        val finalFile = File(dir, fileName)
        var conn: HttpURLConnection? = null
        try {
            val existing = if (part.exists()) part.length() else 0L
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                throw Exception("HTTP $code")
            }

            val startAt = if (resuming) existing else 0L
            val remaining = conn.contentLengthLong.let { if (it > 0) it else -1L }
            val total = if (remaining > 0) startAt + remaining else -1L

            RandomAccessFile(part, "rw").use { raf ->
                if (resuming) raf.seek(existing) else raf.setLength(0)
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = startAt
                    var lastPct = -1
                    while (true) {
                        if (cancelRequested) throw InterruptedException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            progress = pct
                            updateNotification(name, pct)
                        }
                    }
                }
            }

            if (cancelRequested) throw InterruptedException("cancelled")
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) throw Exception("could not finalize file")
            progress = 100
            lastCompletedId = id
            Log.d(TAG, "Download complete: $fileName")
        } catch (e: InterruptedException) {
            Log.d(TAG, "Download cancelled: $fileName")
            lastError = null
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $fileName", e)
            lastError = e.message ?: "download failed"
        } finally {
            conn?.disconnect()
            activeId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopNow(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    // --- notification -------------------------------------------------------------------------

    private fun startForegroundCompat(name: String, pct: Int) {
        ensureChannel()
        val n = buildNotification(name, pct)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(name: String, pct: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(name, pct))
    }

    private fun buildNotification(name: String, pct: Int): Notification {
        val text = if (pct in 0..100) "$name — $pct%" else "$name — downloading…"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Whisper model")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setProgress(100, if (pct in 0..100) pct else 0, pct !in 0..100)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRequested = true
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIF_ID = 77

        const val ACTION_DOWNLOAD = "com.example.aicallresponder.DOWNLOAD_MODEL"
        const val ACTION_CANCEL = "com.example.aicallresponder.CANCEL_MODEL_DOWNLOAD"
        const val EX_ID = "id"
        const val EX_URL = "url"
        const val EX_FILE = "file"
        const val EX_NAME = "name"

        /** id of the model currently downloading, or null. */
        @Volatile var activeId: String? = null
        /** 0..100, or -1 when total size is unknown. */
        @Volatile var progress: Int = 0
        /** last error message (shown once), or null. */
        @Volatile var lastError: String? = null
        /** id of the most recently completed download. */
        @Volatile var lastCompletedId: String? = null

        fun start(context: Context, model: WhisperModel) {
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EX_ID, model.id)
                putExtra(EX_URL, model.url)
                putExtra(EX_FILE, model.fileName)
                putExtra(EX_NAME, model.name)
            }
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(i)
        }
    }
}

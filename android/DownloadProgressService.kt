package com.sanzz.client.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Tiruan `ro.alynsampmobile.alyn_launcher.DownloadService` milik Alyn.
 *
 * Versi Alyn aslinya cuma service notifikasi (bukan yang mengunduh): downloader-nya di Dart,
 * service ini dipanggil lewat intent START/UPDATE/STOP dengan extra "progress" & "title".
 * Fungsinya dua:
 *   1. Notifikasi progress di shade (user lihat sisa berapa).
 *   2. Menjaga proses tetap hidup saat app di-background — TANPA ini Android membunuh
 *      download 1,34 GB dalam ~1 menit (Doze / App Standby).
 *
 * Permission yang SUDAH kamu declare di manifest dan akhirnya kepakai:
 *   FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK.
 */
class DownloadProgressService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                startForegroundCompat(buildNotification(title, 0, indeterminate = true))
            }

            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
                val sub = intent.getStringExtra(EXTRA_SUBTEXT)
                val det = intent.getStringExtra(EXTRA_DETAIL)
                nm.notify(NOTIF_ID, buildNotification(title, progress, false, sub, det))
            }

            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY   // jangan di-restart otomatis; downloader-nya idempotent (resume)
    }

    // -----------------------------------------------------------------

    private val nm: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Download data game",
            NotificationManager.IMPORTANCE_LOW,   // LOW = tanpa suara, tidak mengganggu
        ).apply {
            description = "Progress unduhan asset game"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        indeterminate: Boolean,
        subText: String? = null,
        detail: String? = null,
    ): Notification {
        ensureChannel()
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = tapIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadProgressService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subText ?: if (indeterminate) "Mempersiapkan…" else "$progress%")
            .setSubText(detail)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)          // KRUSIAL: tanpa ini notifikasi bunyi tiap update
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .addAction(0, "Batalkan", cancelPi)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.sanzz.client.download.START"
        const val ACTION_UPDATE = "com.sanzz.client.download.UPDATE"
        const val ACTION_STOP = "com.sanzz.client.download.STOP"

        const val EXTRA_TITLE = "title"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_SUBTEXT = "subtext"
        const val EXTRA_DETAIL = "detail"

        private const val CHANNEL_ID = "sanzz_asset_download"
        private const val NOTIF_ID = 4711
        private const val DEFAULT_TITLE = "Mengunduh data game"

        fun start(ctx: Context, title: String = DEFAULT_TITLE) {
            val i = Intent(ctx, DownloadProgressService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /**
         * Jangan panggil ini tiap byte! Panggil dari Flow yang sudah di-throttle 5 Hz
         * (AssetDownloader sudah begitu), atau pakai sample(200) di sisi collector.
         */
        fun update(ctx: Context, progress: Int, subText: String?, detail: String? = null) {
            val i = Intent(ctx, DownloadProgressService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_PROGRESS, progress.coerceIn(0, 100))
                .putExtra(EXTRA_TITLE, DEFAULT_TITLE)
                .putExtra(EXTRA_SUBTEXT, subText)
                .putExtra(EXTRA_DETAIL, detail)
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, DownloadProgressService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

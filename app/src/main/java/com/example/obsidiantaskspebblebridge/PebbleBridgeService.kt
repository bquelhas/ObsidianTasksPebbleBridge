package com.example.obsidiantaskspebblebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service whose ONLY job is to keep a runtime-registered
 * [BackgroundReceiver] alive so watch-originated messages (DONE / REMIND /
 * FETCH) are delivered even when the app UI is closed.
 *
 * Why this exists: on Android 8+ the system refuses to deliver the *implicit*
 * broadcast "com.getpebble.action.app.RECEIVE" (which is what the Pebble/Rebble
 * app sends) to manifest-registered receivers. Only a receiver registered at
 * runtime via Context.registerReceiver gets it — and a runtime receiver only
 * lives as long as its host. A foreground service is the one host Android keeps
 * alive in the background reliably.
 *
 * Battery cost is effectively zero: the service does NO polling, holds NO
 * wakelock, and runs NO loop. It registers the receiver and sleeps. The CPU only
 * wakes when the watch actually sends something (user-initiated, rare), at which
 * point BackgroundReceiver does its brief work and goes back to sleep. A resident
 * process consumes RAM, not battery.
 *
 * The notification uses IMPORTANCE_MIN so it stays silent and collapses to the
 * bottom of the shade; the user can disable the channel (or suppress it with an
 * app like BuzzKill) to hide it entirely while the service keeps running.
 */
class PebbleBridgeService : Service() {

    private val pebbleReceiver = BackgroundReceiver()
    private var receiverRegistered = false

    // Opportunistic Obsidian refresh: while the phone is locked Android blocks
    // the background launch of Obsidian, so SyncWorker just reads whatever is on
    // disk. Catch the moment the user unlocks and — if the auto-open toggle is
    // on and the last launch is older than the sync interval — run one sync
    // cycle right away, while the launch is actually allowed.
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("openObsidianBeforeSync", false)) return
            // Same throttle the worker applies: the user-chosen open frequency.
            val intervalMs = prefs.getInt("obsidianOpenIntervalHours", 6).coerceAtLeast(1) * 3_600_000L
            val last = prefs.getLong("lastObsidianOpenMs", 0L)
            if (System.currentTimeMillis() - last < intervalMs) return
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "obsidian_unlock_sync",
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
        }
    }
    private var unlockRegistered = false

    override fun onCreate() {
        super.onCreate()

        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        val filter = IntentFilter("com.getpebble.action.app.RECEIVE")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pebbleReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pebbleReceiver, filter)
        }
        receiverRegistered = true

        // USER_PRESENT is a protected system broadcast: NOT_EXPORTED still
        // receives it (the flag only shuts out other apps).
        val unlockFilter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, unlockFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(unlockReceiver, unlockFilter)
        }
        unlockRegistered = true
    }

    // START_STICKY: if the system kills us under memory pressure, recreate the
    // service (and thus re-register the receiver) as soon as resources allow.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(pebbleReceiver) }
            receiverRegistered = false
        }
        if (unlockRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockRegistered = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.svc_channel_name),
                NotificationManager.IMPORTANCE_MIN  // silent, no peek, bottom of shade
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_notification_title))
            .setContentText(getString(R.string.svc_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "pebble_bridge"
        private const val NOTIF_ID = 1

        /** Start the service in a version-safe way (foreground service on O+). */
        fun start(context: Context) {
            val intent = Intent(context, PebbleBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

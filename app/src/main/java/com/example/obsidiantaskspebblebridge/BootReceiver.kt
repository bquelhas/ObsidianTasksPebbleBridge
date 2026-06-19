package com.example.obsidiantaskspebblebridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [PebbleBridgeService] after a device reboot or after the app is
 * updated, so watch-originated actions keep working without the user having to
 * open the app first — and re-arms any pending reminders (AlarmManager forgets
 * them across a reboot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                PebbleBridgeService.start(context)
                rearmReminders(context)
            }
        }
    }

    /**
     * AlarmManager drops every scheduled alarm across a reboot (and across an app
     * update). [ReminderStore] still holds them in SharedPreferences, so without
     * this they would linger in the UI but never fire. Re-create each still-future
     * alarm at its STORED trigger time (not recomputed) so the original schedule
     * is preserved.
     */
    private fun rearmReminders(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // pending() already prunes anything in the past (1-min grace).
        for (r in ReminderStore.pending(context)) {
            val pi = PendingIntent.getBroadcast(
                context, r.alarmId,
                Intent(context, ReminderReceiver::class.java)
                    .putExtra("TASK_TEXT", r.title)
                    .putExtra("ALARM_ID", r.alarmId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Exact-alarm permission can be revoked on Android 12+; don't let one
            // failure abort the rest.
            runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
            }.onFailure {
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
                } catch (_: Exception) {}
            }
        }
    }
}

package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.content.Intent

// Pending reminders aren't queryable from AlarmManager, so we mirror them in
// SharedPreferences: add() when scheduled, remove() when fired/cancelled.
// Each entry is stored as "alarmId|triggerAtMillis|title".
object ReminderStore {
    private const val PREFS = "ObsidianConfig"
    private const val KEY = "pendingReminders"
    const val ACTION_UPDATED = "UPDATE_REMINDERS"

    data class Reminder(val alarmId: Int, val triggerAt: Long, val title: String)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, alarmId: Int, title: String, triggerAt: Long) {
        val list = loadRaw(ctx).toMutableList()
        list.add(Reminder(alarmId, triggerAt, title))
        save(ctx, list)
        notifyUpdated(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, alarmId: Int) {
        val list = loadRaw(ctx).toMutableList()
        list.removeAll { it.alarmId == alarmId }
        save(ctx, list)
        notifyUpdated(ctx)
    }

    /** Future reminders, soonest first; prunes anything already past (1-min grace). */
    @Synchronized
    fun pending(ctx: Context): List<Reminder> {
        val now = System.currentTimeMillis()
        val all = loadRaw(ctx)
        val future = all.filter { it.triggerAt > now - 60_000L }
        if (future.size != all.size) save(ctx, future)
        return future.sortedBy { it.triggerAt }
    }

    private fun loadRaw(ctx: Context): List<Reminder> =
        (prefs(ctx).getString(KEY, "") ?: "").lines().mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val id  = parts[0].toIntOrNull()  ?: return@mapNotNull null
            val t   = parts[1].toLongOrNull() ?: return@mapNotNull null
            Reminder(id, t, parts[2])
        }

    private fun save(ctx: Context, list: List<Reminder>) {
        val s = list.joinToString("\n") { "${it.alarmId}|${it.triggerAt}|${it.title.replace("\n", " ")}" }
        prefs(ctx).edit().putString(KEY, s).apply()
    }

    private fun notifyUpdated(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_UPDATED).setPackage(ctx.packageName))
    }
}

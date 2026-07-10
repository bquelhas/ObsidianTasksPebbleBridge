package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.getpebble.android.kit.util.PebbleDictionary

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val OBSIDIAN_PACKAGE = "md.obsidian"
        // Obsidian Sync pulls shortly after the app comes to the foreground; give it
        // time to finish before we re-read the vault. Worker threads may block, and
        // WorkManager's 10-minute budget dwarfs this wait.
        private const val OBSIDIAN_SYNC_WAIT_MS = 45_000L
    }

    override fun doWork(): Result {
        maybeOpenObsidian()

        val data = PebbleDictionary()
        data.addString(90, "FETCH")
        val syncIntent = Intent(applicationContext, BackgroundReceiver::class.java).apply {
            action = "com.getpebble.action.app.RECEIVE"
            putExtra(com.getpebble.android.kit.Constants.TRANSACTION_ID, -1)
            putExtra(com.getpebble.android.kit.Constants.MSG_DATA, data.toJsonString())
        }
        applicationContext.sendBroadcast(syncIntent)
        return Result.success()
    }

    /**
     * Opt-in vault refresh: Obsidian Sync only refreshes this phone's files while
     * the Obsidian app runs, so bring it up before scanning the vault. Two modes:
     *
     *  - Shizuku (invisible): `am start` via adb-privilege — works even while the
     *    phone is locked and shows nothing on screen. Preferred when available.
     *  - Foreground (visible): startActivity, which needs an unlocked screen and
     *    the "display over other apps" grant; returns to the launcher afterwards.
     *
     * Both degrade silently to "scan whatever is on disk" when their requirements
     * aren't met, so the sync never fails outright.
     */
    private fun maybeOpenObsidian() {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("openObsidianBeforeSync", false)) return
        // Own throttle, decoupled from the (much shorter) watch-refresh interval:
        // the disk sync every 15 min is invisible, but opening Obsidian is not —
        // cap it to the user-chosen frequency (default 4×/day).
        val openMs = prefs.getInt("obsidianOpenIntervalHours", 6).coerceAtLeast(1) * 3_600_000L
        if (System.currentTimeMillis() - prefs.getLong("lastObsidianOpenMs", 0L) < openMs) {
            Log.d("ObsidianBg", "Auto-open Obsidian: throttled, syncing from disk only")
            return
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(OBSIDIAN_PACKAGE)
        val comp = launch?.component
        if (launch == null || comp == null) {
            log(ctx, "Auto-open Obsidian: app not installed, skipping")
            return
        }

        // Preferred: Shizuku — invisible and works while the phone is locked.
        if (prefs.getBoolean("useShizuku", false) && ShizukuLauncher.isReadyAndPermitted()) {
            if (ShizukuLauncher.launch(comp)) {
                prefs.edit().putLong("lastObsidianOpenMs", System.currentTimeMillis()).apply()
                log(ctx, "Auto-open Obsidian via Shizuku (background), waiting ${OBSIDIAN_SYNC_WAIT_MS / 1000}s for sync")
                Thread.sleep(OBSIDIAN_SYNC_WAIT_MS)
                return   // no keyguard/overlay needed, nothing to send home
            }
            log(ctx, "Shizuku launch failed, falling back to visible open")
        }

        // Fallback: visible foreground launch.
        // Locked / screen-off: Android suppresses the launch, so Obsidian would
        // never actually sync — don't burn 45 s waiting for nothing. The unlock
        // receiver in PebbleBridgeService retries at the next opportunity.
        // (Log.d only: this fires every cycle overnight and would flood the UI log.)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) {
            Log.d("ObsidianBg", "Auto-open Obsidian: phone locked, syncing from disk only")
            return
        }
        if (!Settings.canDrawOverlays(ctx)) {
            log(ctx, "Auto-open Obsidian: overlay permission missing, skipping")
            return
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            // Stamp BEFORE the wait: the unlock receiver throttles on this, and a
            // launch that is under way already counts as "fresh".
            prefs.edit().putLong("lastObsidianOpenMs", System.currentTimeMillis()).apply()
            log(ctx, "Auto-open Obsidian: launched, waiting ${OBSIDIAN_SYNC_WAIT_MS / 1000}s for sync")
            Thread.sleep(OBSIDIAN_SYNC_WAIT_MS)
            // Don't leave Obsidian parked on the screen after the pull — go home.
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            log(ctx, "Auto-open Obsidian failed: ${e.message}")
        }
    }

    private fun log(ctx: Context, message: String) {
        Log.d("ObsidianBg", message)
        ctx.sendBroadcast(Intent("UPDATE_LOG").putExtra("log_msg", message).setPackage(ctx.packageName))
    }
}

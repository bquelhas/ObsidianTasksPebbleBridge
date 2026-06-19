package com.example.obsidiantaskspebblebridge

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObsidianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic colors (wallpaper-based palette)
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        // Follow system light/dark mode setting
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    companion object {
        // Single-thread executor that serialises ALL vault read-modify-write I/O.
        // Watch actions can arrive concurrently (rapid DONEs, or DONE + SYNC_NOW from
        // Tasker); each does a read-modify-write on a .md file opened with "wt"
        // (truncate), so two raw Threads could both read the old content and the last
        // writer would clobber the other's change. Funnelling every handler through one
        // thread removes that race and bounds thread count under bursts.
        val io: ExecutorService = Executors.newSingleThreadExecutor()
    }
}

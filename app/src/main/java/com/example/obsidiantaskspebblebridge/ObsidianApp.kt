package com.example.obsidiantaskspebblebridge

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObsidianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Vault (violet) is a fixed brand palette, so we deliberately do NOT apply
        // Material You dynamic colors (they would override the Vault scheme with the
        // wallpaper palette). Force dark mode — dark is the approved primary direction.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
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

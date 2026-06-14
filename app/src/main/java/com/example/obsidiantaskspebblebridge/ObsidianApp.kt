package com.example.obsidiantaskspebblebridge

import android.app.Application
import com.google.android.material.color.DynamicColors

class ObsidianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: tint the whole app from the system wallpaper palette on
        // Android 12+. No-op on older devices, which keep the baseline M3 colors.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

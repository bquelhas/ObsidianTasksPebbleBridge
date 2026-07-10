package com.example.obsidiantaskspebblebridge

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku bridge. When the user has Shizuku installed, running, and has
 * granted this app permission, we can run a shell command with adb-level privilege
 * — enough to `am start` Obsidian in the background even while the phone is locked,
 * with nothing shown on screen.
 *
 * Everything here is wrapped so it degrades to false / no-op when the Shizuku
 * classes aren't wired up or the service is absent: the app never depends on it,
 * and a missing/dead Shizuku just falls back to the visible foreground launch.
 */
object ShizukuLauncher {

    /** Shizuku's service is alive (installed + started), regardless of permission. */
    fun isRunning(): Boolean =
        try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    /** Alive AND this app already holds Shizuku permission — safe to launch. */
    fun isReadyAndPermitted(): Boolean =
        try { Shizuku.pingBinder() && hasPermission() } catch (t: Throwable) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    /**
     * Launch [component] via `am start -n` through Shizuku. Blocks until the
     * command exits; returns true on exit code 0. Verified on-device that this
     * command resumes Obsidian behind the lockscreen without dismissing keyguard.
     */
    fun launch(component: ComponentName): Boolean {
        return try {
            val cmd = arrayOf("am", "start", "-n", component.flattenToShortString())
            val process = newProcess(cmd) ?: return false
            val exit = process.waitFor()
            Log.d("ObsidianBg", "Shizuku am start exit=$exit")
            exit == 0
        } catch (t: Throwable) {
            Log.d("ObsidianBg", "Shizuku launch error: ${t.message}")
            false
        }
    }

    /** Shizuku.newProcess is a @hide API; reach it by reflection (stable 11–13). */
    private fun newProcess(cmd: Array<String>): Process? {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as? Process
    }
}

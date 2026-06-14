package com.example.obsidiantaskspebblebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [PebbleBridgeService] after a device reboot or after the app is
 * updated, so watch-originated actions keep working without the user having to
 * open the app first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> PebbleBridgeService.start(context)
        }
    }
}

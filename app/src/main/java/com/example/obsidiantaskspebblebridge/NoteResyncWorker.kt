package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary

/**
 * Re-sends the Obsidian task list to the watch after a voice note is saved, so
 * the new task comes back and clears the watch's "?" placeholder — even if the
 * watch/Bluetooth was asleep when the note was first written.
 *
 * The immediate re-send in BackgroundReceiver may not land if the watch is
 * unreachable at that instant. This worker retries (WorkManager backoff) while
 * the watch is disconnected, giving the round-trip several chances over a few
 * minutes instead of waiting for the 15-min periodic sync.
 */
class NoteResyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // While the watch is unreachable, keep retrying (capped) so we don't spin
        // forever. Once reachable, fire one FETCH-style re-send and finish.
        if (!PebbleKit.isWatchConnected(applicationContext)) {
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }

        val data = PebbleDictionary()
        data.addString(90, "FETCH")
        val intent = Intent(applicationContext, BackgroundReceiver::class.java).apply {
            action = "com.getpebble.action.app.RECEIVE"
            putExtra(com.getpebble.android.kit.Constants.TRANSACTION_ID, -1)
            putExtra(com.getpebble.android.kit.Constants.MSG_DATA, data.toJsonString())
        }
        applicationContext.sendBroadcast(intent)
        return Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 6   // ~ several minutes of backoff
    }
}

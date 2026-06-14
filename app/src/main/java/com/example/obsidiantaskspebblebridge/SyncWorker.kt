package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.getpebble.android.kit.util.PebbleDictionary

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
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
}

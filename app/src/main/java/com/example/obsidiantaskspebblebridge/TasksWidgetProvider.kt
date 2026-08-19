package com.example.obsidiantaskspebblebridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import android.widget.RemoteViews

/**
 * Home-screen widget: tasks grouped by tag (one card per group), plus a button
 * to add a task. Data comes from the vault sync (see BackgroundReceiver, which
 * calls [refresh] after each sync). Tapping a task opens its note in the app the
 * user picked ("Open notes with" — Obsidian by default, or a system chooser).
 */
class TasksWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, buildViews(context, id))
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        // onUpdate fires when the widget is placed, resized, or restored after a
        // reboot — pull fresh vault data now so it never sits empty or stale until
        // the app happens to be opened. (Throttled; does NOT open Obsidian.)
        requestDataRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_OPEN) {
            intent.getStringExtra(EXTRA_FILE)?.let { openNote(context, it) }
        }
    }

    private fun openNote(context: Context, fileUri: String) {
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        val openWith = prefs.getString("openWith", "obsidian")
        try {
            if (openWith == "chooser") {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(fileUri), "text/markdown")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(view, context.getString(R.string.open_with_label))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                val obs = obsidianUri(prefs, fileUri)
                if (obs != null) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(obs)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    // Couldn't build the deep link — just open Obsidian.
                    context.packageManager.getLaunchIntentForPackage("md.obsidian")?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ObsidianWidget", "openNote failed: ${e.message}")
        }
    }

    // obsidian://open?vault=<folder name>&file=<path within the vault, no .md>
    private fun obsidianUri(prefs: android.content.SharedPreferences, fileUri: String): String? {
        return try {
            val treeUriStr = prefs.getString("vaultUri", null) ?: return null
            val treeDocId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUriStr))
            val docId = DocumentsContract.getDocumentId(Uri.parse(fileUri))
            if (!docId.startsWith(treeDocId)) return null
            val vault = treeDocId.substringAfterLast('/').substringAfterLast(':')
            val rel = docId.removePrefix(treeDocId).removePrefix("/").removeSuffix(".md")
            if (vault.isEmpty() || rel.isEmpty()) return null
            "obsidian://open?vault=${Uri.encode(vault)}&file=${Uri.encode(rel)}"
        } catch (e: Exception) { null }
    }

    companion object {
        const val ACTION_OPEN = "com.example.obsidiantaskspebblebridge.WIDGET_OPEN"
        const val EXTRA_FILE = "file"
        const val EXTRA_NEW_TASK = "widget_new_task"

        /** Repaint every placed widget from the freshly-written cache. Called after a
         *  sync writes widgetData. Direct (not via APPWIDGET_UPDATE) so it does NOT
         *  re-enter onUpdate — which would loop onUpdate -> sync -> refresh -> onUpdate. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TasksWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, buildViews(context, id))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        /** Ask BackgroundReceiver to re-read the vault and rewrite widgetData (which
         *  then calls refresh()). Throttled so a chatty launcher calling onUpdate
         *  repeatedly doesn't hammer the vault. SYNC_NOW never opens Obsidian. */
        private fun requestDataRefresh(context: Context) {
            val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("widgetLastRefreshReq", 0L) < 60_000L) return
            prefs.edit().putLong("widgetLastRefreshReq", now).apply()
            context.sendBroadcast(
                Intent(context, BackgroundReceiver::class.java)
                    .setAction(BackgroundReceiver.ACTION_SYNC_NOW)
            )
        }

        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_tasks)

            // Unique data per widget id: without it, multiple placed widgets share one
            // RemoteViewsFactory and the framework can hand back null views.
            val adapter = Intent(context, TasksWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            rv.setRemoteAdapter(R.id.widget_list, adapter)
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)

            val openTemplate = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TasksWidgetProvider::class.java).setAction(ACTION_OPEN),
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
            )
            rv.setPendingIntentTemplate(R.id.widget_list, openTemplate)

            val newTask = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_NEW_TASK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )
            rv.setOnClickPendingIntent(R.id.widget_fab, newTask)
            return rv
        }

        private fun mutableFlag() =
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

        private fun immutableFlag() =
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    }
}

package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray

/**
 * Feeds the home-screen widget's ListView. Instead of grouping all tasks into a single
 * giant card per tag (which breaks scrolling when the card is taller than the widget),
 * we flatten the list so each header and each task is an individual item in the ListView.
 * This guarantees smooth vertical scrolling on any launcher and widget size.
 */
class TasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TasksWidgetFactory(applicationContext)
}

private class Group(val title: String, val count: Int, val tasks: List<Task>)

/** "98_PESSOAL" -> "98_Pessoal": Title Case por palavra (separa em espacos e "_").
 *  As tags vêm em MAIÚSCULAS do vault (e os titulos de regra são forçados a caps na
 *  app) — aqui só se embeleza a apresentação na widget, o dado original não muda. */
private fun titleCase(raw: String): String =
    raw.split(' ').joinToString(" ") { part ->
        part.split('_').joinToString("_") { w ->
            if (w.isEmpty()) w
            else w.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercase() }
        }
    }
private class Task(val text: String, val urgency: String, val due: String, val file: String)

private sealed class ListItem {
    class HeaderItem(val title: String, val count: Int) : ListItem()
    class TaskRow(val task: Task) : ListItem()
}

private class TasksWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<ListItem> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = items.size
    override fun getViewTypeCount() = 2
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val rawGroups = parse(
            context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
                .getString("widgetData", "[]") ?: "[]"
        )
        val list = mutableListOf<ListItem>()
        for (g in rawGroups) {
            if (g.title.isNotEmpty()) {
                list.add(ListItem.HeaderItem(titleCase(g.title), g.count))
            }
            for (t in g.tasks) {
                list.add(ListItem.TaskRow(t))
            }
        }
        items = list
    }

    override fun getViewAt(position: Int): RemoteViews {
        val pkg = context.packageName
        val item = items[position]
        return when (item) {
            is ListItem.HeaderItem -> {
                val header = RemoteViews(pkg, R.layout.widget_card)
                header.setTextViewText(R.id.card_title, item.title)
                header.setTextViewText(R.id.card_count, item.count.toString())
                header
            }
            is ListItem.TaskRow -> {
                val t = item.task
                val row = RemoteViews(pkg, R.layout.widget_task)
                row.setTextViewText(R.id.task_text, t.text)
                row.setTextViewText(R.id.task_due, t.due)
                // Monocromatico: sem cor de urgencia. O marcador e as cores vem dos
                // recursos brute_* (light/dark automatico), nada e definido aqui.
                row.setOnClickFillInIntent(
                    R.id.task_root,
                    Intent().putExtra(TasksWidgetProvider.EXTRA_FILE, t.file)
                )
                row
            }
        }
    }

    private fun parse(json: String): List<Group> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tArr = o.optJSONArray("t") ?: JSONArray()
            val tasks = (0 until tArr.length()).map { j ->
                val to = tArr.getJSONObject(j)
                Task(to.optString("x"), to.optString("u"), to.optString("d"), to.optString("f"))
            }
            Group(o.optString("h"), o.optInt("c", tasks.size), tasks)
        }
    } catch (e: Exception) { emptyList() }

}

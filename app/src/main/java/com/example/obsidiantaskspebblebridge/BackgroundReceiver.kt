package com.example.obsidiantaskspebblebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class BackgroundReceiver : BroadcastReceiver() {

    private val PEBBLE_APP_UUID = UUID.fromString("177c4f8f-df6c-4721-a8eb-a7b7e9b4b60e")
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DUE_DATE_REGEX = Regex("""📅\s*(\d{4}-\d{2}-\d{2})|@due\((\d{4}-\d{2}-\d{2})\)|due::(\d{4}-\d{2}-\d{2})""")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.getpebble.action.app.RECEIVE") return

        val transactionId = intent.getIntExtra(com.getpebble.android.kit.Constants.TRANSACTION_ID, -1)
        if (transactionId != -1) PebbleKit.sendAckToPebble(context, transactionId)

        val jsonString = intent.getStringExtra(com.getpebble.android.kit.Constants.MSG_DATA) ?: return
        val data = try {
            PebbleDictionary.fromJson(jsonString)
        } catch (e: Exception) {
            sendLog(context, "JSON parse error: ${e.message}")
            return
        }

        // PebbleKit JS sends the timeline token straight to us as key 1002 (no
        // action key). Handle it here instead of dropping it as "missing key 90".
        if (!data.contains(90)) {
            if (data.contains(1002)) {
                val token = data.getString(1002)?.trim()
                val pr = goAsync()
                Thread {
                    try { handleTimelineToken(context, token) }
                    catch (e: Exception) { sendLog(context, "TL_TOKEN(1002) error: ${e.message}") }
                    finally { pr.finish() }
                }.start()
                return
            }
            sendLog(context, "Missing key 90 (action)."); return
        }
        val actionKey = data.getString(90)?.trim()
        sendLog(context, "Action received: '$actionKey'")

        val pendingResult = goAsync()
        Thread {
            try {
                when (actionKey) {
                    "FETCH" -> lerObsidianEEnviar(context)
                    "TL_TOKEN" -> handleTimelineToken(context, data.getString(93)?.trim())
                    "REMIND" -> {
                        val type = data.getInteger(92)?.toInt() ?: run { sendLog(context, "REMIND: missing type"); return@Thread }
                        val taskText = data.getString(93)
                            ?: run {
                                val index = data.getInteger(91)?.toInt() ?: run { sendLog(context, "REMIND: missing text and index"); return@Thread }
                                val tasks = gerarListaOrdenada(context)
                                if (index < 0 || index >= tasks.size || tasks[index].isHeader) return@Thread
                                cleanTitle(tasks[index].texto)
                            }
                        agendarNotificacao(context, taskText, type)
                    }
                    "DONE" -> {
                        val taskText = data.getString(93)
                        if (taskText != null) {
                            marcarTarefaPorTexto(context, taskText)
                        } else {
                            val index = data.getInteger(91)?.toInt() ?: run { sendLog(context, "DONE: missing text and index"); return@Thread }
                            marcarTarefa(context, index)
                        }
                        lerObsidianEEnviar(context)
                    }
                    "PIN_ACT" -> {
                        val code = data.getInteger(91)?.toInt()
                            ?: run { sendLog(context, "PIN_ACT: missing code"); return@Thread }
                        handlePinAction(context, code)
                    }
                    "NOTE" -> {
                        val noteText = data.getString(93)
                            ?: run { sendLog(context, "NOTE: missing text"); return@Thread }
                        appendVoiceNote(context, noteText)
                        // Re-read Obsidian and re-send the list so the new task comes
                        // back to the watch — that return is what clears the watch's
                        // "?" placeholder (confirms the note really landed in the vault).
                        lerObsidianEEnviar(context)
                        // Backup: retry the re-send for a few minutes in case the
                        // watch/BT was asleep just now (clears a stuck placeholder
                        // without waiting for the periodic sync).
                        scheduleNoteResync(context)
                    }
                    else -> sendLog(context, "Unknown action: '$actionKey'")
                }
            } catch (e: Exception) {
                sendLog(context, "Thread error: ${e.message}")
                Log.e("ObsidianBg", "Thread error", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    // --- Log ---

    private fun sendLog(context: Context, message: String) {
        Log.d("ObsidianBg", message)
        context.sendBroadcast(Intent("UPDATE_LOG").putExtra("log_msg", message))
    }

    // Tell the watch a voice note failed to save so it can flip the "?" placeholder
    // to "!" and show the reason when the user taps it (key 90 = NOTE_ERR action,
    // key 93 = short reason text).
    private fun sendNoteError(context: Context, reason: String) {
        try {
            val dict = PebbleDictionary().apply {
                addString(90, "NOTE_ERR")
                addString(93, reason)
            }
            PebbleKit.sendDataToPebble(context, PEBBLE_APP_UUID, dict)
            sendLog(context, "NOTE_ERR sent to Pebble: '$reason'")
        } catch (e: Exception) {
            sendLog(context, "NOTE_ERR send error: ${e.message}")
        }
    }

    // --- Vault access via SAF ---

    private fun getVaultDir(context: Context): DocumentFile? {
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        val uriString = prefs.getString("vaultUri", null) ?: run {
            sendLog(context, context.getString(R.string.log_vault_not_set))
            return null
        }
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        // After an app reinstall or applicationId change the SAF grant is lost even
        // though the saved URI remains, so reads silently return nothing. Detect it
        // and tell the user to re-pick the folder instead of failing quietly.
        if (dir == null || !dir.canRead()) {
            val pt = Locale.getDefault().language == "pt"
            sendLog(context, if (pt) "Sem permissão para a pasta — volta a escolher a pasta da vault em Configuração."
                             else "No folder permission — re-pick the vault folder in Setup.")
            return null
        }
        return dir
    }

    // --- FETCH: read Obsidian and send to Pebble ---

    private fun lerObsidianEEnviar(context: Context) {
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        val maxTasks = prefs.getInt("maxTasks", 20).coerceIn(5, 30)
        val tasks = gerarListaOrdenada(context)
        val limited = tasks.take(maxTasks)
        val nTitles = limited.count { it.isHeader }
        val nTasks = limited.size - nTitles
        val ptLog = Locale.getDefault().language == "pt"
        sendLog(context, if (ptLog) "A enviar: $nTitles título(s) + $nTasks tarefa(s)"
                         else "Sending: $nTitles title(s) + $nTasks task(s)")

        val today = todayMidnight()
        val dict = PebbleDictionary()
        limited.forEachIndexed { i, task ->
            if (task.isHeader) {
                // key 10 = group title, key 11 = "HEADER" marker
                dict.addString(10 + i * 10, task.texto)
                dict.addString(11 + i * 10, "HEADER")
            } else {
                // key 10 = clean title, key 11 = urgency code (W/A/C/N), key 12 = relative due
                val title = cleanTitle(task.texto).let { if (it.length > 60) it.substring(0, 57) + "…" else it }
                dict.addString(10 + i * 10, title)
                dict.addString(11 + i * 10, urgencyCode(task, today))
                dict.addString(12 + i * 10, relativeDue(task, today))
            }
        }
        try {
            PebbleKit.sendDataToPebble(context, PEBBLE_APP_UUID, dict)
            sendLog(context, "List sent to Pebble.")
        } catch (e: Exception) {
            sendLog(context, "Send error: ${e.message}")
        }

        // Broadcast preview + sync metadata (task count, timestamp) for MainActivity.
        val preview = buildPreviewString(limited, today)
        val taskCount = limited.count { !it.isHeader }
        val syncTime = System.currentTimeMillis()
        prefs.edit()
            .putString("taskPreview", preview)
            .putInt("lastTaskCount", taskCount)
            .putLong("lastSyncTime", syncTime)
            .apply()
        context.sendBroadcast(
            Intent("UPDATE_PREVIEW")
                .putExtra("preview", preview)
                .putExtra("taskCount", taskCount)
                .putExtra("syncTime", syncTime)
        )

        // Mirror dated tasks onto the Pebble timeline (no-op without a stored token).
        pushTimelinePins(context, tasks)
    }

    private fun buildPreviewString(tasks: List<ObsidianTask>, today: Date): String {
        val sb = StringBuilder()
        tasks.forEach { task ->
            if (task.isHeader) {
                sb.append("\n▶ ${task.texto}\n")
            } else {
                sb.append("  • ${buildDisplayText(task, today)}\n")
            }
        }
        return sb.toString().trim()
    }

    private fun buildDisplayText(task: ObsidianTask, today: Date): String {
        val title = cleanTitle(task.texto)
        val due = relativeDue(task, today)
        val full = if (due.isNotEmpty()) "$title  ·  $due" else title
        return if (full.length > 70) full.substring(0, 67) + "…" else full
    }

    // --- REMIND: schedule an Android local notification ---
    // `type` matches the watch-side REMIND_TYPE_* constants:
    //   0=1h  1=tonight  2=tomorrow-morning  3=+7days  10+wday=next-weekday

    private fun agendarNotificacao(context: Context, taskTitle: String, type: Int) {
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        val eveningHour = prefs.getInt("eveningHour", 20)
        val morningHour = prefs.getInt("morningHour", 9)
        val triggerAt = resolveReminderTime(type, eveningHour, morningHour)
        val alarmId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("TASK_TEXT", taskTitle)
            .putExtra("ALARM_ID", alarmId)
        val pi = PendingIntent.getBroadcast(
            context, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        ReminderStore.add(context, alarmId, taskTitle, triggerAt)
        val diff = (triggerAt - System.currentTimeMillis()) / 1000
        sendLog(context, "Reminder in ${diff}s (type=$type) for '$taskTitle'")
    }

    private fun resolveReminderTime(type: Int, eveningHour: Int, morningHour: Int): Long {
        val now = System.currentTimeMillis()
        return when {
            type == 0 -> now + 3_600_000L                                  // 1h
            type == 1 -> timeAtHourToday(eveningHour, now)                 // tonight
            type == 2 -> timeAtHourTomorrow(morningHour, now)              // tomorrow morning
            type == 3 -> now + 7L * 24 * 3_600_000L                       // +7 days exactly
            type >= 10 -> timeNextWeekday(type - 10, morningHour, now)     // weekday (wday=type-10)
            else -> now + 3_600_000L
        }
    }

    private fun timeAtHourToday(hour: Int, now: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        // If that time is in the past, push to tomorrow at the same hour.
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun timeAtHourTomorrow(hour: Int, now: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun timeNextWeekday(wday: Int, hour: Int, now: Long): Long {
        // wday: 0=Sun, 1=Mon, ..., 6=Sat (Calendar.DAY_OF_WEEK uses 1=Sun..7=Sat)
        val calWday = wday + 1  // Calendar constant
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
        var daysAhead = (calWday - today + 7) % 7
        if (daysAhead == 0) daysAhead = 7   // strictly next week if today is that day
        cal.add(java.util.Calendar.DAY_OF_YEAR, daysAhead)
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // --- DONE: mark task in file ---

    private fun marcarTarefaPorTexto(context: Context, taskText: String) {
        // The watch echoes the CLEAN title (no dates/tags), possibly truncated
        // (57 chars + "…"), so match against cleanTitle() with a prefix fallback.
        val needle = taskText.removeSuffix("…")
        val task = gerarListaOrdenada(context).firstOrNull {
            if (it.isHeader) return@firstOrNull false
            val clean = cleanTitle(it.texto)
            clean == taskText || (taskText.endsWith("…") && clean.startsWith(needle)) ||
                it.texto == taskText  // legacy fallback for raw-text echoes
        } ?: run { sendLog(context, "DONE: task not found: '$taskText'"); return }
        marcarNaFicheiro(context, task)
    }

    private fun marcarTarefa(context: Context, index: Int) {
        val tasks = gerarListaOrdenada(context)
        if (index < 0 || index >= tasks.size || tasks[index].isHeader) return
        marcarNaFicheiro(context, tasks[index])
    }

    private fun marcarNaFicheiro(context: Context, task: ObsidianTask) {
        val fileUri = Uri.parse(task.caminhoFicheiro)
        val content = context.contentResolver.openInputStream(fileUri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: run {
            sendLog(context, "DONE: cannot read file for '${task.texto}'")
            return
        }
        var found = false
        val modified = content.lines().joinToString("\n") { line ->
            if (!found && line.contains("- [ ]") && line.contains(task.texto)) {
                found = true
                line.replace("- [ ]", "- [x]")
            } else line
        }
        if (found) {
            context.contentResolver.openOutputStream(fileUri, "wt")
                ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(modified) }
            TaskCache.invalidate(fileUri)
            sendLog(context, "Done: '${task.texto}'")
        } else {
            sendLog(context, "DONE: line not found for '${task.texto}'")
        }
    }

    // --- NOTE: append a voice-dictated note to a configurable .md file ---

    private fun appendVoiceNote(context: Context, rawText: String) {
        val text = rawText.trim()
        sendLog(context, "NOTE: text='${text}' (${text.length} chars)")
        if (text.isEmpty()) { sendLog(context, "NOTE: empty text, ignored"); return }

        val pt = Locale.getDefault().language == "pt"
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)

        // Target path (default "pebble.md"); may include subfolders, e.g.
        // "98_PESSOAL/pebble.md". Ensure the leaf ends in a single .md.
        val rawPath = prefs.getString("noteFile", "pebble.md")?.trim().orEmpty()
            .ifEmpty { "pebble.md" }
        val segments = rawPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) { sendLog(context, "NOTE: bad path '$rawPath'"); return }
        val dirs = segments.dropLast(1)
        val leaf = segments.last().let { if (it.endsWith(".md", true)) it else "$it.md" }
        val displayPath = (dirs + leaf).joinToString("/")
        sendLog(context, "NOTE: pref noteFile='$rawPath' → target '$displayPath' (dirs=$dirs leaf='$leaf')")

        val vault = getVaultDir(context) ?: run {
            sendNoteError(context, if (pt) "Sem permissão à pasta" else "No folder access")
            return
        }
        sendLog(context, "NOTE: vault='${vault.name ?: vault.uri}' uri=${vault.uri}")

        // Walk (and lazily create) any subfolders, then find/create the .md file.
        var dir = vault
        for (sub in dirs) {
            val existingDir = dir.findFile(sub)?.takeIf { it.isDirectory }
            dir = existingDir ?: dir.createDirectory(sub)?.also {
                sendLog(context, "NOTE: created folder '$sub'")
            } ?: run {
                sendLog(context, if (pt) "Nota: falha ao criar pasta '$sub'" else "Note: failed to create folder '$sub'")
                sendNoteError(context, if (pt) "Falha na pasta '$sub'" else "Folder '$sub' failed")
                return
            }
            if (existingDir != null) sendLog(context, "NOTE: folder '$sub' exists")
        }

        // Pass the FULL leaf name (with .md): Android's SAF keeps a display name
        // whose extension it doesn't recognise (text/markdown), so "pebble.md"
        // stays "pebble.md" (a base name produced an extension-less file Obsidian
        // ignored).
        var noteFile = dir.findFile(leaf)
        val justCreated = noteFile == null
        if (noteFile == null) {
            noteFile = dir.createFile("text/markdown", leaf)
            if (noteFile == null) {
                sendLog(context, if (pt) "Nota: falha ao criar $displayPath" else "Note: failed to create $displayPath")
                sendNoteError(context, if (pt) "Falha ao criar ficheiro" else "File create failed")
                return
            }
            sendLog(context, "NOTE: created file '${noteFile.name}' uri=${noteFile.uri}")
        } else {
            sendLog(context, "NOTE: appending to existing '${noteFile.name}' uri=${noteFile.uri}")
        }

        val uri = noteFile.uri
        val existing = if (justCreated) "" else
            (context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        sendLog(context, "NOTE: existing content ${existing.length} bytes")

        // Voice notes are written as TASKS ("- [ ] …") so they sync back to the
        // watch list (a plain "- …" note never returns). The watch keeps showing
        // a "?" placeholder until this exact text reappears as a task.
        val prefix = prefs.getString("notePrefix", "- [ ] ") ?: "- [ ] "
        val sb = StringBuilder(existing)
        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
        sb.append(prefix).append(text).append("\n")

        try {
            context.contentResolver.openOutputStream(uri, "wt")
                ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(sb.toString()) }
            TaskCache.invalidate(uri)
            sendLog(context, if (pt) "Nota guardada em $displayPath (${sb.length} bytes, linha: '$prefix$text')"
                             else "Note saved to $displayPath (${sb.length} bytes, line: '$prefix$text')")
        } catch (e: Exception) {
            sendLog(context, if (pt) "Nota: erro ao gravar — ${e.message}" else "Note: write error — ${e.message}")
            sendNoteError(context, if (pt) "Erro ao gravar" else "Write error")
        }
    }

    // --- Timeline pins ---
    //
    // The Core Devices app has no cloud->watch timeline sync: it only intercepts the
    // PebbleKit JS's own HTTP calls to the Rebble timeline API and syncs those to the
    // watch locally. So we cannot PUT pins from Android directly. Instead we pack the
    // dated tasks into a string, hand it to the watchapp, which relays it to its PKJS;
    // the PKJS does the actual timeline PUT/DELETE, which the Core app then intercepts.

    // Packed-pin delimiters, mirroring the PKJS side.
    private val FS = "\u001f" // between fields of one pin
    private val RS = "\u001e" // between pins
    private val GS = "\u001d" // between items of a list field (headings/paragraphs)

    // Receiving the token tells us the PKJS is alive (it just produced it). The PKJS
    // holds its own token copy and does the actual timeline PUT, which the Core app
    // intercepts locally — so we push pins now even for the emulator stub token,
    // because this is the moment we're sure the PKJS is up to receive the relay.
    private fun handleTimelineToken(context: Context, token: String?) {
        if (token.isNullOrEmpty()) { sendLog(context, "TL_TOKEN: empty"); return }
        if (token.startsWith("ERR:")) {
            sendLog(context, "Timeline token unavailable: ${token.removePrefix("ERR:")}")
            return
        }
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        prefs.edit().putString("timelineToken", token).apply()
        sendLog(context, "Timeline token received (len=${token.length}); pushing pins.")
        pushTimelinePins(context, gerarListaOrdenada(context))
    }

    // Pack every dated task into a delimited string and send it to the watchapp, which
    // relays it to its PKJS to PUT onto the Rebble timeline (Core app intercepts -> syncs
    // to watch). Sending an empty payload tells the PKJS to delete all previous pins.
    private fun pushTimelinePins(context: Context, tasks: List<ObsidianTask>) {
        val today = todayMidnight()
        // Timeline shows recent + future pins; skip anything more than 2 days stale.
        val cutoff = Calendar.getInstance().apply { time = today; add(Calendar.DAY_OF_YEAR, -2) }.time
        val pt = Locale.getDefault().language == "pt"
        val vault = getVaultDir(context)?.name ?: ""

        val sb = StringBuilder()
        var count = 0
        for (task in tasks) {
            if (task.isHeader) continue
            val due = task.dueDate ?: continue
            if (due.before(cutoff)) continue
            if (count > 0) sb.append(RS)
            val (headings, paragraphs) = pinSections(task, today, vault, pt)
            val title = cleanTitle(task.texto).take(64)
            val iso = pinIso(task)
            // Content version: any change to a rendered field yields a new pin id, so the
            // watch re-renders it (the timeline dedups by id and won't re-render an
            // already-present pin when only its contents change).
            val version = (title + "|" + task.tag + "|" + iso + "|" +
                headings.joinToString(GS) + "|" + paragraphs.joinToString(GS)).hashCode()
            // Fields: id, iso, title, subtitle(tag), code(28-bit base), headings, paragraphs, lang
            sb.append(pinId(task, version)).append(FS)
                .append(iso).append(FS)
                .append(title).append(FS)
                .append(task.tag).append(FS)
                .append(pinCode(task)).append(FS)
                .append(headings.joinToString(GS)).append(FS)
                .append(paragraphs.joinToString(GS)).append(FS)
                .append(if (pt) "pt" else "en")
            count++
        }

        val dict = PebbleDictionary().apply {
            addString(90, "TLPIN")
            addString(93, sb.toString())
        }
        try {
            PebbleKit.sendDataToPebble(context, PEBBLE_APP_UUID, dict)
            sendLog(context, "Timeline: $count pin(s) sent to watch for sync.")
        } catch (e: Exception) {
            sendLog(context, "Timeline send error: ${e.message}")
        }
    }

    // The task's context as paired (heading, paragraph) sections for the pin detail
    // view: each heading is a small label shown above its value. The tag is carried
    // separately as the pin subtitle (the prominent line under the title), so it is
    // not repeated here.
    private fun pinSections(task: ObsidianTask, today: Date, vault: String, pt: Boolean):
        Pair<List<String>, List<String>> {
        val headings = mutableListOf<String>()
        val paragraphs = mutableListOf<String>()
        fun add(h: String, p: String) {
            if (p.isBlank()) return
            // Section text must not contain the packed delimiters.
            headings += h
            paragraphs += p.replace(FS, " ").replace(RS, " ").replace(GS, " ")
        }
        val dueFmt = SimpleDateFormat(if (pt) "EEEE, d 'de' MMMM" else "EEEE, MMMM d",
            if (pt) Locale("pt") else Locale.US)
        task.dueDate?.let {
            add(if (pt) "Prazo" else "Due", dueFmt.format(it) + " (" + relativeDue(task, today) + ")")
        }
        add(if (pt) "Nota" else "Note", noteName(task.caminhoFicheiro))
        add(if (pt) "Cofre" else "Vault", vault)
        return headings to paragraphs
    }

    // Readable note (file) name from the SAF document URI: ".../Daily/2026-06-15.md" -> "2026-06-15".
    private fun noteName(uriString: String): String {
        val seg = Uri.parse(uriString).lastPathSegment ?: return ""
        return seg.substringAfterLast('/').removeSuffix(".md")
    }

    // Stable identity of a task (file + title), independent of the displayed content.
    private fun pinIdentity(task: ObsidianTask): String {
        val basis = task.caminhoFicheiro + "|" + cleanTitle(task.texto)
        return "obsidian-" + Integer.toHexString(basis.hashCode().toInt()) +
            "-" + Integer.toHexString(task.texto.hashCode())
    }

    // Timeline pin id: stable identity + a content-version suffix so re-rendering is
    // forced whenever the displayed fields change.
    private fun pinId(task: ObsidianTask, version: Int): String =
        pinIdentity(task) + "-" + Integer.toHexString(version)

    // Stable 28-bit code for a task, derived from its identity (NOT the content version)
    // so action routing survives content changes. Pin actions OR an action type into the
    // top 4 bits of the launchCode; this base (low 28 bits) is how we find the task back.
    private fun pinCode(task: ObsidianTask): Int = pinIdentity(task).hashCode() and 0x0FFFFFFF

    // A timeline pin action fired: the launch code is (actionType shl 28) | pinCode.
    // Decode it, find the matching task, and run the same action as inside the app.
    private fun handlePinAction(context: Context, code: Int) {
        val actionType = (code ushr 28) and 0xF
        val base = code and 0x0FFFFFFF
        val tasks = gerarListaOrdenada(context)
        val task = tasks
            .firstOrNull { !it.isHeader && it.dueDate != null && pinCode(it) == base }
        if (task == null) {
            sendLog(context, "PIN_ACT $code: no matching task"); return
        }
        when (actionType) {
            0 -> { marcarTarefaPorTexto(context, task.texto); lerObsidianEEnviar(context) }
            1 -> agendarNotificacao(context, cleanTitle(task.texto), 0)   // 1h
            2 -> agendarNotificacao(context, cleanTitle(task.texto), 2)   // tomorrow morning
            3 -> agendarNotificacao(context, cleanTitle(task.texto), 3)   // +7 days
            else -> sendLog(context, "PIN_ACT: unknown type $actionType")
        }
    }

    private fun pinIso(task: ObsidianTask): String {
        val cal = Calendar.getInstance().apply {
            time = task.dueDate!!
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(cal.time)
    }

    // Enqueue a retrying re-send of the task list so a freshly-saved note's task
    // gets back to the watch even if it was unreachable during the immediate send.
    private fun scheduleNoteResync(context: Context) {
        val req = OneTimeWorkRequestBuilder<NoteResyncWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("note_resync", ExistingWorkPolicy.REPLACE, req)
    }

    // --- Task data model ---

    data class ObsidianTask(
        val texto: String,
        val prioridade: Int,
        val tag: String,
        val caminhoFicheiro: String,
        val isHeader: Boolean,
        val dueDate: Date? = null
    )

    data class Rule(val tag: String, val priority: Int, val groupName: String)

    // --- Due date helpers ---

    private fun todayMidnight(): Date =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time

    private fun daysBetween(from: Date, to: Date): Int =
        ((to.time - from.time) / (1000L * 60 * 60 * 24)).toInt()

    private fun extractDueDate(text: String): Date? {
        val match = DUE_DATE_REGEX.find(text) ?: return null
        val dateStr = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return runCatching { DATE_FORMAT.parse(dateStr) }.getOrNull()
    }

    private fun urgencyScore(task: ObsidianTask): Int {
        val today = todayMidnight()
        return when {
            task.dueDate == null          -> 0
            task.dueDate.before(today)    -> 300
            daysBetween(today, task.dueDate) == 0 -> 200
            daysBetween(today, task.dueDate) <= 7 -> 100
            else                          -> 50
        }
    }

    private fun urgencyTag(task: ObsidianTask, today: Date): String {
        if (task.dueDate == null) return ""
        val diff = daysBetween(today, task.dueDate)
        return when {
            diff < 0  -> "!"
            diff == 0 -> "hj"
            diff == 1 -> "+1d"
            diff <= 7 -> "+${diff}d"
            else      -> ""
        }
    }

    // Single-letter urgency code sent to the watch — drives the row icon:
    //   W = overdue (warning), A = due within 7 days (alarm),
    //   C = has a date >7 days out (calendar), N = no date (note).
    private fun urgencyCode(task: ObsidianTask, today: Date): String {
        val due = task.dueDate ?: return "N"
        val diff = daysBetween(today, due)
        return when {
            diff < 0  -> "W"
            diff <= 7 -> "A"
            else      -> "C"
        }
    }

    // Relative due text shown under the title on the selected row.
    private fun relativeDue(task: ObsidianTask, today: Date): String {
        val due = task.dueDate ?: return ""
        val diff = daysBetween(today, due)
        val pt = Locale.getDefault().language == "pt"
        return when {
            diff < 0  -> if (pt) "Atrasada ${-diff}d" else "Overdue ${-diff}d"
            diff == 0 -> if (pt) "Hoje"       else "Today"
            diff == 1 -> if (pt) "Amanhã"     else "Tomorrow"
            diff <= 7 -> if (pt) "Em $diff dias" else "In $diff days"
            else      -> SimpleDateFormat("d MMM", Locale.getDefault()).format(due)
        }
    }

    // Strip Obsidian/Tasks metadata so the watch shows a clean title:
    // due/scheduled/start/created/done dates, priority & recurrence emoji,
    // #tags, and [[wikilink]] brackets.
    private fun cleanTitle(texto: String): String {
        var t = texto
        t = t.replace(Regex("""[📅⏳🛫➕✅]\s*\d{4}-\d{2}-\d{2}"""), " ")
        t = t.replace(Regex("""@due\(\d{4}-\d{2}-\d{2}\)"""), " ")
        t = t.replace(Regex("""due::\s*\d{4}-\d{2}-\d{2}"""), " ")
        t = t.replace(Regex("""[⏫🔺🔼🔽⏬🔁]"""), " ")
        t = t.replace(Regex("""(^|\s)#[\w/\-]+"""), " ")
        t = t.replace(Regex("""\[\[([^\]|]+)(\|[^\]]+)?\]\]"""), "$1")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t
    }

    // --- Rules ---

    private fun parseRules(rawText: String): List<Rule> =
        rawText.lines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size >= 3) {
                try { Rule(parts[0].trim(), parts[1].trim().toInt(), parts[2].trim()) }
                catch (e: NumberFormatException) { null }
            } else null
        }

    // --- Build sorted task list ---

    private fun gerarListaOrdenada(context: Context): List<ObsidianTask> {
        val vaultDir = getVaultDir(context) ?: return emptyList()
        val prefs = context.getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)
        val rules = parseRules(prefs.getString("rules", "") ?: "")
        val rawTasks = mutableListOf<ObsidianTask>()

        traverseDir(context, vaultDir, rules, rawTasks)

        val finalList = mutableListOf<ObsidianTask>()
        rawTasks.sortedByDescending { urgencyScore(it) * 1000 + it.prioridade }
            .groupBy { it.tag }
            .forEach { (tag, tasks) ->
                finalList.add(ObsidianTask(tag, 999, "HEADER", "", true))
                finalList.addAll(tasks)
            }
        return finalList
    }

    private fun traverseDir(context: Context, dir: DocumentFile, rules: List<Rule>, out: MutableList<ObsidianTask>) {
        dir.listFiles().forEach { file ->
            when {
                file.isDirectory -> {
                    // Skip hidden folders (.trash, .obsidian, .git…) exactly like
                    // Obsidian does — deleted notes live in .trash and must not resurface.
                    val name = file.name ?: ""
                    if (!name.startsWith(".")) traverseDir(context, file, rules, out)
                }
                file.name?.endsWith(".md") == true -> readMdFile(context, file, rules, out)
            }
        }
    }

    private fun readMdFile(context: Context, file: DocumentFile, rules: List<Rule>, out: MutableList<ObsidianTask>) {
        try {
            val lines = TaskCache.getLines(file) {
                context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader(Charsets.UTF_8)?.readLines() ?: emptyList()
            }
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("- [ ]")) {
                    val content = trimmed.substringAfter("- [ ]").trim()
                    if (content.isNotEmpty()) out.add(processarTask(content, file.uri.toString(), rules))
                }
            }
        } catch (e: Exception) {
            sendLog(context, "Error reading ${file.name}: ${e.message}")
        }
    }

    private fun processarTask(texto: String, uriString: String, rules: List<Rule>): ObsidianTask {
        val dueDate = extractDueDate(texto)
        for (rule in rules) {
            if (texto.contains(rule.tag, ignoreCase = true))
                return ObsidianTask(texto, rule.priority, rule.groupName, uriString, false, dueDate)
        }
        val defaultGroup = Uri.parse(uriString).lastPathSegment
            ?.substringBeforeLast('/')?.substringAfterLast('/') ?: "Raiz"
        return ObsidianTask(texto, 10, defaultGroup, uriString, false, dueDate)
    }
}

package com.example.obsidiantaskspebblebridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.getpebble.android.kit.util.PebbleDictionary
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // The two tabs live in ViewPager2 pages that are created/destroyed by the
    // pager, so every view reference is nullable and (re)bound in bindSetupPage /
    // bindSyncPage. State that must survive a page rebind lives in the activity:
    // the tag rows (tagAdapter) and the running log (logBuffer).
    private var txtLog: TextView? = null
    private var scrollLog: ScrollView? = null
    private var txtVaultName: TextView? = null
    private var spinnerSyncInterval: AutoCompleteTextView? = null
    private var edtMaxTasks: TextInputEditText? = null
    private var edtNoteFile: TextInputEditText? = null
    private var txtPreview: TextView? = null
    private var rvTags: RecyclerView? = null
    private var txtTagsEmpty: TextView? = null
    private var txtSyncStatus: TextView? = null
    private var txtReminders: TextView? = null
    private var cardVault: MaterialCardView? = null

    private lateinit var tagAdapter: TagRuleAdapter
    private val logBuffer = StringBuilder()

    private lateinit var prefs: android.content.SharedPreferences

    private val intervalMinutes = listOf(15, 30, 60, 120)

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("vaultUri", uri.toString()).apply()
            updateVaultLabel(uri)
            log(getString(R.string.log_vault_saved, uri.lastPathSegment ?: uri.toString()))
            // Auto-discover tags from the freshly picked vault (merges, keeping any
            // rows the user already has).
            scanAndMergeTags()
        }
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "UPDATE_LOG"     -> intent.getStringExtra("log_msg")?.let { log(it) }
                "UPDATE_PREVIEW" -> {
                    intent.getStringExtra("preview")?.let { updatePreview(it) }
                    if (intent.hasExtra("syncTime")) {
                        updateSyncStatus(
                            intent.getIntExtra("taskCount", 0),
                            intent.getLongExtra("syncTime", 0L)
                        )
                    }
                }
                ReminderStore.ACTION_UPDATED -> renderReminders()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ObsidianConfig", Context.MODE_PRIVATE)

        // Tag rows are owned by the activity so edits survive page swipes.
        tagAdapter = TagRuleAdapter(loadTagRules().toMutableList())
        // Auto-persist tag rows on every change — no Save button needed.
        tagAdapter.onRulesChanged = { persistTagRules() }
        tagAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateTagsEmpty()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateTagsEmpty()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateTagsEmpty()
        })

        setupPager()

        val filter = IntentFilter().apply {
            addAction("UPDATE_LOG")
            addAction("UPDATE_PREVIEW")
            addAction(ReminderStore.ACTION_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(messageReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(messageReceiver, filter)

        // Watch-originated messages (DONE / REMIND / FETCH) arrive as the implicit
        // broadcast "com.getpebble.action.app.RECEIVE", which Android 8+ refuses to
        // deliver to manifest-registered receivers. PebbleBridgeService keeps a
        // RUNTIME-registered receiver alive in the background so these work even
        // when this Activity is closed.
        PebbleBridgeService.start(this)

        verificarPermissoes()
        scheduleAutoSync(replace = false)
        enviarParaPebble()
    }

    override fun onResume() {
        super.onResume()
        renderReminders()
    }

    /** Render the list of pending (scheduled, not-yet-fired) reminders. */
    private fun renderReminders() {
        val tv = txtReminders ?: return
        val pending = ReminderStore.pending(this)
        if (pending.isEmpty()) {
            tv.text = getString(R.string.reminders_empty)
            return
        }
        val now = System.currentTimeMillis()
        tv.text = pending.joinToString("\n") { r ->
            "• ${r.title}  —  ${formatReminderWhen(r.triggerAt, now)}"
        }
    }

    /** Human "when" for a reminder: time today, weekday+time this week, else date+time. */
    private fun formatReminderWhen(triggerAt: Long, now: Long): String {
        val timeFmt = android.text.format.DateFormat.getTimeFormat(this)
        val timeStr = timeFmt.format(java.util.Date(triggerAt))
        val cal = java.util.Calendar.getInstance()
        val today = cal.apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = ((triggerAt - today) / (1000L * 60 * 60 * 24)).toInt()
        return when {
            days <= 0 -> getString(R.string.reminder_today, timeStr)
            days == 1 -> getString(R.string.reminder_tomorrow, timeStr)
            days in 2..6 -> {
                val wd = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                    .format(java.util.Date(triggerAt))
                "$wd, $timeStr"
            }
            else -> {
                val d = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                    .format(java.util.Date(triggerAt))
                "$d, $timeStr"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Focus-loss may never fire (e.g. user types then backgrounds the app), so
        // flush the text fields here too. Both persist helpers no-op if unbound.
        persistNoteFile()
        persistMaxTasks()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(messageReceiver)
    }

    // --- ViewPager2 / tabs ---

    private fun setupPager() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = PagerAdapter { position, root ->
            if (position == 0) bindSetupPage(root) else bindSyncPage(root)
        }
        // 2 pages total → keep the neighbour alive so swiping back never loses
        // in-progress edits or the log view.
        viewPager.offscreenPageLimit = 1

        // Edge-to-edge: the CoordinatorLayout (fitsSystemWindows) insets the top
        // for the tab bar; here we pad the pager's internal RecyclerView so page
        // content clears the navigation bar (and side cutouts in landscape).
        (viewPager.getChildAt(0) as? RecyclerView)?.let { inner ->
            ViewCompat.setOnApplyWindowInsetsListener(viewPager) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                inner.setPadding(bars.left, 0, bars.right, bars.bottom)
                insets
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = getString(if (pos == 0) R.string.tab_config else R.string.tab_sync)
        }.attach()
    }

    private fun bindSetupPage(root: View) {
        txtVaultName  = root.findViewById(R.id.txtVaultName)
        txtPreview    = root.findViewById(R.id.txtPreview)
        txtTagsEmpty  = root.findViewById(R.id.txtTagsEmpty)
        txtSyncStatus = root.findViewById(R.id.txtSyncStatus)
        cardVault     = root.findViewById(R.id.cardVault)
        rvTags        = root.findViewById(R.id.rvTags)
        edtNoteFile   = root.findViewById(R.id.edtNoteFile)

        edtNoteFile?.setText(prefs.getString("noteFile", "pebble.md"))
        // Auto-save the voice-note target file when the field loses focus.
        edtNoteFile?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistNoteFile() }

        rvTags?.let { rv ->
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = tagAdapter
            tagAdapter.touchHelper.attachToRecyclerView(rv)
        }

        root.findViewById<MaterialButton>(R.id.btnChooseFolder).setOnClickListener {
            folderPickerLauncher.launch(null)
        }
        root.findViewById<MaterialButton>(R.id.btnAddTag).setOnClickListener {
            tagAdapter.addRow()
            updateTagsEmpty()
            rvTags?.let { rv -> rv.post { rv.smoothScrollToPosition(tagAdapter.itemCount - 1) } }
        }
        root.findViewById<MaterialButton>(R.id.btnRescanTags).setOnClickListener { scanAndMergeTags() }
        root.findViewById<MaterialButton>(R.id.btnInstallWatchStore).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://apps.rebble.io/en_US/application/6a2eb7a169dd300009bf84e4")))
        }
        root.findViewById<MaterialButton>(R.id.btnInstallWatch).setOnClickListener { installWatchApp() }
        root.findViewById<MaterialButton>(R.id.btnSaveVault).setOnClickListener {
            // Explicitly persist the vault + note-file settings and confirm. The
            // vault URI is already stored on folder-pick; this also flushes the
            // note-file field and gives clear "saved" feedback.
            edtNoteFile?.clearFocus()
            persistNoteFile()
            Toast.makeText(this, getString(R.string.vault_saved), Toast.LENGTH_SHORT).show()
        }

        val vaultUri = prefs.getString("vaultUri", null)
        if (vaultUri != null) updateVaultLabel(Uri.parse(vaultUri)) else updateVaultEmptyState(false)
        prefs.getString("taskPreview", null)?.let { updatePreview(it) }
        restoreSyncStatus()
        updateTagsEmpty()
    }

    private fun bindSyncPage(root: View) {
        txtLog              = root.findViewById(R.id.txtLog)
        scrollLog           = root.findViewById(R.id.scrollLog)
        spinnerSyncInterval = root.findViewById(R.id.spinnerSyncInterval)
        edtMaxTasks         = root.findViewById(R.id.edtMaxTasks)
        txtReminders        = root.findViewById(R.id.txtReminders)
        renderReminders()

        edtMaxTasks?.setText(prefs.getInt("maxTasks", 20).toString())
        // Auto-save the max-tasks value when the field loses focus.
        edtMaxTasks?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistMaxTasks() }
        setupSyncIntervalDropdown()

        // The log lives in a ScrollView nested inside the page's NestedScrollView,
        // which otherwise swallows the drag. While touching the log, tell the
        // parent not to intercept so the log scrolls independently.
        scrollLog?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false  // let the ScrollView handle the actual scrolling
        }

        // Restore the running log into the freshly created view.
        if (logBuffer.isNotEmpty()) {
            txtLog?.text = logBuffer.toString().removePrefix("\n")
            scrollLog?.post { scrollLog?.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        root.findViewById<MaterialButton>(R.id.btnSync).setOnClickListener { enviarParaPebble() }
        root.findViewById<ImageButton>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        root.findViewById<ImageButton>(R.id.btnClearLog).setOnClickListener { clearLog() }
    }

    private fun clearLog() {
        logBuffer.setLength(0)
        txtLog?.text = ""
        Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("Obsidian Pebble log", txtLog?.text ?: "")
        )
        Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
    }

    /** Auto-persist the tag rows (called on every tag edit/add/delete/reorder). */
    private fun persistTagRules() {
        prefs.edit().putString("rules", serializeTagRules()).apply()
    }

    /** Auto-persist the max-tasks value (called when the field loses focus). */
    private fun persistMaxTasks() {
        val maxTasks = edtMaxTasks?.text?.toString()?.toIntOrNull()?.coerceIn(5, 30) ?: return
        prefs.edit().putInt("maxTasks", maxTasks).apply()
    }

    /** Auto-persist the voice-note target file name (normalised to end in .md). */
    private fun persistNoteFile() {
        val edt = edtNoteFile ?: return   // field not bound → don't clobber the saved value
        val raw = edt.text?.toString()?.trim().orEmpty().ifEmpty { "pebble.md" }
        val name = if (raw.endsWith(".md", ignoreCase = true)) raw else "$raw.md"
        prefs.edit().putString("noteFile", name).apply()
        // Reflect the normalised value back into the field.
        if (edtNoteFile?.text?.toString() != name) edtNoteFile?.setText(name)
    }

    /** Auto-persist the sync interval and reschedule the periodic worker. */
    private fun persistInterval(idx: Int) {
        val interval = intervalMinutes.getOrElse(idx) { 15 }
        prefs.edit().putInt("syncIntervalMinutes", interval).apply()
        scheduleAutoSync(replace = true)
    }

    // --- Tag editor (Setup tab) ---

    private fun updateTagsEmpty() {
        txtTagsEmpty?.visibility = if (tagAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /** Parse the stored "tag, priority, name" lines into rows, ordered by priority desc. */
    private fun loadTagRules(): List<TagRuleAdapter.TagRule> =
        (prefs.getString("rules", "") ?: "").lines()
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size >= 3) {
                    val tag = parts[0].trim()
                    val priority = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                    val name = parts.drop(2).joinToString(",").trim()
                    if (tag.isEmpty()) null else Triple(tag, priority, name)
                } else null
            }
            .sortedByDescending { it.second }
            .map { TagRuleAdapter.TagRule(it.first, it.third) }

    /**
     * Serialize rows back to the watch's "tag, priority, name" format. Row order
     * defines priority (top = highest); a base of 100 keeps every configured tag
     * above the default folder-group priority (10) used by unmatched tasks.
     */
    private fun serializeTagRules(): String {
        val rows = tagAdapter.rules.filter { it.tag.trim().isNotEmpty() }
        val n = rows.size
        return rows.mapIndexed { index, r ->
            val priority = (n - index) + 100
            val title = r.title.trim().replace(",", " ").ifBlank { r.tag.trim() }
            "${r.tag.trim()}, $priority, $title"
        }.joinToString("\n")
    }

    private fun scanAndMergeTags() {
        val vaultUri = prefs.getString("vaultUri", null)
        if (vaultUri == null) {
            Toast.makeText(this, getString(R.string.log_vault_not_set), Toast.LENGTH_SHORT).show()
            return
        }
        log(getString(R.string.tags_scanning))
        Thread {
            val found = try { TagScanner.scan(this, vaultUri) } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                val existing = tagAdapter.rules.toMutableList()
                val have = existing.map { it.tag.trim().lowercase() }.toMutableSet()
                for (p in found) {
                    val key = p.tag.lowercase()
                    if (key !in have) {
                        existing.add(TagRuleAdapter.TagRule(p.tag, p.title))
                        have.add(key)
                    }
                }
                tagAdapter.replaceAll(existing)
                updateTagsEmpty()
                log(
                    if (found.isEmpty()) getString(R.string.tags_scan_none)
                    else getString(R.string.tags_scan_result, found.size)
                )
            }
        }.start()
    }

    private fun setupSyncIntervalDropdown() {
        val spinner = spinnerSyncInterval ?: return
        val labels = listOf(
            getString(R.string.sync_15min), getString(R.string.sync_30min),
            getString(R.string.sync_1h),   getString(R.string.sync_2h)
        )
        spinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        val saved = prefs.getInt("syncIntervalMinutes", 15)
        val idx   = intervalMinutes.indexOf(saved).let { if (it < 0) 0 else it }
        spinner.setText(labels[idx], false)
        spinner.tag = idx
        spinner.setOnItemClickListener { _, _, pos, _ ->
            spinner.tag = pos
            persistInterval(pos)   // auto-save on selection
        }
    }

    private fun updateVaultLabel(uri: Uri) {
        val seg = uri.lastPathSegment ?: uri.toString()
        txtVaultName?.text = seg.substringAfterLast(':').substringAfterLast('/').ifBlank { seg }
        updateVaultEmptyState(true)
    }

    /** Highlight the vault card (red stroke + name) when no folder is configured,
     *  so the required first step is obvious. */
    private fun updateVaultEmptyState(hasVault: Boolean) {
        if (hasVault) {
            txtVaultName?.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            cardVault?.strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
        } else {
            txtVaultName?.text = getString(R.string.vault_none)
            txtVaultName?.setTextColor(themeColor(com.google.android.material.R.attr.colorError))
            cardVault?.strokeColor = themeColor(com.google.android.material.R.attr.colorError)
        }
    }

    private fun updateSyncStatus(count: Int, timeMillis: Long) {
        runOnUiThread {
            txtSyncStatus?.text = if (timeMillis <= 0L) getString(R.string.sync_status_never)
            else getString(
                R.string.sync_status, count,
                android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(timeMillis))
            )
        }
    }

    private fun restoreSyncStatus() {
        updateSyncStatus(prefs.getInt("lastTaskCount", 0), prefs.getLong("lastSyncTime", 0L))
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else 0xFF000000.toInt()
    }

    private fun updatePreview(text: String) {
        runOnUiThread { txtPreview?.text = text.ifBlank { getString(R.string.preview_empty) } }
    }

    private fun scheduleAutoSync(replace: Boolean) {
        val minutes = prefs.getInt("syncIntervalMinutes", 15).toLong().coerceAtLeast(15L)
        val policy  = if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "obsidian_sync", policy,
                PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES).build()
            )
    }

    // Copies the bundled watch app (.pbw, in assets) to a shareable cache file and
    // hands it to the Pebble/Core app via a VIEW intent — a one-tap install/update
    // for the watch app without needing a USB/dev-connection toolchain.
    private fun installWatchApp() {
        try {
            val pbwDir = java.io.File(cacheDir, "pbw").apply { mkdirs() }
            val pbwFile = java.io.File(pbwDir, "teste_obsidian.pbw")
            assets.open("teste_obsidian.pbw").use { input ->
                pbwFile.outputStream().use { output -> input.copyTo(output) }
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", pbwFile
            )

            // No MIME type on purpose: the Core app's .pbw intent-filter matches on
            // the content:// scheme + ".*\.pbw" path and declares no type, so an
            // intent carrying a type would fail to match it.
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Prefer the new Core Devices app; otherwise let the user pick a handler.
            val pm = packageManager
            val coreApp = "coredevices.coreapp"
            val target = if (pm.getLaunchIntentForPackage(coreApp) != null) {
                intent.setPackage(coreApp)
            } else {
                Intent.createChooser(intent, getString(R.string.btn_install_watch))
            }

            if (target.resolveActivity(pm) != null || target.action == Intent.ACTION_CHOOSER) {
                startActivity(target)
                log(getString(R.string.install_launched))
            } else {
                Toast.makeText(this, getString(R.string.install_no_handler), Toast.LENGTH_LONG).show()
                log(getString(R.string.install_no_handler))
            }
        } catch (e: Exception) {
            val msg = getString(R.string.install_error, e.message ?: "unknown")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            log(msg)
        }
    }

    private fun enviarParaPebble() {
        log(getString(R.string.log_sending))
        val data = PebbleDictionary()
        data.addString(90, "FETCH")
        sendBroadcast(Intent(this, BackgroundReceiver::class.java).apply {
            action = "com.getpebble.action.app.RECEIVE"
            putExtra(com.getpebble.android.kit.Constants.TRANSACTION_ID, -1)
            putExtra(com.getpebble.android.kit.Constants.MSG_DATA, data.toJsonString())
        })
    }

    private fun log(msg: String) {
        Log.d("ObsidianDebug", msg)
        val line = "\n${System.currentTimeMillis() % 100000}: $msg"
        runOnUiThread {
            logBuffer.append(line)
            txtLog?.append(line)
            scrollLog?.let { sv -> sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) } }
        }
    }

    private fun verificarPermissoes() {
        // POST_NOTIFICATIONS is still needed for the foreground bridge service's
        // persistent notification (reminders themselves are now watch-native timeline
        // pins, not Android notifications).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }
}

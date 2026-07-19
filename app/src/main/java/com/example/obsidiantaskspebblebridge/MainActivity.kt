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
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import androidx.documentfile.provider.DocumentFile
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
import com.google.android.material.textfield.TextInputEditText
import com.getpebble.android.kit.util.PebbleDictionary
import java.util.concurrent.TimeUnit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var llReminders: LinearLayout? = null
    private var txtRemindersHeader: TextView? = null
    private var edtEveningHour: TextInputEditText? = null
    private var edtMorningHour: TextInputEditText? = null
    private var cardVault: MaterialCardView? = null
    private var switchShizuku: com.google.android.material.switchmaterial.SwitchMaterial? = null

    private lateinit var tagAdapter: TagRuleAdapter
    private val logBuffer = StringBuilder()

    // Shizuku permission result arrives asynchronously; reflect it into the pref
    // and the switch. Guarded so it's inert when the Shizuku service is absent.
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            prefs.edit().putBoolean("useShizuku", granted).apply()
            switchShizuku?.isChecked = granted
            if (!granted) Toast.makeText(this, getString(R.string.shizuku_denied), Toast.LENGTH_LONG).show()
        }
    private val shizukuReqCode = 4321

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

        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener) }

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
        // NOT_EXPORTED: every sender of these actions is this app itself (they all
        // use setPackage), so no other app needs -- or should be able -- to deliver them.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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

    /** Render the list of pending (scheduled, not-yet-fired) reminders with cancel buttons. */
    private fun renderReminders() {
        val pending = ReminderStore.pending(this)
        val ll = llReminders ?: return
        ll.removeAllViews()
        txtRemindersHeader?.text =
            if (pending.isEmpty()) getString(R.string.section_reminders)
            else "${getString(R.string.section_reminders)} (${pending.size})"
        val dp = resources.displayMetrics.density
        if (pending.isEmpty()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            }
            box.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_empty_reminders)
                setColorFilter(
                    themeColor(com.google.android.material.R.attr.colorOutline),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
            })
            box.addView(TextView(this).apply {
                text = getString(R.string.reminders_empty)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            ll.addView(box)
            return
        }
        val now = System.currentTimeMillis()
        pending.forEach { r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(this).apply {
                text = "• ${r.title}  —  ${formatReminderWhen(r.triggerAt, now)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnX = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_clear)
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                background = ta.getDrawable(0)
                ta.recycle()
                setColorFilter(
                    themeColor(com.google.android.material.R.attr.colorPrimary),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                contentDescription = getString(R.string.reminder_cancel)
                val size = (40 * dp).toInt()
                val pad  = (8  * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { cancelReminder(r.alarmId) }
            }
            row.addView(label)
            row.addView(btnX)
            ll.addView(row)
        }
    }

    /** Cancel a scheduled reminder: remove from AlarmManager + ReminderStore. */
    private fun cancelReminder(alarmId: Int) {
        val pi = PendingIntent.getBroadcast(
            this, alarmId,
            Intent(this, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        ReminderStore.remove(this, alarmId)
        // ACTION_UPDATED broadcast from remove() will call renderReminders() automatically.
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
        // flush the text fields here too. All persist helpers no-op if unbound.
        persistNoteFile()
        persistMaxTasks()
        persistEveningHour()
        persistMorningHour()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener) }
        unregisterReceiver(messageReceiver)
    }

    // --- ViewPager2 / tabs ---

    private fun setupPager() {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = PagerAdapter { position, root ->
            when (position) {
                0 -> bindPreviewPage(root)
                1 -> bindSetupPage(root)
                else -> bindSyncPage(root)
            }
        }
        // 3 pages total → keep them all alive so swiping never loses in-progress
        // edits or the log view.
        viewPager.offscreenPageLimit = 2

        // Edge-to-edge: the CoordinatorLayout (fitsSystemWindows) insets the top
        // for the app bar; here we pad the pager's internal RecyclerView so page
        // content clears the navigation bar (and side cutouts in landscape).
        (viewPager.getChildAt(0) as? RecyclerView)?.let { inner ->
            ViewCompat.setOnApplyWindowInsetsListener(viewPager) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                inner.setPadding(bars.left, 0, bars.right, bars.bottom)
                insets
            }
        }

        // Pill segmented control: 3 checkable MaterialButtons that drive the pager,
        // plus a sliding indicator pill that tracks the swipe.
        val segRow = findViewById<LinearLayout>(R.id.segRow)
        val segIndicator = findViewById<View>(R.id.segIndicator)
        val segs = listOf<MaterialButton>(
            findViewById(R.id.segPreview),
            findViewById(R.id.segSetup),
            findViewById(R.id.segSync)
        )
        fun selectTab(pos: Int) { segs.forEachIndexed { i, b -> b.isChecked = i == pos } }

        var isProgrammaticClick = false

        // Size the indicator to one segment width once the row has been laid out.
        fun segWidth() = segRow.width / segs.size
        fun moveIndicator(position: Int, offset: Float) {
            val w = segWidth()
            if (w == 0) return
            if (segIndicator.width != w) {
                segIndicator.layoutParams = segIndicator.layoutParams.apply { width = w }
                segIndicator.requestLayout()
            }
            segIndicator.translationX = (position + offset) * w
        }
        segRow.post { moveIndicator(viewPager.currentItem, 0f) }

        segs.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (viewPager.currentItem != i) {
                    isProgrammaticClick = true
                    viewPager.setCurrentItem(i, true)
                    selectTab(i)

                    val targetX = i * segWidth().toFloat()
                    android.animation.ObjectAnimator.ofFloat(segIndicator, "translationX", segIndicator.translationX, targetX).apply {
                        duration = 350
                        interpolator = android.view.animation.OvershootInterpolator(1.4f)
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                isProgrammaticClick = false
                            }
                        })
                    }.start()
                }
            }
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = selectTab(position)
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (!isProgrammaticClick) {
                    moveIndicator(position, positionOffset)
                    // Toggle active tab checked state at the 50% threshold of transition
                    val closestPage = if (positionOffset < 0.5f) position else position + 1
                    selectTab(closestPage)
                }
            }
        })

        // Preview sits left of Setup, but Setup is the entry point — open there.
        viewPager.setCurrentItem(1, false)
        selectTab(1)
    }

    private fun bindPreviewPage(root: View) {
        txtPreview         = root.findViewById(R.id.txtPreview)
        txtSyncStatus      = root.findViewById(R.id.txtSyncStatus)
        llReminders        = root.findViewById(R.id.llReminders)
        txtRemindersHeader = root.findViewById(R.id.txtRemindersHeader)

        prefs.getString("taskPreview", null)?.let { updatePreview(it) }
        restoreSyncStatus()
        renderReminders()
    }

    private fun bindSetupPage(root: View) {
        txtVaultName  = root.findViewById(R.id.txtVaultName)
        txtTagsEmpty  = root.findViewById(R.id.txtTagsEmpty)
        cardVault     = root.findViewById(R.id.cardVault)
        rvTags        = root.findViewById(R.id.rvTags)
        edtNoteFile   = root.findViewById(R.id.edtNoteFile)

        // Keyboard-covers-content fix (issue #2): the page is a NestedScrollView, but
        // with edge-to-edge (targetSdk 36) the soft keyboard is not auto-avoided.
        // Pad the scroll content by the IME inset so the focused tag-rule field can
        // scroll above the keyboard. clipToPadding=false keeps the last row visible.
        (root as? androidx.core.widget.NestedScrollView)?.let { sv ->
            sv.clipToPadding = false
            val basePad = sv.paddingBottom
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + maxOf(ime, nav))
                insets
            }
        }

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
                Uri.parse(getString(R.string.rebble_store_url))))
        }
        root.findViewById<MaterialButton>(R.id.btnInstallWatch).setOnClickListener { installWatchApp() }
        checkForAppUpdate(root)
        root.findViewById<MaterialButton>(R.id.btnSaveVault).setOnClickListener {
            // Explicitly persist the vault + note-file settings and confirm. The
            // vault URI is already stored on folder-pick; this also flushes the
            // note-file field and gives clear "saved" feedback.
            edtNoteFile?.clearFocus()
            persistNoteFile()
            Toast.makeText(this, getString(R.string.vault_saved), Toast.LENGTH_SHORT).show()
        }

        val vaultUri = prefs.getString("vaultUri", null)
        when {
            vaultUri == null -> updateVaultEmptyState(false)
            vaultAccessible(Uri.parse(vaultUri)) -> updateVaultLabel(Uri.parse(vaultUri))
            // URI saved but the persisted permission/folder is gone (e.g. app
            // reinstalled, folder moved/removed) — flag it loudly so the user
            // re-picks the folder instead of seeing silent "vault not configured".
            else -> updateVaultEmptyState(false, lost = true)
        }
        updateTagsEmpty()
    }

    /** Ask GitHub if a newer release of this app exists; reveal the update card if so. */
    private fun checkForAppUpdate(root: View) {
        val cardUpdate = root.findViewById<View>(R.id.cardUpdate) ?: return
        val txtUpdateMsg = root.findViewById<TextView>(R.id.txtUpdateMsg)
        val btnUpdate = root.findViewById<MaterialButton>(R.id.btnUpdate)
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        if (current.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val result = UpdateChecker.checkForUpdate(current)
            withContext(Dispatchers.Main) {
                if (result != null) {
                    txtUpdateMsg?.text =
                        getString(R.string.update_available_msg, result.latestVersion, current)
                    btnUpdate?.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl)))
                    }
                    cardUpdate.visibility = View.VISIBLE
                } else {
                    cardUpdate.visibility = View.GONE
                }
            }
        }
    }

    private fun bindSyncPage(root: View) {
        txtLog              = root.findViewById(R.id.txtLog)
        scrollLog           = root.findViewById(R.id.scrollLog)
        spinnerSyncInterval = root.findViewById(R.id.spinnerSyncInterval)
        edtMaxTasks         = root.findViewById(R.id.edtMaxTasks)
        edtEveningHour      = root.findViewById(R.id.edtEveningHour)
        edtMorningHour      = root.findViewById(R.id.edtMorningHour)

        // Auto-open-Obsidian toggle. Set the state BEFORE attaching the listener
        // so the rebind doesn't refire it.
        val tilObsidianOpen =
            root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilObsidianOpen)
        switchShizuku = root.findViewById(R.id.switchShizuku)
        setupObsidianOpenDropdown(root)
        setupShizukuSwitch()
        root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoOpenObsidian)?.let { sw ->
            sw.isChecked = prefs.getBoolean("openObsidianBeforeSync", false)
            tilObsidianOpen?.isEnabled = sw.isChecked
            switchShizuku?.isEnabled = sw.isChecked
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("openObsidianBeforeSync", checked).apply()
                tilObsidianOpen?.isEnabled = checked
                switchShizuku?.isEnabled = checked
                if (checked && !android.provider.Settings.canDrawOverlays(this)) {
                    // Explain WHY before bouncing the user to a scary system screen.
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.perm_overlay_title)
                        .setMessage(R.string.perm_overlay_msg)
                        .setPositiveButton(R.string.perm_overlay_grant) { _, _ ->
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                        .setNegativeButton(R.string.perm_overlay_later) { _, _ ->
                            // Feature can't work without the grant — turn it back off
                            // (fires this listener again with checked=false, harmless).
                            sw.isChecked = false
                        }
                        .show()
                }
            }
        }

        edtMaxTasks?.setText(prefs.getInt("maxTasks", 20).toString())
        edtEveningHour?.setText(prefs.getInt("eveningHour", 20).toString())
        edtMorningHour?.setText(prefs.getInt("morningHour", 9).toString())
        // Auto-save values when fields lose focus.
        edtMaxTasks?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistMaxTasks() }
        edtEveningHour?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistEveningHour() }
        edtMorningHour?.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistMorningHour() }
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
        root.findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_url))))
        }
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

    /** Auto-persist the evening reminder hour (called when the field loses focus). */
    private fun persistEveningHour() {
        val h = edtEveningHour?.text?.toString()?.toIntOrNull()?.coerceIn(0, 23) ?: return
        prefs.edit().putInt("eveningHour", h).apply()
    }

    /** Auto-persist the morning reminder hour (called when the field loses focus). */
    private fun persistMorningHour() {
        val h = edtMorningHour?.text?.toString()?.toIntOrNull()?.coerceIn(0, 23) ?: return
        prefs.edit().putInt("morningHour", h).apply()
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
        val empty = tagAdapter.itemCount == 0
        txtTagsEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) setEmptyIllustration(txtTagsEmpty, R.drawable.ic_empty_tasks)
    }

    /** Show a muted illustration above an empty-state TextView (centered). */
    private fun setEmptyIllustration(tv: TextView?, iconRes: Int) {
        tv ?: return
        tv.gravity = android.view.Gravity.CENTER_HORIZONTAL
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, iconRes, 0, 0)
        tv.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        TextViewCompat.setCompoundDrawableTintList(
            tv,
            ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOutline))
        )
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
        lifecycleScope.launch(Dispatchers.IO) {
            val found = try { TagScanner.scan(this@MainActivity, vaultUri) } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
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
        }
    }

    /** How often the auto-open may bring Obsidian to the foreground — deliberately
     *  decoupled from the watch-refresh interval, which is invisible and cheap. */
    private val obsidianOpenHours = listOf(1, 4, 6, 8, 12, 24)

    private fun setupObsidianOpenDropdown(root: View) {
        val spinner = root.findViewById<AutoCompleteTextView>(R.id.spinnerObsidianOpen) ?: return
        val labels = listOf(
            getString(R.string.open_1h), getString(R.string.open_4h), getString(R.string.open_6h),
            getString(R.string.open_8h), getString(R.string.open_12h), getString(R.string.open_24h)
        )
        spinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        val saved = prefs.getInt("obsidianOpenIntervalHours", 6)
        val idx   = obsidianOpenHours.indexOf(saved).let { if (it < 0) 2 else it }
        spinner.setText(labels[idx], false)
        spinner.setOnItemClickListener { _, _, pos, _ ->
            prefs.edit().putInt("obsidianOpenIntervalHours", obsidianOpenHours[pos]).apply()
        }
    }

    /** Invisible-mode (Shizuku) toggle. Set state before wiring the listener so the
     *  page rebind doesn't refire it. Enabling requires Shizuku running + granted. */
    private fun setupShizukuSwitch() {
        val sw = switchShizuku ?: return
        sw.isChecked = prefs.getBoolean("useShizuku", false)
        sw.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                prefs.edit().putBoolean("useShizuku", false).apply()
                return@setOnCheckedChangeListener
            }
            when {
                !ShizukuLauncher.isRunning() -> {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.shizuku_title)
                        .setMessage(R.string.shizuku_not_found)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    sw.isChecked = false
                }
                ShizukuLauncher.hasPermission() ->
                    prefs.edit().putBoolean("useShizuku", true).apply()
                else -> {
                    // Async: shizukuPermListener applies the result to pref + switch.
                    try {
                        rikka.shizuku.Shizuku.requestPermission(shizukuReqCode)
                    } catch (t: Throwable) {
                        Toast.makeText(this, getString(R.string.shizuku_error), Toast.LENGTH_LONG).show()
                        sw.isChecked = false
                    }
                }
            }
        }
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
     *  so the required first step is obvious. `lost` distinguishes "never set"
     *  from "was set but access is gone". */
    private fun updateVaultEmptyState(hasVault: Boolean, lost: Boolean = false) {
        if (hasVault) {
            txtVaultName?.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            cardVault?.strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
        } else {
            txtVaultName?.text = getString(if (lost) R.string.vault_lost else R.string.vault_none)
            txtVaultName?.setTextColor(themeColor(com.google.android.material.R.attr.colorError))
            cardVault?.strokeColor = themeColor(com.google.android.material.R.attr.colorError)
        }
    }

    /** True if we still hold a readable persisted permission for the vault folder. */
    private fun vaultAccessible(uri: Uri): Boolean {
        val held = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!held) return false
        return try {
            DocumentFile.fromTreeUri(this, uri)?.canRead() == true
        } catch (e: Exception) {
            false
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
        runOnUiThread {
            val tv = txtPreview ?: return@runOnUiThread
            if (text.isBlank()) {
                tv.text = getString(R.string.preview_empty)
                setEmptyIllustration(tv, R.drawable.ic_empty_tasks)
            } else {
                tv.text = text
                tv.gravity = android.view.Gravity.START
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
        }
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
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Request exact alarm permission on Android 12+ (API 31+) if missing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback
                }
            }
        }
    }
}

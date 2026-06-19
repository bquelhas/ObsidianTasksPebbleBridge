package com.example.obsidiantaskspebblebridge

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import java.util.Collections

/**
 * Editable, reorderable list of tag → group-title rows for the Setup tab.
 *
 * Each row owns a [TagRule]; the EditText watchers write straight back into the
 * backing model so [rules] is always current when MainActivity serializes it.
 * Drag-to-reorder is driven by [touchHelper]; row order == priority order.
 */
class TagRuleAdapter(
    val rules: MutableList<TagRule>
) : RecyclerView.Adapter<TagRuleAdapter.VH>() {

    data class TagRule(var tag: String, var title: String)

    /** Invoked whenever the rules change (edit, add, delete, reorder) so the host
     *  can auto-persist them — no explicit Save button needed. */
    var onRulesChanged: (() -> Unit)? = null

    private var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    val touchHelper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            Collections.swap(rules, from, to)
            notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

        // Persist once when a drag-reorder settles (not on every intermediate move).
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            onRulesChanged?.invoke()
        }
    })

    init {
        onStartDrag = { vh -> touchHelper.startDrag(vh) }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tilTag: TextInputLayout = view.findViewById(R.id.tilTag)
        val edtTag: EditText = view.findViewById(R.id.edtTag)
        val edtTitle: EditText = view.findViewById(R.id.edtTitle)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteTag)

        var tagWatcher: TextWatcher? = null
        var titleWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_rule, parent, false)
        return VH(v)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val rule = rules[holder.bindingAdapterPosition]

        // Detach old watchers before resetting text (recycled views).
        holder.tagWatcher?.let { holder.edtTag.removeTextChangedListener(it) }
        holder.titleWatcher?.let { holder.edtTitle.removeTextChangedListener(it) }

        holder.edtTag.setText(rule.tag)
        holder.edtTitle.setText(rule.title)
        applyTagValidation(holder.tilTag, rule.tag)

        holder.tagWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return
                val text = s?.toString() ?: ""
                rules[p].tag = text
                applyTagValidation(holder.tilTag, text)
                onRulesChanged?.invoke()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        holder.titleWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return
                rules[p].title = s?.toString() ?: ""
                onRulesChanged?.invoke()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        holder.edtTag.addTextChangedListener(holder.tagWatcher)
        holder.edtTitle.addTextChangedListener(holder.titleWatcher)

        // Auto-normalize: when the tag field loses focus, prepend a missing '#'.
        holder.edtTag.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
            val raw = holder.edtTag.text?.toString()?.trim() ?: ""
            if (raw.isNotEmpty() && !raw.startsWith("#")) {
                holder.edtTag.setText("#$raw")  // watcher writes back + re-validates
            }
        }

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag?.invoke(holder)
            false
        }

        holder.btnDelete.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                rules.removeAt(p)
                notifyItemRemoved(p)
                onRulesChanged?.invoke()
            }
        }
    }

    /** A tag is valid only if it is non-blank and starts with '#'. Invalid tags
     *  get a red border so the problem is obvious at a glance. */
    private fun applyTagValidation(til: TextInputLayout, tag: String) {
        val t = tag.trim()
        val valid = t.isNotEmpty() && t.startsWith("#")
        til.error = if (valid) null else " "
    }

    override fun getItemCount() = rules.size

    fun addRow(tag: String = "", title: String = "") {
        rules.add(TagRule(tag, title))
        notifyItemInserted(rules.size - 1)
        onRulesChanged?.invoke()
    }

    /** Replace contents, preserving any rows the user already edited (match by tag). */
    @SuppressLint("NotifyDataSetChanged")
    fun replaceAll(newRules: List<TagRule>) {
        rules.clear()
        rules.addAll(newRules)
        notifyDataSetChanged()
        onRulesChanged?.invoke()
    }
}

package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Discovers tag → group-title pairs from the Obsidian vault so the Setup tab can
 * pre-fill the tag editor instead of forcing the user to type rules by hand.
 *
 * Two sources, merged in this order:
 *  1. An Obsidian Tasks `group by function` block (the authoritative source — it
 *     carries both the title AND the intended order). We parse lines shaped like
 *        if (task.tags.includes('#DEN')) return '02_DENÚNCIAS';
 *  2. Every other tag that appears in an actual task line ("- [ ] … #FOO"),
 *     appended with a blank title for the user to fill in.
 *
 * Returns ordered, de-duplicated (case-insensitive) pairs. Pure read-only IO.
 */
object TagScanner {

    data class TagPair(val tag: String, val title: String)

    // if (task.tags.includes('#XXX')) return 'TITLE';
    private val GROUP_FN_REGEX =
        Regex("""task\.tags\.includes\(\s*['"](#[^'"]+)['"]\s*\)\s*\)\s*return\s*['"]([^'"]*)['"]""")

    // A hashtag inside task text: '#' followed by a letter/digit, then tag chars.
    private val TASK_TAG_REGEX =
        Regex("""#[\p{L}\p{N}][\p{L}\p{N}_/-]*""")

    fun scan(context: Context, vaultUri: String): List<TagPair> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(vaultUri)) ?: return emptyList()

        val ordered = LinkedHashMap<String, String>()  // lowercase tag -> TagPair via index
        val display = LinkedHashMap<String, TagPair>()  // lowercase tag -> pair (keeps casing/title)

        // 1) Collect group-by-function pairs first (authoritative order + titles).
        val fnPairs = mutableListOf<TagPair>()
        // 2) Collect bare task tags second (blank titles, only if not already known).
        val taskTags = LinkedHashSet<String>()

        walk(context, root) { text ->
            GROUP_FN_REGEX.findAll(text).forEach { m ->
                fnPairs.add(TagPair(m.groupValues[1].trim(), m.groupValues[2].trim()))
            }
            text.lineSequence().forEach { line ->
                val t = line.trimStart()
                if (t.startsWith("- [ ]") || t.startsWith("- [x]") || t.startsWith("- [X]")) {
                    TASK_TAG_REGEX.findAll(t).forEach { taskTags.add(it.value) }
                }
            }
        }

        for (p in fnPairs) {
            val key = p.tag.lowercase()
            if (!display.containsKey(key)) display[key] = p
        }
        for (tag in taskTags) {
            val key = tag.lowercase()
            if (!display.containsKey(key)) display[key] = TagPair(tag, "")
        }

        return display.values.toList()
    }

    private fun walk(context: Context, dir: DocumentFile, onMd: (String) -> Unit) {
        dir.listFiles().forEach { file ->
            when {
                file.isDirectory -> {
                    val name = file.name ?: ""
                    if (!name.startsWith(".")) walk(context, file, onMd)
                }
                file.name?.endsWith(".md") == true -> {
                    try {
                        context.contentResolver.openInputStream(file.uri)
                            ?.bufferedReader(Charsets.UTF_8)?.use { onMd(it.readText()) }
                    } catch (_: Exception) { /* skip unreadable file */ }
                }
            }
        }
    }
}

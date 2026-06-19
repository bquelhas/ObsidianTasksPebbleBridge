package com.example.obsidiantaskspebblebridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Discovers tag → group-title pairs from the Obsidian vault so the Setup tab can
 * pre-fill the tag editor instead of forcing the user to type rules by hand.
 *
 * Two sources, merged in this order:
 *  1. An Obsidian Tasks `group by function` block (the authoritative source — it
 *     carries both the title AND the intended order). We parse lines shaped like
 *        if (task.tags.includes('#work')) return '01_Work';
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
        val treeUri = Uri.parse(vaultUri)
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return emptyList()
        }

        val display = LinkedHashMap<String, TagPair>()
        val fnPairs = mutableListOf<TagPair>()
        val taskTags = LinkedHashSet<String>()

        walk(context, treeUri, rootDocId) { text ->
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

    private fun walk(context: Context, treeUri: Uri, dirDocId: String, onMd: (String) -> Unit) {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        } catch (e: Exception) {
            return
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol)
                    val childName = cursor.getString(nameCol) ?: ""
                    val childMime = cursor.getString(mimeCol) ?: ""

                    if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!childName.startsWith(".")) {
                            walk(context, treeUri, childId, onMd)
                        }
                    } else if (childName.endsWith(".md")) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        try {
                            context.contentResolver.openInputStream(fileUri)
                                ?.bufferedReader(Charsets.UTF_8)?.use { onMd(it.readText()) }
                        } catch (_: Exception) { /* skip unreadable file */ }
                    }
                }
            }
        } catch (_: Exception) {
            // Query failed or permission revoked, skip directory
        }
    }
}

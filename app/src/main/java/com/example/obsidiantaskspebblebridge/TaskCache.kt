package com.example.obsidiantaskspebblebridge

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * In-memory cache of .md file contents keyed by URI string.
 * Each entry stores the file's last-modified timestamp alongside its lines;
 * a file is only re-read when its modification time changes.
 *
 * The cache is memory-only — it rebuilds transparently after a cold start
 * (the first sync reads everything fresh, which is fine).
 */
object TaskCache {

    private data class Entry(val lastModified: Long, val lines: List<String>)

    private val cache = HashMap<String, Entry>()

    /**
     * Return the lines of [uriString], using the cached copy when the file's
     * last-modified timestamp has not changed.  [readFn] is called only on
     * a cache miss and must return the fresh line list.
     */
    @Synchronized
    fun getLines(uriString: String, modified: Long, readFn: () -> List<String>): List<String> {
        val cached   = cache[uriString]
        // Use cached copy only when the timestamp matches AND is non-zero
        // (SAF sometimes returns 0 for files on certain providers).
        if (cached != null && modified != 0L && cached.lastModified == modified) {
            return cached.lines
        }
        val lines = readFn()
        if (modified != 0L) cache[uriString] = Entry(modified, lines)
        return lines
    }

    @Synchronized
    fun getLines(file: DocumentFile, readFn: () -> List<String>): List<String> {
        return getLines(file.uri.toString(), file.lastModified(), readFn)
    }

    /** Invalidate a single file (e.g. after marking a task done or saving a note). */
    @Synchronized
    fun invalidate(fileUri: Uri) {
        cache.remove(fileUri.toString())
    }

    /** Clear the entire cache (e.g. when the vault folder changes). */
    @Synchronized
    fun clear() = cache.clear()
}

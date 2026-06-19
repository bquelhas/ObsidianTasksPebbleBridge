package com.example.obsidiantaskspebblebridge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub "latest release" of this app and reports whether it is newer
 * than the installed build. All network work runs on the caller's (background) thread.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/bquelhas/ObsidianTasksPebbleBridge/releases/latest"

    data class Result(val latestVersion: String, val releaseUrl: String)

    /** Returns release info when the latest GitHub release is newer than
     *  [currentVersion], or null if up to date or unreachable. Call off the main thread. */
    fun checkForUpdate(currentVersion: String): Result? {
        return try {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ObsidianTasksPebbleBridge")
            }
            conn.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val tag = json.optString("tag_name").trim()
                val url = json.optString("html_url").trim()
                if (tag.isEmpty()) return null
                if (isNewer(tag, currentVersion)) {
                    Result(tag.removePrefix("v").removePrefix("V"), url)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote)
        val l = parse(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split(".")
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}

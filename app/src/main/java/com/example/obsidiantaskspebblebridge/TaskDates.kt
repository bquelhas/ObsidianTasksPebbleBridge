package com.example.obsidiantaskspebblebridge

/**
 * Pure, Android-free helpers for Obsidian Tasks date markers.
 *
 *  - 📅 due date      (a real deadline)
 *  - ⏳ scheduled date (when you plan to act — used for watch-pin reminders)
 *
 * Kept dependency-free so it can be covered by fast JVM unit tests.
 */
object TaskDates {
    private val DUE = Regex("""📅\s*\d{4}-\d{2}-\d{2}""")
    private val SCHED = Regex("""⏳\s*\d{4}-\d{2}-\d{2}""")
    private val DUE_EXTRACT =
        Regex("""📅\s*(\d{4}-\d{2}-\d{2})|@due\((\d{4}-\d{2}-\d{2})\)|due::(\d{4}-\d{2}-\d{2})""")

    /** Set/replace the 📅 due date on a task line. Existing one is updated; else appended. */
    fun setDueDateOnLine(line: String, dueStr: String): String =
        if (DUE.containsMatchIn(line)) DUE.replace(line, "📅 $dueStr")
        else line.trimEnd() + " 📅 $dueStr"

    /** Set/replace the ⏳ scheduled date on a task line, leaving any 📅 due date intact. */
    fun setScheduledDateOnLine(line: String, dateStr: String): String =
        if (SCHED.containsMatchIn(line)) SCHED.replace(line, "⏳ $dateStr")
        else line.trimEnd() + " ⏳ $dateStr"

    /** Extract the first due date (yyyy-MM-dd) from a task line, supporting
     *  📅, @due(...) and due::... formats. Returns null when none present. */
    fun extractDueDateIso(text: String): String? {
        val m = DUE_EXTRACT.find(text) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }
}

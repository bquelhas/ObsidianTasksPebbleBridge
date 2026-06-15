package com.example.obsidiantaskspebblebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDatesTest {

    // --- setDueDateOnLine ---

    @Test
    fun due_appendedWhenAbsent() {
        assertEquals(
            "- [ ] Buy milk 📅 2026-06-20",
            TaskDates.setDueDateOnLine("- [ ] Buy milk", "2026-06-20")
        )
    }

    @Test
    fun due_replacedWhenPresent() {
        assertEquals(
            "- [ ] Buy milk 📅 2026-06-20",
            TaskDates.setDueDateOnLine("- [ ] Buy milk 📅 2026-01-01", "2026-06-20")
        )
    }

    @Test
    fun due_trimsTrailingWhitespaceBeforeAppending() {
        assertEquals(
            "- [ ] Task 📅 2026-06-20",
            TaskDates.setDueDateOnLine("- [ ] Task   ", "2026-06-20")
        )
    }

    @Test
    fun due_doesNotTouchScheduledMarker() {
        assertEquals(
            "- [ ] Task ⏳ 2026-05-01 📅 2026-06-20",
            TaskDates.setDueDateOnLine("- [ ] Task ⏳ 2026-05-01", "2026-06-20")
        )
    }

    // --- setScheduledDateOnLine ---

    @Test
    fun scheduled_appendedWhenAbsent() {
        assertEquals(
            "- [ ] Task ⏳ 2026-06-18",
            TaskDates.setScheduledDateOnLine("- [ ] Task", "2026-06-18")
        )
    }

    @Test
    fun scheduled_replacedWhenPresent() {
        assertEquals(
            "- [ ] Task ⏳ 2026-06-18",
            TaskDates.setScheduledDateOnLine("- [ ] Task ⏳ 2026-01-01", "2026-06-18")
        )
    }

    @Test
    fun scheduled_leavesDueDateIntact() {
        // Setting a scheduled date must never destroy a real deadline (📅).
        assertEquals(
            "- [ ] Task 📅 2026-12-31 ⏳ 2026-06-18",
            TaskDates.setScheduledDateOnLine("- [ ] Task 📅 2026-12-31", "2026-06-18")
        )
    }

    // --- extractDueDateIso ---

    @Test
    fun extract_emojiFormat() {
        assertEquals("2026-06-20", TaskDates.extractDueDateIso("- [ ] Task 📅 2026-06-20"))
    }

    @Test
    fun extract_atDueFormat() {
        assertEquals("2026-06-20", TaskDates.extractDueDateIso("- [ ] Task @due(2026-06-20)"))
    }

    @Test
    fun extract_dueColonFormat() {
        assertEquals("2026-06-20", TaskDates.extractDueDateIso("- [ ] Task due::2026-06-20"))
    }

    @Test
    fun extract_nullWhenNoDate() {
        assertNull(TaskDates.extractDueDateIso("- [ ] Task with no date"))
    }

    @Test
    fun extract_ignoresScheduledOnlyLine() {
        // ⏳ is a scheduled date, not a due date — must not be picked up.
        assertNull(TaskDates.extractDueDateIso("- [ ] Task ⏳ 2026-06-20"))
    }
}

package dev.androidcad.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoHistoryTest {
    private var now = 0L
    private fun history(max: Int = 200) = UndoHistory("a", maxSize = max, coalesceWindowMs = 500, clock = { now })

    @Test
    fun startsWithNothingToUndoOrRedo() {
        val h = history()
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertEquals("a", h.current)
        assertNull(h.undo()); assertNull(h.redo())
    }

    @Test
    fun undoAndRedoWalkTheHistory() {
        val h = history()
        h.record("b"); h.record("c")
        assertEquals("b", h.undo()); assertEquals("a", h.undo()); assertNull(h.undo())
        assertEquals("b", h.redo()); assertEquals("c", h.redo()); assertNull(h.redo())
        assertEquals("c", h.current)
    }

    @Test
    fun newChangeAfterUndoDropsRedoBranch() {
        val h = history()
        h.record("b"); h.record("c")
        h.undo()
        h.record("x")
        assertFalse(h.canRedo)
        assertEquals("b", h.undo())
        assertEquals("x", h.redo())
    }

    @Test
    fun sameKeyWithinWindowIsOneStep() {
        val h = history()
        for ((i, s) in listOf("ab", "abc", "abcd").withIndex()) { now = i * 100L; h.record(s, "text") }
        assertEquals("abcd", h.current)
        assertEquals("a", h.undo()) // alle drei Tastenanschläge = ein Schritt
        assertFalse(h.canUndo)
    }

    @Test
    fun pauseOrDifferentKeyStartsNewStep() {
        val h = history()
        now = 0; h.record("b", "text")
        now = 1000; h.record("c", "text")            // Pause > Fenster
        now = 1100; h.record("d", "other")           // anderer Schlüssel
        assertEquals("c", h.undo()); assertEquals("b", h.undo()); assertEquals("a", h.undo())
    }

    @Test
    fun noKeyNeverCoalesces() {
        val h = history()
        h.record("b"); h.record("c")
        assertEquals("b", h.undo())
    }

    @Test
    fun undoEndsCoalescing() {
        val h = history()
        now = 0; h.record("b", "text")
        h.undo()
        now = 10; h.record("c", "text") // gleicher Schlüssel, aber nach Undo → neuer Schritt, Basis "a" bleibt
        assertEquals("a", h.undo())
        assertEquals("c", h.redo())
    }

    @Test
    fun breakCoalescingSplitsSteps() {
        val h = history()
        now = 0; h.record("b", "text")
        h.breakCoalescing()
        now = 10; h.record("c", "text")
        assertEquals("b", h.undo())
    }

    @Test
    fun oldestStepsAreDroppedAtCapacity() {
        val h = history(max = 3)
        h.record("b"); h.record("c"); h.record("d")
        assertEquals("c", h.undo()); assertEquals("b", h.undo())
        assertNull(h.undo()) // "a" wurde verdrängt
        assertEquals("c", h.redo())
    }

    @Test
    fun tinyCapacityIsRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { UndoHistory("a", maxSize = 1) }
        assertTrue(true)
    }
}

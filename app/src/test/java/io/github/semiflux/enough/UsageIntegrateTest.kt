package io.github.semiflux.enough

import io.github.semiflux.enough.data.UsageRepository.ForegroundMark
import io.github.semiflux.enough.data.UsageRepository.Companion.integrate
import io.github.semiflux.enough.data.UsageRepository.Companion.queryStartForDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class UsageIntegrateTest {

    private fun on(pkg: String, t: Long, slot: String = pkg) = ForegroundMark(pkg, t, true, slot)
    private fun off(pkg: String, t: Long, slot: String = pkg) = ForegroundMark(pkg, t, false, slot)

    @Test
    fun simpleSession() {
        val out = integrate(listOf(on("a", 100), off("a", 200)), 0, 1000)
        assertEquals(100L, out["a"])
    }

    @Test
    fun clipsToRange() {
        // Session starts before the range and ends after it.
        val out = integrate(listOf(on("a", -50), off("a", 1200)), 0, 1000)
        assertEquals(1000L, out["a"])
    }

    @Test
    fun openSessionCountsToRangeEnd() {
        val out = integrate(listOf(on("a", 900)), 0, 1000)
        assertEquals(100L, out["a"])
    }

    @Test
    fun dailyUsageQueryIncludesPreviousDayToRecoverOpenSession() {
        val day = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 21, 0, 0, 0)
        }.timeInMillis
        val now = day + 60 * 60 * 1000
        val expectedQueryStart = Calendar.getInstance().run {
            timeInMillis = day
            add(Calendar.DAY_OF_YEAR, -1)
            timeInMillis
        }
        val queryStart = queryStartForDay(day)
        assertEquals(expectedQueryStart, queryStart)

        // Session began at 23:30 on the previous day and is still open now.
        val sessionStart = day - 30 * 60 * 1000
        assertTrue(queryStart <= sessionStart)
        val out = integrate(listOf(on("a", sessionStart)), day, now)
        assertEquals(60 * 60 * 1000L, out["a"])
    }

    @Test
    fun sessionStillOpenFromBeforeRangeCountsFromRangeStart() {
        // No transition occurs after midnight yet, but the app remains in
        // the foreground. The pre-midnight resume must establish the open session.
        val out = integrate(listOf(on("a", -100)), 0, 1000)
        assertEquals(1000L, out["a"])
    }

    @Test
    fun pauseWithoutResumeCountsFromRangeStart() {
        // E.g. session started before midnight, paused after.
        val out = integrate(listOf(off("a", 300)), 0, 1000)
        assertEquals(300L, out["a"])
    }

    @Test
    fun multipleSessionsSum() {
        val out = integrate(
            listOf(on("a", 100), off("a", 200), on("a", 400), off("a", 450)),
            0, 1000
        )
        assertEquals(150L, out["a"])
    }

    @Test
    fun packagesAreIndependent() {
        val out = integrate(listOf(on("a", 0), on("b", 100), off("a", 200), off("b", 300)), 0, 1000)
        assertEquals(200L, out["a"])
        assertEquals(200L, out["b"])
    }

    @Test
    fun emptyInEmptyOut() {
        assertEquals(emptyMap<String, Long>(), integrate(emptyList(), 0, 1000))
    }

    @Test
    fun adversarialTimestampsNeverExceedRange() {
        // Garbage: far-past resume, far-future pause, reversed pair,
        // zero timestamps, duplicates. Total must fit in the range.
        val out = integrate(
            listOf(
                on("a", -9_999_999_999_999L),
                off("a", 9_999_999_999_999L),
                off("a", 500),
                on("a", 700),
                on("a", 0),
                off("a", 0),
                on("a", 100),
                on("a", 150),
                off("a", 200)
            ),
            0, 1000
        )
        val total = out["a"] ?: 0L
        assert(total in 0..1000) { "total=$total" }
    }

    @Test
    fun duplicatePausesCountOnce() {
        // Instagram-style: several stops/teardowns with no in-day resume.
        // Only the first may claim the overnight credit.
        val out = integrate(
            listOf(off("a", 300), off("a", 600), off("a", 900)),
            0, 1000
        )
        assertEquals(300L, out["a"])
    }

    @Test
    fun pauseAfterCountedSessionIsIgnored() {
        val out = integrate(
            listOf(on("a", 100), off("a", 200), off("a", 800)),
            0, 1000
        )
        assertEquals(100L, out["a"])
    }

    @Test
    fun overnightCreditPlusNormalSession() {
        val out = integrate(
            listOf(off("a", 100), on("a", 400), off("a", 500)),
            0, 1000
        )
        assertEquals(200L, out["a"])
    }

    @Test
    fun concurrentSameAppActivitiesCountWallClockOnce() {
        // PiP / multi-window: two activities of one app overlap.
        val out = integrate(
            listOf(
                on("a", 100, "A1"), on("a", 150, "A2"),
                off("a", 300, "A1"), off("a", 400, "A2")
            ),
            0, 1000
        )
        assertEquals(300L, out["a"])
    }

    @Test
    fun trailingActivitySegmentIsNotDropped() {
        // A1 pauses after A2 resumed; package-keyed pairing used to
        // drop A2's tail (second resume discarded, its pause orphaned).
        val out = integrate(
            listOf(
                on("a", 100, "A1"), on("a", 200, "A2"),
                off("a", 250, "A1"), off("a", 400, "A2")
            ),
            0, 1000
        )
        assertEquals(300L, out["a"])
    }

    @Test
    fun orphanCreditsAcrossActivitiesStayBounded() {
        // One overnight credit per activity; the union still caps
        // the package at wall-clock instead of summing credits.
        val out = integrate(
            listOf(off("a", 300, "A1"), off("a", 600, "A2")),
            0, 1000
        )
        assertEquals(600L, out["a"])
    }

    @Test
    fun openSessionsPerActivityExtendToRangeEnd() {
        val out = integrate(
            listOf(on("a", 800, "A1"), on("a", 900, "A2")),
            0, 1000
        )
        assertEquals(200L, out["a"])
    }
}

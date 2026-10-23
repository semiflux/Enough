package io.github.semiflux.enough

import io.github.semiflux.enough.blocking.BlockEvaluator
import io.github.semiflux.enough.blocking.BlockReason
import io.github.semiflux.enough.data.AppRule
import io.github.semiflux.enough.data.BlockWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class BlockEvaluatorTest {

    private fun mondayAt(hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 9, 21, hour, minute, 0, 0, ZoneId.systemDefault()) // a Monday

    private fun saturdayAt(hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 9, 26, hour, minute, 0, 0, ZoneId.systemDefault()) // a Saturday

    @Test
    fun windowRespectsDays() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = null,
            windows = listOf(
                BlockWindow(startMinute = 9 * 60, endMinute = 17 * 60, days = setOf(1, 2, 3, 4, 5))
            )
        )
        val monday = BlockEvaluator.evaluate(rule, 0L, mondayAt(12))
        assertTrue(monday is BlockReason.Schedule)
        assertNull(BlockEvaluator.evaluate(rule, 0L, saturdayAt(12)))
    }

    @Test
    fun quotaRespectsLimitDays() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = 15,
            limitDays = setOf(1, 2, 3, 4, 5)
        )
        val monday = BlockEvaluator.evaluate(rule, 15 * 60_000L, mondayAt(20))
        assertTrue(monday is BlockReason.Quota)
        assertNull(BlockEvaluator.evaluate(rule, 15 * 60_000L, saturdayAt(20)))
    }

    @Test
    fun disabledQuotaNeverBlocks() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = 15,
            dailyLimitEnabled = false
        )
        assertNull(BlockEvaluator.evaluate(rule, 60 * 60_000L, mondayAt(20)))
    }

    @Test
    fun scheduleBlocksInsideWindow() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = null,
            windows = listOf(BlockWindow(startMinute = 9 * 60, endMinute = 17 * 60))
        )
        val reason = BlockEvaluator.evaluate(rule, 0L, mondayAt(12))
        assertTrue(reason is BlockReason.Schedule)
    }

    @Test
    fun scheduleAllowsOutsideWindow() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = null,
            windows = listOf(BlockWindow(startMinute = 9 * 60, endMinute = 17 * 60))
        )
        assertNull(BlockEvaluator.evaluate(rule, 0L, mondayAt(18)))
    }

    @Test
    fun overnightWindowWraps() {
        val window = BlockWindow(startMinute = 22 * 60, endMinute = 6 * 60)
        assertTrue(BlockEvaluator.isInWindow(LocalTime.of(23, 0), DayOfWeek.MONDAY, window))
        assertTrue(BlockEvaluator.isInWindow(LocalTime.of(5, 0), DayOfWeek.MONDAY, window))
        assertTrue(!BlockEvaluator.isInWindow(LocalTime.of(12, 0), DayOfWeek.MONDAY, window))
    }

    @Test
    fun overnightWindowUsesItsStartDayForAfterMidnightPortion() {
        val mondayOnly = BlockWindow(
            startMinute = 22 * 60,
            endMinute = 6 * 60,
            days = setOf(DayOfWeek.MONDAY.value)
        )
        assertTrue(BlockEvaluator.isInWindow(LocalTime.of(23, 0), DayOfWeek.MONDAY, mondayOnly))
        assertTrue(BlockEvaluator.isInWindow(LocalTime.of(5, 0), DayOfWeek.TUESDAY, mondayOnly))
        assertTrue(!BlockEvaluator.isInWindow(LocalTime.of(5, 0), DayOfWeek.MONDAY, mondayOnly))
        assertTrue(!BlockEvaluator.isInWindow(LocalTime.of(5, 0), DayOfWeek.WEDNESDAY, mondayOnly))
    }

    @Test
    fun quotaBlocksAfterLimit() {
        val rule = AppRule(packageName = "com.example", dailyLimitMinutes = 15)
        assertNull(BlockEvaluator.evaluate(rule, 14 * 60_000L, mondayAt(20)))
        assertTrue(BlockEvaluator.evaluate(rule, 15 * 60_000L, mondayAt(20)) is BlockReason.Quota)
    }

    @Test
    fun scheduleWinsOverQuota() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = 15,
            windows = listOf(BlockWindow(startMinute = 9 * 60, endMinute = 17 * 60))
        )
        val reason = BlockEvaluator.evaluate(rule, 60 * 60_000L, mondayAt(10))
        assertTrue(reason is BlockReason.Schedule)
    }

    @Test
    fun disabledWindowDoesNotBlock() {
        val rule = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = null,
            windows = listOf(BlockWindow(startMinute = 9 * 60, endMinute = 17 * 60, enabled = false))
        )
        assertNull(BlockEvaluator.evaluate(rule, 0L, mondayAt(12)))
    }

    @Test
    fun disabledNeverBlocks() {
        val base = AppRule(
            packageName = "com.example",
            dailyLimitMinutes = 1,
            windows = listOf(BlockWindow(startMinute = 0, endMinute = 1439))
        )
        val now = mondayAt(10)
        assertNull(BlockEvaluator.evaluate(base.copy(enabled = false), 60_000L, now))
    }

    @Test
    fun warnWindow() {
        // 15-min limit: warn zone is [10:00, 15:00) of usage.
        assertTrue(!BlockEvaluator.shouldWarnQuota(15, 9 * 60_000L))
        assertTrue(BlockEvaluator.shouldWarnQuota(15, 10 * 60_000L))
        assertTrue(BlockEvaluator.shouldWarnQuota(15, 14 * 60_000L + 59_000L))
        // Exceeded is the block screen's job, not the warning's.
        assertTrue(!BlockEvaluator.shouldWarnQuota(15, 15 * 60_000L))
        assertTrue(!BlockEvaluator.shouldWarnQuota(15, 60 * 60_000L))
    }

    @Test
    fun warnNeedsRealQuota() {
        assertTrue(!BlockEvaluator.shouldWarnQuota(null, 60 * 60_000L))
        // Tiny limits would warn the instant they're opened.
        assertTrue(!BlockEvaluator.shouldWarnQuota(5, 0L))
        assertTrue(!BlockEvaluator.shouldWarnQuota(1, 0L))
        assertTrue(BlockEvaluator.shouldWarnQuota(6, 60_000L))
    }

    @Test
    fun remainingNeverGoesNegative() {
        assertEquals(5 * 60_000L, BlockEvaluator.remainingMillis(10 * 60_000L, 15)!!)
        assertEquals(0L, BlockEvaluator.remainingMillis(20 * 60_000L, 15)!!)
    }
}

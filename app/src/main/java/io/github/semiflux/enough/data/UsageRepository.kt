package io.github.semiflux.enough.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Reads real screen-time from the system UsageStatsManager. */
class UsageRepository(private val context: Context) {

    /**
     * One foreground transition, in chronological order.
     * [resumed] is true for ACTIVITY_RESUMED / MOVE_TO_FOREGROUND,
     * false for ACTIVITY_PAUSED / ACTIVITY_STOPPED / MOVE_TO_BACKGROUND.
     * [slot] pairs resumes with pauses: the activity class when known,
     * else the package (ScreenTime pairs the same way). Pairing per
     * activity instead of per package keeps concurrent same-app
     * activities (PiP, multi-window, rapid transitions with a lost
     * pause) from collapsing into one slot and dropping trailing
     * segments. Attribution stays per [packageName]; overlapping
     * intervals are unioned per package at the end, so concurrency
     * counts wall-clock once, never twice.
     */
    data class ForegroundMark(
        val packageName: String,
        val time: Long,
        val resumed: Boolean,
        val slot: String = packageName
    )

    companion object {
        /** Quota-grade exactness doesn't need per-tick recomputation. */
        const val CACHE_TTL_MS = 30_000L

        /**
         * Event queries are issued in windows of at most this size.
         * The system's own usage tooling chunks long ranges the same way
         * (1h): each query holds a bounded cursor instead of one giant
         * scan, and the concatenated marks stay chronological for
         * [integrate].
         */
        const val QUERY_CHUNK_MS = 3_600_000L

        /**
         * Start the event scan at the previous local midnight so an app
         * resumed before midnight is known to still be foreground today.
         */
        fun queryStartForDay(dayStart: Long): Long = Calendar.getInstance().run {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, -1)
            timeInMillis
        }

        /**
         * Integrates foreground time per package over [rangeStart, rangeEnd].
         * Pure and unit-tested; callers map system events to marks.
         */
        fun integrate(marks: List<ForegroundMark>, rangeStart: Long, rangeEnd: Long): Map<String, Long> {
            val intervals = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
            val open = mutableMapOf<String, Long>()
            val slotPkg = mutableMapOf<String, String>()
            val seen = mutableSetOf<String>()
            fun add(pkg: String, from: Long, to: Long) {
                val clippedFrom = maxOf(from, rangeStart)
                val clippedTo = minOf(to, rangeEnd)
                if (clippedTo > clippedFrom) {
                    intervals.getOrPut(pkg) { mutableListOf() }.add(clippedFrom to clippedTo)
                }
            }
            for (m in marks) {
                val firstSight = seen.add(m.slot)
                slotPkg[m.slot] = m.packageName
                if (m.resumed) {
                    open.putIfAbsent(m.slot, m.time)
                } else {
                    val start = open.remove(m.slot)
                    if (start != null) {
                        add(m.packageName, start, m.time)
                    } else if (firstSight) {
                        // Pause with no observed resume, and nothing seen
                        // before: session started before the range (e.g.
                        // overnight) — count from range start, once.
                        // Later orphan pauses (duplicate stops, PiP
                        // transitions, multi-activity teardown) are
                        // ignored; each would otherwise re-add up to a
                        // full day and inflate totals without bound.
                        add(m.packageName, rangeStart, m.time)
                    }
                }
            }
            // Still foreground at range end: count up to now.
            for ((slot, start) in open) {
                add(slotPkg[slot] ?: continue, start, rangeEnd)
            }
            // Union overlapping intervals so duplicated or nested
            // sessions (reordered/dup system events) never double-count.
            return intervals.mapValues { (_, spans) ->
                var total = 0L
                var curFrom = -1L
                var curTo = -1L
                for ((from, to) in spans.sortedBy { it.first }) {
                    if (from > curTo) {
                        if (curTo >= 0) total += curTo - curFrom
                        curFrom = from
                        curTo = to
                    } else if (to > curTo) {
                        curTo = to
                    }
                }
                if (curTo >= 0) total += curTo - curFrom
                total
            }.filterValues { it > 0 }
        }
    }

    private fun usageManager(): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private fun dayBounds(now: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis to now
    }

    /**
     * Foreground milliseconds per package since midnight. Empty map = no access.
     *
     * Computed from raw events, not from the system's pre-aggregated daily
     * buckets: those buckets are plain 24h periods with no midnight/timezone
     * alignment, so trusting their totals makes the counter follow the
     * system's bucket edge (e.g. ~17:xx) instead of local midnight. The event
     * query begins at the previous local midnight to recover a session that
     * started before today's accounting window and remains open.
     * Results are cached briefly; quota decisions are minute-granular.
     */
    @Synchronized
    fun dailyUsage(now: Long = System.currentTimeMillis()): Map<String, Long> {
        val (start, end) = dayBounds(now)
        if (start == cacheDayStart && now >= cacheAt && now - cacheAt < CACHE_TTL_MS) {
            return cache
        }
        val fresh = computeSinceMidnight(start, end)
        cacheDayStart = start
        cacheAt = now
        cache = fresh
        return fresh
    }

    private var cacheDayStart = 0L
    private var cacheAt = 0L
    private var cache: Map<String, Long> = emptyMap()

    private fun computeSinceMidnight(start: Long, end: Long): Map<String, Long> {
        val manager = usageManager() ?: return emptyMap()
        if (start >= end) return emptyMap()
        val marks = ArrayList<ForegroundMark>(512)
        val event = UsageEvents.Event()
        // Chunked to bound each cursor: shorter queries per chunk,
        // chronological marks across the concatenation.
        var chunkStart = queryStartForDay(start)
        while (chunkStart < end) {
            val chunkEnd = minOf(chunkStart + QUERY_CHUNK_MS, end)
            val events = runCatching {
                manager.queryEvents(chunkStart, chunkEnd)
            }.getOrNull() ?: return emptyMap()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val resumed = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> true
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> false
                    else -> continue
                }
                if (event.packageName == context.packageName) continue
                val pkg = event.packageName ?: continue
                marks.add(ForegroundMark(pkg, event.timeStamp, resumed, event.className ?: pkg))
            }
            chunkStart = chunkEnd
        }
        // Query earlier than the accounting window to recover sessions that
        // began before midnight, but only count intervals from local midnight.
        return integrate(marks, start, end)
    }

    /**
     * Whether the OS app-usage observer API (the mechanism the system
     * usage app enforces with) exists at runtime. It is hidden from the
     * public SDK stubs, so this is probed by reflection; probing only
     * looks the method up, never invokes it. False = keep polling.
     * Cached: the framework doesn't change under a running process.
     */
    fun observerApiPresent(): Boolean = observerApiCached ?: probeObserverApi().also {
        observerApiCached = it
    }

    private var observerApiCached: Boolean? = null

    private fun probeObserverApi(): Boolean = runCatching {
        UsageStatsManager::class.java.getMethod(
            "registerAppUsageObserver",
            Int::class.javaPrimitiveType,
            Array<String>::class.java,
            Long::class.javaPrimitiveType,
            TimeUnit::class.java,
            android.app.PendingIntent::class.java
        )
        true
    }.getOrDefault(false)

    fun usageFor(packageName: String, now: Long = System.currentTimeMillis()): Long =
        dailyUsage(now)[packageName] ?: 0L

    /**
     * Most recently foregrounded package within [sinceMillis]..[now], or null
     * if nothing moved to foreground in that window. Callers should pass a
     * narrow sliding window (seconds) and fall back to their last known
     * value — scanning since midnight every poll gets slower all day.
     */
    fun foregroundPackage(
        sinceMillis: Long,
        now: Long = System.currentTimeMillis()
    ): String? {
        val manager = usageManager() ?: return null
        if (sinceMillis >= now) return null
        val events = runCatching {
            manager.queryEvents(sinceMillis, now)
        }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                last = event.packageName
            }
        }
        return last
    }

    fun dayStartMillis(now: Long = System.currentTimeMillis()): Long = dayBounds(now).first
}

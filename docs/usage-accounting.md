# Usage accounting: what we do and why

## What we do

"Usage since midnight" is **integrated from raw `UsageEvents`**, never read
from the system's pre-aggregated daily buckets.

- `UsageRepository.dailyUsage()` computes `[local-midnight → now]` per call
  (`Calendar`-derived day bounds in the device zone, recomputed every read —
  no cached midnight, no timezone state).
- The query starts at the **previous local midnight**, while integration
  clips intervals to today's midnight. This gives the parser an earlier
  resume event when an app was already foregrounded at midnight and remains
  open; yesterday's usage is not charged to today's quota.
- Events in the queried range are mapped to resume/pause marks
  (`ACTIVITY_RESUMED`/`MOVE_TO_FOREGROUND` = resume;
  `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED`/`MOVE_TO_BACKGROUND` = pause) and
  integrated per package by the pure, unit-tested `integrate()`
  (`UsageIntegrateTest`, 7 tests). Edge cases handled by construction:
  - Pause with no observed resume (session started before midnight) counts
    from midnight — but only once per package per computation. Later
    orphan pauses (duplicate stops, PiP transitions, multi-activity
    teardown — Instagram emits many) are ignored; each would otherwise
    re-add up to a full day and inflate totals without bound (observed:
    1996h on a single day).
  - Still-open session at query time counts up to now, including one whose
    resume occurred before today's accounting range.
  - Per-package intervals are unioned before summing, so duplicated,
    nested, or reordered system events never double-count.
  - Our own package is excluded.
- Event queries are **chunked in 1-hour windows** (same as Digital
  Wellbeing's event loader): bounded cursors per query,
  chronological marks across chunks, identical totals.
- Results are cached 30s (`CACHE_TTL_MS`), keyed on day-start. Quota
  decisions are minute-granular, so worst-case enforcement lag is ~30s.
  Schedule blocking (the 2s poll path) never touches this map.
- Foreground detection is a separate narrow sliding-window event scan
  (seconds, 1s overlap between ticks); the 2s poll loop parks entirely
  while the screen is off or the device is locked.
- Day-scoped state (`warnedToday`) is keyed on `LocalDate`, so the 5-minute
  quota heads-up fires at most once per app per day.
- Midnight needs no alarm: the next read after midnight computes a new
  day-start, which misses the cache and recomputes. A tick straddling
  midnight can't mix days because a single read defines the day.

## Why not bucket totals

`UsageStatsManager.queryUsageStats(INTERVAL_DAILY, midnight, now)` looks
correct but isn't. Verified in AOSP source (`UserUsageStatsService`,
`UnixCalendar`):

- System "daily" buckets carry **no midnight/timezone logic at all** — they
  are plain 24h periods rolling over 24h after whenever they started.
- Query ranges are expanded to whole buckets, unclipped. Asking for
  `[midnight → now]` can return two buckets; keeping one total follows the
  **system's rolling bucket edge** instead of local midnight.

Observed symptom before the fix: counters reset around ~17:xx instead of
midnight, looking random. That was the bucket edge passing, not our day
logic (day numbering, zones, and bounds were all verified correct first).

## What the references do

Established by inspecting the installed apps (Sept 2026); package/class names below
come from inspecting those apps.

**Digital Wellbeing** (Google):
- Its accounting: `queryEvents` only — zero `queryUsageStats` /
  `getTotalTimeInForeground` calls in the whole APK. Filtered event types on
  API 35+, 1h-chunked queries, task-root + `instanceId` attribution.
- Day = local-zone timestamps; start-of-today /
  start-of-tomorrow bounds, a midnight alarm, re-register
  on `TIMEZONE_CHANGED`/`TIME_SET`/`BOOT_COMPLETED`, and a 10s snap of "now"
  across midnight to avoid straddle races.
- Enforcement is **delegated to the OS**: `registerAppUsageLimitObserver()`
  (system tracks limit vs used, fires broadcast), falling back to
  `registerAppUsageObserver(remaining + 5s grace, 1min floor)`. Warning
  observers at 5-min and 1-min remaining. Blocking itself uses
  `SUSPEND_APPS` — real suspension, no overlay race.
- Permissions we can't have: `OBSERVE_APP_USAGE`, `SUSPEND_APPS`,
  `START_ACTIVITIES_FROM_BACKGROUND`, `SYSTEM_APPLICATION_OVERLAY`.

**AppBlock**:
- Own session parsing from raw events
  (foreground/background, screen on/off, shutdown/startup, unlock debounce,
  >12h sessions discarded), persisted into its own DataStore with a
  sync watermark; each sync processes only new events.
- "Today" = `LocalDate.now().atStartOfDay(systemDefault)` → +86399s.
- Enforcement keeps per-limit counters with lazy reset at period start,
  where the daily period starts at a user-configurable **day beginning
  hour** (so a 1am session doesn't get a fresh quota at midnight).
- `queryUsageStats` buckets are used only for a "rough" fallback when event
  history is missing. Clock-tamper resistance via an adjusted clock
  (`currentTime − elapsedRealtime` delta).

**ScreenTime** (Atharok, open source, `ScreenTime/data/statsTracker/UsageStatsTracker.kt`):
- Same events-based integration, same local-midnight `Calendar`
  truncation, single (unchunked) `queryEvents` per range.
- Pairs resume/pause **per activity class** (`event.className ?: package`),
  not per package — concurrent same-app activities (PiP, multi-window,
  rapid transitions) each track independently. Per-app overlaps are
  summed, not unioned (only the device-wide total merges overlaps), so
  concurrency can double-count there.
- Only `ACTIVITY_RESUMED/PAUSED/STOPPED` — no `MOVE_TO_FOREGROUND/BACKGROUND`.
- Tried the overnight credit for orphan pauses and **deleted it**
  ("May cause errors"): orphan pauses are dropped, so midnight-crossing
  sessions undercount. Open-at-end is granted to the single latest resume
  only, not every open session.

All four designs converge: events in, own day bounds, 5-minute warning.

## Adopted vs deliberately skipped

| Idea | Source | Decision |
|---|---|---|
| Events integration, local-midnight bounds | all | **Adopted** — our `dailyUsage()` |
| 1h-chunked event queries | Wellbeing | **Adopted** — `QUERY_CHUNK_MS` |
| 5-min quota warning | all | **Adopted** (already had it) |
| Per-activity pairing (class, else package) | ScreenTime | **Adopted** — `ForegroundMark.slot`. Their per-app sums can double-count overlaps; our per-package union keeps wall-clock-once, so we get their coverage without their overcount. |
| Overnight credit for orphan pauses | ScreenTime tried, deleted | **Adopted (ours).** Kept because midnight-crossing sessions are real usage; bounded by one credit per slot plus the union, so it can't inflate. |
| OS usage observers for enforcement | Wellbeing | **Probed, not used.** `observerApiPresent()` checks by reflection whether `registerAppUsageObserver` exists at runtime (hidden from public SDK stubs) and logs it at service start. Enforcement stays on polling until a real device proves the call works with only `PACKAGE_USAGE_STATS` — unverified, and hidden-API invocation may be blacklisted. |
| Own persistent usage ledger + watermark sync | AppBlock | **Skipped.** Correct but heavy: Room/DataStore schema, migration, backfill. Our full-day re-scan + 30s cache is O(day's events) every 30s — fine until battery profiling says otherwise. Revisit if it does. |
| Configurable day-beginning hour | AppBlock | **Skipped.** Real UX nicety, but scope creep for v1. Midnight reset is the documented, predictable behavior. |
| Clock-tamper adjusted time | AppBlock | **Skipped.** Out of scope: no tamper-resistance goal. |
| `SUSPEND_APPS` real blocking | Wellbeing | **Unavailable.** Signature/privileged permission; not grantable to a user-installed app. Overlay + full-screen-intent remains our mechanism. |
| Task-root / `instanceId` attribution | Wellbeing | **Skipped.** Matters for multi-window correctness; our targets are full-screen phone apps. Revisit if misattribution is ever observed. |

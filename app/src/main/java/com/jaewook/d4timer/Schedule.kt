package com.jaewook.d4timer

const val MIN = 60_000L

data class GameEvent(
    val key: String,
    val name: String,
    val anchor: Long,    // a known start time, epoch millis (UTC)
    val period: Long,
    val duration: Long,
    val color: Int,
    val defaultOn: Boolean,
)

object Schedule {
    // Anchors checked against helltides.com on 2026-10-21 (legion corrected from the user's screenshot)
    val events = listOf(
        GameEvent("boss", "월드보스", 1792587600000L, 210 * MIN, 15 * MIN, 0xFFE2483F.toInt(), true),
        GameEvent("tide", "지옥물결", 1792540800000L, 60 * MIN, 55 * MIN, 0xFFF08A1C.toInt(), false),
        GameEvent("legion", "군단", 1792589700000L, 25 * MIN, 4 * MIN, 0xFF8B6CF0.toInt(), false),
    )

    fun byKey(key: String) = events.first { it.key == key }

    /** The first start strictly after [now]. */
    fun nextStart(e: GameEvent, now: Long): Long =
        e.anchor + (Math.floorDiv(now - e.anchor, e.period) + 1) * e.period

    /** The most recent start at or before [now]. */
    fun lastStart(e: GameEvent, now: Long): Long = nextStart(e, now) - e.period

    fun isLive(e: GameEvent, now: Long): Boolean = now < lastStart(e, now) + e.duration

    /** The first start whose alarm ([leadMin] minutes early) is still in the future. */
    fun nextAlarmStart(e: GameEvent, now: Long, leadMin: Int): Long {
        var start = nextStart(e, now)
        if (start - leadMin * MIN <= now) start += e.period
        // A lead longer than the period would still be in the past; skip ahead until it isn't
        while (start - leadMin * MIN <= now) start += e.period
        return start
    }
}

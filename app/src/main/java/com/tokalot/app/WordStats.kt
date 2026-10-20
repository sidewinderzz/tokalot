package com.tokalot.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The numbers behind the word stats page. Words per month and clean-ups count every device that shares the
 * sync file (this phone alone when it's the only one); pace, streaks and apps come from the history here,
 * which holds the other devices' dictations too when "Include dictation history" is on.
 * The Windows, Linux and Mac apps work these out the same way (WordStats in History.cs).
 */
object WordStats {

    data class MonthRow(val key: String, val words: Long, val dictations: Long) {
        val label: String get() = format(key, "MMMM yyyy")
        val short: String get() = format(key, "MMM yyyy")
    }

    data class AppRow(val label: String, val words: Long)

    data class Streaks(val days: Int, val best: Int, val current: Int)

    /** Clean-ups and offline dictations over every month, on every device that syncs. */
    data class Here(val fillers: Int, val corrections: Int, val offline: Int)

    data class Breakdown(
        val months: List<MonthRow>, // oldest first
        val words: Long,
        val dictations: Long,
        val devices: List<DeviceUsage>, // empty unless more than one device syncs
        val here: Here,
        val pace: Double?,
        val longest: Int,
        val longestAt: Long,
        val days: Streaks,
        val busiestDay: String?,
        val apps: List<AppRow>,
    ) {
        val best: MonthRow? get() = months.filter { it.words > 0 }.maxByOrNull { it.words }
        /** Over the finished months: the month in progress (the last row) would drag it down. */
        val average: Long get() = when {
            months.isEmpty() -> 0
            months.size == 1 -> words
            else -> (words - months.last().words) / (months.size - 1)
        }
        val since: String get() = months.firstOrNull()?.label ?: ""
    }

    /** At most this many months are listed (a clock that was once set years back mustn't make a huge list). */
    const val MAX_MONTHS = 120

    private val REAL_MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")

    private fun format(key: String, pattern: String) = runCatching {
        SimpleDateFormat(pattern, Locale.US).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(key)!!)
    }.getOrDefault(key)

    /** Every month from the first one with words up to [now], oldest first, the devices' counts added together. */
    fun months(devices: Collection<Map<String, UsageMonth>>, now: String): List<MonthRow> {
        val sum = sortedMapOf<String, Pair<Long, Long>>()
        for (d in devices) for ((k, m) in d) {
            val (w, n) = sum[k] ?: (0L to 0L)
            sum[k] = (w + m.words) to (n + m.dictations)
        }
        // A key like "2026-99" gets past the sync file's pattern check, so anything that isn't a real month is skipped.
        val counted = sum.filter { (k, v) -> (v.first > 0 || v.second > 0) && REAL_MONTH.matches(k) }.keys.toList()
        if (counted.isEmpty()) return emptyList()
        fun parse(k: String): Int = k.substring(0, 4).toInt() * 12 + k.substring(5, 7).toInt() - 1
        val first = parse(counted.first())
        val last = maxOf(parse(counted.last()), parse(now))
        val start = maxOf(first, last - MAX_MONTHS + 1)
        return (start..last).map { i ->
            val k = String.format(Locale.US, "%04d-%02d", i / 12, i % 12 + 1)
            val (w, n) = sum[k] ?: (0L to 0L)
            MonthRow(k, w, n)
        }
    }

    /** Days with a dictation, the longest run of days in a row, and the run that reaches today (or yesterday). */
    fun streaks(dayNumbers: Collection<Long>, today: Long): Streaks {
        val days = dayNumbers.toSortedSet()
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (d in days) {
            run = if (prev != Long.MIN_VALUE && d == prev + 1) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        val from = if (today in days) today else today - 1
        var current = 0
        while ((from - current) in days) current++
        return Streaks(days.size, best, current)
    }

    /** Days since 1970 in the phone's own time zone, so a day starts at local midnight. */
    fun dayNumber(ms: Long, tz: TimeZone = TimeZone.getDefault()) = Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L)

    /** Average typing speed on a phone, from a study of 37,000 people typing on their phones (Palin et al., 2019). */
    const val TYPING_WPM = 36

    /** How long typing the words would have taken at [TYPING_WPM], said the short way. */
    fun typingTime(words: Long): String {
        val minutes = words / TYPING_WPM.toDouble()
        if (minutes < 60) return "${Math.round(minutes)} min"
        val hours = short(minutes / 60)
        return hours + if (hours == "1") " hour" else " hours"
    }

    /** What the words would fill: paperback pages (about 300 words each), or novels (about 90,000). Empty below a page. */
    fun equivalent(words: Long): String = when {
        words >= 90_000 -> short(words / 90_000.0).let { it + if (it == "1") " novel" else " novels" }
        words >= 300 -> "${words / 300}" + if (words < 600) " paperback page" else " paperback pages"
        else -> ""
    }

    /**
     * The words a month in progress ends on if the pace so far keeps up, on [day] of a [daysInMonth]-day month.
     * Null for its first two days, when one busy morning would make a wild guess, and on its last day.
     */
    fun onTrack(words: Long, day: Int, daysInMonth: Int): Long? =
        if (words <= 0 || day < 3 || day >= daysInMonth) null else words * daysInMonth / day

    /** One decimal under 10 (dropped when it's .0), whole numbers above. */
    private fun short(v: Double): String =
        if (v < 10) String.format(Locale.US, "%.1f", v).removeSuffix(".0") else Math.round(v).toString()

    fun build(ctx: Context): Breakdown {
        val now = SimpleDateFormat("yyyy-MM", Locale.US).format(System.currentTimeMillis())
        val devices = Usage.everywhere(ctx)?.takeIf { it.devices > 1 }?.each ?: emptyList()
        val months = months(if (devices.size > 1) devices.map { it.months } else listOf(Usage.forSync(ctx).months), now)

        val spoken = History.all(ctx).filter { !it.pending && it.text.isNotEmpty() }
        val counted = spoken.map { it to TextTools.wordCount(it.text) }
        val timed = spoken.filter { it.durationMs > 0 && it.raw.isNotEmpty() }
        val minutes = timed.sumOf { it.durationMs } / 60000.0
        val pace = if (minutes > 0) timed.sumOf { TextTools.wordCount(it.raw) } / minutes else null
        val longest = counted.maxByOrNull { it.second }
        val days = streaks(spoken.map { dayNumber(it.time) }, dayNumber(System.currentTimeMillis()))
        val busiest = if (days.days < 3) null else counted
            .groupBy { Calendar.getInstance().apply { timeInMillis = it.first.time }.get(Calendar.DAY_OF_WEEK) }
            .maxByOrNull { (_, l) -> l.sumOf { it.second } }?.key
            ?.let { d -> Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, d) }.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) }
        // Grouped by app before looking up names, which can be slow for a long history.
        val apps = counted.filter { it.first.app.isNotEmpty() }.groupBy { it.first.label.ifEmpty { it.first.app } }
            .map { (key, l) -> Triple(key, l.first().first, l.sumOf { it.second.toLong() }) }
            .sortedByDescending { it.third }.take(5)
            // A dictation synced from another device brings its app's name; this phone's own are looked up.
            .map { (_, e, w) -> AppRow(e.label.ifEmpty { AppContext.label(ctx, e.app) }, w) }

        val here = if (devices.size > 1) WordStats.Here(
            devices.sumOf { it.fillers }.toInt(), devices.sumOf { it.corrections }.toInt(), devices.sumOf { it.offline }.toInt(),
        ) else Usage.here(ctx)
        return Breakdown(
            months, months.sumOf { it.words }, months.sumOf { it.dictations }, devices, here,
            pace, longest?.second ?: 0, longest?.first?.time ?: 0, days, busiest, apps,
        )
    }
}

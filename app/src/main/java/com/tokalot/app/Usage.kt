package com.tokalot.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Per-month usage and estimated cost, counted on the phone from what each API call reports.
 * These are estimates from list prices; your provider's billing page is the source of truth.
 */
object Usage {
    // $ per hour of audio, and minimum billed seconds per request (Groq bills at least 10 s).
    private val sttPrice = mapOf(SttChoice.GROQ to 0.04, SttChoice.OPENAI to 0.36)
    private val sttMinSec = mapOf(SttChoice.GROQ to 10.0, SttChoice.OPENAI to 0.0)

    // $ per million tokens (input, output).
    private val llmPrice = mapOf(
        CleanupChoice.GROQ to (0.075 to 0.30),
        CleanupChoice.CLAUDE to (1.0 to 5.0),
        CleanupChoice.GEMINI to (0.25 to 1.50),
        CleanupChoice.OPENAI to (0.10 to 0.40),
    )

    data class Month(
        val label: String,
        val dictations: Int,
        val words: Int,
        val localCount: Int,
        val sttSeconds: Map<SttChoice, Double>,
        val tokens: Map<CleanupChoice, Pair<Long, Long>>,
        val fillers: Int,
        val corrections: Int,
    ) {
        fun sttCost(c: SttChoice) = (sttSeconds[c] ?: 0.0) / 3600.0 * (sttPrice[c] ?: 0.0)
        fun llmCost(c: CleanupChoice): Double {
            val (i, o) = tokens[c] ?: (0L to 0L)
            val (pi, po) = llmPrice[c] ?: (0.0 to 0.0)
            return i / 1e6 * pi + o / 1e6 * po
        }
        val total get() = SttChoice.values().sumOf { sttCost(it) } + CleanupChoice.values().sumOf { llmCost(it) }
    }

    const val PREFS = "usage"
    /** This phone's id in the sync file's usage. It lives with the counts, so a backup carries both together. */
    const val DEVICE_KEY = "device"
    private val MONTH = Regex("""\d{4}-\d{2}""")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun monthKey(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(t))

    /** A month's counters; anything unreadable (wrong type, bad JSON) counts as empty rather than crashing. */
    private fun read(ctx: Context, key: String): JSONObject =
        runCatching { JSONObject(sp(ctx).safeString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }

    @Synchronized
    private fun edit(ctx: Context, block: (JSONObject) -> Unit) {
        val key = monthKey()
        val o = read(ctx, key)
        block(o)
        sp(ctx).edit().putString(key, o.toString()).apply()
    }

    private fun JSONObject.inc(name: String, by: Double) = put(name, optDouble(name, 0.0) + by)

    fun recordDictation(ctx: Context, words: Int, local: Boolean) = edit(ctx) {
        it.inc("n", 1.0); it.inc("words", words.toDouble())
        if (local) it.inc("local", 1.0)
    }

    fun recordEdits(ctx: Context, fillers: Int, corrections: Int) = edit(ctx) {
        it.inc("fillers", fillers.toDouble()); it.inc("corrections", corrections.toDouble())
    }

    fun recordStt(ctx: Context, c: SttChoice, seconds: Double) = edit(ctx) {
        it.inc("stt_${c.name}", maxOf(seconds, sttMinSec[c] ?: 0.0))
    }

    fun recordLlm(ctx: Context, c: CleanupChoice, inTok: Long, outTok: Long) = edit(ctx) {
        it.inc("in_${c.name}", inTok.toDouble()); it.inc("out_${c.name}", outTok.toDouble())
    }

    fun month(ctx: Context, key: String = monthKey()): Month {
        val o = read(ctx, key)
        val label = runCatching {
            SimpleDateFormat("MMMM yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(key)!!)
        }.getOrDefault(key)
        return Month(
            label,
            o.optDouble("n", 0.0).toInt(),
            o.optDouble("words", 0.0).toInt(),
            o.optDouble("local", 0.0).toInt(),
            SttChoice.values().associateWith { o.optDouble("stt_${it.name}", 0.0) },
            CleanupChoice.values().associateWith {
                o.optDouble("in_${it.name}", 0.0).toLong() to o.optDouble("out_${it.name}", 0.0).toLong()
            },
            o.optDouble("fillers", 0.0).toInt(),
            o.optDouble("corrections", 0.0).toInt(),
        )
    }

    /** Most recent months first, up to [n]. */
    fun recentMonths(ctx: Context, n: Int = 6): List<Month> =
        sp(ctx).all.keys.filter { it.matches(MONTH) }
            .sortedDescending().take(n).map { month(ctx, it) }

    @Synchronized
    fun deviceId(ctx: Context): String =
        sp(ctx).safeString(DEVICE_KEY, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { sp(ctx).edit().putString(DEVICE_KEY, it).commit() }

    /** This phone's words and dictations per month, as the sync file holds them. */
    fun forSync(ctx: Context): DeviceUsage {
        val months = sp(ctx).all.keys.filter { it.matches(MONTH) }.sorted().associateWith { k ->
            val o = read(ctx, k)
            UsageMonth(o.optDouble("words", 0.0).toLong(), o.optDouble("n", 0.0).toLong())
        }.filterValues { it.words > 0 || it.dictations > 0 }
        return DeviceUsage("Android · ${Build.MODEL}", System.currentTimeMillis(), months)
    }

    /** Words on every device that syncs: (this month, all time, how many devices). Null when sync is off. */
    data class Everywhere(val month: Long, val total: Long, val devices: Int, val each: List<DeviceUsage>)

    fun everywhere(ctx: Context): Everywhere? {
        val prefs = Prefs(ctx)
        if (prefs.syncUri == null) return null
        // The file's entries as of the last sync, with this phone's own counts as they are right now.
        val all = SyncMerge.usage(SyncFormat.parseUsage(prefs.syncUsage), deviceId(ctx), forSync(ctx))
        val key = monthKey()
        val each = all.values.sortedByDescending { it.words }
        return Everywhere(each.sumOf { it.months[key]?.words ?: 0L }, each.sumOf { it.words }, each.size, each)
    }
}

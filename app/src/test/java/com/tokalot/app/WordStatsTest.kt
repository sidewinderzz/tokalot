package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The word stats page's sums. The Windows, Linux and Mac apps check the same cases (Shots.cs, 13a–f). */
class WordStatsTest {

    private fun um(vararg m: Triple<String, Long, Long>) = m.associate { (k, w, n) -> k to UsageMonth(w, n) }

    @Test fun monthsAddUpAcrossDevicesWithGapsFilledToNow() { // 13a
        val rows = WordStats.months(
            listOf(um(Triple("2026-07", 100, 4), Triple("2026-10", 50, 2)), um(Triple("2026-07", 20, 1), Triple("2026-09", 0, 0))),
            "2026-11",
        )
        assertEquals(
            "2026-07=120/5,2026-08=0/0,2026-09=0/0,2026-10=50/2,2026-11=0/0",
            rows.joinToString(",") { "${it.key}=${it.words}/${it.dictations}" },
        )
        assertEquals("July 2026", rows.first().label)
        assertEquals("Nov 2026", rows.last().short)
    }

    @Test fun nothingCountedIsNoMonths() { // 13b
        assertTrue(WordStats.months(listOf(um(Triple("2026-07", 0, 0))), "2026-11").isEmpty())
        assertTrue(WordStats.months(emptyList(), "2026-11").isEmpty())
        assertTrue(WordStats.months(listOf(um(Triple("2026-99", 5, 1), Triple("2026-00", 5, 1))), "2026-11").isEmpty())
    }

    @Test fun aClockSetYearsBackListsAtMostTenYears() { // 13c
        val rows = WordStats.months(listOf(um(Triple("1990-01", 5, 1))), "2026-11")
        assertEquals(WordStats.MAX_MONTHS, rows.size)
        assertEquals("2026-11", rows.last().key)
    }

    @Test fun yearBoundary() {
        val rows = WordStats.months(listOf(um(Triple("2025-12", 5, 1))), "2026-01")
        assertEquals(listOf("2025-12", "2026-01"), rows.map { it.key })
    }

    @Test fun streaks() { // 13d, 13e
        assertEquals(WordStats.Streaks(6, 3, 2), WordStats.streaks(listOf<Long>(10, 11, 12, 15, 19, 20, 20), 21))
        assertEquals(0, WordStats.streaks(listOf<Long>(1, 2), 9).current)
        assertEquals(WordStats.Streaks(0, 0, 0), WordStats.streaks(emptyList(), 9))
    }

    @Test fun typingTimeAndWhatItFills() { // 13f
        assertEquals("33 min", WordStats.typingTime(1200))
        assertEquals("1 hour", WordStats.typingTime(2160))
        assertEquals("46 hours", WordStats.typingTime(100_000))
        assertEquals("", WordStats.equivalent(299))
        assertEquals("1 paperback page", WordStats.equivalent(450))
        assertEquals("1 novel", WordStats.equivalent(90_000))
        assertEquals("1.5 novels", WordStats.equivalent(135_000))
    }

    @Test fun daysStartAtLocalMidnight() {
        val tz = TimeZone.getTimeZone("America/Chicago") // UTC-5 in summer
        val lateEvening = 1_784_000_000_000L // 2026-07-14 03:33 UTC, still the 13th in Chicago
        assertEquals(WordStats.dayNumber(lateEvening, TimeZone.getTimeZone("UTC")) - 1, WordStats.dayNumber(lateEvening, tz))
    }

    @Test fun theMonthInProgressIsProjectedAndLeftOutOfTheAverage() { // 13g
        assertEquals(3100L, WordStats.onTrack(1900, 19, 31))
        assertNull(WordStats.onTrack(500, 2, 31))
        assertNull(WordStats.onTrack(0, 19, 31))
        assertNull(WordStats.onTrack(3000, 30, 30))
        val b = WordStats.Breakdown(
            WordStats.months(listOf(mapOf("2026-08" to UsageMonth(1000, 10), "2026-09" to UsageMonth(3000, 30), "2026-10" to UsageMonth(200, 2))), "2026-10"),
            4200, 42, emptyList(), WordStats.Here(0, 0, 0), null, 0, 0, WordStats.Streaks(0, 0, 0), null, emptyList(),
        )
        assertEquals(2000L, b.average)
    }
}

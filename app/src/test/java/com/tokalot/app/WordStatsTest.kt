package com.tokalot.app

import org.junit.Assert.assertEquals
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
        assertEquals("30 min", WordStats.typingTime(1200))
        assertEquals("1 hour", WordStats.typingTime(2400))
        assertEquals("42 hours", WordStats.typingTime(100_000))
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
}

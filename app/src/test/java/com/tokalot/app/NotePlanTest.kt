package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reminders and checklists on notes: spoken times, lists, the AI's answer and the plan's JSON. */
class NotePlanTest {
    private val zone = ZoneId.of("America/Chicago")
    /** Tuesday, Nov 3 2026, 9:15 PM. */
    private val evening = ZonedDateTime.of(2026, 11, 3, 21, 15, 0, 0, zone)
    /** Tuesday, Nov 3 2026, 7:40 AM. */
    private val morning = ZonedDateTime.of(2026, 11, 3, 7, 40, 0, 0, zone)

    private fun at(text: String, now: ZonedDateTime = morning): String? =
        SpokenTime.find(text, now)?.let { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime().toString() }

    @Test fun clockTimes() {
        assertEquals("2026-11-03T19:30", at("lock the shop at 7:30")) // 7:30 AM has just gone
        assertEquals("2026-11-03T08:00", at("at 8 feed the calves"))
        assertEquals("2026-11-03T18:00", at("load the dogs at 6"))
        assertEquals("2026-11-04T06:00", at("load the dogs at 6", evening))
        assertEquals("2026-11-03T15:00", at("call Mike 3 pm"))
        assertEquals("2026-11-03T15:30", at("call Mike at 3:30pm"))
        assertEquals("2026-11-03T12:00", at("call Mike before noon"))
        assertEquals("2026-11-03T20:00", at("at 8 tonight"))
        assertEquals("2026-11-04T07:00", at("at 7 in the morning", evening))
        assertEquals("2026-11-03T17:00", at("by 17:00"))
    }

    @Test fun daysAndRelative() {
        assertEquals("2026-11-04T09:00", at("tomorrow call the vet"))
        assertEquals("2026-11-04T06:00", at("tomorrow at 6 load the dogs"))
        assertEquals("2026-11-04T17:00", at("tomorrow at 5"))
        assertEquals("2026-11-06T08:00", at("Friday morning oil the hinges"))
        assertEquals("2026-11-03T20:00", at("tonight take the meds"))
        assertEquals("2026-11-03T08:00", at("in 20 minutes check the oven"))
        assertEquals("2026-11-03T08:20", at("in half an hour", morning.plusMinutes(10)))
        assertEquals("2026-11-03T09:40", at("in two hours"))
    }

    @Test fun numbersThatArentTimes() {
        assertNull(at("pack 3 shirts"))
        assertNull(at("buy 2 bags of feed"))
        assertNull(at("Note this, the printer is jammed"))
    }

    @Test fun stripLeavesTheTask() {
        fun strip(s: String) = SpokenTime.strip(s, SpokenTime.find(s, morning))
        assertEquals("Lock the shop", strip("at 7:30 to lock the shop"))
        assertEquals("Lock the shop", strip("lock the shop at 7:30"))
        assertEquals("Call Mike", strip("call Mike before noon."))
        assertEquals("Take the meds", strip("at 8 tonight take the meds"))
        assertEquals("Check the oven", strip("in 20 minutes to check the oven"))
    }

    @Test fun reminderOnThePhone() {
        val made = Plans.local(Plans.Kind.REMINDER, "at 7:30 to lock the shop", morning)
        assertEquals("Lock the shop", made.text)
        assertEquals("2026-11-03T19:30", Instant.ofEpochMilli(made.plan!!.remind).atZone(zone).toLocalDateTime().toString())
        // No time said: still a reminder, waiting for one.
        val open = Plans.local(Plans.Kind.REMINDER, "to lock the shop", morning)
        assertEquals(0L, open.plan!!.remind)
    }

    @Test fun listOnThePhone() {
        val made = Plans.local(Plans.Kind.LIST,
            "Trip list for tomorrow. Pack chargers, cooler, dog food. At 6 load the dogs. Remind me at 7:30 to lock the shop.", evening)
        assertEquals(Plans.Kind.LIST, made.kind)
        assertEquals("Trip list for tomorrow", made.text)
        val items = made.plan!!.items
        assertEquals(listOf("Pack chargers", "Cooler", "Dog food", "Load the dogs", "Lock the shop"), items.map { it.text })
        assertEquals(0L, items[0].at)
        fun time(i: Int) = Instant.ofEpochMilli(items[i].at).atZone(zone).toLocalDateTime().toString()
        assertEquals("2026-11-04T06:00", time(3))
        assertEquals("2026-11-04T07:30", time(4))
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test fun aiAnswer() {
        val reply = """Sure: {"kind":"list","title":"Trip, Wed Nov 4","remind":"","items":[
            {"text":"pack chargers","when":""},{"text":"Load the dogs","when":"2026-11-04 06:00"}]}"""
        val made = Plans.fromAi(reply, zone, 100)!!
        assertEquals(Plans.Kind.LIST, made.kind)
        assertEquals("Pack chargers", made.plan!!.items[0].text)
        assertEquals("2026-11-04T06:00", Instant.ofEpochMilli(made.plan!!.items[1].at).atZone(zone).toLocalDateTime().toString())
        val rem = Plans.fromAi("""{"kind":"reminder","title":"Lock the shop","remind":"2026-11-03 19:30","items":[]}""", zone, 1)!!
        assertEquals("Lock the shop", rem.text)
        assertEquals(Plans.Kind.NOTE, Plans.fromAi("""{"kind":"note","title":"","remind":"","items":[]}""", zone, 1)!!.kind)
        assertNull(Plans.fromAi("no idea", zone, 1))
        assertNull(Plans.fromAi("""{"kind":"reminder","title":"x","remind":"tomorrow-ish"}""", zone, 1))
    }

    @Test fun jsonRoundTrip() {
        val p = NotePlan(remind = 5, alarm = true, items = listOf(PlanItem(1, "a"), PlanItem(2, "b", done = true, at = 9, alarm = true)))
        assertEquals(p, NotePlan.parse(p.line()))
        assertEquals("", NotePlan().line())
        assertNull(NotePlan.parse(""))
        assertNull(NotePlan.parse("{broken"))
        assertEquals("", NotePlan.canon("{}"))
        assertNull(NotePlan.canon(null))
    }

    @Test fun dues() {
        val p = NotePlan(remind = 10, items = listOf(PlanItem(1, "untimed"), PlanItem(2, "timed", at = 20), PlanItem(3, "done", done = true, at = 30)))
        val d = Plans.dues(7, "Note", p)
        assertEquals(listOf("7:r:10", "7:2:20"), d.map { it.key })
        assertTrue(Plans.dues(7, "Note", Plans.setDone(p, null, true)).none { it.itemId == null })
        val moved = Plans.moveTo(Plans.setDone(p, 2, true), 2, 99)
        assertEquals(99L, moved.items[1].at)
        assertEquals(false, moved.items[1].done)
        assertNotNull(Plans.label(evening.plusDays(1).toInstant().toEpochMilli(), evening))
        assertEquals("Tomorrow 9:15 PM", Plans.label(evening.plusDays(1).toInstant().toEpochMilli(), evening))
    }
}

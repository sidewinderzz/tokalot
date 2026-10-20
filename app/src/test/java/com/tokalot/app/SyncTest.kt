package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The sync file's merge rules and format. The Windows and Linux apps are held to the same cases. */
class SyncTest {

    private fun remote(
        words: List<String> = emptyList(), snippets: List<Snippet> = emptyList(),
        styles: Map<String, String> = emptyMap(), instructions: String? = "", keys: Map<String, String>? = null,
    ) = SyncRemote(words, snippets, styles, instructions, keys)

    private fun remote(s: SyncState) = remote(s.words, s.snippets, s.styles, s.instructions)

    private fun merge(
        local: SyncState, remote: SyncRemote?, base: SyncState,
        keys: Map<String, String> = emptyMap(), includeKeys: Boolean = false,
    ) = SyncMerge.merge(local, remote, base, keys, includeKeys)

    private fun words(l: List<String>, r: List<String>, b: List<String>) =
        merge(SyncState(words = l), remote(words = r), SyncState(words = b)).state.words

    // ---------- words ----------

    @Test fun firstSyncTakesTheFilesOrderThenLocalOnlyWords() { // 1
        assertEquals(listOf("beta", "Gamma", "Alpha"), words(listOf("Alpha", "Beta"), listOf("beta", "Gamma"), emptyList()))
    }

    @Test fun wordDeletedHereStaysDeleted() { // 2
        assertEquals(
            listOf("Alpha", "Gamma", "Delta"),
            words(listOf("Alpha", "Gamma"), listOf("Alpha", "Beta", "Gamma", "Delta"), listOf("Alpha", "Beta", "Gamma"))
        )
    }

    @Test fun wordDeletedElsewhereIsDeletedHere() { // 3
        assertEquals(listOf("Alpha"), words(listOf("Alpha", "Beta"), listOf("Alpha"), listOf("Alpha", "Beta")))
    }

    @Test fun everyDeviceSettlesOnTheFile() { // 9
        val s = merge(SyncState(words = listOf("Alpha", "Beta")), remote(words = listOf("beta", "Gamma")), SyncState()).state
        val second = merge(SyncState(words = listOf("Gamma", "Alpha", "BETA")), remote(s), SyncState())
        assertEquals(s, second.state)
        assertFalse(second.write)
        val again = merge(s, remote(s), s)
        assertEquals(s, again.state)
        assertFalse(again.write)
    }

    @Test fun wordRespelledHereKeepsItsNewSpelling() { // 10
        assertEquals(listOf("Beta"), words(listOf("Beta"), listOf("beta"), listOf("beta")))
    }

    @Test fun wordsAreComparedIgnoringCaseAndSpaces() {
        assertEquals(listOf("Alpha"), words(listOf(" alpha ", "ALPHA"), listOf("Alpha"), emptyList()))
    }

    // ---------- snippets ----------

    private fun snippetText(l: String, r: String, b: String) = merge(
        SyncState(snippets = listOf(Snippet("my email", l))),
        remote(snippets = listOf(Snippet("my email", r))),
        SyncState(snippets = listOf(Snippet("my email", b))),
    ).state.snippets

    @Test fun snippetChangedElsewhereIsTakenUnlessChangedHere() { // 4
        assertEquals(listOf(Snippet("my email", "b@x")), snippetText(l = "a@x", r = "b@x", b = "a@x"))
        assertEquals(listOf(Snippet("my email", "c@x")), snippetText(l = "c@x", r = "b@x", b = "a@x"))
    }

    @Test fun snippetsFollowTheFilesOrderAndTriggerSpelling() {
        val r = merge(
            SyncState(snippets = listOf(Snippet("Mine", "1"), Snippet("my email", "a@x"), Snippet("gone", "2"))),
            remote(snippets = listOf(Snippet("new", "3"), Snippet("My Email", "a@x"), Snippet("dropped", "4"))),
            SyncState(snippets = listOf(Snippet("gone", "2"), Snippet("dropped", "4"), Snippet("my email", "a@x"))),
        )
        // "gone" was deleted elsewhere, "dropped" was deleted here.
        assertEquals(listOf(Snippet("new", "3"), Snippet("My Email", "a@x"), Snippet("Mine", "1")), r.state.snippets)
        assertTrue(r.write)
    }

    // ---------- styles and instructions ----------

    private fun styles(l: Map<String, String>, r: Map<String, String>, b: Map<String, String>) =
        merge(SyncState(styles = l), remote(styles = r), SyncState(styles = b)).state.styles

    @Test fun styleUntouchedHereFollowsTheFile() { // 5
        assertEquals(
            mapOf("EMAIL" to "CASUAL", "MESSAGING" to "VERY_CASUAL"),
            styles(mapOf("EMAIL" to "FORMAL"), mapOf("EMAIL" to "CASUAL", "MESSAGING" to "VERY_CASUAL"), mapOf("EMAIL" to "FORMAL"))
        )
        assertEquals(mapOf("EMAIL" to "CASUAL"), styles(mapOf("EMAIL" to "CASUAL"), mapOf("EMAIL" to "FORMAL"), emptyMap()))
    }

    @Test fun styleUnsetElsewhereIsUnsetHere() {
        assertEquals(emptyMap<String, String>(), styles(mapOf("EMAIL" to "FORMAL"), emptyMap(), mapOf("EMAIL" to "FORMAL")))
    }

    private fun instructions(l: String, r: String?, b: String) =
        merge(SyncState(instructions = l), remote(instructions = r), SyncState(instructions = b)).state.instructions

    @Test fun instructionsFollowTheFileUnlessEditedHere() { // 6
        assertEquals("y", instructions(l = "x", r = "y", b = "x"))
        assertEquals("z", instructions(l = "z", r = "y", b = "x"))
        assertEquals("x", instructions(l = "x", r = null, b = "x")) // the file has no such field
    }

    @Test fun firstSyncKeepsEditedInstructionsAndTakesTheFilesOverTheDefault() {
        val base = SyncFormat.firstBase(DEFAULT_INSTRUCTIONS)
        assertEquals("theirs", merge(SyncState(instructions = DEFAULT_INSTRUCTIONS), remote(instructions = "theirs"), base).state.instructions)
        assertEquals("mine", merge(SyncState(instructions = "mine"), remote(instructions = "theirs"), base).state.instructions)
    }

    // ---------- keys ----------

    @Test fun aKeyClearedHereIsNotRefilledFromTheFile() {
        val file = mapOf("groq" to "R1", "openai" to "R2")
        val r = SyncMerge.merge(SyncState(), remote(keys = file), SyncState(), mapOf("groq" to ""), false, knownKeys = setOf("groq"))
        assertEquals("", r.localKeys["groq"])     // had one before, cleared it: stays cleared
        assertEquals("R2", r.localKeys["openai"]) // never had one: filled as before
        assertEquals(file, r.fileKeys)            // and the file keeps its copy
    }

    @Test fun keysOnlyReachTheFileWhenIncluded() { // 7
        val file = mapOf("groq" to "R1", "openai" to "R2")
        val off = merge(SyncState(), remote(keys = file), SyncState(), keys = mapOf("groq" to "L1"), includeKeys = false)
        assertEquals("R2", off.localKeys["openai"])
        assertEquals("L1", off.localKeys["groq"])
        assertEquals(file, off.fileKeys)
        assertFalse(off.write)

        val on = merge(SyncState(), remote(keys = file), SyncState(), keys = mapOf("groq" to "L1"), includeKeys = true)
        assertEquals("R2", on.localKeys["openai"])
        assertEquals("L1", on.localKeys["groq"])
        assertEquals(mapOf("groq" to "L1", "openai" to "R2"), on.fileKeys)
        assertTrue(on.write)
    }

    @Test fun fileWithoutKeysStaysWithoutThemUnlessIncluded() {
        val keys = mapOf("anthropic" to "K")
        assertNull(merge(SyncState(), remote(), SyncState(), keys, includeKeys = false).fileKeys)
        assertEquals(keys, merge(SyncState(), remote(), SyncState(), keys, includeKeys = true).fileKeys)
    }

    // ---------- when to write ----------

    @Test fun missingFileIsWrittenWithLocalValues() {
        val local = SyncState(listOf("Alpha"), listOf(Snippet("a", "b")), mapOf("EMAIL" to "CASUAL"), "mine")
        val r = merge(local, null, SyncState(listOf("Alpha", "Old")))
        assertEquals(local, r.state)
        assertTrue(r.write)
    }

    @Test fun emptyFileAfterSharingIsRefusedNotTreatedAsAFreshStart() { // 11
        assertTrue(SyncMerge.remoteLost(null, SyncState(words = listOf("Alpha"))))
        assertTrue(SyncMerge.remoteLost(null, SyncState(notes = listOf(SyncNote(1, 1, "a")))))
        assertFalse(SyncMerge.remoteLost(null, SyncFormat.firstBase("default")))
        assertFalse(SyncMerge.remoteLost(remote(), SyncState(words = listOf("Alpha"))))
    }

    // ---------- the file ----------

    @Test fun jsonRoundTripKeepsEveryField() { // 8
        val state = SyncState(
            listOf("Kubernetes", "Nguyen"), listOf(Snippet("my email", "me@example.com")),
            mapOf("MESSAGING" to "CASUAL", "EMAIL" to "FORMAL"), "Always write \"lol\" in lowercase.",
        )
        val keys = mapOf("groq" to "g", "anthropic" to "a", "openai" to "o", "gemini" to "m")
        val json = SyncFormat.encode(state, keys, 1_700_000_000_123, "Android")
        assertTrue(json.contains("\n")) // pretty-printed
        val r = SyncFormat.parse(json)!!
        assertEquals(state.words, r.words)
        assertEquals(state.snippets, r.snippets)
        assertEquals(state.styles, r.styles)
        assertEquals(state.instructions, r.instructions)
        assertEquals(keys, r.keys)
        assertEquals(1_700_000_000_123, r.updated)
        assertEquals("Android", r.by)
        assertFalse(SyncMerge.merge(state, r, state, emptyMap(), false).write)

        assertNull(SyncFormat.parse(SyncFormat.encode(state, null, 1, "Android"))!!.keys)
        assertEquals(state, SyncFormat.parseBase(SyncFormat.encodeBase(state), "default"))
    }

    @Test fun fileFromAnotherDeviceIsRead() {
        val r = SyncFormat.parse(
            "﻿" + """{"app":"tokalot-sync","format":1,"updated":5,"by":"Windows","someday":true,
                "words":["Kubernetes"],"snippets":[{"trigger":"my email","text":"me@example.com"}],
                "styles":{"AI_CODE":"VERY_CASUAL","OTHER":"FORMAL","EMAIL":"SHOUTING","PIGEON":"CASUAL"},
                "instructions":"hi"}"""
        )!!
        assertEquals(listOf("Kubernetes"), r.words)
        assertEquals(listOf(Snippet("my email", "me@example.com")), r.snippets)
        assertEquals(mapOf("AI_CODE" to "VERY_CASUAL", "OTHER" to "FORMAL"), r.styles)
        assertEquals("hi", r.instructions)
        assertNull(r.keys)
        assertEquals("Windows", r.by)
    }

    @Test fun emptyOrMissingFileIsNoRemoteState() {
        assertNull(SyncFormat.parse(null))
        assertNull(SyncFormat.parse(""))
        assertNull(SyncFormat.parse(" \n"))
        assertEquals(SyncState(instructions = "default"), SyncFormat.parseBase(null, "default"))
    }

    @Test fun otherFilesAndNewerFormatsAreRejected() { // 8
        fun rejected(json: String, message: String) {
            try {
                SyncFormat.parse(json)
                fail("expected a rejection of: $json")
            } catch (e: SyncFormatException) {
                assertEquals(message, e.message)
            }
        }
        rejected("""{"app":"something-else","format":1,"words":[]}""", "That isn't a Tokalot sync file")
        rejected("""{"format":1,"words":[]}""", "That isn't a Tokalot sync file")
        rejected("not json at all", "That isn't a Tokalot sync file")
        rejected("""{"app":"tokalot-sync","format":2,"words":[]}""", "Update Tokalot to sync with this file")
    }

    // ---------- voice notes ----------

    private val a = SyncNote(1, 1, "Call about the invoice")
    private val b = SyncNote(2, 2, "Buy printer paper")
    private val c = SyncNote(3, 3, "Book the dentist")

    private fun notes(l: List<SyncNote>, r: List<SyncNote>?, base: List<SyncNote>) =
        merge(SyncState(notes = l), SyncRemote(emptyList(), emptyList(), emptyMap(), "", null, notes = r), SyncState(notes = base)).state.notes

    @Test fun notesFromBothDevicesAreKeptNewestFirst() {
        assertEquals(listOf(c, b, a), notes(listOf(a, c), listOf(b), emptyList()))
        assertEquals(listOf(c, b, a), notes(listOf(c, b, a), listOf(c, b, a), listOf(a)))
    }

    @Test fun aDeletedNoteStaysDeleted() {
        assertEquals(listOf(b), notes(listOf(b), listOf(a, b), listOf(a, b)))   // deleted here
        assertEquals(listOf(b), notes(listOf(a, b), listOf(b), listOf(a, b)))   // deleted elsewhere
        assertEquals(listOf(c, b), notes(listOf(c, b), listOf(b), listOf(a, b))) // deleted elsewhere, a new one here
    }

    @Test fun aChangedNoteWins() {
        val edited = a.copy(text = "Call about the March invoice")
        assertEquals(listOf(edited), notes(listOf(a), listOf(edited), listOf(a)))
        assertEquals(listOf(edited), notes(listOf(edited), listOf(a), listOf(a)))
    }

    /** A file last written by a version from before notes synced has no "notes": nothing is deleted because of it. */
    @Test fun aFileWithoutNotesLeavesThemAlone() {
        assertEquals(listOf(b, a), notes(listOf(b, a), null, listOf(b, a)))
        val r = SyncFormat.parse("""{"app":"tokalot-sync","format":1,"words":[],"snippets":[],"styles":{},"instructions":""}""")!!
        assertNull(r.notes)
        val res = SyncMerge.merge(SyncState(notes = listOf(a)), r, SyncState(), emptyMap(), false)
        assertEquals(listOf(a), res.state.notes)
        assertTrue(res.write) // and the notes go into the file
        assertFalse(SyncMerge.merge(SyncState(), r, SyncState(), emptyMap(), false).write)
    }

    @Test fun notesSurviveTheFileAndTheBase() {
        val state = SyncState(words = listOf("Kowalski"), notes = listOf(b, a))
        val r = SyncFormat.parse(SyncFormat.encode(state, null, 1, "Android"))!!
        assertEquals(listOf(b, a), r.notes)
        assertEquals(state.copy(instructions = ""), SyncFormat.parseBase(SyncFormat.encodeBase(state), "default"))
    }

    // ---------- usage ----------

    private val oct = mapOf("2026-10" to UsageMonth(words = 500, dictations = 20))
    private val phone = DeviceUsage("Android · Pixel 8", 1, oct)
    private val pc = DeviceUsage("Windows · FARM-PC", 2, mapOf("2026-09" to UsageMonth(1200, 40), "2026-10" to UsageMonth(300, 9)))

    @Test fun eachDeviceOnlyReplacesItsOwnUsage() {
        val first = SyncMerge.usage(mapOf("pc" to pc), "phone", phone)
        assertEquals(mapOf("pc" to pc, "phone" to phone), first)
        // The phone dictates more: its entry changes, the PC's is carried over untouched.
        val more = phone.copy(updated = 9, months = mapOf("2026-10" to UsageMonth(800, 31)))
        val second = SyncMerge.usage(first, "phone", more)
        assertEquals(pc, second["pc"])
        assertEquals(more, second["phone"])
        assertEquals(1200L + 300 + 800, second.values.sumOf { it.words })
    }

    @Test fun syncingAgainWithTheSameCountsChangesNothing() {
        val file = mapOf("pc" to pc, "phone" to phone)
        // Same counts at a later time: the entry is kept as it was, so the file isn't rewritten for it.
        assertEquals(file, SyncMerge.usage(file, "phone", phone.copy(updated = 99)))
        // A device with nothing counted adds no entry.
        assertEquals(mapOf("pc" to pc), SyncMerge.usage(mapOf("pc" to pc), "new", DeviceUsage("Linux", 5, emptyMap())))
    }

    @Test fun usageSurvivesTheFile() {
        val usage = mapOf("pc" to pc, "phone" to phone)
        val r = SyncFormat.parse(SyncFormat.encode(SyncState(), null, 1, "Android", usage))!!
        assertEquals(usage, r.usage)
        assertEquals(usage, SyncFormat.parseUsage(SyncFormat.encodeUsage(usage)))
        // The base never holds usage, and a file without any leaves "usage" out.
        assertFalse(SyncFormat.encodeBase(SyncState()).contains("usage"))
        assertNull(SyncFormat.parse(SyncFormat.encode(SyncState(), null, 1, "Android"))!!.usage)
    }

    @Test fun badUsageIsSkippedNotAnError() {
        val r = SyncFormat.parse(
            """{"app":"tokalot-sync","format":1,"usage":{"a":{"name":"Mac","months":{"2026-10":{"words":12,"dictations":-3},""" +
                """"oops":{"words":5},"2026-11":"x"}},"b":"not a device"}}"""
        )!!
        assertEquals(mapOf("a" to DeviceUsage("Mac", 0, mapOf("2026-10" to UsageMonth(12, 0)))), r.usage)
        assertEquals(emptyMap<String, DeviceUsage>(), SyncFormat.parseUsage("not json"))
        assertEquals(emptyMap<String, DeviceUsage>(), SyncFormat.parseUsage(null))
    }

    /** Usage changes nothing about the empty-file guard: a device that shared settings still refuses an empty file. */
    @Test fun usageDoesNotWeakenTheEmptyFileGuard() {
        assertTrue(SyncMerge.remoteLost(null, SyncState(words = listOf("Kowalski"))))
        assertFalse(SyncMerge.remoteLost(null, SyncState()))
    }

    // ---------- settings ----------

    private val anyValue = { _: String, _: String -> true }

    private fun settings(l: Map<String, String>, r: Map<String, String>?, b: Map<String, String>?, usable: (String, String) -> Boolean = anyValue) =
        SyncMerge.settings(l, r, b, usable)

    @Test fun settingChangedHereWinsAndOneLeftAloneFollowsTheFile() {
        val base = mapOf("sounds" to "true", "theme" to "\"dark\"")
        val out = settings(mapOf("sounds" to "false", "theme" to "\"dark\""), mapOf("sounds" to "true", "theme" to "\"light\""), base)
        assertEquals(mapOf("sounds" to "false", "theme" to "\"light\""), out)
    }

    @Test fun aJoiningDeviceTakesTheFilesSettings() {
        // No base yet (first sync, or the first sync after updating): the file wins wherever it has a value.
        val out = settings(mapOf("sounds" to "false", "haptics" to "true"), mapOf("sounds" to "true"), null)
        assertEquals(mapOf("haptics" to "true", "sounds" to "true"), out)
        // Same for a setting added in an update that the base doesn't know yet.
        assertEquals(mapOf("sounds" to "true"), settings(mapOf("sounds" to "false"), mapOf("sounds" to "true"), mapOf("theme" to "\"dark\"")))
    }

    @Test fun settingsThisDeviceDoesntHavePassThrough() {
        val out = settings(mapOf("sounds" to "true"), mapOf("sounds" to "true", "indicatorStyle" to "\"bars\""), mapOf("sounds" to "true"))
        assertEquals("\"bars\"", out["indicatorStyle"])
    }

    @Test fun aValueThisDeviceCantUseIsKeptInTheFileNotApplied() {
        val usable = { k: String, v: String -> !(k == "stt" && v == "\"DEEPGRAM\"") }
        // Even changed here: a newer device's choice stays in the file until it's one this device knows.
        val out = settings(mapOf("stt" to "\"GROQ\""), mapOf("stt" to "\"DEEPGRAM\""), mapOf("stt" to "\"OPENAI\""), usable)
        assertEquals("\"DEEPGRAM\"", out["stt"])
    }

    @Test fun settingsSurviveTheFileAndTheBase() {
        val s = mapOf(
            "accent" to "4294945083", "learnedWords" to "[\"Kowalski\"]", "sounds" to "true",
            "sttModels" to "{\"GROQ\":\"whisper-large-v3\"}", "theme" to "\"dark\"",
        )
        val state = SyncState(settings = s)
        val r = SyncFormat.parse(SyncFormat.encode(state, null, 1, "Android"))!!
        assertEquals(s, r.settings)
        assertEquals(s, SyncFormat.parseBase(SyncFormat.encodeBase(state), "").settings)
        assertTrue(SyncMerge.sameContent(state, r))
    }

    @Test fun canonicalValuesCompareEqual() {
        assertEquals("{\"a\":1,\"b\":[true,\"x\"]}", SyncFormat.canon(org.json.JSONObject("{\"b\":[true,\"x\"],\"a\":1.0,\"c\":\"\"}")))
        assertEquals("4294093115", SyncFormat.canon(0xFFF2A93BL))
        assertNull(SyncFormat.canon(null))
    }

    @Test fun aFileWithoutSettingsKeepsThisDevicesAndWritesThem() {
        val local = SyncState(settings = mapOf("sounds" to "true"))
        val res = merge(local, remote(), SyncState())
        assertEquals(mapOf("sounds" to "true"), res.state.settings)
        assertTrue(res.write)
    }

    // ---------- history ----------

    private fun entry(id: Long, text: String = "said $id", own: Boolean = false) =
        SyncEntry(id, id, text, text, 1000, true, "com.whatsapp", "WhatsApp", own)

    @Test fun historyIsJoinedAndDeletionsSpread() {
        val one = entry(1); val two = entry(2); val three = entry(3)
        // First time: everything from both sides, nothing deleted.
        val first = SyncMerge.history(listOf(two, one), listOf(three, one), emptyMap())
        assertEquals(listOf(three, two, one), first.file)
        assertEquals(listOf(three), first.put)
        assertTrue(first.delete.isEmpty())
        // Later: 1 deleted here, 3 deleted elsewhere.
        val base = mapOf(1L to one.hash, 2L to two.hash, 3L to three.hash)
        val second = SyncMerge.history(listOf(three, two), listOf(two, one), base)
        assertEquals(listOf(two), second.file)
        assertEquals(setOf(3L), second.delete)
        assertTrue(second.put.isEmpty())
    }

    @Test fun historyEditMadeElsewhereComesIn() {
        val mine = entry(1)
        val edited = entry(1, "my own words", own = true)
        val res = SyncMerge.history(listOf(mine), listOf(edited), mapOf(1L to mine.hash))
        assertEquals(listOf(edited), res.file)
        assertEquals(listOf(edited), res.put)
        // Edited here instead: this device's version goes in the file.
        val back = SyncMerge.history(listOf(edited), listOf(mine), mapOf(1L to mine.hash))
        assertEquals(listOf(edited), back.file)
        assertTrue(back.put.isEmpty())
    }

    @Test fun historyKeepsTheNewestAndOneThatFellOffIsntDeleted() {
        val max = SyncMerge.HISTORY_MAX.toLong()
        val all = (1..max + 5).map { entry(it) }.reversed()
        val first = SyncMerge.history(all, null, emptyMap())
        assertEquals(SyncMerge.HISTORY_MAX, first.file.size)
        assertEquals(max + 5, first.file.first().id)
        // Another device had entry 3 in its base from back when the file still held it: it fell off, it wasn't deleted.
        val other = SyncMerge.history(listOf(entry(3)), first.file, mapOf(3L to entry(3).hash))
        assertTrue(other.delete.isEmpty())
    }

    @Test fun historySurvivesTheFile() {
        val h = listOf(entry(2, own = true), entry(1))
        val text = SyncFormat.encode(SyncState(), null, 1, "Android", history = h)
        assertEquals(h, SyncFormat.parse(text)!!.history)
        assertNull(SyncFormat.parse(SyncFormat.encode(SyncState(), null, 1, "Android"))!!.history)
        assertEquals(mapOf(2L to h[0].hash, 1L to h[1].hash), SyncFormat.parseHistoryBase(SyncFormat.encodeHistoryBase(h)))
    }

    @Test fun cleanupCountsSurviveTheFile() {
        val usage = mapOf("phone" to DeviceUsage("Android", 1, mapOf("2026-10" to UsageMonth(500, 20, fillers = 7, corrections = 2, offline = 3))))
        assertEquals(usage, SyncFormat.parse(SyncFormat.encode(SyncState(), null, 1, "Android", usage))!!.usage)
    }

    @Test fun fnvMatchesTheDesktopApps() {
        // The same string hashes the same in C# (Sync.Fnv); the value is FNV-1a 32-bit.
        assertEquals(0x4f9f2cab, SyncMerge.fnv("hello"))
    }
}

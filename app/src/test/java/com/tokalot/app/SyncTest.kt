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
}

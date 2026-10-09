package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure logic added with cancel/retry, backup validation, themed colors and the pinned model. */
class LogicTest {

    // ---------- quick mode: what may skip the AI cleanup ----------

    private fun q(t: String, snip: Boolean = false, style: String = "CASUAL", cat: String = "OTHER", custom: String = "") =
        TextTools.nothingToFix(t, snip, style, cat, custom)

    @Test fun quickModeSkipsOnlyShortCleanSentences() {
        assertTrue(q("Can you send me the report by Friday?"))
        assertTrue(q("Sounds good, see you then.", cat = "MESSAGING"))
        assertTrue(q("Thanks for the update.", custom = DEFAULT_INSTRUCTIONS))
        assertFalse(q("Um, can you send me the report?"))
        assertFalse(q("Send it Tuesday, no wait, Wednesday."))
        assertFalse(q("I I think that works."))
        assertFalse(q("I mean it could work."))
        assertFalse(q("Dear Sam comma thanks for the note"))
        assertFalse(q("Add milk new line add eggs"))
        assertFalse(q("That was funny lol"))
        assertFalse(q(List(21) { "word" }.joinToString(" ")))
        assertFalse(q("Here is my address.", snip = true))
        assertFalse(q("Thanks for the update.", cat = "EMAIL"))
        assertFalse(q("Thanks for the update.", style = "VERY_CASUAL"))
        assertFalse(q("Thanks for the update.", custom = "Always use British spelling."))
        assertFalse(q("Ask Stewart, S-T-E-W-A-R-T, about it.")) // spelled out: the AI writes the word once
        assertFalse(q("It's spelled the usual way."))
    }

    // ---------- spacing around inserted text ----------

    @Test fun padAddsSpaceAfterAWord() {
        assertEquals(" hello", TextTools.pad("hello", 'd', null))
        assertEquals(" hello", TextTools.pad("hello", '.', null))
    }

    @Test fun padLeavesStartOfFieldAndExistingSpaceAlone() {
        assertEquals("hello", TextTools.pad("hello", null, null))
        assertEquals("hello", TextTools.pad("hello", ' ', null))
        assertEquals("hello", TextTools.pad("hello", '\n', null))
    }

    @Test fun padSeparatesFromFollowingWordButNotPunctuation() {
        assertEquals("hello ", TextTools.pad("hello", null, 'w'))
        assertEquals("hello", TextTools.pad("hello", null, '.'))
        assertEquals("hello", TextTools.pad("hello", null, ' '))
        assertEquals(" hello ", TextTools.pad("hello", 'a', '1'))
    }

    // ---------- timeouts ----------

    @Test fun deadlineScalesWithAudioAndIsCapped() {
        assertEquals(30_000L, Timeouts.deadlineMs(0))
        assertEquals(50_000L, Timeouts.deadlineMs(10_000))
        assertEquals(600_000L, Timeouts.deadlineMs(300_000))
        assertEquals(600_000L, Timeouts.deadlineMs(3_600_000))
        assertEquals(30_000L, Timeouts.deadlineMs(-5))
    }

    @Test fun sttReadTimeoutScalesAndIsCapped() {
        assertEquals(20_000, Timeouts.sttReadMs(0))
        assertEquals(50_000, Timeouts.sttReadMs(60_000))
        assertEquals(120_000, Timeouts.sttReadMs(300_000))
    }

    // ---------- cancelling ----------

    @Test fun cancelledCallRefusesNewWork() {
        val call = Call()
        call.check() // fine before
        call.cancel()
        assertTrue(call.cancelled)
        try {
            call.check()
            fail("expected CancelledException")
        } catch (_: CancelledException) {}
    }

    // ---------- upload body ----------

    @Test fun multipartHeadNamesTheFileAndItsType() {
        val audio = Upload("audio.m4a", "audio/mp4", 3) {}
        val head = CloudStt.head("B", "whisper-x", "Kubernetes", english = true, audio = audio)
        assertTrue(head.startsWith("--B\r\n"))
        assertTrue(head.contains("name=\"model\"\r\n\r\nwhisper-x\r\n"))
        assertTrue(head.contains("name=\"language\"\r\n\r\nen\r\n"))
        assertTrue(head.contains("name=\"prompt\"\r\n\r\nKubernetes\r\n"))
        assertTrue(head.endsWith("filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"))
    }

    @Test fun multipartHeadOmitsLanguageAndEmptyPrompt() {
        val head = CloudStt.head("B", "m", " ", english = false, audio = Upload("audio.wav", "audio/wav", 0) {})
        assertFalse(head.contains("language"))
        assertFalse(head.contains("prompt"))
    }

    /** Whisper's stock phrase for noise is dropped only when Whisper itself rated the audio as silence. */
    @Test fun strayThankYouIsDroppedButASaidOneIsKept() {
        fun reply(text: String, noSpeech: Double) =
            org.json.JSONObject().put("text", text).put("segments", org.json.JSONArray().put(org.json.JSONObject().put("no_speech_prob", noSpeech)))
        assertEquals("", CloudStt.text(reply(" Thank you.", 0.8)))
        assertEquals(" Thank you.", CloudStt.text(reply(" Thank you.", 0.05)))
        assertEquals("Call me", CloudStt.text(reply("Call me", 0.9)))
        assertEquals(" Thank you.", CloudStt.text(org.json.JSONObject().put("text", " Thank you."))) // plain json: no way to tell
        assertTrue(CloudStt.head("B", "whisper-large-v3-turbo", "", true, Upload("a.wav", "audio/wav", 0) {}, "verbose_json").contains("verbose_json"))
    }

    @Test fun wavUploadLengthMatchesItsBytes() {
        val up = Upload.wav(FloatArray(160))
        val out = java.io.ByteArrayOutputStream()
        up.write(out)
        assertEquals(44 + 320, out.size())
        assertEquals(out.size().toLong(), up.length)
    }

    // ---------- history ----------

    @Test fun historyRoundTripKeepsStatus() {
        val list = listOf(
            Entry(2, 2, "Transcription cancelled", "", 4000, false, "com.whatsapp", History.CANCELLED),
            Entry(1, 1, "Hello there", "hello there", 1500, true, "com.google.android.gm"),
        )
        val back = History.parse(History.toJson(list))
        assertEquals(list, back)
        assertTrue(back[0].pending)
        assertFalse(back[1].pending)
    }

    @Test fun failuresFromOlderVersionsBecomeRetryable() {
        val old = """[{"id":5,"time":5,"text":"Transcription failed: HTTP 500","raw":"","dur":1000,"ai":false,"app":""},
            {"id":4,"time":4,"text":"Transcription failed: is what the log said","raw":"transcription failed is what the log said","dur":1000,"ai":true,"app":""}]"""
        val list = History.parse(old)
        assertEquals(History.FAILED, list[0].status)
        assertEquals("", list[1].status) // a real dictation that merely starts with those words
    }

    @Test fun malformedHistoryThrows() {
        for (bad in listOf("{}", "[{\"id\":1}]", "not json", "[1,2]")) {
            try {
                History.parse(bad)
                fail("expected a failure for: $bad")
            } catch (_: Exception) {}
        }
    }

    // ---------- backup manifest ----------

    private fun manifest(prefs: String) =
        """{"format":1,"created":1,"appVersion":"1.8","includesKeys":true,"prefs":$prefs}"""

    @Test fun goodManifestIsTyped() {
        val m = Backup.parseManifest(
            manifest("""{"settings":{"hold":{"t":"b","v":true},"accent":{"t":"i","v":-1},"theme":{"t":"s","v":"dark"}},"updates":{"x":{"t":"q","v":1}}}""")
        )
        assertTrue(m.hasKeys)
        val s = m.prefs.getValue("settings")
        assertEquals(true, s["hold"])
        assertEquals(-1, s["accent"])
        assertEquals("dark", s["theme"])
        assertNull(m.prefs["updates"]) // files the app doesn't back up are ignored
    }

    @Test fun wrongTypedValuesAreRejected() {
        val bad = listOf(
            """{"settings":{"hold":{"t":"b","v":"yes"}}}""",
            """{"settings":{"accent":{"t":"i","v":"red"}}}""",
            """{"settings":{"theme":{"t":"s","v":5}}}""",
            """{"settings":{"theme":{"t":"zzz","v":5}}}""",
            """{"settings":{"theme":"dark"}}""",
            """{"usage":{"2026-01":{"t":"s"}}}""",
        )
        for (p in bad) {
            try {
                Backup.parseManifest(manifest(p))
                fail("expected rejection of: $p")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun nonBackupsAndNewerFormatsAreRejected() {
        val bad = listOf("", "hello", "[]", "{}", """{"format":1}""", """{"format":"1","prefs":{}}""", """{"format":2,"prefs":{}}""")
        for (j in bad) {
            try {
                Backup.parseManifest(j)
                fail("expected rejection of: $j")
            } catch (_: IllegalArgumentException) {}
        }
    }

    // ---------- colors ----------

    @Test fun lightThemeSecondaryTextMeetsContrast() {
        C.apply(false)
        assertTrue(C.contrast(C.SUB, C.CARD) >= 4.5)
        assertTrue(C.contrast(C.SUB, C.BG) >= 4.5)
        assertEquals(21.0, C.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test fun whiteAccentIsSwappedOnlyOnTheLightTheme() {
        val white = 0xFFFFFFFF.toInt()
        C.apply(false)
        assertEquals(C.TEXT, C.visible(white))
        for ((_, color) in Accents.all) if (color != white) assertEquals(color, C.visible(color))
        C.apply(true)
        assertEquals(white, C.visible(white))
        C.apply(false)
    }

    // ---------- model ----------

    @Test fun pinnedModelHashIsAWellFormedSha256() {
        assertEquals(64, ModelManager.SHA256.length)
        assertTrue(ModelManager.SHA256.all { it in "0123456789abcdef" })
        assertEquals("00ff10", ModelManager.hex(byteArrayOf(0, -1, 16)))
    }
}

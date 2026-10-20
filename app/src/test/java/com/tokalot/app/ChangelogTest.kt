package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {

    private val sample = """
        # Tokalot for Android: what's new

        <!--
        ## 9.9
        - a comment, not a version
        -->

        ## 1.27 · 2026-10-08
        - A **new** banner.
        - Settings has a [changelog](https://example.com),
          on two lines.

        ## 1.26
        - Pieces in noisy places.

        ## 1.25
        Words that aren't a bullet are skipped.

        ## v1.24 · 2026-10-06
        - Quick mode.
    """.trimIndent()

    @Test fun parsesVersionsDatesAndItems() {
        val e = Changelog.parse(sample)
        assertEquals(listOf("1.27", "1.26", "1.24"), e.map { it.version }) // 1.25 has no items, 9.9 is in a comment
        assertEquals("2026-10-08", e[0].date)
        assertNull(e[1].date)
        assertEquals(listOf("A new banner.", "Settings has a changelog, on two lines."), e[0].items)
    }

    @Test fun sinceKeepsOnlyWhatTheUpdateBrings() {
        val e = Changelog.parse(sample)
        assertEquals(listOf("1.27", "1.26"), Changelog.since(e, installed = "1.24").map { it.version })
        assertEquals(listOf("1.26"), Changelog.since(e, installed = "1.24", upTo = "1.26").map { it.version })
        assertEquals(emptyList<String>(), Changelog.since(e, installed = "1.27").map { it.version })
        assertEquals(listOf("1.27"), Changelog.since(e, installed = "1.26").map { it.version })
    }

    @Test fun releaseTextDropsTheCompareLinkAndMarkdown() {
        assertEquals("", Changelog.releaseText("**Full Changelog**: https://github.com/x/y/compare/v1.24...v1.26"))
        assertEquals(
            "New in 1.2\n\n• Faster uploads",
            Changelog.releaseText("## New in 1.2\n\n\n\n- Faster **uploads**\n\n**Full Changelog**: https://x"),
        )
    }

    /** The release workflow and the app both read this file, so a released version must have its section. */
    @Test fun bundledChangelogCoversThisVersion() {
        val version = Regex("versionName = \"([^\"]+)\"").find(File("build.gradle.kts").readText())!!.groupValues[1]
        val entries = Changelog.parse(File("src/main/assets/${Changelog.FILE}").readText())
        assertTrue("CHANGELOG.md is empty", entries.isNotEmpty())
        if ('-' !in version) { // betas don't need their own section
            assertEquals("CHANGELOG.md needs a \"## $version\" section at the top", version, entries.first().version)
        }
        // Newest first, so the banner and Settings list read top-down.
        entries.zipWithNext().forEach { (a, b) -> assertTrue("${a.version} should come before ${b.version}", Updater.isNewer(a.version, b.version)) }
    }
}

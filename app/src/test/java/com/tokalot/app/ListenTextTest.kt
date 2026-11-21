package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTextTest {
    @Test fun articleComesOutOfAPage() {
        val html = """<html><head><title>How to calibrate a drill | Maker Journal</title>
            <meta property="og:site_name" content="Maker Journal"></head><body>
            <nav><p>Home</p><p>Subscribe</p></nav>
            <article><h1>How to calibrate a drill</h1>
            <p>Jack up one side so the drive wheel turns freely. ${"Count the turns it takes to cover a hundredth of an acre. ".repeat(4)}</p>
            <p>Catch the seed from two or three openers &amp; weigh it.</p>
            <script>var x = "<p>not this</p>";</script></article>
            <footer><p>© 2026</p></footer></body></html>"""
        val page = ListenText.page(html, "https://www.makerjournal.example/a/1")
        assertEquals("How to calibrate a drill", page.title)
        assertEquals("Maker Journal", page.site)
        assertEquals(3, page.blocks.size)
        assertEquals("Catch the seed from two or three openers & weigh it.", page.blocks[2])
    }

    @Test fun loneLinks() {
        assertEquals("https://example.com/a", ListenText.loneLink("Look at this https://example.com/a."))
        assertNull(ListenText.loneLink("https://a.com and https://b.com"))
        assertNull(ListenText.loneLink("word ".repeat(40) + "https://a.com"))
    }

    @Test fun pdfLinesBecomeParagraphs() {
        // Body lines differ page to page; only the header and page numbers repeat.
        fun long(n: Int) = "Line $n of the paragraph runs right across the page width of the document"
        val pages = listOf("Field Guide\n${long(1)}\n${long(2)}\nends here.\n1", "Field Guide\nNext part\n${long(3)}\nends too.\n2", "Field Guide\n${long(4)}\nlast.\n3")
        val blocks = ListenText.reflow(ListenText.withoutPageFurniture(pages))
        assertTrue(blocks.none { it == "Field Guide" || it == "1" })
        assertTrue(blocks.contains("Next part"))
        assertTrue(blocks.first().startsWith(long(1)) && blocks.first().endsWith("ends here."))
    }

    @Test fun wordParagraphs() {
        val xml = """<w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> there</w:t></w:r></w:p><w:p></w:p><w:p><w:r><w:t>Two &amp; three</w:t></w:r></w:p></w:body>"""
        assertEquals(listOf("Hello there", "Two & three"), ListenText.docxBlocks(xml))
    }

    @Test fun editorReply() {
        assertEquals(setOf(0, 1, 2, 7), ListenText.removed("REMOVE: 0-2, 7, 99", 20))
        assertEquals(emptySet<Int>(), ListenText.removed("REMOVE: none", 20))
        assertEquals(emptySet<Int>(), ListenText.removed("REMOVE: 0-19", 20)) // nearly everything: the reply went wrong
    }

    @Test fun piecesAndTimes() {
        val blocks = listOf("One two three.", "Four five.", "Six seven eight nine. " + "Ten eleven twelve. ".repeat(10))
        val pieces = ListenText.pieces(blocks, 60)
        assertTrue(pieces.all { it.first.length <= 60 })
        assertEquals(listOf(0, 1), pieces[0].second)
        val starts = ArrayList<Double>(); val lengths = ArrayList<Double>()
        var t = 0.0
        for (p in pieces) { starts += t; lengths += 2.0; t += 2.5 }
        val timed = ListenText.timeWords(blocks, pieces, starts, lengths)
        assertEquals(blocks.sumOf { b -> b.split(" ").count { it.isNotEmpty() } }, timed.words.size)
        assertEquals(3, timed.segments.size) // one Timeline point per paragraph, even one split over pieces
        assertEquals(setOf(3, 5), timed.breaks)
        assertEquals(blocks.joinToString("\n\n") { it.trim().replace(Regex("\\s+"), " ") }, NoteMedia.text(timed.words, timed.breaks))
        assertTrue(timed.words.zipWithNext().all { (a, b) -> b.start >= a.start })
    }

    @Test fun wavRoundTripAndResample() {
        val data = ByteArray(4800) { (it % 7).toByte() }
        val w = ListenText.readWav(ListenText.wav(data, 24000))!!
        assertEquals(24000, w.rate)
        assertEquals(0.1, w.seconds, 1e-9)
        assertEquals(4410, ListenText.resample(data, 24000, 22050).size)
        val stereo = ListenText.Wav(24000, 2, 16, byteArrayOf(10, 0, 20, 0))
        assertEquals(listOf<Byte>(15, 0), ListenText.mono16(stereo).toList())
    }
}

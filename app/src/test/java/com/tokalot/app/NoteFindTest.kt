package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Notes page's search, tag chips, sort and dates. The desktop apps' NoteFind is held to the same cases. */
class NoteFindTest {
    private val leak = NoteFind.Item(1, 300, "Found a water leak under the kitchen sink by the left valve.", "Kitchen sink water leak", listOf("House", "Repairs"))
    private val planter = NoteFind.Item(2, 200, "Parts list: two water hoses, 3/8 and 1/2, and washers.", "Parts list for the washer", listOf("Home"))
    private val call = NoteFind.Item(3, 100, "Call Dave about the gutter quote", null, emptyList())
    private val all = listOf(call, leak, planter)

    private fun ids(f: NoteFind.Filter) = NoteFind.apply(all, f).map { it.id }

    @Test fun sortsByDateOrTitle() {
        assertEquals(listOf(1L, 2L, 3L), ids(NoteFind.Filter()))
        assertEquals(listOf(3L, 2L, 1L), ids(NoteFind.Filter(sort = NoteFind.Sort.OLDEST)))
        assertEquals(listOf(1L, 2L, 3L), ids(NoteFind.Filter(sort = NoteFind.Sort.TITLE))) // untitled last
    }

    @Test fun searchFindsEveryWordInTitleTextOrTags() {
        assertEquals(listOf(1L, 2L), ids(NoteFind.Filter(query = "WATER")))
        assertEquals(listOf(2L), ids(NoteFind.Filter(query = "water hoses")))
        assertEquals(listOf(1L), ids(NoteFind.Filter(query = "repairs")))  // a tag
        assertEquals(listOf(3L), ids(NoteFind.Filter(query = "dave")))
        assertEquals(emptyList<Long>(), ids(NoteFind.Filter(query = "ladder")))
    }

    @Test fun tagsAndDatesNarrowIt() {
        assertEquals(listOf(1L), ids(NoteFind.Filter(tag = "house")))
        assertEquals(listOf(2L), ids(NoteFind.Filter(query = "water", tag = "Home")))
        assertEquals(listOf(2L, 3L), ids(NoteFind.Filter(from = 100, to = 300)))
    }

    @Test fun snippetShowsTheWordWithSomeAroundIt() {
        val s = NoteFind.snippet("a ".repeat(60) + "the hydraulic line by the left lift " + "b ".repeat(60), listOf("hydraulic"), 20)!!
        assertEquals('…', s.text.first())
        assertEquals('…', s.text.last())
        assertEquals("hydraulic", s.text.substring(s.hits.single().first, s.hits.single().last + 1))
        assertNull(NoteFind.snippet("nothing here", listOf("hydraulic")))
        assertEquals("Short note", NoteFind.snippet("Short note", listOf("note"))!!.text)
    }
}

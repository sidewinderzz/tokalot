package com.tokalot.app

/**
 * The keyboard's pages (letters, two of symbols, a number pad) as rows of keys, and where each key sits
 * once the keyboard's size is known. Plain Kotlin: [KeyboardView] draws it and the unit tests check it.
 */
object KeyCode {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4
    const val LETTERS = -5
    const val MORE_SYMBOLS = -6
    const val EMOJI = -7
    const val SPACE = 32
}

/**
 * One key. [code] is the character it types (or a [KeyCode]); [width] is in key widths (a letter is 1);
 * [popup] holds what a long press offers, first one first; [hint] is the small character shown in its corner.
 */
class Key(
    val code: Int,
    val label: String,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
    val hint: String? = null,
) {
    /** Where it's drawn. */
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    /** Where a touch counts as this key (wider than the drawn key at the ends of an indented row). */
    var hitLeft = 0f; var hitRight = 0f

    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val isLetter get() = code > 0 && Character.isLetter(code)
    /** Shift, delete, enter and the page keys: drawn on the darker key colour. */
    val isFunction get() = code < 0 || code == '\n'.code
}

/** [heightScale]: the row's height against an ordinary row's (the number row above the letters is shorter). */
class KeyRow(val keys: List<Key>, val padLeft: Float = 0f, val padRight: Float = 0f, val heightScale: Float = 1f)

enum class PageKind { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS, PHONE }

class KeyboardPage(val kind: PageKind, val rows: List<KeyRow>) {
    var width = 0f
        private set
    var height = 0f
        private set
    /** The width of one ordinary key, and of one row, in pixels, after [layout]. */
    var unit = 0f
        private set
    var rowHeight = 0f
        private set
    /** Each row's top edge, then the bottom of the last. */
    private var rowTops = FloatArray(0)
    /** The top of the first row of letters (below the number row, if there is one). */
    val lettersTop: Float
        get() = rowTops.getOrElse(rows.indexOfFirst { row -> row.keys.any { it.isLetter } }.coerceAtLeast(0)) { 0f }

    /** The page's height for rows [rowHeight] high, before [layout]. */
    fun heightFor(rowHeight: Float) = rowHeight * rows.sumOf { it.heightScale.toDouble() }.toFloat()

    val keys get() = rows.flatMap { it.keys }

    /**
     * Places every key in a [width] × [heightFor] area, ordinary rows [rowHeight] high. [gap] is the space between keys; each
     * key's touch area takes in half the gap around it, so there is no dead space between keys.
     */
    fun layout(width: Float, rowHeight: Float, gap: Float) {
        this.width = width
        this.rowHeight = rowHeight
        height = heightFor(rowHeight)
        rowTops = FloatArray(rows.size + 1)
        for (r in rows.indices) rowTops[r + 1] = rowTops[r] + rowHeight * rows[r].heightScale
        val units = rows.maxOf { r -> r.padLeft + r.padRight + r.keys.sumOf { it.width.toDouble() }.toFloat() }
        unit = width / units
        rows.forEachIndexed { r, row ->
            var x = row.padLeft * unit
            // A row narrower than the widest (the indented middle letter row) is centred.
            val rowUnits = row.padLeft + row.padRight + row.keys.sumOf { it.width.toDouble() }.toFloat()
            x += (units - rowUnits) * unit / 2
            row.keys.forEachIndexed { i, k ->
                val kw = k.width * unit
                k.x = x + gap / 2; k.w = kw - gap
                k.y = rowTops[r] + gap / 2; k.h = rowHeight * row.heightScale - gap
                k.hitLeft = if (i == 0) 0f else x
                k.hitRight = if (i == row.keys.size - 1) width else x + kw
                x += kw
            }
        }
    }

    /** The key under a touch at ([x], [y]), or null below or above the keys. */
    fun keyAt(x: Float, y: Float): Key? {
        if (rows.isEmpty() || rowHeight <= 0f) return null
        if (y < 0 || y >= height) return null
        val r = (rows.indices).lastOrNull { rowTops[it] <= y } ?: return null
        return rows[r].keys.firstOrNull { x >= it.hitLeft && x < it.hitRight } ?: rows[r].keys.lastOrNull()
    }

    /** Letter key centres in key widths and rows, for [Autocorrect.centers]. */
    fun letterCenters(): Map<Char, FloatArray> {
        val out = HashMap<Char, FloatArray>()
        if (unit <= 0f) return Qwerty.centers
        val top = lettersTop
        for (k in keys) if (k.isLetter) out[k.code.toChar()] = floatArrayOf(k.cx / unit, (k.cy - top) / rowHeight - 0.5f)
        return out
    }

    /** Letter key centres in pixels, for [Glide]. */
    fun letterPixels(): Map<Char, FloatArray> =
        keys.filter { it.isLetter }.associate { it.code.toChar() to floatArrayOf(it.cx, it.cy) }
}

object Layouts {
    /** The number row's height against a letter row's. */
    const val NUMBER_ROW = 0.78f
    private val ACCENTS = mapOf(
        'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "èéêëēęė", 'i' to "ìíîïīį", 'n' to "ñń",
        'o' to "òóôöõœøō", 's' to "ßśš", 'u' to "ùúûüū", 'y' to "ÿý", 'z' to "žźż", 'l' to "ł",
    )
    /** The long-press symbol on each letter, as on Gboard; the top row has its digits instead. */
    private val SYMBOL = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5", 'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
    )
    val PERIOD_POPUP = listOf(",", "?", "!", "'", "\"", ":", ";", "-", "/", "@", "#", "&", "(", ")")

    private fun letter(c: Char, symbols: Boolean): Key {
        val sym = SYMBOL[c]
        val popup = buildList {
            if (sym != null && (symbols || sym[0].isDigit())) add(sym)
            ACCENTS[c]?.forEach { add(it.toString()) }
        }
        return Key(c.code, c.toString(), popup = popup, hint = sym?.takeIf { symbols || it[0].isDigit() })
    }

    private fun chars(s: String) = s.map { Key(it.code, it.toString()) }

    private fun bottomRow(page: PageKind): KeyRow {
        val switch = when (page) {
            PageKind.LETTERS -> Key(KeyCode.SYMBOLS, "?123", 1.5f)
            else -> Key(KeyCode.LETTERS, "ABC", 1.5f)
        }
        return KeyRow(listOf(
            switch,
            Key(','.code, ","),
            Key(KeyCode.EMOJI, "emoji"),
            Key(KeyCode.SPACE, "", 4f),
            Key('.'.code, ".", popup = PERIOD_POPUP),
            Key(KeyCode.ENTER, "enter", 1.5f),
        ))
    }

    /** [symbolHints]: show and offer each letter's symbol on long press (the top row's digits always). */
    fun letters(numberRow: Boolean, symbolHints: Boolean): KeyboardPage {
        val rows = ArrayList<KeyRow>()
        // Shorter than the letter rows, as on Gboard; it stays in proportion when the keyboard's height changes.
        if (numberRow) rows += KeyRow(chars("1234567890"), heightScale = NUMBER_ROW)
        rows += KeyRow(Qwerty.rows[0].map { letter(it, symbolHints).let { k -> if (numberRow) Key(k.code, k.label, popup = k.popup.filter { p -> !p[0].isDigit() }) else k } })
        rows += KeyRow(Qwerty.rows[1].map { letter(it, symbolHints) }, 0.5f, 0.5f)
        rows += KeyRow(listOf(Key(KeyCode.SHIFT, "shift", 1.5f)) + Qwerty.rows[2].map { letter(it, symbolHints) } + Key(KeyCode.DELETE, "delete", 1.5f))
        rows += bottomRow(PageKind.LETTERS)
        return KeyboardPage(PageKind.LETTERS, rows)
    }

    fun symbols(): KeyboardPage = KeyboardPage(PageKind.SYMBOLS, listOf(
        KeyRow(chars("1234567890")),
        KeyRow(chars("@#\$_&-+()/")),
        KeyRow(listOf(Key(KeyCode.MORE_SYMBOLS, "=\\<", 1.5f)) + chars("*\"':;!?") + Key(KeyCode.DELETE, "delete", 1.5f)),
        bottomRow(PageKind.SYMBOLS),
    ))

    fun moreSymbols(): KeyboardPage = KeyboardPage(PageKind.MORE_SYMBOLS, listOf(
        KeyRow(chars("~`|•√π÷×¶∆")),
        KeyRow(chars("£¢€¥^°={}\\")),
        KeyRow(listOf(Key(KeyCode.SYMBOLS, "?123", 1.5f)) + chars("%©®™✓[]") + Key(KeyCode.DELETE, "delete", 1.5f)),
        bottomRow(PageKind.MORE_SYMBOLS),
    ))

    /** The number pad for number and phone fields. */
    fun numbers(phone: Boolean): KeyboardPage {
        fun k(s: String, popup: List<String> = emptyList()) = Key(s[0].code, s, 2.5f, popup)
        return KeyboardPage(if (phone) PageKind.PHONE else PageKind.NUMBERS, listOf(
            KeyRow(listOf(k("1"), k("2"), k("3"), if (phone) k("(") else k("-"))),
            KeyRow(listOf(k("4"), k("5"), k("6"), if (phone) k(")") else k(","))),
            KeyRow(listOf(k("7"), k("8"), k("9"), Key(KeyCode.DELETE, "delete", 2.5f))),
            KeyRow(listOf(
                if (phone) k("*", listOf("#", "-", "+", ",", ";")) else Key(KeyCode.SYMBOLS, "?123", 2.5f),
                k("0", listOf("+")),
                if (phone) k("#") else k(".", listOf("-", ",")),
                Key(KeyCode.ENTER, "enter", 2.5f),
            )),
        ))
    }
}

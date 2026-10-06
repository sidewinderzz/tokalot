package com.tokalot.app

/** Plain-code text handling: noise stripping, the no-AI fallback cleanup, and snippets. */
object TextTools {
    private val NOISE = Regex("""\[[^\]]*]|\((?:music|laughs?|applause|silence|inaudible|blank_audio|noise)[^)]*\)|\*[^*]*\*""", RegexOption.IGNORE_CASE)
    private val FILLERS = Regex("""(?i)\b(?:um+|uh+|erm|hmm+)\b[,.]?\s*""") // not "er"/"mm": real words (the ER, 5 mm)
    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.!?;:])""")

    /** Removes Whisper's sound tags like [BLANK_AUDIO] or (music). */
    fun stripNoise(s: String) = s.replace(NOISE, " ").replace(SPACES, " ").trim()

    private val NOT_LETTERS = Regex("""[^a-z ]""")
    private val PHANTOMS = setOf(
        "thank you", "thank you very much", "thank you for watching", "thanks for watching", "you", "bye", "beep",
    )

    /** Whisper invents these for silence. Only trusted as "nothing said" when barely any sound was heard. */
    fun isPhantom(s: String) = s.lowercase().replace(NOT_LETTERS, " ").replace(SPACES, " ").trim() in PHANTOMS

    /** The fallback when AI cleanup is off or unreachable. */
    fun basicClean(s: String) = s.replace(FILLERS, "")
        .replace(SPACES, " ")
        .replace(SPACE_BEFORE_PUNCT, "$1")
        .trim()
        .replaceFirstChar { it.uppercaseChar() }

    /**
     * Adds the spaces a dictated piece needs so it doesn't glue onto what's around the cursor.
     * [before] / [after] are the characters on either side of the cursor (null at the ends of the field).
     */
    fun pad(text: String, before: Char?, after: Char?): String {
        val spaceBefore = before != null && !before.isWhitespace()
        val spaceAfter = after != null && after.isLetterOrDigit()
        return (if (spaceBefore) " " else "") + text + (if (spaceAfter) " " else "")
    }

    fun wordCount(s: String) = s.split(SPACES).count { it.isNotBlank() }

    // Things only the AI cleanup can deal with: fillers, self-corrections, spoken punctuation and formatting.
    private val NEEDS_AI = Regex("""(?i)\b(?:um+|uh+|er+m?|hmm+|you know|i mean|actually|scratch that|no wait|wait no|sorry|or rather|correction|let me rephrase|new line|new paragraph|next line|bullet|number (?:one|two|three|four|five|\d+)|first(?:ly)?|second(?:ly)?|third(?:ly)?|comma|period|full stop|question mark|exclamation (?:point|mark)|colon|semicolon|quote|unquote|open paren\w*|close paren\w*|dash|hyphen|slash|at sign|dot com|hashtag|emoji|all caps|capital|lol)\b""")
    private val STUTTER = Regex("""(?i)\b(\w+)[ ,]+\1\b""")
    const val QUICK_WORDS = 20

    /**
     * True when a short dictation came out of speech recognition already fit to type, so the AI cleanup
     * (the slower half of a short dictation) has nothing to do: no fillers, corrections, repeats or spoken
     * formatting, no snippets, a style that is just normal capitals and punctuation, and no instructions
     * of the user's own. Email is left out because its greeting and sign-off are laid out by the cleanup.
     */
    fun nothingToFix(text: String, hasSnippets: Boolean, style: String, category: String, custom: String): Boolean {
        if (hasSnippets || text.isEmpty() || wordCount(text) > QUICK_WORDS) return false
        if (style != "FORMAL" && style != "CASUAL") return false
        if (category == "EMAIL") return false
        val own = custom.trim()
        if (own.isNotEmpty() && own != DEFAULT_INSTRUCTIONS) return false
        if ('\n' in text) return false
        return !NEEDS_AI.containsMatchIn(text) && !STUTTER.containsMatchIn(text)
    }

    // ---------- snippets ----------

    private fun triggerRegex(trigger: String): Regex {
        val parts = trigger.trim().split(SPACES).filter { it.isNotEmpty() }.map { Regex.escape(it) }
        // Whisper may add commas/periods between spoken words, so allow punctuation between them.
        return Regex("""(?i)(?<![\w])""" + parts.joinToString("""[\s,.!?;:\-]*""") + """(?![\w])""")
    }

    /**
     * Swaps spoken trigger phrases for placeholders like {{SNIP1}} before AI cleanup,
     * so the model can't reword the snippet text. Returns the text and placeholder map.
     */
    fun protect(text: String, snippets: List<Snippet>): Pair<String, Map<String, String>> {
        var out = text
        val map = LinkedHashMap<String, String>()
        // Longest triggers first so "my home address" wins over "address".
        snippets.filter { it.trigger.isNotBlank() }.sortedByDescending { it.trigger.length }.forEach { sn ->
            val re = triggerRegex(sn.trigger)
            if (re.containsMatchIn(out)) {
                val token = "{{SNIP${map.size + 1}}}"
                map[token] = sn.text
                out = out.replace(re, token)
            }
        }
        return out to map
    }

    /** Puts snippet text back. If the model dropped a placeholder, append it rather than lose it. */
    fun restore(text: String, map: Map<String, String>): String {
        var out = text
        for ((token, value) in map) {
            out = if (out.contains(token)) out.replace(token, value) else "$out $value".trim()
        }
        return out
    }
}

package com.tokalot.app

/** Plain-code text handling: noise stripping, the no-AI fallback cleanup, and snippets. */
object TextTools {
    private val NOISE = Regex("""\[[^\]]*]|\((?:music|laughs?|applause|silence|inaudible|blank_audio|noise)[^)]*\)|\*[^*]*\*""", RegexOption.IGNORE_CASE)
    private val FILLERS = Regex("""(?i)\b(?:um+|uh+|erm?|hmm+|mm+)\b[,.]?\s*""")
    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.!?;:])""")

    /** Removes Whisper's sound tags like [BLANK_AUDIO] or (music). */
    fun stripNoise(s: String) = s.replace(NOISE, " ").replace(SPACES, " ").trim()

    /** The fallback when AI cleanup is off or unreachable. */
    fun basicClean(s: String) = s.replace(FILLERS, "")
        .replace(SPACES, " ")
        .replace(SPACE_BEFORE_PUNCT, "$1")
        .trim()
        .replaceFirstChar { it.uppercaseChar() }

    fun wordCount(s: String) = s.split(SPACES).count { it.isNotBlank() }

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

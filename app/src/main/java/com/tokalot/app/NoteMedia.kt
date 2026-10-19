package com.tokalot.app

/**
 * The parts of a shared voice note that need no phone: lining Whisper's word timings up with its punctuated text,
 * the stretches playback can skip (silence, "um"), and the prompts and parsing for summaries and the index.
 * Ported from WAtranscribe (condense.py, outline.py, claude_integration.py). Times are in seconds.
 */
object NoteMedia {
    /** One word as shown: its punctuated text and when it was said. */
    data class Word(val text: String, val start: Double, val end: Double)

    /** A stretch of speech as Whisper split it. */
    data class Segment(val start: Double, val end: Double, val text: String)

    /** One line of the index: when the point was made, its topic (may be blank) and the point. */
    data class Point(val time: Double, val topic: String, val text: String)

    private val STRIP = Regex("[^a-z0-9'-]")
    fun bare(word: String) = STRIP.replace(word.lowercase(), "")

    /**
     * The words to show, with times. Whisper's word list has no punctuation and its segments have no word times,
     * so each word of the segment text takes the time of the matching timed word (looking a few ahead, since the two
     * can split numbers or contractions differently). A word with no match sits between its neighbours. With no
     * timed words at all, each segment's time is shared out evenly over its words.
     */
    fun align(segments: List<Segment>, timed: List<Word>): List<Word> {
        val out = ArrayList<Word>()
        var j = 0
        for (seg in segments) {
            val tokens = seg.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            if (timed.isEmpty()) {
                val step = (seg.end - seg.start).coerceAtLeast(0.0) / tokens.size
                tokens.forEachIndexed { i, t -> out.add(Word(t, seg.start + i * step, seg.start + (i + 1) * step)) }
                continue
            }
            for (t in tokens) {
                val b = bare(t)
                var hit = -1
                if (b.isNotEmpty()) {
                    for (k in j until minOf(j + 4, timed.size)) if (bare(timed[k].text) == b) { hit = k; break }
                }
                if (hit >= 0) {
                    out.add(Word(t, timed[hit].start, timed[hit].end))
                    j = hit + 1
                } else {
                    val from = out.lastOrNull()?.end ?: seg.start
                    val to = timed.getOrNull(j)?.start?.coerceAtLeast(from) ?: maxOf(from, seg.end)
                    out.add(Word(t, from, to))
                }
            }
        }
        return out
    }

    /** The transcript as text, with a new paragraph wherever the speaker paused for [pause] seconds or more. */
    fun text(words: List<Word>, pause: Double = 2.0): String = buildString {
        words.forEachIndexed { i, w ->
            if (i > 0) append(if (w.start - words[i - 1].end >= pause) "\n\n" else " ")
            append(w.text)
        }
    }

    /** The index of the word playing at [t], or -1 before the first. */
    fun wordAt(words: List<Word>, t: Double): Int {
        var lo = 0
        var hi = words.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (words[mid].start <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    // ---------- what playback can skip ----------

    /** Sounds that are never a word. */
    private val DISFLUENCIES = setOf(
        "uh", "uhh", "uhhh", "um", "umm", "ummm", "er", "err", "erm", "ah", "ahh",
        "eh", "hm", "hmm", "hmmm", "mm", "mmm", "mhm", "mmhm", "uh-huh", "huh",
    )

    /** Real words that are filler only when set off by a comma or a pause ("I like that" must survive). */
    private val SOFT_FILLERS = setOf(
        "like", "basically", "literally", "actually", "honestly", "obviously",
        "seriously", "anyway", "right", "well", "so",
    )
    private val SOFT_PHRASES = listOf("you" to "know", "i" to "mean", "sort" to "of", "kind" to "of", "you" to "see")

    private const val SET_OFF_PAUSE = 0.18
    /** Gaps shorter than this are the rhythm of speech; cutting them sounds rushed. */
    const val MIN_GAP = 0.45
    /** Left on each side of a cut so a jump never clips the start of a word. */
    private const val PAD = 0.06

    private fun setOff(words: List<Word>, i: Int): Boolean {
        val raw = words[i].text.trim()
        if (raw.isNotEmpty() && raw.last() in ",.!?…;") return true
        val before = if (i > 0) words[i].start - words[i - 1].end else SET_OFF_PAUSE
        val after = if (i + 1 < words.size) words[i + 1].start - words[i].end else SET_OFF_PAUSE
        return before >= SET_OFF_PAUSE || after >= SET_OFF_PAUSE
    }

    fun fillerIndices(words: List<Word>): Set<Int> {
        val found = HashSet<Int>()
        words.forEachIndexed { i, w ->
            val b = bare(w.text)
            if (b in DISFLUENCIES) found.add(i)
            else if (b in SOFT_FILLERS && setOff(words, i)) found.add(i)
        }
        for (i in 0 until words.size - 1) {
            val pair = bare(words[i].text) to bare(words[i + 1].text)
            if (pair in SOFT_PHRASES && (setOff(words, i) || setOff(words, i + 1))) { found.add(i); found.add(i + 1) }
        }
        return found
    }

    /** Sorted, joined where they overlap or touch, and without the negligible ones. */
    fun merge(ranges: List<ClosedFloatingPointRange<Double>>, minLength: Double = 0.08): List<ClosedFloatingPointRange<Double>> {
        val usable = ranges.filter { it.endInclusive - it.start >= minLength }.sortedBy { it.start }
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        for (r in usable) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.endInclusive + 0.02) out[out.size - 1] = last.start..maxOf(last.endInclusive, r.endInclusive)
            else out.add(r)
        }
        return out
    }

    /** Stretches with no speech: before the first word, between words, and after the last. */
    fun gapCuts(words: List<Word>, duration: Double = 0.0): List<ClosedFloatingPointRange<Double>> {
        if (words.isEmpty()) return emptyList()
        val cuts = ArrayList<ClosedFloatingPointRange<Double>>()
        if (words[0].start > MIN_GAP) cuts.add(0.0..(words[0].start - PAD))
        for (i in 1 until words.size) {
            if (words[i].start - words[i - 1].end >= MIN_GAP) cuts.add((words[i - 1].end + PAD)..(words[i].start - PAD))
        }
        if (duration > 0 && duration - words.last().end > MIN_GAP) cuts.add((words.last().end + PAD)..duration)
        return merge(cuts)
    }

    fun fillerCuts(words: List<Word>): List<ClosedFloatingPointRange<Double>> =
        merge(fillerIndices(words).sorted().map { maxOf(0.0, words[it].start - 0.02)..(words[it].end + 0.02) }, minLength = 0.03)

    /** Where playback at [t] should jump to, or null when [t] isn't inside a cut. */
    fun skipTo(cuts: List<ClosedFloatingPointRange<Double>>, t: Double): Double? =
        cuts.firstOrNull { t >= it.start && t < it.endInclusive - 0.01 }?.endInclusive

    fun total(cuts: List<ClosedFloatingPointRange<Double>>) = cuts.sumOf { it.endInclusive - it.start }

    // ---------- summaries ----------

    /** Detail level 1 to 5: the instruction, how many bullet points, and the output cap. */
    private val LEVELS = mapOf(
        1 to Triple("Capture only the single most important thing said.", 0, 400),
        2 to Triple("Capture the main points.", 2, 700),
        3 to Triple("Capture the key points and the context needed to act on them.", 4, 1200),
        4 to Triple("Cover all important points, supporting details, and context.", 6, 2000),
        5 to Triple("Cover everything: main points, nuance, context, details, implications.", 9, 3000),
    )
    val LEVEL_NAMES = listOf("One line", "Short", "Medium", "Detailed", "Everything")

    fun summaryMaxTokens(level: Int) = (LEVELS[level] ?: LEVELS.getValue(3)).third

    fun summarySystem(level: Int, voice: Boolean): String {
        val (instruction, bullets, _) = LEVELS[level] ?: LEVELS.getValue(3)
        val shape = if (bullets == 0) "Reply with a single sentence and nothing else."
        else "Reply in this exact shape:\nFirst line: one sentence giving the gist.\n" +
            "Then up to $bullets lines, each starting with '- ', one point per line.\nNo headings, no preamble, no closing remark."
        val what = if (voice) "a voice message" else "a note"
        return "You summarize $what for someone who does not have time to ${if (voice) "listen to" else "read"} it. $instruction\n\n$shape\n\n" +
            "Write plainly, in the third person, and never mention the transcript, the audio, or yourself. " +
            (if (voice) "Transcripts come from speech recognition and may contain misheard words: summarize the evident intent rather than quoting oddities." else "")
    }

    fun summaryUser(text: String) = "Summarize this:\n\n<note>\n$text\n</note>"

    // ---------- the index ----------

    const val MAX_POINTS = 40

    const val INDEX_SYSTEM =
        "You index voice messages so someone can see everything in one at a glance and jump to any part of it.\n\n" +
            "You are given the message in numbered segments. Return one line per distinct point, in the order it was said, in exactly this shape:\n\n" +
            "<segment number>|<topic>|<the point>\n\n" +
            "Rules:\n" +
            "- One line per distinct thing said. Never merge two points into one line, and never split one point across two.\n" +
            "- The segment number is where that point starts.\n" +
            "- Topic is one or two words. Repeat it on consecutive lines about the same subject so the run reads as a group.\n" +
            "- Drop the reasons, the background and the retelling. Keep every doubt, condition and qualifier (maybe, if, not sure, unless), because those change what a reply should say.\n" +
            "- Write a question as a question, and an ask as an ask.\n" +
            "- Say it plainly and in the third person. Do not mention the message, the recording or yourself.\n" +
            "- Output nothing but those lines: no preamble, no numbering, no bullets, no closing remark."

    fun indexUser(segments: List<Segment>) =
        "Index this message:\n\n" + segments.mapIndexed { i, s -> "[${i + 1}] ${s.text.trim()}" }.joinToString("\n")

    /** The model's lines as points in time order. A bad line costs only itself; a number out of range is clamped. */
    fun parseIndex(raw: String, segments: List<Segment>): List<Point> {
        if (raw.isBlank() || segments.isEmpty()) return emptyList()
        val points = ArrayList<Point>()
        for (line0 in raw.lines()) {
            val line = line0.trim().trimStart('-', '*', '•', '#').trim()
            if (line.isEmpty() || '|' !in line) continue
            val head = line.substringBefore('|')
            val rest = line.substringAfter('|')
            var topic = rest.substringBefore('|', rest).trim()
            var text = if ('|' in rest) rest.substringAfter('|').trim() else ""
            if (text.isEmpty()) { text = topic; topic = "" }
            if (text.isEmpty()) continue
            val n = head.trim().trim('[', ']').substringBefore('.').trim().toIntOrNull() ?: (points.size + 1)
            points.add(Point(segments[n.coerceIn(1, segments.size) - 1].start, topic, text))
            if (points.size >= MAX_POINTS) break
        }
        return points.sortedBy { it.time }
    }

    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}

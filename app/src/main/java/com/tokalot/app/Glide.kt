/*
 * Swipe typing: the shape-matching approach of FlorisBoard's StatisticalGlideTypingClassifier,
 * Copyright (C) 2025 The FlorisBoard Contributors, Licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Ported to plain Kotlin for Tokalot and changed: scores
 * are added as log-probabilities, word frequency and the word before count in the ranking, ideal path
 * lengths are cached per layout, and the gesture is sampled at fewer points.
 * The method itself comes from Étienne Desticourt's write-up for AnySoftKeyboard
 * (https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870).
 */
package com.tokalot.app

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a finger path across the letter keys into words. Every word has an "ideal" path (straight lines
 * from key centre to key centre). The user's path is compared with the ideal path of each likely word in
 * two ways: by shape (both scaled to the same size, so a small or offset swipe still matches) and by
 * location (where on the keyboard it actually went). Those two closeness scores, plus how common the word
 * is and whether it often follows the word before, rank the words.
 *
 * Only words whose first and last letters are near where the swipe started and ended, and whose ideal
 * path is about as long as the swipe, are compared at all, which keeps it fast.
 */
class Glide(private val words: KeyboardWords) {
    /** Key centres in pixels, by letter, and the size of a key. */
    private var centers: Map<Char, FloatArray> = emptyMap()
    private var keyW = 1f
    private var keyH = 1f

    /** Word ids grouped by first and last letter. */
    private var buckets = HashMap<Int, IntArray>()
    private var lengths = FloatArray(0)
    private var vocabSize = -1

    private val xs = FloatArray(MAX_POINTS)
    private val ys = FloatArray(MAX_POINTS)
    private var count = 0

    companion object {
        private const val MAX_POINTS = 500
        private const val SAMPLES = 64
        /** How far, in key widths, a swipe's length may be from a word's ideal length. */
        private const val LENGTH_SLACK = 8.42f
        private const val SHAPE_STD = 22.08f * SAMPLES / 200f * 0.7f // the original's figure was a sum over 200 points; a little stricter here
        private const val LOCATION_STD = 0.35f
        /** Words rarer than this (0-255) are left out of swipe typing; they're still in the list for tapping. */
        const val MIN_PROB = 30
        private const val WORD_WEIGHT = 1f / 64f
        private const val END_WEIGHT = 1f

        private fun pair(a: Char, b: Char) = a.code * 65536 + b.code
    }

    val points get() = count

    /** Call when the keyboard's size or the letters' positions change. */
    fun setLayout(centers: Map<Char, FloatArray>, keyWidth: Float, keyHeight: Float) {
        if (centers == this.centers && keyWidth == keyW && keyHeight == keyH) return
        this.centers = centers
        keyW = keyWidth; keyH = keyHeight
        lengths = FloatArray(0) // ideal lengths depend on the key positions
        vocabSize = -1
    }

    /** Rebuilds the word groups (after user words were added). */
    fun refreshWords() { vocabSize = -1 }

    private fun ensureVocab() {
        if (vocabSize == words.size && lengths.isNotEmpty()) return
        val b = HashMap<Int, ArrayList<Int>>()
        for (id in words.swipeable(MIN_PROB)) {
            val k = letters(id) ?: continue
            if (k.length < 2) continue
            b.getOrPut(pair(k.first(), k.last())) { ArrayList() } += id
        }
        buckets = HashMap(b.mapValues { it.value.toIntArray() })
        lengths = FloatArray(words.size) { -1f }
        vocabSize = words.size
    }

    /** A word's letters as keys on the keyboard (accents and apostrophes dropped), or null if one has no key. */
    private fun letters(id: Int): String? {
        val k = KeyboardWords.key(words.form(id))
        return if (k.all { it in centers }) k else null
    }

    fun start(x: Float, y: Float) { count = 0; add(x, y) }

    fun add(x: Float, y: Float) {
        if (count >= MAX_POINTS) return
        if (count > 0) {
            val dx = xs[count - 1] - x
            val dy = ys[count - 1] - y
            val min = keyW / 4
            if (dx * dx + dy * dy < min * min) return
        }
        xs[count] = x; ys[count] = y; count++
    }

    /** The best [n] words for the swipe so far, best first. [prev]: the word before, for context. */
    fun words(n: Int, prev: String?, history: TypingHistory? = null): List<String> {
        if (count < 2 || centers.isEmpty()) return emptyList()
        ensureVocab()
        val radius = min(keyW, keyH)
        val userLen = length(xs, ys, count)
        val user = resample(xs, ys, count, SAMPLES)
        val userNorm = normalize(user)
        val starts = closest(xs[0], ys[0], 2)
        val ends = closest(xs[count - 1], ys[count - 1], 2)
        val next = prev?.let { words.next(it) }

        val best = ArrayList<Pair<Int, Float>>()
        for (s in starts) for (e in ends) {
            val ids = buckets[pair(s, e)] ?: continue
            for (id in ids) {
                val k = letters(id) ?: continue
                var len = lengths[id]
                if (len < 0f) {
                    len = idealLength(k)
                    lengths[id] = len
                }
                if (abs(userLen - len) > LENGTH_SLACK * radius) continue
                // A doubled letter may be swiped with a little loop or straight through: take whichever fits.
                var fit = Float.NEGATIVE_INFINITY
                for (loops in if (hasDouble(k)) booleanArrayOf(false, true) else booleanArrayOf(false)) {
                    val path = idealPath(k, loops)
                    val sampled = resample(path.first, path.second, path.third, SAMPLES)
                    val shape = shapeDistance(normalize(sampled), userNorm)
                    val location = locationDistance(sampled, user)
                    fit = max(fit, -0.5f * sq(shape / SHAPE_STD) - 0.5f * sq(location / (LOCATION_STD * radius)))
                }
                val form = words.form(id)
                // Where the swipe starts and ends is the most deliberate part of it.
                val first = centers.getValue(k.first()); val last = centers.getValue(k.last())
                val endMiss = sq(hypot(xs[0] - first[0], ys[0] - first[1]) / radius) +
                    sq(hypot(xs[count - 1] - last[0], ys[count - 1] - last[1]) / radius)
                var score = fit - END_WEIGHT * endMiss + words.prob(id) * WORD_WEIGHT
                // Names and abbreviations ("Harry", "GWR") only win when clearly better than an ordinary word.
                if (form[0].isUpperCase() && form != "I" && !form.startsWith("I'")) score -= if (form.length > 1 && form[1].isUpperCase()) 1.5f else 1f
                if (next != null) {
                    val i = next.indexOfFirst { it.equals(form, ignoreCase = true) }
                    if (i >= 0) score += 1.5f - 0.3f * i
                }
                if (history != null) {
                    val c = history.count(form)
                    if (c > 0) score += min(2f, 0.6f * kotlin.math.ln(1f + c))
                    if (prev != null && history.pairCount(prev, form) > 0) score += 1.5f
                }
                insert(best, id to score, n)
            }
        }
        return best.map { words.form(it.first) }.distinct()
    }

    private fun insert(list: ArrayList<Pair<Int, Float>>, item: Pair<Int, Float>, n: Int) {
        var at = list.size
        while (at > 0 && list[at - 1].second < item.second) at--
        if (at >= n) return
        // The same letters in two forms (us, US): keep the better.
        val dup = list.indexOfFirst { KeyboardWords.key(words.form(it.first)) == KeyboardWords.key(words.form(item.first)) }
        if (dup in 0 until at) return
        if (dup >= 0) list.removeAt(dup)
        list.add(min(at, list.size), item)
        if (list.size > n) list.removeAt(n)
    }

    private fun sq(v: Float) = v * v

    private fun closest(x: Float, y: Float, n: Int): List<Char> =
        centers.entries.sortedBy { hypot(it.value[0] - x, it.value[1] - y) }.take(n).map { it.key }

    private fun idealLength(k: String): Float {
        var len = 0f
        for (i in 1 until k.length) {
            val a = centers.getValue(k[i - 1]); val b = centers.getValue(k[i])
            len += hypot(a[0] - b[0], a[1] - b[1])
        }
        return len
    }

    private fun hasDouble(k: String) = (1 until k.length).any { k[it] == k[it - 1] }

    /** Key centre to key centre; with [loops], a doubled letter gets a small loop so "pool" and "pol" differ. */
    private fun idealPath(k: String, loops: Boolean): Triple<FloatArray, FloatArray, Int> {
        val px = FloatArray(k.length * 5)
        val py = FloatArray(k.length * 5)
        var n = 0
        for (i in k.indices) {
            val c = centers.getValue(k[i])
            if (i > 0 && k[i] == k[i - 1]) {
                if (!loops) continue
                val dx = keyW / 4; val dy = keyH / 4
                px[n] = c[0] + dx; py[n++] = c[1] + dy
                px[n] = c[0] + dx; py[n++] = c[1] - dy
                px[n] = c[0] - dx; py[n++] = c[1] - dy
                px[n] = c[0] - dx; py[n++] = c[1] + dy
            }
            px[n] = c[0]; py[n++] = c[1]
        }
        return Triple(px, py, n)
    }

    private fun length(x: FloatArray, y: FloatArray, n: Int): Float {
        var len = 0f
        for (i in 1 until n) len += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return len
    }

    /** [n] points evenly spaced along the path (all at the start when it has no length). */
    private fun resample(x: FloatArray, y: FloatArray, size: Int, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        val total = length(x, y, size)
        if (size < 2 || total <= 0f) {
            for (i in 0 until n) { out[2 * i] = x[0]; out[2 * i + 1] = y[0] }
            return out
        }
        val step = total / (n - 1)
        var seg = 1
        var segStart = 0f // distance along the path where segment [seg-1, seg] begins
        for (i in 0 until n) {
            val target = min(step * i, total)
            while (seg < size - 1 && segStart + hypot(x[seg] - x[seg - 1], y[seg] - y[seg - 1]) < target) {
                segStart += hypot(x[seg] - x[seg - 1], y[seg] - y[seg - 1])
                seg++
            }
            val segLen = hypot(x[seg] - x[seg - 1], y[seg] - y[seg - 1])
            val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
            out[2 * i] = x[seg - 1] + (x[seg] - x[seg - 1]) * t
            out[2 * i + 1] = y[seg - 1] + (y[seg] - y[seg - 1]) * t
        }
        return out
    }

    /** Scaled so the longer side of its bounding box is 1, and centred on 0. */
    private fun normalize(p: FloatArray): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) {
            minX = min(minX, p[2 * i]); maxX = max(maxX, p[2 * i])
            minY = min(minY, p[2 * i + 1]); maxY = max(maxY, p[2 * i + 1])
        }
        val side = max(max(maxX - minX, maxY - minY), 0.00001f)
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        return FloatArray(p.size) { i -> (p[i] - if (i % 2 == 0) cx else cy) / side }
    }

    private fun shapeDistance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in 0 until a.size / 2) d += hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1])
        return d
    }

    private fun locationDistance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / (a.size / 2) / 2
    }
}

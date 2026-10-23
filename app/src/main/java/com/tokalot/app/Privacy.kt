package com.tokalot.app

import android.content.Context

/**
 * The three privacy levels picked on first run and on Settings › Privacy. A level is only a template: it sets
 * the switches below, and every switch can still be changed one by one, here or on its own page. The level shown
 * is worked out from the switches each time, so a change made anywhere shows up here, and a mix that matches no
 * level is "Custom". Nothing extra is stored or synced; the switches themselves sync as before.
 * The desktop apps have the same levels and switches (Privacy.cs).
 */
object Privacy {
    enum class Level(val label: String, val blurb: String) {
        FULL("Everything on", "Every feature, including learning from your corrections. Your speech goes to the services you pick, with your own keys."),
        BALANCED("Balanced", "Cloud speech and AI cleanup, but Tokalot never looks at what's already in your text boxes."),
        PRIVATE("Most private", "Nothing leaves the phone. Speech to text runs on the phone (slower, more mistakes), with no AI cleanup."),
    }

    /** One switch on the Privacy page, and the levels that turn it on. */
    class Switch(
        val title: String,
        val note: String,
        val levels: Set<Level>,
        val isOn: (Prefs) -> Boolean,
        val set: (Prefs, Boolean) -> Unit,
    )

    private val CLOUD = setOf(Level.FULL, Level.BALANCED)

    val switches = listOf(
        Switch("Cloud speech to text", "Off: speech to text runs on the phone and your voice never leaves it. Needs the on-device model.", CLOUD,
            { it.stt != SttChoice.LOCAL }) { p, on ->
            if (on) p.stt = cloudStt(p) else { p.lastCloudStt = p.stt; p.stt = SttChoice.LOCAL }
        },
        Switch("AI cleanup", "Removes ums, applies \"no wait, I mean\" corrections and fits the style of the app you're in. Off: only ums are removed, on the phone.", CLOUD,
            { it.cleanup != CleanupChoice.OFF }) { p, on ->
            if (on) p.cleanup = cloudCleanup(p) else { p.lastCleanup = p.cleanup; p.cleanup = CleanupChoice.OFF }
        },
        Switch("Transcribe while I talk", "Sends what you've said so far each time you pause, so long dictations come back quickly.", CLOUD,
            { it.liveStt }) { p, on -> p.liveStt = on },
        Switch("Fit into the sentence", "Uses where the cursor sits (not the text) so a dictation mid-sentence starts in lowercase.", CLOUD,
            { it.fitSentence }) { p, on -> p.fitSentence = on },
        Switch("Learn from my corrections", "Looks at the text box again for two minutes after a dictation and adds names you respell, and words you spell out, to your dictionary.", setOf(Level.FULL),
            { it.learnWords }) { p, on -> p.learnWords = on },
    )

    /** How long recordings are kept at each level, in days (see [Prefs.audioKeepDays]). */
    fun keepDays(level: Level) = when (level) {
        Level.FULL -> 30
        Level.BALANCED -> 7
        Level.PRIVATE -> 0
    }

    /** The level the current switches match, or null for Custom. */
    fun current(p: Prefs): Level? = Level.values().firstOrNull { l ->
        switches.all { it.isOn(p) == (l in it.levels) } && p.audioKeepDays == keepDays(l)
    }

    /** Sets every switch to [level]. Switches already right are left alone, so a cloud provider picked earlier stays. */
    fun apply(ctx: Context, p: Prefs, level: Level) {
        for (s in switches) {
            val want = level in s.levels
            if (s.isOn(p) != want) s.set(p, want)
        }
        val days = keepDays(level)
        if (p.audioKeepDays != days) {
            p.audioKeepDays = days
            val app = ctx.applicationContext
            Thread { AudioStore.prune(app, days) }.start()
        }
    }

    /** The cloud speech service to go back to: the last one used, or the first with a key, else Groq. */
    private fun cloudStt(p: Prefs): SttChoice {
        val cloud = SttChoice.values().filter { it.service != null }
        return p.lastCloudStt.takeIf { it.service != null }
            ?: cloud.firstOrNull { p.key(it.service).isNotEmpty() } ?: SttChoice.GROQ
    }

    private fun cloudCleanup(p: Prefs): CleanupChoice {
        val cloud = CleanupChoice.values().filter { it.service != null }
        return p.lastCleanup.takeIf { it.service != null }
            ?: cloud.firstOrNull { p.key(it.service).isNotEmpty() } ?: CleanupChoice.GROQ
    }
}

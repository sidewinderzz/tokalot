package com.tokalot.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Fills the app with made-up dictations for README screenshots. Only works in debug builds,
 * triggered by the screenshot workflow with `am start ... --ez demo true`.
 */
object Demo {
    fun isDebug(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Applies screenshot extras (theme/tab/settings) and seeds data. Returns true if it did anything. */
    fun handle(ctx: Context, intent: Intent?): Boolean {
        if (intent == null || !isDebug(ctx) || !intent.getBooleanExtra("demo", false)) return false
        val prefs = Prefs(ctx)
        intent.getStringExtra("theme")?.let { prefs.theme = it }
        seed(ctx)
        return true
    }

    private fun seed(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        prefs.setKey("groq", "demo") // makes setup look complete; never used to call anything
        prefs.words = listOf("Kubernetes", "PostgreSQL", "Nguyen", "Tokalot", "Groq")
        prefs.snippets = listOf(
            Snippet("my email", "alex@example.com"),
            Snippet("home address", "123 Main St, Springfield"),
            Snippet("calendar link", "Grab any open slot here: example.com/meet/alex"),
        )
        app.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(app.filesDir, "history.json").delete()
        History.reload()

        val now = System.currentTimeMillis()
        val min = 60_000L
        val day = 24 * 60 * min
        data class D(val ago: Long, val app: String, val text: String, val raw: String, val secs: Int)
        val items = listOf(
            D(day + 190 * min, "com.android.chrome", "Best dog-friendly hiking trails near Lake Tahoe",
                "best uh dog friendly hiking trails near lake tahoe", 4),
            D(day + 95 * min, "com.google.android.gm",
                "Hi Sam,\n\nThanks for sending the draft over. I made a few edits to the second section and left comments on the budget table.\n\nLet me know if Thursday still works to go over it.\n\nThanks,\nAlex",
                "hi sam thanks for sending the draft over um i made a few edits to the second section and left comments on the budget table let me know if tuesday no wait thursday still works to go over it thanks alex", 21),
            D(day + 20 * min, "com.Slack", "Heads up, the deploy is moving to 4 instead of 3. I'll post in here once it's live.",
                "heads up the deploy is moving to uh three no four instead of three i'll post in here once it's live", 8),
            D(140 * min, "com.anthropic.claude",
                "Refactor `SettingsScreen.kt` so each section is its own function, and keep the existing `render()` behavior. Then add a unit test for `TextTools.protect`.",
                "refactor settings screen dot kt so each section is its own function and keep the existing render behavior then um add a unit test for text tools dot protect", 14),
            D(52 * min, "com.whatsapp", "lol yes. we'll be there around 7, save us a spot?",
                "lol yes we'll be there around seven save us a spot", 5),
            D(9 * min, "com.google.android.apps.messaging", "Sounds good, I'll grab coffee on the way. Want your usual?",
                "um sounds good i'll grab uh coffee on the way want your usual", 5),
        )
        var words = 0
        items.sortedByDescending { it.ago }.forEach {
            val t = now - it.ago
            History.add(app, Entry(t, t, it.text, it.raw, it.secs * 1000L, true, it.app))
            words += TextTools.wordCount(it.text)
            Usage.recordDictation(app, TextTools.wordCount(it.text), false)
            Usage.recordStt(app, SttChoice.GROQ, it.secs.toDouble())
            Usage.recordLlm(app, CleanupChoice.GROQ, 900, 60)
            Usage.recordEdits(app, AppContext.fillerCount(it.raw), AppContext.correctionCount(it.raw))
        }
        // A fuller-looking month for the stats card.
        repeat(180) {
            Usage.recordDictation(app, 24, false)
            Usage.recordStt(app, SttChoice.GROQ, 9.0)
            Usage.recordLlm(app, CleanupChoice.GROQ, 900, 60)
        }
        Usage.recordEdits(app, 212, 19)
    }
}

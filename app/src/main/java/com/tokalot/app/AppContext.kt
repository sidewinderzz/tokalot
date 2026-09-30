package com.tokalot.app

import android.content.Context

/** What kind of app you're dictating into; each gets its own style and formatting rules. */
enum class AppCategory(val label: String, val blurb: String, val defaultStyle: Style, val rule: String) {
    MESSAGING(
        "Messages", "Texts, WhatsApp, Messenger, Slack, Discord…", Style.CASUAL,
        "This is a chat or text message. Keep it conversational and brief. No greeting or sign-off unless the user said one."
    ),
    EMAIL(
        "Email", "Gmail, Outlook, Samsung Email…", Style.FORMAL,
        "This is an email body. If the user dictates a greeting (\"Hi Sam\") put it on its own line followed by a blank line; put a dictated sign-off (\"Thanks, Alex\") on its own lines at the end. Use a blank line between paragraphs. Never invent a greeting, subject line or signature."
    ),
    AI_CODE(
        "AI & code", "Claude, ChatGPT, GitHub, terminals…", Style.CASUAL,
        "This is a prompt for an AI assistant or a coding tool. Keep technical terms exact: programming languages, libraries, frameworks, acronyms (API, JSON, APK, SQL), version numbers. Write file names, CLI commands, function and variable names in their conventional form (build.gradle.kts, camelCase, snake_case) and wrap them in `backticks`. When the user lists steps or requirements, format them as a numbered or bulleted list."
    ),
    OTHER("Everything else", "Browsers, notes, forms, search…", Style.CASUAL, ""),
}

object AppContext {
    private val known = mapOf(
        AppCategory.MESSAGING to listOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp",
            "org.thoughtcrime.securesms", "org.telegram.messenger", "com.facebook.orca", "com.discord",
            "com.instagram.android", "com.snapchat.android", "com.Slack", "com.microsoft.teams",
            "com.google.android.apps.dynamite", "com.groupme.android",
        ),
        AppCategory.EMAIL to listOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
            "ch.protonmail.android", "com.yahoo.mobile.client.android.mail", "com.fsck.k9", "me.bluemail.mail",
        ),
        AppCategory.AI_CODE to listOf(
            "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.github.android",
            "com.termux", "com.replit.app", "ai.perplexity.app.android", "com.microsoft.copilot", "com.x.grok",
        ),
    )

    fun categorize(pkg: String?, prefs: Prefs): AppCategory {
        if (pkg.isNullOrEmpty()) return AppCategory.OTHER
        prefs.appOverride(pkg)?.let { return it }
        known.forEach { (cat, list) -> if (pkg in list) return cat }
        val p = pkg.lowercase()
        return when {
            "mail" in p -> AppCategory.EMAIL
            "messag" in p || "sms" in p || "chat" in p -> AppCategory.MESSAGING
            else -> AppCategory.OTHER
        }
    }

    private val labels = HashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    // ---------- cheap "what did cleanup fix" counters, from the raw transcript ----------

    private val FILLER = Regex("""(?i)\b(?:um+|uh+|erm|hmm+|you know)\b""")
    private val CORRECTION = Regex(
        """(?i)\b(?:no wait|wait no|actually no|no actually|scratch that|sorry,? i meant|let me rephrase|or rather|correction)\b"""
    )

    fun fillerCount(raw: String) = FILLER.findAll(raw).count()
    fun correctionCount(raw: String) = CORRECTION.findAll(raw).count()
}

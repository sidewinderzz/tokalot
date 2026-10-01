package com.tokalot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Snippet(val trigger: String, val text: String)

/** Where speech becomes text. Cloud options use the OpenAI-style /audio/transcriptions API. */
enum class SttChoice(val label: String, val service: String?, val baseUrl: String, val model: String, val note: String) {
    LOCAL("On-device", null, "", "", "Free, offline, audio never leaves the phone. Less accurate."),
    GROQ("Groq Whisper", "groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "Fastest and most accurate per dollar. Free tier covers heavy use."),
    OPENAI("OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-transcribe", "Top accuracy, about $0.006 per minute."),
}

/** Which LLM rewrites the raw transcript. */
enum class CleanupChoice(val label: String, val service: String?, val baseUrl: String, val model: String, val note: String) {
    OFF("Off", null, "", "", "Only strips um/uh. No AI, no cost."),
    GROQ("Groq GPT-OSS 20B", "groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "Lowest latency, pennies a month."),
    CLAUDE("Claude Haiku 4.5", "anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5", "Very good cleanup, about $0.60 a month at heavy use."),
    GEMINI("Gemini Flash-Lite", "gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-flash-lite-preview", "Cheap and fast."),
    OPENAI("OpenAI GPT-4.1 nano", "openai", "https://api.openai.com/v1", "gpt-4.1-nano", "Cheap and fast."),
}

enum class Style(val label: String, val example: String, val rule: String) {
    FORMAL(
        "Formal",
        "Hi Sam, I'll be there around 3 on Tuesday. Let me know if that works.",
        "Proper capitalization and punctuation, complete sentences, polished grammar. Keep the speaker's wording and tone; do not make it stiff."
    ),
    CASUAL(
        "Casual",
        "Hey, I'll be there around 3 on Tuesday. Let me know if that works",
        "Normal capitalization and punctuation, relaxed conversational tone. A trailing period on the last sentence is optional."
    ),
    VERY_CASUAL(
        "Very casual",
        "hey, ill be there around 3 on tuesday. that work for you?",
        "All lowercase, like a text message. Keep the punctuation that carries meaning: commas, question marks, and periods between sentences, so the message is never confusing. Only drop the period at the very end. Apostrophes are optional."
    ),
}

object Services {
    val all = listOf(
        "groq" to "Groq",
        "anthropic" to "Anthropic (Claude)",
        "openai" to "OpenAI",
        "gemini" to "Google Gemini",
    )
    val keyPages = mapOf(
        "groq" to "console.groq.com/keys",
        "anthropic" to "console.anthropic.com/settings/keys",
        "openai" to "platform.openai.com/api-keys",
        "gemini" to "aistudio.google.com/apikey",
    )
}

/**
 * Everything the user sets lives in app-private SharedPreferences on the phone.
 * No accounts, no database; uninstalling the app erases it.
 */
/** Choices for the floating button's listening color. Amber matches the app icon. */
object Accents {
    val DEFAULT = 0xFFF2A93B.toInt()
    val all = listOf(
        "Amber" to DEFAULT,
        "Green" to 0xFF34C77B.toInt(),
        "Teal" to 0xFF2EC4C4.toInt(),
        "Blue" to 0xFF4C8DFF.toInt(),
        "Purple" to 0xFFA77BFF.toInt(),
        "Pink" to 0xFFFF6FAE.toInt(),
        "Red" to 0xFFFF5A52.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
    )
}

/** Pre-filled "Your instructions" for a fresh install; users can edit or clear it. */
const val DEFAULT_INSTRUCTIONS = "Always write \"lol\" in lowercase."

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def

    var stt: SttChoice
        get() = enumOr(sp.getString("stt", null), SttChoice.GROQ)
        set(v) = sp.edit().putString("stt", v.name).apply()

    var cleanup: CleanupChoice
        get() = enumOr(sp.getString("cleanup", null), CleanupChoice.GROQ)
        set(v) = sp.edit().putString("cleanup", v.name).apply()

    var style: Style
        get() = enumOr(sp.getString("style", null), Style.CASUAL)
        set(v) = sp.edit().putString("style", v.name).apply()

    fun styleFor(c: AppCategory): Style = enumOr(sp.getString("style_${c.name}", null), c.defaultStyle)
    fun setStyleFor(c: AppCategory, s: Style) = sp.edit().putString("style_${c.name}", s.name).apply()

    /** User moved an app to a different category. */
    fun appOverride(pkg: String): AppCategory? = sp.getString("app_$pkg", null)?.let { enumOr(it, AppCategory.OTHER) }
    fun setAppOverride(pkg: String, c: AppCategory) = sp.edit().putString("app_$pkg", c.name).apply()

    var customInstructions: String
        get() = sp.getString("custom", DEFAULT_INSTRUCTIONS) ?: ""
        set(v) = sp.edit().putString("custom", v).apply()

    fun key(service: String?): String = if (service == null) "" else sp.getString("key_$service", "")?.trim() ?: ""
    fun setKey(service: String, v: String) = sp.edit().putString("key_$service", v.trim()).apply()

    /** Model name, user-overridable per choice (providers rename models over time). */
    fun sttModel(c: SttChoice) = sp.getString("sttmodel_${c.name}", null)?.takeIf { it.isNotBlank() } ?: c.model
    fun setSttModel(c: SttChoice, v: String) = sp.edit().putString("sttmodel_${c.name}", v.trim()).apply()
    fun cleanupModel(c: CleanupChoice) = sp.getString("model_${c.name}", null)?.takeIf { it.isNotBlank() } ?: c.model
    fun setCleanupModel(c: CleanupChoice, v: String) = sp.edit().putString("model_${c.name}", v.trim()).apply()

    var words: List<String>
        get() {
            val arr = JSONArray(sp.getString("words", "[]"))
            return List(arr.length()) { arr.getString(it) }
        }
        set(v) = sp.edit().putString("words", JSONArray(v).toString()).apply()

    var snippets: List<Snippet>
        get() {
            val arr = JSONArray(sp.getString("snippets", "[]"))
            return List(arr.length()) {
                val o = arr.getJSONObject(it)
                Snippet(o.getString("t"), o.getString("x"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("t", it.trigger).put("x", it.text)) }
            sp.edit().putString("snippets", arr.toString()).apply()
        }

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Color of the floating button's bars while it's listening. */
    var accent: Int
        get() = sp.getInt("accent", Accents.DEFAULT)
        set(v) = sp.edit().putInt("accent", v).apply()

    /** Hold the button to talk instead of tap-to-start / tap-to-stop. */
    var holdToTalk: Boolean
        get() = sp.getBoolean("hold", false)
        set(v) = sp.edit().putBoolean("hold", v).apply()

    /** Vibration feedback from the floating button. */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** Stop automatically after 30 s of silence (tap mode only). */
    var autoStop: Boolean
        get() = sp.getBoolean("autostop", true)
        set(v) = sp.edit().putBoolean("autostop", v).apply()

    /** Let cloud speech-to-text detect the language instead of forcing English. */
    var autoLanguage: Boolean
        get() = sp.getBoolean("autolang", false)
        set(v) = sp.edit().putBoolean("autolang", v).apply()

    /** How long to keep recordings: 0 = don't save, Int.MAX_VALUE = forever. */
    var audioKeepDays: Int
        get() = sp.getInt("audio_days", 30)
        set(v) = sp.edit().putInt("audio_days", v).apply()

    /** Cloud STT is only used when a key for it exists; otherwise we silently stay on-device. */
    val cloudSttReady get() = stt != SttChoice.LOCAL && key(stt.service).isNotEmpty()
    val cleanupReady get() = cleanup != CleanupChoice.OFF && key(cleanup.service).isNotEmpty()
}

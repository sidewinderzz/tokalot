package com.tokalot.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

/** Tidying and sanity-checking API keys as they're pasted in. */
object Keys {
    /** How each provider's keys start today; a key that doesn't match only gets a warning, never refused. */
    val prefixes = mapOf(
        "groq" to "gsk_",
        "anthropic" to "sk-ant-",
        "openai" to "sk-",
        "gemini" to "AIza",
    )

    /**
     * Keys never contain spaces, so drop every bit of whitespace (line breaks from a wrapped email,
     * invisible zero-width characters from web pages) plus quotes around a key copied out of code.
     */
    fun clean(raw: String): String =
        raw.replace(Regex("[\\s\u200B-\u200D\u2060\uFEFF]"), "").trim('"', '\'', '`', '“', '”', '‘', '’')

    /** A short warning when [key] doesn't look like a [service] key, or null when it looks right (or is empty). */
    fun problem(service: String, key: String): String? {
        if (key.isEmpty()) return null
        val name = nameOf(service)
        // The longest matching prefix wins, so an Anthropic "sk-ant-" key isn't taken for OpenAI's "sk-".
        val looksLike = prefixes.entries.filter { key.startsWith(it.value) }.maxByOrNull { it.value.length }?.key
        return when {
            looksLike != null && looksLike != service -> "That looks like a key for ${nameOf(looksLike)}. This box is for $name."
            looksLike == null && prefixes[service] != null -> "$name keys usually start with ${prefixes[service]}. Check you copied the whole key."
            key.length < 20 -> "That looks too short for an API key. Check you copied the whole key."
            else -> null
        }
    }

    private fun nameOf(service: String) = Services.all.firstOrNull { it.first == service }?.second ?: service
}

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
/** Playback speeds a voice note offers, in the order the speed button steps through them. */
val NOTE_SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f)
/** The keyboard's light and dark choices: "auto" follows the phone. */
val KB_THEMES = listOf("auto", "light", "dark")
/** The keyboard heights offered, in percent. */
val KB_HEIGHTS = 80..130

const val DEFAULT_INSTRUCTIONS = "Always write \"lol\" in lowercase."

// Tolerant reads: a value of the wrong type (say, from a hand-edited backup) gives the
// default instead of crashing the app on every launch.
fun SharedPreferences.safeString(key: String, def: String?): String? =
    try { getString(key, def) } catch (_: ClassCastException) { def }
fun SharedPreferences.safeInt(key: String, def: Int): Int =
    try { getInt(key, def) } catch (_: ClassCastException) { def }
fun SharedPreferences.safeBoolean(key: String, def: Boolean): Boolean =
    try { getBoolean(key, def) } catch (_: ClassCastException) { def }

/**
 * Encrypts API keys at rest with AES-GCM. The AES key is made by, and never leaves, the
 * Android Keystore, so a copy of the app's files is useless without this phone.
 */
object Secrets {
    private const val ALIAS = "tokalot_api_keys"
    private const val PREFIX = "enc1:"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    // Stored value -> key. Saves a Keystore round trip on every read (screens read keys a lot).
    private val cache = ConcurrentHashMap<String, String>()

    fun isEncrypted(stored: String) = stored.startsWith(PREFIX)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns the value to store, or null if this phone's Keystore isn't working. */
    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance(CIPHER)
        c.init(Cipher.ENCRYPT_MODE, key())
        val stored = PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        cache[stored] = plain
        stored
    } catch (_: Exception) {
        null
    }

    /** Returns null if it can't be read (e.g. the data was copied from another phone). */
    fun decrypt(stored: String): String? = cache[stored] ?: try {
        val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance(CIPHER)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
        String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)).also { cache[stored] = it }
    } catch (_: Exception) {
        null
    }
}

/**
 * Everything the user sets lives in app-private SharedPreferences on the phone.
 * No accounts, no database; uninstalling the app erases it.
 */
class Prefs(ctx: Context) {
    companion object {
        /** Synced on/off settings: name in the sync file -> key here. */
        private val BOOLEANS = mapOf(
            "autoStop" to "autostop", "quickSkip" to "quick_skip", "liveStt" to "live_stt", "weakSignal" to "weak_signal",
            "autoLanguage" to "autolang", "polish" to "polish", "sounds" to "sounds", "learnWords" to "learn_words",
            "notesBeta" to "notes_beta", "holdToTalk" to "hold", "haptics" to "haptics", "fitSentence" to "fit_sentence",
            "noteSkipSilence" to "note_skip_silence", "noteSkipUms" to "note_skip_ums",
            "keyboardAutocorrect" to "kb_autocorrect", "keyboardSwipe" to "kb_swipe", "keyboardAutoCaps" to "kb_autocap",
            "keyboardDoubleSpace" to "kb_double_space", "keyboardLearn" to "kb_learn", "keyboardNumberRow" to "kb_number_row",
            "keyboardSymbolHints" to "kb_symbol_hints", "keyboardSound" to "kb_sound", "keyboardVibrate" to "kb_vibrate",
            "keyboardPopup" to "kb_popup", "keyboardClipboard" to "kb_clipboard", "keyboardClipSuggest" to "kb_clip_suggest",
            "keyboardScreenshots" to "kb_screenshots", "keyboardCodes" to "kb_codes",
            "headsetMic" to "headset_mic", "headsetMicAll" to "headset_mic_all",
            "shellCommands" to "shell_commands", "shellCue" to "shell_cue",
        )

        /** The keys here whose change is a change to the sync file (see [Sync.watch]). */
        fun synced(key: String) = key in BOOLEANS.values || key in setOf(
            "stt", "cleanup", "theme", "accent", "sound_set", "note_phrases", "learned_words", "dismissed_words",
            "note_level", "note_speed", "kb_theme", "kb_style", "kb_accent", "kb_toolbar", "kb_height",
        ) || key.startsWith("sttmodel_") || key.startsWith("model_") || key.startsWith("app_")

        /** Whether a synced setting's value from the file is one this version can use. */
        fun usable(name: String, text: String): Boolean {
            val v = SyncFormat.value(text) ?: return false
            fun strings(check: (String) -> Boolean = { true }) =
                v is JSONObject && v.keys().asSequence().all { (v.opt(it) as? String)?.let(check) == true }
            return when (name) {
                "stt" -> v is String && SttChoice.values().any { it.name == v }
                "cleanup" -> v is String && CleanupChoice.values().any { it.name == v }
                "theme" -> v is String && v in setOf("system", "light", "dark")
                "soundSet" -> v is String && Sounds.valid(v) == v
                "notePhrases" -> v is String
                "accent", "keyboardAccent" -> v is Number && v.toLong() in 0L..0xFFFFFFFFL
                "keyboardTheme" -> v is String && v in KB_THEMES
                "keyboardStyle" -> v is String && v in KeyboardTheme.STYLES
                "keyboardToolbar" -> v is String && KeyboardToolbar.valid(v)
                "keyboardHeight" -> v is Number && v.toDouble() == Math.floor(v.toDouble()) && v.toInt() in KB_HEIGHTS
                "sttModels", "cleanupModels" -> strings()
                "androidAppOverrides" -> strings { c -> AppCategory.values().any { it.name == c } }
                "noteSummaryLevel" -> v is Number && v.toDouble() == Math.floor(v.toDouble()) && v.toInt() in 1..5
                "noteSpeed" -> v is Number && NOTE_SPEEDS.any { Math.abs(it - v.toDouble()) < 0.001 }
                "learnedWords", "dismissedWords" -> v is JSONArray && (0 until v.length()).all { v.opt(it) is String }
                else -> name in BOOLEANS && v is Boolean
            }
        }
    }

    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // Sync's own state lives in a separate file that Backup never reads or replaces, so a
    // backup restored on another phone can't carry this phone's sync file with it.
    private val syncSp = app.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def

    var stt: SttChoice
        get() = enumOr(sp.safeString("stt", null), SttChoice.GROQ)
        set(v) = sp.edit().putString("stt", v.name).apply()

    var cleanup: CleanupChoice
        get() = enumOr(sp.safeString("cleanup", null), CleanupChoice.GROQ)
        set(v) = sp.edit().putString("cleanup", v.name).apply()

    var style: Style
        get() = enumOr(sp.safeString("style", null), Style.CASUAL)
        set(v) = sp.edit().putString("style", v.name).apply()

    fun styleFor(c: AppCategory): Style = enumOr(sp.safeString("style_${c.name}", null), c.defaultStyle)
    fun setStyleFor(c: AppCategory, s: Style) {
        sp.edit().putString("style_${c.name}", s.name).apply()
        Sync.changed(app)
    }

    /** Only the styles the user picked themselves (category id -> style id); the rest follow the defaults. */
    val explicitStyles: Map<String, String>
        get() {
            val out = LinkedHashMap<String, String>()
            for (c in AppCategory.values()) {
                val name = sp.safeString("style_${c.name}", null) ?: continue
                if (Style.values().any { it.name == name }) out[c.name] = name
            }
            return out
        }

    /** User moved an app to a different category. */
    fun appOverride(pkg: String): AppCategory? = sp.safeString("app_$pkg", null)?.let { enumOr(it, AppCategory.OTHER) }
    fun setAppOverride(pkg: String, c: AppCategory) = sp.edit().putString("app_$pkg", c.name).apply()

    var customInstructions: String
        get() = sp.safeString("custom", DEFAULT_INSTRUCTIONS) ?: ""
        set(v) {
            sp.edit().putString("custom", v).apply()
            Sync.changed(app)
        }

    /** API keys are stored encrypted (see [Secrets]); one saved in plain text by an older version is encrypted on first read. */
    fun key(service: String?): String {
        if (service == null) return ""
        val stored = sp.safeString("key_$service", "")?.trim() ?: ""
        if (stored.isEmpty()) return ""
        if (Secrets.isEncrypted(stored)) return Secrets.decrypt(stored) ?: ""
        Secrets.encrypt(stored)?.let { sp.edit().putString("key_$service", it).apply() }
        return stored
    }

    fun setKey(service: String, v: String) {
        val plain = Keys.clean(v)
        // If the Keystore is broken on this phone, a plain-text key still beats a key that can't be saved.
        val stored = if (plain.isEmpty()) "" else Secrets.encrypt(plain) ?: plain
        sp.edit().putString("key_$service", stored).apply()
        if (syncKeys) Sync.changed(app)
    }

    /** Model name, user-overridable per choice (providers rename models over time). */
    fun sttModel(c: SttChoice) = sp.safeString("sttmodel_${c.name}", null)?.takeIf { it.isNotBlank() } ?: c.model
    fun setSttModel(c: SttChoice, v: String) = sp.edit().putString("sttmodel_${c.name}", v.trim()).apply()
    fun cleanupModel(c: CleanupChoice) = sp.safeString("model_${c.name}", null)?.takeIf { it.isNotBlank() } ?: c.model
    fun setCleanupModel(c: CleanupChoice, v: String) = sp.edit().putString("model_${c.name}", v.trim()).apply()

    var words: List<String>
        get() = runCatching {
            val arr = JSONArray(sp.safeString("words", "[]"))
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(v) {
            sp.edit().putString("words", JSONArray(v).toString()).apply()
            Sync.changed(app)
        }

    /** Services this device has held a key for. One of these with no key now was cleared by the user, so sync doesn't refill it. */
    var knownKeys: Set<String>
        get() = sp.getStringSet("known_keys", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("known_keys", v).apply()

    /** Learn names and terms from the user's corrections after a dictation (see [Learn]). Off unless switched on. */
    var learnWords: Boolean
        get() = sp.safeBoolean("learn_words", false)
        set(v) = sp.edit().putBoolean("learn_words", v).apply()

    /** The dictionary words that were learned rather than typed in, so the Dictionary screen can mark them. */
    var learnedWords: Set<String>
        get() = runCatching {
            val arr = JSONArray(sp.safeString("learned_words", "[]"))
            List(arr.length()) { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit().putString("learned_words", JSONArray(v.toList()).toString()).apply()

    private fun stringList(key: String): List<String> = runCatching {
        val arr = JSONArray(sp.safeString(key, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** Words the user spelled out while dictating, waiting for Add or Not now (see [Learn.spelled]). This device only. */
    var suggestedWords: List<String>
        get() = stringList("suggested_words")
        set(v) = sp.edit().putString("suggested_words", JSONArray(v).toString()).apply()

    /** Suggestions the user turned down, so the same spelling isn't offered again. */
    var dismissedWords: Set<String>
        get() = stringList("dismissed_words").toSet()
        set(v) = sp.edit().putString("dismissed_words", JSONArray(v.toList()).toString()).apply()

    /** Offers [found] for the dictionary, leaving out words already there, offered or turned down. */
    /**
     * Words spelled out while dictating: added to the dictionary (marked learned) when [learnWords] is on, else
     * offered with Add and Not now. Returns the words that were added.
     */
    fun suggestWords(found: List<String>): List<String> {
        val have = (words + suggestedWords + dismissedWords).map { it.lowercase() }.toSet()
        val fresh = found.filter { it.lowercase() !in have }.distinctBy { it.lowercase() }
        if (fresh.isEmpty()) return emptyList()
        if (!learnWords) { suggestedWords = suggestedWords + fresh; return emptyList() }
        words = words + fresh
        learnedWords = learnedWords + fresh
        return fresh
    }

    var snippets: List<Snippet>
        get() = runCatching {
            val arr = JSONArray(sp.safeString("snippets", "[]"))
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Snippet(o.getString("t"), o.getString("x"))
            }
        }.getOrDefault(emptyList())
        set(v) {
            sp.edit().putString("snippets", snippetsJson(v)).apply()
            Sync.changed(app)
        }

    private fun snippetsJson(v: List<Snippet>): String {
        val arr = JSONArray()
        v.forEach { arr.put(JSONObject().put("t", it.trigger).put("x", it.text)) }
        return arr.toString()
    }

    // ---------- sync (optional; see Sync.kt) ----------

    /** What this device would put in the sync file right now. */
    fun syncState() = SyncState(words, snippets, explicitStyles, customInstructions, settings = syncedSettings())

    /**
     * Every other setting the sync file carries, by its name there (the same names the desktop apps use), as
     * [SyncFormat.canon] text. Left out on purpose: the on-device model and how long recordings are kept
     * (they depend on this phone's storage), the apps that get the clipboard (this phone's launchers), what
     * the accessibility shortcut does (set up with this phone's own accessibility settings), and anything about
     * this screen or this network.
     */
    fun syncedSettings(): Map<String, String> {
        fun overrides(prefix: String) = JSONObject().also { o ->
            for ((k, v) in sp.all) if (k.startsWith(prefix) && v is String && v.isNotBlank()) o.put(k.removePrefix(prefix), v)
        }
        val v = mapOf<String, Any>(
            "stt" to stt.name,
            "cleanup" to cleanup.name,
            "sttModels" to overrides("sttmodel_"),
            "cleanupModels" to overrides("model_"),
            "autoStop" to autoStop,
            "quickSkip" to quickSkip,
            "liveStt" to liveStt,
            "weakSignal" to weakSignal,
            "autoLanguage" to autoLanguage,
            "polish" to polish,
            "sounds" to sounds,
            "soundSet" to soundSet,
            "theme" to theme,
            "accent" to (accent.toLong() and 0xFFFFFFFFL),
            "learnWords" to learnWords,
            "learnedWords" to JSONArray(learnedWords.toList()),
            "dismissedWords" to JSONArray(dismissedWords.toList()),
            "notesBeta" to notesBeta,
            "holdToTalk" to holdToTalk,
            "haptics" to haptics,
            "fitSentence" to fitSentence,
            "noteSkipSilence" to noteSkipSilence,
            "noteSkipUms" to noteSkipUms,
            "keyboardAutocorrect" to kbAutocorrect,
            "keyboardSwipe" to kbSwipe,
            "keyboardAutoCaps" to kbAutoCaps,
            "keyboardDoubleSpace" to kbDoubleSpace,
            "keyboardLearn" to kbLearn,
            "keyboardNumberRow" to kbNumberRow,
            "keyboardSymbolHints" to kbSymbolHints,
            "keyboardSound" to kbSound,
            "keyboardVibrate" to kbVibrate,
            "keyboardPopup" to kbPopup,
            "keyboardClipboard" to kbClipboard,
            "keyboardClipSuggest" to kbClipSuggest,
            "keyboardScreenshots" to kbScreenshots,
            "keyboardCodes" to kbCodes,
            "keyboardTheme" to kbTheme,
            "keyboardStyle" to kbStyle,
            "keyboardAccent" to (kbAccent.toLong() and 0xFFFFFFFFL),
            "keyboardToolbar" to kbToolbar,
            "keyboardHeight" to kbHeight,
            "headsetMic" to headsetMic,
            "headsetMicAll" to headsetMicAll,
            "shellCommands" to shellCommands,
            "shellCue" to shellCue,
            "noteSummaryLevel" to noteSummaryLevel,
            "noteSpeed" to noteSpeed.toDouble(),
            "notePhrases" to notePhrases,
            "androidAppOverrides" to overrides("app_"),
        )
        return v.mapNotNull { (k, x) -> SyncFormat.canon(x)?.let { k to it } }.toMap().toSortedMap()
    }

    /** Stores a merge result in one go, without counting as a change the user made. */
    fun applySynced(state: SyncState, keys: Map<String, String>) {
        val ed = sp.edit()
            .putString("words", JSONArray(state.words).toString())
            .putString("snippets", snippetsJson(state.snippets))
            .putString("custom", state.instructions)
        for (c in AppCategory.values()) {
            val style = state.styles[c.name]?.takeIf { name -> Style.values().any { it.name == name } }
            if (style != null) ed.putString("style_${c.name}", style) else ed.remove("style_${c.name}")
        }
        val now = syncedSettings()
        state.settings?.forEach { (name, text) ->
            if (now[name] != null && now[name] != text && usable(name, text)) putSetting(ed, name, SyncFormat.value(text)!!)
        }
        ed.apply()
        // Only fills a slot (or replaces a key that changed), so a key is never re-encrypted for nothing.
        keys.forEach { (service, v) -> if (v.isNotEmpty() && v != key(service)) setKeyQuietly(service, v) }
    }

    /** Writes one synced setting that [usable] has checked. */
    private fun putSetting(ed: SharedPreferences.Editor, name: String, v: Any) {
        fun strings(a: JSONArray) = JSONArray((0 until a.length()).map { a.getString(it) }).toString()
        fun overrides(prefix: String, o: JSONObject) {
            for (k in sp.all.keys) if (k.startsWith(prefix)) ed.remove(k)
            for (k in o.keys()) ed.putString(prefix + k, o.getString(k))
        }
        when (name) {
            "stt", "cleanup", "theme" -> ed.putString(name, v as String)
            "notePhrases" -> ed.putString("note_phrases", v as String)
            "soundSet" -> ed.putString("sound_set", v as String)
            "sttModels" -> overrides("sttmodel_", v as JSONObject)
            "cleanupModels" -> overrides("model_", v as JSONObject)
            "androidAppOverrides" -> overrides("app_", v as JSONObject)
            "accent" -> ed.putInt("accent", (v as Number).toLong().toInt())
            "keyboardAccent" -> ed.putInt("kb_accent", (v as Number).toLong().toInt())
            "keyboardTheme" -> ed.putString("kb_theme", v as String)
            "keyboardStyle" -> ed.putString("kb_style", v as String)
            "keyboardToolbar" -> ed.putString("kb_toolbar", v as String)
            "keyboardHeight" -> ed.putInt("kb_height", (v as Number).toInt())
            "noteSummaryLevel" -> ed.putInt("note_level", (v as Number).toInt())
            "noteSpeed" -> ed.putInt("note_speed", Math.round((v as Number).toDouble() * 100).toInt())
            "learnedWords" -> ed.putString("learned_words", strings(v as JSONArray))
            "dismissedWords" -> ed.putString("dismissed_words", strings(v as JSONArray))
            else -> BOOLEANS[name]?.let { ed.putBoolean(it, v as Boolean) }
        }
    }

    private fun setKeyQuietly(service: String, v: String) {
        val plain = Keys.clean(v)
        sp.edit().putString("key_$service", if (plain.isEmpty()) "" else Secrets.encrypt(plain) ?: plain).apply()
    }

    /** The chosen sync file (a document URI), or null when sync is off. */
    var syncUri: String?
        get() = syncSp.safeString("uri", null)?.takeIf { it.isNotEmpty() }
        set(v) = syncSp.edit().putString("uri", v).apply()

    /** "Include API keys": whether this device writes its keys into the sync file. */
    var syncKeys: Boolean
        get() = syncSp.safeBoolean("keys", false)
        set(v) = syncSp.edit().putBoolean("keys", v).apply()

    /** "Include dictation history": whether this device shares its dictations through the sync file. Off unless turned on. */
    var syncHistory: Boolean
        get() = syncSp.safeBoolean("history", false)
        set(v) = syncSp.edit().putBoolean("history", v).remove("history_base").apply()

    /** The sync file was last written by a newer Tokalot that syncs more than this one does. */
    var syncNewer: Boolean
        get() = syncSp.safeBoolean("newer", false)
        set(v) { if (v != syncNewer) syncSp.edit().putBoolean("newer", v).apply() }

    /** Each dictation's id and hash as the sync file had them after the last sync (see [SyncFormat.encodeHistoryBase]). */
    var syncHistoryBase: String?
        get() = syncSp.safeString("history_base", null)
        set(v) = syncSp.edit().putString("history_base", v).apply()

    /** The synced values as they were after the last successful sync (never keys); null before the first. */
    var syncBase: String?
        get() = syncSp.safeString("base", null)
        set(v) = syncSp.edit().putString("base", v).apply()

    /** Every device's usage as the sync file had it after the last sync (see [Usage.everywhere]). */
    var syncUsage: String?
        get() = syncSp.safeString("usage", null)
        set(v) = syncSp.edit().putString("usage", v).apply()

    /** When the last successful sync finished, in ms; 0 = never. */
    var syncLast: Long
        get() = try { syncSp.getLong("last", 0) } catch (_: ClassCastException) { 0 }
        set(v) = syncSp.edit().putLong("last", v).apply()

    /** Why the last attempt failed, ready to show; null if it worked. */
    var syncError: String?
        get() = syncSp.safeString("error", null)
        set(v) = syncSp.edit().putString("error", v).apply()

    /** The file's permission is gone: nothing is tried again until the user picks the file again. */
    var syncBroken: Boolean
        get() = syncSp.safeBoolean("broken", false)
        set(v) = syncSp.edit().putBoolean("broken", v).apply()

    /** Turns sync off on this device. The file itself and every setting stay as they are. */
    fun forgetSync() = syncSp.edit().clear().apply()

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.safeString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Color of the floating button's bars while it's listening. */
    var accent: Int
        get() = sp.safeInt("accent", Accents.DEFAULT)
        set(v) = sp.edit().putInt("accent", v).apply()

    /** Hold the button to talk instead of tap-to-start / tap-to-stop. */
    var holdToTalk: Boolean
        get() = sp.safeBoolean("hold", false)
        set(v) = sp.edit().putBoolean("hold", v).apply()

    /** Vibration feedback from the floating button. */
    var haptics: Boolean
        get() = sp.safeBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** Feedback sounds from the floating button. Off unless turned on. */
    var sounds: Boolean
        get() = sp.safeBoolean("sounds", false)
        set(v) = sp.edit().putBoolean("sounds", v).apply()

    // ---------- the Tokalot keyboard (see TokalotKeyboard) ----------

    /** Fix a mistyped word when space or punctuation is typed. */
    var kbAutocorrect: Boolean
        get() = sp.safeBoolean("kb_autocorrect", true)
        set(v) = sp.edit().putBoolean("kb_autocorrect", v).apply()
    /** Swipe typing: slide a finger across the letters. */
    var kbSwipe: Boolean
        get() = sp.safeBoolean("kb_swipe", true)
        set(v) = sp.edit().putBoolean("kb_swipe", v).apply()
    /** Capital letter at the start of each sentence. */
    var kbAutoCaps: Boolean
        get() = sp.safeBoolean("kb_autocap", true)
        set(v) = sp.edit().putBoolean("kb_autocap", v).apply()
    /** Two spaces type a period and a space. */
    var kbDoubleSpace: Boolean
        get() = sp.safeBoolean("kb_double_space", true)
        set(v) = sp.edit().putBoolean("kb_double_space", v).apply()
    /** Words typed that the keyboard doesn't know go into the dictionary once used twice (see KeyboardLearn). */
    var kbLearn: Boolean
        get() = sp.safeBoolean("kb_learn", true)
        set(v) = sp.edit().putBoolean("kb_learn", v).apply()
    /** A row of digits above the letters. */
    var kbNumberRow: Boolean
        get() = sp.safeBoolean("kb_number_row", false)
        set(v) = sp.edit().putBoolean("kb_number_row", v).apply()
    /** Each letter shows its long-press symbol in the corner. */
    var kbSymbolHints: Boolean
        get() = sp.safeBoolean("kb_symbol_hints", true)
        set(v) = sp.edit().putBoolean("kb_symbol_hints", v).apply()
    /** Click on each key. */
    var kbSound: Boolean
        get() = sp.safeBoolean("kb_sound", false)
        set(v) = sp.edit().putBoolean("kb_sound", v).apply()
    /** Short buzz on each key. */
    var kbVibrate: Boolean
        get() = sp.safeBoolean("kb_vibrate", true)
        set(v) = sp.edit().putBoolean("kb_vibrate", v).apply()
    /** The key shown bigger above the finger as it's pressed. */
    var kbPopup: Boolean
        get() = sp.safeBoolean("kb_popup", true)
        set(v) = sp.edit().putBoolean("kb_popup", v).apply()

    /** Things copied are kept in the keyboard's clipboard list (on this phone only). */
    var kbClipboard: Boolean
        get() = sp.safeBoolean("kb_clipboard", true)
        set(v) = sp.edit().putBoolean("kb_clipboard", v).apply()
    /** Something just copied shows on the suggestion bar, ready to paste. */
    var kbClipSuggest: Boolean
        get() = sp.safeBoolean("kb_clip_suggest", true)
        set(v) = sp.edit().putBoolean("kb_clip_suggest", v).apply()
    /** A screenshot just taken shows on the bar, ready to paste (needs access to photos). */
    var kbScreenshots: Boolean
        get() = sp.safeBoolean("kb_screenshots", true)
        set(v) = sp.edit().putBoolean("kb_screenshots", v).apply()
    /** A sign-in code that just came in a text shows on the bar (needs notification access; see CodeListener). */
    var kbCodes: Boolean
        get() = sp.safeBoolean("kb_codes", true)
        set(v) = sp.edit().putBoolean("kb_codes", v).apply()
    /** "auto" follows the phone's dark mode; "light" or "dark" always. */
    var kbTheme: String
        get() = sp.safeString("kb_theme", "auto")?.takeIf { it in KB_THEMES } ?: "auto"
        set(v) = sp.edit().putString("kb_theme", v).apply()
    /** How keys look: one of [KeyboardTheme.STYLES]. */
    var kbStyle: String
        get() = sp.safeString("kb_style", KeyboardTheme.CLASSIC)?.takeIf { it in KeyboardTheme.STYLES } ?: KeyboardTheme.CLASSIC
        set(v) = sp.edit().putString("kb_style", v).apply()
    /** The keyboard's key height, in percent of the usual (80-130). */
    var kbHeight: Int
        get() = sp.safeInt("kb_height", 100).coerceIn(KB_HEIGHTS)
        set(v) = sp.edit().putInt("kb_height", v.coerceIn(KB_HEIGHTS)).apply()
    /** The keyboard's top bar buttons, in order (see [KeyboardToolbar]). */
    var kbToolbar: String
        get() = KeyboardToolbar.format(KeyboardToolbar.parse(sp.safeString("kb_toolbar", null)))
        set(v) = sp.edit().putString("kb_toolbar", v).apply()
    /** The keyboard's accent colour; 0 = the same as the app's. */
    var kbAccent: Int
        get() = sp.safeInt("kb_accent", 0)
        set(v) = sp.edit().putInt("kb_accent", v).apply()

    /** Which Sounds style plays (soft, chime, retro, bubble). */
    var soundSet: String
        get() = Sounds.valid(sp.safeString("sound_set", null))
        set(v) = sp.edit().putString("sound_set", v).apply()

    /**
     * How long things have been taking lately, for the progress ring's estimate. Running averages, kept
     * separately for Wi-Fi and mobile data: "stt_wifi" / "stt_cell" (speech-to-text, milliseconds per second
     * of audio), "clean_…" (cleanup, milliseconds per character) and "ping_…" (see [Net.rtt]).
     */
    fun rate(key: String, def: Float): Float = runCatching { sp.getFloat("rate_$key", def) }.getOrDefault(def)

    /** Folds a new measurement into the running average kept under [key]. */
    fun learnRate(key: String, measured: Float) {
        val old = rate(key, measured)
        sp.edit().putFloat("rate_$key", old * 0.6f + measured * 0.4f).apply()
    }

    /** Beta: skip the AI cleanup when a short dictation has nothing for it to fix. */
    var quickSkip: Boolean
        get() = sp.safeBoolean("quick_skip", false)
        set(v) = sp.edit().putBoolean("quick_skip", v).apply()

    /** The last 20 dictations' timings, newest first, for the Speed section in Settings. */
    var speedLog: List<String>
        get() = runCatching {
            val arr = JSONArray(sp.safeString("speed_log", "[]"))
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("speed_log", JSONArray(v.take(20)).toString()).apply()

    /** Send long dictations to the speech service in pieces while they are still being spoken. */
    var liveStt: Boolean
        get() = sp.safeBoolean("live_stt", true)
        set(v) = sp.edit().putBoolean("live_stt", v).apply()

    /** Which on-device model to use (see [ModelManager.active] for when it isn't downloaded yet). */
    var localModel: LocalModel
        get() = LocalModel.of(sp.safeString("local_model", null))
        set(v) = sp.edit().putString("local_model", v.id).apply()

    /**
     * Weak signal: go straight to the on-device model when there's no connection or the service was slow to
     * answer while the user talked, and give the cloud only a few seconds before switching to it.
     */
    var weakSignal: Boolean
        get() = sp.safeBoolean("weak_signal", false)
        set(v) = sp.edit().putBoolean("weak_signal", v).apply()

    /** Stop automatically after 30 s of silence (tap mode only). */
    var autoStop: Boolean
        get() = sp.safeBoolean("autostop", true)
        set(v) = sp.edit().putBoolean("autostop", v).apply()

    /** Let cloud speech-to-text detect the language instead of forcing English. */
    var autoLanguage: Boolean
        get() = sp.safeBoolean("autolang", false)
        set(v) = sp.edit().putBoolean("autolang", v).apply()

    /** Let the cleanup model tighten and clarify the wording. Off: the user's own words are kept. */
    var polish: Boolean
        get() = sp.safeBoolean("polish", false)
        set(v) = sp.edit().putBoolean("polish", v).apply()

    /** Synced with the computers: dictating into Termux turns what you say into a shell command (typed, never run). */
    var shellCommands: Boolean
        get() = sp.safeBoolean("shell_commands", false)
        set(v) = sp.edit().putBoolean("shell_commands", v).apply()

    /** With Terminal commands on: only a dictation that starts with the word "shell" becomes a command; anything else is dictated normally. */
    var shellCue: Boolean
        get() = sp.safeBoolean("shell_cue", true)
        set(v) = sp.edit().putBoolean("shell_cue", v).apply()

    /**
     * Fit a dictation into the sentence around the cursor: no capital in the middle of a sentence, no
     * period when the sentence carries on. Only where the cursor sits is used, never the text itself.
     */
    var fitSentence: Boolean
        get() = sp.safeBoolean("fit_sentence", true)
        set(v) = sp.edit().putBoolean("fit_sentence", v).apply()

    /** Voice notes (beta): the Notes page, the "Voice note" Quick Settings tile and notes from the shortcut. */
    /** On a voice note: jump over stretches with no voice, and over "um"s. */
    var noteSkipSilence: Boolean
        get() = sp.safeBoolean("note_skip_silence", true)
        set(v) = sp.edit().putBoolean("note_skip_silence", v).apply()

    var noteSkipUms: Boolean
        get() = sp.safeBoolean("note_skip_ums", true)
        set(v) = sp.edit().putBoolean("note_skip_ums", v).apply()

    /** How long a note's summary is, 1 (one line) to 5 (everything). */
    var noteSummaryLevel: Int
        get() = sp.safeInt("note_level", 3).coerceIn(1, 5)
        set(v) = sp.edit().putInt("note_level", v).apply()

    /** Voice note playback speed, one of [NOTE_SPEEDS]. Kept as a percentage. */
    var noteSpeed: Float
        get() = (sp.safeInt("note_speed", 100) / 100f).takeIf { s -> NOTE_SPEEDS.any { Math.abs(it - s) < 0.001 } } ?: 1f
        set(v) = sp.edit().putInt("note_speed", Math.round(v * 100)).apply()

    /** Record from a connected Bluetooth headset's mic, falling back to the phone's when none is connected. */
    var headsetMic: Boolean
        get() = sp.safeBoolean("headset_mic", false)
        set(v) = sp.edit().putBoolean("headset_mic", v).apply()

    /** [headsetMic] for every dictation too, not only voice notes. */
    var headsetMicAll: Boolean
        get() = sp.safeBoolean("headset_mic_all", false)
        set(v) = sp.edit().putBoolean("headset_mic_all", v).apply()

    /** The "Tap any word to play from there" hint on a voice note was tapped through or closed. This phone only. */
    var noteWordHintSeen: Boolean
        get() = sp.safeBoolean("note_word_hint_seen", false)
        set(v) = sp.edit().putBoolean("note_word_hint_seen", v).apply()

    var notesBeta: Boolean
        get() = sp.safeBoolean("notes_beta", false)
        set(v) = sp.edit().putBoolean("notes_beta", v).apply()

    /** The "worth keeping?" card on Notes was closed or answered, so it stays hidden. */
    var notesFeedbackDone: Boolean
        get() = sp.safeBoolean("notes_feedback_done", false)
        set(v) = sp.edit().putBoolean("notes_feedback_done", v).apply()

    /**
     * Apps where, with no keyboard showing, dictated text goes on the clipboard instead of into the selected text
     * box. Starts out as the phone's launchers: a home screen can keep an invisible search box selected (Niagara does).
     */
    var clipboardApps: Set<String>
        get() = sp.getStringSet("clipboard_apps", null)?.toSet() ?: AppContext.launchers(app).also { clipboardApps = it }
        set(v) = sp.edit().putStringSet("clipboard_apps", v).apply()

    /** Phrases that, said at the very start of a dictation, make it a voice note (comma-separated). */
    var notePhrases: String
        get() = sp.safeString("note_phrases", null) ?: TextTools.DEFAULT_NOTE_PHRASES
        set(v) = sp.edit().putString("note_phrases", v).apply()

    /**
     * What Android's accessibility shortcut (holding both volume keys, or the accessibility button) does:
     * "off", "dictate" (type into the text box, or copy when there isn't one) or "note" (a voice note).
     */
    var shortcutAction: String
        get() = sp.safeString("shortcut_action", "off") ?: "off"
        set(v) = sp.edit().putString("shortcut_action", v).apply()

    /** The cloud speech service in use before cloud speech was switched off on the Privacy page, to go back to. This phone only. */
    var lastCloudStt: SttChoice
        get() = enumOr(sp.safeString("last_cloud_stt", null), SttChoice.GROQ)
        set(v) = sp.edit().putString("last_cloud_stt", v.name).apply()

    /** Same for the AI cleanup service. */
    var lastCleanup: CleanupChoice
        get() = enumOr(sp.safeString("last_cleanup", null), CleanupChoice.GROQ)
        set(v) = sp.edit().putString("last_cleanup", v.name).apply()

    /** The first-run welcome (privacy level and setup) was finished or skipped, or wasn't needed (see [Welcome]). */
    var welcomeDone: Boolean
        get() = sp.safeBoolean("welcome_done", false)
        set(v) = sp.edit().putBoolean("welcome_done", v).apply()

    /** The welcome has started, and set the level it opens on. */
    var welcomeStarted: Boolean
        get() = sp.safeBoolean("welcome_started", false)
        set(v) = sp.edit().putBoolean("welcome_started", v).apply()

    /** The one-time Home card that introduces privacy levels to people who updated (see [Welcome]). */
    var privacyCardSeen: Boolean
        get() = sp.safeBoolean("privacy_card_seen", false)
        set(v) = sp.edit().putBoolean("privacy_card_seen", v).apply()

    /** How long to keep recordings: 0 = don't save, Int.MAX_VALUE = forever. */
    var audioKeepDays: Int
        get() = sp.safeInt("audio_days", 30)
        set(v) = sp.edit().putInt("audio_days", v).apply()

    /** Cloud STT is only used when a key for it exists; otherwise we silently stay on-device. */
    val cloudSttReady get() = stt != SttChoice.LOCAL && key(stt.service).isNotEmpty()
    val cleanupReady get() = cleanup != CleanupChoice.OFF && key(cleanup.service).isNotEmpty()
}

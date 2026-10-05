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
    fun syncState() = SyncState(words, snippets, explicitStyles, customInstructions)

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
        ed.apply()
        // Only fills a slot (or replaces a key that changed), so a key is never re-encrypted for nothing.
        keys.forEach { (service, v) -> if (v.isNotEmpty() && v != key(service)) setKeyQuietly(service, v) }
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

    /** The synced values as they were after the last successful sync (never keys); null before the first. */
    var syncBase: String?
        get() = syncSp.safeString("base", null)
        set(v) = syncSp.edit().putString("base", v).apply()

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

    /** Send long dictations to the speech service in pieces while they are still being spoken. */
    var liveStt: Boolean
        get() = sp.safeBoolean("live_stt", true)
        set(v) = sp.edit().putBoolean("live_stt", v).apply()

    /** Stop automatically after 30 s of silence (tap mode only). */
    var autoStop: Boolean
        get() = sp.safeBoolean("autostop", true)
        set(v) = sp.edit().putBoolean("autostop", v).apply()

    /** Let cloud speech-to-text detect the language instead of forcing English. */
    var autoLanguage: Boolean
        get() = sp.safeBoolean("autolang", false)
        set(v) = sp.edit().putBoolean("autolang", v).apply()

    /**
     * Let the cleanup model tighten and clarify the wording. Off: the user's own words are kept.
     * A per-device setting: it is in backups, but not in the sync file.
     */
    var polish: Boolean
        get() = sp.safeBoolean("polish", false)
        set(v) = sp.edit().putBoolean("polish", v).apply()

    /** How long to keep recordings: 0 = don't save, Int.MAX_VALUE = forever. */
    var audioKeepDays: Int
        get() = sp.safeInt("audio_days", 30)
        set(v) = sp.edit().putInt("audio_days", v).apply()

    /** Cloud STT is only used when a key for it exists; otherwise we silently stay on-device. */
    val cloudSttReady get() = stt != SttChoice.LOCAL && key(stt.service).isNotEmpty()
    val cleanupReady get() = cleanup != CleanupChoice.OFF && key(cleanup.service).isNotEmpty()
}

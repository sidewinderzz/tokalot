package com.tokalot.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Tokalot as a keyboard: Gboard-style typing with autocorrect ([Autocorrect]), next-word suggestions, swipe
 * typing ([Glide]) and an emoji page, plus Tokalot's own two buttons on the suggestion bar: the mic
 * (dictation, the same as the floating button) and AI fix (the cleanup, run on what was typed).
 *
 * The word being typed is kept as "composing" text (underlined in most apps) until a space or punctuation
 * ends it, so autocorrect can still replace it. Everything else goes straight into the text box.
 */
class TokalotKeyboard : InputMethodService(), KeyboardView.Listener, EmojiPanel.Listener {

    companion object {
        @Volatile var instance: TokalotKeyboard? = null
            private set
        /** The keyboard is on screen, so the floating mic button stays out of its way. */
        @Volatile var shown = false
            private set

        // Loaded once per process, off the main thread, and only used on it.
        private var words: KeyboardWords? = null
        private var history: TypingHistory? = null
        private var emoji: EmojiData? = null
        private var loading = false
        /** The dictionary words already added to [words] (removed ones stay until the app restarts). */
        private val userWords = HashSet<String>()

        private const val HISTORY_FILE = "typing_history.txt"
        private const val RECENT_MAX = 32
        /** Typed after a word, these end it and let autocorrect fix it. */
        private const val CORRECT_AFTER = ".,!?;:)]}\"\n"
        /** Typed right after an automatic space, these take its place ("word ." becomes "word. "). */
        private const val SWAP_SPACE = ".,!?;:"
        private const val AROUND = 200
        private const val FIX_MAX = 6000

        /** Settings: forgets the words and pairs the keyboard learned from typing. */
        fun clearHistory(ctx: Context) {
            history?.clear()
            File(ctx.filesDir, HISTORY_FILE).delete()
        }

        /** Whether Tokalot is the keyboard picked in Android's settings. */
        fun isCurrent(ctx: Context): Boolean {
            val id = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
            return id.startsWith(ctx.packageName + "/")
        }

        /** Whether Tokalot's keyboard is switched on in Android's settings (it can then be picked). */
        fun isEnabled(ctx: Context): Boolean {
            val imm = ctx.getSystemService(InputMethodManager::class.java) ?: return false
            return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var kv: KeyboardView? = null
    private var panel: EmojiPanel? = null
    private lateinit var prefs: Prefs

    private var ac: Autocorrect? = null
    private var glide: Glide? = null

    // ------------------------------------------------------------------ what this text box allows

    /** Suggestions and autocorrect: ordinary text, not passwords, addresses or numbers. */
    private var predictive = false
    /** Typing may be remembered (not a password, not an incognito tab). */
    private var personal = false
    private var autocorrect = true
    private var vibrate = true
    private var sound = false

    // ------------------------------------------------------------------ the word being typed

    private val word = StringBuilder()
    /** Where each letter was touched, in key widths and rows ([Autocorrect]'s units); NaN when not tapped. */
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    /** The word before it, for context. */
    private var wordPrev: String? = null
    /** It came from a swipe: the bar shows [glideWords], and a backspace takes all of it. */
    private var glided = false
    private var glideWords: List<String> = emptyList()
    /** A swiped word wants a space before whatever word comes next (but not before punctuation). */
    private var phantomSpace = false
    /** A space was added after a picked suggestion; punctuation typed next takes its place. */
    private var autoSpaced = false
    private var lastSpaceAt = 0L

    /** The last autocorrection, which a backspace straight after undoes. */
    private class Correction(val typed: String, val corrected: String, val sep: String, val prev: String?)
    private var lastCorrection: Correction? = null
    /** A word whose correction was undone: left alone if it's ended again. */
    private var keepAsTyped: String? = null

    private var lettersPage: KeyboardPage? = null
    private val symbolsPage by lazy { Layouts.symbols() }
    private val morePage by lazy { Layouts.moreSymbols() }

    // ------------------------------------------------------------------ setup

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        load()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        shown = false
        cancelMic()
        fixCall?.cancel()
        saveHistory()
        super.onDestroy()
    }

    /** Never the full-screen editing mode in landscape: the app stays visible above the keys, as with Gboard. */
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val k = KeyboardView(this, this)
        val e = EmojiPanel(this, this).apply { visibility = View.GONE }
        kv = k; panel = e
        emoji?.let { e.data = it }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** The word list, typing history and emoji, read once in the background. */
    private fun load() {
        if (words != null) { loaded(); return }
        if (loading) return
        loading = true
        val app = applicationContext
        Thread {
            val w = runCatching {
                app.assets.open("keyboard/en_US.txt.gz").use { KeyboardWords.load(GZIPInputStream(it).bufferedReader()) }
            }.getOrNull()
            val h = runCatching {
                File(app.filesDir, HISTORY_FILE).takeIf { it.exists() }?.readText()?.let { TypingHistory.parse(it) }
            }.getOrNull() ?: TypingHistory()
            val e = runCatching { app.assets.open("keyboard/emoji.txt").bufferedReader().use { EmojiData.load(it) } }.getOrNull()
            main.post {
                loading = false
                words = w; history = h; emoji = e
                instance?.loaded()
            }
        }.start()
    }

    private fun loaded() {
        val w = words ?: return
        addUserWords()
        if (ac == null) ac = Autocorrect(w, history)
        if (glide == null) glide = Glide(w)
        emoji?.let { e -> panel?.let { if (it.data == null) it.data = e } }
        onLayoutChanged()
        showStrip()
    }

    /** Tokalot's dictionary words (typed in, learned, or synced from another device) count as known words. */
    private fun addUserWords() {
        val w = words ?: return
        val add = prefs.words.filter { it !in userWords }
        if (add.isEmpty()) return
        w.addUserWords(add)
        userWords += add
        glide?.refreshWords()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetWord()
        lastCorrection = null; keepAsTyped = null
        autoSpaced = false; phantomSpace = false
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val k = kv ?: return
        prefs = Prefs(this)
        shown = true
        OfflineFlowService.instance?.keyboardChanged()

        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        predictive = cls == InputType.TYPE_CLASS_TEXT && !password && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER,
        ) && type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        personal = !password && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 && prefs.kbLearn
        autocorrect = prefs.kbAutocorrect
        vibrate = prefs.kbVibrate
        sound = prefs.kbSound

        val dark = when (prefs.theme) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val theme = if (dark) KeyboardTheme.dark(prefs.accent) else KeyboardTheme.light(prefs.accent)
        k.theme = theme
        panel?.theme = theme
        window?.window?.let { w ->
            w.navigationBarColor = theme.background
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        k.keyPopups = prefs.kbPopup
        k.symbolHints = prefs.kbSymbolHints
        k.enterKind = enterKind(info)

        if (!restarting || emojiMode != EmojiMode.OFF) {
            closeEmoji(show = false)
            lettersPage = null // the number row or symbol hints may have been switched in Settings
            k.shift = KeyboardView.Shift.OFF
            showPage(when (cls) {
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Layouts.numbers(phone = false)
                InputType.TYPE_CLASS_PHONE -> Layouts.numbers(phone = true)
                else -> letters()
            })
        } else {
            k.glideEnabled = canGlide()
        }
        k.reset()
        addUserWords()
        load()
        updateShift()
        showStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        shown = false
        OfflineFlowService.instance?.keyboardChanged()
        kv?.reset()
        if (micState == Mic.RECORDING) stopMic()
        commitWord() // or the app keeps the word underlined
        saveHistory()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        shown = false
        OfflineFlowService.instance?.keyboardChanged()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val ic = currentInputConnection ?: return
        if (slideAnchor >= 0) return
        // These reports trail behind what was typed, so the text itself is checked before deciding the
        // cursor was moved away (a tap elsewhere in the text, or the app changing it).
        if (word.isNotEmpty()) {
            if (candidatesStart >= 0 && newSelStart == candidatesEnd && newSelEnd == candidatesEnd) return
            val before = ic.getTextBeforeCursor(word.length, 0)?.toString()
            if (before == word.toString() && newSelStart == newSelEnd) return
            ic.finishComposingText()
            resetWord()
            phantomSpace = false
        } else {
            lastCorrection?.let { c ->
                if (ic.getTextBeforeCursor(c.corrected.length + c.sep.length, 0)?.toString() != c.corrected + c.sep) lastCorrection = null
            }
            if (autoSpaced && ic.getTextBeforeCursor(1, 0)?.toString() != " ") autoSpaced = false
        }
        if (newSelStart == newSelEnd) updateShift()
        showStrip()
    }

    private fun enterKind(info: EditorInfo): Int {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return 0
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> 1
            EditorInfo.IME_ACTION_SEND -> 2
            EditorInfo.IME_ACTION_NEXT -> 3
            EditorInfo.IME_ACTION_DONE -> 4
            EditorInfo.IME_ACTION_GO -> 5
            else -> 0
        }
    }

    // ------------------------------------------------------------------ pages

    private fun letters(): KeyboardPage =
        lettersPage ?: Layouts.letters(prefs.kbNumberRow, prefs.kbSymbolHints).also { lettersPage = it }

    private fun canGlide() = kv?.page?.kind == PageKind.LETTERS && predictive && prefs.kbSwipe && emojiMode == EmojiMode.OFF

    private fun showPage(p: KeyboardPage) {
        val k = kv ?: return
        k.page = p
        k.glideEnabled = canGlide()
        k.relayout()
    }

    override fun onLayoutChanged() {
        val p = kv?.page ?: return
        if (p.kind != PageKind.LETTERS || p.unit <= 0f) return
        ac?.centers = p.letterCenters()
        glide?.setLayout(p.letterPixels(), p.unit, p.rowHeight)
    }

    /** Shift on at the start of a sentence (as the text box asks), off otherwise; caps lock stays. */
    private fun updateShift() {
        val k = kv ?: return
        if (k.page.kind != PageKind.LETTERS || k.shift == KeyboardView.Shift.LOCKED || word.isNotEmpty()) return
        val ei = currentInputEditorInfo ?: return
        val caps = prefs.kbAutoCaps && (currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0) != 0
        k.shift = if (caps) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
    }

    // ------------------------------------------------------------------ keys

    override fun onPress(code: Int) {
        val k = kv ?: return
        if (vibrate) {
            @Suppress("DEPRECATION")
            k.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (sound) {
            val fx = when (code) {
                KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyCode.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyCode.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            getSystemService(AudioManager::class.java)?.playSoundEffect(fx, -1f)
        }
    }

    override fun onKey(code: Int, x: Float, y: Float) {
        if (emojiMode == EmojiMode.SEARCH) { searchKey(code); return }
        if (code != KeyCode.SHIFT) dropNotice()
        when (code) {
            KeyCode.SHIFT -> {}
            KeyCode.DELETE -> delete()
            KeyCode.ENTER -> enter()
            KeyCode.SYMBOLS -> showPage(symbolsPage)
            KeyCode.MORE_SYMBOLS -> showPage(morePage)
            KeyCode.LETTERS -> { showPage(letters()); updateShift() }
            KeyCode.EMOJI -> openEmoji()
            KeyCode.SPACE -> space()
            else -> type(code.toChar(), x, y)
        }
    }

    override fun onText(text: String) {
        if (emojiMode == EmojiMode.SEARCH) { text.forEach { searchKey(it.code) }; return }
        dropNotice()
        if (text.length == 1) type(text[0], Float.NaN, Float.NaN)
        else { commitWord(); currentInputConnection?.commitText(text, 1); showStrip() }
    }

    override fun onLongPress(code: Int): Boolean = when (code) {
        KeyCode.SPACE -> {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }
        ','.code -> {
            openApp(settings = true)
            true
        }
        else -> false
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || ((c == '\'' || c == '’' || c == '-') && word.isNotEmpty() && !glided)

    private fun type(c: Char, x: Float, y: Float) {
        if (isWordChar(c)) letter(c, x, y) else punctuation(c.toString())
    }

    private fun letter(c: Char, x: Float, y: Float) {
        val ic = currentInputConnection ?: return
        val k = kv ?: return
        if (glided || phantomSpace) {
            // A word typed after a swiped one starts with a space.
            val space = phantomSpace
            commitWord()
            if (space) ic.commitText(" ", 1)
        }
        lastCorrection = null
        autoSpaced = false
        val ch = if (k.shift != KeyboardView.Shift.OFF && c.isLetter()) c.uppercaseChar() else c
        if (k.shift == KeyboardView.Shift.ON) k.shift = KeyboardView.Shift.OFF
        if (!predictive) {
            ic.commitText(ch.toString(), 1)
            return
        }
        if (word.isEmpty()) wordPrev = prevWord(ic.getTextBeforeCursor(64, 0))
        word.append(ch)
        val p = k.page
        val first = if (p.kind == PageKind.LETTERS && prefs.kbNumberRow) 1 else 0
        val tapped = !x.isNaN() && p.kind == PageKind.LETTERS && p.unit > 0f && c.isLetter()
        xs += if (tapped) x / p.unit else Float.NaN
        ys += if (tapped) y / p.rowHeight - first - 0.5f else Float.NaN
        ic.setComposingText(word, 1)
        showStrip()
    }

    private fun punctuation(s: String) {
        val ic = currentInputConnection ?: return
        if (word.isNotEmpty()) {
            endWord(s, correct = s[0] in CORRECT_AFTER)
        } else if (autoSpaced && s[0] in SWAP_SPACE && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.deleteSurroundingText(1, 0)
            ic.commitText("$s ", 1)
        } else {
            ic.commitText(s, 1)
        }
        // After "word." the automatic space stays put: "word. " is ready for the next sentence.
        autoSpaced = autoSpaced && s[0] in SWAP_SPACE
        phantomSpace = false
        updateShift()
        showStrip()
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (word.isNotEmpty()) {
            endWord(" ", correct = true)
            lastSpaceAt = now
        } else {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (prefs.kbDoubleSpace && now - lastSpaceAt < 800 && before.length == 2 && before[1] == ' ' &&
                (before[0].isLetterOrDigit() || before[0] in ")\"'”’")
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0
            } else {
                ic.commitText(" ", 1)
                lastSpaceAt = now
            }
            lastCorrection = null
        }
        autoSpaced = false
        phantomSpace = false
        val kind = kv?.page?.kind
        if (kind == PageKind.SYMBOLS || kind == PageKind.MORE_SYMBOLS) showPage(letters())
        updateShift()
        showStrip()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        if (word.isNotEmpty()) endWord("", correct = true)
        autoSpaced = false; phantomSpace = false; lastCorrection = null
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ei == null || ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        when {
            !noAction -> ic.performEditorAction(action)
            ei != null && ei.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 -> ic.commitText("\n", 1)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateShift()
        showStrip()
    }

    private fun delete() {
        val ic = currentInputConnection ?: return
        val c = lastCorrection
        lastCorrection = null
        if (c != null && word.isEmpty()) {
            // Straight after an autocorrection: back to what was typed. Undoing one counts as using the word,
            // so twice and it's learned.
            if (ic.getTextBeforeCursor(c.corrected.length + c.sep.length, 0)?.toString() == c.corrected + c.sep) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(c.corrected.length + c.sep.length, 0)
                ic.commitText(c.typed + c.sep, 1)
                ic.endBatchEdit()
                keepAsTyped = c.typed
                remember(c.typed, c.prev, kept = true)
                showStrip()
                return
            }
        }
        if (word.isNotEmpty()) {
            if (glided) {
                // A swiped word goes in one go, as on Gboard.
                ic.setComposingText("", 1)
                ic.finishComposingText()
                resetWord()
                phantomSpace = false
            } else {
                val n = if (word.length >= 2 && Character.isLowSurrogate(word[word.length - 1])) 2 else 1
                word.setLength(word.length - n)
                repeat(n) { if (xs.isNotEmpty()) { xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1) } }
                ic.setComposingText(word, 1)
                if (word.isEmpty()) { ic.finishComposingText(); resetWord() }
            }
            if (word.isEmpty()) updateShift()
            showStrip()
            return
        }
        autoSpaced = false
        phantomSpace = false
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (predictive) resumeWord(ic)
        updateShift()
        showStrip()
    }

    /** Backspaced to the end of a word: it becomes the word being typed again, so suggestions come back. */
    private fun resumeWord(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString()
        if (after != null && after.isNotEmpty() && after[0].isLetterOrDigit()) return
        var i = before.length
        while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '\'' || before[i - 1] == '’')) i--
        val w = before.substring(i)
        if (w.isEmpty() || i == 0 && before.length >= 48 || !w.any { it.isLetter() }) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(w.length, 0)
        ic.setComposingText(w, 1)
        ic.endBatchEdit()
        word.setLength(0); word.append(w)
        xs.clear(); ys.clear()
        repeat(w.length) { xs += Float.NaN; ys += Float.NaN }
        wordPrev = prevWord(before.substring(0, i))
        glided = false
    }

    // ------------------------------------------------------------------ ending words

    /** Ends the word being typed with [sep], autocorrecting it first if [correct] and allowed here. */
    private fun endWord(sep: String, correct: Boolean) {
        val ic = currentInputConnection ?: return
        val typed = word.toString()
        val prev = wordPrev
        var final = typed
        if (correct && autocorrect && !glided && typed != keepAsTyped) {
            ac?.suggest(typed, prev, xs.toFloatArray(), ys.toFloatArray(), auto = true)?.correction?.let { final = it }
        }
        ic.commitText(final + sep, 1)
        lastCorrection = if (final != typed) Correction(typed, final, sep, prev) else null
        if (typed != keepAsTyped) keepAsTyped = null
        remember(final, prev, kept = final == typed && !glided)
        resetWord()
    }

    /** Commits the word being typed as it stands. */
    private fun commitWord() {
        if (word.isEmpty()) return
        currentInputConnection?.finishComposingText()
        remember(word.toString(), wordPrev, kept = !glided)
        resetWord()
        phantomSpace = false
    }

    private fun resetWord() {
        word.setLength(0); xs.clear(); ys.clear()
        wordPrev = null
        glided = false
        glideWords = emptyList()
    }

    /**
     * The word was used. [kept]: typed by the user and left as typed, so a word the list doesn't know is
     * learned once it has been kept [KeyboardLearn.TIMES] times.
     */
    private fun remember(w: String, prev: String?, kept: Boolean) {
        if (!personal) return
        val h = history ?: return
        h.used(w, prev)
        val list = words ?: return
        if (kept && KeyboardLearn.worth(w, list) && h.count(w) >= KeyboardLearn.TIMES) learn(w)
    }

    private fun learn(w: String) {
        val p = prefs
        if (p.words.any { it.equals(w, ignoreCase = true) }) return
        p.words = p.words + w
        p.learnedWords = p.learnedWords + w
        words?.addUserWords(listOf(w))
        userWords += w
        glide?.refreshWords()
        notice("Learned “$w” · tap to undo", 5000) {
            val q = Prefs(this)
            q.words = q.words.filter { it != w }
            q.learnedWords = q.learnedWords - w
            history?.forget(w)
            notice("Removed “$w”", 2000)
        }
    }

    /** The word just before the end of [before] (null at the start of a sentence or after other punctuation). */
    private fun prevWord(before: CharSequence?): String? {
        val t = before ?: return null
        var i = t.length
        while (i > 0 && t[i - 1] == ' ') i--
        if (i == 0 || !(t[i - 1].isLetterOrDigit() || t[i - 1] == '\'')) return null
        var j = i
        while (j > 0 && (t[j - 1].isLetterOrDigit() || t[j - 1] == '\'' || t[j - 1] == '’')) j--
        return t.substring(j, i)
    }

    private fun sentenceStart(before: CharSequence?): Boolean {
        val t = before?.toString()?.trimEnd(' ') ?: return true
        return t.isEmpty() || t.last() in ".!?\n"
    }

    // ------------------------------------------------------------------ the suggestion bar

    override fun onStrip(slot: Int) {
        when (slot) {
            KeyboardView.SLOT_MIC -> if (emojiMode == EmojiMode.OFF) mic()
            KeyboardView.SLOT_FIX -> fix()
            KeyboardView.SLOT_CANCEL -> when {
                emojiMode == EmojiMode.SEARCH -> searchDone()
                micState != Mic.IDLE -> cancelMic()
                fixCall != null -> { fixCall?.cancel(); fixCall = null; endBusy() }
            }
            KeyboardView.SLOT_MESSAGE -> {
                val a = noticeAction
                dropNotice()
                a?.invoke()
            }
            else -> pick(slot)
        }
    }

    private fun pick(slot: Int) {
        val ic = currentInputConnection ?: return
        val s = kv?.suggestions?.getOrNull(slot) ?: return
        dropNotice()
        if (word.isNotEmpty()) {
            if (glided) {
                // Another of the swipe's words: swapped in, still waiting for what comes next.
                word.setLength(0); word.append(s)
                ic.setComposingText(s, 1)
                showStrip()
                return
            }
            val typed = word.toString()
            val prev = wordPrev
            ic.commitText("$s ", 1)
            remember(s, prev, kept = s == typed)
            resetWord()
        } else {
            val prev = prevWord(ic.getTextBeforeCursor(64, 0))
            ic.commitText("$s ", 1)
            remember(s, prev, kept = false)
        }
        lastCorrection = null
        autoSpaced = true
        lastSpaceAt = 0
        updateShift()
        showStrip()
    }

    /** Fills the bar: the word's corrections and completions, or next-word guesses between words. */
    private fun showStrip() {
        val k = kv ?: return
        if (busy() || notice != null || emojiMode == EmojiMode.SEARCH) return
        k.message = null
        k.cancelable = false
        val a = ac
        if (!predictive || a == null) { k.suggestions = emptyList(); k.boldSlot = -1; return }
        if (word.isNotEmpty()) {
            if (glided) {
                val current = word.toString()
                val others = glideWords.filter { it != current }
                k.suggestions = listOfNotNull(others.getOrNull(0), current, others.getOrNull(1))
                k.boldSlot = k.suggestions.indexOf(current)
                return
            }
            val r = a.suggest(word.toString(), wordPrev, xs.toFloatArray(), ys.toFloatArray(), auto = autocorrect && word.toString() != keepAsTyped)
            k.suggestions = r.shown
            k.boldSlot = r.pick
            return
        }
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(64, 0)
        val prev = prevWord(before)
        val list = when {
            before != null && before.isNotEmpty() && before.last() != ' ' && before.last() != '\n' -> emptyList()
            prev != null -> a.predict(prev, 3)
            sentenceStart(before) -> a.predict(null, 3)
            else -> emptyList()
        }
        val caps = k.shift != KeyboardView.Shift.OFF
        k.suggestions = list.map { if (caps) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
        k.boldSlot = -1
    }

    // ---- short notices on the bar ("Learned …", "Fixed · tap to undo")

    private var notice: String? = null
    private var noticeAction: (() -> Unit)? = null
    private val noticeEnd = Runnable { dropNotice() }

    private fun notice(text: String, ms: Long, action: (() -> Unit)? = null) {
        val k = kv ?: return
        notice = text
        noticeAction = action
        main.removeCallbacks(noticeEnd)
        main.postDelayed(noticeEnd, ms)
        if (!busy()) { k.message = text; k.cancelable = false }
    }

    /** Typing on, or the notice's time running out, clears it. */
    private fun dropNotice() {
        if (notice == null) return
        main.removeCallbacks(noticeEnd)
        notice = null
        noticeAction = null
        if (!busy() && emojiMode != EmojiMode.SEARCH) { kv?.message = null; showStrip() }
    }

    private fun busy() = micState != Mic.IDLE || fixCall != null

    /** The mic or the fix finished: the bar goes back to suggestions (or a notice left for after). */
    private fun endBusy() {
        val k = kv ?: return
        k.cancelable = false
        k.recording = false
        k.message = notice
        if (notice == null) showStrip()
    }

    // ------------------------------------------------------------------ swipe typing

    private var glideSpace = false

    override fun onGlideStart(x: Float, y: Float) {
        val ic = currentInputConnection ?: return
        dropNotice()
        if (word.isNotEmpty()) {
            if (glided) commitWord() else endWord("", correct = true)
            glideSpace = true
        } else {
            val b = ic.getTextBeforeCursor(1, 0)?.toString()
            glideSpace = !b.isNullOrEmpty() && !b[0].isWhitespace() && b[0] !in "([{\"'/@#“‘"
        }
        lastCorrection = null
        autoSpaced = false
        glide?.start(x, y)
    }

    override fun onGlideMove(x: Float, y: Float) { glide?.add(x, y) }

    override fun onGlideEnd() {
        val ic = currentInputConnection ?: return
        val g = glide ?: return
        val k = kv ?: return
        val prev = prevWord(ic.getTextBeforeCursor(64, 0))
        val found = g.words(5, prev, if (personal) history else null)
        if (found.isEmpty()) return
        val case: (String) -> String = when (k.shift) {
            KeyboardView.Shift.LOCKED -> { s -> s.uppercase() }
            KeyboardView.Shift.ON -> { s -> s.replaceFirstChar { it.uppercaseChar() } }
            KeyboardView.Shift.OFF -> { s -> s }
        }
        val list = found.map(case).distinct()
        if (glideSpace) ic.commitText(" ", 1)
        wordPrev = prev
        word.setLength(0); word.append(list[0])
        xs.clear(); ys.clear()
        glided = true
        glideWords = list
        phantomSpace = true
        ic.setComposingText(word, 1)
        if (k.shift == KeyboardView.Shift.ON) k.shift = KeyboardView.Shift.OFF
        showStrip()
    }

    // ------------------------------------------------------------------ cursor and word-delete slides

    override fun onCursorSlide(steps: Int) {
        commitWord()
        autoSpaced = false; lastCorrection = null
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(key) }
    }

    private var slideAnchor = -1
    private var slideBefore = ""

    override fun onDeleteSlide(words: Int, done: Boolean) {
        val ic = currentInputConnection ?: return
        if (slideAnchor < 0) {
            commitWord()
            autoSpaced = false; lastCorrection = null
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            slideAnchor = if (et != null && et.selectionStart >= 0) et.startOffset + maxOf(et.selectionStart, et.selectionEnd) else 0
            slideBefore = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            if (et == null) slideAnchor = Int.MAX_VALUE // positions unknown: only the final delete is done
        }
        val n = wordsBack(slideBefore, words)
        val known = slideAnchor != Int.MAX_VALUE
        if (!done) {
            if (known) ic.setSelection(slideAnchor - n, slideAnchor)
            return
        }
        if (known) {
            ic.setSelection(slideAnchor - n, slideAnchor)
            if (n > 0) ic.commitText("", 1)
        } else if (n > 0) {
            ic.deleteSurroundingText(n, 0)
        }
        slideAnchor = -1
        updateShift()
        showStrip()
    }

    /** How many characters the last [n] words of [t] take, with the spaces after them. */
    private fun wordsBack(t: String, n: Int): Int {
        var i = t.length
        repeat(n) {
            while (i > 0 && t[i - 1].isWhitespace()) i--
            if (i > 0 && !t[i - 1].isLetterOrDigit()) { i--; return@repeat }
            while (i > 0 && !t[i - 1].isWhitespace() && (t[i - 1].isLetterOrDigit() || t[i - 1] == '\'' || t[i - 1] == '’')) i--
        }
        return t.length - i
    }

    // ------------------------------------------------------------------ emoji

    private enum class EmojiMode { OFF, GRID, SEARCH }
    private var emojiMode = EmojiMode.OFF
    private val query = StringBuilder()

    private fun emojiPrefs() = getSharedPreferences("keyboard", Context.MODE_PRIVATE)

    private fun openEmoji() {
        val k = kv ?: return
        val e = panel ?: return
        commitWord()
        if (e.data == null) emoji?.let { e.data = it }
        val sp = emojiPrefs()
        e.recents = sp.getString("emoji_recent", "")!!.split('\n').filter { it.isNotEmpty() }
        e.tones = sp.getString("emoji_tones", "")!!.split('\n').mapNotNull { line ->
            val t = line.split('\t')
            if (t.size == 2) t[1].toIntOrNull()?.let { t[0] to it } else null
        }.toMap()
        e.gridHeight = k.height
        e.results = null
        e.reset()
        emojiMode = EmojiMode.GRID
        e.visibility = View.VISIBLE
        k.visibility = View.GONE
    }

    /** Back to the letters ([show]: and show them; false when a new text box is about to set the page). */
    private fun closeEmoji(show: Boolean = true) {
        if (emojiMode == EmojiMode.OFF) return
        emojiMode = EmojiMode.OFF
        query.setLength(0)
        panel?.visibility = View.GONE
        panel?.results = null
        kv?.visibility = View.VISIBLE
        kv?.message = null
        kv?.cancelable = false
        if (show) { showPage(letters()); updateShift(); showStrip() }
    }

    override fun onEmoji(text: String) {
        val ic = currentInputConnection ?: return
        commitWord()
        if (phantomSpace) ic.commitText(" ", 1)
        phantomSpace = false; autoSpaced = false; lastCorrection = null
        ic.commitText(text, 1)
        val e = panel ?: return
        val recent = (listOf(text) + e.recents.filter { it != text }).take(RECENT_MAX)
        emojiPrefs().edit()
            .putString("emoji_recent", recent.joinToString("\n"))
            .putString("emoji_tones", e.tones.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()
        // The recent row updates next time the page opens, so the grid doesn't jump under the finger.
    }

    override fun onEmojiDelete() {
        commitWord()
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEmojiAbc() = closeEmoji()

    override fun onEmojiSearch() {
        val k = kv ?: return
        val e = panel ?: return
        emojiMode = EmojiMode.SEARCH
        query.setLength(0)
        e.results = e.recents.map { EmojiData.Emoji(it, false, emptyList()) }
        k.visibility = View.VISIBLE
        k.shift = KeyboardView.Shift.OFF
        showPage(letters())
        searchBar()
    }

    private fun searchBar() {
        val k = kv ?: return
        k.message = if (query.isEmpty()) "Search emoji" else "🔍 $query"
        k.cancelable = true
    }

    private fun searchKey(code: Int) {
        val e = panel ?: return
        when (code) {
            KeyCode.DELETE -> if (query.isNotEmpty()) query.setLength(query.length - 1)
            KeyCode.ENTER, KeyCode.EMOJI -> { searchDone(); return }
            KeyCode.SYMBOLS -> { showPage(symbolsPage); return }
            KeyCode.MORE_SYMBOLS -> { showPage(morePage); return }
            KeyCode.LETTERS -> { showPage(letters()); return }
            KeyCode.SHIFT -> return
            KeyCode.SPACE -> if (query.isNotEmpty() && query.last() != ' ') query.append(' ')
            else -> if (code > 0) query.append(code.toChar().lowercaseChar())
        }
        val q = query.toString().trim()
        e.results = if (q.isEmpty()) e.recents.map { EmojiData.Emoji(it, false, emptyList()) } else emoji?.search(q) ?: emptyList()
        searchBar()
    }

    /** Leaves search for the emoji grid. */
    private fun searchDone() {
        val k = kv ?: return
        k.message = null
        k.cancelable = false
        query.setLength(0)
        openEmoji()
    }

    // ------------------------------------------------------------------ the mic

    private enum class Mic { IDLE, RECORDING, WORKING }
    private var micState = Mic.IDLE
    private var recorder: Recorder? = null
    private val dictation by lazy { Dictation(applicationContext) }
    private val levelTick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            kv?.level = r.level
            main.postDelayed(this, 50)
        }
    }

    private fun mic() {
        when (micState) {
            Mic.IDLE -> startMic()
            Mic.RECORDING -> stopMic()
            Mic.WORKING -> {}
        }
    }

    /** The recording notification's Stop button. */
    fun stopFromNotification() {
        main.post { if (micState == Mic.RECORDING) stopMic() }
    }

    private fun startMic() {
        val k = kv ?: return
        val p = Prefs(this)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micOk || !(p.cloudSttReady || ModelManager.isReady(this))) {
            notice("Finish setting up Tokalot to use the mic · open", 5000) { openApp(settings = false) }
            return
        }
        dropNotice()
        commitWord()
        val r = Recorder()
        r.onFull = { main.post { if (micState == Mic.RECORDING && recorder === r) stopMic() } }
        r.onLost = { main.post { if (micState == Mic.RECORDING && recorder === r) stopMic() } }
        r.cueSamples = if (p.sounds) Sounds.CUE_SAMPLES else 0
        // The notification with a Stop button, as for the floating button. The keyboard is on screen, so the
        // mic works even if Android won't start it.
        runCatching { RecordingService.start(this) }
        if (!r.start()) {
            RecordingService.stop(this)
            Haptics.play(this, Haptics.Kind.ERROR)
            notice("Couldn't open the microphone", 3000)
            return
        }
        recorder = r
        micState = Mic.RECORDING
        dictation.warmUp()
        Haptics.play(this, Haptics.Kind.START)
        k.recording = true
        k.message = "Listening… tap the mic when you're done"
        k.cancelable = true
        main.post(levelTick)
    }

    private fun stopMic() {
        val r = recorder ?: return
        recorder = null
        main.removeCallbacks(levelTick)
        Haptics.play(this, Haptics.Kind.STOP)
        val speech = r.speechSamples
        val taken = r.stopMic()
        RecordingService.stop(this)
        micState = Mic.WORKING
        kv?.let { it.recording = false; it.message = "Transcribing…"; it.cancelable = true }
        // A long recording is put together off the main thread so the keys stay responsive.
        Thread {
            val audio = taken.audio()
            main.post { transcribe(audio, speech) }
        }.start()
    }

    private fun cancelMic() {
        when (micState) {
            Mic.RECORDING -> {
                main.removeCallbacks(levelTick)
                recorder?.stop()
                recorder = null
                RecordingService.stop(this)
                micState = Mic.IDLE
                Haptics.play(this, Haptics.Kind.CANCEL)
                endBusy()
            }
            Mic.WORKING -> dictation.cancel()
            Mic.IDLE -> {}
        }
    }

    private fun transcribe(audio: Recorder.Audio, speech: Int) {
        if (micState != Mic.WORKING) return
        val samples = audio.samples
        val heard = speech >= Recorder.SAMPLE_RATE * 3 / 10
        val problem = when {
            samples.size < Recorder.SAMPLE_RATE / 2 -> ""
            audio.peak < 0.0005f -> "The mic only picked up silence"
            !heard && samples.size < Recorder.SAMPLE_RATE * 5 / 2 -> "Didn't catch anything"
            else -> null
        }
        if (problem != null) {
            micState = Mic.IDLE
            endBusy()
            if (problem.isNotEmpty()) notice(problem, 3000)
            return
        }
        val ic = currentInputConnection
        val spot = if (prefs.fitSentence && ic != null) {
            TextTools.spot(ic.getTextBeforeCursor(AROUND, 0) ?: "", ic.getTextAfterCursor(AROUND, 0) ?: "")
        } else null
        dictation.process(samples, currentInputEditorInfo?.packageName, sparse = !heard, spot = spot) { outcome, err ->
            micState = Mic.IDLE
            endBusy()
            when {
                err == Dictation.CANCELLED -> Haptics.play(this, Haptics.Kind.CANCEL)
                err == Dictation.TIMED_OUT -> { Haptics.play(this, Haptics.Kind.ERROR); notice("Timed out. It's in Tokalot's history to try again.", 5000) }
                err != null -> { Haptics.play(this, Haptics.Kind.ERROR); notice(err, 5000) }
                outcome == null || outcome.text.isEmpty() -> notice("Didn't catch anything", 3000)
                else -> {
                    typeDictation(outcome.text, outcome.fitted)
                    Haptics.play(this, Haptics.Kind.DONE)
                    outcome.warning?.let { notice(it, 4000) }
                }
            }
        }
    }

    /** Types a dictation at the cursor, fitted to the sentence around it, or copies it if the text box is gone. */
    private fun typeDictation(text: String, fitted: Boolean) {
        val ic = currentInputConnection
        if (ic == null || !shown) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tokalot", text))
            Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
            return
        }
        commitWord()
        val before = ic.getTextBeforeCursor(AROUND, 0) ?: ""
        val after = ic.getTextAfterCursor(AROUND, 0) ?: ""
        var t = text
        if (prefs.fitSentence) t = TextTools.fit(t, TextTools.spot(before, after), prefs.words, lowercase = !fitted)
        t = TextTools.pad(t, before.lastOrNull(), after.firstOrNull())
        ic.commitText(t, 1)
        updateShift()
        notice("Typed ✓ · tap to undo", 5000) {
            val c = currentInputConnection ?: return@notice
            if (c.getTextBeforeCursor(t.length, 0)?.toString() == t) c.deleteSurroundingText(t.length, 0)
            updateShift(); showStrip()
        }
    }

    // ------------------------------------------------------------------ AI fix

    private var fixCall: Call? = null

    /** Runs the AI cleanup on the selected text, or the whole text box, and puts the fixed text in its place. */
    private fun fix() {
        val k = kv ?: return
        val ic = currentInputConnection ?: return
        if (busy()) return
        val p = Prefs(this)
        if (!p.cleanupReady) {
            notice("AI fix needs an AI key in Tokalot · open", 5000) { openApp(settings = false) }
            return
        }
        dropNotice()
        commitWord()
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val selected = ic.getSelectedText(0)?.toString()
        val whole = selected.isNullOrEmpty()
        val text = when {
            !whole -> selected!!
            et?.text != null -> et.text.toString()
            else -> (ic.getTextBeforeCursor(FIX_MAX, 0) ?: "").toString() + (ic.getTextAfterCursor(FIX_MAX, 0) ?: "")
        }
        if (text.isBlank()) { notice("Type something, then tap ✨ to fix it", 3000); return }
        if (text.length > FIX_MAX) { notice("Too long to fix in one go. Select part of it first.", 4000); return }
        val start = if (!whole && et != null) et.startOffset + minOf(et.selectionStart, et.selectionEnd) else -1
        val fullLength = if (whole && et != null && et.startOffset == 0 && et.partialStartOffset < 0) et.text.length else -1
        val ei = currentInputEditorInfo
        val pkg = ei?.packageName
        val field = ei?.fieldId
        val call = Call()
        fixCall = call
        k.message = "Fixing…"
        k.cancelable = true
        val app = applicationContext
        Thread {
            var out: String? = null
            var err: String? = null
            val order = (listOf(p.cleanup) + CleanupChoice.values().filter { it != p.cleanup })
                .filter { it != CleanupChoice.OFF && p.key(it.service).isNotEmpty() }.take(2)
            val category = AppContext.categorize(pkg, p)
            val label = pkg?.let { AppContext.label(app, it) }
            for (c in order) {
                if (call.cancelled) break
                try {
                    val r = Cleanup.run(p, c, text, false, category, label, call, p.polish, typed = true)
                    Usage.recordLlm(app, c, r.inTokens, r.outTokens)
                    out = r.text
                    break
                } catch (e: Exception) {
                    if (err == null) err = "AI fix failed: ${e.message?.take(80)}"
                }
            }
            main.post {
                if (fixCall !== call) return@post // cancelled
                fixCall = null
                endBusy()
                val fixed = out
                when {
                    fixed == null -> { Haptics.play(this, Haptics.Kind.ERROR); notice(err ?: "AI fix failed", 4000) }
                    currentInputEditorInfo?.fieldId != field || currentInputConnection == null -> {}
                    else -> putFix(text, keepSpaces(text, fixed), whole, start, fullLength)
                }
            }
        }.start()
    }

    /** [fixed] with [original]'s spaces and line breaks at either end (the AI trims them). */
    private fun keepSpaces(original: String, fixed: String): String {
        val lead = original.takeWhile { it.isWhitespace() }
        val trail = original.takeLastWhile { it.isWhitespace() }
        return lead + fixed.trim() + trail
    }

    private fun putFix(original: String, fixed: String, whole: Boolean, start: Int, fullLength: Int) {
        val ic = currentInputConnection ?: return
        if (fixed == original) { notice("Looks good already ✓", 2500); return }
        fun replace(old: String, new: String, wholeBox: Boolean, at: Int) {
            ic.beginBatchEdit()
            when {
                wholeBox && fullLength >= 0 -> ic.setSelection(0, old.length)
                wholeBox -> ic.performContextMenuAction(android.R.id.selectAll)
                at >= 0 -> ic.setSelection(at, at + old.length)
            }
            ic.commitText(new, 1)
            ic.endBatchEdit()
        }
        replace(original, fixed, whole, start)
        Haptics.play(this, Haptics.Kind.DONE)
        notice("Fixed ✓ · tap to undo", 6000) {
            replace(fixed, original, whole, start)
            updateShift(); showStrip()
        }
    }

    // ------------------------------------------------------------------ odds and ends

    private fun openApp(settings: Boolean) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (settings) i.putExtra(MainActivity.EXTRA_SETTINGS, true)
        startActivity(i)
    }

    private fun saveHistory() {
        val h = history ?: return
        if (!h.changed) return
        val text = h.serialize()
        h.saved()
        val file = File(filesDir, HISTORY_FILE)
        Thread { runCatching { file.writeText(text) } }.start()
    }
}

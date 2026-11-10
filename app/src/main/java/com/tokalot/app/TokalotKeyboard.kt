package com.tokalot.app

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
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
class TokalotKeyboard : InputMethodService(), KeyboardView.Listener, EmojiPanel.Listener, ClipboardPanel.Listener,
    KeyboardMenuPanel.Listener {

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
        /** What the user says in dictations (see [SpokenWords]), and the [History.version] it was read at. */
        private var spoken: SpokenWords? = null
        private var spokenAt = -1L
        private var spokenBusy = false
        /** Names from dictations were added to [words]: switching the feature off reloads the list without them. */
        private var spokenAdded = false
        /** The words hidden in [words] so far (see [Prefs.kbHidden]). */
        private var hiddenWords: Set<String> = emptySet()

        private const val HISTORY_FILE = "typing_history.txt"
        /** The clipboard list: kept out of backups (see [ClipHistory]). */
        private const val CLIPS_FILE = "keyboard_clips.json"
        /** How long a new screenshot or copied picture is offered on the bar. */
        private const val IMAGE_FRESH_MS = 5 * 60 * 1000L
        private const val RECENT_MAX = 32
        /** Typed after a word, these end it and let autocorrect fix it. */
        private const val CORRECT_AFTER = ".,!?;:)]}\"\n"
        /** Typed right after an automatic space, these take its place ("word ." becomes "word. "). */
        private const val SWAP_SPACE = ".,!?;:"
        /** ...and these also hop in front of a space the user typed ("ok ." becomes "ok. "). Not ":" or ";": ":)" follows a space. */
        private const val SWAP_TYPED = ".,!?"
        /** A cursor move this soon after a key is the keyboard's own doing, not a tap in the text. */
        private const val OWN_MOVE_MS = 300L
        private const val FIXES_MAX = 40

        /**
         * Suggestions are worked out here, off the main thread, so a key shows up at once however long the word
         * search takes. [LOCK] is held by a search, and by anything that changes the word list or the typing
         * history while one may be running.
         */
        private val searcher = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val LOCK = Any()
        private const val AROUND = 200
        private const val FIX_MAX = 6000

        /** Settings: forgets the words and pairs the keyboard learned from typing. */
        fun clearHistory(ctx: Context) {
            synchronized(LOCK) { history?.clear() }
            File(ctx.filesDir, HISTORY_FILE).delete()
        }

        /** Settings: the clipboard list was switched off, so what it kept goes. */
        fun clearClips(ctx: Context) {
            instance?.let { it.clips.items.clear(); it.lastCopy = null }
            File(ctx.noBackupFilesDir, CLIPS_FILE).delete()
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
    private var clipPanel: ClipboardPanel? = null
    private var menuPanel: KeyboardMenuPanel? = null
    private lateinit var prefs: Prefs

    private var ac: Autocorrect? = null
    private var glide: Glide? = null

    // ------------------------------------------------------------------ what this text box allows

    /** Suggestions on the bar: any text box but a password one ([autocorrect] says whether words are fixed too). */
    private var predictive = false
    /** A password box: nothing typed here is remembered, and the clipboard isn't offered. */
    private var password = false
    /** Swipe typing: any text box but a password one (where it isn't predictive, the top word goes straight in). */
    private var swipe = false
    /** Typing may be remembered (not a password, not an incognito tab). */
    private var personal = false
    /** What's said in dictations may steer suggestions here: switched on, and not a password box or an incognito tab. */
    private var spokenHere = false
    private var autocorrect = true
    /** Not a web or email address, nor a box that asks for no suggestions: words may be fixed by themselves. */
    private var plainBox = true
    private var vibrate = true
    private var sound = false

    // ------------------------------------------------------------------ the word being typed

    /**
     * Goes up with every new text box (or the same one restarted by its app), so a dictation or AI fix that
     * finishes later can tell the text it was for is gone.
     */
    private var session = 0

    private val word = StringBuilder()
    /** Where each letter was touched, in key widths and rows ([Autocorrect]'s units); NaN when not tapped. */
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    /** The word before it, for context, and the one before that when only a space parts them. */
    private var wordPrev: String? = null
    private var wordPrev2: String? = null
    /** The word's capital came from the keyboard (the start of a sentence), not from a tap on shift. */
    private var wordAutoCap = false
    /** Every letter of the word was typed with caps lock on. */
    private var wordLocked = false
    /**
     * The word as it was picked back up from the text (backspaced into, or the cursor put at its end). Ended
     * again unchanged, it's left exactly as it is: it wasn't typed just now.
     */
    private var resumedAs: String? = null
    /** Shift is on because a sentence starts here, not because it was tapped. */
    private var shiftAuto = false
    /** The space before the cursor was typed on the space bar just now. */
    private var spaceTyped = false
    /** The corrections made in this text box, corrected -> typed: back on one, the bar offers what was typed. */
    private val fixes = LinkedHashMap<String, String>()
    /** When a key, the bar or a swipe was last used (see [OWN_MOVE_MS]). */
    private var lastTouch = 0L
    /** It came from a swipe: the bar shows [glideWords], and a backspace takes all of it. */
    private var glided = false
    private var glideWords: List<String> = emptyList()
    /** A swiped word wants a space before whatever word comes next (but not before punctuation). */
    private var phantomSpace = false
    /** A space was added after a picked suggestion; punctuation typed next takes its place. */
    private var autoSpaced = false
    private var lastSpaceAt = 0L

    /** The last autocorrection, which a backspace straight after undoes. */
    private class Correction(val typed: String, val corrected: String, val sep: String, val prev: String?, val prev2: String?)
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
        clips = runCatching { File(noBackupFilesDir, CLIPS_FILE).takeIf { it.exists() }?.readText()?.let { ClipHistory.parse(it) } }.getOrNull() ?: ClipHistory()
        runCatching { getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener) }
        runCatching { contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imagesChanged) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        shown = false
        cancelMic()
        fixCall?.cancel()
        saveHistory()
        runCatching { getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener) }
        runCatching { contentResolver.unregisterContentObserver(imagesChanged) }
        super.onDestroy()
    }

    /** Never the full-screen editing mode in landscape: the app stays visible above the keys, as with Gboard. */
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val k = KeyboardView(this, this)
        k.wordInProgress = { word.isNotEmpty() }
        val e = EmojiPanel(this, this).apply { visibility = View.GONE }
        val c = ClipboardPanel(this, this).apply { visibility = View.GONE }
        val m = KeyboardMenuPanel(this, this).apply { visibility = View.GONE }
        kv = k; panel = e; clipPanel = c; menuPanel = m
        emoji?.let { e.data = it }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(m, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
                // The build unzips .gz assets and drops the ".gz" (the APK holds keyboard/en_US.txt), so asking
                // for the .gz name failed and the keyboard ran with no word list at all: no suggestions, no
                // autocorrect, no swipe. Both names are tried, the plain one first.
                val main = runCatching { app.assets.open("keyboard/en_US.txt") }.getOrNull()?.bufferedReader()
                    ?: GZIPInputStream(app.assets.open("keyboard/en_US.txt.gz")).bufferedReader()
                // en_US_chat.txt (how common words are in conversation) is under CC BY-SA 4.0: see its first lines.
                main.use { m -> app.assets.open("keyboard/en_US_extra.txt").bufferedReader().use { extra ->
                    app.assets.open("keyboard/en_US_chat.txt").bufferedReader().use { chat -> KeyboardWords.load(m, extra, chat) }
                } }
            }.onFailure { android.util.Log.e("TokalotKeyboard", "word list didn't load", it) }.getOrNull()
            val h = runCatching {
                File(app.filesDir, HISTORY_FILE).takeIf { it.exists() }?.readText()?.let { TypingHistory.parse(it) }
            }.getOrNull() ?: TypingHistory()
            val e = runCatching { app.assets.open("keyboard/emoji.txt").bufferedReader().use { EmojiData.load(it) } }.getOrNull()
            main.post {
                loading = false
                words = w; emoji = e
                if (history == null) history = h
                instance?.loaded()
                if (w == null) instance?.notice("Suggestions and autocorrect are off: the word list didn't load", 5000)
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
        refreshSpoken()
        useSpoken()
        showStrip()
    }

    /**
     * Reads the dictation history into [spoken] when "Learn from my dictations" is on and the history has changed
     * since it was last read, in the background. Switched off, what was read is dropped at once.
     */
    private fun refreshSpoken() {
        if (words == null) return
        if (!prefs.kbSpoken) {
            if (spoken == null) return
            spoken = null
            spokenAt = -1L
            ac?.spoken = null
            if (spokenAdded) {
                // The names it added leave the word list too.
                saveHistory()
                words = null; userWords.clear(); hiddenWords = emptySet(); spokenAdded = false
                ac = null; glide = null
                load()
            }
            return
        }
        val version = History.version
        if (spokenBusy || (spoken != null && version == spokenAt)) return
        spokenBusy = true
        val app = applicationContext
        Thread {
            val read = runCatching { SpokenWords.build(History.all(app).filter { !it.pending }.map { it.text to it.time }) }
                .onFailure { android.util.Log.w("TokalotKeyboard", "couldn't read dictations", it) }.getOrNull()
            main.post {
                spokenBusy = false
                val w = words
                if (read == null || w == null || !Prefs(app).kbSpoken) return@post
                spoken = read
                spokenAt = version
                val names = read.unknown(w)
                if (names.isNotEmpty()) {
                    synchronized(LOCK) { w.addWords(names) }
                    spokenAdded = true
                    instance?.glide?.refreshWords()
                }
                instance?.let { it.useSpoken(); it.showStrip() }
            }
        }.start()
    }

    private fun useSpoken() { ac?.spoken = if (spokenHere) spoken else null }

    /** Tokalot's dictionary words (typed in, learned, or synced from another device) count as known words. */
    private fun addUserWords() {
        val w = words ?: return
        val add = prefs.words.filter { it !in userWords }
        val hide = prefs.kbHidden
        if (add.isEmpty() && hide == hiddenWords) return
        // Words the keyboard learned by itself rank as ordinary words; ones put in the dictionary by hand rank high.
        val learned = prefs.learnedWords
        synchronized(LOCK) {
            w.addUserWords(add.filter { it !in learned })
            w.addUserWords(add.filter { it in learned }, KeyboardWords.LEARNED_PROB)
            for (h in hiddenWords - hide) w.unhide(h)
            for (h in hide - hiddenWords) w.hide(h)
        }
        hiddenWords = hide
        userWords += add
        glide?.refreshWords()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session++
        fixes.clear()
        endSlides()
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
        password = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        predictive = cls == InputType.TYPE_CLASS_TEXT && !password
        // Web and email addresses still get suggestions on the bar, but a word is never changed by itself there
        // ("example.com" must stay as typed). Everywhere else autocorrect works, as on Gboard: many apps and web
        // pages mark their chat or search box "no suggestions" (React Native and web autocomplete="off" do), and
        // honouring that left autocorrect quietly off in much of what gets typed.
        plainBox = variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        // Gboard swipes in any text box but a password one: a search box, an address bar, a chat app that
        // turns suggestions off.
        swipe = cls == InputType.TYPE_CLASS_TEXT && !password
        personal = !password && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 && prefs.kbLearn
        autocorrect = prefs.kbAutocorrect && plainBox
        spokenHere = !password && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 && prefs.kbSpoken
        refreshSpoken()
        useSpoken()
        vibrate = prefs.kbVibrate
        sound = prefs.kbSound

        applyLook()
        k.keyPopups = prefs.kbPopup
        k.symbolHints = prefs.kbSymbolHints
        k.enterKind = enterKind(info)

        if (!restarting || emojiMode != EmojiMode.OFF || clipOpen || menuOpen) {
            closeEmoji(show = false)
            closeClipboard(show = false)
            closeMenu(show = false)
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
        captureClip()
        findScreenshot()
        updateShift()
        showStrip()
    }

    /** The light or dark look and the key style from Settings, on the keys, the pages and the nav bar. */
    private fun applyLook() {
        val k = kv ?: return
        val dark = when (prefs.kbTheme) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val theme = KeyboardTheme.make(dark, prefs.kbStyle, prefs.kbAccent.takeIf { it != 0 } ?: prefs.accent)
        k.theme = theme
        panel?.theme = theme
        clipPanel?.theme = theme
        menuPanel?.theme = theme
        window?.window?.let { w ->
            w.navigationBarColor = theme.background
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        k.toolbar = KeyboardToolbar.parse(prefs.kbToolbar)
        k.heightScale = prefs.kbHeight / 100f
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        shown = false
        OfflineFlowService.instance?.keyboardChanged()
        kv?.reset()
        if (micState == Mic.RECORDING) stopMic()
        commitWord() // or the app keeps the word underlined
        endSlides()
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
        if (slideAnchor >= 0 || cursorSlide != null || cursorByKeys) return
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
            val last = ic.getTextBeforeCursor(1, 0)?.toString()
            if (autoSpaced && last != " ") autoSpaced = false
            if (spaceTyped && last != " ") spaceTyped = false
            // The cursor was put at the end of a word: that word is picked up whole, so letters added to it are
            // checked with it ("walk" + "ing"), and a word that was corrected offers what was typed.
            val moved = oldSelStart != newSelStart || oldSelEnd != newSelEnd
            val byHand = moved && SystemClock.uptimeMillis() - lastTouch > OWN_MOVE_MS
            if (byHand) phantomSpace = false // the space owed after a swiped word was for that spot
            if (moved && shown && predictive && newSelStart == newSelEnd && candidatesStart < 0 && !busy() &&
                SystemClock.uptimeMillis() - lastTouch > OWN_MOVE_MS) resumeAt(ic, newSelStart)
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

    private fun canGlide() = kv?.page?.kind == PageKind.LETTERS && swipe && prefs.kbSwipe && emojiMode == EmojiMode.OFF

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
        val ic = currentInputConnection
        var caps = prefs.kbAutoCaps && (ic?.getCursorCapsMode(ei.inputType) ?: 0) != 0
        // A space is owed after a swiped word and its full stop: the sentence that follows starts with a capital.
        if (!caps && prefs.kbAutoCaps && phantomSpace && ei.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) {
            caps = ic?.getTextBeforeCursor(1, 0)?.lastOrNull()?.let { it in ".!?" } == true
        }
        k.shift = if (caps) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
        shiftAuto = caps
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
        lastTouch = SystemClock.uptimeMillis()
        when (code) {
            KeyCode.SHIFT -> shiftAuto = false
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
        lastTouch = SystemClock.uptimeMillis()
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
        spaceTyped = false
        if (k.chips) dismissChips() // typing on: the things to paste leave the bar, as on Gboard
        val ch = if (k.shift != KeyboardView.Shift.OFF && c.isLetter()) c.uppercaseChar() else c
        val autoCap = k.shift == KeyboardView.Shift.ON && shiftAuto && c.isLetter()
        val locked = k.shift == KeyboardView.Shift.LOCKED
        if (k.shift == KeyboardView.Shift.ON) k.shift = KeyboardView.Shift.OFF
        if (!predictive) {
            ic.commitText(ch.toString(), 1)
            return
        }
        if (word.isEmpty()) {
            val before = ic.getTextBeforeCursor(64, 0)
            wordPrev = prevWord(before)
            wordPrev2 = prevWord2(before)
            wordAutoCap = autoCap
            wordLocked = locked
            resumedAs = null
        } else if (!locked && c.isLetter()) wordLocked = false
        word.append(ch)
        val p = k.page
        val tapped = !x.isNaN() && p.kind == PageKind.LETTERS && p.unit > 0f && c.isLetter()
        xs += if (tapped) x / p.unit else Float.NaN
        ys += if (tapped) (y - p.lettersTop) / p.rowHeight - 0.5f else Float.NaN
        ic.setComposingText(word, 1)
        showStrip()
    }

    private fun punctuation(s: String) {
        val ic = currentInputConnection ?: return
        // A swiped word is owed a space before the next word, and still is after a comma or a full stop.
        val owed = (glided || phantomSpace) && s[0] in SWAP_SPACE
        val typedSpace = spaceTyped && s[0] in SWAP_TYPED
        spaceTyped = false
        var swapped = false
        if (word.isNotEmpty()) {
            endWord(s, correct = s[0] in CORRECT_AFTER)
        } else if ((autoSpaced && s[0] in SWAP_SPACE || typedSpace) && ic.getTextBeforeCursor(2, 0)?.toString().let { it != null && it.endsWith(" ") && (autoSpaced || it.length == 2 && it[0].isLetterOrDigit()) }) {
            // "word ." becomes "word. ": the space was the keyboard's, or typed straight after a word.
            ic.deleteSurroundingText(1, 0)
            ic.commitText("$s ", 1)
            swapped = true
        } else {
            ic.commitText(s, 1)
        }
        // After "word." the automatic space stays put: "word. " is ready for the next sentence.
        autoSpaced = swapped
        phantomSpace = owed
        updateShift()
        showStrip()
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        spaceTyped = true
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
                spaceTyped = false
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
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ei == null || ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        // Send, Search, Go and Done send the word as it stands: a fix made at that moment would go out
        // unseen. A new line fixes the word like a space does.
        if (word.isNotEmpty()) { if (noAction) endWord("", correct = true) else commitWord() }
        autoSpaced = false; phantomSpace = false; lastCorrection = null; spaceTyped = false
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
        spaceTyped = false
        if (c != null && word.isEmpty()) {
            // Straight after an autocorrection: back to what was typed. Undoing one counts as using the word
            // once, so it takes two (or a tap on the bar) before it's learned: a backspace can be a slip too.
            // The correction itself is taken back out of the typing history: it wasn't what was meant.
            if (ic.getTextBeforeCursor(c.corrected.length + c.sep.length, 0)?.toString() == c.corrected + c.sep) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(c.corrected.length + c.sep.length, 0)
                ic.commitText(c.typed + c.sep, 1)
                ic.endBatchEdit()
                keepAsTyped = c.typed
                forgetUse(c.corrected, c.prev, c.prev2)
                fixes.remove(c.corrected)
                remember(c.typed, c.prev, c.prev2, kept = true)
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
        else if (!deleteChar(ic)) {
            // An emoji, an accent, or a box that doesn't say what's in it: the app deletes it its own way. A key
            // press lands whenever the app gets to it, so nothing is read back or picked up after one.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            updateShift()
            showStrip()
            return
        }
        if (predictive) resumeWord(ic)
        updateShift()
        showStrip()
    }

    /**
     * Deletes the one plain character before the cursor through the text connection, so it has happened by the
     * time the text is next read. False if what's there isn't one plain character (see [delete]).
     */
    private fun deleteChar(ic: InputConnection): Boolean {
        val c = ic.getTextBeforeCursor(1, 0)?.singleOrNull() ?: return false
        if (!(c.isLetterOrDigit() || c == ' ' || c == '\n' || c.code in 0x21..0x7e)) return false
        return ic.deleteSurroundingText(1, 0)
    }

    /**
     * Backspaced to the end of a word: it becomes the word being typed again, so suggestions come back. The word
     * is only marked, never deleted and retyped: retyping it from a reading of the text that was a moment out of
     * date took the space in front of it too.
     */
    private fun resumeWord(ic: InputConnection) {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        if (et.selectionStart < 0 || et.selectionStart != et.selectionEnd) return
        resumeAt(ic, et.startOffset + et.selectionStart)
    }

    /**
     * The cursor was put at the end of a word (at [pos] in the text): the word becomes the one being typed. The
     * text itself isn't touched, only marked, and only when the app's own account of where the cursor is agrees.
     */
    private fun resumeAt(ic: InputConnection, pos: Int) {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString()
        if (after != null && after.isNotEmpty() && after[0].isLetterOrDigit()) return
        var i = before.length
        while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '\'' || before[i - 1] == '’')) i--
        val w = before.substring(i)
        if (w.isEmpty() || i == 0 && before.length >= 48 || !w.any { it.isLetter() } || pos < w.length) return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        if (et.selectionStart != et.selectionEnd || et.startOffset + et.selectionStart != pos) return
        if (!ic.setComposingRegion(pos - w.length, pos)) return
        pickedUp(w, before.substring(0, i))
    }

    private fun pickedUp(w: String, before: String) {
        word.setLength(0); word.append(w)
        xs.clear(); ys.clear()
        repeat(w.length) { xs += Float.NaN; ys += Float.NaN }
        wordPrev = prevWord(before)
        wordPrev2 = prevWord2(before)
        wordAutoCap = false
        wordLocked = false
        resumedAs = w
        glided = false
    }

    // ------------------------------------------------------------------ ending words

    /** Ends the word being typed with [sep], autocorrecting it first if [correct] and allowed here. */
    private fun endWord(sep: String, correct: Boolean) {
        val ic = currentInputConnection ?: return
        val typed = word.toString()
        val prev = wordPrev
        val prev2 = wordPrev2
        var final = typed
        // Picked back up from the text and not changed: it stays exactly as it is.
        val untouched = typed == resumedAs
        // What autocorrect makes of it, even where it may not change it, so a typo left alone isn't learned.
        val fix = if (glided || untouched) null else searched()?.correction
        val chosen = typed == keepAsTyped
        if (correct && autocorrect && !chosen && fix != null) final = fix
        ic.commitText(final + sep, 1)
        lastCorrection = if (final != typed) Correction(typed, final, sep, prev, prev2) else null
        if (final != typed && !final.contains(' ')) {
            fixes.remove(final)
            fixes[final] = typed
            if (fixes.size > FIXES_MAX) fixes.remove(fixes.keys.first())
        }
        if (!chosen) keepAsTyped = null
        if (!untouched) remember(final, prev, prev2, kept = final == typed && !glided && (fix == null || chosen))
        resetWord()
    }

    /**
     * What autocorrect makes of the word being typed: the bar's own search if it has finished for exactly this
     * word (see [showStrip]), otherwise worked out now.
     */
    private fun searched(): Autocorrect.Result? {
        found?.let { if (it.gen == stripGen && it.auto) return it.result }
        return ac?.suggest(word.toString(), wordPrev, xs.toFloatArray(), ys.toFloatArray(), true, wordAutoCap, wordLocked, wordPrev2)
    }

    /** Commits the word being typed as it stands. */
    private fun commitWord() {
        if (word.isEmpty()) return
        currentInputConnection?.finishComposingText()
        val typed = word.toString()
        if (typed != resumedAs) {
            val fix = if (glided) null else searched()?.correction
            remember(typed, wordPrev, wordPrev2, kept = !glided && (fix == null || typed == keepAsTyped))
        }
        resetWord()
        phantomSpace = false
    }

    private fun resetWord() {
        word.setLength(0); xs.clear(); ys.clear()
        wordPrev = null
        wordPrev2 = null
        wordAutoCap = false
        wordLocked = false
        resumedAs = null
        glided = false
        glideWords = emptyList()
        found = null
    }

    /**
     * Records a finished word in the typing history. [kept]: typed as it stands on purpose, or nothing better was
     * near. A typo that autocorrect would have fixed (in an address box, or with autocorrect off) isn't [kept]
     * and isn't recorded, so it never turns into a "word of yours" that is no longer corrected, nor gets
     * learned into the dictionary that syncs.
     */
    /**
     * A word the list doesn't know is learned into the dictionary once it has been [kept] [KeyboardLearn.TIMES]
     * times, or at once when [chosen]: the user tapped it on the bar.
     */
    private fun remember(w: String, prev: String?, prev2: String?, kept: Boolean, chosen: Boolean = false) {
        if (!personal) return
        val h = history ?: return
        val list = words ?: return
        if (w.contains(' ')) {
            // A split ("of the"): each word in turn.
            var p = prev
            var p2 = prev2
            synchronized(LOCK) { for (part in w.split(' ').filter { it.isNotEmpty() }) { h.used(part, p, p2); p2 = p; p = part } }
            return
        }
        if (!kept && list.probOf(w) < 0) return
        synchronized(LOCK) { h.used(w, prev, prev2) }
        if (kept && KeyboardLearn.worth(w, list) && (chosen || h.count(w) >= KeyboardLearn.TIMES)) learn(w)
    }

    /** Takes back what [remember] recorded for a correction that was undone. */
    private fun forgetUse(w: String, prev: String?, prev2: String?) {
        if (!personal) return
        val h = history ?: return
        var p = prev
        var p2 = prev2
        synchronized(LOCK) { for (part in w.split(' ').filter { it.isNotEmpty() }) { h.unused(part, p, p2); p2 = p; p = part } }
    }

    private fun learn(w: String) {
        val p = prefs
        if (p.words.any { it.equals(w, ignoreCase = true) }) return
        p.words = p.words + w
        p.learnedWords = p.learnedWords + w
        synchronized(LOCK) { words?.addUserWords(listOf(w), KeyboardWords.LEARNED_PROB) }
        userWords += w
        glide?.refreshWords()
        notice("Learned “$w” · tap to undo", 5000) {
            dropWord(w)
            notice("Removed “$w”", 2000)
        }
    }

    /** Takes [w] out of the dictionary, the typing history and the keyboard's own list, all at once. */
    private fun dropWord(w: String) {
        val q = Prefs(this)
        q.words = q.words.filter { it != w }
        q.learnedWords = q.learnedWords - w
        synchronized(LOCK) {
            history?.forget(w)
            words?.remove(w)
        }
        userWords -= w
        glide?.refreshWords()
    }

    /** A suggestion was held: offers to stop suggesting it. */
    override fun onStripLong(slot: Int) {
        val k = kv ?: return
        if (k.chips || busy() || emojiMode == EmojiMode.SEARCH) return
        val s = k.suggestions.getOrNull(slot) ?: return
        if (s.contains(' ') || !s.any { it.isLetter() }) return
        lastTouch = SystemClock.uptimeMillis()
        notice("Tap here to stop suggesting “$s”", 5000) { forget(s) }
    }

    /**
     * Stops [s] being suggested: one of the user's own words leaves the dictionary; any other word stays a word
     * when typed but is never offered or corrected to again ([Prefs.kbHidden]). Either way it leaves the typing history.
     */
    private fun forget(s: String) {
        val p = Prefs(this)
        val mine = p.words.firstOrNull { it.equals(s, ignoreCase = true) }
        val learned = mine != null && mine in p.learnedWords
        val lower = s.lowercase()
        synchronized(LOCK) { history?.forget(s); history?.forget(lower) }
        if (mine != null) dropWord(mine)
        else {
            p.kbHidden = p.kbHidden + lower
            addUserWords()
        }
        found = null
        notice("Won't suggest “$s” · tap to undo", 5000) {
            val q = Prefs(this)
            if (mine != null) {
                q.words = q.words + mine
                if (learned) q.learnedWords = q.learnedWords + mine
            } else q.kbHidden = q.kbHidden - lower
            addUserWords()
            showStrip()
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

    /** The word before [prevWord]'s, when only spaces part them. */
    private fun prevWord2(before: CharSequence?): String? {
        val t = before ?: return null
        var i = t.length
        while (i > 0 && t[i - 1] == ' ') i--
        while (i > 0 && (t[i - 1].isLetterOrDigit() || t[i - 1] == '\'' || t[i - 1] == '’')) i--
        return prevWord(t.subSequence(0, i))
    }

    private fun sentenceStart(before: CharSequence?): Boolean {
        val t = before?.toString()?.trimEnd(' ') ?: return true
        return t.isEmpty() || t.last() in ".!?\n"
    }

    // ------------------------------------------------------------------ the suggestion bar

    override fun onStrip(slot: Int) {
        lastTouch = SystemClock.uptimeMillis()
        when (slot) {
            KeyboardView.SLOT_MENU -> openMenu()
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
        val k = kv ?: return
        val s = k.suggestions.getOrNull(slot) ?: return
        dropNotice()
        if (k.chips) {
            // Something to paste: a texted code, a picture, or something copied, as it is.
            val act = chipActions.getOrNull(slot) ?: return
            lastCorrection = null; autoSpaced = false; phantomSpace = false
            act()
            updateShift()
            showStrip()
            return
        }
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
            val prev2 = wordPrev2
            // A word picked back up in the middle of the text already has its space or punctuation after it.
            val next = if (typed == resumedAs) ic.getTextAfterCursor(1, 0)?.firstOrNull() else null
            val sep = if (next != null && !next.isLetterOrDigit()) "" else " "
            val original = if (typed == resumedAs) fixes[typed] else null
            ic.commitText(s + sep, 1)
            if (original != null && s == original) {
                // Back to what was typed before it was corrected: the correction leaves the typing history, and
                // the word counts as chosen.
                forgetUse(typed, prev, prev2)
                fixes.remove(typed)
                keepAsTyped = s
                remember(s, prev, prev2, kept = true, chosen = true)
            } else {
                // Tapping what was typed on the bar is choosing it: it counts towards learning it.
                if (s == typed) keepAsTyped = typed
                if (!(s == typed && typed == resumedAs)) remember(s, prev, prev2, kept = s == typed, chosen = s == typed)
            }
            resetWord()
            lastCorrection = null
            autoSpaced = sep.isNotEmpty()
            spaceTyped = false
            lastSpaceAt = 0
            updateShift()
            showStrip()
            return
        } else {
            val before = ic.getTextBeforeCursor(64, 0)
            ic.commitText("$s ", 1)
            remember(s, prevWord(before), prevWord2(before), kept = false)
        }
        lastCorrection = null
        autoSpaced = true
        spaceTyped = false
        lastSpaceAt = 0
        updateShift()
        showStrip()
    }

    /** Fills the bar: the word's corrections and completions, or next-word guesses between words. */
    private fun showStrip() {
        val k = kv ?: return
        stripGen++ // whatever search is still running is for a bar that's gone
        if (busy() || notice != null || emojiMode == EmojiMode.SEARCH) return
        k.message = null
        k.cancelable = false
        k.chips = false
        // Things to paste go on the bar until used or typing starts: a code that just came in a text, a new
        // screenshot or copied picture, and something just copied.
        val paste = if (word.isEmpty() && !password) pasteChips() else emptyList()
        if (paste.isNotEmpty()) {
            k.chips = true
            k.chipThumb = image?.thumb
            k.chipImageLabel = if (image?.screenshot == false) "Image" else "Screenshot"
            k.suggestions = paste.map { it.first }
            chipActions = paste.map { it.second }
            k.boldSlot = -1
            return
        }
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
            // Worked out off the main thread, so the letter shows at once; the bar follows a moment later, and a
            // result for a word that has changed since is dropped.
            val typed = word.toString()
            val auto = autocorrect && typed != keepAsTyped && typed != resumedAs
            val gen = stripGen
            val prev = wordPrev; val prev2 = wordPrev2
            val tx = xs.toFloatArray(); val ty = ys.toFloatArray()
            val autoCap = wordAutoCap; val locked = wordLocked
            searcher.execute {
                if (gen != stripGen) return@execute
                val r = runCatching { synchronized(LOCK) { a.suggest(typed, prev, tx, ty, auto, autoCap, locked, prev2) } }
                    .onFailure { android.util.Log.w("TokalotKeyboard", "suggestions failed", it) }.getOrNull() ?: return@execute
                main.post {
                    if (gen != stripGen || instance !== this) return@post
                    found = Found(gen, auto, r)
                    if (busy() || notice != null || emojiMode == EmojiMode.SEARCH) return@post
                    showFound(r)
                }
            }
            return
        }
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(64, 0)
        val prev = prevWord(before)
        val list = when {
            before != null && before.isNotEmpty() && before.last() != ' ' && before.last() != '\n' -> emptyList()
            prev != null -> a.predict(prev, 3, prevWord2(before))
            sentenceStart(before) -> a.predict(null, 3)
            else -> emptyList()
        }
        val caps = k.shift != KeyboardView.Shift.OFF
        k.suggestions = list.map { if (caps) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
        k.boldSlot = -1
    }

    /** Goes up each time the bar is refilled; a search carries the number it was started for. */
    @Volatile private var stripGen = 0
    /** The last search that finished: for which refill, and whether autocorrect was on for it. */
    private class Found(val gen: Int, val auto: Boolean, val result: Autocorrect.Result)
    private var found: Found? = null

    private fun showFound(r: Autocorrect.Result) {
        val k = kv ?: return
        val typed = word.toString()
        // Back on a word that was corrected: what was typed comes first, one tap away.
        val original = if (typed == resumedAs) fixes[typed]?.takeIf { it != typed } else null
        if (original != null) {
            k.suggestions = listOfNotNull(original, typed, r.shown.firstOrNull { it != original && it != typed })
            k.boldSlot = 1
        } else {
            k.suggestions = r.shown
            k.boldSlot = r.pick
        }
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
        k.working = false
        k.message = notice
        if (notice == null) showStrip()
    }

    // ------------------------------------------------------------------ swipe typing

    private var glideSpace = false

    override fun onGlideStart(x: Float, y: Float) {
        val ic = currentInputConnection ?: return
        dropNotice()
        lastTouch = SystemClock.uptimeMillis()
        spaceTyped = false
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
        lastTouch = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        val k = kv ?: return
        val g = glide
        if (g == null) {
            notice(if (loading) "Swipe typing is still loading its words. Try again in a moment." else "Swipe typing's word list didn't load", 3000)
            return
        }
        val prev = prevWord(ic.getTextBeforeCursor(64, 0))
        // Says on the bar why nothing was typed, rather than nothing happening.
        val found = try {
            g.words(5, prev, if (personal) history else null, if (spokenHere) spoken else null, System.currentTimeMillis())
        } catch (e: Throwable) {
            android.util.Log.e("TokalotKeyboard", "swipe failed", e)
            notice("Swipe didn't work (${e.javaClass.simpleName}: ${e.message?.take(40)})", 6000)
            return
        }
        if (found.isEmpty()) { notice("Couldn't make out that swipe (${g.points} points)", 3000); return }
        val case: (String) -> String = when (k.shift) {
            KeyboardView.Shift.LOCKED -> { s -> s.uppercase() }
            KeyboardView.Shift.ON -> { s -> s.replaceFirstChar { it.uppercaseChar() } }
            KeyboardView.Shift.OFF -> { s -> s }
        }
        val list = found.map(case).distinct()
        if (glideSpace) ic.commitText(" ", 1)
        if (!predictive) {
            // A box without suggestions (a search box, an address bar): the best word goes straight in.
            ic.commitText(list[0], 1)
            phantomSpace = true
            if (k.shift == KeyboardView.Shift.ON) k.shift = KeyboardView.Shift.OFF
            return
        }
        wordPrev = prev
        wordPrev2 = null
        resumedAs = null
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

    /**
     * A space bar slide in progress: the text box's text, where the cursor is in it, and the selection it
     * started from (for putting it back if the slide is abandoned). Null with [cursorByKeys]: the app
     * doesn't share its text, so the cursor is moved with arrow keys instead.
     */
    private class CursorSlide(val base: Int, val text: String, var pos: Int, val fromStart: Int, val fromEnd: Int)
    private var cursorSlide: CursorSlide? = null
    private var cursorByKeys = false

    override fun onCursorSlide(steps: Int) {
        lastTouch = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        if (cursorSlide == null && !cursorByKeys) {
            commitWord()
            autoSpaced = false; lastCorrection = null
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            val text = et?.text?.toString()
            if (et != null && text != null && et.selectionStart >= 0) {
                val a = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
                val b = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
                // A selection collapses towards the way the finger went.
                cursorSlide = CursorSlide(et.startOffset, text, if (steps < 0) a else b, et.startOffset + a, et.startOffset + b)
            } else {
                cursorByKeys = true
            }
        }
        if (steps == 0) return
        val c = cursorSlide
        if (c != null) {
            // Whole characters: an emoji, a flag or an accented letter is one step.
            c.pos = TextSteps.move(c.text, c.pos, steps)
            ic.setSelection(c.base + c.pos, c.base + c.pos)
        } else {
            val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(key) }
        }
    }

    override fun onSlideEnd(cancelled: Boolean) {
        lastTouch = SystemClock.uptimeMillis()
        val ic = currentInputConnection
        if (cancelled && ic != null) {
            cursorSlide?.let { ic.setSelection(it.fromStart, it.fromEnd) }
            if (slideAnchor >= 0 && slideAnchor != Int.MAX_VALUE) ic.setSelection(slideFromStart, slideFromEnd)
        }
        if (kv?.message == EDGE_HINT) kv?.message = null
        endSlides()
        updateShift()
        showStrip()
    }

    /** Forgets any slide without touching the text (the text box is going away). */
    private fun endSlides() {
        cursorSlide = null
        cursorByKeys = false
        slideAnchor = -1
    }

    /** A backspace slide in progress: where it started, what's before that, and the selection it replaced. */
    private var slideAnchor = -1
    private val EDGE_HINT = "Let go to delete everything"
    private var slideBefore = ""
    private var slideFromStart = 0
    private var slideFromEnd = 0

    override fun onDeleteSlide(words: Int, done: Boolean) {
        lastTouch = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        if (slideAnchor < 0) {
            commitWord()
            autoSpaced = false; lastCorrection = null
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            val text = et?.text?.toString()
            if (et != null && text != null && et.selectionStart >= 0) {
                val a = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
                val b = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
                slideFromStart = et.startOffset + a
                slideFromEnd = et.startOffset + b
                slideAnchor = slideFromEnd
                slideBefore = text.substring(maxOf(0, b - 2000), b)
            } else {
                // The app doesn't share its text's positions: nothing is shown selected, the words go on release.
                slideAnchor = Int.MAX_VALUE
                slideBefore = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            }
        }
        val all = words == KeyboardView.DELETE_ALL
        val n = if (all) slideBefore.length else TextSteps.wordsBack(slideBefore, words)
        val known = slideAnchor != Int.MAX_VALUE
        if (!done) {
            when {
                all && known -> ic.performContextMenuAction(android.R.id.selectAll)
                known -> ic.setSelection(slideAnchor - n, slideAnchor)
            }
            // At the left edge the bar says what letting go does, so a whole message never goes by surprise.
            if (!busy()) {
                if (all) kv?.message = EDGE_HINT else if (kv?.message == EDGE_HINT) kv?.message = notice
            }
            return
        }
        if (kv?.message == EDGE_HINT) kv?.message = null
        var gone = ""
        when {
            all -> {
                ic.performContextMenuAction(android.R.id.selectAll)
                gone = ic.getSelectedText(0)?.toString() ?: ""
                ic.commitText("", 1)
            }
            known && n > 0 -> {
                gone = slideBefore.takeLast(n)
                ic.setSelection(slideAnchor - n, slideAnchor); ic.commitText("", 1)
            }
            known -> ic.setSelection(slideFromStart, slideFromEnd) // let go before a whole word: nothing deleted
            n > 0 -> { gone = slideBefore.takeLast(n); ic.deleteSurroundingText(n, 0) }
        }
        endSlides()
        updateShift()
        showStrip()
        // Holding backspace is slow and a slide is quick, so a slide can be taken back: until you type again.
        if (gone.isNotEmpty()) {
            notice(if (all) "Deleted all · tap to undo" else "Deleted · tap to undo", 6000) {
                currentInputConnection?.commitText(gone, 1)
                updateShift()
                showStrip()
            }
        }
    }

    // ------------------------------------------------------------------ the top bar's buttons and the menu

    override fun onTool(item: String) {
        if (emojiMode == EmojiMode.SEARCH) return
        when (item) {
            KeyboardToolbar.MIC -> mic()
            KeyboardToolbar.FIX -> fix()
            KeyboardToolbar.CLIPBOARD -> openClipboard()
            KeyboardToolbar.EMOJI -> openEmoji()
            KeyboardToolbar.SETTINGS -> openApp(settings = true)
            KeyboardToolbar.THEME -> {
                // Flips what's showing now; "Match phone" comes back from the menu or Settings.
                prefs.kbTheme = if (kv?.theme?.dark == true) "light" else "dark"
                applyLook()
            }
            KeyboardToolbar.NUMBERS -> {
                prefs.kbNumberRow = !prefs.kbNumberRow
                resetLetters()
            }
        }
    }

    /** The letters page again, for a changed number row or symbol hints. */
    private fun resetLetters() {
        lettersPage = null
        if (kv?.page?.kind == PageKind.LETTERS) { showPage(letters()); updateShift() }
    }

    private var menuOpen = false

    private fun openMenu() {
        val k = kv ?: return
        val m = menuPanel ?: return
        commitWord()
        closeEmoji(show = false)
        closeClipboard(show = false)
        m.panelHeight = k.height
        m.theme = k.theme
        m.show()
        menuOpen = true
        m.visibility = View.VISIBLE
        k.visibility = View.GONE
    }

    private fun closeMenu(show: Boolean = true) {
        if (!menuOpen) return
        menuOpen = false
        menuPanel?.visibility = View.GONE
        kv?.visibility = View.VISIBLE
        if (show) { showPage(letters()); updateShift(); showStrip() }
    }

    override fun onMenuToolbar(list: List<String>) {
        kv?.toolbar = list
    }

    override fun onMenuSetting() {
        prefs = Prefs(this)
        applyLook()
        autocorrect = prefs.kbAutocorrect && plainBox
        vibrate = prefs.kbVibrate
        sound = prefs.kbSound
        lettersPage = null // the number row may have changed; the letters come back when the menu closes
    }

    override fun onMenuAllSettings() {
        closeMenu()
        openApp(settings = true)
    }

    override fun onMenuClose() = closeMenu()

    // ------------------------------------------------------------------ things to paste on the bar

    private var chipActions: List<() -> Unit> = emptyList()
    private var dismissedCode: String? = null

    /** A picture to offer: a screenshot just taken, or a picture just copied, with its thumbnail. */
    private class Picture(val uri: Uri, val time: Long, val mime: String, val thumb: Bitmap?, val screenshot: Boolean)
    private var image: Picture? = null
    private var dismissedImage: Uri? = null

    /** What the bar offers to paste right now, best first: (what the chip shows, what tapping it does). */
    private fun pasteChips(): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        val now = System.currentTimeMillis()
        val code = CodeListener.fresh(now)?.takeIf { prefs.kbCodes && it != dismissedCode }
        if (code != null) out += "Code $code" to { dismissedCode = code; currentInputConnection?.commitText(code, 1); Unit }
        val pic = image?.takeIf { it.uri != dismissedImage && now - it.time in 0..IMAGE_FRESH_MS }
        if (pic != null) out += KeyboardView.IMAGE_CHIP to { dismissedImage = pic.uri; pastePicture(pic) }
        val copied = lastCopy?.takeIf { p -> prefs.kbClipSuggest && p.text != dismissedClip && now - p.time in 0..ClipHistory.FRESH_MS }
        if (copied != null) for (t in ClipHistory.smart(copied.text)) out += t to { dismissedClip = copied.text; currentInputConnection?.commitText(t, 1); Unit }
        return out.take(3)
    }

    private fun dismissChips() {
        dismissedClip = lastCopy?.text
        dismissedCode = CodeListener.code
        dismissedImage = image?.uri
    }

    /** A code came in a text (from [CodeListener], on its own thread). */
    fun codeArrived() {
        main.post { if (shown && word.isEmpty() && !busy() && notice == null && emojiMode == EmojiMode.OFF) showStrip() }
    }

    private val imagesChanged = object : android.database.ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (!shown) return
            findScreenshot()
            // Some phones save the file a moment after they announce it, so look once more shortly after.
            main.removeCallbacks(lookAgain)
            main.postDelayed(lookAgain, 1500)
        }
    }
    private val lookAgain = Runnable { if (shown) findScreenshot() }

    private fun canSeePhotos() = checkSelfPermission(
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Looks for a screenshot taken in the last few minutes (off the main thread; it reads the photo library).
     * Phones keep screenshots in different places ("Pictures/Screenshots", "DCIM/Screenshots", a "Screenshots"
     * album, files named "Screenshot_…"), so any of those marks it as one.
     */
    private fun findScreenshot() {
        if (!prefs.kbScreenshots || !canSeePhotos()) return
        val app = applicationContext
        val since = (System.currentTimeMillis() - IMAGE_FRESH_MS) / 1000
        Thread {
            val found = screenshots(app, since, max = 1).firstOrNull() ?: return@Thread
            main.post {
                val had = image
                // The same screenshot again: only taken if this time its little picture could be read.
                if (had?.uri == found.uri && (had.thumb != null || found.thumb == null)) return@post
                image = found
                if (shown && word.isEmpty() && !busy() && notice == null && emojiMode == EmojiMode.OFF) showStrip()
            }
        }.start()
    }

    /**
     * Screenshots added since [since] (seconds), newest first, at most [max], each with its little picture. Reads
     * the photo library, so never on the main thread.
     */
    private fun screenshots(app: Context, since: Long, max: Int): List<Picture> = runCatching {
        val out = ArrayList<Picture>()
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA)
        app.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (out.size < max && c.moveToNext()) {
                val where = (3..5).joinToString(" ") { c.getString(it).orEmpty() }
                if (!isScreenshot(where)) continue
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                out += Picture(uri, c.getLong(1) * 1000, c.getString(2) ?: "image/png", thumbnail(app, uri), screenshot = true)
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun isScreenshot(where: String) =
        where.contains("screenshot", ignoreCase = true) || where.contains("screen_shot", ignoreCase = true) ||
            where.contains("screen shot", ignoreCase = true) || where.contains("screencap", ignoreCase = true)

    /** A copied picture (from [captureClip]): offered like a screenshot. */
    private fun pictureCopied(uri: Uri, mime: String, time: Long) {
        val app = applicationContext
        Thread {
            val thumb = thumbnail(app, uri)
            main.post {
                image = Picture(uri, time, mime, thumb, screenshot = false)
                if (shown && word.isEmpty() && !busy() && notice == null && emojiMode == EmojiMode.OFF) showStrip()
            }
        }.start()
    }

    /** A small copy of the picture for the bar: Android's ready-made thumbnail, or else the picture read small. */
    private fun thumbnail(ctx: Context, uri: Uri): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null) }.getOrNull()?.let { return it }
        }
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 160) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }

    /**
     * Puts the picture into the text box the way Gboard does, for apps that take pictures from the keyboard
     * (most messaging apps). It's copied into Tokalot's own cache first so the app can be let read just that.
     */
    private fun pastePicture(pic: Picture) {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val takes = ei?.contentMimeTypes?.any { ClipDescription.compareMimeTypes(pic.mime, it) } == true
        val app = applicationContext
        Thread {
            val shared = runCatching { KeyboardImages.share(app, pic.uri, pic.mime) }.getOrNull()
            main.post {
                if (shared == null) { notice("Couldn't read that picture", 3000); return@post }
                val ok = takes && run {
                    val info = android.view.inputmethod.InputContentInfo(shared, ClipDescription("Picture", arrayOf(pic.mime)))
                    currentInputConnection?.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true
                }
                // The app doesn't take pictures from keyboards: it's put on the clipboard instead, so the app's
                // own Paste (press and hold in the box) can still use it.
                if (!ok) notice(if (copyPicture(shared)) "Picture copied. Press and hold the box, then Paste" else "This app doesn't take pictures from the keyboard", 4000)
            }
        }.start()
    }

    /** Puts a picture the keyboard shares on the clipboard (not offered back on the bar: it's already there). */
    private fun copyPicture(shared: Uri): Boolean = runCatching {
        val cm = getSystemService(ClipboardManager::class.java) ?: return false
        val clip = android.content.ClipData.newUri(contentResolver, "Picture", shared)
        // Android lets the app that pastes it open this one picture (the provider grants per picture).
        ignoreNextPicture = shared
        cm.setPrimaryClip(clip)
        true
    }.getOrDefault(false)
    private var ignoreNextPicture: Uri? = null

    // ------------------------------------------------------------------ clipboard

    private lateinit var clips: ClipHistory
    /** The last thing copied (kept even with the clipboard list off, for the bar). */
    private var lastCopy: ClipHistory.Item? = null
    /** The copied thing the bar no longer offers: it was pasted, or typing started. */
    private var dismissedClip: String? = null
    private var clipOpen = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { main.post { captureClip() } }

    /**
     * Notes what's on the clipboard, if it's new. Android lets only the keyboard in use read it, so a copy made
     * while another keyboard was up is picked up here when Tokalot's next opens (with the time it was copied).
     */
    private fun captureClip() {
        val p = prefs
        if (!p.kbClipboard && !p.kbClipSuggest) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return
        val d = clip.description
        // Passwords and the like: Android 13 marks them, and some apps mark them earlier the same way.
        if (d?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        if (shown && password) return
        val item = runCatching { clip.getItemAt(0) }.getOrNull() ?: return
        val uri = item.uri
        val mime = d?.let { desc -> (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") } }
        if (uri != null && mime != null) {
            val time = d.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
            if (uri == ignoreNextPicture) return
            if (image?.uri != uri && p.kbClipSuggest) pictureCopied(uri, mime, time)
            return
        }
        val text = runCatching { clip.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val time = d?.timestamp?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val last = lastCopy
        if (last != null && last.text == text && last.time >= time) return
        if (last == null && clips.items.firstOrNull()?.let { it.text == text && it.time >= time } == true) {
            lastCopy = clips.items.first()
            return
        }
        lastCopy = ClipHistory.Item(text, time, false)
        if (p.kbClipboard && clips.add(text, time)) saveClips()
        if (shown && !busy() && notice == null) showStrip()
    }

    private fun openClipboard() {
        val k = kv ?: return
        val c = clipPanel ?: return
        commitWord()
        closeEmoji(show = false)
        closeMenu(show = false)
        clips.prune(System.currentTimeMillis())
        if (clips.changed) saveClips()
        c.panelHeight = k.height
        c.show(clips.items.toList(), prefs.kbClipboard)
        showPanelScreenshots()
        clipOpen = true
        c.visibility = View.VISIBLE
        k.visibility = View.GONE
    }

    /** Back to the letters ([show]: and show them; false when a new text box is about to set the page). */
    private fun closeClipboard(show: Boolean = true) {
        if (!clipOpen) return
        clipOpen = false
        clipPanel?.visibility = View.GONE
        kv?.visibility = View.VISIBLE
        if (show) { showPage(letters()); updateShift(); showStrip() }
    }

    override fun onClipPaste(text: String) {
        lastTouch = SystemClock.uptimeMillis()
        spaceTyped = false
        val ic = currentInputConnection ?: return
        if (phantomSpace) ic.commitText(" ", 1)
        phantomSpace = false; autoSpaced = false; lastCorrection = null
        dismissedClip = text
        ic.commitText(text, 1)
        closeClipboard()
    }

    override fun onClipPin(text: String, on: Boolean) {
        clips.pin(text, on)
        saveClips()
        clipPanel?.show(clips.items.toList(), prefs.kbClipboard)
    }

    override fun onClipDelete(text: String) {
        clips.remove(text)
        if (lastCopy?.text == text) dismissedClip = text
        saveClips()
        clipPanel?.show(clips.items.toList(), prefs.kbClipboard)
    }

    override fun onClipClear() {
        clips.clear()
        lastCopy?.let { dismissedClip = it.text }
        saveClips()
        clipPanel?.show(clips.items.toList(), prefs.kbClipboard)
    }

    override fun onClipClose() = closeClipboard()

    /** Screenshots on the clipboard page, as on Gboard: the last hour's, tap one to paste it. */
    private var panelPictures: List<Picture> = emptyList()

    private fun showPanelScreenshots() {
        val c = clipPanel ?: return
        if (!prefs.kbScreenshots) { panelPictures = emptyList(); c.showPictures(emptyList(), needsAccess = false); return }
        if (!canSeePhotos()) {
            // Android took the photo permission back (or "Select photos" was picked, which lasts only while
            // Tokalot is open): say so here, where the missing screenshots are noticed.
            panelPictures = emptyList()
            c.showPictures(emptyList(), needsAccess = true)
            return
        }
        val app = applicationContext
        val since = (System.currentTimeMillis() - 60 * 60 * 1000L) / 1000
        Thread {
            val found = screenshots(app, since, max = 6)
            main.post {
                if (!clipOpen) return@post
                panelPictures = found
                c.showPictures(found.map { it.thumb }, needsAccess = false)
            }
        }.start()
    }

    override fun onClipPicture(index: Int) {
        val pic = panelPictures.getOrNull(index) ?: return
        dismissedImage = pic.uri
        closeClipboard()
        pastePicture(pic)
    }

    override fun onClipAllowPhotos() {
        closeClipboard()
        openApp(settings = true)
    }

    private fun saveClips() {
        val text = clips.serialize()
        val file = File(noBackupFilesDir, CLIPS_FILE)
        Thread { runCatching { file.writeText(text) } }.start()
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
        closeClipboard(show = false)
        closeMenu(show = false)
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
        lastTouch = SystemClock.uptimeMillis()
        spaceTyped = false
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
    /** The text box the dictation was started in ([session]). */
    private var micSession = 0
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
        micSession = session
        dictation.warmUp()
        Haptics.play(this, Haptics.Kind.START)
        k.recording = true
        k.message = "Recording"
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
        kv?.let { it.recording = false; it.working = true; it.message = ""; it.cancelable = true }
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
        lastTouch = SystemClock.uptimeMillis()
        spaceTyped = false
        val ic = currentInputConnection
        // The keyboard closed, or moved to another text box, while the words were being worked on.
        if (ic == null || !shown || session != micSession) {
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
        // What was just said steers the typing that follows it.
        main.postDelayed({ if (instance === this) refreshSpoken() }, 1500)
        val typedIn = session
        notice("Typed ✓ · tap to undo", 5000) {
            val c = currentInputConnection ?: return@notice
            if (session != typedIn) return@notice
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
        val asked = session
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
                    session != asked || currentInputConnection == null -> {} // a different text box now
                    !unchanged(text, whole, start) -> notice("You changed the text while it was being fixed, so the fix wasn't put in", 4000)
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

    /**
     * Whether the text box still holds [text] where it was taken from: the whole box, or the selection that
     * started at [start]. When the app doesn't share its text, there's no telling, and it's taken as unchanged.
     */
    private fun unchanged(text: String, whole: Boolean, start: Int): Boolean {
        val et = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return true
        val now = et.text?.toString() ?: return true
        if (whole) return et.startOffset != 0 || et.partialStartOffset >= 0 || now == text
        if (start < 0) return currentInputConnection?.getSelectedText(0)?.toString()?.let { it == text } ?: true
        val i = start - et.startOffset
        return i >= 0 && i + text.length <= now.length && now.regionMatches(i, text, 0, text.length)
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
        val fixedIn = session
        notice("Fixed ✓ · tap to undo", 6000) {
            // Only while the fixed text is still there untouched, so Undo never overwrites newer typing.
            if (session != fixedIn || !unchanged(fixed, whole, start)) return@notice
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

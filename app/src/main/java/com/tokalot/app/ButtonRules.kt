package com.tokalot.app

/**
 * When the floating mic button shows, as plain rules with no Android in them (so they can be tested).
 * [OfflineFlowService] gathers the facts and acts on the answer.
 *
 * The button is for the keyboard: it shows whenever the keyboard is up on something that takes text, in any app
 * but Tokalot itself, and never on a password box. Android answers "which box has the keyboard?" late or not at all
 * in some apps, so a missing answer is never the final word: the service looks again a few times, and if the
 * keyboard is still up with nothing found, the keyboard itself is taken as the proof that there's a box to type into
 * (a tap then types at the cursor, or copies the text if that can't be done).
 */
object ButtonRules {
    /** How many more looks a round of looking starts with (about 4.5 s: the first half every 250 ms, the rest every 500 ms). */
    const val LOOKS = 12

    /** What the service found when it asked Android which box has input focus. */
    enum class Box { FIELD, PASSWORD, NONE }

    /** [show] the button or hide it; [again] look once more after [delayMs]; [why] names the state, for the log. */
    data class Decision(val show: Boolean, val again: Boolean, val delayMs: Long, val why: String)

    /**
     * @param keyboard an on-screen keyboard is listed with a height.
     * @param box what input focus pointed at.
     * @param editing Android 13+: the keyboard's own view of it, from the text box's editor info (true: a plain
     *   text box is being edited; false: a password box; null: Android hasn't said, or an older Android).
     * @param ownApp Tokalot's own screen is in front (its boxes have no use for the button).
     * @param dismissed the button was dragged away and stays hidden until the next box or the keyboard closes.
     * @param looksLeft how many more looks this round allows.
     */
    fun decide(keyboard: Boolean, box: Box, editing: Boolean?, ownApp: Boolean, dismissed: Boolean, looksLeft: Int): Decision {
        val delay = if (looksLeft > LOOKS / 2) 250L else 500L
        val password = box == Box.PASSWORD || editing == false
        val field = box == Box.FIELD || editing == true
        if (ownApp) return Decision(false, false, 0, "hide: Tokalot's own screen")
        if (password) return Decision(false, false, 0, "hide: password box")
        if (!keyboard) {
            // A box is selected but no keyboard is listed yet: it can be listed a moment later with no further event.
            return if (field && looksLeft > 0) Decision(false, true, delay, "hide: text box but no keyboard yet, looking")
            // Neither: usually there's nothing to type into, but the keyboard can also turn up late. A few quick looks.
            else if (looksLeft > LOOKS / 2) Decision(false, true, delay, "hide: no keyboard, looking")
            else Decision(false, false, 0, "hide: no keyboard")
        }
        if (dismissed) return Decision(false, false, 0, "hide: dragged away")
        if (field) return Decision(true, false, 0, "show: keyboard on a text box")
        // Keyboard up, but no box found. Look again quickly a few times; then trust the keyboard.
        return if (looksLeft > LOOKS / 2) Decision(false, true, delay, "hide: keyboard but no text box found yet, looking")
        else Decision(true, false, 0, "show: keyboard up, no text box reported")
    }

    /** Whether a text box's input type (from its editor info) is a password. */
    fun isPasswordType(inputType: Int): Boolean {
        val cls = inputType and 0x0000000f // InputType.TYPE_MASK_CLASS
        val variation = inputType and 0x00000ff0 // InputType.TYPE_MASK_VARIATION
        return when (cls) {
            1 -> variation == 0x80 || variation == 0x90 || variation == 0xe0 // text: password, visible password, web password
            2 -> variation == 0x10 // number: password
            else -> false
        }
    }
}

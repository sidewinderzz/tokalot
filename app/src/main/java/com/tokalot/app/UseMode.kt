package com.tokalot.app

import android.content.Context

/**
 * The three ways to use Tokalot on a phone, picked on the welcome and in Settings › Setup. Only the floating mic
 * button needs Android's accessibility switch; the keyboard and the plain app work without it. The choice only
 * changes what setup asks for (and what Home's Finish setup counts): nothing is switched off by it.
 */
enum class UseMode(val label: String, val blurb: String) {
    FLOATING(
        "Floating mic button",
        "Keep your own keyboard. A mic button pops up over it in any app. Needs the accessibility switch.",
    ),
    KEYBOARD(
        "Tokalot Keyboard",
        "Use Tokalot as your keyboard, with a mic key built in. No accessibility switch needed.",
    ),
    APP(
        "Just the app",
        "Record in the app, or share voice messages and audio files to Tokalot to get the text. No accessibility switch or keyboard needed.",
    );

    companion object {
        /** The picked mode, or a guess from what's on: the keyboard if only it is, otherwise the floating button. */
        fun current(ctx: Context, prefs: Prefs = Prefs(ctx)): UseMode {
            values().firstOrNull { it.name == prefs.useMode }?.let { return it }
            return guess(ServiceGuard.enabled(ctx), TokalotKeyboard.isEnabled(ctx))
        }

        /** Pure, for tests: someone who never picked keeps working the way they set things up. */
        fun guess(accessibilityOn: Boolean, keyboardOn: Boolean): UseMode = when {
            accessibilityOn -> FLOATING
            keyboardOn -> KEYBOARD
            else -> FLOATING
        }
    }
}

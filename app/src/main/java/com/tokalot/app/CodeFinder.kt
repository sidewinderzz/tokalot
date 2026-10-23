package com.tokalot.app

/**
 * Finds a sign-in or verification code in a text message ("Your Google verification code is 123456",
 * "G-482913 is your code", "Use 7731 to log in"), for the keyboard's bar. Only messages that talk about a
 * code count, so a plain number (an address, a price, a phone number) is never offered.
 */
object CodeFinder {
    private val words = Regex(
        """(?i)\b(code|codes|otp|passcode|pass code|pin|verification|verify|verifying|one[- ]time|2fa|two[- ]factor|""" +
            """security|log ?in|sign ?in|authenticat\w*|confirm\w*|token|código|codigo)\b"""
    )
    /** 4-8 digits, maybe with a letter prefix ("G-123456") or split in the middle ("123-456", "123 456"). */
    private val code = Regex("""(?<![\w$€£#.,/:-])(?:[A-Z]{1,3}-)?(\d{3,4}[- ]\d{3,4}|\d{4,8})(?![\w%,/:-]|[.,]\w)""")

    /** The code in [text], or null. */
    fun find(text: CharSequence?): String? {
        val t = text?.toString() ?: return null
        if (!words.containsMatchIn(t)) return null
        val found = code.findAll(t).map { it.value }.filter { c ->
            val digits = c.count { it.isDigit() }
            digits in 4..8 && !isYear(c)
        }.toList()
        // "G-123456" pastes as 123456, and "123 456" as 123456, as sites ask for the digits.
        return found.firstOrNull()?.filter { it.isDigit() }
    }

    private fun isYear(c: String) = c.length == 4 && c.all { it.isDigit() } && c.toInt() in 1990..2099
}

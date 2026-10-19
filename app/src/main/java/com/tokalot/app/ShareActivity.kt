package com.tokalot.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * The "Tokalot" entry in Android's share sheet. A shared voice note (or any recording) is copied in and
 * transcribed into a voice note; shared text is saved as a note. Then the app opens on Notes. Sharing turns
 * voice notes on if they were off. Shows nothing of its own.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        // Read here, while the sharing app's permission to read the files lasts; the copy is quick.
        Thread {
            val saved = runCatching { take(i) }.getOrDefault(0)
            runOnUiThread {
                if (saved == 0) {
                    Toast.makeText(this, "Nothing Tokalot could save was shared", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.NOTES.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                finish()
            }
        }.start()
    }

    /** Saves what was shared and returns how many things were saved. */
    private fun take(i: Intent): Int {
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (uris.isEmpty() && text.isEmpty()) return 0
        val prefs = Prefs(this)
        if (!prefs.notesBeta) {
            prefs.notesBeta = true
            runCatching { StartTile.setNoteTile(this, true) }
        }
        var saved = 0
        for (u in uris) {
            val mime = contentResolver.getType(u) ?: i.type
            if (mime != null && !mime.startsWith("audio/") && mime != "application/ogg") continue
            if (NoteImport.addAudio(this, u, mime)) saved++
        }
        // A caption that came with a recording is part of the share; text alone is the note.
        if (text.isNotEmpty() && (uris.isEmpty() || saved > 0)) { NoteImport.addText(this, text); saved++ }
        return saved
    }
}

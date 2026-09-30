package com.gdm.offlineflow

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast

/**
 * Watches for a focused, editable (non-password) text field in any app and floats a mic
 * button just above the keyboard. Tap to start, tap to stop; the transcript is typed into
 * the field. Does nothing (no mic, no model in memory) until you tap.
 */
class OfflineFlowService : AccessibilityService() {

    private enum class State { IDLE, RECORDING, WORKING }

    private lateinit var wm: WindowManager
    private lateinit var transcriber: Transcriber
    private val recorder = Recorder()
    private val handler = Handler(Looper.getMainLooper())

    private var button: FrameLayout? = null
    private var buttonBg: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var state = State.IDLE

    private val recheck = Runnable { updateButton() }

    private val sizePx get() = dp(52)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        transcriber = Transcriber(applicationContext)
        buildButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        // Debounce: keyboard open/close fires a burst of window events.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 150)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(recheck)
        if (recorder.isRecording) recorder.stop()
        detach()
        if (::transcriber.isInitialized) transcriber.shutdown()
        super.onDestroy()
    }

    // ---------- overlay button ----------

    private fun buildButton() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
        }
        val frame = FrameLayout(this).apply {
            background = bg
            elevation = dp(6).toFloat()
            addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener { onTap() }
        }
        button = frame
        buttonBg = bg
        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable && !node.isPassword) node else null
    }

    private fun updateButton() {
        // Stay visible while recording/transcribing so a focus blip can't strand the recording.
        if (state == State.IDLE && focusedEditable() == null) {
            detach()
            return
        }
        position()
        if (!attached) {
            try {
                wm.addView(button, params)
                attached = true
            } catch (_: Exception) {}
        } else {
            try { wm.updateViewLayout(button, params) } catch (_: Exception) {}
        }
    }

    private fun position() {
        val dm = resources.displayMetrics
        val kb = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val bottom = if (kb != null) {
            val r = Rect(); kb.getBoundsInScreen(r); r.top
        } else {
            dm.heightPixels - dp(96)
        }
        params?.x = dm.widthPixels - sizePx - dp(12)
        params?.y = bottom - sizePx - dp(8)
    }

    private fun detach() {
        if (attached) {
            try { wm.removeView(button) } catch (_: Exception) {}
            attached = false
        }
    }

    private fun setState(s: State) {
        state = s
        buttonBg?.setColor(
            when (s) {
                State.IDLE -> COLOR_IDLE
                State.RECORDING -> COLOR_REC
                State.WORKING -> COLOR_WORK
            }
        )
    }

    // ---------- dictation flow ----------

    private fun onTap() {
        button?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.WORKING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !ModelManager.isReady(this)
        ) {
            toast("Finish setup in the OfflineFlow app first")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!recorder.start()) {
            toast("Couldn't open the microphone")
            return
        }
        setState(State.RECORDING)
    }

    private fun stopAndTranscribe() {
        val samples = recorder.stop()
        if (samples.size < Recorder.SAMPLE_RATE / 2) { // under half a second
            setState(State.IDLE)
            updateButton()
            return
        }
        setState(State.WORKING)
        transcriber.transcribe(samples) { text, err ->
            setState(State.IDLE)
            when {
                err != null -> toast(err)
                text.isNullOrEmpty() -> toast("Didn't catch anything")
                else -> insert(text)
            }
            updateButton()
        }
    }

    // ---------- text insertion ----------

    private fun insert(text: String) {
        val node = focusedEditable()
        if (node == null) {
            copyToClipboard(text)
            toast("No text field focused, copied instead")
            return
        }
        val hint = node.isShowingHintText
        val current = if (hint) "" else (node.text?.toString() ?: "")
        var start = if (hint) 0 else node.textSelectionStart
        var end = if (hint) 0 else node.textSelectionEnd
        if (start < 0 || end < 0) { start = current.length; end = current.length }
        if (start > end) { val t = start; start = end; end = t }
        start = start.coerceAtMost(current.length)
        end = end.coerceAtMost(current.length)

        val needsSpace = start > 0 && !current[start - 1].isWhitespace()
        val piece = (if (needsSpace) " " else "") + text
        val updated = current.substring(0, start) + piece + current.substring(end)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val caret = start + piece.length
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        } else {
            // Some fields refuse SET_TEXT; fall back to paste at the cursor.
            copyToClipboard(piece)
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                toast("Couldn't type here, text copied to clipboard")
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OfflineFlow", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val COLOR_IDLE = Color.parseColor("#E6263238")
        private val COLOR_REC = Color.parseColor("#E53935")
        private val COLOR_WORK = Color.parseColor("#FFB300")
    }
}

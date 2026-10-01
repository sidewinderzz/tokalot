package com.tokalot.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Floats a small waveform button above the keyboard whenever the keyboard is open on an
 * editable (non-password) field in any app. Tap to start/stop, long-press to cancel,
 * tap again while it's transcribing to cancel that too (the recording stays in history),
 * drag to move (the spot is remembered). Drag it into the red zone at the top of the screen
 * to dismiss it; it comes back the next time an input field is focused or the keyboard
 * reopens. If the field or keyboard closes while you talk,
 * recording continues; when you stop, the text goes into whatever field is focused, or to
 * the clipboard if none is.
 * Does nothing (no mic, no model in memory, no network) until you tap.
 */
class OfflineFlowService : AccessibilityService() {

    companion object {
        @Volatile var instance: OfflineFlowService? = null
        private val COLOR_IDLE = Color.parseColor("#8C3A3A3C")   // grey, semi-transparent
        private val COLOR_ACTIVE = Color.parseColor("#E61C1C1E") // dark while listening/transcribing
        private const val IDLE_ALPHA = 0.75f
        private const val MIN_SAMPLES = Recorder.SAMPLE_RATE / 2 // half a second
        private const val AUTO_STOP_MS = 30_000L
        private const val CANCEL_GUARD_MS = 600L
        private const val AROUND = 64 // characters read either side of the cursor before typing
        /** Placeholders apps show in empty boxes, for apps that don't flag them as hints. */
        private val COMMON_PLACEHOLDERS = setOf(
            "message", "type a message", "text message", "write a message", "send a message",
            "rcs message", "sms message", "chat", "aa", "reply", "add a comment", "search",
        )
    }

    private enum class State { IDLE, STARTING, RECORDING, WORKING }

    private lateinit var wm: WindowManager
    private lateinit var dictation: Dictation
    private val recorder = Recorder()
    private val handler = Handler(Looper.getMainLooper())

    private var button: FrameLayout? = null
    private var bars: BarsView? = null
    private var cancelIcon: ImageView? = null
    private var buttonBg: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var touching = false // don't snap the button back while a finger is on it
    private var dismissed = false // dragged into the top zone; stay hidden until the next field
    private var inDropZone = false
    private var dropZone: FrameLayout? = null
    private var dropIcon: FrameLayout? = null
    private var zoneAttached = false
    private var state = State.IDLE
    private var targetApp: String? = null // app you were in when you tapped the mic
    private var pendingStop = false       // released (hold mode) before the mic finished starting
    private var pendingCancel = false
    private var autoStopped = false
    private var workingSince = 0L         // when transcribing began, to ignore a double-tap on "stop"

    private val silenceCheck = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val quietMs = System.currentTimeMillis() - recorder.lastVoiceAt
            if (quietMs > AUTO_STOP_MS) {
                autoStopped = true
                toast("Stopped after 30 s of silence")
                stopAndTranscribe()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private var bubble: TextView? = null
    private var bubbleAttached = false
    private val hideBubble = Runnable { removeBubble() }

    private val recheck = Runnable { updateButton() }

    private val sizePx get() = dp(48)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dictation = Dictation(applicationContext)
        buildButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A new input field taking focus brings a dismissed button back.
        if (dismissed && event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.source?.isEditable == true
        ) {
            dismissed = false
        }
        // Debounce: keyboard open/close fires a burst of window events.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 150)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        button?.animate()?.cancel()
        if (recorder.isRecording) recorder.stop()
        RecordingService.stop(this)
        detach()
        removeBubble()
        if (::dictation.isInitialized) dictation.shutdown()
        super.onDestroy()
    }

    // ---------- overlay button ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildButton() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
        }
        val b = BarsView(this).apply {
            level = { recorder.level }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the frame speaks for it
        }
        // Shown over the dimmed bars while transcribing, when a tap cancels.
        val x = ImageView(this).apply {
            setImageDrawable(this@OfflineFlowService.getDrawable(R.drawable.ic_close)?.mutate()?.apply { setTint(Color.WHITE) })
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val frame = FrameLayout(this).apply {
            background = bg
            alpha = IDLE_ALPHA
            addView(b, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            addView(x, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            // For screen readers: one labelled, clickable button. Their "activate" arrives as a
            // click rather than a touch, so it is routed to the same tap action (start / stop /
            // cancel, whatever the hold-to-talk setting, since a screen reader can't hold).
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = describe(State.IDLE)
            setOnClickListener { onTap() }
        }
        frame.setOnTouchListener(DragTouch())
        button = frame
        bars = b
        cancelIcon = x
        buttonBg = bg
        params = overlayParams(sizePx, sizePx)
        buildDropZone()
    }

    /** Red gradient with an X along the top edge. Fades in while you drag the button. */
    private fun buildDropZone() {
        val icon = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            val x = ImageView(this@OfflineFlowService).apply {
                setImageDrawable(this@OfflineFlowService.getDrawable(R.drawable.ic_close)?.mutate()?.apply { setTint(Color.WHITE) })
            }
            addView(x, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        val zone = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F2D93A3A"), Color.parseColor("#00D93A3A"))
            )
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS // drag-only decoration
            addView(icon, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(48)
            })
        }
        dropZone = zone
        dropIcon = icon
    }

    private val zoneHeight get() = dp(150)
    /** The button counts as "in the zone" once its centre is inside the red area. */
    private fun overZone(p: WindowManager.LayoutParams) = p.y + sizePx / 2 <= dp(110)

    private fun showDropZone(show: Boolean) {
        dropZone?.animate()?.alpha(if (show) 1f else 0f)?.setDuration(140)?.start()
        if (!show) {
            inDropZone = false
            dropIcon?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
        }
    }

    private fun updateDropHover(p: WindowManager.LayoutParams) {
        val over = overZone(p)
        if (over == inDropZone) return
        inDropZone = over
        dropIcon?.animate()?.scaleX(if (over) 1.3f else 1f)?.scaleY(if (over) 1.3f else 1f)?.setDuration(100)?.start()
        if (over) Haptics.play(this, Haptics.Kind.TICK)
    }

    /** Hide the button until the next input field is focused (or the keyboard reopens). */
    private fun dismissButton() {
        dismissed = true
        showDropZone(false)
        Haptics.play(this, Haptics.Kind.CANCEL)
        handler.removeCallbacks(settle)
        settling = false
        detach()
    }

    /**
     * Tap mode:  tap = start/stop, long-press = cancel, drag = move.
     * Hold mode: hold = talk, release = finish, slide away then release = cancel,
     *            move right away (before the hold kicks in) = drag.
     */
    private inner class DragTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OfflineFlowService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var holding = false   // hold-to-talk recording in progress
        private var inCancelZone = false
        private val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                cancel()
            }
        }
        private val holdStart = Runnable {
            if (!dragging && state == State.IDLE && attached) {
                holding = true
                startRecording()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = params ?: return false
            val holdMode = Prefs(this@OfflineFlowService).holdToTalk
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false; longPressed = false; holding = false; inCancelZone = false
                    inDropZone = false
                    if (holdMode && state == State.IDLE) handler.postDelayed(holdStart, 220)
                    else handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (holding) {
                        // Slide well away from the button to arm cancel; the button dims to show it.
                        val far = kotlin.math.hypot(dx, dy) > dp(90)
                        if (far != inCancelZone) {
                            inCancelZone = far
                            v.alpha = if (far) 0.35f else 1f
                            Haptics.play(this@OfflineFlowService, Haptics.Kind.TICK)
                        }
                    } else {
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            handler.removeCallbacks(longPress)
                            handler.removeCallbacks(holdStart)
                            v.alpha = 1f
                            if (state == State.IDLE) showDropZone(true)
                        }
                        if (dragging) {
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()
                            // Only an idle button can be thrown away; mid-recording it stays put.
                            val canDismiss = state == State.IDLE
                            clamp(p, allowTop = canDismiss)
                            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                            if (canDismiss) updateDropHover(p)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(holdStart)
                    when {
                        holding -> { v.alpha = 1f; if (inCancelZone) cancel() else finishRecording() }
                        dragging -> {
                            if (inDropZone && state == State.IDLE) {
                                handler.post { dismissButton() }
                            } else {
                                showDropZone(false)
                                clamp(p)
                                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                                savePosition(p)
                                if (state == State.IDLE) v.alpha = IDLE_ALPHA
                            }
                        }
                        holdMode && state == State.IDLE -> toast("Hold the button to talk")
                        !longPressed -> onTap()
                    }
                    holding = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(holdStart)
                    if (dragging) showDropZone(false)
                    if (holding) finishRecording()
                    holding = false
                }
            }
            return true
        }
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null // not inside our own app
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable && !node.isPassword) node else null
    }

    private fun updateButton() {
        // Idle: only show when the keyboard is actually up on an editable field.
        // Recording/transcribing: stay visible even if the field or keyboard closed.
        if (state == State.IDLE && keyboardTop() == null) dismissed = false // keyboard closed: forget
        if (state == State.IDLE && (dismissed || keyboardTop() == null || focusedEditable() == null)) {
            handler.removeCallbacks(settle)
            settling = false
            detach()
            return
        }
        if (touching) return
        val p = params ?: return
        val (tx, ty) = targetPosition()
        if (!attached) {
            // Appear invisibly, then fade in once the keyboard has finished sliding up.
            p.x = tx; p.y = ty
            button?.alpha = 0f
            try {
                // The drop zone goes in first (invisible) so it sits underneath the button.
                if (!zoneAttached) {
                    val zp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, zoneHeight)
                    zp.flags = zp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    zp.x = 0; zp.y = 0
                    dropZone?.alpha = 0f
                    wm.addView(dropZone, zp)
                    zoneAttached = true
                }
                wm.addView(button, params)
                attached = true
            } catch (_: Exception) { return }
            beginSettle()
            return
        }
        if (abs(tx - p.x) > dp(12) || abs(ty - p.y) > dp(12)) {
            // The keyboard is moving (closing, opening or resizing). Don't chase its
            // animation; fade out and reappear where it comes to rest.
            if (state == State.IDLE) button?.animate()?.alpha(0f)?.setDuration(90)?.start()
            beginSettle()
        }
    }

    private var settling = false
    private val settle = Runnable { settling = false; applySettled() }

    private fun beginSettle() {
        settling = true
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, 180)
    }

    /** Called once the keyboard has stopped moving: final position, then fade back in. */
    private fun applySettled() {
        if (state == State.IDLE && (dismissed || keyboardTop() == null || focusedEditable() == null)) {
            detach()
            return
        }
        val p = params ?: return
        if (!attached || touching) return
        val (tx, ty) = targetPosition()
        p.x = tx; p.y = ty
        try { wm.updateViewLayout(button, p) } catch (_: Exception) {}
        val rest = if (state == State.IDLE) IDLE_ALPHA else 1f
        button?.animate()?.alpha(rest)?.setDuration(140)?.start()
    }

    private fun keyboardTop(): Int? {
        val kb = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val r = Rect()
        kb.getBoundsInScreen(r)
        return if (r.height() > 0) r.top else null
    }

    // Position is stored as x plus "height above the keyboard", so the button rides with
    // the keyboard wherever you drag it. Without a keyboard, the screen bottom is the anchor.
    private fun anchor() = keyboardTop() ?: resources.displayMetrics.heightPixels
    private fun overlayPrefs() = getSharedPreferences("overlay", Context.MODE_PRIVATE)

    private fun targetPosition(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val sp = overlayPrefs()
        val x = sp.safeInt("x", dm.widthPixels - sizePx - dp(12)).coerceIn(0, dm.widthPixels - sizePx)
        val y = (anchor() - sizePx - sp.safeInt("above", dp(8))).coerceIn(dp(24), dm.heightPixels - sizePx)
        return x to y
    }

    private fun clamp(p: WindowManager.LayoutParams, allowTop: Boolean = false) {
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(0, dm.widthPixels - sizePx)
        p.y = p.y.coerceIn(if (allowTop) 0 else dp(24), dm.heightPixels - sizePx)
    }

    private fun savePosition(p: WindowManager.LayoutParams) {
        overlayPrefs().edit()
            .putInt("x", p.x)
            .putInt("above", anchor() - sizePx - p.y)
            .apply()
    }

    private fun detach() {
        touching = false
        if (attached) {
            try { wm.removeView(button) } catch (_: Exception) {}
            attached = false
        }
        if (zoneAttached) {
            try { wm.removeView(dropZone) } catch (_: Exception) {}
            zoneAttached = false
        }
    }

    /** What a screen reader says for the button: the action a tap would take right now. */
    private fun describe(s: State) = when (s) {
        State.IDLE -> "Start dictation"
        State.STARTING, State.RECORDING -> "Stop and transcribe"
        State.WORKING -> "Cancel transcription"
    }

    private fun setState(s: State) {
        state = s
        button?.contentDescription = describe(s)
        // Transcribing: an X over the dimmed ripple shows that a tap now cancels.
        cancelIcon?.visibility = if (s == State.WORKING) View.VISIBLE else View.GONE
        bars?.alpha = if (s == State.WORKING) 0.35f else 1f
        buttonBg?.setColor(
            when (s) {
                State.IDLE -> COLOR_IDLE
                State.STARTING, State.RECORDING, State.WORKING -> COLOR_ACTIVE
            }
        )
        button?.animate()?.cancel()
        button?.alpha = if (s == State.IDLE) IDLE_ALPHA else 1f
        // Keep the screen awake from tap to finished text, so it can't sleep mid-sentence.
        params?.let { p ->
            val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            p.flags = if (s == State.IDLE) p.flags and keepOn.inv() else p.flags or keepOn
            if (attached) try { wm.updateViewLayout(button, p) } catch (_: Exception) {}
        }
        // Listening: bars in the accent color (amber by default). Transcribing: white ripple.
        bars?.barColor = if (s == State.STARTING || s == State.RECORDING) Prefs(this).accent else Color.WHITE
        bars?.mode = when (s) {
            State.IDLE, State.STARTING -> BarsView.Mode.IDLE
            State.RECORDING -> BarsView.Mode.LISTENING
            State.WORKING -> BarsView.Mode.WORKING
        }
    }

    // ---------- result bubble ("hint") ----------

    /** Small dark pill near the button (e.g. "Copied ✓ …" or "Undo"). */
    private fun showBubble(label: String, durationMs: Long = 4000, onClick: () -> Unit) {
        removeBubble()
        val dm = resources.displayMetrics
        val tv = TextView(this).apply {
            text = label.replace('\n', ' ')
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F21C1C1E"))
            }
            elevation = dp(6).toFloat()
            maxWidth = dm.widthPixels - dp(24)
            setOnClickListener { removeBubble(); onClick() }
        }
        val bp = params ?: return
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        // Line the bubble up with whichever side of the screen the button is on.
        if (bp.x + sizePx / 2 > dm.widthPixels / 2) {
            p.gravity = Gravity.TOP or Gravity.END
            p.x = dm.widthPixels - bp.x - sizePx
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = bp.x
        }
        p.y = if (bp.y > dp(90)) bp.y - dp(56) else bp.y + sizePx + dp(8)
        try {
            wm.addView(tv, p)
            bubble = tv
            bubbleAttached = true
            handler.postDelayed(hideBubble, durationMs)
        } catch (_: Exception) {}
    }

    private fun removeBubble() {
        handler.removeCallbacks(hideBubble)
        if (bubbleAttached) {
            try { wm.removeView(bubble) } catch (_: Exception) {}
            bubbleAttached = false
        }
        bubble = null
    }

    // ---------- dictation flow ----------

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            // A tap while transcribing cancels (the callback in stopAndTranscribe() does the rest),
            // but not the second half of a double-tap on "stop".
            State.WORKING -> if (SystemClock.elapsedRealtime() - workingSince > CANCEL_GUARD_MS) dictation.cancel()
            State.STARTING -> {}
        }
    }

    /** Hold mode release: stop now, or as soon as the mic has actually started. */
    private fun finishRecording() {
        when (state) {
            State.RECORDING -> stopAndTranscribe()
            State.STARTING -> pendingStop = true
            else -> {}
        }
    }

    fun stopFromNotification() {
        handler.post { if (state == State.RECORDING) stopAndTranscribe() }
    }

    private fun startRecording() {
        val prefs = Prefs(this)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val sttOk = prefs.cloudSttReady || ModelManager.isReady(this)
        if (!micOk || !sttOk) {
            Haptics.play(this, Haptics.Kind.ERROR)
            toast("Finish setup in the Tokalot app first")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        removeBubble()
        lastInsert = null
        targetApp = rootInActiveWindow?.packageName?.toString()?.takeIf { it != packageName }
        pendingStop = false; pendingCancel = false; autoStopped = false
        dictation.warmUp() // open connections to the providers while you talk
        setState(State.STARTING)
        // Start the mic foreground service first, then open the mic once Android confirms it.
        var began = false
        val begin = {
            if (!began && state == State.STARTING) {
                began = true
                recorder.onFull = {
                    handler.post {
                        if (state == State.RECORDING) {
                            toast("Reached 5 minutes. Transcribing…")
                            stopAndTranscribe()
                        }
                    }
                }
                if (recorder.start()) {
                    setState(State.RECORDING)
                    Haptics.play(this, Haptics.Kind.START)
                    when {
                        pendingCancel -> cancel()
                        pendingStop -> stopAndTranscribe()
                        prefs.autoStop && !prefs.holdToTalk -> handler.postDelayed(silenceCheck, 1000)
                    }
                } else {
                    RecordingService.stop(this)
                    setState(State.IDLE)
                    Haptics.play(this, Haptics.Kind.ERROR)
                    toast("Couldn't open the microphone")
                    updateButton()
                }
            }
        }
        RecordingService.onReady = { handler.post { begin() } }
        try {
            RecordingService.start(this)
        } catch (_: Exception) {
            RecordingService.onReady = null
            handler.post { begin() }
        }
        handler.postDelayed({ begin() }, 1000) // safety net if the service never reports back
    }

    private fun cancel() {
        if (state == State.STARTING) { pendingCancel = true; return }
        if (state == State.WORKING) { dictation.cancel(); return }
        if (state != State.RECORDING) return
        handler.removeCallbacks(silenceCheck)
        Haptics.play(this, Haptics.Kind.CANCEL)
        recorder.stop()
        RecordingService.stop(this)
        setState(State.IDLE)
        toast("Cancelled")
        updateButton()
    }

    private fun stopAndTranscribe() {
        handler.removeCallbacks(silenceCheck)
        Haptics.play(this, Haptics.Kind.STOP)
        val voiceEnd = recorder.lastVoiceSample
        val speech = recorder.speechSamples
        var samples = recorder.stop()
        // After an auto-stop, drop the trailing 30 s of silence: less to upload, and Whisper
        // tends to invent words ("Thank you.") in long silences.
        if (autoStopped && voiceEnd > 0) {
            val keep = (voiceEnd + Recorder.SAMPLE_RATE).coerceAtMost(samples.size)
            samples = samples.copyOf(keep)
        }
        RecordingService.stop(this)
        if (samples.size < MIN_SAMPLES) {
            setState(State.IDLE)
            updateButton()
            return
        }
        // Android feeds silence (not an error) when it blocks background mic access.
        val peak = samples.maxOf { abs(it) }
        if (peak < 0.0005f) {
            setState(State.IDLE)
            Haptics.play(this, Haptics.Kind.ERROR)
            toast("The mic only picked up silence. Android may have blocked it.")
            updateButton()
            return
        }
        // A quick tap with nothing said: Whisper turns room noise into "Thank you." (300 ms of sound = speech).
        val heard = speech >= Recorder.SAMPLE_RATE * 3 / 10
        if (!heard && samples.size < Recorder.SAMPLE_RATE * 5 / 2) {
            setState(State.IDLE)
            toast("Didn't catch anything")
            updateButton()
            return
        }
        setState(State.WORKING)
        workingSince = SystemClock.elapsedRealtime()
        dictation.process(samples, targetApp, sparse = !heard) { outcome, err ->
            setState(State.IDLE)
            when {
                // Nothing is typed; the recording is in the app's history with a Transcribe button.
                err == Dictation.CANCELLED -> { Haptics.play(this, Haptics.Kind.CANCEL); toast("Cancelled") }
                err == Dictation.TIMED_OUT -> {
                    Haptics.play(this, Haptics.Kind.ERROR); toast("Timed out. Open Tokalot to try it again.")
                }
                err != null -> { Haptics.play(this, Haptics.Kind.ERROR); toast(err) }
                outcome == null || outcome.text.isEmpty() -> {
                    Haptics.play(this, Haptics.Kind.ERROR); toast("Didn't catch anything")
                }
                else -> {
                    deliver(outcome.text)
                    Haptics.play(this, Haptics.Kind.DONE)
                    outcome.warning?.let { toast(it) }
                }
            }
            updateButton()
        }
    }

    // ---------- text insertion ----------

    /** Types into the focused field, or copies to the clipboard if there isn't one. */
    private fun deliver(text: String) {
        val node = focusedEditable()
        if (node == null || !insert(node, text)) {
            copyToClipboard(text)
            showBubble("Copied ✓  $text") { copyToClipboard(text); toast("Copied") }
        } else if (lastInsert != null) {
            showBubble("↶  Undo", 5000) { undoInsert() }
        }
    }

    /** What the last insert did, so Undo can reverse exactly that. */
    private sealed class LastInsert {
        /** Typed at the cursor; [replaced] is the selection it overwrote, if any. */
        class Typed(val piece: String, val replaced: String) : LastInsert()
        /** The whole field was rewritten; [before] is what it held. */
        class Rewritten(val node: AccessibilityNodeInfo, val before: String, val caret: Int) : LastInsert()
    }
    private var lastInsert: LastInsert? = null

    private fun undoInsert() {
        val li = lastInsert ?: return
        lastInsert = null
        val ok = when (li) {
            is LastInsert.Typed -> undoTyped(li)
            is LastInsert.Rewritten -> undoRewritten(li)
        }
        if (!ok) toast("Couldn't undo here")
    }

    /**
     * Three ways in, best first:
     *  1. Android 13+: type at the cursor through the field's own input connection, the way a
     *     keyboard does. Nothing else in the field is touched, so rich editors keep their
     *     formatting (a Gmail signature, mentions, links).
     *  2. Read the field, splice the text in and write the whole thing back (plain text only).
     *  3. Paste at the cursor, for fields that refuse 2.
     */
    private fun insert(node: AccessibilityNodeInfo, text: String): Boolean {
        lastInsert = null
        return typeAtCursor(text) || rewriteField(node, text)
    }

    /** False means "not available here" (older Android, or no connection to the field): nothing was typed. */
    private fun typeAtCursor(text: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ic = inputMethod?.currentInputConnection ?: return false
            // A little text either side of the cursor is enough to decide about spaces.
            val around = ic.getSurroundingText(AROUND, AROUND, 0) ?: return false
            val chars = around.text
            val start = minOf(around.selectionStart, around.selectionEnd)
            val end = maxOf(around.selectionStart, around.selectionEnd)
            if (start < 0 || end > chars.length) return false
            val piece = TextTools.pad(
                text,
                before = if (start > 0) chars[start - 1] else null,
                after = if (end < chars.length) chars[end] else null,
            )
            ic.commitText(piece, 1, null) // replaces the selection, if there is one
            lastInsert = LastInsert.Typed(piece, chars.subSequence(start, end).toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun undoTyped(li: LastInsert.Typed): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ic = inputMethod?.currentInputConnection ?: return false
            val n = li.piece.length
            val around = ic.getSurroundingText(n, 0, 0) ?: return false
            val caret = around.selectionStart
            // Only when the cursor still sits right after what was typed; anything else
            // (the user typed on, moved, or sent the message) and this would delete the wrong text.
            if (caret != around.selectionEnd || caret < n) return false
            if (around.text.subSequence(caret - n, caret).toString() != li.piece) return false
            ic.deleteSurroundingText(n, 0)
            if (li.replaced.isNotEmpty()) ic.commitText(li.replaced, 1, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rewriteField(node: AccessibilityNodeInfo, text: String): Boolean {
        // Empty boxes report their placeholder ("Message", "Search"…) as their text. Some apps
        // (e.g. WhatsApp) don't flag that reliably, so also compare against the hint itself.
        val raw = node.text?.toString() ?: ""
        val hintText = node.hintText?.toString()
        val caretAtStart = node.textSelectionStart <= 0 && node.textSelectionEnd <= 0
        val looksLikePlaceholder = caretAtStart &&
            raw.trim().trimEnd('…', '.').lowercase() in COMMON_PLACEHOLDERS
        val hint = node.isShowingHintText || raw.isEmpty() || (hintText != null && raw == hintText) || looksLikePlaceholder
        val current = if (hint) "" else raw
        var start = if (hint) 0 else node.textSelectionStart
        var end = if (hint) 0 else node.textSelectionEnd
        if (start < 0 || end < 0) { start = current.length; end = current.length }
        if (start > end) { val t = start; start = end; end = t }
        start = start.coerceAtMost(current.length)
        end = end.coerceAtMost(current.length)

        val piece = TextTools.pad(
            text,
            before = if (start > 0) current[start - 1] else null,
            after = if (end < current.length) current[end] else null,
        )
        val updated = current.substring(0, start) + piece + current.substring(end)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            lastInsert = LastInsert.Rewritten(node, current, start)
            val caret = start + piece.length
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            return true
        }
        // Some fields refuse SET_TEXT; try pasting at the cursor instead.
        return paste(node, piece)
    }

    private fun undoRewritten(li: LastInsert.Rewritten): Boolean {
        li.node.refresh()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, li.before)
        }
        if (!li.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, li.caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, li.caret)
        }
        li.node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    /**
     * Pastes [piece] at the cursor, then gives the clipboard back what it held. Android only
     * lets a background service read the clipboard in some situations; when it can't be read
     * there is nothing to restore and the dictated text stays on it.
     */
    private fun paste(node: AccessibilityNodeInfo, piece: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = runCatching { cm.primaryClip }.getOrNull()
        copyToClipboard(piece)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
        // The paste is handled by the other app a moment later, so don't swap it back too soon.
        if (previous != null) handler.postDelayed({ runCatching { cm.setPrimaryClip(previous) } }, 800)
        return true
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Tokalot", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

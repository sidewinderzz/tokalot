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
import android.widget.LinearLayout
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
        private const val IDLE_ALPHA = 0.76f // resting opacity: just enough to recede; colour (not transparency) does most of the quieting
        private val IDLE_BAR = Color.parseColor("#AEAEB2") // soft grey bars while resting
        private val BUBBLE_TEXT = Color.parseColor("#E2E2E6") // undo / copied hint text, a touch softer than white
        private const val PAD_DP = 8        // margin inside the window around the square, for its shadow
        private const val MIN_SAMPLES = Recorder.SAMPLE_RATE / 2 // half a second
        private const val AUTO_STOP_MS = 30_000L
        private const val CANCEL_SHOW_MS = 7000L // the cancel X only appears once transcribing has taken this long
        private const val LEARN_WATCH_MS = 120_000L // how long a dictation's text box is looked at again for corrections
        private const val LEARN_EVERY_MS = 1500L
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
    // "Slide up to cancel" guide shown above the button while recording (square style only).
    private var track: CancelTrackView? = null
    private var trackParams: WindowManager.LayoutParams? = null
    private var trackAttached = false
    private val trackTravel get() = dp(96)          // how far the button slides to reach the X
    private var buttonBg: ButtonDisc? = null
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

    private var bubble: View? = null
    private var bubbleAttached = false
    private val hideBubble = Runnable { removeBubble() }

    private val recheck = Runnable { updateButton() }

    // The window is the button body plus a margin on every side (for the shadow).
    private val winW get() = dp(2 * PAD_DP + 48)
    private val winH get() = dp(2 * PAD_DP + 48)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** The accent color with most of its saturation taken out (61% toward grey), for the resting button. */
    private fun washed(c: Int): Int {
        val grey = (0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c))
        fun mix(v: Int) = (v + (grey - v) * 0.61f).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dictation = Dictation(applicationContext)
        buildButton()
        Sync.request(this) // picks up dictionary and snippet changes made on another device
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A new input field taking focus brings a dismissed button back.
        if (dismissed && event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.source?.isEditable == true
        ) {
            dismissed = false
        }
        lookForUpdate()
        lookAgain = 6
        // Debounce: keyboard open/close fires a burst of window events.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 150)
    }

    private var lastUpdateLook = 0L

    /**
     * Looks for a new version now and then while the keyboard is in use (the check itself is limited to once
     * every few hours) and says so once per version. Without this, an update only showed up after opening the app.
     */
    private fun lookForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateLook < 30 * 60 * 1000L) return
        lastUpdateLook = now
        val app = applicationContext
        Thread {
            runCatching {
                Updater.check(app)
                Updater.announceOnce(app)?.let { v ->
                    handler.post { toast("Tokalot $v is available. Open Tokalot to update.") }
                }
            }
        }.start()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        button?.animate()?.cancel()
        takeLive()?.cancel()
        handler.removeCallbacks(learnLook)
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
        val bg = ButtonDisc(dp(PAD_DP).toFloat(), dp(1).toFloat(), dp(14).toFloat())
        val b = BarsView(this).apply {
            level = { recorder.level }
            // Resting: soft grey bars, the tall one a washed-out accent (full colour comes in when you use it).
            barColor = IDLE_BAR
            accentColor = washed(Prefs(this@OfflineFlowService).accent)
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
            addView(b, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            addView(x, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
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
        track = CancelTrackView(this, dp(30).toFloat(), dp(14).toFloat()).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        params = overlayParams(winW, winH)
        buildDropZone()
    }

    /** Show the slide-to-cancel guide above the button (below it if the button is near the top). */
    private fun showTrack() {
        val t = track ?: return
        val p = params ?: return
        if (!attached) return
        val h = trackTravel + dp(26) // room for the X at full size plus its shadow, so it never gets clipped
        val centre = p.y + winH / 2
        t.up = centre - h >= dp(24)
        t.progress = 0f
        val tp = trackParams ?: overlayParams(winW, h).also {
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            trackParams = it
        }
        tp.width = winW; tp.height = h
        tp.x = p.x
        tp.y = if (t.up) centre - h else centre
        try {
            if (trackAttached) wm.updateViewLayout(t, tp)
            else { t.alpha = 0f; wm.addView(t, tp); trackAttached = true }
            t.animate().alpha(1f).setDuration(160).start()
        } catch (_: Exception) {}
    }

    private fun hideTrack() {
        if (!trackAttached) return
        try { wm.removeView(track) } catch (_: Exception) {}
        trackAttached = false
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
    private fun overZone(p: WindowManager.LayoutParams) = p.y + winH / 2 <= dp(110)

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
        detach()
    }

    /**
     * Tap mode:  tap = start/stop, long-press = cancel, drag = move; while recording, slide up to the X = cancel.
     * Hold mode: hold = talk, release = finish, slide up to the X then release = cancel,
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
        private var sliding = false   // square style, recording: sliding toward the cancel X
        private var slideP = 0f
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
                    sliding = false; slideP = 0f
                    inDropZone = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start() // a small press-in
                    if (holdMode && state == State.IDLE) handler.postDelayed(holdStart, 220)
                    else handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val canSlide = holding || state == State.RECORDING
                    if (canSlide && (sliding || abs(dx) > slop || abs(dy) > slop)) {
                        // Square style while recording: the button only moves along the guide toward
                        // the X. Most of the way there and a release cancels.
                        if (!sliding) {
                            sliding = true
                            handler.removeCallbacks(longPress)
                            if (!trackAttached) showTrack()
                        }
                        val up = track?.up ?: true
                        val toward = if (up) -dy else dy
                        val pNew = (toward / trackTravel).coerceIn(0f, 1f)
                        if ((pNew >= CancelTrackView.ARM) != (slideP >= CancelTrackView.ARM))
                            Haptics.play(this@OfflineFlowService, Haptics.Kind.TICK)
                        slideP = pNew
                        track?.progress = pNew
                        p.x = startX
                        p.y = startY + ((if (up) -1 else 1) * pNew * trackTravel).toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    } else if (holding) {
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
                            // Only an idle button can be thrown away; mid-recording it stays put.
                            val canDismiss = state == State.IDLE
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()
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
                        sliding -> {
                            // Back to where it started, then cancel or carry on.
                            val armed = slideP >= CancelTrackView.ARM
                            p.x = startX; p.y = startY
                            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                            track?.progress = 0f
                            when {
                                armed -> cancel()
                                holding -> finishRecording()
                            }
                        }
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
                    releasePress(v) // after the action, so a state change's animation reset can't leave it half-scaled
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(holdStart)
                    if (dragging) showDropZone(false)
                    if (sliding) { p.x = startX; p.y = startY; try { wm.updateViewLayout(v, p) } catch (_: Exception) {}; track?.progress = 0f }
                    if (holding) finishRecording()
                    holding = false
                    releasePress(v)
                }
            }
            return true
        }

        private fun releasePress(v: View) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
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
        // The "active" window is sometimes the keyboard itself, or briefly missing while it slides up,
        // so look through every app window for the field that has input focus.
        val roots = sequenceOf(rootInActiveWindow) + windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { it.root }
        for (root in roots) {
            if (root == null || root.packageName == packageName) continue // not inside our own app
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            return if (node.isEditable && !node.isPassword) node else null
        }
        return null
    }

    private var lookAgain = 0 // re-checks left when the keyboard is up but no field was found yet

    private fun updateButton() {
        // Idle: only show when the keyboard is actually up on an editable field.
        // Recording/transcribing: stay visible even if the field or keyboard closed.
        if (state == State.IDLE && keyboardTop() == null) dismissed = false // keyboard closed: forget
        if (state == State.IDLE && (dismissed || keyboardTop() == null || focusedEditable() == null)) {
            // Keyboard up but no field found: some apps report the focused field a moment late and send
            // no further event, which left the button hidden. Look again a few times before giving up.
            if (!dismissed && keyboardTop() != null && lookAgain > 0) {
                lookAgain--
                handler.removeCallbacks(recheck)
                handler.postDelayed(recheck, 300)
            }
            detach()
            return
        }
        if (touching) return
        val p = params ?: return
        val (tx, ty) = targetPosition()
        if (!attached) {
            p.x = tx; p.y = ty
            button?.alpha = 0f
            button?.scaleX = 1f; button?.scaleY = 1f // never come back mid-press
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
            button?.animate()?.alpha(if (state == State.IDLE) IDLE_ALPHA else 1f)?.setDuration(140)?.start()
            return
        }
        if (tx != p.x || ty != p.y) {
            // The spot only changes when the screen turns or the position was reset in the app.
            p.x = tx; p.y = ty
            try { wm.updateViewLayout(button, p) } catch (_: Exception) {}
            if (state == State.RECORDING) showTrack() // keep the cancel guide with the button
        }
    }

    private fun keyboardTop(): Int? {
        val kb = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val r = Rect()
        kb.getBoundsInScreen(r)
        return if (r.height() > 0) r.top else null
    }

    // The button is pinned: it sits at the screen position it was last dragged to and never follows the
    // keyboard. Upright and sideways each keep their own spot ("px"/"py" and "lx"/"ly").
    private fun overlayPrefs() = getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private fun sideways() = resources.displayMetrics.let { it.widthPixels > it.heightPixels }

    private fun targetPosition(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val sp = overlayPrefs()
        val (kx, ky) = if (sideways()) "lx" to "ly" else "px" to "py"
        val maxX = dm.widthPixels - winW
        val maxY = dm.heightPixels - winH
        if (sp.contains(kx) && sp.contains(ky))
            return sp.safeInt(kx, 0).coerceIn(0, maxX) to sp.safeInt(ky, 0).coerceIn(dp(24), maxY)
        // No spot yet: the right edge, just above the keyboard. Measured once, then kept. ("x" and "above"
        // are where earlier versions kept a position that moved with the keyboard.)
        val x = sp.safeInt("x", dm.widthPixels - winW - dp(12 - PAD_DP)).coerceIn(0, maxX)
        val top = keyboardTop() ?: return x to (dm.heightPixels * 55 / 100).coerceIn(dp(24), maxY)
        val y = (top - winH - sp.safeInt("above", dp(8 - PAD_DP))).coerceIn(dp(24), maxY)
        sp.edit().putInt(kx, x).putInt(ky, y).apply()
        return x to y
    }

    private fun clamp(p: WindowManager.LayoutParams, allowTop: Boolean = false) {
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(0, dm.widthPixels - winW)
        p.y = p.y.coerceIn(if (allowTop) 0 else dp(24), dm.heightPixels - winH)
    }

    private fun savePosition(p: WindowManager.LayoutParams) {
        val (kx, ky) = if (sideways()) "lx" to "ly" else "px" to "py"
        overlayPrefs().edit().putInt(kx, p.x).putInt(ky, p.y).apply()
    }

    private fun detach() {
        touching = false
        hideTrack()
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
        State.WORKING -> "Transcribing"
    }

    /** Transcribing is taking a while: an X over the dimmed ripple shows that a tap now cancels. */
    private val showCancel = Runnable {
        if (state == State.WORKING) {
            cancelIcon?.visibility = View.VISIBLE
            bars?.alpha = 0.35f
            button?.contentDescription = "Cancel transcription"
        }
    }

    private fun setState(s: State) {
        state = s
        button?.contentDescription = describe(s)
        // A normal transcription takes a second or two; only offer to cancel when it drags on.
        handler.removeCallbacks(showCancel)
        cancelIcon?.visibility = View.GONE
        bars?.alpha = 1f
        if (s == State.WORKING) handler.postDelayed(showCancel, CANCEL_SHOW_MS)
        buttonBg?.active = s != State.IDLE
        button?.animate()?.cancel()
        button?.alpha = if (s == State.IDLE) IDLE_ALPHA else 1f
        // Keep the screen awake from tap to finished text, so it can't sleep mid-sentence.
        params?.let { p ->
            val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            p.flags = if (s == State.IDLE) p.flags and keepOn.inv() else p.flags or keepOn
            if (attached) try { wm.updateViewLayout(button, p) } catch (_: Exception) {}
        }
        // Resting: soft grey with a washed-out accent bar, so it sits quietly but is still easy to find.
        // Listening: bars in full accent color (amber by default). Transcribing: white ripple.
        val accent = Prefs(this).accent
        val listening = s == State.STARTING || s == State.RECORDING
        bars?.barColor = when {
            listening -> accent
            s == State.WORKING -> Color.WHITE
            else -> IDLE_BAR
        }
        bars?.accentColor = when (s) {
            State.WORKING -> null
            State.IDLE -> washed(accent)
            else -> accent
        }
        if (s == State.RECORDING) showTrack() else hideTrack()
        bars?.mode = when (s) {
            State.IDLE, State.STARTING -> BarsView.Mode.IDLE
            State.RECORDING -> BarsView.Mode.LISTENING
            State.WORKING -> BarsView.Mode.WORKING
        }
    }

    // ---------- result bubble ("hint") ----------

    /** Small graphite label near the button (e.g. "Copied ✓ …"). */
    private fun showBubble(label: String, durationMs: Long = 4000, onClick: () -> Unit) {
        removeBubble()
        val dm = resources.displayMetrics
        val tv = TextView(this).apply {
            text = label.replace('\n', ' ')
            setTextColor(BUBBLE_TEXT)
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = bubbleBackground()
            maxWidth = dm.widthPixels - dp(24)
            setOnClickListener { removeBubble(); onClick() }
        }
        attachBubble(tv, durationMs)
    }

    /** Same graphite as the resting button, slightly rounded corners and a faint rim, so it reads as part of it. */
    /** Just the undo arrow in a small graphite rounded square. */
    private fun showUndoChip(durationMs: Long) {
        removeBubble()
        val iv = ImageView(this).apply {
            setImageDrawable(this@OfflineFlowService.getDrawable(R.drawable.ic_undo)?.mutate()?.apply { setTint(BUBBLE_TEXT) })
            background = bubbleBackground()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Undo the dictation"
            setOnClickListener { removeBubble(); undoInsert() }
        }
        attachBubble(FrameLayout(this).apply {
            // 36dp to look at; the frame keeps it a comfortable size to tap
            addView(iv, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            minimumWidth = winW; minimumHeight = dp(44) // as wide as the button window, so it sits centred over it
        }, durationMs)
    }

    private fun bubbleBackground() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#E6505055"), Color.parseColor("#E6333336"))
    ).apply {
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
    }

    /**
     * The same pill with two separately tappable halves: the undo arrow and "My wording", which swaps the
     * AI's polished text for the user's own words. Shown when "Polish my wording" is on.
     */
    private fun showSwapBubble(plain: String, entryId: Long?, durationMs: Long) {
        removeBubble()
        fun part(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(BUBBLE_TEXT)
            textSize = 13f
            maxLines = 1
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minWidth = dp(56)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = desc
            setOnClickListener { removeBubble(); onClick() }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bubbleBackground()
            addView(ImageView(this@OfflineFlowService).apply {
                setImageDrawable(this@OfflineFlowService.getDrawable(R.drawable.ic_undo)?.mutate()?.apply { setTint(BUBBLE_TEXT) })
                setPadding(dp(12), dp(10), dp(10), dp(10))
                contentDescription = "Undo the dictation"
                setOnClickListener { removeBubble(); undoInsert() }
            }, LinearLayout.LayoutParams(dp(42), dp(40)))
            addView(View(this@OfflineFlowService).apply {
                setBackgroundColor(Color.parseColor("#26FFFFFF"))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(1), dp(18)))
            addView(part("My wording", "Use my own wording") { useMyWording(plain, entryId) })
        }
        attachBubble(pill, durationMs)
    }

    private fun attachBubble(view: View, durationMs: Long) {
        val dm = resources.displayMetrics
        val bp = params ?: return
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        // Line the bubble up with whichever side of the screen the button is on.
        if (bp.x + winW / 2 > dm.widthPixels / 2) {
            p.gravity = Gravity.TOP or Gravity.END
            p.x = dm.widthPixels - bp.x - winW
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = bp.x
        }
        p.y = if (bp.y > dp(90)) bp.y - dp(56) else bp.y + winH + dp(8)
        try {
            wm.addView(view, p)
            bubble = view
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
            // A tap cancels once the X is showing (the callback in stopAndTranscribe() does the rest).
            // Before that it does nothing, so a double-tap on "stop" can't cancel. A long press cancels at any time.
            State.WORKING -> if (SystemClock.elapsedRealtime() - workingSince > CANCEL_SHOW_MS) dictation.cancel()
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
        stopWatching(learn = true) // a correction made before this dictation still counts
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
                    live = dictation.newLive()
                    if (live != null) handler.postDelayed(liveFeed, 300)
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

    /** Long dictations: the part said so far is sent for transcription at each pause (see [LiveStt]). */
    private var live: LiveStt? = null
    private val liveFeed = object : Runnable {
        override fun run() {
            val l = live ?: return
            if (state != State.RECORDING) return
            l.feed(recorder.count, recorder.lastVoiceSample, recorder::snapshot)
            handler.postDelayed(this, 300)
        }
    }

    /** Takes the pieces sent so far out of the recording loop; the caller either uses them or cancels them. */
    private fun takeLive(): LiveStt? {
        handler.removeCallbacks(liveFeed)
        return live.also { live = null }
    }

    private fun cancel() {
        if (state == State.STARTING) { pendingCancel = true; return }
        if (state == State.WORKING) { dictation.cancel(); return }
        if (state != State.RECORDING) return
        takeLive()?.cancel()
        handler.removeCallbacks(silenceCheck)
        Haptics.play(this, Haptics.Kind.CANCEL)
        recorder.stop()
        RecordingService.stop(this)
        setState(State.IDLE)
        toast("Cancelled")
        updateButton()
    }

    private fun stopAndTranscribe() {
        val early = takeLive()
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
            early?.cancel()
            setState(State.IDLE)
            updateButton()
            return
        }
        // Android feeds silence (not an error) when it blocks background mic access.
        val peak = samples.maxOf { abs(it) }
        if (peak < 0.0005f) {
            early?.cancel()
            setState(State.IDLE)
            Haptics.play(this, Haptics.Kind.ERROR)
            toast("The mic only picked up silence. Android may have blocked it.")
            updateButton()
            return
        }
        // A quick tap with nothing said: Whisper turns room noise into "Thank you." (300 ms of sound = speech).
        val heard = speech >= Recorder.SAMPLE_RATE * 3 / 10
        if (!heard && samples.size < Recorder.SAMPLE_RATE * 5 / 2) {
            early?.cancel()
            setState(State.IDLE)
            toast("Didn't catch anything")
            updateButton()
            return
        }
        setState(State.WORKING)
        workingSince = SystemClock.elapsedRealtime()
        dictation.process(samples, targetApp, sparse = !heard, live = early) { outcome, err ->
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
                    deliver(outcome.text, outcome.plain, outcome.entryId)
                    Haptics.play(this, Haptics.Kind.DONE)
                    outcome.warning?.let { toast(it) }
                }
            }
            updateButton()
        }
    }

    // ---------- text insertion ----------

    /** Types into the focused field, or copies to the clipboard if there isn't one. */
    // ---------- learning names and terms from corrections (off unless switched on; see Learn) ----------

    private var learnText: String? = null   // the dictation whose text box is being looked at again
    private var learnUntil = 0L
    private var learnSeen: String? = null   // the corrected word found at the last look
    private var learnStable: String? = null // the same word found two looks running (so not caught mid-typing)
    private val learnLook = Runnable { lookForCorrection() }

    private fun watchForCorrection(text: String) {
        stopWatching(learn = false)
        if (!Prefs(this).learnWords) return
        learnText = text
        learnUntil = SystemClock.elapsedRealtime() + LEARN_WATCH_MS
        handler.postDelayed(learnLook, LEARN_EVERY_MS)
    }

    private fun lookForCorrection() {
        val text = learnText ?: return
        val field = runCatching { focusedEditable()?.text?.toString() }.getOrNull()
        val seen = field?.let { Learn.look(text, it, Prefs(this).words) }
        // The dictation is gone (sent, cleared, or the text box was left): what was last seen stands.
        if (seen == null || !seen.found) { stopWatching(learn = true); return }
        learnStable = seen.word?.takeIf { it == learnSeen }
        learnSeen = seen.word
        if (SystemClock.elapsedRealtime() > learnUntil) stopWatching(learn = true)
        else handler.postDelayed(learnLook, LEARN_EVERY_MS)
    }

    /** learn: add the correction that was seen, if there was one. False when the dictation itself was undone. */
    private fun stopWatching(learn: Boolean) {
        handler.removeCallbacks(learnLook)
        val word = learnStable
        learnText = null; learnSeen = null; learnStable = null
        if (!learn || word == null) return
        val prefs = Prefs(this)
        if (prefs.words.any { it.equals(word, ignoreCase = true) }) return
        prefs.words = prefs.words + word
        prefs.learnedWords = prefs.learnedWords + word
        showBubble("Learned “$word” · tap to undo", 6000) {
            val p = Prefs(this)
            p.words = p.words - word
            p.learnedWords = p.learnedWords - word
            toast("Removed “$word”")
        }
    }

    private fun deliver(text: String, plain: String? = null, entryId: Long? = null) {
        val node = focusedEditable()
        if (node == null || !insert(node, text)) {
            copyToClipboard(text)
            showBubble("Copied ✓  $text") { copyToClipboard(text); toast("Copied") }
        } else if (lastInsert != null) {
            watchForCorrection(text)
            // plain: the AI polished the wording, so offer the user's own words next to Undo.
            if (plain != null) showSwapBubble(plain, entryId, 7000)
            else showUndoChip(5000)
        }
    }

    /** What the last insert did, so Undo can reverse exactly that. */
    private sealed class LastInsert {
        /** Typed at the cursor; [replaced] is the selection it overwrote, if any. */
        class Typed(val piece: String, val replaced: String) : LastInsert()
        /**
         * The whole field was rewritten; [before] is what it held, [caret]..[end] the selection the
         * text went into, [after] what the field holds now.
         */
        class Rewritten(
            val node: AccessibilityNodeInfo, val before: String, val caret: Int, val end: Int, val after: String,
        ) : LastInsert()
    }
    private var lastInsert: LastInsert? = null

    private fun undoInsert() {
        val li = lastInsert ?: return
        lastInsert = null
        stopWatching(learn = false)
        val ok = when (li) {
            is LastInsert.Typed -> undoTyped(li)
            is LastInsert.Rewritten -> undoRewritten(li)
        }
        if (!ok) toast("Couldn't undo here")
    }

    /**
     * "My wording": takes the just-inserted text back out and puts [plain] (the user's own words)
     * in its place, then offers Undo for that. Only when the insert can still be undone safely;
     * otherwise the text is left alone. Either way the history entry switches to the user's wording.
     */
    private fun useMyWording(plain: String, entryId: Long?) {
        val li = lastInsert
        lastInsert = null
        stopWatching(learn = false)
        entryId?.let { History.useOwnWording(this, it, plain) }
        val ok = when (li) {
            is LastInsert.Typed -> swapTyped(li, plain)
            is LastInsert.Rewritten -> swapRewritten(li, plain)
            null -> false
        }
        if (ok) showUndoChip(5000)
        else toast("Couldn't swap it here. Your wording is in history.")
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

    /** Undoes [li] and types [text] in the same place, as one edit. False: nothing was changed. */
    private fun swapTyped(li: LastInsert.Typed, text: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ic = inputMethod?.currentInputConnection ?: return false
            val n = li.piece.length
            // One more character either side than the piece itself, to decide about spaces again.
            val around = ic.getSurroundingText(n + 1, 1, 0) ?: return false
            val chars = around.text
            val caret = around.selectionStart
            // Same check as undoTyped: the cursor must still sit right after what was typed.
            if (caret != around.selectionEnd || caret < n || caret > chars.length) return false
            if (chars.subSequence(caret - n, caret).toString() != li.piece) return false
            val piece = TextTools.pad(
                text,
                before = if (caret > n) chars[caret - n - 1] else null,
                after = if (caret < chars.length) chars[caret] else null,
            )
            ic.deleteSurroundingText(n, 0)
            ic.commitText(piece, 1, null)
            lastInsert = LastInsert.Typed(piece, li.replaced)
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
            lastInsert = LastInsert.Rewritten(node, current, start, end, updated)
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

    /** Rewrites the field as it was before [li], with [text] where the dictation went. False: nothing was changed. */
    private fun swapRewritten(li: LastInsert.Rewritten, text: String): Boolean {
        // Only when the field still holds exactly what the insert left there.
        if (!li.node.refresh() || (li.node.text?.toString() ?: "") != li.after) return false
        val piece = TextTools.pad(
            text,
            before = if (li.caret > 0) li.before[li.caret - 1] else null,
            after = if (li.end < li.before.length) li.before[li.end] else null,
        )
        val updated = li.before.substring(0, li.caret) + piece + li.before.substring(li.end)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!li.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        lastInsert = LastInsert.Rewritten(li.node, li.before, li.caret, li.end, updated)
        val caret = li.caret + piece.length
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        }
        li.node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
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

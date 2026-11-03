package com.tokalot.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A note's reminder and checklist (beta), on the note's page: the reminder as a bell pill, the checklist as tick boxes
 * with a time on the lines that ring, a box to add a line, and a warning with Fix buttons when something on the phone
 * would stop reminders ringing. Every change is saved at once (and synced, and the alarms set again).
 */
class PlanCard(private val a: MainActivity, private val noteId: Long) {
    private val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    /** "Add a checklist" was picked: show the empty list with its box, before it has a line. */
    private var listOpen = false

    fun view(): View { render(); return root }

    /** From the note's ⋮ menu. */
    fun addReminder(anchor: View) = pickTime(anchor, "Remind me") { at -> save { (it ?: NotePlan()).copy(remind = at, done = false) } }

    fun addChecklist() { listOpen = true; render() }

    fun hasReminder() = plan()?.remind?.let { it != 0L } == true
    fun hasList() = listOpen || plan()?.items?.isNotEmpty() == true

    private fun plan(): NotePlan? = Notes.all(a).firstOrNull { it.id == noteId }?.plan

    private fun save(change: (NotePlan?) -> NotePlan?) {
        runCatching { Notes.updatePlan(a, noteId, change = change) }.onFailure { a.toast("Couldn't save that") }
        render()
    }

    private fun render() = with(a) {
        root.removeAllViews()
        val plan = plan()
        val now = ZonedDateTime.now()

        // The note's own reminder.
        if (plan != null && plan.remind != 0L) {
            val label = Plans.label(plan.remind, now) + (if (plan.alarm) " · alarm" else "") + (if (plan.done) " · done" else "")
            val pill = pill(label, R.drawable.ic_bell, desc = "Reminder, $label. Change it") {}
            pill.setOnClickListener {
                popupMenu(pill, listOf(
                    "Change time" to { pickTime(pill, "Remind me") { at -> save { p -> p?.let { Plans.moveTo(it, null, at) } } } },
                    (if (plan.alarm) "Ring as a notification" else "Ring like an alarm") to { save { p -> p?.copy(alarm = !plan.alarm) } },
                    (if (plan.done) "Not done yet" else "Mark done") to { save { p -> p?.let { Plans.setDone(it, null, !plan.done) } } },
                    "Remove reminder" to { save { p -> p?.copy(remind = 0, done = false, alarm = false) } },
                ))
            }
            if (plan.done) (pill.getChildAt(pill.childCount - 1) as? android.widget.TextView)?.let {
                it.paintFlags = it.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            root.addView(row(pill), lp().margins(this, b = 6))
        }

        // The checklist.
        val items = plan?.items.orEmpty()
        if (items.isNotEmpty() || listOpen) {
            val box = card(14)
            val doneCount = items.count { it.done }
            if (items.isNotEmpty()) box.addView(text("$doneCount of ${items.size} done", 13f, C.SUB), lp().margins(this, l = 4, b = 2))
            items.forEach { item -> box.addView(itemRow(item, now), lp()) }
            val f = field("Add an item")
            fun add() {
                val said = f.text.toString().trim()
                if (said.isEmpty()) return
                // "Load the dogs at 6" gets its time, like a spoken line.
                val found = SpokenTime.find(said, ZonedDateTime.now())
                val text = SpokenTime.strip(said, found).ifEmpty { said }
                val id = System.currentTimeMillis()
                save { p -> (p ?: NotePlan()).let { it.copy(items = it.items + PlanItem(id, text, at = found?.at ?: 0)) } }
                listOpen = true
                // Ready for the next line.
                root.post { (root.findViewWithTag<View>("add") as? android.widget.EditText)?.requestFocus() }
            }
            f.tag = "add"
            f.imeOptions = EditorInfo.IME_ACTION_DONE
            f.setOnEditorActionListener { _, _, _ -> add(); true }
            box.addView(row(
                f.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                spacer(wDp = 8),
                pill("Add") { add() },
            ).apply { gravity = Gravity.CENTER_VERTICAL }, lp().margins(this, t = 6))
            root.addView(box, lp().margins(this, b = 12))
        }

        // Something on the phone would stop it ringing: say what, with a button to fix each.
        if (Plans.dues(noteId, "", plan).any { it.at > System.currentTimeMillis() }) checkCard()?.let { root.addView(it, lp().margins(this, b = 12)) }
        root.visibility = if (root.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun itemRow(item: PlanItem, now: ZonedDateTime): View = with(a) {
        val cb = CheckBox(this).apply {
            text = item.text
            textSize = 17f
            setTextColor(if (item.done) C.SUB else C.TEXT)
            if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            buttonTintList = ColorStateList.valueOf(C.visible(prefs.accent))
            minHeight = dp(TOUCH_DP)
            isChecked = item.done
            setOnCheckedChangeListener { _, on -> save { p -> p?.let { Plans.setDone(it, item.id, on) } } }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cb.setOnLongClickListener {
            popupMenu(cb, listOf(
                "Edit" to {
                    val f = field("Item", item.text)
                    sheet("Edit item", f, positive = "Save") {
                        val t = f.text.toString().trim()
                        if (t.isNotEmpty()) save { p -> p?.copy(items = p.items.map { if (it.id == item.id) it.copy(text = t) else it }) }
                    }
                    Unit
                },
                "Delete" to { save { p -> p?.copy(items = p.items.filter { it.id != item.id }) } },
            ))
            true
        }
        val time: View = if (item.at != 0L) {
            val label = Plans.label(item.at, now) + if (item.alarm) " · alarm" else ""
            tagChip(label).apply {
                contentDescription = "Rings $label. Change it"
                setOnClickListener {
                    popupMenu(this, listOf(
                        "Change time" to { pickTime(this, item.text) { at -> save { p -> p?.let { Plans.moveTo(it, item.id, at) } } } },
                        (if (item.alarm) "Ring as a notification" else "Ring like an alarm") to {
                            save { p -> p?.copy(items = p.items.map { if (it.id == item.id) it.copy(alarm = !item.alarm) else it }) }
                        },
                        "No time" to { save { p -> p?.copy(items = p.items.map { if (it.id == item.id) it.copy(at = 0, alarm = false) else it }) } },
                    ))
                }
            }
        } else iconButton(R.drawable.ic_bell, "Add a time to ${item.text}", 18, C.SUB) {}.also { b ->
            b.setOnClickListener { pickTime(b, item.text) { at -> save { p -> p?.let { Plans.moveTo(it, item.id, at) } } } }
        }
        row(cb, spacer(wDp = 6), time).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    /** Quick picks first; "Pick a date and time" for anything else. Calls [done] with epoch ms. */
    private fun pickTime(anchor: View, what: String, done: (Long) -> Unit) = with(a) {
        val now = ZonedDateTime.now()
        fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(now.zone).toInstant().toEpochMilli()
        val picks = mutableListOf<Pair<String, () -> Unit>>(
            "In 1 hour" to { done(now.plusHours(1).withSecond(0).withNano(0).toInstant().toEpochMilli()) },
        )
        if (now.hour < 19) picks += "Tonight, 8:00 PM" to { done(at(now.toLocalDate(), 20)) }
        picks += "Tomorrow, 9:00 AM" to { done(at(now.toLocalDate().plusDays(1), 9)) }
        picks += "Pick a date and time…" to {
            DatePickerDialog(this, { _, y, mo, d ->
                val day = LocalDate.of(y, mo + 1, d)
                val start = LocalTime.of(9, 0)
                TimePickerDialog(this, { _, h, m -> done(at(day, h, m)) }, start.hour, start.minute,
                    android.text.format.DateFormat.is24HourFormat(this)).apply { setTitle(what) }.show()
            }, now.year, now.monthValue - 1, now.dayOfMonth).show()
        }
        popupMenu(anchor, picks)
    }

    /** The Reminder check: null when everything that makes reminders ring is fine. */
    private fun checkCard(): View? = with(a) {
        val bad = Reminders.checks(this).filter { !it.ok }
        if (bad.isEmpty()) return null
        val box = card(16)
        box.addView(text("Reminders might not ring on time", 16f, C.WARN, bold = true), lp())
        bad.forEach { c ->
            box.addView(row(
                text(c.title + " is off", 15f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                pill(c.fixLabel) {
                    runCatching { startActivity(c.fix ?: return@runCatching) }.onFailure { toast("Open Android's settings for Tokalot") }
                },
            ).apply { gravity = Gravity.CENTER_VERTICAL }, lp().margins(this, t = 6))
        }
        if (Reminders.samsung) box.addView(text(
            "On Samsung, also check Settings › Battery › Background usage limits, and take Tokalot off \"Sleeping apps\" and \"Deep sleeping apps\".",
            14f, C.SUB), lp().margins(this, t = 8))
        box
    }

    /** Called when the page shows again (back from Android's settings), so fixed checks disappear. */
    fun refresh() = render()
}

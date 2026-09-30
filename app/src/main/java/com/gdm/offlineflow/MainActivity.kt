package com.gdm.offlineflow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One-time setup: mic permission, model download, enable the accessibility service. */
class MainActivity : Activity() {

    private lateinit var micStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var downloadBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        col.addView(label("OfflineFlow", 26f))
        col.addView(label("Voice typing that runs on this phone. Three steps:"))

        micStatus = label("")
        col.addView(micStatus)
        col.addView(button("1. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        modelStatus = label("")
        col.addView(modelStatus)
        downloadBtn = button("2. Download speech model (~60 MB)") { startDownload() }
        col.addView(downloadBtn)

        serviceStatus = label("")
        col.addView(serviceStatus)
        col.addView(button("3. Turn on OfflineFlow in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(label("After that, tap any text field and a mic button appears above your keyboard. Tap to talk, tap again to type it in."))

        setContentView(
            ScrollView(this).apply {
                addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        refresh()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (mic) "Microphone: allowed" else "Microphone: not allowed yet"
        modelStatus.text = if (ModelManager.isReady(this)) "Model: ready" else "Model: not downloaded"
        serviceStatus.text = if (serviceEnabled()) "Accessibility service: on" else "Accessibility service: off"
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.contains("$packageName/${OfflineFlowService::class.java.name}")
    }

    private fun startDownload() {
        downloadBtn.isEnabled = false
        Thread {
            val err = ModelManager.download(this) { pct ->
                runOnUiThread { modelStatus.text = "Model: downloading $pct%" }
            }
            runOnUiThread {
                downloadBtn.isEnabled = true
                refresh()
                if (err != null) modelStatus.text = "Model: failed ($err)"
            }
        }.start()
    }
}

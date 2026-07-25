package com.example.aicallresponder

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Lets the user browse, download (at runtime, into app storage), activate, and delete Whisper
 * models — so they can find the best accuracy/speed trade-off for their device without bundling any
 * model in the APK. UI is built in code to stay framework-only (no AndroidX/RecyclerView).
 */
class ModelManagerActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var headerFree: TextView
    private val rows = mutableListOf<Row>()
    private val main = Handler(Looper.getMainLooper())

    private class Row(
        val model: WhisperModel,
        val status: TextView,
        val action: Button,
        val delete: Button
    )

    private val poller = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Whisper models"
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        main.post(poller)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poller)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Download a model to enable on-device Nepali speech-to-text. Larger = more " +
                "accurate but slower and heavier. Files are stored in the app and used when a call " +
                "is answered."
            setPadding(0, 0, 0, dp(8))
        })
        headerFree = TextView(this).apply { setPadding(0, 0, 0, dp(12)) }
        root.addView(headerFree)

        for (model in WhisperCatalog.models) {
            root.addView(buildRow(model))
            root.addView(divider())
        }
        return scroll
    }

    private fun buildRow(model: WhisperModel): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(TextView(this).apply {
            text = "${model.name}  •  ${model.sizeLabel}"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        box.addView(TextView(this).apply {
            text = model.note
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        val status = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        box.addView(status)

        val buttonBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val action = Button(this)
        val delete = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                ModelStore.delete(this@ModelManagerActivity, model)
                toast("Deleted ${model.name}")
                refresh()
            }
        }
        buttonBar.addView(action)
        buttonBar.addView(delete)
        box.addView(buttonBar)

        rows.add(Row(model, status, action, delete))
        return box
    }

    private fun refresh() {
        val freeGb = ModelStore.modelsDir(this).usableSpace / (1024.0 * 1024 * 1024)
        headerFree.text = "Free space: %.1f GB   •   active model: %s".format(
            freeGb, activeModelName() ?: "none"
        )

        for (row in rows) {
            val downloaded = ModelStore.isDownloaded(this, row.model)
            val downloading = ModelDownloadService.activeId == row.model.id
            val isActive = prefs.whisperModelPath ==
                ModelStore.fileFor(this, row.model).absolutePath

            when {
                downloading -> {
                    val p = ModelDownloadService.progress
                    row.status.setTextColor(Color.parseColor("#1565C0"))
                    row.status.text = if (p in 0..100) "Downloading… $p%" else "Downloading…"
                    row.action.text = "Cancel"
                    row.action.setOnClickListener {
                        ModelDownloadService.cancel(this)
                        toast("Cancelling…")
                    }
                    row.delete.visibility = View.GONE
                }
                downloaded -> {
                    row.status.setTextColor(Color.parseColor("#2E7D32"))
                    row.status.text = if (isActive) "Downloaded • ACTIVE" else "Downloaded"
                    row.action.text = if (isActive) "In use" else "Use this model"
                    row.action.isEnabled = !isActive
                    row.action.setOnClickListener {
                        prefs.whisperModelPath = ModelStore.fileFor(this, row.model).absolutePath
                        prefs.sttEngine = "whisper"
                        toast("${row.model.name} set as active STT model")
                        refresh()
                    }
                    row.delete.visibility = View.VISIBLE
                }
                else -> {
                    val busy = ModelDownloadService.activeId != null
                    val err = ModelDownloadService.lastError
                    row.status.setTextColor(Color.DKGRAY)
                    row.status.text = if (err != null && ModelDownloadService.lastCompletedId == null)
                        "Not downloaded (last error: $err)" else "Not downloaded"
                    row.action.text = "Download"
                    row.action.isEnabled = !busy
                    row.action.setOnClickListener {
                        if (ModelDownloadService.activeId != null) {
                            toast("Another download is in progress")
                        } else {
                            ModelDownloadService.start(this, row.model)
                            toast("Downloading ${row.model.name}…")
                        }
                    }
                    row.delete.visibility = View.GONE
                }
            }
        }
    }

    private fun activeModelName(): String? {
        val path = prefs.whisperModelPath
        if (path.isBlank()) return null
        return WhisperCatalog.models.firstOrNull {
            ModelStore.fileFor(this, it).absolutePath == path
        }?.name ?: "custom"
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
        setBackgroundColor(Color.LTGRAY)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

package com.mekromn.bubble

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.UUID

/** Local metadata only: no credential-bearing links. File opens are always explicit. */
class DownloadsActivity : Activity() {
    private var token = ""
    private lateinit var column: LinearLayout
    private val changed: () -> Unit = { render() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        BrowserDownloads.initialize(this)
        token = intent.getStringExtra("uiToken") ?: UUID.randomUUID().toString()
        if (!FileUi.begin(token)) { finish(); return }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Ui.BG) }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onStart() { super.onStart(); if (::column.isInitialized) BrowserDownloads.listen(changed) }
    override fun onStop() { BrowserDownloads.unlisten(changed); super.onStop() }
    override fun onDestroy() { FileUi.end(token); super.onDestroy() }
    private fun render() {
        column.removeAllViews()
        column.addView(Ui.text(this, "Downloads", 22f, Ui.TEXT, true))
        for (r in BrowserDownloads.records.take(100)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 18, 12, 18) }
            row.addView(Ui.text(this, r.name, 16f, Ui.TEXT, true))
            row.addView(Ui.text(this, "${r.state} · ${r.bytes} bytes\n${r.error}", 13f, Ui.MUTED))
            val label = when (r.state) { "Choose location" -> "Save file"; "Saving" -> "Cancel"; "Saved" -> "Open file"; else -> "Retry using the original file link" }
            row.addView(Ui.text(this, label, 15f).apply {
                minHeight = Ui.dp(context, 48f); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    when (r.state) {
                        "Choose location" -> { FileUi.end(token); finish(); BrowserDownloads.choose(this@DownloadsActivity, r.id) }
                        "Saving" -> BrowserDownloads.cancel(r.id)
                        "Saved" -> { FileUi.end(token); BrowserDownloads.open(this@DownloadsActivity, r); finish() }
                    }
                }
            })
            column.addView(row)
        }
        if (BrowserDownloads.records.isEmpty()) column.addView(Ui.text(this, "Download a file from a webpage, then choose where to save it.", 15f))
        column.addView(Ui.text(this, "Done", 16f).apply { minHeight = Ui.dp(context, 48f); setOnClickListener { finish() } })
    }
}

package com.frameworkstudios.autotapper

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invisible one-shot activity that imports a profile from the clipboard.
 * Exists because on Android 10+ only a focused app may read the clipboard,
 * and a foreground activity is the reliable way to get that focus.
 */
class ImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Clipboard is not readable before the window gains focus; do it there.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

        val result = importProfile(text)
        Toast.makeText(this, result, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun importProfile(text: String?): String {
        if (text.isNullOrBlank()) return "Clipboard is empty — copy a profile first."
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return "Clipboard doesn't contain a valid AutoTapper profile."
        if (!json.has("steps")) return "Clipboard doesn't contain a valid AutoTapper profile."

        val fallback = "Imported " +
            SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val name = json.optString("name").ifBlank { fallback }

        ProfileStore(this).saveProfile(name, json)
        OverlayService.instance?.onProfilesChanged(name)
        return "Imported profile \"$name\"."
    }
}

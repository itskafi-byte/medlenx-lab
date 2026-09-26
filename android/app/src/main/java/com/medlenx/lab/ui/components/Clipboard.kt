package com.medlenx.lab.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * Puts text on the system clipboard and says so.
 *
 * Lifted out of `MedLenXShell`, where it was private, so the scan review's pitch
 * copy can use the same one instead of becoming the fourth hand-rolled copy of
 * `setPrimaryClip`. `ui/screens/hub/HubScreen.kt` still has its own inline version
 * for the campaign script; it uses `as? ClipboardManager` with a null check and
 * predates this, so it is left alone rather than swept up in an unrelated change.
 *
 * The toast is part of the helper on purpose. A copy with no confirmation is
 * indistinguishable from a button that did nothing, which is a bug this codebase
 * has already shipped once (see `344c27c`, the two dead controls on the scan card).
 */
fun copyToClipboard(context: Context, text: String, label: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied to the clipboard", Toast.LENGTH_SHORT).show()
}

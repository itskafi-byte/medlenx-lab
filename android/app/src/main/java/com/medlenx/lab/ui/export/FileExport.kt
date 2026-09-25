package com.medlenx.lab.ui.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Writes a generated document to a location the user picks.
 *
 * The web "exports" by navigating the browser at an endpoint and letting the
 * download manager do the rest. A standalone Android build has no server to
 * fetch from, so the document is built on the device and handed to the system
 * file picker — `ActivityResultContracts.CreateDocument` — which is what gives
 * the user control over the destination without the app needing any storage
 * permission. On API 29+ this writes through `MediaStore`/SAF and needs no
 * runtime grant at all.
 *
 * Two launchers, not one, because `CreateDocument` fixes its MIME type when the
 * contract is constructed: a single launcher would have to declare
 * `application/octet-stream` for both, and the picker would then offer the user
 * no default filename extension and no "PDF" or "CSV" intent handling.
 *
 * [DocumentSaver.savePdf] and [saveCsv] take the bytes rather than a producer
 * lambda on purpose: the document is fully generated *before* the picker opens,
 * so a generation failure surfaces as a message instead of as an empty file the
 * user has already chosen a home for.
 */
class DocumentSaver internal constructor(
    private val save: (name: String, bytes: ByteArray, pdf: Boolean) -> Unit,
) {
    fun savePdf(name: String, bytes: ByteArray) = save(name, bytes, true)

    fun saveCsv(name: String, bytes: ByteArray) = save(name, bytes, false)
}

/**
 * Remembers the picker plumbing and returns a [DocumentSaver].
 *
 * [onMessage] receives one line describing the outcome — success or the reason it
 * failed — so the caller can surface it as a toast. Cancelling the picker reports
 * nothing, because the user already knows what they did.
 */
@Composable
fun rememberDocumentSaver(onMessage: (String) -> Unit): DocumentSaver {
    val context = LocalContext.current

    // The bytes chosen by the most recent save() call, waiting for the picker.
    // Held in an explicit MutableState rather than a `by remember` delegate so the
    // launcher callbacks below capture the state object rather than a local
    // variable, which is the version that cannot go stale.
    val pending = remember { mutableStateOf<PendingDocument?>(null) }

    fun onPicked(uri: Uri?) {
        val document = pending.value
        pending.value = null
        // A null uri means the user backed out of the picker. Nothing was asked
        // for, so nothing is reported.
        if (document == null || uri == null) return

        val result = runCatching {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("the destination could not be opened for writing")
            stream.use { out ->
                out.write(document.bytes)
                out.flush()
            }
        }
        onMessage(
            result.fold(
                onSuccess = { "${document.name} saved" },
                onFailure = { "Could not save ${document.name}: ${it.message}" },
            ),
        )
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> onPicked(uri) }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> onPicked(uri) }

    // Deliberately not `remember`ed: the holder is a trivial two-field object and
    // rebuilding it each recomposition removes any chance of a lambda closing over
    // a previous composition's launcher.
    return DocumentSaver { name, bytes, pdf ->
        pending.value = PendingDocument(name, bytes)
        if (pdf) pdfLauncher.launch(name) else csvLauncher.launch(name)
    }
}

internal class PendingDocument(val name: String, val bytes: ByteArray)

/**
 * `Pitch_Card_Seclo_rxA-12.pdf` — the web's naming, with anything that is not
 * safe in a filename folded to an underscore.
 */
fun exportFileName(vararg parts: String, extension: String): String {
    val stem = parts
        .filter { it.isNotBlank() }
        .joinToString("_")
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
    return "${stem.ifBlank { "export" }}.$extension"
}

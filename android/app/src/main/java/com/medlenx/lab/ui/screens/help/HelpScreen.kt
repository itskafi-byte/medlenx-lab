package com.medlenx.lab.ui.screens.help

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.ErrorReportEntity
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MiniKpiTile
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxErrorLine
import com.medlenx.lab.ui.components.MlxTextField
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Counts for the retraining queue. See [HelpViewModel.queue] on the status question.
 */
data class TrainingQueueStats(
    val total: Int,
    val last7Days: Int,
    val distinctBrands: Int,
) {
    companion object {
        val Empty = TrainingQueueStats(0, 0, 0)

        fun of(rows: List<ErrorReportEntity>): TrainingQueueStats {
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            return TrainingQueueStats(
                total = rows.size,
                last7Days = rows.count { it.createdAt >= weekAgo },
                distinctBrands = rows
                    .map { it.detectedBrand.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .count(),
            )
        }
    }
}

/**
 * Backs the error escalation form on the Help screen.
 *
 * `save_error_report` writes to the backend's training queue; here the row goes
 * to the local `error_reports` table, which is the on-device equivalent and is
 * what the retraining pipeline would drain on sync.
 */
class HelpViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp

    var queued by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * The read half of the retraining queue - the web's GET /api/training/queue.
     *
     * Only `reportError` existed before, so every correction an officer filed went
     * into a table nothing ever read. `observeErrors()` was already declared in the
     * DAO and wired to no one; this is its first consumer.
     *
     * The web's GET /api/training/queue/stats is
     * `SELECT status, COUNT(*) FROM vision_training GROUP BY status`, and Android's
     * `error_reports` table has no status column. Adding one would mean a Room
     * migration for a field that could only ever hold a single value: there is no
     * server here to drain the queue and advance it. So the stats are the counts
     * that stay meaningful without one, and the list shows everything, unsplit.
     */
    val queue: StateFlow<List<ErrorReportEntity>> =
        app.graph.database.rsmDao().observeErrors()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<TrainingQueueStats> = queue
        .map { rows -> TrainingQueueStats.of(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainingQueueStats.Empty)

    fun submit(detectedBrand: String, correction: String, notes: String) {
        viewModelScope.launch {
            error = null
            queued = false
            if (detectedBrand.isBlank()) {
                error = "Enter the brand that was detected before queueing."
                return@launch
            }
            runCatching {
                app.graph.database.rsmDao().reportError(
                    ErrorReportEntity(
                        detectedBrand = detectedBrand.trim(),
                        correction = correction.trim(),
                        notes = notes.trim(),
                        raw = "",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure {
                error = it.message ?: "Could not queue the report"
                return@launch
            }
            queued = true
        }
    }
}

class HelpViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HelpViewModel(application) as T
}

private val scanSteps = listOf(
    "1. Surface" to "Lay the Rx flat. Avoid folded pads and wrinkled thermal paper.",
    "2. Align" to "All four corners visible. Portrait for Bengali pads, landscape for hospital sheets.",
    "3. Light" to "Even daylight. No flash glare on laminated pads. Shadow-free medicine block.",
    "4. Cursive" to "Move closer to the Rx lines. Use contrast toggle after capture for faint ink.",
    "5. Capture" to "Tap Open Camera (not Choose File) on phones. Hold still 1s. Review confidence badges.",
)

private val referenceItems = listOf(
    "BMDC number format:" to "Prefix letter + 5 digits, e.g. A-12345. Accept hyphenated and un-hyphenated forms.",
    "Verification statuses:" to "Green >85% auto-accept; 70–85% review; <70% manual flag required before save.",
    "Qualifications:" to "MBBS, FCPS, MD, MS, MCPS — match against BMDC register.",
    "DGDA:" to "Consult the DGDA essential medicine price list before quoting MRP to doctors.",
    "PII masking:" to "Doctor name and BMDC number are masked in exported CSVs per BMDC compliance.",
)

/** `HelpScreen` in `App.tsx:2072`. */
@Composable
fun HelpScreen(
    vm: HelpViewModel,
    modifier: Modifier = Modifier,
    onTryScan: () -> Unit = {},
) {
    var detected by remember { mutableStateOf("") }
    var correction by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val queue by vm.queue.collectAsState()
    val stats by vm.stats.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MlxD.SectionGap),
    ) {
        MlxCard {
            SectionHeader(
                title = "Interactive Scan Guide",
                subtitle = "Optimal camera alignment, lighting, and cursive handwriting " +
                    "for maximum vision-AI confidence.",
            )
            scanSteps.forEach { (title, body) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MlxD.Space2),
                ) {
                    Text(
                        text = title,
                        style = MlxType.SectionLabel,
                        color = Mlx.BlueText,
                    )
                    Text(text = body, style = MlxType.BodySmall, color = Mlx.Text600)
                }
            }
            Spacer(Modifier.height(MlxD.Space2))
            MlxButton(
                text = "Try a scan",
                onClick = onTryScan,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MlxCard {
            SectionHeader(
                title = "Error Escalation System",
                subtitle = "Report a misidentified drug or a novel handwritten variation. It is " +
                    "queued for the training set — you can also flag from any medicine card after a scan.",
            )
            Spacer(Modifier.height(MlxD.Space3))
            MlxTextField(
                value = detected,
                onValueChange = { detected = it },
                label = "Detected brand",
                placeholder = "e.g. Azithro",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MlxD.Space3))
            MlxTextField(
                value = correction,
                onValueChange = { correction = it },
                label = "Correct brand / company",
                placeholder = "e.g. Azithromycin (Incepta)",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MlxD.Space3))
            MlxTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "What did the handwriting look like?",
                placeholder = "Describe the cursive letterforms, pen pressure, word breaks...",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MlxD.Space3))
            vm.error?.let { MlxErrorLine(it, Modifier.padding(bottom = MlxD.Space2)) }
            if (vm.queued) {
                Text(
                    text = "Queued for training.",
                    style = MlxType.BodySmall,
                    color = Mlx.Ok600,
                    modifier = Modifier.padding(bottom = MlxD.Space2),
                )
            }
            MlxButton(
                text = "Queue for training",
                onClick = {
                    vm.submit(detected, correction, notes)
                    detected = ""
                    correction = ""
                    notes = ""
                },
                tone = ButtonTone.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MlxCard {
            SectionHeader(
                title = "Training Queue",
                subtitle = "Everything flagged for handwriting retraining, newest first. " +
                    "Nothing drains this on-device - it is the local half of what the " +
                    "backend's queue would collect on sync.",
            )
            Spacer(Modifier.height(MlxD.Space3))
            Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
                MiniKpiTile("Queued", stats.total.toString(), Modifier.weight(1f))
                MiniKpiTile(
                    "Last 7 days",
                    stats.last7Days.toString(),
                    Modifier.weight(1f),
                )
                MiniKpiTile(
                    "Brands",
                    stats.distinctBrands.toString(),
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(MlxD.Space3))
            if (queue.isEmpty()) {
                MlxEmptyState(
                    message = "Nothing queued yet - flag a misread from any medicine " +
                        "card after a scan.",
                )
            } else {
                queue.forEach { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = MlxD.Space2)
                            .background(Mlx.Screen, RoundedCornerShape(8.dp))
                            .padding(MlxD.Space2),
                    ) {
                        Text(
                            text = row.detectedBrand +
                                " \u2192 " + row.correction.ifBlank { "\u2014" },
                            style = MlxType.BodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Mlx.Text900,
                        )
                        if (row.notes.isNotBlank()) {
                            Text(
                                text = row.notes,
                                style = MlxType.Footnote,
                                color = Mlx.Text600,
                            )
                        }
                        Text(
                            text = formatQueueStamp(row.createdAt),
                            style = MlxType.Footnote,
                            color = Mlx.Text400,
                        )
                    }
                }
            }
        }

        MlxCard {
            SectionHeader(title = "BMDC & DGDA Reference Manual")
            referenceItems.forEach { (bold, text) ->
                Row(modifier = Modifier.padding(bottom = MlxD.Space2)) {
                    Text(
                        text = "$bold ",
                        style = MlxType.BodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Mlx.Text700,
                    )
                    Text(text = text, style = MlxType.BodySmall, color = Mlx.Text600)
                }
            }
        }
    }
}

private val queueStampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatQueueStamp(epochMillis: Long): String =
    queueStampFormat.format(Instant.ofEpochMilli(epochMillis))

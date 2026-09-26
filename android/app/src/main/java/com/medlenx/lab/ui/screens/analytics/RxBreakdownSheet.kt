package com.medlenx.lab.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.medlenx.lab.data.model.PitchCompliance
import com.medlenx.lab.data.model.Substitution
import com.medlenx.lab.data.model.needsAuditFollowUp
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.data.repo.RxAuditLine
import com.medlenx.lab.ui.components.ConfidenceBadge
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MedicineThumb
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.MlxTextField
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.screens.rx.MarketShareCard
import com.medlenx.lab.ui.screens.rx.ClinicalStrip
import com.medlenx.lab.ui.screens.rx.RxAuditFilter
import com.medlenx.lab.ui.screens.rx.PillButton
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prescription Audit Summary — the slide-over drawer behind a tapped prescription.
 *
 * A port of the web's `#rxDrawer` (`templates/index.html:552`) and its renderer
 * (`:2764`), fed by the single payload `GET /api/prescriptions/{id}` returns
 * (`app/main.py:928`). Four regions, top to bottom, in the web's order: header, toolbar
 * with the filter pills, clinical strip, item table, then the market-share footer.
 *
 * Two things about this screen are deliberate and worth knowing before changing it:
 *
 *  - **The table scrolls sideways.** The web gives it `min-w-[560px]` inside an
 *    `overflow-x-auto`, so on a phone it is already a horizontally scrolled table there
 *    - four columns will not fit any phone. Reproducing that keeps the columns aligned
 *    under one shared scroll state instead of reflowing each row into a card, which
 *    would be a different screen than the one the web and the Figma export describe.
 *  - **The confidence badge and the row wash use different thresholds.** The badge is
 *    the Figma `ConfBadge` (flips at 85); the `<80%` pill, the orange wash and the
 *    "Verify against Medex" link use [needsAuditFollowUp]. That is the web's own
 *    behaviour, and the reasoning is written down in `ConfidenceBands`.
 *
 * [drawer] is null while the payload is loading, which is the web's spinner-row state;
 * the panel is rendered either way so opening it does not flash.
 */
@Composable
fun RxBreakdownSheet(
    drawer: RxAuditDrawer?,
    loading: Boolean,
    /** Shown in place of the panel body when the payload could not be read. */
    loadError: String?,
    onDismiss: () -> Unit,
    onExportCsv: (String) -> Unit,
    onCopyList: (String) -> Unit,
    onCopyPitch: (String) -> Unit,
    onPitchCard: (Substitution, PitchCompliance) -> Unit,
    onVerifyAgainstMedex: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
        ) {
            // The web pins the panel to the right edge at `max-w-2xl` and full height;
            // on a phone the whole width is the panel, which is what `w-full` already
            // does at that breakpoint.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = MlxD.Space5)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Mlx.Screen)
                    // The dialog goes edge-to-edge (`decorFitsSystemWindows = false`),
                    // so nothing insets this panel for us: without the two calls the
                    // title row sat under the status-bar clock and the footer's Export /
                    // Copy buttons under the navigation bar. Applied after the
                    // clip/background so the surface still paints to the window edges
                    // and only the *content* is inset out of the bars' way.
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                if (drawer == null) {
                    LoadingHeader(loading = loading, loadError = loadError, onDismiss = onDismiss)
                } else {
                    DrawerHeader(drawer = drawer, onDismiss = onDismiss)
                    DrawerBody(
                        drawer = drawer,
                        onExportCsv = onExportCsv,
                        onCopyList = onCopyList,
                        onCopyPitch = onCopyPitch,
                        onPitchCard = onPitchCard,
                        onVerifyAgainstMedex = onVerifyAgainstMedex,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ header ----

/** The panel's own close button, shared by the loading and loaded headers. */
@Composable
private fun DrawerTopBar(
    title: String,
    subtitle: String,
    tag: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Mlx.Surface)
            .padding(horizontal = MlxD.Space4, vertical = MlxD.Space3),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = Mlx.Brand600,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = title,
                    style = MlxType.CardTitle,
                    color = Mlx.Text900,
                    modifier = Modifier.weight(1f, fill = false),
                )
                tag?.invoke()
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MlxType.Meta,
                    color = Mlx.Text500,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        MlxIconButton(Icons.Filled.Close, "Close", onDismiss)
    }
}

@Composable
private fun LoadingHeader(loading: Boolean, loadError: String?, onDismiss: () -> Unit) {
    DrawerTopBar(
        title = "Prescription Audit Summary",
        subtitle = when {
            loading -> "Loading audit breakdown..."
            loadError != null -> loadError
            else -> "Prescription not found."
        },
        tag = null,
        onDismiss = onDismiss,
    )
}

@Composable
private fun DrawerHeader(drawer: RxAuditDrawer, onDismiss: () -> Unit) {
    val p = drawer.prescription
    // `${doctor (specialty)} • N Medicines Detected • MR x • district` — the web builds
    // this list, drops the blanks, and joins with a bullet (`index.html:2806`). The
    // count is of *all* items, not the filtered view.
    val subtitle = buildList {
        add(
            if (p.doctorSpecialty.isBlank()) p.doctorName
            else "${p.doctorName} (${p.doctorSpecialty})"
        )
        add("${drawer.lines.size} Medicines Detected")
        if (p.mrId.isNotBlank()) add("MR ${p.mrId}")
        if (p.district.isNotBlank()) add(p.district)
    }.joinToString(" • ")

    Column(modifier = Modifier.fillMaxWidth().background(Mlx.Surface)) {
        DrawerTopBar(
            title = "Prescription Audit Summary — Rx #${p.rxNo}",
            subtitle = subtitle,
            tag = if (p.duplicateOf != null) {
                {
                    StatusPill(
                        text = "Duplicate Rx Detected",
                        tone = PillTone.Red,
                        icon = Icons.Filled.Warning,
                    )
                }
            } else {
                null
            },
            onDismiss = onDismiss,
        )
    }
}

// -------------------------------------------------------------------- body ----

@Composable
private fun DrawerBody(
    drawer: RxAuditDrawer,
    onExportCsv: (String) -> Unit,
    onCopyList: (String) -> Unit,
    onCopyPitch: (String) -> Unit,
    onPitchCard: (Substitution, PitchCompliance) -> Unit,
    onVerifyAgainstMedex: () -> Unit,
) {
    // Plain `remember`, not `rememberSaveable`: an enum and a Set<Int> are not types
    // Bundle can carry, so the saveable variants would compile and then throw when the
    // state was actually saved. The drawer is transient and the web keeps the same three
    // in module-level variables, so nothing is lost by not surviving a config change.
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RxAuditFilter.All) }
    // The web can have several rows expanded at once: `toggleRxAuditPortfolio` only
    // removes the portfolio row belonging to the row that was tapped. A Set, not an Int.
    var expanded by remember { mutableStateOf(emptySet<Int>()) }

    val own = { i: Int ->
        drawer.ownCompany.isNotBlank() &&
            RxAudit.sameCompanyLoose(drawer.lines[i].company, drawer.ownCompany)
    }
    val low = { i: Int -> needsAuditFollowUp(drawer.lines[i].confidencePercent) }

    // The web's `rxAuditFilteredItems`: the pill selects, then the search text narrows.
    val visible = drawer.lines.indices.filter { i ->
        val passesFilter = when (filter) {
            RxAuditFilter.OwnPharma -> own(i)
            RxAuditFilter.Competitors -> !own(i)
            RxAuditFilter.LowConfidence -> low(i)
            RxAuditFilter.All -> true
        }
        passesFilter && matchesQuery(drawer.lines[i], query)
    }
    val counts = mapOf(
        RxAuditFilter.All to drawer.lines.size,
        RxAuditFilter.OwnPharma to drawer.lines.indices.count(own),
        RxAuditFilter.Competitors to drawer.lines.indices.count { !own(it) },
        RxAuditFilter.LowConfidence to drawer.lines.indices.count(low),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        SearchAndFilters(
            query = query,
            onQueryChange = { query = it },
            filter = filter,
            onFilterChange = { filter = it },
            counts = counts,
        )

        // The scrollable middle: the item table, then the duplicate note. One vertical
        // scroll for the whole region, so the table does not get its own nested one.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MlxD.Space4, vertical = MlxD.Space3),
        ) {
            DuplicateNote(drawer)

            ClinicalStrip(
                antibiotics = drawer.lines.count { it.isAntibiotic },
                broadSpectrum = drawer.lines.count { it.broadSpectrum },
                total = drawer.lines.size,
                offTerritory = drawer.prescription.offTerritory,
                slices = drawer.slices,
                // Nothing in this Rx is an antibiotic: the web hides the badge here.
                hideEmptyAntibiotics = true,
                modifier = Modifier.padding(bottom = MlxD.Space3),
            )

            // No wrapper card: every medicine is a card of its own now, so an outer
            // one would only put a white box around a stack of white boxes.
            ItemCards(
                drawer = drawer,
                visible = visible,
                expanded = expanded,
                onTogglePortfolio = { i ->
                    expanded = if (i in expanded) expanded - i else expanded + i
                },
                onPitchCard = onPitchCard,
                onCopyPitch = onCopyPitch,
                onVerifyAgainstMedex = onVerifyAgainstMedex,
            )
        }

        // Footer: market share + the two export actions. Outside the scroll so it stays
        // reachable, which is where the web's `border-t` footer sits too.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Mlx.Surface)
                .padding(horizontal = MlxD.Space4, vertical = MlxD.Space3),
        ) {
            MarketShareCard(
                ownLabel = drawer.ownCompany.ifBlank { "Your company" },
                ownCount = drawer.marketShare.ownCount,
                competitorCount = drawer.marketShare.competitorCount,
                total = drawer.marketShare.totalMedicines,
                onExportCsv = {
                    onExportCsv(
                        RxAudit.itemsToCsv(drawer.lines, drawer.ownCompany)
                    )
                },
                onCopyClipboard = {
                    onCopyList(
                        RxAudit.itemsToClipboard(
                            drawer.lines,
                            // The web's header carries the doctor, the item count and the
                            // MR as well as the number (`main.py:1086`); built by the one
                            // function both this and the audit screen use, so the two
                            // cannot drift apart again.
                            RxAudit.clipboardHeader(
                                rxNo = drawer.prescription.rxNo,
                                doctorName = drawer.prescription.doctorName,
                                count = drawer.lines.size,
                                mrId = drawer.prescription.mrId,
                            ),
                        )
                    )
                },
                showCropHint = false,
            )
        }
    }
}

/** The web's `rxAuditFilteredItems` search blob: brand, generic, company, strength, type. */
private fun matchesQuery(line: RxAuditLine, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val blob = listOf(line.brand, line.generic, line.company.orEmpty(), line.strength, line.type)
        .joinToString(" ")
        .lowercase()
    return blob.contains(q)
}

// ----------------------------------------------------------------- toolbar ----

@Composable
private fun SearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: RxAuditFilter,
    onFilterChange: (RxAuditFilter) -> Unit,
    counts: Map<RxAuditFilter, Int>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Mlx.Surface)
            .padding(horizontal = MlxD.Space4, vertical = MlxD.Space2),
        verticalArrangement = Arrangement.spacedBy(MlxD.Space2),
    ) {
        // The web wraps its input in a bordered div with the magnifier absolutely
        // positioned inside it. `MlxTextField` already draws that border and takes no
        // leading adornment, so the icon is dropped rather than the shared component.
        MlxTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search scanned items...",
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRowCompat(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
            RxAuditFilter.entries.forEach { f ->
                MlxFilterChip(
                    label = "${f.label} (${counts[f] ?: 0})",
                    selected = f == filter,
                    onClick = { onFilterChange(f) },
                )
            }
        }
    }
}
// ------------------------------------------------------------- item cards ----

/**
 * The item list, as one card per medicine.
 *
 * The web draws this as a four-column `<table>` inside `overflow-x-auto` with a
 * `min-w-[560px]`, which on a phone is a horizontal scroll of a table whose third of a
 * screen shows about one column at a time. The same data as stacked cards: the header
 * line, then the two fields that were columns, then every badge in one wrap, then the
 * pitch actions.
 *
 * Nothing is dropped in the fold - the web's four columns (brand, generic, company,
 * confidence) all survive, and the two that lost their column header say what they are
 * instead. The orange wash on a low-confidence read was `bg-orange-50/70` behind the
 * whole `<tr>`; it is now the card's own background.
 */
@Composable
private fun ItemCards(
    drawer: RxAuditDrawer,
    visible: List<Int>,
    expanded: Set<Int>,
    onTogglePortfolio: (Int) -> Unit,
    onPitchCard: (Substitution, PitchCompliance) -> Unit,
    onCopyPitch: (String) -> Unit,
    onVerifyAgainstMedex: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MlxD.Space2),
    ) {
        if (visible.isEmpty()) {
            // Still inside a card: on the web the empty state sits inside the white
            // `rounded-xl border` container with the table, and it should not become
            // bare text on the sheet just because the rows did.
            MlxCard { EmptyItems() }
            return@Column
        }
        visible.forEach { i ->
            val line = drawer.lines[i]
            ItemCard(
                line = line,
                own = drawer.ownCompany.isNotBlank() &&
                    RxAudit.sameCompanyLoose(line.company, drawer.ownCompany),
                portfolio = drawer.portfolios.getOrNull(i),
                expanded = i in expanded,
                onTogglePortfolio = { onTogglePortfolio(i) },
                onPitchCard = onPitchCard,
                onVerifyAgainstMedex = onVerifyAgainstMedex,
            )
            // The web injects the portfolio detail as a second `<tr>` under its item;
            // here it follows the card it belongs to, in the same violet.
            if (i in expanded) {
                drawer.portfolios.getOrNull(i)?.let { sub ->
                    PortfolioCard(
                        substitution = sub,
                        fallbackBrand = line.brand,
                        ownCompany = drawer.ownCompany,
                        onCopyPitch = onCopyPitch,
                    )
                }
            }
        }
    }
}

/**
 * One medicine, as a card.
 *
 * Line 1 is the brand and strength with the confidence badge at the end - the web's
 * brand and Conf. columns are the two ends of the same row, and on a phone they read as
 * a card header. Line 2 is the web's `type • dosage` footnote.
 *
 * The own-company overlay is unchanged: `bg-orange-50/70` behind the whole row when the
 * read is below 80%, which is a different question from how the confidence badge is
 * coloured - see the note on [RxBreakdownSheet].
 */
@Composable
private fun ItemCard(
    line: RxAuditLine,
    own: Boolean,
    portfolio: Substitution?,
    expanded: Boolean,
    onTogglePortfolio: () -> Unit,
    onPitchCard: (Substitution, PitchCompliance) -> Unit,
    onVerifyAgainstMedex: () -> Unit,
) {
    val followUp = needsAuditFollowUp(line.confidencePercent)

    MlxCard(
        background = if (followUp) Mlx.GuessBg.copy(alpha = 0.70f) else Mlx.Surface,
        borderColor = if (followUp) Mlx.GuessBorder else Mlx.Brand200,
        padding = MlxD.Space3,
    ) {
        // ── Card header: brand + strength, confidence at the end ─────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
        ) {
            MedicineThumb(
                name = line.brand,
                imageUrl = line.imageUrl,
                size = 32.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOf(line.brand, line.strength)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { "Unknown Brand" },
                    style = MlxType.BodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (line.brand.isBlank()) Mlx.Text400 else Mlx.Text900,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOf(line.type, line.dosage)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MlxType.Footnote,
                        color = Mlx.Text400,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ConfidenceBadge(percent = line.confidencePercent)
        }

        // ── Generic composition / Pharmaceutical ─────────────────────────────────
        // Side by side as they were in the table, half the card each; the label above
        // each one replaces the `<th>` that used to name the column.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MlxD.Space2),
            horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel("Generic Composition")
                Text(
                    text = line.generic.ifBlank { "—" },
                    style = MlxType.BodySmall,
                    color = if (line.generic.isBlank()) Mlx.Text400 else Mlx.Text600,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel("Pharmaceutical")
                if (line.company.isNullOrBlank()) {
                    Text(
                        text = "Unknown Brand",
                        style = MlxType.BodySmall.copy(fontWeight = FontWeight.Normal),
                        color = Mlx.Text400,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = line.company,
                            style = MlxType.BodySmall.copy(
                                fontWeight = if (own) FontWeight.Bold else FontWeight.Normal,
                            ),
                            color = if (own) Mlx.VioletText else Mlx.Text700,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (own) {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = "Your company",
                                tint = Mlx.VioletText,
                                modifier = Modifier.size(10.dp).padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Every badge in one wrap ───────────────────────────────────────────────
        // They were split across the brand and generic columns on the web (DGDA, ABX
        // and the class under the brand; NEML and TRIPS under the generic). With no
        // columns left there is nothing to split them by, and they are all answering
        // the same question about the same medicine - so one FlowRow, web order.
        FlowRowCompat(
            modifier = Modifier.padding(top = MlxD.Space2),
            horizontalSpacing = 4.dp,
            verticalSpacing = 4.dp,
        ) {
            if (line.dgdaFlagged) {
                StatusPill(
                    text = "DGDA Price Alert",
                    tone = PillTone.Red,
                    icon = Icons.Filled.Block,
                )
            }
            if (line.isAntibiotic) {
                StatusPill(
                    text = if (line.broadSpectrum) "ABX ★" else "ABX",
                    tone = if (line.broadSpectrum) PillTone.RedSoft else PillTone.Amber,
                    icon = Icons.Filled.Biotech,
                )
            }
            line.therapeuticClass?.takeIf { it.isNotBlank() }?.let { cls ->
                StatusPill(text = cls, tone = PillTone.Slate)
            }
            if (line.nemlListed) {
                StatusPill(
                    text = "NEML Listed",
                    tone = PillTone.Blue,
                    icon = Icons.Filled.Check,
                )
            }
            if (line.tripsWatch) {
                StatusPill(
                    text = "TRIPS Watch",
                    tone = PillTone.Amber,
                    icon = Icons.Filled.Public,
                )
            }
        }

        // ── Own Portfolio Match + Generate Doctor Pitch Card ─────────────────────
        // Both only exist when the item has a portfolio match, which is the web's
        // `m.portfolio_match && m.portfolio_match.own_brand` guard.
        if (portfolio != null) {
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = 4.dp,
                verticalSpacing = 4.dp,
            ) {
                PillButton(
                    text = "Own Portfolio Match: ${portfolio.ownBrand.brandName}" +
                        if (expanded) " ▲" else "",
                    filled = false,
                    icon = Icons.Filled.AutoAwesome,
                    onClick = onTogglePortfolio,
                )
                PillButton(
                    text = "Generate Doctor Pitch Card",
                    filled = true,
                    // The audit screen already ported fa-id-card as Badge; the two
                    // screens show the same button, so they share the glyph.
                    icon = Icons.Filled.Badge,
                    // The card gates its compliance badges on the flags, so the row's
                    // own values travel with the substitution. A saved row does not
                    // store the NEML molecule's class, so the PDF leaves the
                    // parenthetical off rather than borrow the medicine's unrelated
                    // therapeutic class.
                    onClick = {
                        onPitchCard(
                            portfolio,
                            PitchCompliance(
                                nemlListed = line.nemlListed,
                                nemlMolecule = line.nemlMolecule,
                                dgdaFlagged = line.dgdaFlagged,
                                dgdaReason = line.dgdaReason,
                            ),
                        )
                    },
                )
            }
        }

        if (followUp) {
            Row(
                modifier = Modifier
                    .padding(top = MlxD.Space2)
                    .clickable(onClick = onVerifyAgainstMedex),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.HelpOutline,
                    contentDescription = null,
                    tint = Mlx.GuessText,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "Verify against Medex",
                    style = MlxType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = Mlx.GuessText,
                )
            }
        }
    }
}

/** The `<th>` each card field lost: the web's column headings, as a field label. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MlxType.RegulatoryPill.copy(letterSpacing = 0.06.em),
        color = Mlx.Text400,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}
/**
 * The expanded "Own Portfolio Match" block under an item.
 *
 * Competitor chip → own-brand chip with the circular arrow between them, the per-unit
 * price delta, and the pitch script. The web injects this as a second `<tr>` with
 * `colspan=4`; here it is the next card in the same list, full width like the card it
 * belongs to rather than a fixed table width it no longer shares.
 */
@Composable
private fun PortfolioCard(
    substitution: Substitution,
    fallbackBrand: String,
    ownCompany: String,
    onCopyPitch: (String) -> Unit,
) {
    val competitor = substitution.competitor
    val own = substitution.ownBrand

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MlxShape.Medium)
            .background(Mlx.VioletBg.copy(alpha = 0.50f))
            .border(1.dp, Mlx.VioletBorder, MlxShape.Medium)
            .padding(horizontal = MlxD.Space3, vertical = MlxD.Space2),
    ) {
        Text(
            text = "OWN PORTFOLIO MATCH — FOR MPO DOCTOR DETAILING",
            style = MlxType.RegulatoryPill.copy(letterSpacing = 0.08.em),
            color = Mlx.VioletText,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRowCompat(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
            PortfolioChip(
                brand = competitor.brandName.ifBlank { fallbackBrand },
                company = competitor.company,
                strength = competitor.strength,
                type = competitor.type,
            )
            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = Mlx.VioletBorder,
                modifier = Modifier.size(16.dp),
            )
            PortfolioChip(
                brand = own.brandName,
                company = own.company.ifBlank { ownCompany },
                strength = own.strength,
                type = own.type,
                emphasise = true,
            )
            if (substitution.unitDifferenceLabel.isNotBlank()) {
                // The label already carries the direction ("0.45 BDT lower per unit"), and
                // the web's drawer row prints it bare - ` (saving)` / ` (premium)` belong
                // to the verification card's price line (`index.html:1724`), which is a
                // different surface. Appending them here made this row say it twice and
                // was one of the three places the port had drifted from the payload.
                StatusPill(
                    text = substitution.unitDifferenceLabel,
                    tone = if (substitution.unitDifference < 0) PillTone.Emerald else PillTone.Amber,
                )
            }
        }
        if (substitution.pitch.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MlxD.Space2)
                    .background(Mlx.Surface, MlxShape.Small)
                    .border(1.dp, Mlx.Brand200, MlxShape.Small)
                    .padding(MlxD.Space2),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Campaign,
                    contentDescription = null,
                    tint = Mlx.Warn500,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = substitution.pitch,
                    style = MlxType.BodySmall.copy(fontWeight = FontWeight.Normal),
                    color = Mlx.Text600,
                    modifier = Modifier.weight(1f),
                )
                // The web's portfolio row is read-only - it prints the script and leaves
                // copying to the pitch card. The copy affordance is added here because
                // this drawer is reachable without opening that card, and a rep who has
                // just read the pitch should be able to take it away.
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy pitch",
                    tint = Mlx.Brand600,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onCopyPitch(substitution.pitch) },
                )
            }
        }
    }
}

@Composable
private fun PortfolioChip(
    brand: String,
    company: String,
    strength: String,
    type: String,
    emphasise: Boolean = false,
) {
    Row(
        modifier = Modifier
            .background(Mlx.Surface, MlxShape.Chip)
            .border(
                1.dp,
                if (emphasise) Mlx.VioletBorder else Mlx.Brand200,
                MlxShape.Chip,
            )
            .padding(horizontal = MlxD.Space2, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = brand,
            style = MlxType.BodySmall.copy(fontWeight = FontWeight.Bold),
            color = if (emphasise) Mlx.VioletText else Mlx.Text900,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (company.isNotBlank()) {
            Text(
                text = "(${company})",
                style = MlxType.RegulatoryPill,
                color = Mlx.Text400,
            )
        }
        val spec = listOf(strength, type).filter { it.isNotBlank() }.joinToString(" ")
        if (spec.isNotBlank()) {
            Text(text = spec, style = MlxType.RegulatoryPill, color = Mlx.Text500)
        }
    }
}

// --------------------------------------------------------- duplicate + empty ----

/**
 * The red fraud note above the table, when this prescription is a repeat scan.
 *
 * The web resolves `duplicate_of` to the original prescription and names it, its MR and
 * when it was first captured (`index.html:2813`). Rendered as a plain banner rather
 * than inside the table, where it is on the web.
 */
@Composable
private fun DuplicateNote(drawer: RxAuditDrawer) {
    val original = drawer.duplicateOf ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MlxD.Space3)
            .background(Mlx.DangerBg, MlxShape.Small)
            .border(1.dp, Mlx.DangerBorder, MlxShape.Small)
            .padding(MlxD.Space2),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Mlx.DangerText,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = buildString {
                append("Fraud alert: this physical prescription appears to have been ")
                append("scanned before — first captured as Rx #${original.rxNo} ")
                append("(${original.mrId.ifBlank { "unknown MR" }}")
                if (original.createdAt > 0L) {
                    append(", ${scannedStamp(original.createdAt)}")
                }
                append("). Excluded from target credit pending RSM review.")
            },
            style = MlxType.Footnote,
            color = Mlx.DangerText,
        )
    }
}

@Composable
private fun EmptyItems() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MlxD.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Mlx.Brand100, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = Mlx.Text400,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "No medicines match this search/filter.",
            style = MlxType.BodySmall,
            color = Mlx.Text500,
            modifier = Modifier.padding(top = MlxD.Space2),
        )
    }
}

/**
 * `yyyy-MM-dd HH:mm` for the duplicate note's "first captured" stamp.
 *
 * `ExportDocuments` has the same pattern in private, for its own artefacts. Kept local
 * rather than promoted: those two are free to diverge - the export also needs an ISO
 * variant and a filename stamp - and a shared date helper for one label is not worth
 * coupling a screen to the export module's formatting.
 */
private fun scannedStamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

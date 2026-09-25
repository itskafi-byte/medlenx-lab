package com.medlenx.lab.data.export

import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.RecentMedicineRow
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.Substitution
import com.medlenx.lab.data.repo.DoctorTiering
import com.medlenx.lab.data.repo.PyCsv
import com.medlenx.lab.data.repo.StewardshipSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The documents the export buttons produce, ported from the three reportlab
 * builders in `app/main.py`.
 *
 * Each mirrors its web counterpart's *content* — the same fields in the same
 * order, with the same wording where the wording is the point — and deliberately
 * not its chrome. See [PdfWriter] for why fidelity was traded away.
 *
 * Everything here is pure: given the same inputs it returns the same bytes, so
 * the only thing that can be wrong is the layout, and the layout is expressed in
 * calls that a reader can check without running them.
 */
object ExportDocuments {

    /** `main.py:1092` — one-page MPO Doctor Pitch Card. */
    fun pitchCardPdf(
        rxId: String,
        doctorName: String,
        doctorSpecialty: String,
        substitution: Substitution,
        bioequivalenceNote: String,
        generatedAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        val competitor = substitution.competitor
        val own = substitution.ownBrand

        val w = PdfWriter()
        w.footerText = "MedLenX Lab · Doctor Pitch Card · Rx $rxId"

        w.title("Doctor Pitch Card", PdfWriter.VIOLET)
        w.subtitle(
            buildString {
                append("Rx #$rxId")
                append(" · ")
                append(doctorName.ifBlank { "Doctor" })
                if (doctorSpecialty.isNotBlank()) append(" · $doctorSpecialty")
                append(" · prepared by MedLenX Lab")
            },
        )

        // The compare grid. Row 3 is the generic, which the two products share, so
        // it spans both columns — the web does this with TableStyle SPAN and here
        // by repeating the value into the second cell.
        w.table(
            header = listOf("Comparison", "Competitor", "Your Brand"),
            rows = listOf(
                listOf("Brand", competitor.brandName.orDash(), own.brandName.orDash()),
                listOf("Company", competitor.company.orDash(), own.company.orDash()),
                listOf("Generic", substitution.generic.orDash(), substitution.generic.orDash()),
                listOf(
                    "Strength / Form",
                    joined(competitor.strength, competitor.type),
                    joined(own.strength, own.type),
                ),
                listOf("MRP (pack)", competitor.mrpLabel(), own.mrpLabel()),
            ),
            weights = listOf(1.15f, 2.5f, 2.5f),
        )

        // The web's version of this section lists the item's NEML and DGDA
        // findings, which come from the audit item it is built server-side. A
        // [Substitution] carries no regulatory fields, so rather than restate
        // standing the card already shows as pills — or assert a compliance fact
        // this call site cannot check — the section carries only the price
        // position, which is derived from the two catalogue rows.
        w.heading("Compliance & evidence")
        w.bullet(
            "Price position: " + substitution.unitDifferenceLabel.ifBlank {
                formatPercent(substitution.unitDifference)
            } + " lower per unit than " + competitor.brandName.orDash(),
        )

        val notes = bioequivalenceNote.split("\n").filter { it.isNotBlank() }
        w.heading("Bioequivalence & dosage")
        if (notes.isEmpty()) {
            w.bullet("No bioequivalence note recorded for this substitution.")
        } else {
            notes.forEach { w.bullet(it) }
        }

        w.heading("Smart pitch script")
        w.body(substitution.pitch.ifBlank { "—" }, PdfWriter.Style(10.5f, PdfWriter.INK, leading = 15f))

        w.gap(14f)
        w.subtitle(
            "Generated from the MedEx-audited catalogue · verify sample stock " +
                "availability before the visit · ${stamp(generatedAt)}",
        )
        return w.finish()
    }

    /**
     * `main.py:1523` — the DGDA / Compliance Audit Report.
     *
     * The web's version is a nine-column member table across a 50+ MPO team.
     * A standalone build only sees this device's own audits, so the same table is
     * rendered over the *doctor* tiering instead of the MPO roster: identical
     * columns and arithmetic, one row per doctor rather than per officer. The
     * header says "Doctors" rather than "Team size" for the same reason.
     */
    fun rsmReportPdf(
        officer: OfficerProfileEntity?,
        tiering: DoctorTiering,
        stewardship: StewardshipSummary,
        offTerritoryCount: Int,
        days: Int,
        generatedAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        val prescriptions = tiering.doctors.sumOf { it.rx }
        val items = tiering.doctors.sumOf { it.items }
        val ownItems = tiering.doctors.sumOf { it.ownItems }
        val competitorItems = tiering.doctors.sumOf { it.competitorItems }
        val sov = if (items > 0) ownItems * 100.0 / items else 0.0

        val w = PdfWriter(
            pageWidth = PdfWriter.A4_LANDSCAPE_WIDTH,
            pageHeight = PdfWriter.A4_LANDSCAPE_HEIGHT,
        )
        w.footerText = "MedLenX Lab · DGDA / Compliance Audit Report"

        w.title("MedLenX Lab — DGDA / Compliance Audit Report")
        w.subtitle(
            "Prepared for ${officer?.fullName.orEmpty().ifBlank { "Regional Sales Manager" }} " +
                "(${officer?.employeeId.orEmpty().ifBlank { "RSM" }}) · " +
                "${officer?.company.orEmpty().ifBlank { tiering.ownCompany }} · " +
                "Reporting window: last ${tiering.days.coerceAtLeast(days)} days",
        )

        // KPI block — the web's six-cell strip, in its order.
        w.table(
            header = listOf(
                "Doctors", "Prescriptions", "Items", "Own items", "Competitor", "Own SoV",
            ),
            rows = listOf(
                listOf(
                    tiering.doctors.size.toString(),
                    prescriptions.toString(),
                    items.toString(),
                    ownItems.toString(),
                    competitorItems.toString(),
                    "${"%.0f".format(sov)}%",
                ),
            ),
            weights = List(6) { 1f },
            banded = true,
        )

        w.heading("Territory / doctor compliance table")
        val header = listOf(
            "Doctor", "Specialty", "Territory", "Rx", "Items", "Own",
            "Competitor", "SoV %", "Tier",
        )
        val rows = tiering.doctors.take(60).map { d ->
            listOf(
                d.doctorName.ifBlank { "Unknown" },
                d.specialty.ifBlank { "General" },
                d.territory.ifBlank { "—" },
                d.rx.toString(),
                d.items.toString(),
                d.ownItems.toString(),
                d.competitorItems.toString(),
                "${"%.0f".format(d.sov)}%",
                // The web's ninth column is WoW growth, which the RSM trends series
                // supplies. Android's per-doctor series has no such column, so the
                // tier — which the screen already computes — takes the slot rather
                // than a blank that would read as missing data.
                d.tier.ifBlank { "—" },
            )
        }
        if (rows.isEmpty()) {
            w.body("No prescriptions captured in this window.", PdfWriter.Style(9f, PdfWriter.MUTED))
        } else {
            w.table(
                header = header,
                rows = rows,
                weights = listOf(1.7f, 1.1f, 1.1f, 0.5f, 0.55f, 0.5f, 0.9f, 0.6f, 0.7f),
                headerBackground = PdfWriter.DARK_HEADER_BG,
                fontSize = 7.5f,
            )
        }

        // Android-only section: the stewardship monitor is a screen the web has no
        // equivalent for, and an antibiotic-share figure is exactly what a DGDA
        // compliance review asks for.
        val totals = stewardship.totals
        if (totals.items > 0) {
            w.heading("Antimicrobial stewardship")
            w.table(
                header = listOf(
                    "Doctors audited", "Prescribed antibiotics", "Items",
                    "Antibiotic items", "Broad spectrum", "Antibiotic share",
                ),
                rows = listOf(
                    listOf(
                        totals.doctorsAudited.toString(),
                        totals.doctorsWithAbx.toString(),
                        totals.items.toString(),
                        totals.abxItems.toString(),
                        totals.broadItems.toString(),
                        "${"%.1f".format(totals.abxSharePct)}%",
                    ),
                ),
                weights = List(6) { 1f },
                banded = true,
            )
        }

        if (offTerritoryCount > 0) {
            w.bullet(
                "$offTerritoryCount prescription(s) were captured outside the assigned " +
                    "territory and are excluded from the territory figures above.",
                PdfWriter.Style(8.5f, PdfWriter.INK, leading = 12f),
            )
        }

        w.gap(6f)
        w.body(
            "Compliance note: every medicine line in this report is matched against the " +
                "MedEx 25K index. Any item whose company could not be verified is flagged for " +
                "manual confirmation before being counted toward a brand target. Prescription " +
                "images are retained for DGDA audit review per BMDC data-handling policy.",
            PdfWriter.Style(7.5f, PdfWriter.FAINT, leading = 10.5f),
        )
        w.body(
            "Generated ${stamp(generatedAt)} · MedLenX Lab · " +
                "Pure Vision + MedEx catalogue enrichment",
            PdfWriter.Style(7.5f, PdfWriter.FAINT, leading = 10.5f),
        )
        return w.finish()
    }

    /**
     * `main.py:853`'s column list, in its order.
     *
     * `created_at` is a millisecond epoch on the device and an ISO string on the
     * web. It is written as ISO here so the exported file is identical in shape to
     * the one the web produces — a spreadsheet column that mixes the two would
     * sort wrongly.
     */
    fun recentMedicinesCsv(rows: List<RecentMedicineRow>): String = buildString {
        // PyCsv.row defaults to CRLF, which is what `csv.DictWriter` writes when
        // main.py:858 leaves the dialect alone.
        append(PyCsv.row(CSV_COLUMNS))
        for (r in rows) {
            append(
                PyCsv.row(
                    listOf(
                        isoStamp(r.createdAt),
                        r.mrId,
                        r.doctorName,
                        r.doctorSpecialty,
                        r.brandName,
                        r.generic,
                        r.companyName.orEmpty(),
                        r.dosageForm,
                        r.strength,
                        r.dosage,
                        formatConfidence(r.confidenceScore),
                        if (r.companyVerified) "1" else "0",
                        r.district,
                        r.upazila,
                        r.territory,
                        r.prescriptionId.toString(),
                    ),
                ),
            )
        }
    }

    /** `main.py:853`. Exposed so a check can assert the header without building rows. */
    val CSV_COLUMNS = listOf(
        "created_at", "mr_id", "doctor_name", "specialty", "brand_name",
        "generic_name", "company_name", "dosage_form", "strength", "dosage",
        "confidence_score", "company_verified", "district", "upazila",
        "territory", "prescription_id",
    )

    // ---------------------------------------------------------- internals ----

    /**
     * The web writes every export through Python's `csv` module, so the rule lives
     * in [PyCsv] and is shared with
     * [com.medlenx.lab.data.repo.RxAudit.itemsToCsv] rather than restated here.
     */
    internal fun csvEscape(field: String): String = PyCsv.escape(field)

    /**
     * `round(conf * 100) if conf <= 1 else round(conf)` — the same normalisation
     * [com.medlenx.lab.data.repo.RxAudit.itemsToCsv] applies, so a confidence reads
     * identically in both exports.
     */
    private fun formatConfidence(conf: Double): String {
        val pct = if (conf <= 1.0) conf * 100.0 else conf
        return Math.round(pct).toString()
    }

    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

    private fun joined(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(" ").ifBlank { "—" }

    private fun MedexProduct.mrpLabel(): String {
        val mrp = mrp ?: return "—"
        val price = if (mrp % 1.0 == 0.0) mrp.toInt().toString() else mrp.toString()
        return if (pack.isBlank()) "$price BDT" else "$price BDT ($pack)"
    }

    private fun formatPercent(difference: Double): String {
        val pct = difference * 100.0
        val rounded = Math.rint(pct * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}%" else "$rounded%"
    }

    private fun stamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

    private fun isoStamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
}

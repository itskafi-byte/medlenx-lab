package com.medlenx.lab.ui.screens.analytics

import com.medlenx.lab.data.local.LiveScanFeedRow
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.repo.CompanySlice
import com.medlenx.lab.data.repo.DashboardKpis
import com.medlenx.lab.data.repo.DoctorLeader
import com.medlenx.lab.data.repo.MostPrescribed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Maps the ported aggregates onto the view models the Analytics screen already
 * renders, so the layout from the Figma export is preserved while the numbers
 * become real.
 */

private fun pct(delta: Double): String =
    "${if (delta >= 0) "" else "-"}${"%.1f".format(abs(delta))}%"

fun DashboardKpis.toKpiData(): List<KpiDatum> {
    val rx = totalPrescriptions
    return listOf(
        KpiDatum(
            label = "Total Captured",
            value = rx.window.toString(),
            delta = pct(rx.deltaPercent),
            deltaUp = rx.deltaPercent >= 0,
            today = rx.today.toString(),
            week = rx.week.toString(),
            month = rx.month.toString(),
        ),
        KpiDatum(
            label = "Identified Medicines",
            value = identifiedItems.toString(),
            delta = null,
            deltaUp = true,
        ),
        KpiDatum(
            label = "Target Share",
            value = "${"%.1f".format(marketShare.percentage)}%",
            // A difference of two percentages, not a percent change.
            delta = "${if (marketShare.deltaPercent >= 0) "+" else ""}" +
                "${"%.1f".format(marketShare.deltaPercent)} pts",
            deltaUp = marketShare.deltaPercent >= 0,
            track = marketShare.percentage.toInt().coerceIn(0, 100),
        ),
        KpiDatum(
            label = "Active Doctor Coverage",
            value = doctorCoverage.active.toString(),
            delta = "of ${doctorCoverage.total}",
            deltaUp = true,
        ),
    )
}

/** Top brand is reported by the KPI strip's footnote, not as a fifth tile. */
fun DashboardKpis.topBrandLabel(): String =
    if (topBrand.count == 0) "No brand captured yet"
    else "${topBrand.brand} · ${topBrand.count} captures"

fun List<MostPrescribed>.toBarData(): List<BarDatum> =
    map { BarDatum(it.brandName, it.captureCount) }

fun List<CompanySlice>.toDonutData(): List<DonutDatum> =
    map { DonutDatum(it.company, it.percentage.toInt(), isOthers = it.isOthers) }

fun List<CompanySlice>.centreSoVLabel(): String {
    val top = firstOrNull { !it.isOthers } ?: return "SoV —"
    return "SoV ${"%.0f".format(top.percentage)}%"
}

fun List<DoctorLeader>.toLeaderRows(offset: Int): List<DoctorLeaderRow> =
    mapIndexed { i, d ->
        DoctorLeaderRow(
            rank = offset + i + 1,
            name = d.doctorName,
            meta = listOf(d.specialty, d.chamber, d.district)
                .filter { it.isNotBlank() && it != "N/A" }
                .joinToString(" • "),
            own = d.volumeOwn,
            competitor = d.volumeCompetitor,
            conversion = d.conversionRate.toInt(),
            rxCount = d.prescriptions,
            shareOfVoice = d.conversionRate.toInt(),
        )
    }

/** "2m ago" style relative stamps, matching the export's feed column. */
fun relativeAgo(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = ((now - epochMillis) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

fun List<LiveScanFeedRow>.toLiveScanRows(): List<LiveScanRow> = map { r ->
    LiveScanRow(
        time = relativeAgo(r.createdAt),
        doctor = r.doctorName.ifBlank { "Unknown doctor" },
        specialty = r.doctorSpecialty.ifBlank { "General" },
        brand = r.brandName.ifBlank { "Unidentified" },
        company = r.companyName.orEmpty().ifBlank { "Unmatched" },
        verified = r.companyVerified,
        confidence = r.confidenceScore.toInt(),
        location = listOf(r.upazila, r.district).filter { it.isNotBlank() }
            .joinToString(", ").ifBlank { "—" },
    )
}

private val rxStamp: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun List<PrescriptionEntity>.toRecentRxRows(): List<RecentRxRow> = map { p ->
    RecentRxRow(
        id = p.id,
        doctor = p.doctorName.ifBlank { "Unknown doctor" },
        bmdc = p.doctorBmdcNo.ifBlank { "—" },
        meds = p.totalMedicines,
        area = listOf(p.upazila, p.district).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { "—" },
        mr = p.mrId.ifBlank { "—" },
        duplicate = p.duplicateOf != null,
    )
}

/** Exposed so the Recent Prescriptions card can show a real capture stamp. */
fun formatRxStamp(epochMillis: Long): String =
    rxStamp.format(Instant.ofEpochMilli(epochMillis))

package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.DoctorPrescriptionCompanyCount
import java.util.Locale
import kotlin.math.roundToInt

/** Aggregates medicine counts by prescription first, not by total medicine volume. */
object DoctorIntelligence {
    fun enrich(leaders: List<DoctorLeader>, rows: List<DoctorPrescriptionCompanyCount>, ownCompany: String): List<DoctorLeader> {
        val byDoctor = rows.groupBy { it.doctorId }
        return leaders.map { leader ->
            val doctorRows = byDoctor[leader.doctorId].orEmpty()
            val prescriptions = doctorRows.groupBy { it.prescriptionId }.values
                .filter { medicines -> medicines.sumOf { it.medicineCount } > 0 }
            val company = ownCompany.trim()
            val competitors = doctorRows.filter {
                val name = it.company.trim()
                it.medicineCount > 0 && name.isNotEmpty() && name.lowercase(Locale.ROOT) !in setOf("unknown", "n/a", "none", "live search failed") &&
                    !name.equals(company, ignoreCase = true)
            }.groupBy { it.company.trim().lowercase(Locale.ROOT) }
            val top = if (company.isBlank()) null else competitors.values
                .sortedWith(compareByDescending<List<DoctorPrescriptionCompanyCount>> { group -> group.sumOf { it.medicineCount } }
                    .thenBy { it.first().company.trim().lowercase(Locale.ROOT) })
                .firstOrNull()?.first()?.company?.trim()
            val totals = prescriptions.map { it.sumOf { row -> row.medicineCount } }
            val ownCounts = prescriptions.map { rx -> rx.filter { it.company.trim().equals(company, ignoreCase = true) }.sumOf { it.medicineCount } }
            val competitorCounts = prescriptions.map { rx -> rx.filter { top != null && it.company.trim().equals(top, ignoreCase = true) }.sumOf { it.medicineCount } }
            val ownShare = if (company.isBlank() || totals.isEmpty()) null else
                totals.indices.map { ownCounts[it].toDouble() / totals[it] }.average().toFloat().coerceIn(0f, 1f)
            val competitorShare = if (ownShare == null) null else
                totals.indices.map { competitorCounts[it].toDouble() / totals[it] }.average().toFloat().coerceIn(0f, 1f - ownShare)
            leader.copy(
                avgSharePerRx = ownShare?.let { it * 100.0 },
                topCompetitor = top,
                avgMedicinesPerRx = if (totals.isEmpty()) 0 else totals.average().roundToInt(),
                ourAvgMedicines = if (company.isBlank() || ownCounts.isEmpty()) 0 else ownCounts.average().roundToInt(),
                ourSharePct = ownShare,
                topCompetitorSharePct = competitorShare,
                otherSharePct = if (ownShare == null || competitorShare == null) null else (1f - ownShare - competitorShare).coerceAtLeast(0f),
            )
        }
    }
}

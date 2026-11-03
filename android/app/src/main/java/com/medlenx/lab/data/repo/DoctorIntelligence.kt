package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.DoctorPrescriptionCompanyCount
import java.util.Locale

/** Aggregates medicine counts by prescription first, not by total medicine volume. */
object DoctorIntelligence {
    fun enrich(leaders: List<DoctorLeader>, rows: List<DoctorPrescriptionCompanyCount>, ownCompany: String): List<DoctorLeader> {
        val byDoctor = rows.groupBy { it.doctorId }
        return leaders.map { leader ->
            val doctorRows = byDoctor[leader.doctorId].orEmpty()
            val perRx = doctorRows.groupBy { it.prescriptionId }.values.mapNotNull { medicines ->
                val total = medicines.sumOf { it.medicineCount }
                if (total == 0 || ownCompany.isBlank()) null else {
                    val own = medicines.filter { it.company.trim().equals(ownCompany.trim(), ignoreCase = true) }.sumOf { it.medicineCount }
                    own.toDouble() / total
                }
            }
            val competitors = doctorRows.filter {
                val name = it.company.trim()
                it.medicineCount > 0 && name.isNotEmpty() && name.lowercase(Locale.ROOT) !in setOf("unknown", "n/a", "none", "live search failed") &&
                    !name.equals(ownCompany.trim(), ignoreCase = true)
            }.groupBy { it.company.trim().lowercase(Locale.ROOT) }
            val top = if (ownCompany.isBlank()) null else competitors.values
                .sortedWith(compareByDescending<List<DoctorPrescriptionCompanyCount>> { group -> group.sumOf { it.medicineCount } }
                    .thenBy { it.first().company.trim().lowercase(Locale.ROOT) })
                .firstOrNull()?.first()?.company?.trim()
            leader.copy(avgSharePerRx = if (perRx.isEmpty()) null else perRx.average() * 100.0, topCompetitor = top)
        }
    }
}

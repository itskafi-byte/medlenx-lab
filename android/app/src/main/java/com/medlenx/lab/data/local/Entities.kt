package com.medlenx.lab.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MedEx catalogue row, imported once from the bundled data/medex_full.json
 * (a top-level array of 25,105 DGDA-registered SKUs).
 *
 * Indexed on brand and company because the matcher and the Share-of-Voice rollups
 * both look up by those keys on every scan.
 */
@Entity(
    tableName = "medex_products",
    indices = [
        Index("brand_name"),
        Index("company"),
        Index("generic"),
        Index("type"),
    ],
)
data class MedexEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "brand_name") val brandName: String,
    val generic: String,
    val strength: String,
    val form: String,
    val type: String,
    val company: String,
    val ingredient: String,
    val category: String,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "pack_image") val packImage: String?,
    val url: String?,
)

/** One verified prescription scan. */
@Entity(
    tableName = "prescriptions",
    indices = [Index("created_at"), Index("mr_id"), Index("doctor_name")],
)
data class PrescriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "rx_no") val rxNo: String,
    @ColumnInfo(name = "image_path") val imagePath: String,
    @ColumnInfo(name = "image_hash") val imageHash: String?,
    @ColumnInfo(name = "doctor_name") val doctorName: String,
    @ColumnInfo(name = "doctor_bmdc_no") val doctorBmdcNo: String,
    @ColumnInfo(name = "doctor_specialty") val doctorSpecialty: String,
    val chamber: String,
    val district: String,
    val upazila: String,
    val territory: String,
    @ColumnInfo(name = "prescription_source") val prescriptionSource: String,
    @ColumnInfo(name = "total_medicines") val totalMedicines: Int,
    @ColumnInfo(name = "mr_id") val mrId: String,
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long?,
    @ColumnInfo(name = "off_territory") val offTerritory: Boolean,
    @ColumnInfo(name = "territory_note") val territoryNote: String?,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Itemised medicine row — powers the Live Recent Scans feed and Rx audit drawer. */
@Entity(
    tableName = "scanned_medicines",
    indices = [Index("prescription_id"), Index("brand_name"), Index("company_name")],
)
data class ScannedMedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "prescription_id") val prescriptionId: Long,
    @ColumnInfo(name = "line_number") val lineNumber: Int,
    @ColumnInfo(name = "brand_name") val brandName: String,
    val generic: String,
    val strength: String,
    @ColumnInfo(name = "dosage_form") val dosageForm: String,
    val dosage: String,
    val raw: String,
    @ColumnInfo(name = "company_name") val companyName: String?,
    @ColumnInfo(name = "company_verified") val companyVerified: Boolean,
    @ColumnInfo(name = "is_own") val isOwn: Boolean,
    @ColumnInfo(name = "confidence_score") val confidenceScore: Double,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "match_type") val matchType: String,
    @ColumnInfo(name = "is_antibiotic") val isAntibiotic: Boolean,
    @ColumnInfo(name = "broad_spectrum") val broadSpectrum: Boolean,
    @ColumnInfo(name = "therapeutic_class") val therapeuticClass: String?,
    @ColumnInfo(name = "neml_listed") val nemlListed: Boolean,
    @ColumnInfo(name = "dgda_flagged") val dgdaFlagged: Boolean,
    @ColumnInfo(name = "trips_watch") val tripsWatch: Boolean,
)

/**
 * Offline-first queue. Scans captured while the rural network is down are stored
 * here and replayed when connectivity returns — the native equivalent of the web
 * app's IndexedDB queue and `POST /api/offline/sync`.
 */
@Entity(tableName = "queued_scans")
data class QueuedScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "image_path") val imagePath: String,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attempts: Int = 0,
)

/** Officer card — company, identity, territory, portfolio. */
@Entity(tableName = "officer_profile")
data class OfficerProfileEntity(
    @PrimaryKey val id: Int = 1,
    val company: String,
    @ColumnInfo(name = "employee_id") val employeeId: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    val role: String,
    val division: String,
    val territory: String,
    val portfolio: String,
)

/** Monthly brand target; vision-model hits on these brands become KPI progress. */
@Entity(tableName = "brand_targets")
data class BrandTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String,
    val target: Int,
)

/** RSM-attached doctor detailing target. Visits auto-log from matching scans. */
@Entity(tableName = "doctor_targets")
data class DoctorTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "mpo_id") val mpoId: String,
    @ColumnInfo(name = "mpo_name") val mpoName: String,
    @ColumnInfo(name = "doctor_name") val doctorName: String,
    val specialty: String,
    @ColumnInfo(name = "monthly_target") val monthlyTarget: Int,
)

/** Auto-logged visit, deduped per prescription so double-scans never count twice. */
@Entity(
    tableName = "doctor_visits",
    indices = [Index(value = ["target_id", "prescription_id"], unique = true)],
)
data class DoctorVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "target_id") val targetId: Long,
    @ColumnInfo(name = "prescription_id") val prescriptionId: Long,
    @ColumnInfo(name = "doctor_name") val doctorName: String,
    @ColumnInfo(name = "mr_id") val mrId: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long,
)

/** Misidentification reports queued for the handwriting retraining pipeline. */
@Entity(tableName = "error_reports")
data class ErrorReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "detected_brand") val detectedBrand: String,
    val correction: String,
    val notes: String,
    val raw: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Projection for [PrescriptionDao.scannedSince].
 *
 * Not an `@Entity`: it exists only to carry the five columns the TRIPS
 * aggregate reads across the scanned-medicine / prescription join.
 */
data class ScannedItemRow(
    val generic: String,
    val brandName: String,
    val territory: String,
    val district: String,
    val createdAt: Long,
)

/**
 * Projection for [PrescriptionDao.doctorTierRows].
 *
 * The aggregation itself is the GROUP BY; tier classification, SoV and the
 * at-risk flag are computed in [com.medlenx.lab.data.repo.TeamMetrics] so they
 * stay testable without a database.
 */
data class DoctorTierRow(
    val doctorName: String,
    val specialty: String,
    val district: String,
    val territory: String,
    val rx: Int,
    val items: Int,
    val ownItems: Int,
)

/** Projection for [PrescriptionDao.stewardshipRows]: one row per scanned item. */
data class StewardshipRow(
    val prescriptionId: Long,
    val doctorName: String,
    val specialty: String,
    val district: String,
    val generic: String,
    val brandName: String,
)

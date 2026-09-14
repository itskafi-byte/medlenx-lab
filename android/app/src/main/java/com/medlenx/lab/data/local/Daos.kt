package com.medlenx.lab.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MedexDao {
    @Query("SELECT COUNT(*) FROM medex_products")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MedexEntity>)

    @Query("SELECT * FROM medex_products WHERE brand_name = :brand COLLATE NOCASE")
    suspend fun byBrandExact(brand: String): List<MedexEntity>

    @Query(
        "SELECT * FROM medex_products WHERE brand_name LIKE '%' || :q || '%' " +
            "OR generic LIKE '%' || :q || '%' OR company LIKE '%' || :q || '%' " +
            "OR type LIKE '%' || :q || '%' LIMIT :limit",
    )
    suspend fun search(q: String, limit: Int = 24): List<MedexEntity>

    @Query("SELECT * FROM medex_products WHERE company = :company LIMIT :limit")
    suspend fun byCompany(company: String, limit: Int = 24): List<MedexEntity>

    /**
     * The whole catalogue, for building the in-memory matcher index.
     *
     * 25k rows is a few MB and is read once per process; MedicineMatcher needs every
     * brand key to do fuzzy lookup, so there is no narrower query that serves it.
     */
    @Query("SELECT * FROM medex_products")
    suspend fun all(): List<MedexEntity>

    @Query("SELECT DISTINCT company FROM medex_products WHERE company != '' ORDER BY company")
    suspend fun companies(): List<String>

    @Query("SELECT DISTINCT type FROM medex_products WHERE type != '' ORDER BY type")
    suspend fun forms(): List<String>
}

@Dao
interface PrescriptionDao {
    @Insert
    suspend fun insert(p: PrescriptionEntity): Long

    @Upsert
    suspend fun upsert(p: PrescriptionEntity)

    @Query("SELECT * FROM prescriptions ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<PrescriptionEntity>>

    @Query("SELECT * FROM prescriptions WHERE id = :id")
    suspend fun byId(id: Long): PrescriptionEntity?

    @Query("SELECT * FROM prescriptions WHERE image_hash = :hash AND id != :excludeId LIMIT 1")
    suspend fun findByHash(hash: String, excludeId: Long = -1): PrescriptionEntity?

    /**
     * Scanned items joined to their prescription, for the TRIPS portfolio
     * aggregate - the on-device equivalent of the backend's
     * `prescribed_medicines JOIN prescriptions`.
     *
     * Returns a projection rather than entities because the aggregate only needs
     * five columns and the join spans two tables.
     */
    @Query(
        """
        SELECT sm.generic AS generic,
               sm.brand_name AS brandName,
               p.territory AS territory,
               p.district AS district,
               p.created_at AS createdAt
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE p.created_at >= :since
        """
    )
    suspend fun scannedSince(since: Long): List<ScannedItemRow>

    /**
     * Per-doctor prescribing volumes for the tiering matrix.
     *
     * Grouped by doctor *name* rather than id: the Android prescription row
     * carries the name and BMDC number but no doctor primary key, unlike the
     * backend's `prescriptions.doctor_id`.
     *
     * Own-brand items are matched on the first token of the officer's company,
     * exactly as `get_doctor_tiers` does with `company_name LIKE '%token%'`.
     */
    @Query(
        """
        SELECT p.doctor_name AS doctorName,
               IFNULL(p.doctor_specialty, '') AS specialty,
               IFNULL(p.district, '') AS district,
               IFNULL(p.territory, '') AS territory,
               COUNT(DISTINCT p.id) AS rx,
               COUNT(sm.id) AS items,
               IFNULL(SUM(CASE WHEN sm.company_name LIKE :ownLike THEN 1 ELSE 0 END), 0) AS ownItems
        FROM prescriptions p
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''
        GROUP BY p.doctor_name
        HAVING rx >= :minRx
        ORDER BY rx DESC
        LIMIT :limit
        """
    )
    suspend fun doctorTierRows(
        since: Long,
        ownLike: String,
        minRx: Int,
        limit: Int,
    ): List<DoctorTierRow>

    /** One row per scanned item, for the antibiotic stewardship aggregate. */
    @Query(
        """
        SELECT p.id AS prescriptionId,
               p.doctor_name AS doctorName,
               IFNULL(p.doctor_specialty, '') AS specialty,
               IFNULL(p.district, '') AS district,
               sm.generic AS generic,
               sm.brand_name AS brandName
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''
        """
    )
    suspend fun stewardshipRows(since: Long): List<StewardshipRow>

    /** `find_off_territory_audits`: geofence failures, newest first. */
    @Query(
        """
        SELECT id, created_at AS createdAt, mr_id AS mrId,
               doctor_name AS doctorName, doctor_specialty AS doctorSpecialty,
               upazila, district, territory, lat, lng,
               territory_note AS territoryNote, total_medicines AS totalMedicines
        FROM prescriptions
        WHERE off_territory = 1 AND created_at >= :since
        ORDER BY created_at DESC
        LIMIT :limit
        """
    )
    suspend fun offTerritoryRows(since: Long, limit: Int): List<OffTerritoryRow>

    /** `get_scan_points`: point-level scan locations for the density map. */
    @Query(
        """
        SELECT p.id AS id, p.created_at AS createdAt, p.mr_id AS mrId,
               p.doctor_name AS doctorName, p.district AS district,
               p.territory AS territory, p.lat AS lat, p.lng AS lng,
               p.off_territory AS offTerritory, p.duplicate_of AS duplicateOf,
               (SELECT COUNT(*) FROM scanned_medicines sm
                 WHERE sm.prescription_id = p.id) AS items
        FROM prescriptions p
        WHERE p.created_at >= :since
        ORDER BY p.id DESC
        LIMIT :limit
        """
    )
    suspend fun scanPointRows(since: Long, limit: Int): List<ScanPointRow>

    /** `get_rsm_trends`: one row per scanned item with its captured company. */
    @Query(
        """
        SELECT p.created_at AS createdAt, sm.company_name AS companyName
        FROM prescriptions p
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.created_at >= :since
        """
    )
    suspend fun trendRows(since: Long): List<TrendItemRow>

    /**
     * `get_target_progress`: this calendar month's captured count per brand.
     *
     * Grouped case-insensitively to match the Python's `brand_name = ? COLLATE
     * NOCASE`; the caller looks rows up by lowercased target brand.
     */
    @Query(
        """
        SELECT sm.brand_name AS brand, COUNT(*) AS captured
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE p.created_at >= :since
        GROUP BY sm.brand_name COLLATE NOCASE
        """
    )
    suspend fun brandCapturedRows(since: Long): List<BrandCapturedRow>

    /**
     * `get_geo_heatmap`: prescriptions aggregated by district + upazila with the
     * own/competitor split, for the territory penetration map.
     */
    @Query(
        """
        SELECT p.district AS district, p.upazila AS upazila, p.territory AS territory,
               p.lat AS lat, p.lng AS lng,
               COUNT(DISTINCT p.id) AS rx,
               COUNT(sm.id) AS items,
               IFNULL(SUM(CASE WHEN sm.company_name LIKE :ownLike THEN 1 ELSE 0 END), 0) AS ownItems
        FROM prescriptions p
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.created_at >= :since AND IFNULL(p.district, '') != ''
        GROUP BY p.district, p.upazila
        ORDER BY items DESC
        """
    )
    suspend fun geoRegionRows(since: Long, ownLike: String): List<GeoRegionRow>

    // ---- Analytics aggregates ------------------------------------------

    @Query("SELECT COUNT(DISTINCT id) FROM prescriptions WHERE created_at >= :since")
    suspend fun prescriptionCountSince(since: Long): Int

    @Query(
        "SELECT COUNT(DISTINCT id) FROM prescriptions " +
            "WHERE created_at >= :from AND created_at < :to"
    )
    suspend fun prescriptionCountBetween(from: Long, to: Long): Int

    @Query("SELECT COUNT(DISTINCT id) FROM prescriptions")
    suspend fun prescriptionCountAll(): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "WHERE p.created_at >= :since"
    )
    suspend fun itemCountSince(since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "WHERE sm.company_name LIKE :ownLike AND p.created_at >= :since"
    )
    suspend fun ownItemCountSince(since: Long, ownLike: String): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "WHERE sm.company_name LIKE :ownLike " +
            "AND p.created_at >= :from AND p.created_at < :to"
    )
    suspend fun ownItemCountBetween(from: Long, to: Long, ownLike: String): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to"
    )
    suspend fun itemCountBetween(from: Long, to: Long): Int

    @Query(
        "SELECT sm.brand_name AS brandName, sm.company_name AS companyName, COUNT(*) AS count " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "WHERE IFNULL(sm.brand_name, '') != '' AND p.created_at >= :since " +
            "GROUP BY sm.brand_name ORDER BY count DESC LIMIT 1"
    )
    suspend fun topBrandRow(since: Long): TopBrandRow?

    @Query(
        "SELECT COUNT(DISTINCT doctor_name) FROM prescriptions " +
            "WHERE created_at >= :since AND IFNULL(doctor_name, '') != ''"
    )
    suspend fun activeDoctorCount(since: Long): Int

    @Query("SELECT COUNT(DISTINCT doctor_name) FROM prescriptions WHERE IFNULL(doctor_name, '') != ''")
    suspend fun allDoctorCount(): Int

    /** Widget A: most prescribed brands. */
    @Query(
        """
        SELECT sm.brand_name AS brandName, sm.generic AS generic,
               sm.company_name AS companyName, COUNT(*) AS captureCount
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE p.created_at >= :since
        GROUP BY sm.brand_name, sm.company_name
        ORDER BY captureCount DESC
        LIMIT :limit
        """
    )
    suspend fun mostPrescribedRows(since: Long, limit: Int): List<MostPrescribedRow>

    /** Widget B: company share of voice, before the Others bucket is formed. */
    @Query(
        """
        SELECT sm.company_name AS companyName, COUNT(*) AS count
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE IFNULL(sm.company_name, '') != ''
          AND sm.company_name NOT LIKE '%Unknown%'
          AND sm.company_name NOT LIKE '%Live search failed%'
          AND p.created_at >= :since
        GROUP BY sm.company_name
        ORDER BY count DESC
        """
    )
    suspend fun companyShareRows(since: Long): List<CompanyShareRow>

    /** Widget C: doctor conversion leaderboard, one page. */
    @Query(
        """
        SELECT p.doctor_name AS doctorName, IFNULL(p.chamber, '') AS chamber,
               IFNULL(p.doctor_specialty, '') AS specialty,
               IFNULL(p.district, '') AS district, IFNULL(p.territory, '') AS territory,
               COUNT(DISTINCT p.id) AS prescriptions,
               SUM(CASE WHEN sm.id IS NOT NULL THEN 1 ELSE 0 END) AS totalMeds,
               IFNULL(SUM(CASE WHEN sm.company_name LIKE :ownLike THEN 1 ELSE 0 END), 0) AS ownMeds
        FROM prescriptions p
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.created_at >= :since
        GROUP BY p.doctor_name
        ORDER BY prescriptions DESC, totalMeds DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun doctorLeaderRows(
        since: Long,
        ownLike: String,
        limit: Int,
        offset: Int,
    ): List<DoctorLeaderRow2>

    /** Widget C: total matching doctors, for the pagination caption. */
    @Query(
        "SELECT COUNT(*) FROM (SELECT doctor_name FROM prescriptions " +
            "WHERE created_at >= :since GROUP BY doctor_name)"
    )
    suspend fun doctorLeaderTotal(since: Long): Int

    /** The Live Recent Scans feed: newest scanned item first, with its Rx context. */
    @Query(
        """
        SELECT p.created_at AS createdAt,
               IFNULL(p.prescription_source, '') AS prescriptionSource,
               p.doctor_name AS doctorName,
               IFNULL(p.doctor_specialty, '') AS doctorSpecialty,
               IFNULL(p.district, '') AS district, IFNULL(p.upazila, '') AS upazila,
               sm.brand_name AS brandName, sm.company_name AS companyName,
               sm.company_verified AS companyVerified,
               sm.confidence_score AS confidenceScore
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        ORDER BY p.created_at DESC, sm.id DESC
        LIMIT :limit
        """
    )
    suspend fun liveScanRows(limit: Int): List<LiveScanFeedRow>

    @Insert
    suspend fun insertMedicines(rows: List<ScannedMedicineEntity>)

    @Query("DELETE FROM scanned_medicines WHERE prescription_id = :prescriptionId")
    suspend fun clearMedicines(prescriptionId: Long)

    @Query("SELECT * FROM scanned_medicines WHERE prescription_id = :prescriptionId ORDER BY line_number")
    suspend fun medicinesFor(prescriptionId: Long): List<ScannedMedicineEntity>

    /** Live Recent Scans feed — every individual medicine detected, newest first. */
    @Query(
        "SELECT * FROM scanned_medicines ORDER BY id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun recentMedicines(limit: Int, offset: Int): List<ScannedMedicineEntity>

    @Query("SELECT COUNT(*) FROM scanned_medicines")
    suspend fun medicineCount(): Int

    @Query("SELECT COUNT(*) FROM prescriptions")
    suspend fun prescriptionCount(): Int
}

@Dao
interface QueueDao {
    @Insert
    suspend fun enqueue(row: QueuedScanEntity): Long

    @Query("SELECT * FROM queued_scans ORDER BY created_at ASC")
    fun observeAll(): Flow<List<QueuedScanEntity>>

    @Query("SELECT COUNT(*) FROM queued_scans")
    fun observeCount(): Flow<Int>

    @Delete
    suspend fun remove(row: QueuedScanEntity)

    @Query("UPDATE queued_scans SET attempts = attempts + 1 WHERE id = :id")
    suspend fun markAttempt(id: Long)
}

@Dao
interface ProfileDao {
    @Upsert
    suspend fun save(profile: OfficerProfileEntity)

    @Query("SELECT * FROM officer_profile WHERE id = 1")
    fun observe(): Flow<OfficerProfileEntity?>

    @Query("SELECT * FROM officer_profile WHERE id = 1")
    suspend fun current(): OfficerProfileEntity?

    @Query("SELECT * FROM brand_targets ORDER BY brand")
    fun observeTargets(): Flow<List<BrandTargetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTargets(targets: List<BrandTargetEntity>)

    @Query("DELETE FROM brand_targets")
    suspend fun clearTargets()

    /** RSM-attached doctor detailing targets with their auto-logged visit counts. */
    @Query(
        """
        SELECT t.id AS targetId, t.doctor_name AS doctorName, t.specialty AS specialty,
               t.monthly_target AS monthlyTarget,
               (SELECT COUNT(*) FROM doctor_visits v WHERE v.target_id = t.id) AS visits
        FROM doctor_targets t
        ORDER BY t.doctor_name
        """
    )
    suspend fun doctorTargetRows(): List<DoctorTargetRow>

    /** Auto-logged visits, newest first, with the Rx that produced each one. */
    @Query(
        """
        SELECT v.doctor_name AS doctorName, v.mr_id AS mrId,
               v.visited_at AS visitedAt, IFNULL(p.rx_no, '') AS rxNo
        FROM doctor_visits v
        LEFT JOIN prescriptions p ON p.id = v.prescription_id
        ORDER BY v.visited_at DESC
        LIMIT :limit
        """
    )
    suspend fun recentVisits(limit: Int): List<DoctorVisitRow>

    @Query("DELETE FROM doctor_targets WHERE id = :targetId")
    suspend fun deleteDoctorTarget(targetId: Long)
}

@Dao
interface RsmDao {
    @Query("SELECT * FROM doctor_targets ORDER BY mpo_name, doctor_name")
    fun observeTargets(): Flow<List<DoctorTargetEntity>>

    @Insert
    suspend fun addTarget(target: DoctorTargetEntity): Long

    @Query("DELETE FROM doctor_targets WHERE id = :id")
    suspend fun removeTarget(id: Long)

    @Query("SELECT * FROM doctor_targets WHERE doctor_name = :doctor COLLATE NOCASE")
    suspend fun targetsForDoctor(doctor: String): List<DoctorTargetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun logVisit(visit: DoctorVisitEntity): Long

    @Query("SELECT * FROM doctor_visits WHERE target_id = :targetId")
    suspend fun visitsFor(targetId: Long): List<DoctorVisitEntity>

    @Query("SELECT * FROM doctor_visits ORDER BY visited_at DESC LIMIT :limit")
    suspend fun recentVisits(limit: Int = 25): List<DoctorVisitEntity>

    @Insert
    suspend fun reportError(row: ErrorReportEntity): Long

    @Query("SELECT * FROM error_reports ORDER BY created_at DESC")
    fun observeErrors(): Flow<List<ErrorReportEntity>>
}

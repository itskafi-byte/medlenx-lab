package com.medlenx.lab.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The doctor identity table.
 *
 * Small and write-once-per-new-doctor: a scan resolves its doctor by
 * [identityKey] and only inserts when nothing matches.
 */
@Dao
interface DoctorDao {

    @Query("SELECT COUNT(*) FROM doctors WHERE user_id = :currentUid")
    suspend fun count(currentUid: String): Int

    @Query("SELECT id FROM doctors WHERE user_id = :currentUid AND identity_key = :key LIMIT 1")
    suspend fun idFor(currentUid: String, key: String): Long?

    @Query("SELECT * FROM doctors WHERE user_id = :currentUid AND identity_key = :key LIMIT 1")
    suspend fun byKey(currentUid: String, key: String): DoctorEntity?

    @Update
    suspend fun update(doctor: DoctorEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(doctor: DoctorEntity): Long

    @Query("SELECT * FROM doctors WHERE user_id = :currentUid ORDER BY name")
    suspend fun all(currentUid: String): List<DoctorEntity>
}

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

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid AND scan_id = :scanId")
    suspend fun countByScanId(currentUid: String, scanId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRestored(p: PrescriptionEntity): Long

    @Query("UPDATE prescriptions SET created_at = :capturedAt WHERE user_id = :currentUid AND company_id = :company AND scan_id = :scanId AND restore_scan_id = :scanId AND sync_status = 'UPLOADED' AND created_at != :capturedAt")
    suspend fun updateRestoredCaptureTime(currentUid: String, company: String, scanId: String, capturedAt: Long): Int

    /** Used only while quarantining a legacy root-level image on this device. */
    @Query("UPDATE prescriptions SET image_path = :newPath WHERE image_path = :oldPath")
    suspend fun relocateLegacyImage(oldPath: String, newPath: String)

    @Query("UPDATE prescriptions SET sync_status = 'UPLOADED' WHERE id = :id AND user_id = :currentUid AND company_id = :company AND scan_id = :scanId AND sync_status = 'PENDING'")
    suspend fun markUploaded(currentUid: String, id: Long, company: String, scanId: String): Int

    /** Confirmed cleanup is device-wide, including other accounts' unsynced audits. */
    @Query("SELECT COUNT(*) FROM prescriptions WHERE sync_status IN ('LOCAL_ONLY', 'PENDING', 'FAILED_REJECTED') OR user_id = 'legacy_user'")
    fun observeTrappedCount(): Flow<Int>

    @Query("SELECT * FROM prescriptions WHERE sync_status IN ('LOCAL_ONLY', 'PENDING', 'FAILED_REJECTED') OR user_id = 'legacy_user' ORDER BY id")
    suspend fun trappedAudits(): List<PrescriptionEntity>

    @Query("DELETE FROM scanned_medicines WHERE prescription_id IN (:ids)")
    suspend fun deleteMedicinesForAudits(ids: List<Long>)

    @Query("DELETE FROM doctor_visits WHERE prescription_id IN (:ids)")
    suspend fun deleteVisitsForAudits(ids: List<Long>)

    @Query("DELETE FROM prescriptions WHERE id IN (:ids) AND (sync_status IN ('LOCAL_ONLY', 'PENDING', 'FAILED_REJECTED') OR user_id = 'legacy_user')")
    suspend fun deleteTrappedAudits(ids: List<Long>): Int

    @Query("SELECT image_path FROM prescriptions WHERE image_path != ''")
    suspend fun allReferencedImagePaths(): List<String>

    @Query("UPDATE prescriptions SET sync_status = 'FAILED_REJECTED' WHERE id = :id AND user_id = :currentUid AND company_id = :company AND scan_id = :scanId AND sync_status = 'PENDING'")
    suspend fun markRejected(currentUid: String, id: Long, company: String, scanId: String): Int

    /** Pending new audits only; historical scans are intentionally not uploaded. */
    @Query("SELECT * FROM prescriptions WHERE user_id = :currentUid AND sync_status = 'PENDING' ORDER BY id ASC")
    suspend fun pendingUploads(currentUid: String): List<PrescriptionEntity>

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid AND sync_status = 'PENDING'")
    fun observePendingUploads(currentUid: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid AND sync_status = 'PENDING'")
    suspend fun pendingUploadCount(currentUid: String): Int

    /** Only this account's unassigned audits are eligible; never re-route a stamped upload. */
    @Query("SELECT * FROM prescriptions WHERE user_id = :currentUid AND company_id = '' AND sync_status IN ('LOCAL_ONLY', 'PENDING') ORDER BY id")
    suspend fun claimableAudits(currentUid: String): List<PrescriptionEntity>

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid AND company_id = '' AND sync_status IN ('LOCAL_ONLY', 'PENDING')")
    fun observeClaimableAudits(currentUid: String): Flow<Int>

    @Query("UPDATE prescriptions SET company_id = :company, sync_status = 'PENDING', scan_id = CASE WHEN scan_id = '' THEN :newScanId ELSE scan_id END WHERE id = :id AND user_id = :currentUid AND company_id = '' AND sync_status IN ('LOCAL_ONLY', 'PENDING')")
    suspend fun assignAudit(currentUid: String, id: Long, company: String, newScanId: String): Int

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid AND sync_status = 'PENDING' AND company_id = ''")
    fun observeUnroutableUploads(currentUid: String): Flow<Int>

    @Query("SELECT * FROM prescriptions WHERE user_id = :currentUid ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(currentUid: String, limit: Int = 10): Flow<List<PrescriptionEntity>>

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid AND created_at >= :start AND created_at < :end")
    fun observeCountBetween(currentUid: String, start: Long, end: Long): Flow<Int>

    @Query("SELECT * FROM prescriptions WHERE user_id = :currentUid AND id = :id")
    suspend fun byId(currentUid: String, id: Long): PrescriptionEntity?

    @Query("SELECT * FROM prescriptions WHERE user_id = :currentUid AND image_hash = :hash AND id != :excludeId LIMIT 1")
    suspend fun findByHash(currentUid: String, hash: String, excludeId: Long = -1): PrescriptionEntity?

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
        WHERE p.user_id = :currentUid AND p.created_at >= :since
        """
    )
    suspend fun scannedSince(currentUid: String, since: Long): List<ScannedItemRow>

    /**
     * Per-doctor prescribing volumes for the tiering matrix — `get_doctor_tiers`
     * (database.py:2361).
     *
     * Grouped by `p.doctor_id` and reading specialty / district / territory from
     * the joined `doctors` row, which is what the web does. It grouped by name
     * before the doctors table existed, and two doctors sharing a name were one
     * row here and two on the web.
     *
     * Own-brand items are matched on the first token of the officer's company,
     * exactly as `get_doctor_tiers` does with `company_name LIKE '%token%'`.
     *
     * The web's `d.*` columns are refreshed on every save, so they carry the
     * doctor's current profile rather than the first one ever recorded.
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
        LEFT JOIN doctors d ON p.doctor_id = d.id
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''
        GROUP BY p.doctor_id
        HAVING rx >= :minRx
        ORDER BY rx DESC
        LIMIT :limit
        """
    )
    suspend fun doctorTierRows(currentUid: String,
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
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''
        """
    )
    suspend fun stewardshipRows(currentUid: String, since: Long): List<StewardshipRow>

    /**
     * `find_off_territory_audits`: geofence failures, newest first.
     *
     * Reads `doctor_specialty` off the prescription row, and so does the backend
     * (`database.py:1782`). This is the one place the web displays the captured
     * value rather than the joined one, and it is deliberate here too: the audit
     * is a record of a scan as it happened, and showing a specialty later
     * corrected on the doctor's profile would misreport what was recorded.
     */
    @Query(
        """
        SELECT id, created_at AS createdAt, mr_id AS mrId,
               doctor_name AS doctorName, doctor_specialty AS doctorSpecialty,
               upazila, district, territory, lat, lng,
               territory_note AS territoryNote, total_medicines AS totalMedicines
        FROM prescriptions
        WHERE user_id = :currentUid AND off_territory = 1 AND created_at >= :since
        ORDER BY created_at DESC
        LIMIT :limit
        """
    )
    suspend fun offTerritoryRows(currentUid: String, since: Long, limit: Int): List<OffTerritoryRow>

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
        WHERE p.user_id = :currentUid AND p.created_at >= :since
        ORDER BY p.id DESC
        LIMIT :limit
        """
    )
    suspend fun scanPointRows(currentUid: String, since: Long, limit: Int): List<ScanPointRow>

    /** `get_rsm_trends`: one row per scanned item with its captured company. */
    @Query(
        """
        SELECT p.created_at AS createdAt, sm.company_name AS companyName
        FROM prescriptions p
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since
        """
    )
    suspend fun trendRows(currentUid: String, since: Long): List<TrendItemRow>

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
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND p.created_at < :until
        GROUP BY sm.brand_name COLLATE NOCASE
        """
    )
    fun observeBrandCapturedRows(currentUid: String, since: Long, until: Long): Flow<List<BrandCapturedRow>>

    /** Dashboard counts prescriptions, not duplicate medicine lines within one scan. */
    @Query(
        """
        SELECT TRIM(sm.brand_name) AS brand, COUNT(DISTINCT p.id) AS captured
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND p.created_at < :until
          AND TRIM(sm.brand_name) != ''
        GROUP BY TRIM(sm.brand_name) COLLATE NOCASE
        """
    )
    fun observeDashboardBrandScans(currentUid: String, since: Long, until: Long): Flow<List<BrandCapturedRow>>

    /** One saved prescription is one scan; unnamed doctors are not ranked. */
    @Query(
        """
        SELECT TRIM(p.doctor_name) AS name, COUNT(*) AS count
        FROM prescriptions p
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND p.created_at < :until
          AND TRIM(IFNULL(p.doctor_name, '')) != ''
        GROUP BY TRIM(p.doctor_name) COLLATE NOCASE
        ORDER BY count DESC, name COLLATE NOCASE ASC
        LIMIT 3
        """
    )
    fun observeDashboardTopDoctors(currentUid: String, since: Long, until: Long): Flow<List<DrillCountRow>>

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
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND IFNULL(p.district, '') != ''
        GROUP BY p.district, p.upazila
        ORDER BY items DESC
        """
    )
    suspend fun geoRegionRows(currentUid: String, since: Long, ownLike: String): List<GeoRegionRow>

    // ---- Analytics aggregates ------------------------------------------

    @Query(
        // `p.id`, not `id`: joining `doctors` puts a second `id` in scope and
        // SQLite rejects an unqualified one as ambiguous.
        "SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun prescriptionCountSince(currentUid: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL
    )
    suspend fun prescriptionCountBetween(currentUid: String,
        from: Long,
        to: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query("SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id WHERE 1=1" + RX_FILTER_SQL)
    suspend fun prescriptionCountAll(currentUid: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun itemCountSince(currentUid: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE TRIM(sm.company_name) = TRIM(:ownLike) COLLATE NOCASE AND p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun ownItemCountSince(currentUid: String,
        since: Long,
        ownLike: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE TRIM(sm.company_name) = TRIM(:ownLike) COLLATE NOCASE " +
            "AND p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL
    )
    suspend fun ownItemCountBetween(currentUid: String,
        from: Long,
        to: Long,
        ownLike: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL
    )
    suspend fun itemCountBetween(currentUid: String,
        from: Long,
        to: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT sm.brand_name AS brandName, sm.company_name AS companyName, COUNT(*) AS count " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE IFNULL(sm.brand_name, '') != '' AND p.created_at >= :since " +
            RX_FILTER_SQL +
            " GROUP BY sm.brand_name ORDER BY count DESC LIMIT 1"
    )
    suspend fun topBrandRow(currentUid: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): TopBrandRow?

    @Query(
        "SELECT COUNT(DISTINCT p.doctor_name) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''" + RX_FILTER_SQL
    )
    suspend fun activeDoctorCount(currentUid: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT p.doctor_name) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE IFNULL(p.doctor_name, '') != ''" + RX_FILTER_SQL
    )
    suspend fun allDoctorCount(currentUid: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    // ─── Drill-down (web: get_company_drilldown / get_brand_doctors) ───────────
    //
    // These three mirror database.py:1166-1195. They reuse RX_FILTER_SQL, which is
    // what makes the drill-down inherit the filter bar's dimensions for free: the
    // web passes the same district / territory / specialty / mr_id / days into
    // `_filter_sql`, so the two cannot drift.

    /**
     * Every captured item for one company in the current filter.
     *
     * Android-only: the web has no such query, because it reports the sum of the
     * top-`limit` generic counts and calls that the total (database.py:1197).
     * That number understates the company as soon as it has more distinct
     * generics than the limit, so this gives the label something true to say and
     * the two figures are shown together rather than one replacing the other.
     */
    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.company_name = :company AND p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun companyItemCount(currentUid: String,
        company: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    /** Top generics for one company — the web's `generics` array. */
    @Query(
        "SELECT CASE WHEN IFNULL(sm.generic, '') = '' THEN 'Unspecified' ELSE sm.generic END AS name, " +
            "COUNT(*) AS count " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.company_name = :company AND p.created_at >= :since" + RX_FILTER_SQL +
            // The CASE is repeated rather than aliased: SQLite resolves an output
            // alias in GROUP BY, but Room's compile-time validator parses the
            // statement against the entity schema and may not, and there is no
            // compiler here to find out. Spelled out, `name` is only ever an
            // output label.
            " GROUP BY CASE WHEN IFNULL(sm.generic, '') = '' THEN 'Unspecified' " +
            "ELSE sm.generic END ORDER BY count DESC LIMIT :limit"
    )
    suspend fun companyGenerics(currentUid: String,
        company: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<DrillCountRow>

    /** Top brands for one company — the web's `brands` array. */
    @Query(
        "SELECT CASE WHEN IFNULL(sm.brand_name, '') = '' THEN 'Unspecified' ELSE sm.brand_name END AS name, " +
            "COUNT(*) AS count " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.company_name = :company AND p.created_at >= :since" + RX_FILTER_SQL +
            " GROUP BY CASE WHEN IFNULL(sm.brand_name, '') = '' THEN 'Unspecified' " +
            "ELSE sm.brand_name END ORDER BY count DESC LIMIT :limit"
    )
    suspend fun companyBrands(currentUid: String,
        company: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<DrillCountRow>

    /**
     * Top prescribing doctors for one company — the web's `doctors` array.
     *
     * Blank doctor names are excluded rather than grouped into one "Unknown"
     * bucket, matching the web's `IFNULL(p.doctor_name,'')!=''` guard: an
     * unnamed prescription is not a doctor who prescribed.
     */
    @Query(
        "SELECT p.doctor_name AS name, COUNT(*) AS count " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.company_name = :company AND p.created_at >= :since " +
            "AND IFNULL(p.doctor_name, '') != ''" + RX_FILTER_SQL +
            " GROUP BY p.doctor_name ORDER BY count DESC LIMIT :limit"
    )
    suspend fun companyDoctors(currentUid: String,
        company: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<DrillCountRow>

    /**
     * Which doctors prescribed a brand — `get_brand_doctors` (database.py:1209).
     *
     * Grouped by `p.doctor_id`, the web's own key. This is the reason the
     * `doctors` table exists: grouping by name merged two doctors who share one
     * into a single row.
     *
     * Two details copied from the web deliberately rather than improved on:
     *
     * - `p.doctor_name` is selected alongside `GROUP BY p.doctor_id`, so it is a
     *   bare column and SQLite returns an arbitrary row's value for the group.
     *   The web does exactly this; the name shown is the one the doctor was
     *   labelled with on whichever visit SQLite picked. Reading the name from the
     *   joined `doctors` row would be tidier and would *not* be the same output.
     * - NULL `doctor_id` groups together, because SQLite treats nulls as equal in
     *   GROUP BY — so every unattributable prescription becomes one "Unknown"
     *   row, which is what the web's null `doctor_id` does too.
     *
     * `lastSeen` is `MAX(p.created_at)`, the web's "Times" column.
     */
    @Query(
        "SELECT CASE WHEN IFNULL(p.doctor_name, '') = '' THEN 'Unknown' ELSE p.doctor_name END AS doctorName, " +
            "CASE WHEN IFNULL(p.doctor_specialty, '') = '' THEN 'General' ELSE p.doctor_specialty END AS specialty, " +
            "CASE WHEN IFNULL(p.chamber, '') = '' THEN 'N/A' ELSE p.chamber END AS chamber, " +
            "COUNT(*) AS count, MAX(p.created_at) AS lastSeen " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.brand_name = :brand AND p.created_at >= :since" + RX_FILTER_SQL +
            " GROUP BY p.doctor_id ORDER BY count DESC LIMIT :limit"
    )
    suspend fun brandDoctors(currentUid: String,
        brand: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<BrandDoctorRow>

    /**
     * Rows behind the web's `/api/export/recent-medicines.csv` (main.py:840).
     *
     * `companyVerified` is a Boolean onto an INTEGER column, which Room maps
     * directly; no converter is involved. Ordered newest-first so a truncated
     * export keeps the most recent work, matching the web's
     * `get_recent_scanned_medicines` ordering.
     *
     * The filter clauses are spelled out here instead of appending
     * [RX_FILTER_SQL], because this is the one endpoint the web backs with the
     * denormalised `recent_scanned_medicines` row rather than with a join.
     * `get_recent_scanned_medicines` filters its own `district` / `territory` /
     * `specialty` columns -- the values written at scan time -- so the specialty
     * here is `p.doctor_specialty`, not the joined `p.doctor_specialty`. Filtering on the
     * doctor's current profile would select rows whose printed specialty
     * disagrees with the filter that selected them, and would export a scan as a
     * specialty it did not record.
     *
     * `p.district`, `p.territory` and `p.mr_id` are already the captured values,
     * so only the specialty differs from the shared fragment.
     *
     * **`source` is applied here and not on the web.** The web's export button
     * sends `source=` (`index.html:2265`) and `/api/export/recent-medicines.csv`
     * never declares the parameter, so Flask drops it and a source-filtered
     * dashboard exports every source with no error. See
     * `agent/findings/2026-09-26-web-export-source.md`. Android honours the filter
     * it is showing rather than reproducing that; the missing web parameter is
     * logged to be fixed at the source instead.
     */
    @Query(
        "SELECT p.created_at AS createdAt, p.mr_id AS mrId, p.doctor_name AS doctorName, " +
            "p.doctor_specialty AS doctorSpecialty, sm.brand_name AS brandName, " +
            "sm.generic AS generic, sm.company_name AS companyName, " +
            "sm.dosage_form AS dosageForm, sm.strength AS strength, sm.dosage AS dosage, " +
            "sm.confidence_score AS confidenceScore, " +
            "sm.company_verified AS companyVerified, p.district AS district, " +
            "p.upazila AS upazila, p.territory AS territory, p.id AS prescriptionId " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL_SNAPSHOT +
            " ORDER BY p.created_at DESC, sm.id DESC LIMIT :limit"
    )
    suspend fun recentMedicineExportRows(currentUid: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<RecentMedicineRow>

    @Query("""
        SELECT p.created_at AS createdAt, p.doctor_name AS doctorName,
               IFNULL(p.doctor_specialty, '') AS specialty,
               COALESCE(NULLIF(sm.brand_name, ''), sm.generic, '') AS medicine,
               IFNULL(sm.company_name, '') AS manufacturer
        FROM prescriptions p LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid
        ORDER BY p.created_at DESC, p.id DESC, sm.line_number ASC
    """)
    suspend fun scanExportRows(currentUid: String): List<ScanExportRow>

    /** Widget A: most prescribed brands. */
    @Query(
        """
        SELECT sm.brand_name AS brandName, sm.generic AS generic,
               sm.company_name AS companyName, COUNT(*) AS captureCount
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)
        GROUP BY sm.brand_name, sm.company_name
        ORDER BY captureCount DESC
        LIMIT :limit
        """
    )
    suspend fun mostPrescribedRows(currentUid: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<MostPrescribedRow>

    /** Widget B: company share of voice, before the Others bucket is formed. */
    @Query(
        """
        SELECT sm.company_name AS companyName, COUNT(*) AS count
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.user_id = :currentUid AND IFNULL(sm.company_name, '') != ''
          AND sm.company_name NOT LIKE '%Unknown%'
          AND sm.company_name NOT LIKE '%Live search failed%'
          AND p.created_at >= :since
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)
        GROUP BY sm.company_name
        ORDER BY count DESC
        """
    )
    suspend fun companyShareRows(currentUid: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<CompanyShareRow>

    /** Widget C: doctor conversion leaderboard, one page. */
    @Query(
        """
        SELECT p.doctor_name AS doctorName, IFNULL(p.chamber, '') AS chamber,
               IFNULL(p.doctor_specialty, '') AS specialty,
               IFNULL(p.district, '') AS district, IFNULL(p.territory, '') AS territory,
               COUNT(DISTINCT p.id) AS prescriptions,
               SUM(CASE WHEN sm.id IS NOT NULL THEN 1 ELSE 0 END) AS totalMeds,
               IFNULL(SUM(CASE WHEN sm.company_name LIKE :ownLike THEN 1 ELSE 0 END), 0) AS ownMeds,
               CASE WHEN TRIM(:ownCompany) = '' THEN NULL ELSE
                   COUNT(DISTINCT CASE WHEN TRIM(IFNULL(sm.company_name, '')) = TRIM(:ownCompany) COLLATE NOCASE THEN p.id END)
               END AS brandMatchRx, p.doctor_id AS doctorId
        FROM prescriptions p
        LEFT JOIN doctors d ON p.doctor_id = d.id
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)
        GROUP BY p.doctor_id
        ORDER BY prescriptions DESC,
                 CASE WHEN prescriptions > 0 THEN 1.0 * IFNULL(brandMatchRx, 0) / prescriptions
                    ELSE 0.0 END DESC,
                 IFNULL(p.doctor_name, '') COLLATE NOCASE ASC, p.doctor_id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun doctorLeaderRows(currentUid: String,
        since: Long,
        ownLike: String,
        ownCompany: String,
        limit: Int,
        offset: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<DoctorLeaderRow2>

    /** Medicine counts per prescription and company for the displayed doctor page. */
    @Query("""
        SELECT p.doctor_id AS doctorId, p.id AS prescriptionId,
               IFNULL(sm.company_name, '') AS company, COUNT(sm.id) AS medicineCount
        FROM prescriptions p LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since
          AND (p.doctor_id IN (:doctorIds) OR (:includeUnassigned = 1 AND p.doctor_id IS NULL))
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)
        GROUP BY p.id, IFNULL(sm.company_name, '') COLLATE NOCASE
    """)
    suspend fun doctorLeaderIntelligence(currentUid: String, since: Long, doctorIds: List<Long>,
        includeUnassigned: Boolean, district: String?, territory: String?, specialty: String?,
        mrId: String?, source: String?): List<DoctorPrescriptionCompanyCount>

    /** Widget C: total matching doctors, for the pagination caption. */
    @Query(
        // Grouped by doctor_id to match the page it counts; the web's total does
        // the same (database.py:1116). Counting by name would disagree with the
        // rows whenever two doctors share one.
        "SELECT COUNT(*) FROM (SELECT p.doctor_id FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL + " GROUP BY p.doctor_id)"
    )
    suspend fun doctorLeaderTotal(currentUid: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): Int

    /**
     * The Live Recent Scans feed: newest scanned item first, with its Rx context.
     *
     * `doctor_specialty` is the prescription's own value, matching the backend's
     * feed, which reads the denormalised `recent_scanned_medicines` row written
     * at scan time (`database.py:1329`). The feed reports what was captured.
     */
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
        WHERE p.user_id = :currentUid
        ORDER BY p.created_at DESC, sm.id DESC
        LIMIT :limit
        """
    )
    suspend fun liveScanRows(currentUid: String, limit: Int): List<LiveScanFeedRow>

    /** Page parent prescriptions before loading medicines, including empty and legacy scans. */
    @Transaction
    @Query("""
        SELECT p.* FROM prescriptions p
        WHERE p.user_id = :currentUid
          AND (:doctorSelected = 0 OR p.doctor_id IS :doctorId)
          AND (:chamber = 'ALL' OR (:chamber = 'HOSPITAL' AND IFNULL(p.prescription_source, '') LIKE '%hospital%')
               OR (:chamber = 'PRIVATE' AND IFNULL(p.prescription_source, '') NOT LIKE '%hospital%'))
        ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset
    """)
    fun observeAuditFeed(currentUid: String, chamber: String, limit: Int, offset: Int, doctorSelected: Boolean, doctorId: Long?): Flow<List<AuditFeedEvent>>

    @Query("""
        SELECT COUNT(*) FROM prescriptions p
        WHERE p.user_id = :currentUid
          AND (:doctorSelected = 0 OR p.doctor_id IS :doctorId)
          AND (:chamber = 'ALL' OR (:chamber = 'HOSPITAL' AND IFNULL(p.prescription_source, '') LIKE '%hospital%')
               OR (:chamber = 'PRIVATE' AND IFNULL(p.prescription_source, '') NOT LIKE '%hospital%'))
    """)
    fun observeAuditFeedCount(currentUid: String, chamber: String, doctorSelected: Boolean, doctorId: Long?): Flow<Int>

    @Query("""
        SELECT p.doctor_specialty AS specialty, IFNULL(sm.company_name, '') AS company, COUNT(*) AS count
        FROM scanned_medicines sm INNER JOIN prescriptions p ON sm.prescription_id = p.id
        WHERE p.user_id = :currentUid AND p.created_at >= :since AND IFNULL(p.doctor_specialty, '') != ''
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)
        GROUP BY p.doctor_specialty, IFNULL(sm.company_name, '')
        ORDER BY p.doctor_specialty
    """)
    suspend fun specialtyCompanyCounts(currentUid: String, since: Long, district: String?, territory: String?,
        specialty: String?, mrId: String?, source: String?): List<SpecialtyCompanyCount>

    /** Widget D: generic counts by the specialty captured with this user's scan. */
    @Query(
        """
        SELECT IFNULL(p.doctor_specialty, '') AS specialty,
               sm.generic AS generic,
               COUNT(*) AS count
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.user_id = :currentUid AND sm.generic != '' AND IFNULL(p.doctor_specialty, '') != ''
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
          AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)
        GROUP BY p.doctor_specialty, sm.generic
        ORDER BY p.doctor_specialty, count DESC
        """
    )
    suspend fun genericMatrixRows(currentUid: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
        source: String?,
    ): List<GenericMatrixRow>

    /** `get_filter_options`: distinct values actually present in the data. */
    @Query(
        "SELECT DISTINCT district FROM prescriptions " +
            "WHERE user_id = :currentUid AND IFNULL(district, '') != '' ORDER BY district"
    )
    suspend fun filterDistricts(currentUid: String): List<String>

    @Query(
        "SELECT DISTINCT territory FROM prescriptions " +
            "WHERE user_id = :currentUid AND IFNULL(territory, '') != '' ORDER BY territory"
    )
    suspend fun filterTerritories(currentUid: String): List<String>

    /** Specialty options come only from this user's prescription snapshots. */
    @Query(
        "SELECT DISTINCT p.doctor_specialty FROM prescriptions p " +
            "WHERE p.user_id = :currentUid AND IFNULL(p.doctor_specialty, '') != '' ORDER BY p.doctor_specialty"
    )
    suspend fun filterSpecialties(currentUid: String): List<String>

    @Query(
        "SELECT DISTINCT mr_id FROM prescriptions " +
            "WHERE user_id = :currentUid AND IFNULL(mr_id, '') != '' ORDER BY mr_id"
    )
    suspend fun filterMrIds(currentUid: String): List<String>

    @Insert
    suspend fun insertMedicines(rows: List<ScannedMedicineEntity>)

    @Query("SELECT sm.* FROM scanned_medicines sm INNER JOIN prescriptions p ON sm.prescription_id = p.id WHERE p.user_id = :currentUid AND p.id = :prescriptionId ORDER BY sm.line_number")
    suspend fun medicinesFor(currentUid: String, prescriptionId: Long): List<ScannedMedicineEntity>

    /** Live Recent Scans feed — every individual medicine detected, newest first. */
    @Query(
        "SELECT sm.* FROM scanned_medicines sm INNER JOIN prescriptions p ON sm.prescription_id = p.id WHERE p.user_id = :currentUid ORDER BY sm.id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun recentMedicines(currentUid: String, limit: Int, offset: Int): List<ScannedMedicineEntity>

    @Query("SELECT COUNT(*) FROM scanned_medicines sm INNER JOIN prescriptions p ON sm.prescription_id = p.id WHERE p.user_id = :currentUid")
    suspend fun medicineCount(currentUid: String): Int

    @Query("SELECT COUNT(*) FROM prescriptions WHERE user_id = :currentUid")
    suspend fun prescriptionCount(currentUid: String): Int

    /**
     * Every stored perceptual hash, oldest first — the duplicate scan's input.
     *
     * Matches `find_duplicate_prescription`'s own predicate and ordering:
     * `WHERE image_phash IS NOT NULL AND image_phash != '' ORDER BY id ASC`.
     * The ordering matters because a tie on Hamming distance keeps the earliest
     * row, which is what the backend's `dist < best_dist` strict comparison does.
     */
    @Query(
        "SELECT id, rx_no AS rxNo, image_hash AS imageHash FROM prescriptions " +
            "WHERE user_id = :currentUid AND image_hash IS NOT NULL AND image_hash != '' ORDER BY id ASC",
    )
    suspend fun hashRows(currentUid: String): List<PrescriptionHashRow>
}

@Dao
interface QueueDao {
    @Query("SELECT image_path FROM queued_scans WHERE image_path != ''")
    suspend fun allReferencedImagePaths(): List<String>

    @Query("UPDATE queued_scans SET image_path = :newPath WHERE image_path = :oldPath")
    suspend fun relocateLegacyImage(oldPath: String, newPath: String)

    @Insert
    suspend fun enqueue(row: QueuedScanEntity): Long

    @Query("SELECT * FROM queued_scans WHERE user_id = :currentUid ORDER BY created_at ASC")
    fun observeAll(currentUid: String): Flow<List<QueuedScanEntity>>

    @Query("SELECT COUNT(*) FROM queued_scans WHERE user_id = :currentUid")
    fun observeCount(currentUid: String): Flow<Int>

    @Query("DELETE FROM queued_scans WHERE user_id = :currentUid AND id = :id")
    suspend fun remove(currentUid: String, id: Long)

    @Query("UPDATE queued_scans SET attempts = attempts + 1 WHERE user_id = :currentUid AND id = :id")
    suspend fun markAttempt(currentUid: String, id: Long)
}

@Dao
interface ProfileDao {
    @Upsert
    suspend fun save(profile: OfficerProfileEntity)

    @Query("SELECT * FROM officer_profile WHERE user_id = :currentUid")
    fun observe(currentUid: String): Flow<OfficerProfileEntity?>

    @Query("SELECT * FROM officer_profile WHERE user_id = :currentUid")
    suspend fun current(currentUid: String): OfficerProfileEntity?

    @Query("SELECT * FROM brand_targets WHERE user_id = :currentUid ORDER BY brand")
    fun observeTargets(currentUid: String): Flow<List<BrandTargetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTargets(targets: List<BrandTargetEntity>)

    @Query("DELETE FROM brand_targets WHERE user_id = :currentUid")
    suspend fun clearTargets(currentUid: String)

    /** RSM-attached doctor detailing targets with their auto-logged visit counts. */
    @Query(
        """
        SELECT t.id AS targetId, t.doctor_name AS doctorName, t.specialty AS specialty,
               t.monthly_target AS monthlyTarget,
               (SELECT COUNT(*) FROM doctor_visits v WHERE v.target_id = t.id AND v.user_id = :currentUid) AS visits
        FROM doctor_targets t
        WHERE t.user_id = :currentUid
        ORDER BY t.doctor_name
        """
    )
    suspend fun doctorTargetRows(currentUid: String): List<DoctorTargetRow>

    /** Auto-logged visits, newest first, with the Rx that produced each one. */
    @Query(
        """
        SELECT v.doctor_name AS doctorName, v.mr_id AS mrId,
               v.visited_at AS visitedAt, IFNULL(p.rx_no, '') AS rxNo
        FROM doctor_visits v
        LEFT JOIN prescriptions p ON p.id = v.prescription_id AND p.user_id = :currentUid
        WHERE v.user_id = :currentUid
        ORDER BY v.visited_at DESC
        LIMIT :limit
        """
    )
    suspend fun recentVisits(currentUid: String, limit: Int): List<DoctorVisitRow>

    @Query("DELETE FROM doctor_targets WHERE user_id = :currentUid AND id = :targetId")
    suspend fun deleteDoctorTarget(currentUid: String, targetId: Long)
}

@Dao
interface RsmDao {
    @Query("SELECT * FROM doctor_targets WHERE user_id = :currentUid ORDER BY mpo_name, doctor_name")
    fun observeTargets(currentUid: String): Flow<List<DoctorTargetEntity>>

    @Insert
    suspend fun addTarget(target: DoctorTargetEntity): Long

    @Query("DELETE FROM doctor_targets WHERE user_id = :currentUid AND id = :id")
    suspend fun removeTarget(currentUid: String, id: Long)

    @Query("SELECT * FROM doctor_targets WHERE user_id = :currentUid AND doctor_name = :doctor COLLATE NOCASE")
    suspend fun targetsForDoctor(currentUid: String, doctor: String): List<DoctorTargetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun logVisit(visit: DoctorVisitEntity): Long

    @Query("SELECT * FROM doctor_visits WHERE user_id = :currentUid AND target_id = :targetId")
    suspend fun visitsFor(currentUid: String, targetId: Long): List<DoctorVisitEntity>

    @Query("SELECT * FROM doctor_visits WHERE user_id = :currentUid ORDER BY visited_at DESC LIMIT :limit")
    suspend fun recentVisits(currentUid: String, limit: Int = 25): List<DoctorVisitEntity>

    @Insert
    suspend fun reportError(row: ErrorReportEntity): Long

    @Query("SELECT * FROM error_reports WHERE user_id = :currentUid ORDER BY created_at DESC")
    fun observeErrors(currentUid: String): Flow<List<ErrorReportEntity>>
}

@Dao
interface ManufacturerAliasDao {
    @Query("SELECT DISTINCT canonical_company_id FROM manufacturer_aliases ORDER BY canonical_company_id COLLATE NOCASE")
    suspend fun canonicalCompanies(): List<String>

    // Exact equality is intentional: LIKE would interpret OCR '%' and '_' as wildcards.
    @Query("SELECT canonical_company_id FROM manufacturer_aliases WHERE alias_string = TRIM(:name) COLLATE NOCASE LIMIT 1")
    suspend fun canonicalFor(name: String): String?
}

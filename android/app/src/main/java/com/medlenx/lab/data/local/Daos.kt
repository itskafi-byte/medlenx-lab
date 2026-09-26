package com.medlenx.lab.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT COUNT(*) FROM doctors")
    suspend fun count(): Int

    @Query("SELECT id FROM doctors WHERE identity_key = :key LIMIT 1")
    suspend fun idFor(key: String): Long?

    @Query("SELECT * FROM doctors WHERE identity_key = :key LIMIT 1")
    suspend fun byKey(key: String): DoctorEntity?

    @Update
    suspend fun update(doctor: DoctorEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(doctor: DoctorEntity): Long

    @Query("SELECT * FROM doctors ORDER BY name")
    suspend fun all(): List<DoctorEntity>
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
               IFNULL(d.specialty, '') AS specialty,
               IFNULL(d.district, '') AS district,
               IFNULL(d.territory, '') AS territory,
               COUNT(DISTINCT p.id) AS rx,
               COUNT(sm.id) AS items,
               IFNULL(SUM(CASE WHEN sm.company_name LIKE :ownLike THEN 1 ELSE 0 END), 0) AS ownItems
        FROM prescriptions p
        LEFT JOIN doctors d ON p.doctor_id = d.id
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''
        GROUP BY p.doctor_id
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
               IFNULL(d.specialty, '') AS specialty,
               IFNULL(d.district, '') AS district,
               sm.generic AS generic,
               sm.brand_name AS brandName
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''
        """
    )
    suspend fun stewardshipRows(since: Long): List<StewardshipRow>

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

    @Query(
        // `p.id`, not `id`: joining `doctors` puts a second `id` in scope and
        // SQLite rejects an unqualified one as ambiguous.
        "SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun prescriptionCountSince(
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL
    )
    suspend fun prescriptionCountBetween(
        from: Long,
        to: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query("SELECT COUNT(DISTINCT p.id) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id WHERE 1=1" + RX_FILTER_SQL)
    suspend fun prescriptionCountAll(
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun itemCountSince(
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.company_name LIKE :ownLike AND p.created_at >= :since" + RX_FILTER_SQL
    )
    suspend fun ownItemCountSince(
        since: Long,
        ownLike: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.company_name LIKE :ownLike " +
            "AND p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL
    )
    suspend fun ownItemCountBetween(
        from: Long,
        to: Long,
        ownLike: String,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :from AND p.created_at < :to" + RX_FILTER_SQL
    )
    suspend fun itemCountBetween(
        from: Long,
        to: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
    suspend fun topBrandRow(
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): TopBrandRow?

    @Query(
        "SELECT COUNT(DISTINCT p.doctor_name) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since AND IFNULL(p.doctor_name, '') != ''" + RX_FILTER_SQL
    )
    suspend fun activeDoctorCount(
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT p.doctor_name) FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE IFNULL(p.doctor_name, '') != ''" + RX_FILTER_SQL
    )
    suspend fun allDoctorCount(
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
    suspend fun companyItemCount(
        company: String,
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
    suspend fun companyGenerics(
        company: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
    suspend fun companyBrands(
        company: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
    suspend fun companyDoctors(
        company: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
            "CASE WHEN IFNULL(d.specialty, '') = '' THEN 'General' ELSE d.specialty END AS specialty, " +
            "CASE WHEN IFNULL(d.chamber, '') = '' THEN 'N/A' ELSE d.chamber END AS chamber, " +
            "COUNT(*) AS count, MAX(p.created_at) AS lastSeen " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE sm.brand_name = :brand AND p.created_at >= :since" + RX_FILTER_SQL +
            " GROUP BY p.doctor_id ORDER BY count DESC LIMIT :limit"
    )
    suspend fun brandDoctors(
        brand: String,
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): List<BrandDoctorRow>

    /**
     * Rows behind the web's `/api/export/recent-medicines.csv` (main.py:840).
     *
     * `companyVerified` is a Boolean onto an INTEGER column, which Room maps
     * directly; no converter is involved. Ordered newest-first so a truncated
     * export keeps the most recent work, matching the web's
     * `get_recent_scanned_medicines` ordering.
     */
    @Query(
        // The export mirrors the backend's CSV, which reads
        // `recent_scanned_medicines` -- the denormalised feed written at scan
        // time -- so its `specialty` column is the captured value, not the joined
        // one (main.py:840). Filtering it by the current profile would make the
        // filtered rows and the printed specialty disagree.
        "SELECT p.created_at AS createdAt, p.mr_id AS mrId, p.doctor_name AS doctorName, " +
            "p.doctor_specialty AS doctorSpecialty, sm.brand_name AS brandName, " +
            "sm.generic AS generic, sm.company_name AS companyName, " +
            "sm.dosage_form AS dosageForm, sm.strength AS strength, sm.dosage AS dosage, " +
            "sm.confidence_score AS confidenceScore, " +
            "sm.company_verified AS companyVerified, p.district AS district, " +
            "p.upazila AS upazila, p.territory AS territory, p.id AS prescriptionId " +
            "FROM scanned_medicines sm " +
            "INNER JOIN prescriptions p ON sm.prescription_id = p.id " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL +
            " ORDER BY p.created_at DESC, sm.id DESC LIMIT :limit"
    )
    suspend fun recentMedicineExportRows(
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): List<RecentMedicineRow>

    /** Widget A: most prescribed brands. */
    @Query(
        """
        SELECT sm.brand_name AS brandName, sm.generic AS generic,
               sm.company_name AS companyName, COUNT(*) AS captureCount
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE p.created_at >= :since
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR d.specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
        GROUP BY sm.brand_name, sm.company_name
        ORDER BY captureCount DESC
        LIMIT :limit
        """
    )
    suspend fun mostPrescribedRows(
        since: Long,
        limit: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): List<MostPrescribedRow>

    /** Widget B: company share of voice, before the Others bucket is formed. */
    @Query(
        """
        SELECT sm.company_name AS companyName, COUNT(*) AS count
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE IFNULL(sm.company_name, '') != ''
          AND sm.company_name NOT LIKE '%Unknown%'
          AND sm.company_name NOT LIKE '%Live search failed%'
          AND p.created_at >= :since
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR d.specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
        GROUP BY sm.company_name
        ORDER BY count DESC
        """
    )
    suspend fun companyShareRows(
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): List<CompanyShareRow>

    /** Widget C: doctor conversion leaderboard, one page. */
    @Query(
        """
        SELECT p.doctor_name AS doctorName, IFNULL(d.chamber, '') AS chamber,
               IFNULL(d.specialty, '') AS specialty,
               IFNULL(d.district, '') AS district, IFNULL(d.territory, '') AS territory,
               COUNT(DISTINCT p.id) AS prescriptions,
               SUM(CASE WHEN sm.id IS NOT NULL THEN 1 ELSE 0 END) AS totalMeds,
               IFNULL(SUM(CASE WHEN sm.company_name LIKE :ownLike THEN 1 ELSE 0 END), 0) AS ownMeds
        FROM prescriptions p
        LEFT JOIN doctors d ON p.doctor_id = d.id
        LEFT JOIN scanned_medicines sm ON sm.prescription_id = p.id
        WHERE p.created_at >= :since
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR d.specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
        GROUP BY p.doctor_id
        ORDER BY prescriptions DESC, totalMeds DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun doctorLeaderRows(
        since: Long,
        ownLike: String,
        limit: Int,
        offset: Int,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): List<DoctorLeaderRow2>

    /** Widget C: total matching doctors, for the pagination caption. */
    @Query(
        // Grouped by doctor_id to match the page it counts; the web's total does
        // the same (database.py:1116). Counting by name would disagree with the
        // rows whenever two doctors share one.
        "SELECT COUNT(*) FROM (SELECT p.doctor_id FROM prescriptions p " +
            "LEFT JOIN doctors d ON p.doctor_id = d.id " +
            "WHERE p.created_at >= :since" + RX_FILTER_SQL + " GROUP BY p.doctor_id)"
    )
    suspend fun doctorLeaderTotal(
        since: Long,
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
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
        ORDER BY p.created_at DESC, sm.id DESC
        LIMIT :limit
        """
    )
    suspend fun liveScanRows(limit: Int): List<LiveScanFeedRow>

    /**
     * Widget D: generic counts per specialty, `get_generic_brand_matrix`.
     *
     * Specialty comes from the joined doctor in all four places the backend reads
     * it (`database.py:1233`): the selected column, the non-blank test, the
     * `GROUP BY` and the `ORDER BY`. This used to read the prescription's own
     * `doctor_specialty`, which is the value captured at scan time and does not
     * move when a doctor's specialty is corrected.
     */
    @Query(
        """
        SELECT IFNULL(d.specialty, '') AS specialty,
               sm.generic AS generic,
               COUNT(*) AS count
        FROM scanned_medicines sm
        INNER JOIN prescriptions p ON sm.prescription_id = p.id
        LEFT JOIN doctors d ON p.doctor_id = d.id
        WHERE sm.generic != '' AND IFNULL(d.specialty, '') != ''
          AND (:district IS NULL OR :district = '' OR p.district = :district)
          AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)
          AND (:specialty IS NULL OR :specialty = '' OR d.specialty = :specialty)
          AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)
        GROUP BY d.specialty, sm.generic
        ORDER BY d.specialty, count DESC
        """
    )
    suspend fun genericMatrixRows(
        district: String?,
        territory: String?,
        specialty: String?,
        mrId: String?,
    ): List<GenericMatrixRow>

    /** `get_filter_options`: distinct values actually present in the data. */
    @Query(
        "SELECT DISTINCT district FROM prescriptions " +
            "WHERE IFNULL(district, '') != '' ORDER BY district"
    )
    suspend fun filterDistricts(): List<String>

    @Query(
        "SELECT DISTINCT territory FROM prescriptions " +
            "WHERE IFNULL(territory, '') != '' ORDER BY territory"
    )
    suspend fun filterTerritories(): List<String>

    /**
     * `get_filter_options` (database.py:1393) reads the specialty options from
     * `doctors`, not from the prescriptions, so the dropdown offers exactly the
     * values the filter can match. Listing them from `prescriptions` instead
     * would offer a specialty that no longer exists on any doctor, and selecting
     * it would return nothing.
     */
    @Query(
        "SELECT DISTINCT specialty FROM doctors " +
            "WHERE IFNULL(specialty, '') != '' ORDER BY specialty"
    )
    suspend fun filterSpecialties(): List<String>

    @Query(
        "SELECT DISTINCT mr_id FROM prescriptions " +
            "WHERE IFNULL(mr_id, '') != '' ORDER BY mr_id"
    )
    suspend fun filterMrIds(): List<String>

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
            "WHERE image_hash IS NOT NULL AND image_hash != '' ORDER BY id ASC",
    )
    suspend fun hashRows(): List<PrescriptionHashRow>
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

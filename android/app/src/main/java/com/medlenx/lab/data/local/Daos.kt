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

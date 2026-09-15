package com.medlenx.lab.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MedexEntity::class,
        PrescriptionEntity::class,
        ScannedMedicineEntity::class,
        QueuedScanEntity::class,
        OfficerProfileEntity::class,
        BrandTargetEntity::class,
        DoctorTargetEntity::class,
        DoctorVisitEntity::class,
        ErrorReportEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MedLenXDatabase : RoomDatabase() {

    abstract fun medexDao(): MedexDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun queueDao(): QueueDao
    abstract fun profileDao(): ProfileDao
    abstract fun rsmDao(): RsmDao

    companion object {
        @Volatile private var instance: MedLenXDatabase? = null

        fun get(context: Context): MedLenXDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MedLenXDatabase::class.java,
                "medlenx.db",
            )
                // Destructive migration is acceptable while the schema is still settling
                // in Step 2; replace with real migrations before the first public release.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

package com.medlenx.lab.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DoctorEntity::class,
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
    version = 3,
    exportSchema = false,
)
abstract class MedLenXDatabase : RoomDatabase() {

    abstract fun doctorDao(): DoctorDao
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
                // Kept as the fallback for any *other* version change, but v1->v2
                // is a real migration: see [Migrations.MIGRATION_1_2]. Room prefers
                // a registered migration over the destructive path, so this only
                // fires for a version it has no route for -- and a wipe is the
                // right answer there, since the rest of the schema is derived data
                // that the next scan rebuilds.
                .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

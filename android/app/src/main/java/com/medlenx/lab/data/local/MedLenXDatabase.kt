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
        ManufacturerAlias::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class MedLenXDatabase : RoomDatabase() {

    abstract fun manufacturerAliasDao(): ManufacturerAliasDao
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
                // Never silently wipe unsynced local data on an unknown schema.
                .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8, Migrations.MIGRATION_8_9, Migrations.MIGRATION_9_10, Migrations.MIGRATION_10_11, Migrations.migration11To12(context.applicationContext))
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        ManufacturerAliases.seed(context.applicationContext, db)
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}

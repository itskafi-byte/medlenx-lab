package com.medlenx.lab.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * The database was created with `fallbackToDestructiveMigration`, which is fine
 * while every table is derived data — a wiped database loses nothing that the
 * next scan does not rebuild. That stopped being true the moment the doctor
 * identity table arrived: it exists precisely to give *historical* prescriptions
 * a stable doctor, and a destructive upgrade would delete the history it is meant
 * to attribute. So v1→v2 is a real migration.
 *
 * Room validates the resulting schema against the entity definitions when the
 * database opens, and a mismatch throws there rather than corrupting anything —
 * but it throws at runtime, on the user's device, and cannot be checked from
 * here. The DDL below therefore mirrors what Room generates for
 * [DoctorEntity] field by field, including the `INTEGER NOT NULL` on every
 * non-null Kotlin property and the index names, which Room derives from the
 * table and column.
 */
object Migrations {

    /**
     * Creates `doctors`, adds `prescriptions.doctor_id`, and backfills.
     *
     * The backfill is done in Kotlin over a cursor rather than in one `UPDATE ...
     * SELECT`, because the identity key has to be computed with exactly the same
     * rule the save path uses ([DoctorIdentity.keyOf]). Expressing that rule a
     * second time in SQL would mean two implementations of the thing the identity
     * depends on, and a migration whose notion of "same doctor" drifted from the
     * app's would split one doctor across two rows the first time a new
     * prescription was saved.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `doctors` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`identity_key` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`specialty` TEXT NOT NULL, " +
                    "`chamber` TEXT NOT NULL, " +
                    "`district` TEXT NOT NULL, " +
                    "`upazila` TEXT NOT NULL, " +
                    "`bmdc_no` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_doctors_identity_key` " +
                    "ON `doctors` (`identity_key`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_doctors_name` ON `doctors` (`name`)",
            )
            // Nullable and with no default: the column is added to a populated
            // table, and NOT NULL without a default would fail on every existing
            // row. Rows the backfill cannot attribute stay null, which is also what
            // the web's own `doctor_id` allows.
            db.execSQL("ALTER TABLE `prescriptions` ADD COLUMN `doctor_id` INTEGER")

            backfillDoctors(db)
        }
    }

    /**
     * Adds `doctors.territory`.
     *
     * The doctor profile is the join target for the tiering and leaderboard
     * queries, and they read specialty / district / territory off it rather than
     * off the prescription row. `territory` was missed in v2 and the queries fell
     * back to `prescriptions.territory`, which under a `GROUP BY doctor` is
     * whichever row SQLite happened to scan last — a value that could change
     * between two runs of the same query. A profile column is one value per
     * doctor, so it is both correct and stable.
     *
     * `NOT NULL DEFAULT ''` rather than nullable: `ALTER TABLE ADD COLUMN` cannot
     * add a NOT NULL column to a populated table without a default, and every
     * other text column on this entity is non-null with `''` standing in for
     * unknown. The web's own columns are nullable, but there `None` and `''` are
     * the same value to every reader — the queries all wrap them in `IFNULL`.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `doctors` ADD COLUMN `territory` TEXT NOT NULL DEFAULT ''",
            )
            backfillTerritory(db)
        }
    }

    /**
     * Fills `territory` from the most recent prescription that has one.
     *
     * The save path refreshes the profile on every scan from then on, so this is
     * only about making an existing database match what a fresh install would
     * have. Latest-first because the profile tracks the doctor's current
     * territory, not the first one ever recorded.
     *
     * A correlated subquery in one `UPDATE` would be shorter, but the rule above
     * is a statement about ordering that SQLite would have to re-derive per row;
     * a single pass with an in-memory map also keeps this consistent with
     * [backfillDoctors].
     */
    private fun backfillTerritory(db: SupportSQLiteDatabase) {
        val latest = HashMap<Long, String>()
        db.query(
            "SELECT doctor_id, territory FROM prescriptions " +
                "WHERE doctor_id IS NOT NULL ORDER BY created_at ASC",
        ).use { cursor ->
            val idIdx = cursor.getColumnIndex("doctor_id")
            val territoryIdx = cursor.getColumnIndex("territory")
            if (idIdx < 0 || territoryIdx < 0) return
            while (cursor.moveToNext()) {
                // Ascending order, so the last non-blank value seen for a doctor
                // is the one from their most recent prescription.
                val value = cursor.getString(territoryIdx)
                if (!value.isNullOrBlank()) latest[cursor.getLong(idIdx)] = value
            }
        }
        for ((doctorId, territory) in latest) {
            db.execSQL(
                "UPDATE `doctors` SET `territory` = ? WHERE `id` = ?",
                // Explicit type argument: the two bind values are a String and a
                // Long, and nothing here can confirm which common supertype Kotlin
                // would infer for them.
                arrayOf<Any?>(territory, doctorId),
            )
        }
    }

    /**
     * Attributes existing prescriptions to doctors.
     *
     * Reads the distinct doctor blocks, resolves each to an id, then writes the id
     * back onto the prescriptions it came from. The whole migration already runs
     * inside a transaction, so a failure here leaves the database at v1 rather
     * than half-migrated.
     *
     * Row by row rather than in bulk: the number of prescriptions on a handset is
     * small (this is a field officer's device, not the server), and a bulk path
     * would need the key rule in SQL again.
     */
    private fun backfillDoctors(db: SupportSQLiteDatabase) {
        // key -> doctors.id, so a doctor seen on twenty prescriptions is inserted
        // once and looked up in memory for the rest.
        val ids = HashMap<String, Long>()

        db.query(
            "SELECT id, doctor_name, doctor_bmdc_no, chamber, district, " +
                "doctor_specialty, upazila FROM prescriptions",
        ).use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val nameIdx = cursor.getColumnIndex("doctor_name")
            val bmdcIdx = cursor.getColumnIndex("doctor_bmdc_no")
            val chamberIdx = cursor.getColumnIndex("chamber")
            val districtIdx = cursor.getColumnIndex("district")
            val specialtyIdx = cursor.getColumnIndex("doctor_specialty")
            val upazilaIdx = cursor.getColumnIndex("upazila")
            // A schema where these are absent means the table does not look like
            // this migration's `from` version, so skip rather than write garbage.
            if (idIdx < 0 || nameIdx < 0 || bmdcIdx < 0 || chamberIdx < 0 ||
                districtIdx < 0 || specialtyIdx < 0 || upazilaIdx < 0
            ) {
                return
            }

            while (cursor.moveToNext()) {
                val prescriptionId = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx)
                val bmdc = cursor.getString(bmdcIdx)
                val chamber = cursor.getString(chamberIdx)
                val district = cursor.getString(districtIdx)
                val specialty = cursor.getString(specialtyIdx)
                val upazila = cursor.getString(upazilaIdx)

                val key = DoctorIdentity.keyOf(name, bmdc, chamber, district) ?: continue

                val doctorId = ids[key] ?: run {
                    db.execSQL(
                        "INSERT OR IGNORE INTO `doctors` " +
                            "(`identity_key`, `name`, `specialty`, `chamber`, " +
                            "`district`, `upazila`, `bmdc_no`) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            key,
                            DoctorIdentity.displayName(name),
                            specialty.orEmpty(),
                            chamber.orEmpty(),
                            district.orEmpty(),
                            upazila.orEmpty(),
                            bmdc.orEmpty(),
                        ),
                    )
                    db.query(
                        "SELECT `id` FROM `doctors` WHERE `identity_key` = ? LIMIT 1",
                        arrayOf(key),
                    ).use { idCursor ->
                        if (!idCursor.moveToFirst()) return@run null
                        idCursor.getLong(0)
                    }?.also { ids[key] = it }
                } ?: continue

                db.execSQL(
                    "UPDATE `prescriptions` SET `doctor_id` = ? WHERE `id` = ?",
                    arrayOf(doctorId, prescriptionId),
                )
            }
        }
    }
}

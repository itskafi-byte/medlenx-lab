package com.medlenx.lab.data.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.medlenx.lab.data.local.ScannedMedicineEntity
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

/** Preserve the officer's literal date; only an unambiguous ISO date gets a timestamp. */
internal object ScanCloudFields {
    fun timestamp(raw: String?): Timestamp? {
        val value = raw?.trim() ?: return null
        if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value)) return null
        return runCatching { Timestamp(Date(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())) }.getOrNull()
    }

    fun restoredDate(doc: DocumentSnapshot): String? =
        doc.getString("prescription_date_raw")?.takeIf { it.isNotBlank() }
            ?: (doc.get("prescription_date") as? Timestamp)?.toDate()?.toInstant()?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString()
            ?: (doc.get("prescription_date") as? Number)?.toLong()?.let { millis ->
                runCatching { java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString() }.getOrNull()
            }

    fun medicineMap(med: ScannedMedicineEntity): Map<String, Any?> = mapOf(
        "brand" to med.brandName, "generic" to med.generic, "confidence" to med.confidenceScore,
        "company" to med.companyName.orEmpty(), "medex_product_id" to med.medexProductId,
    )

    /** Old documents have no IDs. Compare the immutable original fields, in line order. */
    fun sameMedicines(raw: Any?, meds: List<ScannedMedicineEntity>, requireCompany: Boolean = true): Boolean {
        val cloud = raw as? List<*> ?: return false
        if (cloud.size != meds.size) return false
        return cloud.zip(meds).all { (value, med) ->
            val row = value as? Map<*, *> ?: return@all false
            val cloudId = row["medex_product_id"] as? String
            row["brand"] == med.brandName && row["generic"] == med.generic &&
                (row["confidence"] as? Number)?.toDouble() == med.confidenceScore &&
                (!requireCompany || row["company"] == med.companyName.orEmpty()) &&
                (cloudId == null || med.medexProductId == null || cloudId == med.medexProductId)
        }
    }

    fun compatibleMetadata(doc: DocumentSnapshot, date: String?, medicines: List<ScannedMedicineEntity>): Boolean {
        val rawDate = doc.get("prescription_date_raw")
        if (rawDate != null && rawDate !is String) return false
        val cloudDate = rawDate as? String
        if (!date.isNullOrBlank() && cloudDate != null && date != cloudDate) return false
        val timeValue = doc.get("prescription_date")
        if (timeValue != null && timeValue !is Timestamp && timeValue !is Number) return false
        val cloudMillis = when (timeValue) {
            is Timestamp -> timeValue.toDate().time
            is Number -> timeValue.toLong()
            else -> null
        }
        val localMillis = timestamp(date)?.toDate()?.time
        if (localMillis != null && cloudMillis != null && localMillis != cloudMillis) return false
        val raw = doc.get("medicines") as? List<*> ?: return false
        val parallel = doc.get("medex_product_ids")
        if (parallel != null && parseIds(parallel, raw.size) == null) return false
        val cloudIds = ids(doc, raw)
        if (cloudIds.size != medicines.size) return false
        return medicines.indices.all { i ->
            val local = medicines[i].medexProductId
            val remote = cloudIds[i]
            local.isNullOrBlank() || (remote == null && parallel == null) || local == remote
        }
    }

    fun encodeIds(medicines: List<ScannedMedicineEntity>): String = org.json.JSONArray().apply {
        medicines.forEach { put(it.medexProductId ?: org.json.JSONObject.NULL) }
    }.toString()

    private fun parseIds(value: Any?, expected: Int): List<String?>? = runCatching {
        val array = org.json.JSONArray(value as? String ?: return null)
        if (array.length() != expected || expected > 100) return null
        (0 until array.length()).map { i ->
            if (array.isNull(i)) null else (array.get(i) as? String ?: return null)
        }
    }.getOrNull()

    fun ids(doc: DocumentSnapshot, raw: List<*>): List<String?> {
        val backfill = parseIds(doc.get("medex_product_ids"), raw.size)
        return raw.mapIndexed { index, value ->
            val med = value as? Map<*, *>
            (med?.get("medex_product_id") as? String)?.takeIf { it.isNotBlank() }
                ?: (backfill?.get(index) as? String)?.takeIf { it.isNotBlank() }
        }
    }
}

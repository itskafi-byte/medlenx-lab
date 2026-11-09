package com.medlenx.lab.data.remote

import com.medlenx.lab.data.model.VlDoctor
import com.medlenx.lab.data.model.VlMedicine
import com.medlenx.lab.data.model.VlPatientInfo
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Remove only outer Markdown fences; backticks inside quoted JSON remain unchanged. */
internal object GeminiScanParser {
    private val openingFence = Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE)
    private val closingFence = Regex("\\s*```\\s*$")

    fun clean(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.replaceFirst(openingFence, "").replaceFirst(closingFence, "").trim()
    }

    /** Validates the complete metadata object and one result per selected crop. */
    fun parse(json: Json, text: String, expectedLines: Int): VlScanResult {
        val root = json.parseToJsonElement(clean(text)).jsonObject
        require(root.keys.containsAll(listOf("doctor", "patient_info", "prescription_date", "medicines")))
        val doc = root.getValue("doctor").jsonObject
        val doctorKeys = listOf("name", "bmdc_no", "qualifications", "hospital", "department", "specialty", "chamber", "district", "upazila", "territory")
        require(doc.keys.containsAll(doctorKeys) && doctorKeys.all { doc.getValue(it).jsonPrimitive.isString })
        val date = root.getValue("prescription_date").jsonPrimitive
        require(date.isString)
        val patient = root.getValue("patient_info").jsonObject
        require(patient.getValue("masked").jsonPrimitive.booleanOrNull == true && patient.getValue("note").jsonPrimitive.isString)
        val medicines = root.getValue("medicines").jsonArray
        require(medicines.size == expectedLines)
        val rows = medicines.map { element ->
            val row = element.jsonObject
            val brand = row.getValue("brand_name").jsonPrimitive
            val confidence = row.getValue("confidence").jsonPrimitive
            require(brand.isString && !confidence.isString)
            val score = confidence.doubleOrNull ?: error("Missing confidence")
            require(score.isFinite() && score in 0.0..1.0)
            VlMedicine(brandName = brand.content, rawText = brand.content, confidence = score)
        }
        return VlScanResult(doctor = json.decodeFromJsonElement(VlDoctor.serializer(), doc),
            prescriptionDate = date.content, medicines = rows,
            patientInfo = VlPatientInfo(masked = true, note = "Patient identifiers masked"))
    }
}

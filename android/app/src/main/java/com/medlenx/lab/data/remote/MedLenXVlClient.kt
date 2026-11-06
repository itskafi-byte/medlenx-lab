package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.FinishReason
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlDoctor
import com.medlenx.lab.data.model.VlMedicine
import com.medlenx.lab.data.model.VlPatientInfo
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed class VlOutcome {
    data class Success(val result: VlScanResult, val rawContent: String) : VlOutcome()
    data class Failure(val message: String, val retryable: Boolean = false) : VlOutcome()
    data object NoKey : VlOutcome()
}

/** Direct Gemini access embeds an extractable API key in the APK. */
class MedLenXVlClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
) {
    val hasKey get() = apiKey.isNotBlank()
    val displayName get() = "Gemini 2.5 Flash"

    suspend fun scanPrescription(bitmap: Bitmap, boxes: List<Rect>): VlOutcome = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext VlOutcome.NoKey
        if (boxes.isEmpty()) return@withContext VlOutcome.Failure("Select at least one medicine line.")
        val crops = mutableListOf<Bitmap>()
        try {
            for (box in boxes) {
                require(box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height && !box.isEmpty)
                crops += Bitmap.createBitmap(bitmap, box.left, box.top, box.right - box.left, box.bottom - box.top)
            }
            val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = resultSchema()
                })
            val response = model.generateContent(content {
                text(PROMPT)
                text("Full prescription image, for doctor and date metadata only:")
                image(bitmap)
                crops.forEachIndexed { index, crop ->
                    text("Selected medicine line ${index + 1}:")
                    image(crop)
                }
            })
            if (response.candidates.firstOrNull()?.finishReason != FinishReason.STOP)
                return@withContext VlOutcome.Failure("Gemini did not complete the scan. Please retry.")
            val text = response.text ?: return@withContext VlOutcome.Failure("Gemini returned no result. Please retry.")
            val root = json.parseToJsonElement(text).jsonObject
            require(root.keys.containsAll(listOf("doctor", "patient_info", "prescription_date", "medicines")))
            val doc = root.getValue("doctor").jsonObject
            val doctorKeys = listOf("name", "bmdc_no", "qualifications", "hospital", "department", "specialty", "chamber", "district", "upazila", "territory")
            require(doc.keys.containsAll(doctorKeys) && doctorKeys.all { doc.getValue(it).jsonPrimitive.isString })
            val date = root.getValue("prescription_date").jsonPrimitive
            require(date.isString)
            val patient = root.getValue("patient_info").jsonObject
            require(patient.getValue("masked").jsonPrimitive.booleanOrNull == true && patient.getValue("note").jsonPrimitive.isString)
            val medicines = root.getValue("medicines").jsonArray
            require(medicines.size == boxes.size)
            val rows = medicines.map { element ->
                val row = element.jsonObject
                val brand = row.getValue("brand_name").jsonPrimitive
                val confidence = row.getValue("confidence").jsonPrimitive
                require(brand.isString && !confidence.isString)
                val score = confidence.doubleOrNull ?: error("Missing confidence")
                require(score.isFinite() && score in 0.0..1.0)
                VlMedicine(brandName = brand.content, rawText = brand.content, confidence = score)
            }
            VlOutcome.Success(VlScanResult(doctor = json.decodeFromJsonElement(VlDoctor.serializer(), doc),
                prescriptionDate = date.content, medicines = rows,
                patientInfo = VlPatientInfo(masked = true, note = "Patient identifiers masked")), text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            VlOutcome.Failure("Not enough memory to prepare this image. Try a smaller photo.")
        } catch (_: Exception) {
            VlOutcome.Failure("Gemini could not complete the scan or returned an invalid result. Check your connection and retry.")
        } finally {
            crops.forEach { if (it !== bitmap) it.recycle() }
        }
    }

    companion object {
        private const val PROMPT = """You are an expert medical transcriptionist. The first image is the full original prescription. Extract doctor details and prescription date from that image only. Do not extract patient identifiers: patient_info must be masked and contain no identifying details. The remaining images are selected cropped lines from that prescription, in order. Read handwritten medicine names from the crops only, not from the full image. Return exactly one medicine object per crop, in the same order, with brand_name and confidence between 0 and 1. If illegible use an empty brand and confidence 0. Return all schema fields, empty strings where unknown. Never infer a manufacturer. Output JSON only."""
        private fun resultSchema(): Schema<out Any> {
            val keys = listOf("name", "bmdc_no", "qualifications", "hospital", "department", "specialty", "chamber", "district", "upazila", "territory")
            val doctor = Schema.obj("doctor", "Doctor metadata from the full image", *keys.map { Schema.str(it, it) }.toTypedArray())
            val patient = Schema.obj("patient_info", "Masked patient details", Schema.bool("masked", "Always true"), Schema.str("note", "No patient identifiers"))
            val medicine = Schema.obj("medicine", "One selected line", Schema.str("brand_name", "Transcribed name"), Schema.double("confidence", "Confidence from 0 to 1"))
            return Schema.obj("prescription", "Prescription result", doctor, patient,
                Schema.str("prescription_date", "Date as written or empty"), Schema.arr("medicines", "One per crop in order", medicine))
        }
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        fun defaultJson(): Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    }
}

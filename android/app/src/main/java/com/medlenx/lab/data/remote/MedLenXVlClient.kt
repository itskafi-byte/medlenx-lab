package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.types.Schema
import com.google.genai.kotlin.types.Type
import com.google.genai.kotlin.types.FinishReason
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.Part
import com.google.genai.kotlin.types.Blob
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.HttpOptions
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        try {
            fun imagePart(source: Bitmap, scaleFullImage: Boolean): Part {
                val bytes = ImagePrep.bitmapUploadJpeg(source, scaleFullImage)
                return Part(inlineData = Blob(data = bytes, mimeType = "image/jpeg"))
            }
            val parts = mutableListOf(
                Part(text = PROMPT),
                Part(text = "Full prescription image, for doctor and date metadata only:"),
                imagePart(bitmap, scaleFullImage = true),
            )
            boxes.forEachIndexed { index, box ->
                require(box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height && !box.isEmpty)
                val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.right - box.left, box.bottom - box.top)
                try {
                    parts += Part(text = "Selected medicine line ${index + 1}:")
                    parts += imagePart(crop, scaleFullImage = false)
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
            val response = Client(apiKey = apiKey, httpOptions = HttpOptions(timeout = 240_000)).use { client ->
                client.models.generateContent(model = "gemini-2.5-flash",
                    content = Content(role = "user", parts = parts),
                    config = GenerateContentConfig(responseMimeType = "application/json", responseSchema = resultSchema()))
            }
            if (response.finishReason != FinishReason.STOP)
                return@withContext VlOutcome.Failure("Gemini did not complete the scan: ${response.finishReason ?: "no finish reason"}.")
            val text = response.text ?: return@withContext VlOutcome.Failure("Gemini returned no result. Please retry.")
            val result = try {
                GeminiScanParser.parse(json, text, expectedLines = boxes.size)
            } catch (e: Exception) {
                Log.e("GeminiScan", "Response parsing error", e)
                return@withContext VlOutcome.Failure("Gemini response parsing error: ${e.localizedMessage ?: e.toString()}")
            }
            VlOutcome.Success(result = result, rawContent = text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e("GeminiScan", "Image preparation error", e)
            VlOutcome.Failure("Not enough memory to prepare this image. Try a smaller photo.")
        } catch (e: Exception) {
            Log.e("GeminiScan", "API Error", e)
            VlOutcome.Failure(e.localizedMessage ?: e.toString())
        }
    }

    companion object {
        private const val PROMPT = """You are an expert medical transcriptionist. The first image is the full original prescription. Extract doctor details and prescription date from that image only. Do not extract patient identifiers: patient_info must be masked and contain no identifying details. The remaining images are selected cropped lines from that prescription, in order. Read handwritten medicine names from the crops only, not from the full image. Return exactly one medicine object per crop, in the same order, with brand_name and confidence between 0 and 1. If illegible use an empty brand and confidence 0. Return all schema fields, empty strings where unknown. Never infer a manufacturer. Output JSON only."""
        private fun resultSchema(): Schema {
            fun obj(properties: Map<String, Schema>) = Schema(type = Type.OBJECT, properties = properties, required = properties.keys.toList())
            fun text() = Schema(type = Type.STRING)
            val keys = listOf("name", "bmdc_no", "qualifications", "hospital", "department", "specialty", "chamber", "district", "upazila", "territory")
            val doctor = obj(keys.associateWith { text() })
            val patient = obj(mapOf("masked" to Schema(type = Type.BOOLEAN), "note" to text()))
            val medicine = obj(mapOf("brand_name" to text(), "confidence" to Schema(type = Type.NUMBER)))
            return obj(mapOf("doctor" to doctor, "patient_info" to patient, "prescription_date" to text(),
                "medicines" to Schema(type = Type.ARRAY, items = medicine)))
        }
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        fun defaultJson(): Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    }
}

package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.FinishReason
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        // Decoded payload copies must live until the SDK has serialized the request.
        val payloadBitmaps = mutableListOf<Bitmap>()
        try {
            fun compressedBitmap(source: Bitmap, scaleFullImage: Boolean): Bitmap {
                val bytes = ImagePrep.bitmapUploadJpeg(source, scaleFullImage)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Could not decode compressed image")
                payloadBitmaps.add(decoded)
                return decoded
            }
            val fullImage = compressedBitmap(bitmap, scaleFullImage = true)
            val lineImages = boxes.map { box ->
                require(box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height && !box.isEmpty)
                val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.right - box.left, box.bottom - box.top)
                try {
                    compressedBitmap(crop, scaleFullImage = false)
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
            val generativeModel = GenerativeModel(
                modelName = "gemini-3.8-flash", apiKey = apiKey,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = resultSchema()
                },
                requestOptions = RequestOptions(timeout = 240_000L),
            )
            val response = generativeModel.generateContent(content {
                text(PROMPT)
                text("Full prescription image, for doctor and date metadata only:")
                image(fullImage)
                lineImages.forEachIndexed { index, crop ->
                    text("Selected medicine line ${index + 1}:")
                    image(crop)
                }
            })
            val finishReason = response.candidates.firstOrNull()?.finishReason
            if (finishReason != FinishReason.STOP)
                return@withContext VlOutcome.Failure("Gemini did not complete the scan: ${finishReason ?: "no finish reason"}.")
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
            // The legacy SDK can wrap cancellation in its own exception.
            currentCoroutineContext().ensureActive()
            Log.e("GeminiScan", "API Error", e)
            VlOutcome.Failure(e.localizedMessage ?: e.toString())
        } finally {
            payloadBitmaps.forEach { it.recycle() }
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

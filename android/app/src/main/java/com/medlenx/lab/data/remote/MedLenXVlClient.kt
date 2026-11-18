package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class VlOutcome {
    data class Success(val result: VlScanResult, val rawContent: String) : VlOutcome()
    data class Failure(val message: String, val retryable: Boolean = false) : VlOutcome()
    data object NoKey : VlOutcome()
}

/** Direct OpenRouter access embeds an extractable API key in the APK. */
class MedLenXVlClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY,
) {
    val hasKey get() = apiKey.isNotBlank()
    val displayName get() = "Nemotron 3.5 Super VL"

    suspend fun scanPrescription(bitmap: Bitmap, boxes: List<Rect>): VlOutcome = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext VlOutcome.NoKey
        if (boxes.isEmpty()) return@withContext VlOutcome.Failure("Select at least one medicine line.")
        try {
            fun encode(source: Bitmap, scaleFullImage: Boolean): String = Base64.encodeToString(
                ImagePrep.bitmapUploadJpeg(source, scaleFullImage), Base64.NO_WRAP,
            )
            val fullImage = encode(bitmap, scaleFullImage = true)
            val lineImages = boxes.map { box ->
                require(box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height && !box.isEmpty)
                val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.right - box.left, box.bottom - box.top)
                try {
                    encode(crop, scaleFullImage = false)
                } finally {
                    if (crop !== bitmap) crop.recycle()
                }
            }
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://medlenx.com")
                .header("X-Title", "MedLenX Lab")
                .post(OpenRouterScanProtocol.requestBody(fullImage, lineImages).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val (code, body) = httpClient.newCall(request).awaitBody()
            val problem = OpenRouterErrors.fromResponse(json, body, code)
            if (problem != null) return@withContext VlOutcome.Failure(problem.message, problem.retryable)
            val text = OpenRouterScanProtocol.assistantText(json, body)
            val result = try {
                GeminiScanParser.parse(json, text, expectedLines = boxes.size)
            } catch (e: Exception) {
                Log.e("OpenRouterScan", "Response parsing error", e)
                return@withContext VlOutcome.Failure("OpenRouter response parsing error: ${e.localizedMessage ?: e.toString()}")
            }
            VlOutcome.Success(result = result, rawContent = text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e("OpenRouterScan", "Image preparation error", e)
            VlOutcome.Failure("Not enough memory to prepare this image. Try a smaller photo.")
        } catch (e: Exception) {
            Log.e("OpenRouterScan", "API Error", e)
            VlOutcome.Failure(e.localizedMessage ?: e.toString())
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        fun defaultJson(): Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    }
}

/** JSON contract for the OpenAI-compatible multimodal chat endpoint. */
internal object OpenRouterScanProtocol {
    private const val MODEL = "nvidia/nemotron-3.5-super-vl:free"
    private const val PROMPT = """You are an expert medical transcriptionist. The first image is the full original prescription. Extract doctor details and prescription date from that image only. Do not extract patient identifiers: patient_info must be masked and contain no identifying details. The remaining images are selected cropped lines from that prescription, in order. Read handwritten medicine names from the crops only, not from the full image. Return exactly one medicine object per crop, in the same order, with brand_name and confidence between 0 and 1. If illegible use an empty brand and confidence 0. Return all fields, empty strings where unknown. Never infer a manufacturer. Output only a JSON object with this exact structure: {"doctor":{"name":"","bmdc_no":"","qualifications":"","hospital":"","department":"","specialty":"","chamber":"","district":"","upazila":"","territory":""},"patient_info":{"masked":true,"note":"Patient identifiers masked"},"prescription_date":"","medicines":[{"brand_name":"","confidence":0}]}"""

    fun requestBody(fullImage: String, lineImages: List<String>): JsonObject {
        fun textPart(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
        fun imagePart(data: String) = buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$data") })
        }
        val content = buildJsonArray {
            add(textPart(PROMPT))
            add(textPart("Full prescription image, for doctor and date metadata only:"))
            add(imagePart(fullImage))
            lineImages.forEachIndexed { index, image ->
                add(textPart("Selected medicine line ${index + 1}:"))
                add(imagePart(image))
            }
        }
        return buildJsonObject {
            put("model", MODEL)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", content) }) })
        }
    }

    fun assistantText(json: Json, body: String): String {
        val root = json.parseToJsonElement(body) as? JsonObject ?: error("OpenRouter returned an invalid response")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: error("OpenRouter returned no choices")
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        check(finish == null || finish == "stop") { "OpenRouter did not complete the scan: $finish" }
        val message = choice["message"] as? JsonObject ?: error("OpenRouter returned no message")
        val text = message["content"] as? JsonPrimitive ?: error("OpenRouter returned no text")
        check(text.isString && text.content.isNotBlank()) { "OpenRouter returned no text" }
        return text.content
    }
}

/** Close the response and cancel the underlying request when the coroutine is cancelled. */
private suspend fun Call.awaitBody(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { it.code to (it.body?.string() ?: "") }
                continuation.resume(result)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    })
}

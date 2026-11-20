package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private fun redact(value: String): String = if (apiKey.isBlank()) value else value.replace(apiKey, "[REDACTED]")
    val hasKey get() = apiKey.isNotBlank()
    val displayName get() = "Gemma 4 31B / Nemotron 3.5 Super VL"

    suspend fun scanPrescription(bitmap: Bitmap): VlOutcome = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext VlOutcome.NoKey
        try {
            val fullImage = Base64.encodeToString(ImagePrep.bitmapUploadJpeg(bitmap), Base64.NO_WRAP)
            val payload = OpenRouterScanProtocol.requestBody(fullImage)
            requestWithFallback(payload, onPrimaryFailure = { _ ->
                // Do not log request images or raw provider response bodies.
                Log.e("OpenRouterScan", "Primary model failed; trying free fallback")
            }) { modelPayload -> requestModel(modelPayload) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e("OpenRouterScan", "Image preparation error", e)
            VlOutcome.Failure("Not enough memory to prepare this image. Try a smaller photo.")
        } catch (e: Exception) {
            val message = redact(e.localizedMessage ?: e.toString())
            Log.e("OpenRouterScan", "Image preparation error: $message")
            VlOutcome.Failure(message)
        }
    }

    internal suspend fun requestModel(payload: JsonObject): VlOutcome {
        try {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://medlenx.com")
                .header("X-Title", "MedLenX Lab")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = httpClient.newCall(request).awaitBody()
            val problem = OpenRouterErrors.fromResponse(json, response.body, response.code)
            if (!response.isSuccessful || problem != null) {
                val providerMessage = OpenRouterErrors.providerMessage(json, response.body)
                val redacted = providerMessage?.let(::redact)
                Log.e("OpenRouterScan", "HTTP ${response.code}; OpenRouter error.message: ${redacted ?: "(not provided)"}")
                return VlOutcome.Failure(problem?.message ?: "OpenRouter request failed (HTTP ${response.code})", problem?.retryable ?: false)
            }
            return try {
                val text = OpenRouterScanProtocol.assistantText(json, response.body)
                VlOutcome.Success(result = GeminiScanParser.parse(json, text), rawContent = text)
            } catch (e: Exception) {
                Log.e("OpenRouterScan", "HTTP ${response.code}; invalid scan response")
                VlOutcome.Failure("OpenRouter returned an invalid scan response (HTTP ${response.code}).")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = redact(e.localizedMessage ?: e.toString())
            Log.e("OpenRouterScan", "API Error: $message")
            return VlOutcome.Failure(message)
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
    const val PRIMARY_MODEL = "google/gemma-4-31b-it:free"
    const val FALLBACK_MODEL = "nvidia/nemotron-3.5-super-vl:free"
    private const val PROMPT = """Read this full prescription page. Extract all prescribed medicine lines under Rx in top-to-bottom order; ignore clinic headers, patient labels and doctor letterhead. Never output patient identifiers and never infer a manufacturer. Use empty strings for unknown text and 0 confidence for illegible names. Return only a JSON object with every field shown, and a medicines array of any length: {"doctor":{"name":"","bmdc_no":"","qualifications":"","hospital":"","department":"","specialty":"","chamber":"","district":"","upazila":"","territory":""},"patient_info":{"masked":true,"note":"Patient identifiers masked"},"prescription_date":"","medicines":[{"brand_name":"","form":"","strength":"","frequency":"","duration":"","confidence":0.0}]}. Confidence must be between 0.0 and 1.0. Read only what is visible."""

    fun requestBody(fullImage: String): JsonObject = buildJsonObject {
        put("model", PRIMARY_MODEL)
        put("temperature", 0.1)
        put("max_tokens", 4096)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", PROMPT) })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$fullImage") })
                    })
                })
            })
        })
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

/** Retry once with the same content and the explicitly free fallback model. */
internal suspend fun requestWithFallback(
    payload: JsonObject,
    onPrimaryFailure: (VlOutcome.Failure) -> Unit,
    attempt: suspend (JsonObject) -> VlOutcome,
): VlOutcome {
    suspend fun safeAttempt(body: JsonObject): VlOutcome = try {
        attempt(body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VlOutcome.Failure(e.localizedMessage ?: e.toString())
    }
    val primary = safeAttempt(payload)
    if (primary !is VlOutcome.Failure) return primary
    onPrimaryFailure(primary)
    delay(2500) // Cancellable pause; a rate limit may still apply afterwards.
    val fallbackPayload = JsonObject(payload + ("model" to JsonPrimitive(OpenRouterScanProtocol.FALLBACK_MODEL)))
    val fallback = safeAttempt(fallbackPayload)
    return if (fallback is VlOutcome.Failure) {
        VlOutcome.Failure("Primary: ${primary.message} Fallback: ${fallback.message}", fallback.retryable)
    } else fallback
}

private data class HttpResult(val code: Int, val isSuccessful: Boolean, val body: String)

/** Close the response and cancel the underlying request when the coroutine is cancelled. */
private suspend fun Call.awaitBody(): HttpResult = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { HttpResult(it.code, it.isSuccessful, it.body?.string() ?: "") }
                continuation.resume(result)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    })
}

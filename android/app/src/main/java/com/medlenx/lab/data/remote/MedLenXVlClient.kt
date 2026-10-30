package com.medlenx.lab.data.remote

import android.util.Base64
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Outcome of a single MedLenX VL call. */
sealed class VlOutcome {
    data class Success(val result: VlScanResult, val rawContent: String) : VlOutcome()
    data class Failure(val message: String) : VlOutcome()

    /** No API key configured — mirrors the web app's demo mode. */
    data object NoKey : VlOutcome()
}

/**
 * MedLenX VL client.
 *
 * Direct Gemini API client for personal testing only. The key embedded in
 * BuildConfig can be extracted from the APK; use a backend before distribution.
 */
class MedLenXVlClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val baseUrl: String = BuildConfig.GEMINI_BASE_URL,
    private val primaryModel: String = BuildConfig.VL_MODEL_PRIMARY,
) {

    val hasKey: Boolean get() = apiKey.isNotBlank()

    /** Display name shown in the UI — "MedLenX VL 1.0-Pro". */
    val displayName: String get() = "${BuildConfig.VL_DISPLAY_NAME} ${BuildConfig.VL_VERSION}"

    /**
     * Reads a Bangladeshi prescription image.
     *
     * @param imageBytes JPEG/PNG/WEBP bytes from the camera or gallery.
     * @param mimeType MIME type of the image sent to Gemini.
     */
    suspend fun scanPrescription(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
    ): VlOutcome = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext VlOutcome.NoKey

        // Shrink before encoding: a raw 12MP capture is a multi-megabyte base64 body.
        val (uploadBytes, uploadMime) = prepareUpload(imageBytes, mimeType)

        call(uploadBytes, uploadMime, primaryModel)
    }

    /** Re-encodes for upload, keeping the original bytes if the image cannot be decoded. */
    private fun prepareUpload(bytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
        val prepared = ImagePrep.toUploadJpeg(bytes)
        return if (prepared != null) prepared to "image/jpeg" else bytes to mimeType
    }

    private suspend fun call(
        imageBytes: ByteArray,
        mimeType: String,
        model: String,
    ): VlOutcome {
        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", SYSTEM_PROMPT) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", USER_PROMPT) })
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mimeType)
                                put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("thinkingConfig", buildJsonObject { put("thinkingLevel", "high") })
            })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use VlOutcome.Failure("Gemini request failed (HTTP ${response.code}). Check API access, billing, and quota.")
                }
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@use VlOutcome.Failure("Gemini returned no scan result; try another image.")
                val reason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
                if (reason != "STOP") {
                    return@use VlOutcome.Failure("Gemini could not complete the scan (${reason ?: "unknown reason"}). Retry.")
                }
                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                val text = parts?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("").orEmpty()
                if (text.isBlank()) VlOutcome.Failure("Gemini returned an empty scan result.")
                else parseScanJson(text)
            }
        }.getOrElse { VlOutcome.Failure("Gemini request failed. Check your connection and try again.") }
    }

    /**
     * The model sometimes wraps its JSON in a markdown fence despite being told not
     * to, so strip fences and grab the outermost object before decoding.
     */
    private fun parseScanJson(text: String): VlOutcome {
        val cleaned = text
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return VlOutcome.Failure("MedLenX VL did not return JSON")
        }
        return runCatching {
            VlOutcome.Success(
                result = json.decodeFromString(VlScanResult.serializer(), cleaned.substring(start, end + 1)),
                rawContent = text,
            )
        }.getOrElse { VlOutcome.Failure("Could not parse MedLenX VL output: ${it.message}") }
    }

    companion object {

        /** Verbatim from MedLenXVLClient.scan_prescription_pure(). */
        private const val SYSTEM_PROMPT =
            "You are MedLenX VL, expert Bangladeshi prescription reader for MR field work.\n" +
                "You handle Bengali/English mixed prescriptions, extract BMDC numbers, and mask patient PII per compliance.\n" +
                "Output valid JSON only."

        /** Verbatim from MedLenXVLClient.scan_prescription_pure(). */
        private const val USER_PROMPT = """
Analyze this Bangladeshi prescription image - mobile capture may have rotation/contrast issues.

Extract per new design spec:

1. DOCTOR BLOCK (top, printed):
   - name, qualifications, hospital, department, specialty (Cardiology, Medicine, Orthopedics etc)
   - bmdc_no / BMDC Registration No (e.g., A-12345, format letter + numbers)
   - chamber (clinic name), district, upazila, territory

2. MEDICINES (Rx onwards, handwritten cursive, English brand + Bengali dosage):
   - brand_name: Trade name exact (e.g., Napa, Seclo, Sergel, Ace)
   - generic_name: Chemical generic (e.g., Paracetamol, Omeprazole)
   - raw_text: Full line as seen (include Bengali if present like ১+০+১)
   - form: Tab/Cap/Syr/Inj etc
   - type: Tablet/Capsule/Syrup/Injection/Cream/Drop/Inhaler/Suppository/Powder etc
   - strength: 20mg, 500mg, 10ml
   - dosage: Extract dosage pattern - can be English 1+0+1 or Bengali ১+০+১ or ১ চামচ করে - keep original in dosage_bengali, then normalize to English in dosage_normalized (০-৯ → 0-9)
   - dosage_bengali: Original Bengali if present
   - dosage_normalized: Converted to English 1+0+1
   - frequency: Before meal, After meal, At night etc
   - company: ONLY if the manufacturer is literally printed/written on the
     prescription next to that medicine. DO NOT guess or infer the company from
     the brand name - the server resolves the manufacturer from the official
     MedEx catalogue. If it is not written on the paper, return "" (empty string).
   - confidence: 0-1
   - bbox: normalised [x, y, w, h] (each 0..1) bounding box of this medicine's
     handwritten line on the image, so the app can highlight it while editing

3. Patient PII: DO NOT extract patient name, age, phone - mask per BMDC compliance, note "masked"

4. Bengali handling: ১=1, ২=2, ৩=3, ৪=4, ৫=5, ৬=6, ৭=7, ৮=8, ৯=9, ০=0, চামচ=spoon

Return ONLY JSON:
{
  "doctor": {
    "name": "Dr. A. K. M. Rahman",
    "bmdc_no": "A-12345",
    "qualifications": "MBBS, FCPS...",
    "hospital": "Dhaka Medical College Hospital",
    "department": "Medicine",
    "specialty": "Medicine",
    "chamber": "Popular Diagnostic, Dhanmondi",
    "district": "Dhaka",
    "upazila": "Dhanmondi",
    "territory": "Dhaka South"
  },
  "medicines": [
    {
      "brand_name": "Seclo",
      "generic_name": "Omeprazole",
      "raw_text": "Cap. Seclo 20mg - ১+০+১ - 1 month",
      "form": "Cap",
      "type": "Capsule",
      "strength": "20mg",
      "dosage": "1+0+1",
      "dosage_bengali": "১+০+১",
      "dosage_normalized": "1+0+1",
      "frequency": "Before meal",
      "company": "",
      "confidence": 0.92
    }
  ],
  "patient_info": {"masked": true, "note": "Patient PII masked per BMDC compliance"},
  "meta": {"total_medicines": 1, "legibility": 0.8, "language_mix": "English+Bengali ১+০+১"}
}

IMPORTANT: never invent a pharmaceutical company. Leave "company" empty unless it
is actually printed on the prescription. An empty company is correct and useful;
a guessed company is a data error.

Output JSON only, no markdown.
"""

        /** OkHttp tuned for a 120s vision-model round trip, as in the Python client. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

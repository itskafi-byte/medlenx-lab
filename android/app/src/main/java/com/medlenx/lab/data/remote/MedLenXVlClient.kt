package com.medlenx.lab.data.remote

import android.util.Base64
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.remote.dto.ChatMessage
import com.medlenx.lab.data.remote.dto.ChatRequest
import com.medlenx.lab.data.remote.dto.ChatResponse
import com.medlenx.lab.data.remote.dto.ImagePart
import com.medlenx.lab.data.remote.dto.ImageUrl
import com.medlenx.lab.data.remote.dto.TextPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
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
 * Direct Kotlin port of app/medlenx_client.py. The system prompt, the user prompt,
 * the request headers, max_tokens and temperature are copied verbatim so the Android
 * app produces byte-identical requests to the web backend.
 *
 * SECURITY: BuildConfig.OPENROUTER_API_KEY is compiled into the APK and is extractable
 * by anyone who unpacks it. Use a rate-limited key, or proxy through your own server
 * before shipping publicly.
 */
class MedLenXVlClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY,
    private val baseUrl: String = BuildConfig.OPENROUTER_BASE_URL,
    private val primaryModel: String = BuildConfig.VL_MODEL_PRIMARY,
    // build.gradle declared VL_MODEL_FALLBACK but nothing ever read it, so a rate
    // limit or timeout on the big model failed the read outright.
    private val fallbackModel: String = BuildConfig.VL_MODEL_FALLBACK,
) {

    val hasKey: Boolean get() = apiKey.isNotBlank()

    /** Display name shown in the UI — "MedLenX VL 1.0-Pro". */
    val displayName: String get() = "${BuildConfig.VL_DISPLAY_NAME} ${BuildConfig.VL_VERSION}"

    /**
     * Reads a Bangladeshi prescription image.
     *
     * @param imageBytes JPEG/PNG/WEBP bytes from the camera or gallery.
     * @param mimeType used to build the `data:` URI exactly as the Python client does.
     */
    suspend fun scanPrescription(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
    ): VlOutcome = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext VlOutcome.NoKey

        // Shrink before encoding: a raw 12MP capture is a multi-megabyte base64 body.
        val (uploadBytes, uploadMime) = prepareUpload(imageBytes, mimeType)

        val first = call(uploadBytes, uploadMime, primaryModel)

        // Retry once on the smaller model. The 235B endpoint rate-limits and times
        // out far more readily than the 30B one, and a failed read costs the officer
        // the whole scan. Only a failure is retried - a successful but empty read is
        // a real answer and is returned as-is.
        if (first is VlOutcome.Failure &&
            fallbackModel.isNotBlank() &&
            fallbackModel != primaryModel
        ) {
            val retry = call(uploadBytes, uploadMime, fallbackModel)
            if (retry !is VlOutcome.Failure) return@withContext retry
        }
        return@withContext first
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
        val dataUri = "data:$mimeType;base64," +
            Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val content = buildJsonArray {
            add(json.encodeToJsonElement(ImagePart(imageUrl = ImageUrl(url = dataUri))))
            add(json.encodeToJsonElement(TextPart(text = USER_PROMPT)))
        }

        val messages = listOf(
            ChatMessage(role = "system", content = json.encodeToJsonElement(SYSTEM_PROMPT)),
            ChatMessage(role = "user", content = content),
        )

        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages,
                maxTokens = 4000,
                temperature = 0.1,
            ),
        )

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            // Same attribution headers the Python client sends.
            .addHeader("HTTP-Referer", "https://medlenx-lab.local")
            .addHeader("X-Title", "MedLenX Lab")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use VlOutcome.Failure("MedLenX VL HTTP ${response.code}: ${body.take(200)}")
                }
                val parsed = json.decodeFromString(ChatResponse.serializer(), body)
                parsed.error?.let { return@use VlOutcome.Failure(it.message) }
                val choice = parsed.choices.firstOrNull()
                val text = choice?.message?.content.orEmpty()
                if (text.isBlank()) {
                    VlOutcome.Failure("MedLenX VL returned an empty completion")
                } else {
                    val outcome = parseScanJson(text)
                    // A reply cut off at max_tokens can decode to a valid object that
                    // simply ends after the doctor block - medicines silently empty.
                    // Surface that instead of handing the officer a blank panel.
                    if (outcome is VlOutcome.Success &&
                        choice?.finishReason == "length" &&
                        outcome.result.medicines.isEmpty()
                    ) {
                        VlOutcome.Failure(
                            "The model reply was cut off before the medicine list. Retry."
                        )
                    } else {
                        outcome
                    }
                }
            }
        }.getOrElse { VlOutcome.Failure(it.message ?: "MedLenX VL request failed") }
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
      "company": "Square Pharmaceuticals Ltd.",
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

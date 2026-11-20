package com.medlenx.lab.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedLenXVlFallbackHttpTest {
    @Test fun httpFailuresTriggerFallbackWithSamePayloadAndHeaders() = runBlocking {
        val content = """{"doctor":{"name":"","bmdc_no":"","qualifications":"","hospital":"","department":"","specialty":"","chamber":"","district":"","upazila":"","territory":""},"patient_info":{"masked":true,"note":""},"prescription_date":"","medicines":[{"brand_name":"Napa","form":"","strength":"","frequency":"","duration":"","confidence":0.9}]}"""
        val successBody = """{"choices":[{"finish_reason":"stop","message":{"content":${JsonPrimitive(content)}}}]}"""
        for (status in listOf(403, 422, 429, 503)) {
            val payloads = mutableListOf<JsonObject>()
            val headers = mutableListOf<List<String?>>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                headers.add(listOf(request.header("Authorization"), request.header("HTTP-Referer"), request.header("X-Title")))
                val buffer = Buffer()
                checkNotNull(request.body).writeTo(buffer)
                payloads.add(Json.parseToJsonElement(buffer.readUtf8()) as JsonObject)
                val code = if (payloads.size == 1) status else 200
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .body((if (code == 200) successBody else "{}").toResponseBody("application/json".toMediaType())).build()
            }.build()
            val client = MedLenXVlClient(http, Json, "test-key")
            val payload = MedLenXVlScanProtocol.requestBody("FULL")
            val result = requestWithFallback(payload, {}) { client.requestModel(it) }
            assertTrue(result is VlOutcome.Success)
            assertEquals(2, payloads.size)
            assertEquals(listOf("Bearer test-key", "https://medlenx.com", "MedLenX Lab"), headers[0])
            assertEquals(headers[0], headers[1])
            assertEquals(MedLenXVlScanProtocol.PRIMARY_MODEL, payloads[0].getValue("model").jsonPrimitive.content)
            assertEquals(MedLenXVlScanProtocol.FALLBACK_MODEL, payloads[1].getValue("model").jsonPrimitive.content)
            assertEquals(payloads[0].getValue("messages"), payloads[1].getValue("messages"))
        }
    }
}

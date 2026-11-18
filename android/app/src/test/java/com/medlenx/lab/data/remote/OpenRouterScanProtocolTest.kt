package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterScanProtocolTest {
    @Test fun multimodalPayloadKeepsFullImageThenOrderedCrops() {
        assertEquals("meta-llama/llama-3.2-11b-vision-instruct:free", OpenRouterScanProtocol.FALLBACK_MODEL)
        val body = OpenRouterScanProtocol.requestBody("FULL", listOf("FIRST", "SECOND"))
        assertEquals("google/gemma-4-31b-it:free", body.getValue("model").jsonPrimitive.content)
        val message = body.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        val parts = message.getValue("content").jsonArray
        assertEquals(4, parts.size)
        assertEquals(setOf("model", "messages"), body.keys)
        assertEquals(setOf("role", "content"), message.keys)
        assertEquals(setOf("type", "text"), parts[0].jsonObject.keys)
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("prescription_date"))
        parts.drop(1).forEach { part ->
            assertEquals(setOf("type", "image_url"), part.jsonObject.keys)
            assertEquals(setOf("url"), part.jsonObject.getValue("image_url").jsonObject.keys)
        }
        val urls = parts.filter { it.jsonObject.getValue("type").jsonPrimitive.content == "image_url" }
            .map { it.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content }
        assertEquals(listOf("data:image/jpeg;base64,FULL", "data:image/jpeg;base64,FIRST", "data:image/jpeg;base64,SECOND"), urls)
    }

    @Test fun primaryFailureRetriesIdenticalContentWithFallback() = runBlocking {
        for (code in listOf(422, 429, 503)) {
            val payload = OpenRouterScanProtocol.requestBody("FULL", listOf("CROP"))
            val calls = mutableListOf<kotlinx.serialization.json.JsonObject>()
            var logged = false
            val success = VlOutcome.Success(VlScanResult(), "{}")
            val result = requestWithFallback(payload, { logged = true }) { request ->
                calls.add(request)
                if (calls.size == 1) VlOutcome.Failure("HTTP $code") else success
            }
            assertEquals(success, result)
            assertTrue(logged)
            assertEquals(2, calls.size)
            assertEquals(OpenRouterScanProtocol.PRIMARY_MODEL, calls[0].getValue("model").jsonPrimitive.content)
            assertEquals(OpenRouterScanProtocol.FALLBACK_MODEL, calls[1].getValue("model").jsonPrimitive.content)
            assertEquals(calls[0].getValue("messages"), calls[1].getValue("messages"))
        }
    }

    @Test fun successDoesNotRetryAndExceptionsDo() = runBlocking {
        val payload = OpenRouterScanProtocol.requestBody("FULL", listOf("CROP"))
        val success = VlOutcome.Success(VlScanResult(), "{}")
        var calls = 0
        assertEquals(success, requestWithFallback(payload, {}) { calls++; success })
        assertEquals(1, calls)
        calls = 0
        val failed = requestWithFallback(payload, {}) {
            calls++
            if (calls == 1) throw java.io.IOException("connection failed")
            VlOutcome.Failure("fallback failed")
        }
        assertEquals(2, calls)
        assertTrue((failed as VlOutcome.Failure).message.contains("fallback failed"))
    }

    @Test fun cancellationNeverStartsFallback() = runBlocking {
        val payload = OpenRouterScanProtocol.requestBody("FULL", listOf("CROP"))
        var calls = 0
        try {
            requestWithFallback(payload, {}) { calls++; throw CancellationException("cancelled") }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationDuringFallbackDelayNeverStartsFallback() = runBlocking {
        val payload = OpenRouterScanProtocol.requestBody("FULL", listOf("CROP"))
        val primaryFailed = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            requestWithFallback(payload, { primaryFailed.complete(Unit) }) {
                calls++
                VlOutcome.Failure("primary failed")
            }
        }
        primaryFailed.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun extractsResponseTextWithoutChangingFencedJson() {
        val content = "```json\n{\"ok\":true}\n```"
        val body = """{"choices":[{"finish_reason":"stop","message":{"content":${JsonPrimitive(content)}}}]}"""
        assertEquals(content, OpenRouterScanProtocol.assistantText(Json, body))
    }

    @Test fun passesExtractedFencedResponseToExistingParser() {
        val content = """```json
{"doctor":{"name":"","bmdc_no":"","qualifications":"","hospital":"","department":"","specialty":"","chamber":"","district":"","upazila":"","territory":""},"patient_info":{"masked":true,"note":"masked"},"prescription_date":"","medicines":[{"brand_name":"Napa","confidence":0.9}]}
```"""
        val body = """{"choices":[{"finish_reason":"stop","message":{"content":${JsonPrimitive(content)}}}]}"""
        val parsed = GeminiScanParser.parse(Json, OpenRouterScanProtocol.assistantText(Json, body), expectedLines = 1)
        assertEquals("Napa", parsed.medicines.single().brandName)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsTruncatedResponse() {
        OpenRouterScanProtocol.assistantText(Json, """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""")
    }
}

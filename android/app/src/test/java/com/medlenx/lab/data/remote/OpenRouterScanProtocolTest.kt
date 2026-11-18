package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterScanProtocolTest {
    @Test fun multimodalPayloadKeepsFullImageThenOrderedCrops() {
        val body = OpenRouterScanProtocol.requestBody("FULL", listOf("FIRST", "SECOND"))
        assertEquals("nvidia/nemotron-3.5-super-vl:free", body.getValue("model").jsonPrimitive.content)
        val message = body.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        val parts = message.getValue("content").jsonArray
        assertEquals(7, parts.size)
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("prescription_date"))
        val urls = parts.filter { it.jsonObject.getValue("type").jsonPrimitive.content == "image_url" }
            .map { it.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content }
        assertEquals(listOf("data:image/jpeg;base64,FULL", "data:image/jpeg;base64,FIRST", "data:image/jpeg;base64,SECOND"), urls)
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

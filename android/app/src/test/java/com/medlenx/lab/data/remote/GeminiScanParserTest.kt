package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiScanParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val response = """{"doctor":{"name":"Dr A","bmdc_no":"","qualifications":"","hospital":"","department":"","specialty":"","chamber":"","district":"","upazila":"","territory":""},"patient_info":{"masked":true,"note":""},"prescription_date":"","medicines":[{"brand_name":"Napa","form":"tablet","strength":"500mg","frequency":"twice daily","duration":"5 days","confidence":0.9}]}"""

    @Test fun parsesPlainAndFencedJson() {
        for (text in listOf(response, "```json\n$response\n```", "  ```JSON\r\n$response\r\n```  ", "```\n$response\n```")) {
            assertEquals("Napa", GeminiScanParser.parse(json, text).medicines.single().brandName)
        }
    }

    @Test fun preservesBackticksInsideJsonStrings() {
        val quotedTicks = response.replace("Napa", "Napa ``` note")
        assertEquals("Napa ``` note", GeminiScanParser.parse(json, "```json\n$quotedTicks\n```").medicines.single().brandName)
    }

    @Test fun invalidAndIncompleteResponsesAreRejected() {
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, "```json\n{\n```") }
        assertEquals(0, GeminiScanParser.parse(json, response.replace(Regex("\\[\\{.*?\\}\\]"), "[]")).medicines.size)
        assertEquals("twice daily; 5 days", GeminiScanParser.parse(json, response).medicines.single().frequency)
        val second = """},{"brand_name":"Second","form":"","strength":"","frequency":"","duration":"","confidence":0.3}"""
        val two = response.replace("0.9}]", "0.9$second]")
        assertEquals(listOf("Napa", "Second"), GeminiScanParser.parse(json, two).medicines.map { it.brandName })
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response.replace("\"bmdc_no\":\"\",", "")) }
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response.replace("\"form\":\"tablet\",", "")) }
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response.replace("0.9", "1.5")) }
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response.replace("\"masked\":true", "\"masked\":false")) }
    }

    @Test fun uploadSizingPreservesAspectAndDoesNotEnlarge() {
        assertEquals(1280 to 960, ImagePrep.uploadDimensions(4000, 3000))
        assertEquals(960 to 1280, ImagePrep.uploadDimensions(3000, 4000))
        assertEquals(1280 to 64, ImagePrep.uploadDimensions(4000, 200))
        assertEquals(100 to 200, ImagePrep.uploadDimensions(100, 200))
        assertEquals(1 to 1280, ImagePrep.uploadDimensions(1, 5000))
    }
}

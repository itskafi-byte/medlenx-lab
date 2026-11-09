package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiScanParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val response = """{"doctor":{"name":"Dr A","bmdc_no":"","qualifications":"","hospital":"","department":"","specialty":"","chamber":"","district":"","upazila":"","territory":""},"patient_info":{"masked":true,"note":""},"prescription_date":"","medicines":[{"brand_name":"Napa","confidence":0.9}]}"""

    @Test fun parsesPlainAndFencedJson() {
        for (text in listOf(response, "```json\n$response\n```", "  ```JSON\r\n$response\r\n```  ", "```\n$response\n```")) {
            assertEquals("Napa", GeminiScanParser.parse(json, text, 1).medicines.single().brandName)
        }
    }

    @Test fun preservesBackticksInsideJsonStrings() {
        val quotedTicks = response.replace("Napa", "Napa ``` note")
        assertEquals("Napa ``` note", GeminiScanParser.parse(json, "```json\n$quotedTicks\n```", 1).medicines.single().brandName)
    }

    @Test fun invalidAndIncompleteResponsesAreRejected() {
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, "```json\n{\n```", 1) }
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response, 2) }
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response.replace("0.9", "1.5"), 1) }
        assertThrows(Exception::class.java) { GeminiScanParser.parse(json, response.replace("\"masked\":true", "\"masked\":false"), 1) }
    }

    @Test fun fullImageSizingPreservesAspectAndDoesNotEnlarge() {
        assertEquals(1600 to 1200, ImagePrep.uploadDimensions(4000, 3000))
        assertEquals(1200 to 1600, ImagePrep.uploadDimensions(3000, 4000))
        assertEquals(100 to 200, ImagePrep.uploadDimensions(100, 200))
        assertEquals(1 to 1600, ImagePrep.uploadDimensions(1, 5000))
    }
}

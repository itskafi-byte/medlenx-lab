package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Chat Completions structured-output contract matching the serialized VL models. */
internal object VlResponseSchema {
    val responseFormat: JsonObject = Json.parseToJsonElement(
        """
        {
          "type": "json_schema",
          "json_schema": {
            "name": "prescription_scan",
            "strict": true,
            "schema": {
              "type": "object", "additionalProperties": false,
              "required": ["doctor", "medicines", "patient_info", "meta"],
              "properties": {
                "doctor": {
                  "type": "object", "additionalProperties": false,
                  "required": ["name", "bmdc_no", "qualifications", "hospital", "department", "specialty", "chamber", "district", "upazila", "territory"],
                  "properties": {
                    "name": {"type":"string"}, "bmdc_no": {"type":"string"},
                    "qualifications": {"type":"string"}, "hospital": {"type":"string"},
                    "department": {"type":"string"}, "specialty": {"type":"string"},
                    "chamber": {"type":"string"}, "district": {"type":"string"},
                    "upazila": {"type":"string"}, "territory": {"type":"string"}
                  }
                },
                "medicines": {
                  "type": "array", "items": {
                    "type": "object", "additionalProperties": false,
                    "required": ["brand_name", "generic_name", "raw_text", "form", "type", "strength", "dosage", "dosage_bengali", "dosage_normalized", "frequency", "company", "confidence", "bbox"],
                    "properties": {
                      "brand_name": {"type":"string"}, "generic_name": {"type":"string"},
                      "raw_text": {"type":"string"}, "form": {"type":"string"},
                      "type": {"type":"string"}, "strength": {"type":"string"},
                      "dosage": {"type":"string"}, "dosage_bengali": {"type":"string"},
                      "dosage_normalized": {"type":"string"}, "frequency": {"type":"string"},
                      "company": {"type":"string"}, "confidence": {"type":"number"},
                      "bbox": {"type":"array", "items":{"type":"number"}}
                    }
                  }
                },
                "patient_info": {
                  "type": ["object", "null"], "additionalProperties": false,
                  "required": ["masked", "note"],
                  "properties": {"masked":{"type":"boolean"}, "note":{"type":"string"}}
                },
                "meta": {
                  "type": ["object", "null"], "additionalProperties": false,
                  "required": ["total_medicines", "legibility", "language_mix"],
                  "properties": {"total_medicines":{"type":"integer"}, "legibility":{"type":["number","null"]}, "language_mix":{"type":"string"}}
                }
              }
            }
          }
        }
        """.trimIndent(),
    ).jsonObject

    /** Providers may treat strict mode as a hint; do not silently accept missing fields. */
    fun accepts(value: JsonElement): Boolean {
        val schema = responseFormat["json_schema"]!!.jsonObject["schema"]!!.jsonObject
        return matches(value, schema)
    }

    private fun matches(value: JsonElement, schema: JsonObject): Boolean {
        val type = schema["type"]
        val types = when (type) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> return false
        }
        if (value is JsonNull) return "null" in types
        if (value is JsonObject && "object" in types) {
            val properties = schema["properties"] as? JsonObject ?: return false
            val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return false
            if (required.any { it !in value } || value.keys.any { it !in properties }) return false
            return value.all { (key, child) -> matches(child, properties[key] as? JsonObject ?: return false) }
        }
        if (value is JsonArray && "array" in types) {
            val items = schema["items"] as? JsonObject ?: return false
            return value.all { matches(it, items) }
        }
        if (value !is JsonPrimitive || value.isString && "string" !in types) return false
        return when {
            value.isString -> "string" in types
            "boolean" in types -> value.booleanOrNull != null
            "integer" in types -> value.intOrNull != null
            "number" in types -> value.doubleOrNull?.isFinite() == true
            else -> false
        }
    }
}

package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Safe, actionable errors without exposing provider messages, keys or prescription content. */
internal object OpenRouterErrors {
    data class Problem(val message: String, val retryable: Boolean)

    fun fromResponse(json: Json, body: String, httpCode: Int): Problem? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val error = (root?.get("error") as? JsonObject) ?: (choice?.get("error") as? JsonObject)
        if (httpCode in 200..299 && error == null) return null
        val providerCode = (error?.get("code") as? JsonPrimitive)?.intOrNull
        val code = providerCode?.takeIf { it in 400..599 }
            ?: httpCode.takeIf { it !in 200..299 }
        val type = ((error?.get("metadata") as? JsonObject)?.get("error_type") as? JsonPrimitive)
            ?.contentOrNull
        val hint = when (code) {
            400, 413, 422 -> "The provider rejected the request or image. Try another image."
            401, 403 -> "Check your OpenRouter key, account permissions and privacy settings."
            402 -> "Check your OpenRouter account's credit or spending limit."
            404 -> "No matching endpoint. Check model availability and OpenRouter privacy settings."
            408, 504 -> "The model provider timed out. Try again later."
            429 -> "The free model's rate limit or quota was reached. Try again later."
            else -> if (code != null && code in 500..599)
                "The model provider is unavailable or returned an error. Try again later."
            else "The model provider returned an error. Check OpenRouter activity and try again."
        }
        val safeType = type?.takeIf { it in SAFE_TYPES }
        val suffix = listOfNotNull(code?.let { "code $it" }, safeType).joinToString(", ")
        val label = if (suffix.isBlank()) "OpenRouter error" else "OpenRouter error ($suffix)"
        return Problem("$label: $hint", code != null && code in RETRYABLE_CODES)
    }

    private val RETRYABLE_CODES = setOf(404, 408, 429, 500, 502, 503, 504, 524)
    private val SAFE_TYPES = setOf(
        "rate_limit_exceeded", "provider_error", "provider_timeout", "server",
        "unmapped", "invalid_request", "authentication", "permission_denied",
    )
}

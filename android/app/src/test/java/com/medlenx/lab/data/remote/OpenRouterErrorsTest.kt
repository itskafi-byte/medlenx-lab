package com.medlenx.lab.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterErrorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun providerFailureInsideHttp200IsRetryable() {
        val body = """{"error":{"code":503,"message":"private provider detail"}}"""
        val problem = OpenRouterErrors.fromResponse(json, body, 200)!!
        assertTrue(problem.retryable)
        assertTrue(problem.message.contains("503"))
        assertFalse(problem.message.contains("private provider detail"))
    }

    @Test fun choiceLevelProviderErrorAlsoRetries() {
        val body = """{"choices":[{"finish_reason":"error","error":{"code":502}}]}"""
        assertTrue(OpenRouterErrors.fromResponse(json, body, 200)!!.retryable)
    }

    @Test fun authenticationDoesNotFallback() {
        val problem = OpenRouterErrors.fromResponse(json, "{}", 401)!!
        assertFalse(problem.retryable)
        assertTrue(problem.message.contains("key"))
    }

    @Test fun normalResponseAndNullErrorAreNotFailures() {
        assertNull(OpenRouterErrors.fromResponse(json, """{"error":null,"choices":[]}""", 200))
        assertNull(OpenRouterErrors.fromResponse(json, """{"choices":[]}""", 200))
    }

    @Test fun nestedErrorCodeIsUsedForRateLimits() {
        val problem = OpenRouterErrors.fromResponse(json, """{"error":{"code":429}}""", 200)!!
        assertTrue(problem.retryable)
        assertTrue(problem.message.contains("rate limit"))
    }

    @Test fun malformedErrorBodyUsesHttpStatus() {
        val problem = OpenRouterErrors.fromResponse(json, "not json", 503)!!
        assertTrue(problem.retryable)
        assertEquals(true, problem.message.contains("503"))
    }
}

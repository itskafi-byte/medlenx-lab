package com.medlenx.lab.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** OpenRouter chat-completions wire format (a thin OpenAI-compatible subset). */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 4000,
    val temperature: Double = 0.2,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val model: String? = null,
    val error: OpenRouterError? = null,
)

@Serializable
data class ChatChoice(
    val message: ChatMessageContent? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatMessageContent(
    val role: String? = null,
    val content: String = "",
)

@Serializable
data class OpenRouterError(
    val message: String = "",
    val code: Int? = null,
)

/** Multimodal content parts. */
@Serializable
data class TextPart(val type: String = "text", val text: String)

@Serializable
data class ImagePart(
    val type: String = "image_url",
    @SerialName("image_url") val imageUrl: ImageUrl,
)

@Serializable
data class ImageUrl(val url: String)

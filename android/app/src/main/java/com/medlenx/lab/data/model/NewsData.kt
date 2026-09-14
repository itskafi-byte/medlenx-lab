package com.medlenx.lab.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A news item, whether it came from the bundled seed or a live RSS feed.
 *
 * `live` distinguishes the two on screen: the web app shows a "LIVE" dot on
 * fetched items and not on the curated ones, and an offline reader needs to know
 * which headlines are current and which are a snapshot.
 */
@Serializable
data class NewsItem(
    val id: String = "",
    val source: String = "",
    @SerialName("source_type") val sourceType: String = "",
    val title: String = "",
    val summary: String = "",
    val url: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val tags: List<String> = emptyList(),
    val live: Boolean = false,
)

@Serializable
data class NewsSeed(
    val items: List<NewsItem> = emptyList(),
)

/** Python's `get_pharma_news` return shape. */
data class NewsFeed(
    val liveCount: Int,
    val curatedCount: Int,
    val items: List<NewsItem>,
)

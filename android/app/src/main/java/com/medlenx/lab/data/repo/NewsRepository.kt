package com.medlenx.lab.data.repo

import android.util.Xml
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.model.NewsFeed
import com.medlenx.lab.data.model.NewsItem
import com.medlenx.lab.data.model.NewsSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Port of the RSS half of `app/pharma_hub.py`, plus `get_pharma_news`.
 *
 * **What is deliberately not ported.** `_fetch_medex_news` and `_fetch_dgda_news`
 * are not RSS at all — they regex-match HTML out of medex.com.bd/news and the
 * DGDA site. Reproducing brittle HTML scrapers on-device would produce a feed
 * that silently breaks the first time either site changes its markup, with no
 * server to fix it centrally. Only the WHO feed, which is real RSS, is fetched.
 * The curated seed supplies the regulatory and market headlines.
 */
class NewsRepository(
    private val catalogue: AssetCatalogue,
    private val httpClient: OkHttpClient,
) {

    /** Python's `_NEWS_TTL`: refetch at most once per interval. */
    private var cachedAt: Long = 0L
    private var cachedLive: List<NewsItem> = emptyList()

    suspend fun load(live: Boolean = true): NewsFeed = withContext(Dispatchers.IO) {
        val seed = catalogue.readAsset("pharma_news.json", NewsSeed.serializer()) ?: NewsSeed()
        val curated = seed.items

        var liveItems = emptyList<NewsItem>()
        if (live) {
            val now = System.currentTimeMillis()
            liveItems = if (cachedLive.isNotEmpty() && now - cachedAt < NEWS_TTL_MS) {
                cachedLive
            } else {
                fetchLive().also {
                    cachedAt = now
                    cachedLive = it
                }
            }
        }

        // merged = live + curated, newest first, exactly as the Python does.
        val merged = (liveItems + curated).sortedByDescending { it.publishedAt }
        NewsFeed(
            liveCount = liveItems.size,
            curatedCount = curated.size,
            items = merged,
        )
    }

    /** Python's `_fetch_live_news`, minus the two HTML scrapers. */
    private fun fetchLive(): List<NewsItem> {
        val xml = httpGet(WHO_FEED_URL) ?: return emptyList()
        return parseRssItems(xml, "WHO", "public-health", limit = 5)
    }

    /** Python's `_http_get`: a short-timeout GET that yields null on any failure. */
    private fun httpGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MedLenX Lab Pharma Intelligence Hub/3.2")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (response.code == 200 && !body.isNullOrEmpty()) body else null
        }
    }.getOrNull()

    companion object {
        const val WHO_FEED_URL = "https://www.who.int/rss-feeds/news-english.xml"
        const val NEWS_TTL_MS = 15L * 60L * 1000L

        /**
         * RFC 822, in the order Python tries them.
         *
         * `%Z` yields a *naive* datetime whose `isoformat()` carries no offset;
         * `%z` yields an *aware* one whose `isoformat()` ends in `+00:00`. That
         * distinction is not cosmetic: `get_pharma_news` sorts the merged feed by
         * this string, so a live item and a seed item stamped at the same second
         * order differently depending on which format matched. Both are kept so
         * the sort agrees with the web app.
         */
        private val RSS_NAIVE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        private val RSS_AWARE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        private val ISO_NAIVE = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val ISO_AWARE = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        private val HTML_TAG = Regex("<[^>]+>")
        private val WHITESPACE = Regex("\\s+")

        /**
         * Python's `_parse_rss_items`.
         *
         * Reads every descendant `<item>` and takes its title, link, description
         * and pubDate. Items without a title are skipped. The description has
         * simple HTML stripped and is truncated to 320 characters.
         *
         * The generated id uses `String.hashCode` rather than Python's `hash()`,
         * which is randomised per process by PYTHONHASHSEED and so could not be
         * reproduced deterministically even in principle.
         */
        fun parseRssItems(
            xmlText: String,
            source: String,
            sourceType: String,
            limit: Int = 8,
        ): List<NewsItem> {
            val out = mutableListOf<NewsItem>()
            runCatching {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(xmlText.byteInputStream(), null)

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT && out.size < limit) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        readItem(parser)?.let { raw ->
                            if (raw.title.isNotBlank()) {
                                out += NewsItem(
                                    id = "rss-${kotlin.math.abs(raw.title.hashCode()) % 10_000_000}",
                                    source = source,
                                    sourceType = sourceType,
                                    title = raw.title,
                                    summary = cleanSummary(raw.description),
                                    url = raw.link,
                                    publishedAt = parseRssDate(raw.pubDate)
                                        ?: ISO_NAIVE.format(Date()),
                                    tags = listOf(source, "Live"),
                                    live = true,
                                )
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return out
        }

        private data class RawItem(
            val title: String,
            val link: String,
            val description: String,
            val pubDate: String,
        )

        /** Consumes one `<item>` element, returning its four child texts. */
        private fun readItem(parser: XmlPullParser): RawItem? {
            var title = ""; var link = ""; var desc = ""; var pub = ""
            var current: String? = null
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        current = parser.name
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        when (current) {
                            "title" -> title += text
                            "link" -> link += text
                            "description" -> desc += text
                            "pubDate" -> pub += text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        depth--
                        current = null
                    }
                }
            }
            return RawItem(title.trim(), link.trim(), desc.trim(), pub.trim())
        }

        private fun cleanSummary(desc: String): String {
            var d = desc
            if (d.contains("<")) {
                d = HTML_TAG.replace(d, " ")
                d = WHITESPACE.replace(d, " ").trim()
            }
            return if (d.length > 320) d.substring(0, 320) else d
        }

        private fun parseRssDate(pub: String): String? {
            if (pub.isBlank()) return null
            // Naive first, matching Python's format order.
            runCatching { RSS_NAIVE_FORMAT.parse(pub) }.getOrNull()?.let {
                return ISO_NAIVE.format(it)
            }
            runCatching { RSS_AWARE_FORMAT.parse(pub) }.getOrNull()?.let {
                return ISO_AWARE.format(it)
            }
            return null
        }

        /** Shared client: one connection pool for the whole app. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Python uses timeout=3.5; OkHttp has no fractional connect timeout
            // in practice, so 4s is the nearest safe value on a rural link.
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.local.MedexDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a pack photo for a medicine **name**.
 *
 * Two sources, in order:
 *
 *  1. **Bundled catalogue** — all 25,105 rows ship a `medex.com.bd` pack-photo URL, so
 *     for any medicine in the index the lookup is a local SQL hit and needs no network.
 *  2. **Live medex.com.bd search** — for a name the catalogue does not carry. The search
 *     page is fetched with a browser User-Agent and the first packaging image path is
 *     scraped out of the HTML.
 *
 * Results (including misses) are memoised, because a list of medicine rows asks for the
 * same handful of names on every recomposition.
 */
class MedicineImageStore(
    private val catalogue: AssetCatalogue,
    private val medexDao: MedexDao,
    private val httpClient: OkHttpClient,
) {

    private val cache = ConcurrentHashMap<String, String?>()

    /** Serialises the "is the catalogue imported yet" check across concurrent rows. */
    private val importMutex = Mutex()

    suspend fun imageFor(name: String): String? {
        val key = name.trim().lowercase()
        // Below two characters every brand in the country matches; not worth a query.
        if (key.length < 2) return null
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null // known miss

        return withContext(Dispatchers.IO) {
            val found = runCatching { bundled(key) }.getOrNull()
                ?: runCatching { live(key) }.getOrNull()
            cache[key] = found
            found
        }
    }

    private suspend fun bundled(key: String): String? {
        // The catalogue import runs on the graph scope at process start; a row that
        // renders before it lands must wait rather than silently miss.
        importMutex.withLock { catalogue.ensureImported() }
        return medexDao.search(key, 5)
            .firstNotNullOfOrNull { row ->
                row.imageUrl?.takeIf { it.isNotBlank() }
                    ?: row.packImage?.takeIf { it.isNotBlank() }
            }
    }

    private fun live(key: String): String? {
        val url = SEARCH_URL + URLEncoder.encode(key, "UTF-8")
        val request = Request.Builder()
            .url(url)
            // MedEx serves the search page to browsers; a default client UA risks a
            // block, and a block must degrade to "no image" rather than an error.
            .header("User-Agent", BROWSER_UA)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string().orEmpty()
            val path = PACK_IMAGE.find(html)?.value ?: return null
            return if (path.startsWith("http")) path else ORIGIN + path
        }
    }

    private companion object {
        const val ORIGIN = "https://medex.com.bd"
        const val SEARCH_URL = "$ORIGIN/search?q="
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** Matches the packaging asset paths MedEx embeds in its search results. */
        val PACK_IMAGE = Regex(
            "/storage/images/packaging/[A-Za-z0-9._%\\-]+\\.(?:webp|jpg|jpeg|png)",
        )
    }
}

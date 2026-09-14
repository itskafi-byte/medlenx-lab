package com.medlenx.lab.data.local

import android.content.Context
import com.medlenx.lab.data.model.MedexProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where the bundled catalogue import currently stands. */
enum class CatalogueState { NotStarted, Importing, Ready, Missing, Failed }

/**
 * Imports the bundled datasets into Room on first launch.
 *
 * The standalone app ships the same JSON the FastAPI backend serves, copied into
 * assets/data by the `copyMedLenXAssets` Gradle task. `medex_full.json` is a
 * top-level array of 25,105 records (~16 MB), so it is decoded once, written to
 * Room in batches, and then released rather than held in memory.
 */
class AssetCatalogue(
    private val context: Context,
    private val json: Json,
    private val medexDao: MedexDao,
) {

    private val _state = MutableStateFlow(CatalogueState.NotStarted)
    val state: StateFlow<CatalogueState> = _state

    private val _recordCount = MutableStateFlow(0)
    val recordCount: StateFlow<Int> = _recordCount

    /** Idempotent: a no-op once the catalogue is present. */
    suspend fun ensureImported(force: Boolean = false) {
        if (!force && _state.value == CatalogueState.Ready) return
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = medexDao.count()
                if (existing > 0 && !force) {
                    _recordCount.value = existing
                    _state.value = CatalogueState.Ready
                    return@runCatching
                }

                _state.value = CatalogueState.Importing
                val products = readProducts()
                if (products.isEmpty()) {
                    _state.value = CatalogueState.Missing
                    return@runCatching
                }

                // Batched insert keeps the transaction log and peak memory bounded.
                products.chunked(2_000).forEach { chunk ->
                    medexDao.insertAll(chunk.map { it.toEntity() })
                }
                _recordCount.value = medexDao.count()
                _state.value = CatalogueState.Ready
            }.onFailure {
                _state.value = CatalogueState.Failed
            }
        }
    }

    /** Reads assets/data/medex_full.json, which is a top-level JSON array. */
    private fun readProducts(): List<MedexProduct> {
        val stream = runCatching { context.assets.open("data/medex_full.json") }
            .getOrElse { return emptyList() }
        return stream.use { input ->
            json.decodeFromString(
                ListSerializer(MedexProduct.serializer()),
                input.bufferedReader().use { it.readText() },
            )
        }
    }

    /**
     * Reads a smaller bundled dataset, returning null when it is absent.
     *
     * Takes the serializer explicitly rather than being `inline reified`: a public
     * inline function cannot reach the private `context`/`json` members.
     */
    suspend fun <T> readAsset(fileName: String, serializer: KSerializer<T>): T? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("data/$fileName").bufferedReader().use {
                    json.decodeFromString(serializer, it.readText())
                }
            }.getOrNull()
        }
}

private fun MedexProduct.toEntity() = MedexEntity(
    id = id.ifBlank { "$brandName|$company|$strength" },
    brandName = brandName,
    generic = generic,
    strength = strength,
    form = form,
    type = type,
    company = company,
    ingredient = ingredient,
    category = category,
    imageUrl = imageUrl,
    packImage = packImage,
    url = url,
)

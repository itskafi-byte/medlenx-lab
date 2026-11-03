package com.medlenx.lab.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

/** Versioned bundled seed, shared by fresh installs and the v11 -> v12 migration. */
object ManufacturerAliases {
    fun seed(context: Context, db: SupportSQLiteDatabase) {
        val entries = JSONArray(context.assets.open("manufacturer_aliases.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val seen = mutableMapOf<String, String>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val canonical = entry.getString("canonical").trim()
            require(canonical.isNotEmpty())
            val aliases = entry.getJSONArray("aliases")
            val names = (0 until aliases.length()).map { aliases.getString(it).trim() } + canonical
            for (alias in names) {
                require(alias.isNotEmpty())
                val key = alias.lowercase(java.util.Locale.ROOT)
                require(seen[key] == null || seen[key] == canonical) { "Conflicting manufacturer alias: $alias" }
                seen[key] = canonical
                db.execSQL("INSERT OR REPLACE INTO manufacturer_aliases (alias_string, canonical_company_id) VALUES (?, ?)", arrayOf<Any?>(alias, canonical))
            }
        }
    }
}

package com.medlenx.lab.data.local

/** Reject placeholder values as well as empty input. */
object CompanySelection {
    fun isAssigned(name: String?): Boolean {
        val value = name?.trim().orEmpty()
        return value.isNotEmpty() && value.length <= 500 && value.lowercase(java.util.Locale.ROOT) !in
            setOf("unassigned", "unknown", "none", "n/a", "not assigned", "other (manual entry)")
    }
}

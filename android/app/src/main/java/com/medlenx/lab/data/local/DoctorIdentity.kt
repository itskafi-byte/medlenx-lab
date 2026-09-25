package com.medlenx.lab.data.local

/**
 * How two prescriptions are decided to be from the same doctor.
 *
 * This exists as its own object because the key is computed in two places that
 * must agree exactly: when a prescription is saved, and when existing rows are
 * backfilled into the `doctors` table by the v1→v2 migration. If the two
 * disagreed by so much as a casing rule, the migration would create one doctor
 * row and the save path another, and the drill-down would show the same person
 * twice with their prescriptions split between.
 *
 * The web groups by `prescriptions.doctor_id`, a surrogate key assigned when the
 * row is written, so it never has this problem — and it has no equivalent of this
 * function for the same reason. This is the Android substitute for the identity
 * the backend gets for free.
 */
object DoctorIdentity {

    /**
     * BMDC registration number when the officer recorded one, otherwise name
     * plus chamber plus district.
     *
     * BMDC wins because it is the actual national physician identifier, and a
     * doctor who moves chamber keeps it — so two visits across a move stay one
     * identity, which the name-and-chamber fallback would split. When it is
     * absent, the three-part fallback is the best available: name alone merges
     * every namesake in the country, and adding specialty does nothing to
     * separate them.
     *
     * Returns null when there is nothing to key on at all — a prescription with
     * no name and no BMDC has no identity, and the caller stores null rather than
     * inventing one. SQLite groups NULLs together and the drill-down matches that
     * behaviour, so an anonymous prescription still lands somewhere.
     */
    fun keyOf(
        name: String?,
        bmdcNo: String?,
        chamber: String?,
        district: String?,
    ): String? {
        val bmdc = norm(bmdcNo)
        if (bmdc.isNotEmpty()) return "bmdc:$bmdc"
        val who = norm(name)
        if (who.isEmpty()) return null
        return "ncd:$who|${norm(chamber)}|${norm(district)}"
    }

    /** Key for a prescription row. */
    fun keyOf(prescription: PrescriptionEntity): String? = keyOf(
        name = prescription.doctorName,
        bmdcNo = prescription.doctorBmdcNo,
        chamber = prescription.chamber,
        district = prescription.district,
    )

    /**
     * Case, internal whitespace and surrounding punctuation are all noise.
     *
     * `Dr. Md. Abdul Karim` and `dr md  abdul karim` are the same doctor, and the
     * scan pipeline produces both: the doctor block is read by the vision model
     * and then hand-edited. Dots are dropped rather than folded because they are
     * how the honorific is written, not part of the name.
     */
    private fun norm(value: String?): String =
        (value ?: "")
            .lowercase()
            .replace(Regex("[.,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * The display name to store, preferring whatever the officer typed.
     *
     * Not normalised: this is what the drill-down prints, so it should read the
     * way the prescription does rather than the way the key is built.
     */
    fun displayName(name: String?): String =
        (name ?: "").replace(Regex("\\s+"), " ").trim()
}

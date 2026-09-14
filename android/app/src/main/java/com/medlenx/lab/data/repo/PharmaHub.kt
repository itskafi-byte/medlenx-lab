package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.EnrichedJob
import com.medlenx.lab.data.model.HealthCalendar
import com.medlenx.lab.data.model.HealthDayEntry
import com.medlenx.lab.data.model.HealthDays
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.JobBoard
import com.medlenx.lab.data.model.PharmaJob
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Port of the data-driven half of `app/pharma_hub.py`.
 *
 * Covers the job board and the health-day calendar, both of which are pure
 * functions over the bundled JSON. The network half of that module — RSS news
 * fetching from medex.com.bd and the DGDA site — is deliberately not here; the
 * standalone app is offline-first and has no server to proxy it.
 */
object PharmaHub {

    /** Python's `_COMPANY_CAREER_URL`. Iteration order matters: first key wins. */
    val COMPANY_CAREER_URL: Map<String, String> = linkedMapOf(
        "square" to "https://www.squarepharma.com.bd/careers/",
        "beximco" to "https://www.beximcopharma.com/careers",
        "incepta" to "https://www.incepta.com.bd/careers",
        "renata" to "https://renata-ltd.com/career",
        "aci" to "https://www.aci-bd.com/career.html",
        "healthcare" to "https://www.healthcare-pharma.com/careers",
        "eskayef" to "https://eskayefbd.com/careers",
        "novo nordisk" to "https://www.novonordisk.com/careers",
        "opsonin" to "https://opsonin.com/careers",
        "beacon" to "https://beaconpharma.com.bd/careers",
        "aristopharma" to "https://www.aristopharma.com/careers",
        "popular" to "https://popularpharmabd.com/careers",
    )

    /** Python's `_CATEGORY_DEPT` — category to functional department. */
    val CATEGORY_DEPT: Map<String, String> = mapOf(
        "MPO" to "Field Sales",
        "RSO" to "Field Sales",
        "Product Management" to "Marketing & Product",
        "Sales Ops" to "Operations",
        "Regulatory Affairs" to "Regulatory & QA",
    )

    /** Python's `_CATEGORY_TAGS` — skill tags rendered as chips. */
    val CATEGORY_TAGS: Map<String, List<String>> = mapOf(
        "MPO" to listOf("MPO", "Territory", "Doctor Coverage"),
        "RSO" to listOf("RSO", "RSM", "Territory", "Leadership"),
        "Product Management" to listOf("PMD", "Product Executive", "KOL"),
        "Sales Ops" to listOf("Ops", "Incentive", "Analytics"),
        "Regulatory Affairs" to listOf("Regulatory", "DGDA", "QA"),
    )

    /**
     * Python's `_job_apply_url`.
     *
     * The company name is lowercased before matching *and* before being folded
     * into the fallback search URL, so an unknown company produces a lowercase
     * query string. Reproduced verbatim.
     */
    fun jobApplyUrl(job: PharmaJob): String {
        val company = job.company.lowercase()
        val title = job.title.replace(" ", "+")
        for ((key, url) in COMPANY_CAREER_URL) {
            if (company.contains(key)) return url
        }
        return "https://www.google.com/search?q=$title+${company.replace(" ", "+")}+career"
    }

    /**
     * Python's `get_pharma_jobs`: filter the bundled vacancies and decorate each
     * one with its posting date, freshness, department, tags and apply link.
     *
     * `today` is injected rather than read from the clock so the result is
     * testable and so a scan started before midnight renders consistently.
     */
    fun pharmaJobs(
        jobs: List<PharmaJob>,
        today: LocalDate,
        category: String = "",
        q: String = "",
        location: String = "",
        department: String = "",
        territory: String = "",
    ): JobBoard {
        val qLower = q.lowercase().trim()
        val catLower = category.lowercase().trim()
        val locLower = location.lowercase().trim()
        val deptLower = department.lowercase().trim()
        val terrLower = territory.lowercase().trim()

        val out = mutableListOf<EnrichedJob>()
        for (job in jobs) {
            if (catLower.isNotEmpty() && !job.category.lowercase().contains(catLower)) continue
            val locBlob = (job.location + " " + job.division).lowercase()
            if (locLower.isNotEmpty() && !locBlob.contains(locLower)) continue
            val dept = CATEGORY_DEPT[job.category] ?: ""
            if (deptLower.isNotEmpty() && !dept.lowercase().contains(deptLower)) continue
            if (terrLower.isNotEmpty() && !locBlob.contains(terrLower)) continue

            val blob = listOf(job.title, job.company, job.description, job.location)
                .joinToString(" ").lowercase()
            if (qLower.isNotEmpty() && !blob.contains(qLower)) continue

            out += EnrichedJob(
                job = job,
                postedOn = today.minusDays(job.postedDaysAgo.toLong()).toString(),
                fresh = job.postedDaysAgo <= 1,
                department = CATEGORY_DEPT[job.category] ?: "Field Sales",
                tags = CATEGORY_TAGS[job.category] ?: emptyList(),
                applyUrl = jobApplyUrl(job),
            )
        }

        // sorted({...}) in Python: unique and alphabetically ordered.
        return JobBoard(
            total = out.size,
            categories = jobs.map { it.category }.filter { it.isNotEmpty() }.distinct().sorted(),
            locations = jobs.map { it.location }.filter { it.isNotEmpty() }.distinct().sorted(),
            departments = jobs.filter { it.category.isNotEmpty() }
                .map { CATEGORY_DEPT[it.category] ?: "Field Sales" }
                .distinct().sorted(),
            jobs = out,
        )
    }

    /** Python's `_TOP_COMPANY_TOKENS` — the leading companies for the top10 slice. */
    val TOP_COMPANY_TOKENS: List<String> = listOf(
        "square", "incepta", "beximco", "renata", "aci limited",
        "healthcare pharmaceuticals", "opsonin", "eskayef", "acme",
        "beacon", "aristopharma", "drug international",
    )

    /**
     * Python's `_CATEGORY_TERMS`.
     *
     * "antacid" appears twice under `otc` in the original. Kept verbatim: the
     * duplicate cannot change an `any` test, and silently de-duplicating a ported
     * table makes the diff against the Python harder to audit.
     */
    val CATEGORY_TERMS: Map<String, List<String>> = mapOf(
        "cardiology" to listOf(
            "atorvastatin", "amlodipine", "losartan", "valsartan", "telmisartan",
            "metoprolol", "bisoprolol", "carvedilol", "nebivolol", "clopidogrel",
            "aspirin", "rosuvastatin", "nitroglycerin", "isosorbide", "ramipril",
            "enalapril", "digoxin", "furosemide", "diltiazem", "verapamil",
            "rivaroxaban", "warfarin", "trimetazidine", "ivabradine", "sacubitril",
        ),
        "antibiotics" to listOf(
            "amoxicillin", "ciprofloxacin", "azithromycin", "cephalexin",
            "cefixime", "cefuroxime", "ceftriaxone", "ceftazidime", "cefepime",
            "meropenem", "levofloxacin", "clarithromycin", "doxycycline",
            "cephradine", "cloxacillin", "moxifloxacin", "cefaclor", "flucloxacillin",
            "amikacin", "gentamicin", "nitrofurantoin", "cotrimoxazole", "metronidazole",
        ),
        "otc" to listOf(
            "paracetamol", "antacid", "vitamin", "calcium", "multivitamin",
            "cough", "cold", "antihistamine", "fertile", "ferrous", "folic acid",
            "zinc", "domperidone", "ors", "oral rehydration", "digestive", "antacid",
            "hydrocortisone", "emollient", "sunscreen", "multimineral", "b-complex",
            "dextromethorphan", "loratadine", "cetirizine", "chlorpheniramine",
            "nasal", "expectorant", "mucolytic", "probiotic",
        ),
    )

    val BROWSE_CATEGORIES: List<String> = listOf("top10", "cardiology", "antibiotics", "otc")

    /** Rows whose pack image came from the MedEx CDN carry a pack photo. */
    private fun packScore(row: MedexProduct): Int =
        if ((row.packImage ?: row.imageUrl ?: "").contains("medex.com.bd/storage")) 1 else 0

    /**
     * Python's `medex_browse`: a curated slice of the catalogue for the index
     * tab's quick-filter pills.
     *
     * Ranking is `rank + pack_score`, then by brand-name length; ties keep
     * catalogue order because both Python's `list.sort` and Kotlin's `sortedWith`
     * are stable. Results are de-duplicated on brand+strength+company.
     *
     * The Python source comments "cap 2 rows per company for top10", but the code
     * under it does no such capping - it only de-duplicates. The code is what was
     * ported, and `total` is the pre-dedupe match count, as in the original.
     */
    fun medexBrowse(
        category: String = "top10",
        medexDb: List<MedexProduct>,
        limit: Int = 24,
    ): BrowseResult {
        val cat = category.lowercase().trim()
        if (cat !in BROWSE_CATEGORIES) return BrowseResult(cat, 0, emptyList())
        if (medexDb.isEmpty()) return BrowseResult(cat, 0, emptyList())

        val scored = mutableListOf<Pair<Int, MedexProduct>>()
        for (row in medexDb) {
            val company = row.company.lowercase()
            val blob = listOf(row.generic, row.category, row.ingredient)
                .joinToString(" ").lowercase()
            val rank = when {
                cat == "top10" ->
                    if (TOP_COMPANY_TOKENS.any { company.contains(it) }) 5 else 0
                else ->
                    if (CATEGORY_TERMS[cat].orEmpty().any { blob.contains(it) }) {
                        // An exact category-field match outranks a substring hit.
                        // Note row.category may be blank, and "" is a substring of
                        // every string, so a blank category scores the higher rank -
                        // exactly as Python's `"" in blob` does.
                        if (blob.contains(row.category.lowercase())) 6 else 4
                    } else {
                        0
                    }
            }
            if (rank != 0) scored += (rank + packScore(row)) to row
        }

        scored.sortWith(
            compareByDescending<Pair<Int, MedexProduct>> { it.first }
                .thenBy { it.second.brandName.length }
        )

        val results = mutableListOf<MedexProduct>()
        val seen = HashSet<Triple<String, String, String>>()
        for ((_, row) in scored) {
            val key = Triple(row.brandName, row.strength, row.company)
            if (!seen.add(key)) continue
            results += row
            if (results.size >= limit) break
        }
        return BrowseResult(category = cat, total = scored.size, results = results)
    }

    /**
     * Python's `unique_companies_from`: distinct manufacturers with a brand count,
     * ranked by volume then name.
     */
    fun uniqueCompaniesFrom(
        medexDb: List<MedexProduct>,
        q: String = "",
        limit: Int = 25,
    ): CompanyList {
        val qLower = q.lowercase().trim()
        val seen = LinkedHashMap<String, Int>()
        for (row in medexDb) {
            val name = row.company.trim()
            if (name.isEmpty()) continue
            if (qLower.isNotEmpty() && !name.lowercase().contains(qLower)) continue
            seen[name] = (seen[name] ?: 0) + 1
        }
        val ranked = seen.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { CompanyCount(it.key, it.value) }
        return CompanyList(
            total = ranked.size,
            companies = ranked.take(maxOf(1, minOf(limit, 200))),
        )
    }

    /**
     * Python's `get_health_days`: resolve every entry against a real calendar
     * year and classify it as today / upcoming / past.
     *
     * Entries whose month/day is not a valid date in [year] are dropped, exactly
     * as the Python `except ValueError: continue` does — that is what keeps a
     * 29 February entry from crashing a non-leap year.
     */
    fun healthDays(
        data: HealthDays,
        today: LocalDate,
        year: Int? = null,
        upcomingOnly: Boolean = false,
    ): HealthCalendar {
        val y = year ?: today.year
        val entries = mutableListOf<HealthDayEntry>()
        for (d in data.days) {
            val date = runCatching { LocalDate.of(y, d.month, d.day) }.getOrNull() ?: continue
            val status = when {
                date == today -> "today"
                date.isAfter(today) -> "upcoming"
                else -> "past"
            }
            if (upcomingOnly && status == "past") continue
            entries += HealthDayEntry(
                day = d,
                date = date.toString(),
                year = y,
                // Python's %A under the default C locale is the English name.
                weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                daysUntil = date.toEpochDay() - today.toEpochDay(),
                status = status,
            )
        }
        // sorted by (month, day). Kotlin's sortedWith is stable, so two days
        // sharing a date keep their file order - as Python's sort does.
        entries.sortWith(compareBy({ it.day.month }, { it.day.day }))

        return HealthCalendar(
            year = y,
            today = today.toString(),
            next = entries.firstOrNull { it.status == "today" || it.status == "upcoming" },
            count = entries.size,
            days = entries,
            byMonth = entries.groupBy { it.day.month },
        )
    }
}

/** Python's `medex_browse` return shape. Every row is DGDA-registered. */
data class BrowseResult(
    val category: String,
    val total: Int,
    val results: List<MedexProduct>,
)

data class CompanyCount(val name: String, val brands: Int)

/** Python's `unique_companies_from` return shape. */
data class CompanyList(val total: Int, val companies: List<CompanyCount>)

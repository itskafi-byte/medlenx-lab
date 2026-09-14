package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.EnrichedJob
import com.medlenx.lab.data.model.HealthCalendar
import com.medlenx.lab.data.model.HealthDayEntry
import com.medlenx.lab.data.model.HealthDays
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

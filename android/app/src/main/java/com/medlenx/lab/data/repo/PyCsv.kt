package com.medlenx.lab.data.repo

/**
 * `csv.writer`'s QUOTE_MINIMAL, in one place.
 *
 * The web writes every export through Python's `csv` module, so both Android
 * exports have to agree with it field for field. Keeping the rule in one object
 * rather than once per exporter is deliberate: an escape rule that differs
 * between two exports is a corrupt file waiting for a brand name with a comma in
 * it, and the failure is silent because the file still opens.
 *
 * Sits beside [PyMath] for the same reason — Python semantics that Android has to
 * reproduce exactly, isolated so the difference is explicit rather than assumed.
 */
object PyCsv {

    /**
     * Quotes only when the field needs it, which is what QUOTE_MINIMAL does:
     * a comma, a quote, or a newline anywhere in the field, and nothing else.
     *
     * An embedded quote is doubled rather than escaped with a backslash, per RFC
     * 4180 — a backslash escape would be read as literal text by every
     * spreadsheet that opens the file.
     */
    fun escape(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${field.replace("\"", "\"\"")}\"" else field
    }

    /**
     * `csv.writer`'s default `lineterminator`.
     *
     * Python terminates CSV rows with CRLF unless a dialect says otherwise, and
     * `/api/export/recent-medicines.csv` does not override it — so a file written
     * with LF here is not the same file the web produces, even though every value
     * matches. Byte-level differences like this are invisible until a checksum or
     * a diff is compared, which is why both terminators are named.
     */
    const val CRLF = "\r\n"

    /** What `rx_audit.py:153` passes as `lineterminator`. */
    const val LF = "\n"

    /** One row, terminator included, with every field escaped. */
    fun row(fields: List<String>, lineTerminator: String = CRLF): String =
        fields.joinToString(",") { escape(it) } + lineTerminator
}

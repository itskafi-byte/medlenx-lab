package com.medlenx.lab.data.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument

/**
 * A deliberately small PDF layout engine over [PdfDocument].
 *
 * ## Why not render the Compose UI into the page
 *
 * The obvious way to get a pixel-faithful PDF is to record the composable into a
 * `GraphicsLayer` and draw that bitmap onto the page. It was rejected. Capturing
 * a composable that is not currently on screen needs a `ComposeView` attached to
 * a real window, then measured, laid out and drawn by hand, and the composition
 * must have finished or the capture is silently blank. None of that can be
 * verified without a device, and every way it can fail produces either a blank
 * page or a crash rather than a wrong pixel.
 *
 * Hand-drawing costs fidelity — this reproduces the *content* of each document,
 * not the Compose chrome — and buys predictability: every call below is a stable
 * `android.graphics` API whose behaviour is known without running it. For a
 * document whose whole purpose is to leave the device and be printed, a valid
 * page with the right numbers beats a faithful page that might be empty.
 *
 * ## Coordinates
 *
 * [PdfDocument] pages are sized in points at 1/72 inch, so the A4 defaults below
 * are the same 595 x 842 the web's reportlab documents use. Y runs downward from
 * the top edge, and [cursor] tracks the next free baseline.
 *
 * Instances are single-use: build a document, call [finish], discard.
 */
internal class PdfWriter(
    private val pageWidth: Int = A4_WIDTH,
    private val pageHeight: Int = A4_HEIGHT,
    private val margin: Float = MARGIN,
) {

    companion object {
        /** A4 at 1/72 inch — reportlab's `A4`, so both render the same page size. */
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842

        /** Landscape A4, for the RSM report whose member table is nine columns wide. */
        const val A4_LANDSCAPE_WIDTH = 842
        const val A4_LANDSCAPE_HEIGHT = 595

        private const val MARGIN = 44f

        // The web's palette, so a printed page and a screenshot agree.
        const val INK = 0xFF0F172A.toInt()
        const val MUTED = 0xFF475569.toInt()
        const val FAINT = 0xFF64748B.toInt()
        const val RULE = 0xFFCBD5E1.toInt()
        const val ACCENT = 0xFF1E40AF.toInt()
        const val VIOLET = 0xFF7C3AED.toInt()
        const val HEADER_BG = 0xFF1E40AF.toInt()
        const val HEADER_FG = 0xFFFFFFFF.toInt()
        const val BAND_BG = 0xFFEFF6FF.toInt()
        const val ZEBRA_BG = 0xFFF8FAFC.toInt()
        const val DARK_HEADER_BG = 0xFF0F172A.toInt()
    }

    /** A text style: size in points, colour, weight and line height. */
    internal class Style(
        val size: Float,
        val color: Int = INK,
        val bold: Boolean = false,
        val leading: Float = size * 1.35f,
    )

    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNumber = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isSubpixelText = true
    }

    /** The y at which the next line of text will be drawn. */
    private var cursor = 0f

    /** Stamped at the foot of every page as it closes, so set it before the first block. */
    var footerText: String = "MedLenX Lab · generated on device"

    private val contentWidth: Float get() = pageWidth - margin * 2f


    // ------------------------------------------------------------- pages ----

    private fun openPage() {
        pageNumber++
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val p = document.startPage(info)
        page = p
        canvas = p.canvas
        cursor = margin
    }

    /** Starts a page if none is open, then breaks if [blockHeight] will not fit. */
    private fun reserve(blockHeight: Float) {
        if (canvas == null) openPage()
        if (cursor + blockHeight > pageHeight - margin) {
            closePage()
            openPage()
        }
    }

    private fun closePage() {
        val p = page ?: return
        // The footer is written here rather than in a post-pass because
        // PdfDocument cannot reopen a finished page, and the total count is not
        // knowable until the last page is closed. So the footer carries the brand
        // line only; the page number is omitted rather than printed wrong.
        canvas?.let { c ->
            configure(Style(7.5f, FAINT, leading = 10f))
            c.drawText(footerText, margin, pageHeight - margin / 2f, paint)
        }
        document.finishPage(p)
        page = null
        canvas = null
    }

    private fun configure(style: Style) {
        paint.color = style.color
        paint.textSize = style.size
        paint.typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /**
     * Greedy word wrap against the content width.
     *
     * A word longer than the line is emitted on its own line and overflows rather
     * than being split: these documents carry brand names, and breaking a brand
     * across two lines reads as two products.
     */
    private fun wrap(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = candidate
            } else {
                lines.add(line)
                line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        return if (lines.isEmpty()) listOf("") else lines
    }

    /** Truncates with an ellipsis rather than overflowing — for table cells. */
    private fun fit(text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) {
            t = t.dropLast(1)
        }
        return "$t…"
    }

    // ------------------------------------------------------------ blocks ----

    /** A run of wrapped paragraph text in [style]. Returns the height used. */
    fun paragraph(text: String, style: Style, indent: Float = 0f): Float {
        if (text.isBlank()) return 0f
        configure(style)
        val width = contentWidth - indent
        val lines = wrap(text, width)
        for (line in lines) {
            reserve(style.leading)
            // Reserve can land us on a fresh page with a reset cursor.
            drawText(line, margin + indent, cursor + style.size)
            cursor += style.leading
        }
        return lines.size * style.leading
    }

    private fun drawText(text: String, x: Float, baseline: Float) {
        canvas?.drawText(text, x, baseline, paint)
    }

    fun title(text: String, color: Int = INK) {
        paragraph(text, Style(19f, color, bold = true, leading = 24f))
    }

    fun subtitle(text: String) {
        paragraph(text, Style(9f, MUTED, leading = 13f))
        gap(6f)
    }

    fun heading(text: String, color: Int = ACCENT) {
        gap(4f)
        paragraph(text, Style(12f, color, bold = true, leading = 17f))
        gap(2f)
    }

    fun body(text: String, style: Style = Style(10f, INK, leading = 14f)) {
        paragraph(text, style)
    }

    /** A wrapped bullet, with the marker hanging outside the text block. */
    fun bullet(text: String, style: Style = Style(10f, INK, leading = 14f)) {
        if (text.isBlank()) return
        configure(style)
        val lines = wrap(text, contentWidth - 12f)
        lines.forEachIndexed { index, line ->
            reserve(style.leading)
            if (index == 0) drawText("•", margin, cursor + style.size)
            drawText(line, margin + 12f, cursor + style.size)
            cursor += style.leading
        }
    }

    fun gap(height: Float) {
        if (canvas == null) return
        cursor += height
    }

    fun rule(color: Int = RULE, thickness: Float = 0.7f) {
        reserve(thickness + 4f)
        paint.color = color
        canvas?.drawRect(margin, cursor, margin + contentWidth, cursor + thickness, paint)
        cursor += thickness + 4f
    }

    /**
     * A bordered grid: header row, then body rows.
     *
     * [weights] are relative column widths, not absolute, so callers describe
     * proportion rather than doing arithmetic against the page width.
     */
    fun table(
        header: List<String>,
        rows: List<List<String>>,
        weights: List<Float>,
        headerBackground: Int = HEADER_BG,
        headerColor: Int = HEADER_FG,
        fontSize: Float = 8f,
        zebra: Boolean = true,
        /** Tints every body row the same, as the web's KPI strip does. */
        banded: Boolean = false,
    ) {
        require(weights.size == header.size) {
            "table(): ${weights.size} column widths for ${header.size} columns"
        }
        val total = weights.sum()
        val widths = weights.map { it / total * contentWidth }
        val cellStyle = Style(fontSize, INK, leading = fontSize * 1.5f)
        val rowHeight = fontSize * 2.4f

        // Header. Re-drawn at the top of each new page so a table that spans a
        // break stays readable.
        fun drawHeader() {
            reserve(rowHeight)
            val c = canvas ?: return
            paint.color = headerBackground
            c.drawRect(margin, cursor - 2f, margin + contentWidth, cursor + rowHeight - 2f, paint)
            configure(Style(fontSize, headerColor, bold = true))
            var x = margin
            header.forEachIndexed { i, text ->
                drawText(fit(text, widths[i] - 8f), x + 4f, cursor + fontSize * 1.6f)
                x += widths[i]
            }
            cursor += rowHeight
        }

        drawHeader()

        rows.forEachIndexed { rowIndex, row ->
            if (cursor + rowHeight > pageHeight - margin) {
                closePage()
                openPage()
                drawHeader()
            }
            val c = canvas ?: return
            val bodyBackground = when {
                banded -> BAND_BG
                zebra && rowIndex % 2 == 1 -> ZEBRA_BG
                else -> null
            }
            if (bodyBackground != null) {
                paint.color = bodyBackground
                c.drawRect(margin, cursor - 2f, margin + contentWidth, cursor + rowHeight - 2f, paint)
            }
            configure(cellStyle)
            var x = margin
            row.forEachIndexed { i, text ->
                if (i < widths.size) {
                    drawText(fit(text, widths[i] - 8f), x + 4f, cursor + fontSize * 1.6f)
                    x += widths[i]
                }
            }
            cursor += rowHeight
            // Hairline between rows.
            paint.color = RULE
            c.drawRect(margin, cursor - 2f, margin + contentWidth, cursor - 1.3f, paint)
        }
        gap(8f)
    }

    /**
     * Closes the document and returns the bytes.
     *
     * Every page must be finished before `writeTo`, and [document] is closed by
     * this call — the writer is single-use, matching the single-use `PdfDocument`.
     */
    fun finish(): ByteArray {
        closePage()
        val sink = java.io.ByteArrayOutputStream()
        document.writeTo(sink)
        document.close()
        return sink.toByteArray()
    }

    /** Pages produced, for the caller to sanity-check the output. */
    val pageCount: Int get() = pageNumber
}

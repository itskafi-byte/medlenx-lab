package com.medlenx.lab.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.medlenx.lab.data.local.PrescriptionDao
import com.medlenx.lab.data.repo.DashboardKpis
import com.medlenx.lab.data.repo.PyCsv
import com.medlenx.lab.data.repo.PyMath
import com.medlenx.lab.ui.components.avatarResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local, account-scoped exports. No photos or patient identifiers are included. */
object AnalyticsExportManager {
    private fun reportFile(context: Context, ext: String): File {
        val dir = File(context.cacheDir, "reports")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create report directory" }
        return File(dir, "medlenx-${UUID.randomUUID()}.$ext")
    }

    suspend fun csv(context: Context, dao: PrescriptionDao, uid: String): File {
        require(uid.isNotBlank())
        val rows = dao.scanExportRows(uid)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val file = reportFile(context, "csv")
        try {
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write(PyCsv.row(listOf("Date", "Doctor Name", "Specialty", "Medicine Name", "Manufacturer")))
                rows.forEach { row ->
                    // Spreadsheet applications treat these leading characters as formulas.
                    fun safe(s: String): String = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$s" else s
                    out.write(PyCsv.row(listOf(date.format(Date(row.createdAt)), row.doctorName, row.specialty, row.medicine, row.manufacturer).map(::safe)))
                }
            }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    fun pdf(context: Context, name: String, avatarUri: String?, avatarId: String?, kpis: DashboardKpis, filterCaption: String): File {
        val file = reportFile(context, "pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.WHITE)
            paint.color = Color.rgb(31, 43, 91)
            canvas.drawRect(0f, 0f, 595f, 125f, paint)
            paint.color = Color.WHITE; paint.textSize = 24f; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("MedLenX Lab", 40f, 52f, paint)
            paint.textSize = 13f; paint.typeface = Typeface.DEFAULT
            canvas.drawText("Executive analytics summary", 40f, 79f, paint)
            val bitmap = runCatching {
                if (!avatarUri.isNullOrBlank() && Uri.parse(avatarUri).scheme == "content")
                    context.contentResolver.openInputStream(Uri.parse(avatarUri))?.use { BitmapFactory.decodeStream(it) }
                else avatarResource(avatarId)?.let { id ->
                    ContextCompat.getDrawable(context, id)?.let { drawable ->
                        val image = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, 120, 120)
                        drawable.draw(Canvas(image))
                        image
                    }
                }
            }.getOrNull()
            if (bitmap != null) {
                val save = canvas.save()
                val path = android.graphics.Path().apply { addCircle(500f, 62f, 30f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(path)
                canvas.drawBitmap(bitmap, null, RectF(470f, 32f, 530f, 92f), paint)
                canvas.restoreToCount(save)
                bitmap.recycle()
            } else {
                paint.color = Color.LTGRAY; canvas.drawCircle(500f, 62f, 30f, paint)
                paint.color = Color.DKGRAY; paint.textSize = 20f
                canvas.drawText(name.trim().take(1).uppercase(Locale.ROOT).ifBlank { "?" }, 493f, 69f, paint)
            }
            paint.color = Color.rgb(31, 43, 91); paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 20f
            val textPaint = TextPaint(paint)
            canvas.drawText(TextUtils.ellipsize(name.ifBlank { "Officer" }, textPaint, 510f, TextUtils.TruncateAt.END).toString(), 40f, 172f, paint)
            paint.textSize = 11f; paint.typeface = Typeface.DEFAULT; paint.color = Color.DKGRAY
            canvas.drawText(TextUtils.ellipsize("Dashboard filters: $filterCaption", TextPaint(paint), 510f, TextUtils.TruncateAt.END).toString(), 40f, 195f, paint)
            val metrics = listOf("Total Captured" to kpis.totalPrescriptions.window.toString(),
                "Identified Medicines" to kpis.identifiedItems.toString(),
                "Target Share" to "${PyMath.fixed1(kpis.marketShare.percentage)}%",
                "Active Doctors" to kpis.doctorCoverage.active.toString())
            metrics.forEachIndexed { index, (label, value) ->
                val x = 40f + (index % 2) * 260f; val y = 230f + (index / 2) * 125f
                paint.color = Color.rgb(242, 245, 251); canvas.drawRoundRect(RectF(x, y, x + 245f, y + 105f), 10f, 10f, paint)
                paint.color = Color.DKGRAY; paint.textSize = 13f; paint.typeface = Typeface.DEFAULT
                canvas.drawText(label, x + 16f, y + 30f, paint)
                paint.color = Color.rgb(31, 43, 91); paint.textSize = 28f; paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(value, x + 16f, y + 72f, paint)
            }
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
            return file
        } catch (e: Exception) { file.delete(); throw e } finally { document.close() }
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report"))
    }
}

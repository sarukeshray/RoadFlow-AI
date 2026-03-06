package com.roadflow.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PdfGeneratorHelper — Generates an official damage report PDF
 * using Android's native PdfDocument API.
 *
 * The PDF contains:
 *   - Title: "RoadFlow-AI Official Damage Report"
 *   - Timestamp and GPS coordinates (mocked for now)
 *   - Annotated image with bounding boxes
 *   - RHI Score and grade
 *   - Detection summary
 *
 * Saves to the device's Downloads folder.
 */
object PdfGeneratorHelper {

    // A4 dimensions at 72 DPI
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40

    // Class colors matching OverlayView
    private val CLASS_COLORS = intArrayOf(
        Color.rgb(220, 53, 69),   // Pothole  — Red
        Color.rgb(255, 165, 0),   // Crack    — Orange
        Color.rgb(0, 123, 255)    // Manhole  — Blue
    )

    /**
     * Draw bounding boxes onto a bitmap and return the annotated copy.
     */
    fun drawDetectionsOnBitmap(
        original: Bitmap,
        detections: List<TFLiteHelper.Detection>
    ): Bitmap {
        val annotated = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val w = annotated.width.toFloat()
        val h = annotated.height.toFloat()

        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        val labelBgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            isFakeBoldText = true
        }

        for (det in detections) {
            val color = CLASS_COLORS.getOrElse(det.classId) { Color.GRAY }
            boxPaint.color = color
            labelBgPaint.color = color

            val left = det.boundingBox.left * w
            val top = det.boundingBox.top * h
            val right = det.boundingBox.right * w
            val bottom = det.boundingBox.bottom * h

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${det.className} ${(det.confidence * 100).toInt()}%"
            val textW = textPaint.measureText(label)
            val textH = textPaint.textSize

            canvas.drawRect(left, top - textH - 6f, left + textW + 12f, top, labelBgPaint)
            canvas.drawText(label, left + 6f, top - 4f, textPaint)
        }

        return annotated
    }

    /**
     * Generate a PDF report and save it to the Downloads folder.
     *
     * @return The filename of the saved PDF, or null on failure.
     */
    fun generateReport(
        context: Context,
        annotatedBitmap: Bitmap,
        detections: List<TFLiteHelper.Detection>,
        rhiScore: Int,
        rhiGrade: String
    ): String? {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "RoadFlowAI_Report_$fileTimestamp.pdf"

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        var yPos = MARGIN.toFloat()

        // --- Title ---
        val titlePaint = Paint().apply {
            color = Color.rgb(61, 220, 132) // Android green
            textSize = 22f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("RoadFlow-AI Official Damage Report", MARGIN.toFloat(), yPos + 22f, titlePaint)
        yPos += 40f

        // --- Divider line ---
        val dividerPaint = Paint().apply {
            color = Color.rgb(200, 200, 200)
            strokeWidth = 1f
        }
        canvas.drawLine(MARGIN.toFloat(), yPos, (PAGE_WIDTH - MARGIN).toFloat(), yPos, dividerPaint)
        yPos += 20f

        // --- Metadata ---
        val metaPaint = Paint().apply {
            color = Color.rgb(100, 100, 100)
            textSize = 11f
            isAntiAlias = true
        }
        canvas.drawText("Date: $timestamp", MARGIN.toFloat(), yPos, metaPaint)
        yPos += 16f
        canvas.drawText("GPS: 12.9716N, 77.5946E (approximate)", MARGIN.toFloat(), yPos, metaPaint)
        yPos += 16f
        canvas.drawText("Report ID: RF-$fileTimestamp", MARGIN.toFloat(), yPos, metaPaint)
        yPos += 30f

        // --- Annotated Image ---
        val availableWidth = PAGE_WIDTH - 2 * MARGIN
        val imgAspect = annotatedBitmap.width.toFloat() / annotatedBitmap.height
        val imgWidth = availableWidth
        val imgHeight = (imgWidth / imgAspect).toInt().coerceAtMost(350)

        val scaledBitmap = Bitmap.createScaledBitmap(annotatedBitmap, imgWidth, imgHeight, true)
        canvas.drawBitmap(scaledBitmap, MARGIN.toFloat(), yPos, null)
        yPos += imgHeight + 20f

        // --- RHI Score ---
        val rhiColor = when {
            rhiScore >= 75 -> Color.rgb(40, 167, 69)
            rhiScore >= 40 -> Color.rgb(255, 193, 7)
            else -> Color.rgb(220, 53, 69)
        }

        val rhiLabelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("ROAD HEALTH INDEX", MARGIN.toFloat(), yPos, rhiLabelPaint)
        yPos += 24f

        val rhiScorePaint = Paint().apply {
            color = rhiColor
            textSize = 36f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("$rhiScore / 100", MARGIN.toFloat(), yPos, rhiScorePaint)

        val gradePaint = Paint().apply {
            color = rhiColor
            textSize = 18f
            isAntiAlias = true
        }
        canvas.drawText(rhiGrade, MARGIN + 160f, yPos, gradePaint)
        yPos += 30f

        // --- Divider ---
        canvas.drawLine(MARGIN.toFloat(), yPos, (PAGE_WIDTH - MARGIN).toFloat(), yPos, dividerPaint)
        yPos += 20f

        // --- Detection Summary ---
        val summaryLabelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("DETECTED DAMAGES", MARGIN.toFloat(), yPos, summaryLabelPaint)
        yPos += 22f

        val detailPaint = Paint().apply {
            color = Color.rgb(60, 60, 60)
            textSize = 13f
            isAntiAlias = true
        }

        if (detections.isEmpty()) {
            canvas.drawText("No road damage detected.", MARGIN.toFloat(), yPos, detailPaint)
            yPos += 18f
        } else {
            // Group by class
            val grouped = detections.groupBy { it.className }
            for ((className, dets) in grouped) {
                val avgConf = (dets.sumOf { (it.confidence * 100).toInt() } / dets.size)
                val color = CLASS_COLORS.getOrElse(dets[0].classId) { Color.GRAY }
                detailPaint.color = color
                canvas.drawText(
                    "${dets.size}x $className (avg confidence: $avgConf%)",
                    MARGIN + 10f, yPos, detailPaint
                )
                yPos += 18f
            }

            yPos += 10f
            detailPaint.color = Color.rgb(60, 60, 60)
            canvas.drawText("Total detections: ${detections.size}", MARGIN.toFloat(), yPos, detailPaint)
            yPos += 18f
        }

        // --- Footer ---
        val footerPaint = Paint().apply {
            color = Color.rgb(150, 150, 150)
            textSize = 9f
            isAntiAlias = true
        }
        canvas.drawText(
            "Generated by RoadFlow-AI v1.0 | This is an automated report.",
            MARGIN.toFloat(),
            (PAGE_HEIGHT - MARGIN).toFloat(),
            footerPaint
        )

        document.finishPage(page)

        // --- Save PDF ---
        val saved = savePdfToDownloads(context, document, fileName)
        document.close()

        return if (saved) fileName else null
    }

    /**
     * Save PDF document to Downloads folder.
     * Uses MediaStore on API 29+, direct file access on older versions.
     */
    private fun savePdfToDownloads(
        context: Context,
        document: PdfDocument,
        fileName: String
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage (API 29+)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return false

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    document.writeTo(outputStream)
                }
                true
            } else {
                // Legacy storage (API < 29)
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    document.writeTo(outputStream)
                }
                true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }
}

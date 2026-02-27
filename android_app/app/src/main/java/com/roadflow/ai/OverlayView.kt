package com.roadflow.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * OverlayView — Custom View that draws bounding boxes and labels
 * over the camera preview for detected road damage.
 *
 * Color coding:
 *   - Pothole (class 0): Red
 *   - Crack   (class 1): Orange
 *   - Manhole (class 2): Blue
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<TFLiteHelper.Detection> = emptyList()

    // Box paint (stroke only)
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // Label background paint
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Label text paint
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Class colors
    private val classColors = intArrayOf(
        Color.rgb(220, 53, 69),   // Pothole  — Red
        Color.rgb(255, 165, 0),   // Crack    — Orange
        Color.rgb(0, 123, 255)    // Manhole  — Blue
    )

    /**
     * Update the detections to draw. Must be called on the UI thread.
     */
    fun setDetections(newDetections: List<TFLiteHelper.Detection>) {
        detections = newDetections
        invalidate() // Trigger redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        for (detection in detections) {
            val color = classColors.getOrElse(detection.classId) { Color.GRAY }
            boxPaint.color = color
            labelBgPaint.color = color

            // Scale normalized coordinates [0, 1] to view dimensions
            val rect = RectF(
                detection.boundingBox.left * viewWidth,
                detection.boundingBox.top * viewHeight,
                detection.boundingBox.right * viewWidth,
                detection.boundingBox.bottom * viewHeight
            )

            // Draw bounding box
            canvas.drawRect(rect, boxPaint)

            // Draw label background + text
            val label = "${detection.className} ${(detection.confidence * 100).toInt()}%"
            val textWidth = labelTextPaint.measureText(label)
            val textHeight = labelTextPaint.textSize

            val labelRect = RectF(
                rect.left,
                rect.top - textHeight - 8f,
                rect.left + textWidth + 16f,
                rect.top
            )
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(label, rect.left + 8f, rect.top - 6f, labelTextPaint)
        }
    }
}

package com.roadflow.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLiteHelper — Loads the YOLOv8 TFLite model, runs inference,
 * and post-processes output into a list of detections.
 *
 * Model: best_float32.tflite
 * Input:  (1, 640, 640, 3) float32, RGB normalized to [0, 1]
 * Output: (1, 7, 8400) float32 — 4 bbox + 3 class scores per candidate
 */
class TFLiteHelper(context: Context) {

    companion object {
        private const val MODEL_FILE = "best_float32.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CHANNELS = 3
        private const val NUM_CLASSES = 3
        private const val NUM_COORDS = 4 // x_center, y_center, width, height
        private const val NUM_DETECTIONS = 8400
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val NUM_THREADS = 4

        val CLASS_NAMES = arrayOf("Pothole", "Crack", "Manhole")
    }

    private val interpreter: Interpreter

    init {
        val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply {
            setNumThreads(NUM_THREADS)
        }
        interpreter = Interpreter(modelBuffer, options)
    }

    /**
     * Data class representing a single detection result.
     */
    data class Detection(
        val classId: Int,
        val className: String,
        val confidence: Float,
        val boundingBox: RectF // Normalized coordinates [0, 1]
    )

    /**
     * Run inference on a Bitmap and return filtered detections.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        // 1. Pre-process: resize and convert to float ByteBuffer
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = bitmapToByteBuffer(resized)

        // 2. Prepare output buffer: shape (1, 7, 8400)
        val outputArray = Array(1) { Array(NUM_COORDS + NUM_CLASSES) { FloatArray(NUM_DETECTIONS) } }

        // 3. Run inference
        interpreter.run(inputBuffer, outputArray)

        // 4. Post-process: parse, threshold, NMS
        val rawDetections = parseOutput(outputArray[0])
        return nonMaxSuppression(rawDetections)
    }

    /**
     * Convert a Bitmap to a float32 ByteBuffer normalized to [0, 1].
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * Parse YOLOv8 output tensor (7, 8400) into Detection candidates.
     *
     * Format: Row 0-3 = x_center, y_center, width, height (normalized)
     *         Row 4-6 = class confidence scores
     */
    private fun parseOutput(output: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in 0 until NUM_DETECTIONS) {
            // Find best class
            var maxConf = 0f
            var maxClassId = 0
            for (c in 0 until NUM_CLASSES) {
                val conf = output[NUM_COORDS + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClassId = c
                }
            }

            if (maxConf < CONFIDENCE_THRESHOLD) continue

            // Extract bbox (normalized 0-1)
            val xCenter = output[0][i]
            val yCenter = output[1][i]
            val width = output[2][i]
            val height = output[3][i]

            // YOLOv8 outputs coordinates in 640px space — normalize to [0, 1]
            val left = ((xCenter - width / 2f) / INPUT_SIZE).coerceIn(0f, 1f)
            val top = ((yCenter - height / 2f) / INPUT_SIZE).coerceIn(0f, 1f)
            val right = ((xCenter + width / 2f) / INPUT_SIZE).coerceIn(0f, 1f)
            val bottom = ((yCenter + height / 2f) / INPUT_SIZE).coerceIn(0f, 1f)

            val normalizedRect = RectF(left, top, right, bottom)

            detections.add(
                Detection(
                    classId = maxClassId,
                    className = CLASS_NAMES[maxClassId],
                    confidence = maxConf,
                    boundingBox = normalizedRect
                )
            )
        }

        return detections
    }

    /**
     * Non-Maximum Suppression to remove overlapping detections.
     */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        // Sort by confidence descending
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            sorted.removeAll { other ->
                other.classId == best.classId &&
                        computeIoU(best.boundingBox, other.boundingBox) > NMS_IOU_THRESHOLD
            }
        }

        return selected
    }

    /**
     * Compute Intersection over Union between two bounding boxes.
     */
    private fun computeIoU(a: RectF, b: RectF): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        val intersectArea = maxOf(0f, intersectRight - intersectLeft) *
                maxOf(0f, intersectBottom - intersectTop)

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)

        val unionArea = areaA + areaB - intersectArea
        return if (unionArea > 0f) intersectArea / unionArea else 0f
    }

    /**
     * Release interpreter resources.
     */
    fun close() {
        interpreter.close()
    }
}

package com.roadflow.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RoadDamageAnalyzer — CameraX ImageAnalysis.Analyzer that converts
 * camera frames to Bitmaps, runs TFLite inference, and reports detections.
 *
 * Includes frame throttling: skips frames while inference is in-flight
 * to maintain smooth camera preview.
 */
class RoadDamageAnalyzer(
    private val tfliteHelper: TFLiteHelper,
    private val onDetections: (List<TFLiteHelper.Detection>) -> Unit
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        // Skip frame if previous inference is still running
        if (isProcessing.get()) {
            imageProxy.close()
            return
        }

        isProcessing.set(true)

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val detections = tfliteHelper.detect(bitmap)
                onDetections(detections)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    /**
     * Convert an ImageProxy (YUV_420_888) to a Bitmap.
     * Also applies rotation correction based on imageProxy.imageInfo.rotationDegrees.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, outputStream)

        val jpegBytes = outputStream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

        // Apply rotation if needed
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
}

package com.roadflow.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LiveScannerActivity — Real-time road damage detection using CameraX + TFLite.
 *
 * This activity provides:
 *   1. Live camera preview via CameraX
 *   2. On-device YOLOv8 inference via TFLite
 *   3. Bounding box overlay for detected damage
 *   4. Real-time RHI (Road Health Index) scoring
 *
 * RHI Logic:
 *   score = max(0, 100 - (potholes * 15) - (cracks * 5))
 */
class LiveScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveScanner"
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var rhiScoreText: TextView
    private lateinit var detectionCountText: TextView
    private lateinit var tfliteHelper: TFLiteHelper
    private lateinit var cameraExecutor: ExecutorService

    // Permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for road analysis", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_scanner)

        // Initialize views
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        rhiScoreText = findViewById(R.id.rhiScoreText)
        detectionCountText = findViewById(R.id.detectionCountText)

        // Initialize TFLite model
        try {
            tfliteHelper = TFLiteHelper(this)
            Log.d(TAG, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            Toast.makeText(this, "Failed to load ML model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check and request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Set up CameraX with Preview and ImageAnalysis use cases.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // ImageAnalysis use case with our road damage analyzer
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            val analyzer = RoadDamageAnalyzer(tfliteHelper) { detections ->
                runOnUiThread {
                    onDetectionsReceived(detections)
                }
            }
            imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

            // Use back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Handle new detections: update overlay and compute RHI score.
     */
    private fun onDetectionsReceived(detections: List<TFLiteHelper.Detection>) {
        overlayView.setDetections(detections)

        var potholeCount = 0
        var crackCount = 0
        var manholeCount = 0

        for (detection in detections) {
            when (detection.classId) {
                0 -> potholeCount++
                1 -> crackCount++
                2 -> manholeCount++
            }
        }

        // RHI: base 100, -15 per pothole, -5 per crack
        val rhiScore = maxOf(0, 100 - (potholeCount * 15) - (crackCount * 5))

        rhiScoreText.text = "RHI: $rhiScore"
        rhiScoreText.setTextColor(getRhiColor(rhiScore))

        val countParts = mutableListOf<String>()
        if (potholeCount > 0) countParts.add("$potholeCount Pothole${if (potholeCount > 1) "s" else ""}")
        if (crackCount > 0) countParts.add("$crackCount Crack${if (crackCount > 1) "s" else ""}")
        if (manholeCount > 0) countParts.add("$manholeCount Manhole${if (manholeCount > 1) "s" else ""}")

        detectionCountText.text = if (countParts.isEmpty()) "Road Clear" else countParts.joinToString(" | ")
    }

    private fun getRhiColor(score: Int): Int {
        return when {
            score >= 75 -> Color.rgb(40, 167, 69)
            score >= 40 -> Color.rgb(255, 193, 7)
            else -> Color.rgb(220, 53, 69)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tfliteHelper.isInitialized) {
            tfliteHelper.close()
        }
    }
}

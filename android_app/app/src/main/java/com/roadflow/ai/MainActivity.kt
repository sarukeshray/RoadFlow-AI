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
 * MainActivity — Main entry point for the RoadFlow AI application.
 *
 * Responsibilities:
 *   1. Request camera permission
 *   2. Set up CameraX (Preview + ImageAnalysis)
 *   3. Receive detections and update the OverlayView + RHI score
 *
 * RHI Logic:
 *   score = max(0, 100 - (potholes * 15) - (cracks * 5))
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoadFlowAI"
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
        setContentView(R.layout.activity_main)

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
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to lifecycle
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
        // Update bounding box overlay
        overlayView.setDetections(detections)

        // Count damage types
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

        // Calculate RHI: base 100, -15 per pothole, -5 per crack
        val rhiScore = maxOf(0, 100 - (potholeCount * 15) - (crackCount * 5))

        // Update RHI display
        rhiScoreText.text = "RHI: $rhiScore"
        rhiScoreText.setTextColor(getRhiColor(rhiScore))

        // Update detection count
        val countParts = mutableListOf<String>()
        if (potholeCount > 0) countParts.add("$potholeCount Pothole${if (potholeCount > 1) "s" else ""}")
        if (crackCount > 0) countParts.add("$crackCount Crack${if (crackCount > 1) "s" else ""}")
        if (manholeCount > 0) countParts.add("$manholeCount Manhole${if (manholeCount > 1) "s" else ""}")

        detectionCountText.text = if (countParts.isEmpty()) {
            "Road Clear"
        } else {
            countParts.joinToString(" | ")
        }
    }

    /**
     * Return a color based on the RHI score.
     *   75-100: Green (Good)
     *   40-74:  Yellow/Orange (Fair)
     *   0-39:   Red (Critical)
     */
    private fun getRhiColor(score: Int): Int {
        return when {
            score >= 75 -> Color.rgb(40, 167, 69)    // Green
            score >= 40 -> Color.rgb(255, 193, 7)     // Yellow/Amber
            else -> Color.rgb(220, 53, 69)            // Red
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

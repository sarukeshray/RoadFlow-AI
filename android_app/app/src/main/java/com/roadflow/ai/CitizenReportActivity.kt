package com.roadflow.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.FirebaseDatabase

/**
 * CitizenReportActivity — Citizen damage reporting flow.
 *
 * Flow:
 *   1. User takes a photo or picks from gallery
 *   2. Image is run through YOLOv8 TFLite model
 *   3. Annotated image + RHI score displayed
 *   4. User can download a PDF report
 */
class CitizenReportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CitizenReport"
    }

    private lateinit var tfliteHelper: TFLiteHelper

    // UI — Initial state
    private lateinit var initialSection: LinearLayout
    private lateinit var btnTakePhoto: MaterialButton
    private lateinit var btnUploadGallery: MaterialButton

    // UI — Results state
    private lateinit var resultsSection: LinearLayout
    private lateinit var resultImageView: ImageView
    private lateinit var rhiScoreText: TextView
    private lateinit var rhiGradeText: TextView
    private lateinit var detectionSummaryText: TextView
    private lateinit var btnDownloadPdf: MaterialButton
    private lateinit var btnScanAnother: MaterialButton

    // State
    private var annotatedBitmap: Bitmap? = null
    private var lastDetections: List<TFLiteHelper.Detection> = emptyList()
    private var lastRhiScore: Int = 100
    private var lastRhiGrade: String = "Good"

    // --- Activity Result Launchers ---

    // Camera: capture a preview bitmap
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            processImage(bitmap)
        } else {
            Toast.makeText(this, "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery: pick an image
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                processImage(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image from gallery", e)
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Camera permission request
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePictureLauncher.launch(null)
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
        }
    }

    // Storage permission request (for API < 29)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            downloadPdfReport()
        } else {
            Toast.makeText(this, "Storage permission is required to save PDF", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_citizen_report)

        // Initialize TFLite
        try {
            tfliteHelper = TFLiteHelper(this)
            Log.d(TAG, "TFLite model loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            Toast.makeText(this, "Failed to load ML model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Bind views — Initial
        initialSection = findViewById(R.id.initialSection)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnUploadGallery = findViewById(R.id.btnUploadGallery)

        // Bind views — Results
        resultsSection = findViewById(R.id.resultsSection)
        resultImageView = findViewById(R.id.resultImageView)
        rhiScoreText = findViewById(R.id.rhiScoreText)
        rhiGradeText = findViewById(R.id.rhiGradeText)
        detectionSummaryText = findViewById(R.id.detectionSummaryText)
        btnDownloadPdf = findViewById(R.id.btnDownloadPdf)
        btnScanAnother = findViewById(R.id.btnScanAnother)

        // Back button
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Camera button
        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                takePictureLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Gallery button
        btnUploadGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Download PDF button
        btnDownloadPdf.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Need WRITE_EXTERNAL_STORAGE on API < 29
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@setOnClickListener
                }
            }
            downloadPdfReport()
        }

        // Scan Another button
        btnScanAnother.setOnClickListener {
            showInitialState()
        }

        // Start in initial state
        showInitialState()
    }

    /**
     * Process the acquired image: run inference, draw boxes, show results.
     */
    private fun processImage(bitmap: Bitmap) {
        Toast.makeText(this, "Analyzing image...", Toast.LENGTH_SHORT).show()

        try {
            // Run TFLite inference
            val detections = tfliteHelper.detect(bitmap)
            lastDetections = detections

            // Draw bounding boxes on a copy
            annotatedBitmap = PdfGeneratorHelper.drawDetectionsOnBitmap(bitmap, detections)

            // Calculate RHI
            var potholeCount = 0
            var crackCount = 0
            var manholeCount = 0

            for (det in detections) {
                when (det.classId) {
                    0 -> potholeCount++
                    1 -> crackCount++
                    2 -> manholeCount++
                }
            }

            lastRhiScore = maxOf(0, 100 - (potholeCount * 15) - (crackCount * 5))
            lastRhiGrade = when {
                lastRhiScore >= 75 -> "Good"
                lastRhiScore >= 50 -> "Fair"
                lastRhiScore >= 25 -> "Poor"
                else -> "Critical"
            }

            // Build detection summary text
            val parts = mutableListOf<String>()
            if (potholeCount > 0) parts.add("$potholeCount Pothole${if (potholeCount > 1) "s" else ""}")
            if (crackCount > 0) parts.add("$crackCount Crack${if (crackCount > 1) "s" else ""}")
            if (manholeCount > 0) parts.add("$manholeCount Manhole${if (manholeCount > 1) "s" else ""}")

            val summaryText = if (parts.isEmpty()) "No damage detected" else parts.joinToString(", ")

            // Push report to Firebase
            pushReportToFirebase(lastRhiScore, summaryText, potholeCount, crackCount, manholeCount)

            // Update UI
            showResultsState(summaryText)

        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            Toast.makeText(this, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Show the initial state: camera/gallery buttons visible, results hidden.
     */
    private fun showInitialState() {
        initialSection.visibility = View.VISIBLE
        resultsSection.visibility = View.GONE
        annotatedBitmap = null
        lastDetections = emptyList()
    }

    /**
     * Show the results state: annotated image, RHI score, PDF button.
     */
    private fun showResultsState(summary: String) {
        initialSection.visibility = View.GONE
        resultsSection.visibility = View.VISIBLE

        resultImageView.setImageBitmap(annotatedBitmap)

        rhiScoreText.text = "RHI: $lastRhiScore / 100"
        val rhiColor = when {
            lastRhiScore >= 75 -> Color.rgb(40, 167, 69)
            lastRhiScore >= 40 -> Color.rgb(255, 193, 7)
            else -> Color.rgb(220, 53, 69)
        }
        rhiScoreText.setTextColor(rhiColor)

        rhiGradeText.text = lastRhiGrade
        rhiGradeText.setTextColor(rhiColor)

        detectionSummaryText.text = summary
    }

    /**
     * Generate and save the PDF report.
     */
    private fun downloadPdfReport() {
        val bmp = annotatedBitmap
        if (bmp == null) {
            Toast.makeText(this, "No image to generate report from", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = PdfGeneratorHelper.generateReport(
            context = this,
            annotatedBitmap = bmp,
            detections = lastDetections,
            rhiScore = lastRhiScore,
            rhiGrade = lastRhiGrade
        )

        if (fileName != null) {
            Toast.makeText(this, "PDF saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Failed to save PDF report", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Push the damage report to Firebase Realtime Database.
     */
    private fun pushReportToFirebase(
        rhiScore: Int,
        summary: String,
        potholes: Int,
        cracks: Int,
        manholes: Int
    ) {
        try {
            val reportsRef = FirebaseDatabase.getInstance().getReference("reports")
            val newRef = reportsRef.push()
            val report = DamageReport(
                id = newRef.key ?: "",
                lat = 12.9716 + (Math.random() * 0.02 - 0.01),  // Mocked GPS with jitter
                lon = 77.5946 + (Math.random() * 0.02 - 0.01),
                rhiScore = rhiScore,
                damageSummary = summary,
                potholes = potholes,
                cracks = cracks,
                manholes = manholes,
                timestamp = System.currentTimeMillis(),
                status = "open"
            )
            newRef.setValue(report.toMap())
                .addOnSuccessListener {
                    Log.d(TAG, "Report pushed to Firebase: ${newRef.key}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to push report", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase push failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tfliteHelper.isInitialized) {
            tfliteHelper.close()
        }
    }
}

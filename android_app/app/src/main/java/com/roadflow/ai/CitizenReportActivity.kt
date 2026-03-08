package com.roadflow.ai

import android.Manifest
import android.annotation.SuppressLint
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.FirebaseDatabase

/**
 * CitizenReportActivity — Citizen damage reporting flow.
 *
 * Flow:
 *   1. User takes a photo or picks from gallery
 *   2. Image is run through YOLOv8 TFLite model
 *   3. Annotated image + RHI score displayed
 *   4. User clicks "Submit Report" to push to Firebase
 *   5. User can download a PDF report
 */
class CitizenReportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CitizenReport"
    }

    private lateinit var tfliteHelper: TFLiteHelper
    private lateinit var fusedLocationClient: FusedLocationProviderClient

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
    private lateinit var btnSubmitReport: MaterialButton
    private lateinit var btnDownloadPdf: MaterialButton
    private lateinit var btnScanAnother: MaterialButton

    // State
    private var annotatedBitmap: Bitmap? = null
    private var lastDetections: List<TFLiteHelper.Detection> = emptyList()
    private var lastRhiScore: Int = 100
    private var lastRhiGrade: String = "Good"
    private var lastSummaryText: String = ""
    private var lastPotholes: Int = 0
    private var lastCracks: Int = 0
    private var lastManholes: Int = 0
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0

    // --- Activity Result Launchers ---

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            processImage(bitmap)
        } else {
            Toast.makeText(this, "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

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

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePictureLauncher.launch(null)
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            downloadPdfReport()
        } else {
            Toast.makeText(this, "Storage permission is required to save PDF", Toast.LENGTH_LONG).show()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            fetchCurrentLocation()
        } else {
            Log.w(TAG, "Location permission denied")
            Toast.makeText(this, "Location unavailable. Using approximate coordinates.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_citizen_report)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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
        btnSubmitReport = findViewById(R.id.btnSubmitReport)
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

        // Submit Report button — pushes to Firebase
        btnSubmitReport.setOnClickListener {
            submitReportToFirebase()
        }

        // Download PDF button
        btnDownloadPdf.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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

        // Request location on launch
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            fetchCurrentLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        val cancellationToken = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLon = location.longitude
                    Log.d(TAG, "Got current location: $currentLat, $currentLon")
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            currentLat = lastLoc.latitude
                            currentLon = lastLoc.longitude
                            Log.d(TAG, "Got last known location: $currentLat, $currentLon")
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location", e)
            }
    }

    private fun processImage(bitmap: Bitmap) {
        Toast.makeText(this, "Analyzing image...", Toast.LENGTH_SHORT).show()

        try {
            val detections = tfliteHelper.detect(bitmap)
            lastDetections = detections

            annotatedBitmap = PdfGeneratorHelper.drawDetectionsOnBitmap(bitmap, detections)

            lastPotholes = 0
            lastCracks = 0
            lastManholes = 0

            for (det in detections) {
                when (det.classId) {
                    0 -> lastPotholes++
                    1 -> lastCracks++
                    2 -> lastManholes++
                }
            }

            lastRhiScore = maxOf(0, 100 - (lastPotholes * 15) - (lastCracks * 5))
            lastRhiGrade = when {
                lastRhiScore >= 75 -> "Good"
                lastRhiScore >= 50 -> "Fair"
                lastRhiScore >= 25 -> "Poor"
                else -> "Critical"
            }

            val parts = mutableListOf<String>()
            if (lastPotholes > 0) parts.add("$lastPotholes Pothole${if (lastPotholes > 1) "s" else ""}")
            if (lastCracks > 0) parts.add("$lastCracks Crack${if (lastCracks > 1) "s" else ""}")
            if (lastManholes > 0) parts.add("$lastManholes Manhole${if (lastManholes > 1) "s" else ""}")

            lastSummaryText = if (parts.isEmpty()) "No damage detected" else parts.joinToString(", ")

            // Show results — user must click "Submit Report" to push to Firebase
            showResultsState(lastSummaryText)

        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            Toast.makeText(this, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Called when user clicks "Submit Report" — pushes to Firebase with real GPS.
     */
    @SuppressLint("MissingPermission")
    private fun submitReportToFirebase() {
        btnSubmitReport.isEnabled = false
        btnSubmitReport.text = "Submitting..."

        // Refresh location right before submit
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLon = location.longitude
                }
                pushToFirebase()
            }.addOnFailureListener {
                pushToFirebase()
            }
        } else {
            pushToFirebase()
        }
    }

    private fun pushToFirebase() {
        try {
            val database = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
            val reportsRef = database.getReference("reports")
            val newRef = reportsRef.push()

            val report = DamageReport(
                id = newRef.key ?: "",
                lat = currentLat,
                lon = currentLon,
                rhiScore = lastRhiScore,
                damageSummary = lastSummaryText,
                potholes = lastPotholes,
                cracks = lastCracks,
                manholes = lastManholes,
                timestamp = System.currentTimeMillis(),
                status = "open"
            )

            Log.d(TAG, "Pushing to Firebase: url=${AppConstants.FIREBASE_DB_URL}, key=${newRef.key}")

            newRef.setValue(report.toMap())
                .addOnSuccessListener {
                    Log.d(TAG, "Report pushed successfully: ${newRef.key}")
                    Toast.makeText(this, "Report submitted successfully!", Toast.LENGTH_LONG).show()
                    btnSubmitReport.text = "Submitted!"
                    btnSubmitReport.setBackgroundColor(Color.rgb(40, 167, 69))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firebase push FAILED: ${e.message}", e)
                    Toast.makeText(this, "Submit failed: ${e.message}", Toast.LENGTH_LONG).show()
                    btnSubmitReport.isEnabled = true
                    btnSubmitReport.text = "Submit Report"
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase exception: ${e.message}", e)
            Toast.makeText(this, "Database error: ${e.message}", Toast.LENGTH_LONG).show()
            btnSubmitReport.isEnabled = true
            btnSubmitReport.text = "Submit Report"
        }
    }

    private fun showInitialState() {
        initialSection.visibility = View.VISIBLE
        resultsSection.visibility = View.GONE
        annotatedBitmap = null
        lastDetections = emptyList()
    }

    private fun showResultsState(summary: String) {
        initialSection.visibility = View.GONE
        resultsSection.visibility = View.VISIBLE

        // Reset submit button
        btnSubmitReport.isEnabled = true
        btnSubmitReport.text = "Submit Report"

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

    override fun onDestroy() {
        super.onDestroy()
        if (::tfliteHelper.isInitialized) {
            tfliteHelper.close()
        }
    }
}

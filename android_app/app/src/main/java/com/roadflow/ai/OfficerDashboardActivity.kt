package com.roadflow.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * OfficerDashboardActivity — PWD Officer Dashboard.
 *
 * Features:
 *   1. Full-screen Google Map with red markers for open damage reports
 *   2. Bottom Sheet with RecyclerView of report cards
 *   3. Firebase Realtime Database listener for live updates
 *   4. AI-powered repair verification via camera + TFLite
 */
class OfficerDashboardActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "OfficerDashboard"
        private val DEFAULT_LOCATION = LatLng(12.9716, 77.5946) // Bangalore
        private const val DEFAULT_ZOOM = 13f
    }

    private var googleMap: GoogleMap? = null
    private lateinit var reportAdapter: ReportAdapter
    private lateinit var tvReportCount: TextView
    private lateinit var tfliteHelper: TFLiteHelper

    private val markers = mutableMapOf<String, Marker>() // reportId -> Marker
    private val openReports = mutableListOf<DamageReport>()
    private var verifyingReportId: String? = null // Track which report is being verified

    private var firebaseListener: ValueEventListener? = null

    // Camera launcher for repair verification
    private val verifyPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            handleVerificationPhoto(bitmap)
        } else {
            Toast.makeText(this, "Camera cancelled", Toast.LENGTH_SHORT).show()
            verifyingReportId = null
        }
    }

    // Camera permission for verification
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            verifyPhotoLauncher.launch(null)
        } else {
            Toast.makeText(this, "Camera permission required for verification", Toast.LENGTH_LONG).show()
            verifyingReportId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_officer_dashboard)

        // Initialize TFLite
        try {
            tfliteHelper = TFLiteHelper(this)
            Log.d(TAG, "TFLite model loaded for verification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            Toast.makeText(this, "ML model unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Back button
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Report count badge
        tvReportCount = findViewById(R.id.tvReportCount)

        // Bottom Sheet setup
        val bottomSheet = findViewById<android.widget.LinearLayout>(R.id.bottomSheet)
        val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        // RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.rvReports)
        reportAdapter = ReportAdapter { report ->
            onVerifyRepairClicked(report)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = reportAdapter

        // Initialize Map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Dark mode map styling (simple approach)
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false

        // Move camera to default location
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))

        // Start listening to Firebase once map is ready
        startFirebaseListener()
    }

    /**
     * Listen to Firebase Realtime Database for open reports.
     */
    private fun startFirebaseListener() {
        val reportsRef = FirebaseDatabase.getInstance().getReference("reports")

        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                openReports.clear()

                for (child in snapshot.children) {
                    try {
                        val report = child.getValue(DamageReport::class.java)
                        if (report != null && report.status == "open") {
                            // Ensure the ID is set from the Firebase key
                            val reportWithId = report.copy(id = child.key ?: report.id)
                            openReports.add(reportWithId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse report: ${child.key}", e)
                    }
                }

                // Sort by newest first
                openReports.sortByDescending { it.timestamp }

                // Update UI
                updateMapMarkers()
                reportAdapter.submitList(openReports.toList())
                tvReportCount.text = "${openReports.size} report${if (openReports.size != 1) "s" else ""}"

                Log.d(TAG, "Loaded ${openReports.size} open reports")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                Toast.makeText(
                    this@OfficerDashboardActivity,
                    "Failed to load reports: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        reportsRef.addValueEventListener(firebaseListener!!)
    }

    /**
     * Update Google Map markers based on open reports.
     */
    private fun updateMapMarkers() {
        val map = googleMap ?: return

        // Clear existing markers
        markers.values.forEach { it.remove() }
        markers.clear()

        // Add markers for open reports
        for (report in openReports) {
            val position = LatLng(report.lat, report.lon)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("RHI: ${report.rhiScore}")
                    .snippet(report.damageSummary)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            if (marker != null) {
                markers[report.id] = marker
            }
        }
    }

    /**
     * Handle "Verify Repair" button click — launch camera.
     */
    private fun onVerifyRepairClicked(report: DamageReport) {
        verifyingReportId = report.id
        Toast.makeText(this, "Take a photo of the repaired road", Toast.LENGTH_SHORT).show()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            verifyPhotoLauncher.launch(null)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Process the verification photo through TFLite.
     * If RHI == 100 (no damage), mark as resolved.
     */
    private fun handleVerificationPhoto(bitmap: Bitmap) {
        val reportId = verifyingReportId
        if (reportId == null) {
            Toast.makeText(this, "No report selected for verification", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Analyzing repair...", Toast.LENGTH_SHORT).show()

        try {
            if (!::tfliteHelper.isInitialized) {
                Toast.makeText(this, "ML model not available", Toast.LENGTH_LONG).show()
                verifyingReportId = null
                return
            }

            // Run inference
            val detections = tfliteHelper.detect(bitmap)

            // Count damage types
            var potholeCount = 0
            var crackCount = 0

            for (det in detections) {
                when (det.classId) {
                    0 -> potholeCount++
                    1 -> crackCount++
                }
            }

            val rhiScore = maxOf(0, 100 - (potholeCount * 15) - (crackCount * 5))

            if (rhiScore == 100) {
                // Road is clean — mark as resolved
                resolveReport(reportId)
                Toast.makeText(
                    this,
                    "Repair verified! RHI: 100. Report marked as resolved.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Damage still detected
                Toast.makeText(
                    this,
                    "Damage still detected (RHI: $rhiScore). Repair rejected.\n" +
                            "Found: ${potholeCount} potholes, ${crackCount} cracks",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Verification inference failed", e)
            Toast.makeText(this, "Verification failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            verifyingReportId = null
        }
    }

    /**
     * Update Firebase report status to "resolved".
     */
    private fun resolveReport(reportId: String) {
        val reportRef = FirebaseDatabase.getInstance()
            .getReference("reports")
            .child(reportId)

        reportRef.child("status").setValue("resolved")
            .addOnSuccessListener {
                Log.d(TAG, "Report $reportId resolved")
                // Marker will be removed automatically by the ValueEventListener
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to resolve report $reportId", e)
                Toast.makeText(this, "Failed to update report status", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Remove Firebase listener
        if (firebaseListener != null) {
            FirebaseDatabase.getInstance().getReference("reports")
                .removeEventListener(firebaseListener!!)
        }

        // Release TFLite
        if (::tfliteHelper.isInitialized) {
            tfliteHelper.close()
        }
    }
}

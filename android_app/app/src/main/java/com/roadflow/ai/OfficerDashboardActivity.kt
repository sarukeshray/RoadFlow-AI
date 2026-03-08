package com.roadflow.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
 *   1. Full-screen Google Map with blue dot (My Location)
 *   2. Auto-zoom to current location
 *   3. Red markers for open damage reports from Firebase
 *   4. Bottom Sheet with RecyclerView of report cards
 *   5. AI-powered repair verification via camera + TFLite
 */
class OfficerDashboardActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "OfficerDashboard"
        private val DEFAULT_LOCATION = LatLng(12.9716, 77.5946) // Fallback
        private const val DEFAULT_ZOOM = 13f
        private const val MY_LOCATION_ZOOM = 15f
    }

    private var googleMap: GoogleMap? = null
    private lateinit var reportAdapter: ReportAdapter
    private lateinit var tvReportCount: TextView
    private lateinit var tfliteHelper: TFLiteHelper
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val markers = mutableMapOf<String, Marker>()
    private val openReports = mutableListOf<DamageReport>()
    private var verifyingReportId: String? = null

    private var firebaseListener: ValueEventListener? = null
    private var firebaseAvailable = false

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

    // Location permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (granted) {
            enableMyLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_officer_dashboard)

        // Location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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

        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = true

        // Move to default first, then try to get real location
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))

        // Request location permission and enable blue dot
        requestLocationAndEnableBlueDot()

        // Start Firebase listener
        try {
            startFirebaseListener()
            firebaseAvailable = true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase not available: ${e.message}")
            tvReportCount.text = "Firebase offline"
            Toast.makeText(this, "Firebase not configured. Map is in offline mode.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Request location permission, enable blue dot, and zoom to current location.
     */
    private fun requestLocationAndEnableBlueDot() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * Enable the blue dot and animate camera to current location.
     */
    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        val map = googleMap ?: return

        // Enable the blue dot
        map.isMyLocationEnabled = true

        // Animate to current location
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val myLatLng = LatLng(location.latitude, location.longitude)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(myLatLng, MY_LOCATION_ZOOM))
                Log.d(TAG, "Moved camera to: ${location.latitude}, ${location.longitude}")
            }
        }
    }

    /**
     * Listen to Firebase Realtime Database for open reports.
     */
    private fun startFirebaseListener() {
        val reportsRef = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL).getReference("reports")

        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                openReports.clear()

                for (child in snapshot.children) {
                    try {
                        val report = child.getValue(DamageReport::class.java)
                        if (report != null && report.status == "open") {
                            val reportWithId = report.copy(id = child.key ?: report.id)
                            openReports.add(reportWithId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse report: ${child.key}", e)
                    }
                }

                openReports.sortByDescending { it.timestamp }

                updateMapMarkers()
                reportAdapter.submitList(openReports.toList())
                tvReportCount.text = "${openReports.size} report${if (openReports.size != 1) "s" else ""}"

                Log.d(TAG, "Loaded ${openReports.size} open reports from Firebase")
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

    private fun updateMapMarkers() {
        val map = googleMap ?: return

        markers.values.forEach { it.remove() }
        markers.clear()

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

            val detections = tfliteHelper.detect(bitmap)

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
                resolveReport(reportId)
                Toast.makeText(
                    this,
                    "Repair verified! RHI: 100. Report marked as resolved.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Damage still detected (RHI: $rhiScore). Repair rejected.\n" +
                            "Found: $potholeCount potholes, $crackCount cracks",
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

    private fun resolveReport(reportId: String) {
        try {
            val reportRef = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
                .getReference("reports")
                .child(reportId)

            reportRef.child("status").setValue("resolved")
                .addOnSuccessListener {
                    Log.d(TAG, "Report $reportId resolved")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to resolve report $reportId", e)
                    Toast.makeText(this, "Failed to update report status", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase not available for resolve", e)
            Toast.makeText(this, "Firebase not configured", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (firebaseAvailable && firebaseListener != null) {
            try {
                FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL).getReference("reports")
                    .removeEventListener(firebaseListener!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove Firebase listener", e)
            }
        }

        if (::tfliteHelper.isInitialized) {
            tfliteHelper.close()
        }
    }
}

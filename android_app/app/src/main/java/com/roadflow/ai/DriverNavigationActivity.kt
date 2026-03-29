package com.roadflow.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.AutocompleteActivity
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DriverNavigationActivity — Smart route navigation with Active Tracking.
 *
 * Features:
 *   1. Google Map with current location
 *   2. Places Autocomplete search for destinations
 *   3. Vehicle toggle (Car vs Two-Wheeler)
 *   4. Directions API with alternative routes and modes
 *   5. Route scoring against Firebase damage reports
 *   6. Active Nav Mode: 3D Tracking camera
 *   7. Proximity Alerts: Audio-visual warnings for upcoming damage
 */
class DriverNavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "DriverNavigation"
        private const val MY_LOCATION_ZOOM = 15f
        private const val ALERT_DISTANCE_METERS = 50.0f
    }

    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null
    private var apiKey: String = ""

    // UI
    private lateinit var topOverlay: LinearLayout
    private lateinit var searchCard: MaterialCardView
    private lateinit var vehicleToggleGroup: MaterialButtonToggleGroup
    private lateinit var routeInfoBar: LinearLayout
    private lateinit var tvRouteTitle: TextView
    private lateinit var tvRouteDetails: TextView
    private lateinit var tvAlternativeInfo: TextView
    private lateinit var tvSearchHint: TextView
    private lateinit var btnStartNavigation: MaterialButton
    private lateinit var alertCard: MaterialCardView
    private lateinit var tvAlertText: TextView

    // Firebase reports cache
    private val openReports = mutableListOf<DamageReport>()
    private var firebaseListener: ValueEventListener? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // State
    private var isActiveNavMode: Boolean = false
    private var selectedVehicleMode: String = "driving"
    private var activeScoredRoute: RouteScorer.ScoredRoute? = null
    
    // Tracking & Proximity
    private var locationCallback: LocationCallback? = null
    private var toneGenerator: ToneGenerator? = null
    private var isAlertActive: Boolean = false

    // Places Autocomplete launcher
    private val autocompleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_OK -> {
                val place = Autocomplete.getPlaceFromIntent(result.data!!)
                Log.d(TAG, "Place selected: ${place.name}, ${place.latLng}")
                tvSearchHint.text = place.name ?: "Selected destination"
                tvSearchHint.setTextColor(Color.WHITE)
                place.latLng?.let { destination ->
                    destinationLatLng = destination
                    fetchRoutes()
                }
            }
            AutocompleteActivity.RESULT_ERROR -> {
                val status = Autocomplete.getStatusFromIntent(result.data!!)
                Log.e(TAG, "Autocomplete error: ${status.statusMessage}")
                Toast.makeText(this, "Search error: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
            RESULT_CANCELED -> {
                Log.d(TAG, "Autocomplete cancelled")
            }
        }
    }

    // Location permission
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
        setContentView(R.layout.activity_driver_navigation)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Get API key from BuildConfig (injected from local.properties via Gradle)
        apiKey = BuildConfig.MAPS_API_KEY

        // Initialize Places SDK
        if (!Places.isInitialized() && apiKey.isNotEmpty()) {
            Places.initialize(applicationContext, apiKey)
        }

        // Bind views
        topOverlay = findViewById(R.id.topOverlay)
        searchCard = findViewById(R.id.searchCard)
        vehicleToggleGroup = findViewById(R.id.vehicleToggleGroup)
        routeInfoBar = findViewById(R.id.routeInfoBar)
        tvRouteTitle = findViewById(R.id.tvRouteTitle)
        tvRouteDetails = findViewById(R.id.tvRouteDetails)
        tvAlternativeInfo = findViewById(R.id.tvAlternativeInfo)
        tvSearchHint = findViewById(R.id.tvSearchHint)
        btnStartNavigation = findViewById(R.id.btnStartNavigation)
        alertCard = findViewById(R.id.alertCard)
        tvAlertText = findViewById(R.id.tvAlertText)

        // Back button
        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }

        // Search card tap → launch Places Autocomplete
        searchCard.setOnClickListener {
            launchPlacesAutocomplete()
        }

        // Vehicle toggle
        vehicleToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedVehicleMode = if (checkedId == R.id.btnCar) "driving" else "bicycling"
                // Re-fetch routes if destination is already set
                if (destinationLatLng != null) {
                    fetchRoutes()
                }
            }
        }

        // Start Navigation
        btnStartNavigation.setOnClickListener {
            enterActiveNavMode()
        }

        // Initialize Map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragmentDriver) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Start fetching Firebase reports early
        fetchFirebaseReports()
    }

    private fun launchPlacesAutocomplete() {
        try {
            val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)
            val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields)
                .build(this)
            autocompleteLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch autocomplete", e)
            Toast.makeText(this, "Search unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = true

        // Default camera
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(12.9716, 77.5946), 13f))

        // Request location
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        val map = googleMap ?: return
        map.isMyLocationEnabled = true

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                currentLatLng = LatLng(location.latitude, location.longitude)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng!!, MY_LOCATION_ZOOM))
            }
        }
    }

    /**
     * Fetch all open damage reports from Firebase (cached for route scoring).
     */
    private fun fetchFirebaseReports() {
        try {
            val reportsRef = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
                .getReference("reports")

            firebaseListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    openReports.clear()
                    for (child in snapshot.children) {
                        try {
                            val report = child.getValue(DamageReport::class.java)
                            if (report != null && report.status == "open") {
                                openReports.add(report.copy(id = child.key ?: report.id))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse report", e)
                        }
                    }
                    Log.d(TAG, "Loaded ${openReports.size} open reports for route scoring")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase cancelled: ${error.message}")
                }
            }
            reportsRef.addValueEventListener(firebaseListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase not available: ${e.message}")
        }
    }

    /**
     * Call Google Directions API with alternatives and selected vehicle mode.
     */
    private fun fetchRoutes(fallbackToDriving: Boolean = false) {
        val origin = currentLatLng
        val dest = destinationLatLng
        
        if (origin == null) {
            Toast.makeText(this, "Waiting for current location...", Toast.LENGTH_SHORT).show()
            return
        }
        if (dest == null) return

        if (!fallbackToDriving) {
            Toast.makeText(this, "Finding safest routes...", Toast.LENGTH_SHORT).show()
        }
        
        // Hide start button until routes are loaded
        btnStartNavigation.visibility = View.GONE

        val modeToUse = if (fallbackToDriving) "driving" else selectedVehicleMode
        val url = "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=${origin.latitude},${origin.longitude}" +
                "&destination=${dest.latitude},${dest.longitude}" +
                "&alternatives=true" +
                "&mode=$modeToUse" +
                "&key=$apiKey"

        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Directions API failed", e)
                runOnUiThread {
                    Toast.makeText(this@DriverNavigationActivity,
                        "Failed to fetch routes: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (body == null) {
                    runOnUiThread {
                        Toast.makeText(this@DriverNavigationActivity,
                            "Empty response from Directions API", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                try {
                    val json = JSONObject(body)
                    val status = json.getString("status")

                    // Fallback to driving if bicycling/two-wheeler returns ZERO_RESULTS (common in some regions)
                    if (status == "ZERO_RESULTS" && selectedVehicleMode != "driving" && !fallbackToDriving) {
                        runOnUiThread {
                            Toast.makeText(this@DriverNavigationActivity, "Two-wheeler route unavailable. Falling back to Car route.", Toast.LENGTH_SHORT).show()
                            fetchRoutes(fallbackToDriving = true)
                        }
                        return
                    }

                    if (status != "OK") {
                        runOnUiThread {
                            Toast.makeText(this@DriverNavigationActivity,
                                "Directions API: $status", Toast.LENGTH_LONG).show()
                        }
                        return
                    }

                    val routesArray = json.getJSONArray("routes")
                    val routes = mutableListOf<RouteScorer.RouteData>()

                    for (i in 0 until routesArray.length()) {
                        val route = routesArray.getJSONObject(i)
                        val overviewPolyline = route.getJSONObject("overview_polyline")
                            .getString("points")

                        val leg = route.getJSONArray("legs").getJSONObject(0)
                        val duration = leg.getJSONObject("duration").getString("text")
                        val distance = leg.getJSONObject("distance").getString("text")
                        val summary = route.optString("summary", "Route ${i + 1}")

                        routes.add(
                            RouteScorer.RouteData(
                                overviewPolyline = overviewPolyline,
                                durationText = duration,
                                distanceText = distance,
                                summary = summary
                            )
                        )
                    }

                    // Score routes against damage reports
                    val scoredRoutes = RouteScorer.scoreRoutes(routes, openReports.toList())

                    runOnUiThread {
                        drawScoredRoutes(scoredRoutes, dest)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse Directions response", e)
                    runOnUiThread {
                        Toast.makeText(this@DriverNavigationActivity,
                            "Failed to parse routes", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Draw scored routes on the map.
     */
    private fun drawScoredRoutes(
        scoredRoutes: List<RouteScorer.ScoredRoute>,
        destination: LatLng
    ) {
        val map = googleMap ?: return
        map.clear()

        try {
            val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                map.isMyLocationEnabled = true
            }
        } catch (_: Exception) {}

        if (scoredRoutes.isEmpty()) {
            Toast.makeText(this, "No routes found", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Draw Alternatives First
        for (i in scoredRoutes.indices.reversed()) {
            val route = scoredRoutes[i]
            val color = if (i == 0) Color.rgb(40, 167, 69) else Color.rgb(128, 128, 128)
            val width = if (i == 0) 14f else 8f
            val zIndex = if (i == 0) 10f else 1f

            map.addPolyline(
                PolylineOptions()
                    .addAll(route.polyline)
                    .color(color)
                    .width(width)
                    .zIndex(zIndex)
                    .geodesic(true)
            )
        }

        // 2. Dest Marker
        map.addMarker(
            MarkerOptions()
                .position(destination)
                .title("Destination")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )

        // 3. Hazards on safest route
        val safestRoute = scoredRoutes[0]
        for (report in safestRoute.intersectingReports) {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(report.lat, report.lon))
                    .title("Hazard: RHI ${report.rhiScore}")
                    .snippet(report.damageSummary)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
        }

        // 4. Hazards on alternatives
        for (i in 1 until scoredRoutes.size) {
            for (report in scoredRoutes[i].intersectingReports) {
                if (!safestRoute.intersectingReports.any { it.id == report.id }) {
                    map.addMarker(
                        MarkerOptions()
                            .position(LatLng(report.lat, report.lon))
                            .title("Hazard: RHI ${report.rhiScore}")
                            .snippet(report.damageSummary)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    )
                }
            }
        }

        // Zoom bounds
        val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
        for (point in safestRoute.polyline) { boundsBuilder.include(point) }
        currentLatLng?.let { boundsBuilder.include(it) }
        boundsBuilder.include(destination)

        try {
            val bounds = boundsBuilder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to zoom to route bounds", e)
        }

        // Store active route for tracking
        activeScoredRoute = safestRoute

        // Update UI
        updateRouteInfoBar(scoredRoutes)
        btnStartNavigation.visibility = View.VISIBLE
    }

    private fun updateRouteInfoBar(scoredRoutes: List<RouteScorer.ScoredRoute>) {
        routeInfoBar.visibility = View.VISIBLE

        val safest = scoredRoutes[0]

        tvRouteTitle.text = if (safest.damageCount == 0) {
            "Safest Route — No Hazards!"
        } else {
            "Safest Route — ${safest.damageCount} hazard${if (safest.damageCount > 1) "s" else ""}"
        }

        tvRouteTitle.setTextColor(
            if (safest.damageCount == 0) Color.rgb(40, 167, 69) else Color.rgb(255, 193, 7)
        )

        tvRouteDetails.text = "${safest.durationText} · ${safest.distanceText} via ${safest.summary}"

        if (scoredRoutes.size > 1) {
            val altInfo = scoredRoutes.drop(1).joinToString(" | ") {
                "${it.summary}: ${it.damageCount} hazards"
            }
            tvAlternativeInfo.text = "Alternatives: $altInfo"
            tvAlternativeInfo.visibility = View.VISIBLE
        } else {
            tvAlternativeInfo.visibility = View.GONE
        }
    }

    // ==========================================
    // Active Navigation Mode: Tracking & Alerts
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun enterActiveNavMode() {
        if (activeScoredRoute == null) return

        isActiveNavMode = true

        // UI Changes
        searchCard.visibility = View.GONE
        vehicleToggleGroup.visibility = View.GONE
        btnStartNavigation.visibility = View.GONE
        
        // Hide toolbar/button overlay from maps
        googleMap?.uiSettings?.isMyLocationButtonEnabled = false
        googleMap?.uiSettings?.isCompassEnabled = true

        // Setup ToneGenerator for alarms
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

        // Request continuous location updates
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
            
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    currentLatLng = LatLng(location.latitude, location.longitude)
                    updateTrackingCamera(location)
                    checkProximityAlerts(location)
                }
            }
        }

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        }
    }

    private fun updateTrackingCamera(location: Location) {
        val map = googleMap ?: return
        
        val builder = CameraPosition.builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(18f) // close-up zoom
            .tilt(60f) // 3D viewpoint

        // Only update bearing if we actually moved enough to have one
        if (location.hasBearing()) {
            builder.bearing(location.bearing)
        }
        
        map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
    }

    private fun checkProximityAlerts(location: Location) {
        val route = activeScoredRoute ?: return
        
        var isNearDamage = false
        val results = FloatArray(1)
        
        for (report in route.intersectingReports) {
            Location.distanceBetween(
                location.latitude, location.longitude,
                report.lat, report.lon,
                results
            )
            val distanceToReport = results[0]
            
            if (distanceToReport <= ALERT_DISTANCE_METERS) {
                isNearDamage = true
                break
            }
        }
        
        if (isNearDamage && !isAlertActive) {
            // Entered alert zone
            isAlertActive = true
            alertCard.visibility = View.VISIBLE
            // Play alarm beep
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
            
        } else if (!isNearDamage && isAlertActive) {
            // Left alert zone
            isAlertActive = false
            alertCard.visibility = View.GONE
        }
    }

    private fun exitActiveNavMode() {
        isActiveNavMode = false
        
        // Stop updates
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        
        // Release ToneGenerator
        toneGenerator?.release()
        toneGenerator = null
        
        // Hide alerts
        isAlertActive = false
        alertCard.visibility = View.GONE

        // Restore UI
        searchCard.visibility = View.VISIBLE
        vehicleToggleGroup.visibility = View.VISIBLE
        btnStartNavigation.visibility = View.VISIBLE
        googleMap?.uiSettings?.isMyLocationButtonEnabled = true
        googleMap?.uiSettings?.isCompassEnabled = false

        // Zoom out to route overview
        activeScoredRoute?.let { route ->
            val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
            for (point in route.polyline) { boundsBuilder.include(point) }
            currentLatLng?.let { boundsBuilder.include(it) }
            destinationLatLng?.let { boundsBuilder.include(it) }
            
            try {
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100))
            } catch (e: Exception) {
                // Ignore if bounds is empty or view not ready
            }
        }
    }

    override fun onBackPressed() {
        if (isActiveNavMode) {
            exitActiveNavMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (isActiveNavMode) {
            exitActiveNavMode()
        }

        if (firebaseListener != null) {
            try {
                FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
                    .getReference("reports")
                    .removeEventListener(firebaseListener!!)
            } catch (_: Exception) {}
        }
    }
}

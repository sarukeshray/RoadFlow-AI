package com.roadflow.ai

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil

/**
 * RouteScorer — Scores routes by counting intersecting damage reports.
 *
 * Uses PolyUtil.isLocationOnPath() with a tolerance to determine
 * if a DamageReport falls on a given route polyline.
 * Lower score (fewer damage intersections) = safer route.
 */
object RouteScorer {

    private const val TOLERANCE_METERS = 25.0 // 25m proximity to route

    /**
     * A scored route with its original polyline, damage count, and intersecting reports.
     */
    data class ScoredRoute(
        val index: Int,
        val polyline: List<LatLng>,
        val overviewPolyline: String,  // encoded polyline string
        val damageCount: Int,
        val intersectingReports: List<DamageReport>,
        val durationText: String,
        val distanceText: String,
        val summary: String
    )

    /**
     * Score a list of routes against open damage reports.
     *
     * @param routes List of decoded route polylines with metadata
     * @param reports List of open DamageReport from Firebase
     * @return Routes sorted by safety (fewest intersections first)
     */
    fun scoreRoutes(
        routes: List<RouteData>,
        reports: List<DamageReport>
    ): List<ScoredRoute> {

        return routes.mapIndexed { index, route ->
            val decodedPoints = PolyUtil.decode(route.overviewPolyline)
            val intersecting = mutableListOf<DamageReport>()

            for (report in reports) {
                if (report.lat == 0.0 && report.lon == 0.0) continue // Skip invalid

                val reportLatLng = LatLng(report.lat, report.lon)
                val isOnPath = PolyUtil.isLocationOnPath(
                    reportLatLng,
                    decodedPoints,
                    true,  // geodesic
                    TOLERANCE_METERS
                )

                if (isOnPath) {
                    intersecting.add(report)
                }
            }

            ScoredRoute(
                index = index,
                polyline = decodedPoints,
                overviewPolyline = route.overviewPolyline,
                damageCount = intersecting.size,
                intersectingReports = intersecting,
                durationText = route.durationText,
                distanceText = route.distanceText,
                summary = route.summary
            )
        }.sortedBy { it.damageCount } // Safest first
    }

    /**
     * Raw route data parsed from Directions API response.
     */
    data class RouteData(
        val overviewPolyline: String,
        val durationText: String,
        val distanceText: String,
        val summary: String
    )
}

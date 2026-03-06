package com.roadflow.ai

/**
 * DamageReport — Data model for Firebase Realtime Database.
 *
 * Firebase requires a no-arg constructor for deserialization.
 * Stored under "reports/" node in the database.
 */
data class DamageReport(
    val id: String = "",
    val lat: Double = 12.9716,
    val lon: Double = 77.5946,
    val rhiScore: Int = 100,
    val damageSummary: String = "",
    val potholes: Int = 0,
    val cracks: Int = 0,
    val manholes: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "open"  // "open" | "resolved"
) {
    /**
     * Convert to a Map for Firebase push.
     */
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "lat" to lat,
        "lon" to lon,
        "rhiScore" to rhiScore,
        "damageSummary" to damageSummary,
        "potholes" to potholes,
        "cracks" to cracks,
        "manholes" to manholes,
        "timestamp" to timestamp,
        "status" to status
    )
}

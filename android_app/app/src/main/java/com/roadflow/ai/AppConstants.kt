package com.roadflow.ai

/**
 * App-wide constants.
 */
object AppConstants {
    /**
     * Firebase Realtime Database URL.
     * Required for non-US region databases (asia-southeast1 in this case).
     * FirebaseDatabase.getInstance() without a URL defaults to US — which won't work.
     */
    const val FIREBASE_DB_URL = "https://roadflow-ai-3b57a-default-rtdb.asia-southeast1.firebasedatabase.app/"
}

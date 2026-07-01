package com.roadflow.ai

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * RoadFlowApp — Application entry point.
 *
 * Initializes Firebase manually from BuildConfig values (which are sourced from
 * the gitignored `.env` file at build time). This lets the project ship WITHOUT
 * a committed `google-services.json`, keeping all credentials out of source control.
 */
class RoadFlowApp : Application() {

    companion object {
        private const val TAG = "RoadFlowApp"
    }

    override fun onCreate() {
        super.onCreate()
        initFirebase()
    }

    private fun initFirebase() {
        // Skip if already initialized, or if secrets are missing (e.g. no .env yet).
        if (FirebaseApp.getApps(this).isNotEmpty()) return
        if (BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank()) {
            Log.w(TAG, "Firebase not initialized: missing FIREBASE_API_KEY/FIREBASE_APP_ID. " +
                    "Copy .env.example to .env and fill in your Firebase values.")
            return
        }

        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_PROJECT_NUMBER)
            .setDatabaseUrl(BuildConfig.FIREBASE_DB_URL)
            .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            .build()

        FirebaseApp.initializeApp(this, options)
        Log.d(TAG, "Firebase initialized from BuildConfig (.env)")
    }
}

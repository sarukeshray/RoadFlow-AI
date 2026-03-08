package com.roadflow.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * LoginActivity — Placeholder for Driver/Officer authentication.
 *
 * Provides demo buttons for both Driver and Officer flows.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Demo: go to Officer Dashboard
        findViewById<MaterialButton>(R.id.btnContinueOfficer).setOnClickListener {
            startActivity(Intent(this, OfficerDashboardActivity::class.java))
        }

        // Demo: go to Driver Navigation
        findViewById<MaterialButton>(R.id.btnContinueDriver).setOnClickListener {
            startActivity(Intent(this, DriverNavigationActivity::class.java))
        }
    }
}

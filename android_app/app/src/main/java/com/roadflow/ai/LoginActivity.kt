package com.roadflow.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * LoginActivity — Placeholder for Driver/Officer authentication.
 *
 * Currently provides a "Continue as Officer (Demo)" button
 * that skips auth and goes directly to the dashboard.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Demo: go straight to officer dashboard
        findViewById<MaterialButton>(R.id.btnContinueOfficer).setOnClickListener {
            startActivity(Intent(this, OfficerDashboardActivity::class.java))
        }
    }
}

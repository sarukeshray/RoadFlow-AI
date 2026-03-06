package com.roadflow.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * MainActivity — Welcome / Role Selection Screen.
 *
 * Three user personas:
 *   1. Driver (Navigation) — Login required
 *   2. PWD Officer (Dashboard) — Login required
 *   3. Citizen (Anonymous Reporting) — No login needed
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Button 1: Login as Driver / Officer
        findViewById<MaterialButton>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // Button 2: Report a Damage (Citizen)
        findViewById<MaterialButton>(R.id.btnReportDamage).setOnClickListener {
            startActivity(Intent(this, CitizenReportActivity::class.java))
        }

        // Live Scanner quick access (for testing)
        findViewById<MaterialButton>(R.id.btnLiveScanner).setOnClickListener {
            startActivity(Intent(this, LiveScannerActivity::class.java))
        }
    }
}

package com.frameworkstudios.autotapper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusAccessibility: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnLaunchControls: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusOverlay = findViewById(R.id.statusOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnLaunchControls = findViewById(R.id.btnLaunchControls)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnLaunchControls.setOnClickListener {
            startService(Intent(this, OverlayService::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)

        statusAccessibility.text =
            "1. Accessibility service: ${if (accessibilityEnabled) "ON" else "OFF"}"
        statusOverlay.text =
            "2. Overlay permission: ${if (overlayEnabled) "ON" else "OFF"}"

        btnLaunchControls.isEnabled = accessibilityEnabled && overlayEnabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${AutoTapAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}

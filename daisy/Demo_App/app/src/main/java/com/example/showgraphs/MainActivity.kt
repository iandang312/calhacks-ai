package com.example.showgraphs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var orb: DaisyOrbView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshSetupStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestSetupPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    private fun buildUi() {
        val orbSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            220f,
            resources.displayMetrics,
        ).toInt()

        orb = DaisyOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSizePx, orbSizePx)
            setState(DaisyState.STANDBY)
        }

        statusText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    24f,
                    resources.displayMetrics,
                ).toInt()
            }
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.caption_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(48, 0, 48, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            addView(orb)
            addView(statusText)
        }
        setContentView(root)
    }

    private fun requestSetupPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            refreshSetupStatus()
        }
    }

    private fun refreshSetupStatus() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val accessibility = DaisyAccessibilityService.isEnabled()

        when {
            !mic -> {
                statusText.text = "Allow microphone access so Daisy can always listen."
                orb.setState(DaisyState.STANDBY)
            }
            !overlay -> {
                statusText.text = "Allow overlay permission so Daisy can appear over other apps."
                openOverlaySettings()
            }
            !accessibility -> {
                statusText.text = "Enable Daisy accessibility to control apps like Uber.\nTap here after enabling."
                statusText.setOnClickListener { openAccessibilitySettings() }
            }
            else -> {
                statusText.text = "Daisy is listening.\nSay \"Hi Daisy\" anytime."
                statusText.setOnClickListener(null)
                orb.setState(DaisyState.STANDBY)
                startDaisyService()
            }
        }
    }

    private fun startDaisyService() {
        val intent = Intent(this, DaisyService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

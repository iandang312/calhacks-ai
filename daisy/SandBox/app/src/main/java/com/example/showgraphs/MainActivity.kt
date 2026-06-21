package com.example.showgraphs

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var setupButton: Button
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

        setupButton = Button(this).apply {
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
            visibility = View.GONE
            setOnClickListener { grantNextMissingPermission() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            addView(orb)
            addView(statusText)
            addView(setupButton)
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
        when (firstMissingPermission()) {
            Permission.MIC -> showSetupStep(
                "Daisy needs a few permissions to get started.",
                "Allow microphone",
            )
            Permission.OVERLAY -> showSetupStep(
                "Microphone is on. Next, let Daisy appear over other apps.",
                "Allow display over apps",
            )
            Permission.ACCESSIBILITY -> showSetupStep(
                "Almost there. Enable Daisy accessibility to control apps.",
                "Enable accessibility",
            )
            null -> {
                statusText.text = "Daisy is listening.\nSay \"Hey Daisy\" anytime."
                setupButton.visibility = View.GONE
                orb.setState(DaisyState.STANDBY)
                startDaisyService()
            }
        }
    }

    private fun showSetupStep(status: String, buttonLabel: String) {
        statusText.text = status
        orb.setState(DaisyState.STANDBY)
        setupButton.text = buttonLabel
        setupButton.visibility = View.VISIBLE
    }

    /** Sends the user to grant the next permission that's still missing. */
    private fun grantNextMissingPermission() {
        when (firstMissingPermission()) {
            Permission.MIC -> requestSetupPermissions()
            Permission.OVERLAY -> openOverlaySettings()
            Permission.ACCESSIBILITY -> openAccessibilitySettings()
            null -> refreshSetupStatus()
        }
    }

    private enum class Permission { MIC, OVERLAY, ACCESSIBILITY }

    /** The first permission still needed, in setup order, or null if all granted. */
    private fun firstMissingPermission(): Permission? {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val accessibility = isAccessibilityEnabled()
        return when {
            !mic -> Permission.MIC
            !overlay -> Permission.OVERLAY
            !accessibility -> Permission.ACCESSIBILITY
            else -> null
        }
    }

    /**
     * Whether the user has enabled Daisy's accessibility service in system
     * settings. Reads the secure setting directly rather than relying on the live
     * service instance ([DaisyAccessibilityService.isEnabled]) — on a fresh app
     * process the system rebinds the service slightly after [onResume] runs, so
     * the instance can still be null here even though it's genuinely enabled.
     */
    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, DaisyAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
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

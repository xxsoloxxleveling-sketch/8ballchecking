package com.pool.guideline.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pool.guideline.overlay.cv.TableFeltPreset
import com.pool.guideline.overlay.databinding.ActivityMainBinding
import com.pool.guideline.overlay.service.OverlayService

/**
 * Setup and permission gating activity for the AI Pool Guideline Overlay.
 * Manages SYSTEM_ALERT_WINDOW permission and Android 14+ MediaProjection authorization.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    // Felt preset selection
    private var selectedPreset = TableFeltPreset.AUTO
    private var selectedBounces = 3
    private var selectedAlpha = 0.35f
    private var showDebug = false

    // MediaProjection screen recording launcher
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayServiceWithToken(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            updateUiState()
        }
    }

    // Overlay permission launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupControls()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUiState()
    }

    private fun checkPermissions(): Boolean {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        binding.layoutPermissionWarning.visibility = if (canDraw) View.GONE else View.VISIBLE
        return canDraw
    }

    private fun setupControls() {
        // Felt preset spinner setup
        val presets = TableFeltPreset.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
        binding.spinnerFeltPreset.adapter = adapter
        binding.spinnerFeltPreset.setSelection(TableFeltPreset.AUTO.ordinal)

        // Bounces SeekBar
        binding.sbBounces.progress = selectedBounces
        binding.tvBouncesLabel.text = "Max Cushion Bounces: $selectedBounces"

        // Smoothing SeekBar
        binding.sbSmoothing.progress = (selectedAlpha * 100).toInt()
        binding.tvSmoothingLabel.text = "EMA Smoothing Factor: \u03B1 = ${String.format("%.2f", selectedAlpha)}"
    }

    private fun setupListeners() {
        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }

        binding.btnToggleOverlay.setOnClickListener {
            if (OverlayService.isRunning) {
                stopOverlayService()
            } else {
                if (checkPermissions()) {
                    requestScreenCapture()
                } else {
                    Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.spinnerFeltPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPreset = TableFeltPreset.values()[position]
                sendConfigUpdate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.sbBounces.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedBounces = progress.coerceIn(0, 4)
                binding.tvBouncesLabel.text = "Max Cushion Bounces: $selectedBounces"
                sendConfigUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbSmoothing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedAlpha = (progress / 100f).coerceIn(0.05f, 1.0f)
                binding.tvSmoothingLabel.text = "EMA Smoothing Factor: \u03B1 = ${String.format("%.2f", selectedAlpha)}"
                sendConfigUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchDebugBounds.setOnCheckedChangeListener { _, isChecked ->
            showDebug = isChecked
            sendConfigUpdate()
        }
    }

    private fun requestScreenCapture() {
        // Android 14+ Sequence: Start foreground service first before obtaining projection token
        val startServiceIntent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, startServiceIntent)

        // Launch projection prompt
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startOverlayServiceWithToken(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        sendConfigUpdate()
        updateUiState()
        Toast.makeText(this, "AI Guideline Overlay Started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(stopIntent)
        updateUiState()
        Toast.makeText(this, "Overlay Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun sendConfigUpdate() {
        if (!OverlayService.isRunning) return

        val configIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_CONFIG
            putExtra(OverlayService.EXTRA_FELT_PRESET, selectedPreset.name)
            putExtra(OverlayService.EXTRA_MAX_BOUNCES, selectedBounces)
            putExtra(OverlayService.EXTRA_SHOW_DEBUG, showDebug)
            putExtra(OverlayService.EXTRA_SMOOTHING_ALPHA, selectedAlpha)
        }
        startService(configIntent)
    }

    private fun updateUiState() {
        val running = OverlayService.isRunning
        if (running) {
            binding.tvStatus.text = getString(R.string.status_active)
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.btnToggleOverlay.text = getString(R.string.btn_stop)
        } else {
            binding.tvStatus.text = getString(R.string.status_inactive)
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
            binding.btnToggleOverlay.text = getString(R.string.btn_start)
        }
    }
}

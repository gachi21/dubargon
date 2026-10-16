package com.dubdu.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var spProvider: Spinner
    private lateinit var swUseMicFallback: Switch
    private lateinit var swDuckAudio: Switch
    private lateinit var btnToggleDub: Button
    private lateinit var tvStatus: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startDubbingService(result.resultCode, result.data)
        } else {
            Toast.makeText(this, "مجوز ضبط صدای صفحه داده نشد", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        spProvider = findViewById(R.id.spProvider)
        swUseMicFallback = findViewById(R.id.swUseMicFallback)
        swDuckAudio = findViewById(R.id.swDuckAudio)
        btnToggleDub = findViewById(R.id.btnToggleDub)
        tvStatus = findViewById(R.id.tvStatus)

        val providers = arrayOf("Gemini 2.0 Flash (توصیه شده - سریع)", "OpenAI (Whisper + GPT)")
        spProvider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)

        val prefs = getSharedPreferences("dubdu_prefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        spProvider.setSelection(prefs.getInt("provider_idx", 0))

        requestPermissionsIfNeeded()

        btnToggleDub.setOnClickListener {
            if (DubbingService.isRunning) {
                stopService(Intent(this, DubbingService::class.java))
                updateUiState(false)
            } else {
                val apiKey = etApiKey.text.toString().trim()
                if (apiKey.isEmpty()) {
                    Toast.makeText(this, "لطفاً کلید API را وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("api_key", apiKey)
                    .putInt("provider_idx", spProvider.selectedItemPosition)
                    .apply()

                if (swUseMicFallback.isChecked) {
                    // شروع مستقیم با میکروفون (بدون نیاز به MediaProjection)
                    startDubbingService(0, null)
                } else {
                    // درخواست مجوز ضبط صدای داخلی اندروید ۱۰+
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
        }
    }

    private fun startDubbingService(resultCode:Int, data: Intent?) {
        val intent = Intent(this, DubbingService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
            putExtra("API_KEY", etApiKey.text.toString().trim())
            putExtra("PROVIDER", spProvider.selectedItemPosition)
            putExtra("USE_MIC", swUseMicFallback.isChecked)
            putExtra("DUCK_AUDIO", swDuckAudio.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
        updateUiState(true)
    }

    private fun updateUiState(running: Boolean) {
        if (running) {
            btnToggleDub.text = "توقف دوبله زنده"
            btnToggleDub.setBackgroundColor(0xFFC62828.toInt())
            tvStatus.text = "وضعیت: در حال دریافت صدا و دوبله به فارسی...\nاکنون وارد اینستاگرام، تیک‌تاک یا یوتیوب شوید."
        } else {
            btnToggleDub.text = "شروع دوبله زنده فارسی"
            btnToggleDub.setBackgroundColor(0xFF2E7D32.toInt())
            tvStatus.text = "وضعیت: متوقف شد"
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }
}

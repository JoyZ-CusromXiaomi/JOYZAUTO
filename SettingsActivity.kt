package com.joyz.autoscrolljoy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.joyz.autoscrolljoy.databinding.ActivitySettingsBinding

/**
 * Pengaturan: sensitivitas kepala, kecepatan & kehalusan scroll, durasi timer, kata kunci suara.
 * Semua perubahan disimpan langsung dan dibaca service secara real-time.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        val prefs = ScrollPrefs.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Sensitivitas gerakan kepala
        bindSlider(
            binding.sliderEye, binding.tvEyeValue, prefs.eyeSensitivity,
            format = { getString(R.string.value_ratio, it) }
        ) { prefs.eyeSensitivity = it }

        // Kecepatan & kehalusan scroll
        bindSlider(
            binding.sliderSpeed, binding.tvSpeedValue, prefs.scrollSpeed,
            format = { getString(R.string.value_ratio, it) }
        ) { prefs.scrollSpeed = it }

        bindSlider(
            binding.sliderSmooth, binding.tvSmoothValue, prefs.smoothness / 10 * 10,
            format = { getString(R.string.value_percent, it) }
        ) { prefs.smoothness = it }

        // Timer
        bindSlider(
            binding.sliderTimer, binding.tvTimerValue, prefs.timerSeconds,
            format = { getString(R.string.value_seconds, it) }
        ) { prefs.timerSeconds = it }

        binding.swJitter.isChecked = prefs.timerJitter
        binding.swJitter.setOnCheckedChangeListener { _, checked -> prefs.timerJitter = checked }

        // Kata kunci suara
        binding.etKeywords.setText(prefs.voiceKeywords)
        binding.etKeywords.doAfterTextChanged { prefs.voiceKeywords = it?.toString().orEmpty() }

        // Umum
        binding.swSupportedOnly.isChecked = prefs.supportedOnly
        binding.swSupportedOnly.setOnCheckedChangeListener { _, checked ->
            prefs.supportedOnly = checked
        }

        binding.btnTest.setOnClickListener { runTest() }
    }

    private fun bindSlider(
        slider: Slider,
        label: TextView,
        initial: Int,
        format: (Int) -> String,
        onChanged: (Int) -> Unit
    ) {
        slider.value = initial.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        label.text = format(initial)
        slider.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            label.text = format(v)
            if (fromUser) onChanged(v)
        }
    }

    /** Scroll satu kali setelah 3 detik, memberi waktu pindah ke aplikasi video pendek. */
    private fun runTest() {
        if (!ScrollBridge.isConnected) {
            Snackbar.make(binding.root, R.string.test_need_service, Snackbar.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.test_toast, Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed(
            { ScrollBridge.requestScroll("test", force = true) },
            3000L
        )
    }
}

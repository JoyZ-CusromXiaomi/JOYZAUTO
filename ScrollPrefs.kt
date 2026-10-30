package com.joyz.autoscrolljoy

import android.content.Context
import android.content.SharedPreferences

/**
 * Semua pengaturan aplikasi dalam satu tempat (SharedPreferences).
 * Dibaca langsung oleh service, jadi perubahan di layar Pengaturan berlaku seketika.
 */
class ScrollPrefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Sakelar utama. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) { sp.edit().putBoolean(KEY_ENABLED, value).apply() }

    var modeEye: Boolean
        get() = sp.getBoolean(KEY_MODE_EYE, false)
        set(value) { sp.edit().putBoolean(KEY_MODE_EYE, value).apply() }

    var modeTimer: Boolean
        get() = sp.getBoolean(KEY_MODE_TIMER, false)
        set(value) { sp.edit().putBoolean(KEY_MODE_TIMER, value).apply() }

    var modeVoice: Boolean
        get() = sp.getBoolean(KEY_MODE_VOICE, false)
        set(value) { sp.edit().putBoolean(KEY_MODE_VOICE, value).apply() }

    /** 1..10. Makin tinggi = dongakan kepala yang dibutuhkan makin kecil. */
    var eyeSensitivity: Int
        get() = sp.getInt(KEY_EYE_SENSITIVITY, 5).coerceIn(1, 10)
        set(value) { sp.edit().putInt(KEY_EYE_SENSITIVITY, value.coerceIn(1, 10)).apply() }

    /** 1..10. Makin tinggi = usapan makin cepat. */
    var scrollSpeed: Int
        get() = sp.getInt(KEY_SCROLL_SPEED, 5).coerceIn(1, 10)
        set(value) { sp.edit().putInt(KEY_SCROLL_SPEED, value.coerceIn(1, 10)).apply() }

    /** 0..100. Makin tinggi = jalur usapan makin melengkung & bervariasi (lebih natural). */
    var smoothness: Int
        get() = sp.getInt(KEY_SMOOTHNESS, 60).coerceIn(0, 100)
        set(value) { sp.edit().putInt(KEY_SMOOTHNESS, value.coerceIn(0, 100)).apply() }

    /** Durasi tonton per video untuk mode timer (detik). */
    var timerSeconds: Int
        get() = sp.getInt(KEY_TIMER_SECONDS, 15).coerceIn(3, 120)
        set(value) { sp.edit().putInt(KEY_TIMER_SECONDS, value.coerceIn(3, 120)).apply() }

    var timerJitter: Boolean
        get() = sp.getBoolean(KEY_TIMER_JITTER, true)
        set(value) { sp.edit().putBoolean(KEY_TIMER_JITTER, value).apply() }

    /** Kata kunci suara mentah, dipisah koma. */
    var voiceKeywords: String
        get() = sp.getString(KEY_VOICE_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(value) { sp.edit().putString(KEY_VOICE_KEYWORDS, value).apply() }

    /** Bila true, scroll hanya dijalankan saat aplikasi video pendek yang sedang terbuka. */
    var supportedOnly: Boolean
        get() = sp.getBoolean(KEY_SUPPORTED_ONLY, true)
        set(value) { sp.edit().putBoolean(KEY_SUPPORTED_ONLY, value).apply() }

    /** Daftar kata kunci yang sudah dibersihkan (huruf kecil, tanpa duplikat). */
    val keywordList: List<String>
        get() = voiceKeywords
            .split(',', ';', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Sudut dongak (derajat) yang dibutuhkan. Sensitivitas 1 -> 22°, 10 -> 8°. */
    fun eyeThresholdDegrees(): Float = 22f - (eyeSensitivity - 1) * (14f / 9f)

    /** Durasi satu usapan (ms). Kecepatan 1 -> ~1015 ms, 10 -> 250 ms. */
    fun swipeDurationMs(): Long = (1100 - scrollSpeed * 85).toLong()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val FILE = "auto_scroll_joy_prefs"

        const val KEY_ENABLED = "enabled"
        const val KEY_MODE_EYE = "mode_eye"
        const val KEY_MODE_TIMER = "mode_timer"
        const val KEY_MODE_VOICE = "mode_voice"
        const val KEY_EYE_SENSITIVITY = "eye_sensitivity"
        const val KEY_SCROLL_SPEED = "scroll_speed"
        const val KEY_SMOOTHNESS = "smoothness"
        const val KEY_TIMER_SECONDS = "timer_seconds"
        const val KEY_TIMER_JITTER = "timer_jitter"
        const val KEY_VOICE_KEYWORDS = "voice_keywords"
        const val KEY_SUPPORTED_ONLY = "supported_only"

        const val DEFAULT_KEYWORDS = "scroll, next, b"

        @Volatile
        private var instance: ScrollPrefs? = null

        fun get(context: Context): ScrollPrefs =
            instance ?: synchronized(this) {
                instance ?: ScrollPrefs(context).also { instance = it }
            }
    }
}

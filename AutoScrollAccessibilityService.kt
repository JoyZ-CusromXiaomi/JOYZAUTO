package com.joyz.autoscrolljoy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

/** Paket aplikasi video pendek yang didukung. Tambahkan paket lain di sini bila perlu. */
object SupportedApps {
    val PACKAGES: Set<String> = setOf(
        // TikTok (global, regional, Douyin)
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.ss.android.ugc.aweme",
        // Instagram (Reels)
        "com.instagram.android",
        "com.instagram.lite",
        // YouTube (Shorts)
        "com.google.android.youtube",
        // Facebook (Reels)
        "com.facebook.katana",
        "com.facebook.lite",
        // Snapchat (Spotlight)
        "com.snapchat.android",
        // Kwai
        "com.kwai.video"
    )
}

/**
 * Layanan aksesibilitas yang mensimulasikan usapan layar ke atas (scroll ke video berikutnya).
 *
 * Layanan ini ringan: tidak memproses event layar sama sekali. Ia hanya
 *  1. menerima perintah scroll dari modul sensor / timer, lalu
 *  2. menjalankan satu gestur lewat dispatchGesture().
 *
 * Pemeriksaan sebelum scroll: sakelar utama, layar menyala, aplikasi di depan termasuk daftar
 * [SupportedApps] (bisa dimatikan), jeda minimum antar scroll, dan tidak ada gestur yang masih berjalan.
 */
class AutoScrollAccessibilityService : AccessibilityService() {

    private lateinit var prefs: ScrollPrefs
    private val handler = Handler(Looper.getMainLooper())

    private var lastScrollAt = 0L
    private var gestureStartedAt = 0L
    private var gestureInFlight = false

    /** Cadangan bila rootInActiveWindow bernilai null (mis. saat transisi layar). */
    private var lastEventPackage: String? = null

    private val timerRunnable = Runnable { onTimerTick() }

    // Harus disimpan sebagai properti: SharedPreferences hanya menyimpan referensi lemah.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            ScrollPrefs.KEY_ENABLED,
            ScrollPrefs.KEY_MODE_TIMER,
            ScrollPrefs.KEY_TIMER_SECONDS,
            ScrollPrefs.KEY_TIMER_JITTER -> rescheduleTimer()
            else -> Unit
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = ScrollPrefs.get(this)
        prefs.registerListener(prefListener)
        ScrollBridge.attach(this)
        rescheduleTimer()
        Log.d(TAG, "Layanan aksesibilitas tersambung")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Hanya mencatat paket jendela terakhir sebagai cadangan. Tidak membaca isi layar.
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.systemui") lastEventPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterListener(prefListener)
        ScrollBridge.detach(this)
    }

    // ---------------------------------------------------------------------------------------------
    // Permintaan scroll
    // ---------------------------------------------------------------------------------------------

    /** Dipanggil dari modul sensor, timer, atau tombol uji. Aman dari thread mana pun. */
    fun requestScroll(source: String, force: Boolean = false) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performScroll(source, force)
        } else {
            handler.post { performScroll(source, force) }
        }
    }

    private fun performScroll(source: String, force: Boolean) {
        if (!force) {
            if (!prefs.enabled) return
            if (prefs.supportedOnly && !isSupportedAppInForeground()) {
                Log.d(TAG, "Scroll ($source) dilewati: bukan aplikasi video pendek")
                return
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return // layar mati

        val now = SystemClock.elapsedRealtime()
        if (gestureInFlight && now - gestureStartedAt < GESTURE_STUCK_MS) return
        if (now - lastScrollAt < MIN_INTERVAL_MS) return
        lastScrollAt = now

        Log.d(TAG, "Scroll dari: $source")
        dispatchSwipeUp()
    }

    @Suppress("DEPRECATION")
    private fun isSupportedAppInForeground(): Boolean {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: lastEventPackage
        root?.recycle()
        return pkg != null && pkg in SupportedApps.PACKAGES
    }

    // ---------------------------------------------------------------------------------------------
    // Gestur usap ke atas
    // ---------------------------------------------------------------------------------------------

    /**
     * Satu usapan ke atas dengan jalur melengkung halus.
     *
     * - Kecepatan (1..10) menentukan durasi usapan: 250 ms (cepat) sampai ~1 detik (pelan).
     * - Kehalusan (0..100) menentukan kelengkungan jalur dan variasi titik awal, supaya gerakan
     *   terasa seperti jari manusia dan tidak lurus seperti robot.
     * - Titik awal di tengah layar (jauh dari tombol suka/komentar di tepi kanan) dan jarak usapan
     *   ~44% tinggi layar, cukup untuk berpindah satu video tanpa memicu gestur lain.
     */
    private fun dispatchSwipeUp() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val smooth = prefs.smoothness / 100f

        val startX = w * (0.5f + (Random.nextFloat() - 0.5f) * 0.08f * (0.3f + smooth))
        val startY = h * 0.74f
        val endY = h * 0.30f
        val bow = w * 0.06f * smooth * (if (Random.nextBoolean()) 1f else -1f)
        val endX = startX + bow * 0.3f

        val path = Path().apply {
            moveTo(startX, startY)
            // Titik kontrol digeser ke samping: lengkungan maksimum ~ nilai bow di tengah jalur.
            quadTo(startX + bow * 2f, (startY + endY) / 2f, endX, endY)
        }

        val duration = prefs.swipeDurationMs().coerceAtLeast(120L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        gestureInFlight = true
        gestureStartedAt = SystemClock.elapsedRealtime()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    Log.d(TAG, "Gestur dibatalkan sistem")
                }
            },
            handler
        )

        if (!dispatched) {
            gestureInFlight = false
            Log.w(TAG, "dispatchGesture ditolak sistem")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mode timer (estimasi durasi video)
    // ---------------------------------------------------------------------------------------------

    private fun rescheduleTimer() {
        handler.removeCallbacks(timerRunnable)
        if (prefs.enabled && prefs.modeTimer) {
            handler.postDelayed(timerRunnable, nextTimerDelayMs())
        }
    }

    private fun nextTimerDelayMs(): Long {
        val base = prefs.timerSeconds * 1000L
        val factor = if (prefs.timerJitter) 0.8 + Random.nextDouble() * 0.4 else 1.0
        return (base * factor).toLong()
    }

    private fun onTimerTick() {
        performScroll("timer", force = false)
        rescheduleTimer()
    }

    private companion object {
        const val TAG = "AutoScrollJoy"

        /** Jeda minimum antar scroll dari sumber mana pun, mencegah scroll ganda. */
        const val MIN_INTERVAL_MS = 1200L

        /** Bila callback gestur tidak pernah datang, anggap gestur macet setelah waktu ini. */
        const val GESTURE_STUCK_MS = 3000L
    }
}
